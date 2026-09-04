package com.deathbyvegemite.platewatch.core.tracking

import kotlin.math.abs

/** One frame's worth of "there is a plate here", from the analyser. */
data class PlateObservation(
    val timestampMs: Long,
    /** Where the plate is in the frame *as currently zoomed*. */
    val box: NormalizedBox,
    /** The zoom ratio the frame was captured at. */
    val zoomRatio: Float,
)

/**
 * Where a plate is and how it is moving, in **1× equivalent** units.
 *
 * Zoom is a pure magnification about the frame centre, so dividing every offset and
 * size by the zoom ratio gives numbers that do not change when the zoom does. That
 * is what makes velocity and growth estimates meaningful across a zoom change —
 * without it, zooming in would look exactly like the car lunging towards you.
 */
data class TrackState(
    /** Horizontal offset of the plate centre from the frame centre, at 1×. */
    val offsetX: Float,
    /** Vertical offset of the plate centre from the frame centre, at 1×. */
    val offsetY: Float,
    /** Plate height as a fraction of frame height, at 1×. */
    val height: Float,
    /** Offset velocity, frame-widths per second at 1×. */
    val velocityX: Float,
    val velocityY: Float,
    /** Relative growth of height per second: 0.5 means "50 % taller each second". */
    val growthRate: Float,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val observations: Int,
) {
    /** How tall the plate appears at [zoom], as a fraction of frame height. */
    fun apparentHeight(zoom: Float): Float = height * zoom

    /** Plate centre in frame-normalised coordinates at [zoom]. */
    fun apparentCenter(zoom: Float): Pair<Float, Float> = (0.5f + offsetX * zoom) to (0.5f + offsetY * zoom)

    /** Larger of the two offsets — the one that decides whether zoom pushes the plate out. */
    val maxOffset: Float get() = maxOf(abs(offsetX), abs(offsetY))
}

data class TrackerConfig(
    /** Two observations further apart than this are different cars. */
    val maxGapMs: Long = 700,
    /** Weight of the newest velocity sample, 0..1. Lower is smoother and laggier. */
    val smoothing: Float = 0.5f,
)

/**
 * Follows a single plate across frames.
 *
 * One plate, not many: the zoom is a single global control, so there is no point
 * tracking a second car while the first has the lens. The analyser hands over its
 * best candidate per frame and this decides whether it is the same car as last time.
 */
class PlateTracker(private val config: TrackerConfig = TrackerConfig()) {

    private var state: TrackState? = null

    fun observe(observation: PlateObservation): TrackState {
        val zoom = observation.zoomRatio.coerceAtLeast(1f)
        val offsetX = (observation.box.centerX - 0.5f) / zoom
        val offsetY = (observation.box.centerY - 0.5f) / zoom
        val height = (observation.box.height / zoom).coerceAtLeast(MIN_HEIGHT)

        val previous = state?.takeIf { observation.timestampMs - it.lastSeenMs <= config.maxGapMs }
        val next = if (previous == null) {
            TrackState(
                offsetX = offsetX, offsetY = offsetY, height = height,
                velocityX = 0f, velocityY = 0f, growthRate = 0f,
                firstSeenMs = observation.timestampMs, lastSeenMs = observation.timestampMs,
                observations = 1,
            )
        } else {
            val dt = (observation.timestampMs - previous.lastSeenMs).coerceAtLeast(1L) / 1000f
            val a = config.smoothing
            fun blend(fresh: Float, old: Float) = a * fresh + (1f - a) * old
            TrackState(
                offsetX = offsetX, offsetY = offsetY, height = height,
                velocityX = blend((offsetX - previous.offsetX) / dt, previous.velocityX),
                velocityY = blend((offsetY - previous.offsetY) / dt, previous.velocityY),
                growthRate = blend((height - previous.height) / dt / previous.height, previous.growthRate),
                firstSeenMs = previous.firstSeenMs, lastSeenMs = observation.timestampMs,
                observations = previous.observations + 1,
            )
        }
        state = next
        return next
    }

    /** The live track, or `null` once it has gone stale. */
    fun current(nowMs: Long, staleAfterMs: Long = config.maxGapMs): TrackState? =
        state?.takeIf { nowMs - it.lastSeenMs <= staleAfterMs }

    fun reset() {
        state = null
    }

    private companion object {
        const val MIN_HEIGHT = 1e-4f
    }
}
