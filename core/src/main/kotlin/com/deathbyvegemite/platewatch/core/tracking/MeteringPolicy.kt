package com.deathbyvegemite.platewatch.core.tracking

import kotlin.math.abs

sealed interface MeteringDecision {
    /** Point the camera's focus and exposure at this frame-normalised upright point. */
    data class Meter(val x: Float, val y: Float) : MeteringDecision

    /** Let the camera go back to its own metering. */
    data object Cancel : MeteringDecision

    /** Nothing to change. */
    data object Hold : MeteringDecision
}

data class MeteringPolicyConfig(
    /** Re-meter when the plate has moved this far since the last point. */
    val minMove: Float = 0.08f,
    /** Re-meter this often regardless, so the point stays fresh as distance changes. */
    val refreshMs: Long = 1_500,
)

/**
 * Decides when to point focus and exposure at the plate.
 *
 * This is the single biggest image-quality lever for plates and costs nothing: a
 * plate is retro-reflective, so under headlights it is the brightest thing in the
 * frame and the camera's default metering blows it out to a white slab. Metering
 * on the plate itself exposes for the characters instead. Autofocus on the plate
 * rather than the road ahead helps for the same reason.
 *
 * Re-issuing the request every frame would keep the lens hunting, so it is
 * throttled to real movement or a refresh interval.
 */
class MeteringPolicy(private val config: MeteringPolicyConfig = MeteringPolicyConfig()) {

    private var lastX: Float? = null
    private var lastY: Float? = null
    private var lastMs: Long = 0

    fun decide(track: TrackState?, zoom: Float, nowMs: Long): MeteringDecision {
        if (track == null) {
            if (lastX == null) return MeteringDecision.Hold
            lastX = null; lastY = null
            return MeteringDecision.Cancel
        }

        val (x, y) = track.apparentCenter(zoom)
        val cx = x.coerceIn(0f, 1f)
        val cy = y.coerceIn(0f, 1f)

        val moved = lastX?.let { abs(cx - it) > config.minMove || abs(cy - lastY!!) > config.minMove } ?: true
        val stale = nowMs - lastMs > config.refreshMs
        if (!moved && !stale) return MeteringDecision.Hold

        lastX = cx; lastY = cy; lastMs = nowMs
        return MeteringDecision.Meter(cx, cy)
    }

    fun reset() {
        lastX = null; lastY = null; lastMs = 0
    }
}
