package com.deathbyvegemite.platewatch.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.util.Log
import com.deathbyvegemite.platewatch.core.tracking.FrameGeometry
import com.deathbyvegemite.platewatch.core.tracking.NormalizedBox
import kotlin.math.roundToInt

/**
 * Cuts a region out of a full-resolution still without decoding the whole thing.
 *
 * A 12-megapixel frame is ~48 MB as a bitmap, which is a fine way to get killed for
 * memory in a long session. [BitmapRegionDecoder] decodes only the rectangle asked
 * for, so a plate crop costs a few hundred kilobytes however large the still is.
 */
object HiResCropper {

    /**
     * @param box frame-normalised *upright* region, e.g. from the last analyser frame
     */
    fun crop(still: StillImage, box: NormalizedBox): Bitmap? {
        if (box.isEmpty()) return null
        return try {
            // The JPEG buffer is in sensor orientation; the box is upright. Map it over.
            val sensor = FrameGeometry.uprightToSensor(box, still.rotationDegrees)
            val rect = Rect(
                (sensor.left * still.width).roundToInt().coerceIn(0, still.width - 1),
                (sensor.top * still.height).roundToInt().coerceIn(0, still.height - 1),
                (sensor.right * still.width).roundToInt().coerceIn(1, still.width),
                (sensor.bottom * still.height).roundToInt().coerceIn(1, still.height),
            )
            if (rect.width() < MIN_PX || rect.height() < MIN_PX) return null

            @Suppress("DEPRECATION")
            val decoder = BitmapRegionDecoder.newInstance(still.jpeg, 0, still.jpeg.size, false)
                ?: return null
            val raw = try {
                decoder.decodeRegion(rect, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
            } finally {
                decoder.recycle()
            }
            if (raw == null || still.rotationDegrees == 0) return raw

            val matrix = Matrix().apply { postRotate(still.rotationDegrees.toFloat()) }
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
                .also { if (it != raw) raw.recycle() }
        } catch (e: Exception) {
            Log.w(TAG, "High-resolution crop failed", e)
            null
        }
    }

    private const val TAG = "HiResCropper"
    private const val MIN_PX = 16
}
