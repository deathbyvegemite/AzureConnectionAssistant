package com.deathbyvegemite.platewatch.capture

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.deathbyvegemite.platewatch.core.tracking.NormalizedBox
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.ObjectDetector

/** One vehicle-shaped thing the detector found, in frame-normalised upright coordinates. */
data class VehicleDetection(val box: NormalizedBox, val label: String, val score: Float)

/**
 * Confirms there is actually a vehicle in frame before any text found near it is
 * trusted as a plate.
 *
 * This is a different job from anything [com.deathbyvegemite.platewatch.core.plate.PlateTextParser]
 * does. The parser is fooled by text that *looks* like a plate — it only ever sees
 * character classes, never where the text came from. This is fooled by nothing (or
 * something else) being read as though it were a plate at all: a caption in a
 * dashcam-compilation video playing on a phone screen, a search box, a road sign.
 * None of that is plate-shaped text near a vehicle, so confirming "is a vehicle even
 * here" throws all of it out before the parser gets a vote.
 *
 * Runs [EfficientDet-Lite0](https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/int8/1/efficientdet_lite0.tflite)
 * (Apache 2.0, ~4.4 MB), bundled in `assets/` so nothing is downloaded at runtime. It
 * is a general 90-class COCO detector; only the vehicle classes are kept, and the
 * label of the best-scoring one becomes a sighting's body type for free.
 */
class VehicleDetector(context: Context, minScore: Float = 0.35f, maxResults: Int = 6) {

    private val detector: ObjectDetector? = try {
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder().setNumThreads(NUM_THREADS).build())
            .setScoreThreshold(minScore)
            .setMaxResults(maxResults)
            .build()
        ObjectDetector.createFromFileAndOptions(context, MODEL_ASSET, options)
    } catch (e: Exception) {
        Log.w(TAG, "Vehicle detector unavailable; the vehicle-presence gate will stay off", e)
        null
    }

    /**
     * True once construction has actually been attempted and the model loaded.
     * Checking this — rather than trusting the caller's setting alone — is what stops
     * a missing or corrupt model asset from silently gating out every plate forever:
     * see [com.deathbyvegemite.platewatch.ui.capture.CaptureViewModel] for how the
     * gate falls back to off, not to "no vehicle ever found", when this is false.
     */
    val isAvailable: Boolean get() = detector != null

    /** @param upright a bitmap already rotated the right way up */
    fun detect(upright: Bitmap): List<VehicleDetection> {
        val d = detector ?: return emptyList()
        return try {
            d.detect(TensorImage.fromBitmap(upright))
                .flatMap { result ->
                    val box = result.boundingBox
                    result.categories
                        .filter { it.label in VEHICLE_LABELS }
                        .map { category ->
                            VehicleDetection(
                                box = NormalizedBox.fromPixels(
                                    box.left.toInt(), box.top.toInt(), box.right.toInt(), box.bottom.toInt(),
                                    upright.width, upright.height,
                                ),
                                label = category.label,
                                score = category.score,
                            )
                        }
                }
        } catch (e: Exception) {
            Log.w(TAG, "Vehicle detection failed on this frame", e)
            emptyList()
        }
    }

    fun close() {
        detector?.close()
    }

    private companion object {
        const val TAG = "VehicleDetector"
        const val MODEL_ASSET = "efficientdet_lite0.tflite"
        const val NUM_THREADS = 2
        val VEHICLE_LABELS = setOf("car", "truck", "bus", "motorcycle")
    }
}
