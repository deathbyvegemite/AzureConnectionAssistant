package com.deathbyvegemite.platewatch.ui.capture

import android.app.Application
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.deathbyvegemite.platewatch.PlateWatchApp
import com.deathbyvegemite.platewatch.capture.Alerts
import com.deathbyvegemite.platewatch.capture.AnalyzerConfig
import com.deathbyvegemite.platewatch.capture.CameraControls
import com.deathbyvegemite.platewatch.capture.HiResCropper
import com.deathbyvegemite.platewatch.capture.PlateFrameResult
import com.deathbyvegemite.platewatch.capture.VehicleDetector
import com.deathbyvegemite.platewatch.core.plate.PlateRegions
import com.deathbyvegemite.platewatch.core.plate.PlateTextParser
import com.deathbyvegemite.platewatch.core.sighting.AggregateResult
import com.deathbyvegemite.platewatch.core.sighting.PlateReading
import com.deathbyvegemite.platewatch.core.sighting.SightingAggregator
import com.deathbyvegemite.platewatch.core.tab.TabColorCycle
import com.deathbyvegemite.platewatch.core.tab.TabExpiry
import com.deathbyvegemite.platewatch.core.tab.TabStatus
import com.deathbyvegemite.platewatch.core.tab.TabTextParser
import com.deathbyvegemite.platewatch.core.tracking.CropGeometry
import com.deathbyvegemite.platewatch.core.tracking.MeteringDecision
import com.deathbyvegemite.platewatch.core.tracking.MeteringPolicy
import com.deathbyvegemite.platewatch.core.tracking.NormalizedBox
import com.deathbyvegemite.platewatch.core.tracking.PlateObservation
import com.deathbyvegemite.platewatch.core.tracking.PlateTracker
import com.deathbyvegemite.platewatch.core.tracking.ZoomPolicy
import com.deathbyvegemite.platewatch.core.tracking.ZoomPolicyConfig
import com.deathbyvegemite.platewatch.data.db.SightingEntity
import com.deathbyvegemite.platewatch.data.prefs.CaptureSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.abs

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
    /** The zoom the camera is at right now, whoever set it. */
    val zoomRatio: Float = 1f,
    /** True while a plate is being followed. */
    val tracking: Boolean = false,
)

/**
 * Owns the run loop: frames in, sightings out.
 *
 * Every frame result is funnelled through a single channel and consumed by one
 * coroutine, so the aggregator is only ever touched from one place and a confirmed
 * plate's database id is always attached before the next frame can reinforce it.
 *
 * It also runs the camera-steering loop. Observations from the analyser update a
 * tracker; the zoom and metering policies read the track and the resulting requests
 * go to the camera through [CameraControls]. A 250 ms ticker runs the same policies
 * when no frames are arriving, which is what releases the zoom once a plate is gone.
 */
class CaptureViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as PlateWatchApp).container
    private val repository = container.repository
    private val alerts = Alerts(application)

    private val _uiState = MutableStateFlow(CaptureUiState())
    val uiState: StateFlow<CaptureUiState> = _uiState.asStateFlow()

    /** Dropping the oldest frame is right: a stale frame is worth less than a fresh one. */
    private val frames = Channel<PlateFrameResult>(capacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Second, lighter stream: every plausible plate box, for steering the camera. */
    private val observations =
        Channel<Pair<PlateObservation, Int>>(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    @Volatile
    private var settings = CaptureSettings()

    @Volatile
    private var parser = PlateTextParser(PlateRegions.byId(settings.regionId))

    private var aggregator = SightingAggregator(settings.toAggregatorConfig())
    private var watchlist: Set<String> = emptySet()

    private val tracker = PlateTracker()
    private var zoomPolicy = ZoomPolicy(ZoomPolicyConfig(maxZoom = settings.maxAutoZoom))
    private val meteringPolicy = MeteringPolicy()
    private var controls: CameraControls? = null
    private var tickerJob: Job? = null

    /**
     * Built once, on first use, not per config call — loading the model is real
     * work and [analyzerConfig] runs on every analysed frame. `isInitialized()`
     * lets [onCleared] release it without forcing that load if the gate was never
     * turned on for this session.
     */
    private val vehicleDetectorLazy = lazy { VehicleDetector(application) }
    private val vehicleDetector by vehicleDetectorLazy

    @Volatile
    private var currentZoom = 1f
    private var lastRotationDegrees = 0

    init {
        viewModelScope.launch {
            container.settingsStore.settings.collect { next ->
                val regionChanged = next.regionId != settings.regionId
                val tuningChanged = next.toAggregatorConfig() != settings.toAggregatorConfig()
                val zoomChanged = next.maxAutoZoom != settings.maxAutoZoom
                val evChanged = next.exposureBias != settings.exposureBias
                settings = next
                if (regionChanged) parser = PlateTextParser(PlateRegions.byId(next.regionId))
                if (regionChanged || tuningChanged) aggregator = SightingAggregator(next.toAggregatorConfig())
                if (zoomChanged) zoomPolicy = ZoomPolicy(ZoomPolicyConfig(maxZoom = next.maxAutoZoom))
                if (evChanged) controls?.setExposureCompensation(next.exposureBias)
                if (!next.autoZoom && currentZoom != 1f) onManualZoom(1f)
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
        viewModelScope.launch { consumeObservations() }
    }

    /** Supplies the analyser with current settings; called once per frame. */
    fun analyzerConfig(): AnalyzerConfig = AnalyzerConfig(
        parser = parser,
        frameIntervalMs = settings.frameIntervalMs,
        minFrameScore = settings.minFrameScore,
        wantCrops = settings.savePhotos,
        tabParser = if (settings.readTabs) TabTextParser(LocalDate.now().year) else null,
        zoomRatio = currentZoom,
        // Checking isAvailable, not just the setting, matters: if the model asset
        // is ever missing or fails to load, this falls back to the gate being off
        // rather than to "no vehicle ever found" — which would silently stop the
        // app from logging anything at all.
        vehicleDetector = if (settings.requireVehicleDetection && vehicleDetector.isAvailable) vehicleDetector else null,
    )

    /** Called from the camera analysis thread. Must not block. */
    fun onFrameResult(result: PlateFrameResult) {
        frames.trySend(result)
    }

    /** Called from the camera analysis thread for every plausible plate box. */
    fun onObservation(observation: PlateObservation, rotationDegrees: Int) {
        observations.trySend(observation to rotationDegrees)
    }

    /** The screen hands over the bound camera; `null` when it is unbound. */
    fun attachControls(newControls: CameraControls?) {
        controls = newControls
        newControls?.let {
            it.setExposureCompensation(settings.exposureBias)
            it.setZoomRatio(1f)
        }
        currentZoom = 1f
        _uiState.update { it.copy(zoomRatio = 1f, tracking = false) }
    }

    /** Manual zoom from the slider, used when automatic zoom is switched off. */
    fun onManualZoom(ratio: Float) {
        val c = controls ?: return
        val clamped = ratio.coerceIn(1f, c.maxZoomRatio.coerceAtLeast(1f))
        c.setZoomRatio(clamped)
        currentZoom = clamped
        _uiState.update { it.copy(zoomRatio = clamped) }
    }

    fun onCaptureStarted() {
        container.locationTracker.start()
        _uiState.update { it.copy(running = true, sessionCount = 0, recent = emptyList()) }
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (isActive) {
                delay(TICK_MS)
                applyPolicies(System.currentTimeMillis())
            }
        }
    }

    fun onCaptureStopped() {
        tickerJob?.cancel()
        tickerJob = null
        container.locationTracker.stop()
        aggregator.reset()
        tracker.reset()
        meteringPolicy.reset()
        controls?.cancelMetering()
        controls?.setZoomRatio(1f)
        currentZoom = 1f
        _uiState.update { it.copy(running = false, pendingPlates = emptyList(), zoomRatio = 1f, tracking = false) }
    }

    fun dismissWatchlistAlert() = _uiState.update { it.copy(watchlistAlert = null) }

    private suspend fun consumeObservations() {
        for ((observation, rotation) in observations) {
            tracker.observe(observation)
            lastRotationDegrees = rotation
            applyPolicies(observation.timestampMs)
        }
    }

    /**
     * One pass of the steering loop. Runs on the main dispatcher from both the
     * observation consumer and the ticker, so the tracker and policies are only ever
     * touched from one thread.
     */
    private fun applyPolicies(nowMs: Long) {
        val c = controls ?: return
        if (!_uiState.value.running) return
        val track = tracker.current(nowMs, RELEASE_AFTER_MS)

        if (settings.autoZoom) {
            val target = zoomPolicy.decide(track, currentZoom)
                .coerceIn(1f, c.maxZoomRatio.coerceAtLeast(1f))
            if (abs(target - currentZoom) > 1e-3f) {
                c.setZoomRatio(target)
                currentZoom = target
            }
        }

        if (settings.plateMetering) {
            when (val decision = meteringPolicy.decide(track, currentZoom, nowMs)) {
                is MeteringDecision.Meter -> c.meterAt(decision.x, decision.y, lastRotationDegrees)
                MeteringDecision.Cancel -> c.cancelMetering()
                MeteringDecision.Hold -> Unit
            }
        }

        val tracking = track != null
        val state = _uiState.value
        if (state.zoomRatio != currentZoom || state.tracking != tracking) {
            _uiState.update { it.copy(zoomRatio = currentZoom, tracking = tracking) }
        }
    }

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
            vehicleBodyType = frame.bodyType,
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

        if (settings.hiResStills && settings.savePhotos) {
            frame.plateBox?.let { box -> captureHiResCrops(id, stamp, box) }
        }
    }

    /**
     * The live frame that confirmed a plate is a 1080p-ish analysis frame. A full
     * resolution still taken a moment later gives a crop with several times the
     * pixels across the plate — the difference between "probably a 7" and a 7.
     *
     * The car keeps moving between the two, so the plate box is padded generously
     * before it is applied to the still.
     */
    private fun captureHiResCrops(id: Long, stamp: String, box: NormalizedBox) {
        val c = controls ?: return
        c.captureStill { still ->
            if (still == null) return@captureStill
            viewModelScope.launch(Dispatchers.Default) {
                val plate = HiResCropper.crop(still, CropGeometry.plate(box, padding = HI_RES_PLATE_PADDING))
                val vehicle = HiResCropper.crop(still, CropGeometry.vehicle(box))
                val platePath = plate?.let { container.photos.save(it, "${stamp}_plate_hires", quality = 92) }
                val vehiclePath = vehicle?.let { container.photos.save(it, "${stamp}_vehicle_hires", quality = 88) }
                plate?.recycle()
                vehicle?.recycle()
                if (platePath != null || vehiclePath != null) {
                    repository.replaceCrops(id, platePath, vehiclePath)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        tickerJob?.cancel()
        container.locationTracker.stop()
        frames.close()
        observations.close()
        if (vehicleDetectorLazy.isInitialized()) vehicleDetector.close()
    }

    private companion object {
        const val MAX_RECENT = 8
        const val TICK_MS = 250L
        /** How long after the last sighting the zoom lets go and returns to 1×. */
        const val RELEASE_AFTER_MS = 500L
        /** Extra margin on the still crop, for the distance the car covers meanwhile. */
        const val HI_RES_PLATE_PADDING = 0.4f
    }
}
