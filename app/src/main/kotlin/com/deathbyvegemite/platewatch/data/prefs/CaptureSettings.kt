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

    /** Analysis stream size: "720p", "1080p" or "2160p". */
    val analysisResolution: String = "1080p",
    /** Follow a detected plate and zoom towards it when that would help the read. */
    val autoZoom: Boolean = true,
    /**
     * Ceiling for automatic zoom. 2.5× keeps a Galaxy S25 Ultra on its main sensor;
     * past ~3× it switches lenses, which costs a refocus at the worst moment.
     */
    val maxAutoZoom: Float = 2.5f,
    /** Point focus and exposure at the plate rather than the road. */
    val plateMetering: Boolean = true,
    /** Exposure compensation in camera steps; negative tames retro-reflective glare. */
    val exposureBias: Int = 0,
    /** Take a full-resolution still when a plate is confirmed and keep that crop. */
    val hiResStills: Boolean = true,

    /**
     * Confirm an actual vehicle is in frame before any text near it is trusted as a
     * plate. Without this, anything that produces plate-shaped text — a caption in a
     * video playing on a phone screen propped in view, a road sign, a search box —
     * can be logged as a real plate. On by default; the cost is a vehicle-detector
     * pass on every analysed frame, which costs real cycles per second in exchange
     * for not logging things that were never a plate.
     */
    val requireVehicleDetection: Boolean = true,
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
