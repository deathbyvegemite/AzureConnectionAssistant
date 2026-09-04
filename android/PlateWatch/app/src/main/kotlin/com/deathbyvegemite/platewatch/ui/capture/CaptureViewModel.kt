package com.deathbyvegemite.platewatch.ui.capture

import android.app.Application
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.deathbyvegemite.platewatch.PlateWatchApp
import com.deathbyvegemite.platewatch.capture.Alerts
import com.deathbyvegemite.platewatch.capture.AnalyzerConfig
import com.deathbyvegemite.platewatch.capture.PlateFrameResult
import com.deathbyvegemite.platewatch.core.plate.PlateRegions
import com.deathbyvegemite.platewatch.core.plate.PlateTextParser
import com.deathbyvegemite.platewatch.core.sighting.AggregateResult
import com.deathbyvegemite.platewatch.core.sighting.PlateReading
import com.deathbyvegemite.platewatch.core.sighting.SightingAggregator
import com.deathbyvegemite.platewatch.core.tab.TabColorCycle
import com.deathbyvegemite.platewatch.core.tab.TabExpiry
import com.deathbyvegemite.platewatch.core.tab.TabStatus
import com.deathbyvegemite.platewatch.core.tab.TabTextParser
import com.deathbyvegemite.platewatch.data.db.SightingEntity
import com.deathbyvegemite.platewatch.data.prefs.CaptureSettings
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/** One plate this session put into the log, for the live strip on the capture screen. */
data class LoggedPlate(
    val sightingId: Long,
    val plate: String,
    val atEpochMs: Long,
    val color: String?,
    val confidence: Float,
    val onWatchlist: Boolean,
    val tabStatus: TabStatus = TabStatus.UNKNOWN,
)

data class CaptureUiState(
    val running: Boolean = false,
    val settings: CaptureSettings = CaptureSettings(),
    val pendingPlates: List<String> = emptyList(),
    val recent: List<LoggedPlate> = emptyList(),
    val sessionCount: Int = 0,
    val hasLocationFix: Boolean = false,
    val locationAccuracyMeters: Float? = null,
    val watchlistAlert: String? = null,
)

/**
 * Owns the run loop: frames in, sightings out.
 *
 * Every frame result is funnelled through a single channel and consumed by one
 * coroutine, so the aggregator is only ever touched from one place and a confirmed
 * plate's database id is always attached before the next frame can reinforce it.
 */
class CaptureViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as PlateWatchApp).container
    private val repository = container.repository
    private val alerts = Alerts(application)

    private val _uiState = MutableStateFlow(CaptureUiState())
    val uiState: StateFlow<CaptureUiState> = _uiState.asStateFlow()

    /** Dropping the oldest frame is right: a stale frame is worth less than a fresh one. */
    private val frames = Channel<PlateFrameResult>(capacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    @Volatile
    private var settings = CaptureSettings()

    @Volatile
    private var parser = PlateTextParser(PlateRegions.byId(settings.regionId))

    private var aggregator = SightingAggregator(settings.toAggregatorConfig())
    private var watchlist: Set<String> = emptySet()

    init {
        viewModelScope.launch {
            container.settingsStore.settings.collect { next ->
                val regionChanged = next.regionId != settings.regionId
                val tuningChanged = next.toAggregatorConfig() != settings.toAggregatorConfig()
                settings = next
                if (regionChanged) parser = PlateTextParser(PlateRegions.byId(next.regionId))
                if (regionChanged || tuningChanged) {
                    aggregator = SightingAggregator(next.toAggregatorConfig())
                }
                _uiState.update { it.copy(settings = next) }
            }
        }
        viewModelScope.launch {
            repository.observeWatchlistPlates().collect { plates -> watchlist = plates.toSet() }
        }
        viewModelScope.launch {
            container.locationTracker.location.collect { fix ->
                _uiState.update {
                    it.copy(
                        hasLocationFix = fix != null,
                        locationAccuracyMeters = fix?.takeIf { f -> f.hasAccuracy() }?.accuracy,
                    )
                }
            }
        }
        viewModelScope.launch { consumeFrames() }
    }

    /** Supplies the analyser with current settings; called once per frame. */
    fun analyzerConfig(): AnalyzerConfig = AnalyzerConfig(
        parser = parser,
        frameIntervalMs = settings.frameIntervalMs,
        minFrameScore = settings.minFrameScore,
        wantCrops = settings.savePhotos,
        tabParser = if (settings.readTabs) TabTextParser(LocalDate.now().year) else null,
    )

    /** Called from the camera analysis thread. Must not block. */
    fun onFrameResult(result: PlateFrameResult) {
        frames.trySend(result)
    }

    fun onCaptureStarted() {
        container.locationTracker.start()
        _uiState.update { it.copy(running = true, sessionCount = 0, recent = emptyList()) }
    }

    fun onCaptureStopped() {
        container.locationTracker.stop()
        aggregator.reset()
        _uiState.update { it.copy(running = false, pendingPlates = emptyList()) }
    }

    fun dismissWatchlistAlert() = _uiState.update { it.copy(watchlistAlert = null) }

    private suspend fun consumeFrames() {
        for (frame in frames) {
            try {
                handleFrame(frame)
            } finally {
                // Crops are compressed to disk inside handleFrame, so by here the
                // pixels have served their purpose either way.
                frame.plateCrop?.takeIf { !it.isRecycled }?.recycle()
                frame.vehicleCrop?.takeIf { !it.isRecycled }?.recycle()
            }
        }
    }

    private suspend fun handleFrame(frame: PlateFrameResult) {
        val fix = container.locationTracker.location.value
        val now = System.currentTimeMillis()

        val reading = PlateReading(
            plate = frame.candidate.plate,
            raw = frame.candidate.raw,
            formatId = frame.candidate.formatId,
            score = frame.candidate.score,
            timestampMs = now,
            latitude = fix?.latitude,
            longitude = fix?.longitude,
        )

        when (val result = aggregator.offer(reading)) {
            null -> Unit

            is AggregateResult.Reinforced -> {
                result.sightingId?.let {
                    repository.reinforce(it, result.lastSeenMs, result.readCount, result.confidence)
                }
            }

            is AggregateResult.Confirmed -> commit(result, frame, fix, now)
        }

        // Only publish when it actually changed; this runs several times a second.
        val pending = aggregator.pendingPlates()
        if (pending != _uiState.value.pendingPlates) {
            _uiState.update { it.copy(pendingPlates = pending) }
        }
    }

    private suspend fun commit(
        result: AggregateResult.Confirmed,
        frame: PlateFrameResult,
        fix: Location?,
        nowMs: Long,
    ) {
        val stamp = "${result.plate}_$nowMs"
        val platePath = frame.plateCrop
            ?.takeIf { settings.savePhotos }
            ?.let { container.photos.save(it, "${stamp}_plate") }
        val vehiclePath = frame.vehicleCrop
            ?.takeIf { settings.savePhotos }
            ?.let { container.photos.save(it, "${stamp}_vehicle") }

        val today = LocalDate.now()
        val tabStatus = TabExpiry.evaluate(
            reading = frame.tabReading,
            nowYear = today.year,
            nowMonth = today.monthValue,
        )
        val colorCheck = TabColorCycle.checkConsistency(frame.tabReading?.year, frame.tabColorName)

        val entity = SightingEntity(
            plate = result.plate,
            rawPlate = result.reading.raw,
            regionId = settings.regionId,
            formatId = result.reading.formatId,
            confidence = result.confidence,
            readCount = result.readCount,
            firstSeenEpochMs = result.firstSeenMs,
            lastSeenEpochMs = nowMs,
            latitude = fix?.latitude,
            longitude = fix?.longitude,
            accuracyMeters = fix?.takeIf { it.hasAccuracy() }?.accuracy,
            speedMps = fix?.takeIf { it.hasSpeed() }?.speed,
            bearingDegrees = fix?.takeIf { it.hasBearing() }?.bearing,
            address = null,
            vehicleColor = frame.colorName,
            tabMonth = frame.tabReading?.month,
            tabYear = frame.tabReading?.year,
            tabStatus = tabStatus.name,
            tabColor = frame.tabColorName,
            tabColorMismatch = when (colorCheck) {
                TabColorCycle.Consistency.MISMATCH -> true
                TabColorCycle.Consistency.CONSISTENT -> false
                TabColorCycle.Consistency.UNKNOWN -> null
            },
            plateImagePath = platePath,
            vehicleImagePath = vehiclePath,
        )

        val id = repository.record(entity)
        aggregator.attachSightingId(result.plate, id)

        val onWatchlist = result.plate in watchlist
        val expiredTab = tabStatus == TabStatus.EXPIRED && settings.alertOnExpiredTab
        if ((onWatchlist && settings.alertOnWatchlist) || expiredTab) {
            alerts.watchlistHit()
        } else {
            alerts.logged()
        }

        _uiState.update { state ->
            state.copy(
                sessionCount = state.sessionCount + 1,
                watchlistAlert = if (onWatchlist) result.plate else state.watchlistAlert,
                recent = (
                    listOf(
                        LoggedPlate(
                            sightingId = id,
                            plate = result.plate,
                            atEpochMs = nowMs,
                            color = frame.colorName,
                            confidence = result.confidence,
                            onWatchlist = onWatchlist,
                            tabStatus = tabStatus,
                        ),
                    ) + state.recent
                    ).take(MAX_RECENT),
            )
        }

        if (settings.resolveAddresses && fix != null) {
            // Fire and forget: a sighting is complete without an address, and the
            // lookup needs a network round trip we should not make the driver wait for.
            viewModelScope.launch {
                container.reverseGeocoder.addressFor(fix.latitude, fix.longitude)
                    ?.let { repository.setAddress(id, it) }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        container.locationTracker.stop()
        frames.close()
    }

    private companion object {
        const val MAX_RECENT = 8
    }
}
