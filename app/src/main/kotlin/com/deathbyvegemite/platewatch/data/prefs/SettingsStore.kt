package com.deathbyvegemite.platewatch.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.preferencesStore: DataStore<Preferences> by
    preferencesDataStore(name = "platewatch_settings")

class SettingsStore(private val context: Context) {

    val settings: Flow<CaptureSettings> = context.preferencesStore.data.map(::read)

    /** Read-modify-write, so callers can say `update { it.copy(analysisFps = 8) }`. */
    suspend fun update(transform: (CaptureSettings) -> CaptureSettings) {
        context.preferencesStore.edit { p -> write(p, transform(read(p))) }
    }

    private fun read(p: Preferences): CaptureSettings {
        val d = CaptureSettings()
        return CaptureSettings(
            regionId = p[Keys.REGION] ?: d.regionId,
            minConfirmations = p[Keys.MIN_CONFIRMATIONS] ?: d.minConfirmations,
            confirmWindowSeconds = p[Keys.CONFIRM_WINDOW] ?: d.confirmWindowSeconds,
            dedupWindowSeconds = p[Keys.DEDUP_WINDOW] ?: d.dedupWindowSeconds,
            dedupRadiusMeters = p[Keys.DEDUP_RADIUS] ?: d.dedupRadiusMeters,
            fuzzyMerge = p[Keys.FUZZY_MERGE] ?: d.fuzzyMerge,
            analysisFps = p[Keys.ANALYSIS_FPS] ?: d.analysisFps,
            minFrameScore = p[Keys.MIN_FRAME_SCORE] ?: d.minFrameScore,
            savePhotos = p[Keys.SAVE_PHOTOS] ?: d.savePhotos,
            retentionDays = p[Keys.RETENTION_DAYS] ?: d.retentionDays,
            keepScreenOn = p[Keys.KEEP_SCREEN_ON] ?: d.keepScreenOn,
            resolveAddresses = p[Keys.RESOLVE_ADDRESSES] ?: d.resolveAddresses,
            alertOnWatchlist = p[Keys.ALERT_WATCHLIST] ?: d.alertOnWatchlist,
        )
    }

    private fun write(p: androidx.datastore.preferences.core.MutablePreferences, s: CaptureSettings) {
        p[Keys.REGION] = s.regionId
        p[Keys.MIN_CONFIRMATIONS] = s.minConfirmations
        p[Keys.CONFIRM_WINDOW] = s.confirmWindowSeconds
        p[Keys.DEDUP_WINDOW] = s.dedupWindowSeconds
        p[Keys.DEDUP_RADIUS] = s.dedupRadiusMeters
        p[Keys.FUZZY_MERGE] = s.fuzzyMerge
        p[Keys.ANALYSIS_FPS] = s.analysisFps
        p[Keys.MIN_FRAME_SCORE] = s.minFrameScore
        p[Keys.SAVE_PHOTOS] = s.savePhotos
        p[Keys.RETENTION_DAYS] = s.retentionDays
        p[Keys.KEEP_SCREEN_ON] = s.keepScreenOn
        p[Keys.RESOLVE_ADDRESSES] = s.resolveAddresses
        p[Keys.ALERT_WATCHLIST] = s.alertOnWatchlist
    }

    private object Keys {
        val REGION = stringPreferencesKey("region")
        val MIN_CONFIRMATIONS = intPreferencesKey("min_confirmations")
        val CONFIRM_WINDOW = intPreferencesKey("confirm_window_seconds")
        val DEDUP_WINDOW = intPreferencesKey("dedup_window_seconds")
        val DEDUP_RADIUS = intPreferencesKey("dedup_radius_meters")
        val FUZZY_MERGE = booleanPreferencesKey("fuzzy_merge")
        val ANALYSIS_FPS = intPreferencesKey("analysis_fps")
        val MIN_FRAME_SCORE = floatPreferencesKey("min_frame_score")
        val SAVE_PHOTOS = booleanPreferencesKey("save_photos")
        val RETENTION_DAYS = intPreferencesKey("retention_days")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val RESOLVE_ADDRESSES = booleanPreferencesKey("resolve_addresses")
        val ALERT_WATCHLIST = booleanPreferencesKey("alert_watchlist")
    }
}
