package com.deathbyvegemite.platewatch.core.tracking

import kotlin.math.abs

data class ZoomPolicyConfig(
    /**
     * The plate height, as a fraction of frame height, that reads best. Matches the
     * analyser's notion of an ideal read; there is no benefit in zooming past it.
     */
    val idealPlateHeight: Float = 0.07f,
    /** Hard ceiling. See [ZoomPolicy] for why this defaults where it does. */
    val maxZoom: Float = 2.5f,
    /** Keep the plate at least this far, in frame-normalised units, from any edge. */
    val edgeMargin: Float = 0.12f,
    /** How long a zoom change takes to land. The plate keeps moving in the meantime. */
    val actuationLatencyMs: Long = 300,
    /** Plates crossing the frame faster than this, at the target zoom, are left alone. */
    val maxLateralSpeed: Float = 0.6f,
    /** Largest single step when zooming in. Zooming out is never limited. */
    val maxStepIn: Float = 0.5f,
    /** Changes smaller than this are not worth the refocus they cause. */
    val hysteresis: Float = 0.15f,
    /** Motion cannot be estimated from one frame, so wait for this many. */
    val minObservations: Int = 2,
    /**
     * Where the zoom sits when nothing is being tracked — both the level a capture
     * session starts at and the level it settles back to once a plate is lost. A
     * stationary camera watching one spot can sit zoomed in here with nothing to
     * lose; a phone in a moving car should keep this near 1× so nothing beside the
     * one plate already being read is missed. Independent of [maxZoom]: this is
     * where zoom *rests*, not how far it is allowed to *reach*.
     */
    val baseZoom: Float = 1f,
)

/**
 * Decides how far to zoom, given where a plate is and how it is moving.
 *
 * The intuition "see a plate, zoom in on it" is wrong more often than right from a
 * moving car, and this policy exists to say no in those cases:
 *
 *  - **Zoom is about the frame centre and the mount cannot pan.** A plate off to one
 *    side is pushed *out* of the frame by zooming, not brought closer. The most zoom
 *    that keeps a plate at offset *d* in frame is `(0.5 − margin) / d`, and the policy
 *    never exceeds it.
 *  - **The plate is still moving while the lens reacts.** The keep-in-frame test is
 *    run against where the plate will be after [ZoomPolicyConfig.actuationLatencyMs],
 *    not where it was.
 *  - **Cross traffic is gone before the zoom lands.** Apparent lateral speed scales
 *    with zoom, so a plate crossing the frame is only zoomed as far as keeps its
 *    apparent speed under a threshold — usually not at all.
 *  - **Zoom in slowly, zoom out instantly.** Zooming in is rate-limited so a bad
 *    decision costs little; zooming out is never limited, because being zoomed in
 *    on nothing is the expensive state — every other car in the frame is lost.
 *
 * The default ceiling and resting zoom are conservative starting points, not fixed
 * limits — both are configurable (see [ZoomPolicyConfig.maxZoom] and
 * [ZoomPolicyConfig.baseZoom]). On a Galaxy S25 Ultra specifically: up to about 3×
 * the phone serves a crop from the 200-megapixel main sensor, which is sharp and
 * changes nothing else about capture. Past that it switches to the telephoto lens,
 * which costs a refocus and an exposure change — a few hundred milliseconds of
 * unreadable frames right as a zoom-in crosses that boundary. Some phones' telephoto
 * lenses hold a lock well past that point regardless; [maxZoom] is deliberately not
 * hard-capped in code at 2.5× so a ceiling like 10× is a legitimate choice where the
 * hardware supports it — the trade is paid once per zoom-in, not continuously.
 */
class ZoomPolicy(private val config: ZoomPolicyConfig = ZoomPolicyConfig()) {

    /**
     * @param track    the plate being followed, or `null` if there is none
     * @param current  the zoom ratio the camera is at now
     * @return the zoom ratio to request; equal to [current] when nothing should change
     */
    fun decide(track: TrackState?, current: Float): Float {
        if (track == null) return config.baseZoom
        if (track.observations < config.minObservations) return current

        val latency = config.actuationLatencyMs / 1000f
        val predictedX = track.offsetX + track.velocityX * latency
        val predictedY = track.offsetY + track.velocityY * latency
        val predictedOffset = maxOf(abs(predictedX), abs(predictedY))

        val keepInFrame = if (predictedOffset < 1e-3f) config.maxZoom
        else (0.5f - config.edgeMargin) / predictedOffset

        val idealZoom = config.idealPlateHeight / track.height

        // The floor here is the hardware minimum, not the configured resting zoom:
        // an actively tracked plate that is already comfortably sized should be free
        // to zoom out below baseZoom, all the way to 1×, not get held above it. Only
        // the no-track early return above uses baseZoom.
        var target = minOf(idealZoom, keepInFrame, config.maxZoom).coerceAtLeast(HARDWARE_MIN_ZOOM)

        val speed = maxOf(abs(track.velocityX), abs(track.velocityY))
        if (speed > 1e-3f && speed * target > config.maxLateralSpeed) {
            target = minOf(target, config.maxLateralSpeed / speed).coerceAtLeast(HARDWARE_MIN_ZOOM)
        }

        return when {
            target > current -> {
                val stepped = minOf(target, current + config.maxStepIn)
                if (stepped - current < config.hysteresis) current else stepped
            }
            target < current ->
                if (current - target < config.hysteresis && target > HARDWARE_MIN_ZOOM) current else target
            else -> current
        }
    }

    private companion object {
        /** No zoom at all — the camera's own floor, independent of where it rests. */
        const val HARDWARE_MIN_ZOOM = 1f
    }
}
