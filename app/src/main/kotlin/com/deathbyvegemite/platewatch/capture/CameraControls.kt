package com.deathbyvegemite.platewatch.capture

/** A full-resolution still, as the camera produced it: JPEG bytes in sensor orientation. */
class StillImage(
    val jpeg: ByteArray,
    /** Degrees the buffer must be rotated to be upright. */
    val rotationDegrees: Int,
    val width: Int,
    val height: Int,
)

/**
 * The handful of camera controls the tracking layer drives.
 *
 * An interface rather than a CameraX type so the view model never touches CameraX
 * directly, and so the whole zoom/metering loop can be exercised with a fake.
 */
interface CameraControls {
    val maxZoomRatio: Float

    fun setZoomRatio(ratio: Float)

    /**
     * Point focus and exposure at a frame-normalised **upright** point. The
     * implementation maps it onto the sensor using [rotationDegrees].
     */
    fun meterAt(x: Float, y: Float, rotationDegrees: Int)

    fun cancelMetering()

    /** Exposure compensation in the camera's own index steps; clamped by the implementation. */
    fun setExposureCompensation(index: Int)

    /** Take a full-resolution still. [onResult] receives `null` on failure. */
    fun captureStill(onResult: (StillImage?) -> Unit)
}
