package com.deathbyvegemite.platewatch.core.tracking

/**
 * A rectangle in frame-normalised coordinates: `0..1` on both axes, origin top-left.
 *
 * Everything in the tracking layer works in these units so that it is independent
 * of stream resolution — a 720p analysis frame and a 12-megapixel still describe
 * the same plate with the same numbers.
 */
data class NormalizedBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun clamped(): NormalizedBox = NormalizedBox(
        left.coerceIn(0f, 1f), top.coerceIn(0f, 1f), right.coerceIn(0f, 1f), bottom.coerceIn(0f, 1f),
    )

    fun isEmpty(): Boolean = width <= 0f || height <= 0f

    companion object {
        fun fromPixels(left: Int, top: Int, right: Int, bottom: Int, frameWidth: Int, frameHeight: Int) =
            NormalizedBox(
                left / frameWidth.toFloat(), top / frameHeight.toFloat(),
                right / frameWidth.toFloat(), bottom / frameHeight.toFloat(),
            )
    }
}

/**
 * Maps between the *upright* frame the recogniser reports boxes in and the *sensor*
 * frame the camera hardware actually addresses.
 *
 * The recogniser is handed frames rotated to be upright, so its boxes are in upright
 * coordinates. Camera controls — metering regions, still-capture crops — address the
 * raw sensor buffer, which on a phone held in portrait is rotated 90° from upright.
 * Getting this wrong meters on the sky and crops the bumper.
 */
object FrameGeometry {

    /** Convert a normalised upright point to normalised sensor coordinates. */
    fun uprightToSensor(x: Float, y: Float, rotationDegrees: Int): Pair<Float, Float> =
        when (Math.floorMod(rotationDegrees, 360)) {
            0 -> x to y
            90 -> y to (1f - x)
            180 -> (1f - x) to (1f - y)
            270 -> (1f - y) to x
            else -> throw IllegalArgumentException("Rotation must be a multiple of 90°, got $rotationDegrees")
        }

    /** Convert a normalised upright box to its bounding box in sensor coordinates. */
    fun uprightToSensor(box: NormalizedBox, rotationDegrees: Int): NormalizedBox {
        val corners = listOf(
            uprightToSensor(box.left, box.top, rotationDegrees),
            uprightToSensor(box.right, box.top, rotationDegrees),
            uprightToSensor(box.left, box.bottom, rotationDegrees),
            uprightToSensor(box.right, box.bottom, rotationDegrees),
        )
        return NormalizedBox(
            left = corners.minOf { it.first },
            top = corners.minOf { it.second },
            right = corners.maxOf { it.first },
            bottom = corners.maxOf { it.second },
        )
    }
}

/**
 * The regions around a plate that the app cares about, expressed relative to the
 * plate's own box so they scale with distance.
 *
 * Shared by the live analyser (working on a 720p/1080p frame) and the high-resolution
 * still path (working on a 12-megapixel JPEG) so both cut exactly the same regions.
 */
object CropGeometry {

    /** The plate itself, padded so a slightly-off box still contains every character. */
    fun plate(box: NormalizedBox, padding: Float = 0.12f): NormalizedBox = NormalizedBox(
        box.left - box.width * padding,
        box.top - box.height * padding,
        box.right + box.width * padding,
        box.bottom + box.height * padding,
    ).clamped()

    /**
     * The bodywork directly above the plate — boot lid, badge, rear window. Wide
     * enough to recognise the vehicle by eye, and what the colour estimate samples.
     */
    fun vehicle(box: NormalizedBox): NormalizedBox {
        val halfWidth = box.width * VEHICLE_WIDTH_FACTOR / 2f
        return NormalizedBox(
            box.centerX - halfWidth,
            box.top - box.height * VEHICLE_TOP_FACTOR,
            box.centerX + halfWidth,
            box.top - box.height * VEHICLE_BOTTOM_FACTOR,
        ).clamped()
    }

    /** Upper-right corner of the plate, where Washington puts the registration tab. */
    fun tab(box: NormalizedBox): NormalizedBox = NormalizedBox(
        box.right - box.width * 0.10f,
        box.top - box.height * 0.90f,
        box.right + box.width * 0.28f,
        box.top + box.height * 0.15f,
    ).clamped()

    const val VEHICLE_WIDTH_FACTOR = 2.2f
    const val VEHICLE_TOP_FACTOR = 2.0f
    const val VEHICLE_BOTTOM_FACTOR = 0.35f
}
