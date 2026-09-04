package com.deathbyvegemite.platewatch.data.prefs

import com.deathbyvegemite.platewatch.core.sighting.AggregatorConfig

/**
 * Every knob that changes how aggressively the app reads and logs.
 *
 * Defaults are tuned to be conservative: it is much better to miss a plate than to
 * write down the wrong one, because a wrong plate in a neighbourhood-watch log is
 * worse than no plate at all.
 */
data class CaptureSettings(
    val regionId: String = "US",
    /** Frames that must agree before anything is written down. */
    val minConfirmations: Int = 3,
    val confirmWindowSeconds: Int = 6,
    val dedupWindowSeconds: Int = 120,
    val dedupRadiusMeters: Int = 150,
    /** Pool near-identical readings so one flipped character does not split a plate. */
    val fuzzyMerge: Boolean = true,
    /** Frames per second handed to the recogniser. Higher drains battery and heats the phone. */
    val analysisFps: Int = 5,
    /** Single-frame readings below this are discarded before they can vote. */
    val minFrameScore: Float = 0.55f,
    val savePhotos: Boolean = true,
    /** Sightings older than this are deleted automatically. 0 disables the purge. */
    val retentionDays: Int = 30,
    val keepScreenOn: Boolean = true,
    val resolveAddresses: Boolean = true,
    val alertOnWatchlist: Boolean = true,
    /** Read the expiry month and year printed on the registration tab. */
    val readTabs: Boolean = true,
    /** Alert like a watchlist hit when a tab reads as expired. */
    val alertOnExpiredTab: Boolean = false,
) {
    fun toAggregatorConfig(): AggregatorConfig = AggregatorConfig(
        minConfirmations = minConfirmations.coerceAtLeast(1),
        confirmWindowMs = confirmWindowSeconds * 1_000L,
        pendingTtlMs = (confirmWindowSeconds * 1_000L).coerceAtLeast(4_000L),
        dedupWindowMs = dedupWindowSeconds * 1_000L,
        dedupRadiusMeters = dedupRadiusMeters.toDouble(),
        fuzzyMerge = fuzzyMerge,
    )

    val frameIntervalMs: Long get() = 1_000L / analysisFps.coerceIn(1, 30)
}
