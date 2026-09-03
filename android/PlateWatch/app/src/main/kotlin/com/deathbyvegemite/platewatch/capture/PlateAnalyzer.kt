package com.deathbyvegemite.platewatch.capture

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.deathbyvegemite.platewatch.core.color.VehicleColor
import com.deathbyvegemite.platewatch.core.plate.PlateCandidate
import com.deathbyvegemite.platewatch.core.plate.PlateTextParser
import com.deathbyvegemite.platewatch.core.plate.RecognizedLine
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognizer
import java.util.concurrent.Executor
import kotlin.math.roundToInt

/** What the analyser needs to know, re-read every frame so Settings apply instantly. */
data class AnalyzerConfig(
    val parser: PlateTextParser,
    val frameIntervalMs: Long,
    val minFrameScore: Float,
    val wantCrops: Boolean,
)

/** One frame that produced a plausible plate. */
class PlateFrameResult(
    val candidate: PlateCandidate,
    val plateCrop: Bitmap?,
    val vehicleCrop: Bitmap?,
    val colorName: String?,
)

/**
 * Runs text recognition on camera frames and turns the results into plate candidates.
 *
 * Three things keep this cheap enough to run in a car for an hour:
 *  - frames are throttled to the configured rate, not analysed as fast as they arrive
 *  - the frame is only converted to a [Bitmap] when a plate has actually matched,
 *    which is a few frames a minute rather than every frame
 *  - every callback is dispatched onto [callbackExecutor]; ML Kit would otherwise
 *    run them on the main thread and rotating a 720p frame there visibly janks
 *    the preview
 */
class PlateAnalyzer(
    private val recognizer: TextRecognizer,
    private val callbackExecutor: Executor,
    private val config: () -> AnalyzerConfig,
    private val onResult: (PlateFrameResult) -> Unit,
) : ImageAnalysis.Analyzer {

    @Volatile
    private var lastAnalysisAt = 0L

    @OptIn(markerClass = [ExperimentalGetImage::class])
    override fun analyze(proxy: ImageProxy) {
        val settings = config()
        val now = SystemClock.elapsedRealtime()
        if (now - lastAnalysisAt < settings.frameIntervalMs) {
            proxy.close()
            return
        }
        val mediaImage = proxy.image
        if (mediaImage == null) {
            proxy.close()
            return
        }
        lastAnalysisAt = now

        val rotation = proxy.imageInfo.rotationDegrees
        // ML Kit reports boxes in the *upright* frame, so measure against upright dimensions.
        val uprightHeight = if (rotation % 180 == 0) proxy.height else proxy.width

        recognizer.process(InputImage.fromMediaImage(mediaImage, rotation))
            .addOnSuccessListener(callbackExecutor) { text ->
                runCatching { handle(text, proxy, rotation, uprightHeight, settings) }
                    .onFailure { Log.w(TAG, "Frame handling failed", it) }
            }
            .addOnFailureListener(callbackExecutor) { Log.d(TAG, "Recognition failed", it) }
            // Always last, and always runs: this is the only thing that frees the frame.
            .addOnCompleteListener(callbackExecutor) { proxy.close() }
    }

    private fun handle(
        text: Text,
        proxy: ImageProxy,
        rotation: Int,
        uprightHeight: Int,
        settings: AnalyzerConfig,
    ) {
        val match = bestMatch(text, uprightHeight, settings) ?: return
        if (match.candidate.score < settings.minFrameScore) return

        var plateCrop: Bitmap? = null
        var vehicleCrop: Bitmap? = null
        var colorName: String? = null

        val box = match.box
        if (box != null) {
            val upright = uprightBitmap(proxy, rotation)
            if (upright != null) {
                plateCrop = if (settings.wantCrops) crop(upright, expand(box, upright, 0.12f)) else null
                val vehicleRect = vehicleRect(box, upright)
                if (vehicleRect != null) {
                    if (settings.wantCrops) vehicleCrop = crop(upright, vehicleRect)
                    colorName = estimateColor(upright, vehicleRect)
                }
                if (upright != plateCrop && upright != vehicleCrop) upright.recycle()
            }
        }

        onResult(PlateFrameResult(match.candidate, plateCrop, vehicleCrop, colorName))
    }

    private class Match(val candidate: PlateCandidate, val box: Rect?)

    /**
     * Considers each text block as a whole and each of its lines separately: a plate
     * often lands as one block split into two lines, and the block text glues them
     * back together while the line box gives a tighter crop.
     */
    private fun bestMatch(text: Text, uprightHeight: Int, settings: AnalyzerConfig): Match? {
        var best: Match? = null

        fun consider(raw: String, box: Rect?) {
            if (raw.isBlank()) return
            val line = RecognizedLine(raw, confidenceFromSize(box, uprightHeight))
            val candidate = settings.parser.best(listOf(line)) ?: return
            if (best == null || candidate.score > best!!.candidate.score) {
                best = Match(candidate, box)
            }
        }

        for (block in text.textBlocks) {
            consider(block.text, block.boundingBox)
            for (line in block.lines) consider(line.text, line.boundingBox)
        }
        return best
    }

    /**
     * Stands in for a per-character confidence the recogniser does not expose:
     * text that fills more of the frame is closer, sharper and more trustworthy.
     */
    private fun confidenceFromSize(box: Rect?, uprightHeight: Int): Float {
        if (box == null || uprightHeight <= 0) return FALLBACK_CONFIDENCE
        val fraction = box.height().toFloat() / uprightHeight
        return (fraction / IDEAL_TEXT_HEIGHT_FRACTION).coerceIn(0.15f, 1f)
    }

    @OptIn(markerClass = [ExperimentalGetImage::class])
    private fun uprightBitmap(proxy: ImageProxy, rotation: Int): Bitmap? = try {
        val raw = proxy.toBitmap()
        if (rotation == 0) {
            raw
        } else {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
                .also { if (it != raw) raw.recycle() }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not convert frame to bitmap", e)
        null
    }

    private fun expand(box: Rect, within: Bitmap, fraction: Float): Rect {
        val dx = (box.width() * fraction).roundToInt()
        val dy = (box.height() * fraction).roundToInt()
        return Rect(box.left - dx, box.top - dy, box.right + dx, box.bottom + dy).clampTo(within)
    }

    /**
     * The bodywork sits directly above the plate. Sampling there gives a usable
     * colour on the great majority of cars, and a wide enough crop to recognise the
     * vehicle by eye later.
     */
    private fun vehicleRect(plate: Rect, within: Bitmap): Rect? {
        val centerX = plate.centerX()
        val halfWidth = (plate.width() * VEHICLE_WIDTH_FACTOR / 2f).roundToInt()
        val rect = Rect(
            centerX - halfWidth,
            plate.top - (plate.height() * VEHICLE_TOP_FACTOR).roundToInt(),
            centerX + halfWidth,
            plate.top - (plate.height() * VEHICLE_BOTTOM_FACTOR).roundToInt(),
        ).clampTo(within)
        return if (rect.width() < MIN_CROP_PX || rect.height() < MIN_CROP_PX) null else rect
    }

    private fun crop(source: Bitmap, rect: Rect): Bitmap? = try {
        if (rect.width() < MIN_CROP_PX || rect.height() < MIN_CROP_PX) null
        else Bitmap.createBitmap(source, rect.left, rect.top, rect.width(), rect.height())
    } catch (e: Exception) {
        Log.w(TAG, "Crop $rect failed", e)
        null
    }

    private fun estimateColor(source: Bitmap, rect: Rect): String? {
        val width = rect.width()
        val height = rect.height()
        if (width < MIN_CROP_PX || height < MIN_CROP_PX) return null
        return try {
            val pixels = IntArray(width * height)
            source.getPixels(pixels, 0, width, rect.left, rect.top, width, height)
            for (i in pixels.indices) pixels[i] = pixels[i] and 0xFFFFFF
            if (VehicleColor.isLowInformation(pixels)) null
            else VehicleColor.dominantColor(pixels)?.let(VehicleColor::name)
        } catch (e: Exception) {
            Log.w(TAG, "Colour sample failed", e)
            null
        }
    }

    private fun Rect.clampTo(bitmap: Bitmap) = Rect(
        left.coerceIn(0, bitmap.width),
        top.coerceIn(0, bitmap.height),
        right.coerceIn(0, bitmap.width),
        bottom.coerceIn(0, bitmap.height),
    )

    private companion object {
        const val TAG = "PlateAnalyzer"
        const val FALLBACK_CONFIDENCE = 0.5f
        /** A plate this tall relative to the frame is about as good as it gets. */
        const val IDEAL_TEXT_HEIGHT_FRACTION = 0.07f
        const val VEHICLE_WIDTH_FACTOR = 2.2f
        const val VEHICLE_TOP_FACTOR = 2.0f
        const val VEHICLE_BOTTOM_FACTOR = 0.35f
        const val MIN_CROP_PX = 8
    }
}
