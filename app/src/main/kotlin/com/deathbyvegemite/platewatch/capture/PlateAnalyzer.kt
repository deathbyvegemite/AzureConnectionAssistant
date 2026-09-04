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
import com.deathbyvegemite.platewatch.core.tab.TabReading
import com.deathbyvegemite.platewatch.core.tab.TabTextParser
import com.deathbyvegemite.platewatch.core.tracking.CropGeometry
import com.deathbyvegemite.platewatch.core.tracking.NormalizedBox
import com.deathbyvegemite.platewatch.core.tracking.PlateObservation
import com.deathbyvegemite.platewatch.core.tracking.VehicleGate
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
    /** Null disables registration tab reading entirely. */
    val tabParser: TabTextParser? = null,
    /** The zoom the camera is at, so the tracker can normalise geometry to 1×. */
    val zoomRatio: Float = 1f,
    /**
     * Weaker than [minFrameScore]: a reading not yet good enough to log is still
     * worth zooming towards, because zooming is how it becomes good enough.
     */
    val trackMinScore: Float = 0.30f,
    /**
     * Confirms a vehicle is actually in frame before any text near it is trusted as
     * a plate. Null disables the gate entirely: every plate-shaped run of text is
     * considered, exactly as before this existed.
     */
    val vehicleDetector: VehicleDetector? = null,
)

/** One frame that produced a plausible plate. */
class PlateFrameResult(
    val candidate: PlateCandidate,
    val plateCrop: Bitmap?,
    val vehicleCrop: Bitmap?,
    val colorName: String?,
    /** Month and year read off the registration tab as text, when legible. */
    val tabReading: TabReading? = null,
    /** Tab colour, kept only to corroborate [tabReading] — never to infer a year from. */
    val tabColorName: String? = null,
    /** Where the plate was, so a high-resolution still can be cropped to it. */
    val plateBox: NormalizedBox? = null,
    /** Body type from the vehicle detector, when the gate is on and confident enough. */
    val bodyType: String? = null,
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
 *
 * It also feeds the tracker: every frame with a plausible plate, however weak, is
 * reported through [onObservation] so zoom and metering can be steered towards it.
 *
 * When [AnalyzerConfig.vehicleDetector] is set, no plate-shaped text is trusted
 * unless it sits near an actual detected vehicle — see [VehicleGate]. That check
 * happens inside [handle], after ML Kit has already finished reading the frame, not
 * before. It would be cheaper to run the vehicle detector first and skip OCR
 * entirely when nothing is there, but that means building the upright [Bitmap] (via
 * `proxy.toBitmap()`) *before* handing the same underlying camera buffer to ML Kit's
 * `InputImage.fromMediaImage`, an ordering this analyser has never exercised and
 * cannot be verified without a device. Gating after the OCR call, in the exact
 * position the bitmap conversion already ran in, costs one extra text-recognition
 * pass on frames with no vehicle — the trade for not guessing at buffer-sharing
 * behaviour blind.
 */
class PlateAnalyzer(
    private val recognizer: TextRecognizer,
    private val callbackExecutor: Executor,
    private val config: () -> AnalyzerConfig,
    private val onResult: (PlateFrameResult) -> Unit,
    private val onObservation: (observation: PlateObservation, rotationDegrees: Int) -> Unit = { _, _ -> },
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
        val uprightWidth = if (rotation % 180 == 0) proxy.width else proxy.height
        val uprightHeight = if (rotation % 180 == 0) proxy.height else proxy.width

        recognizer.process(InputImage.fromMediaImage(mediaImage, rotation))
            .addOnSuccessListener(callbackExecutor) { text ->
                runCatching { handle(text, proxy, rotation, uprightWidth, uprightHeight, settings) }
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
        uprightWidth: Int,
        uprightHeight: Int,
        settings: AnalyzerConfig,
    ) {
        // The vehicle gate runs first, before any plate-shaped text is even
        // considered. Building the bitmap here — rather than only after a match, as
        // the rest of this function still does when the gate is off — is the one
        // real added cost of turning the gate on: an upright frame is produced (and
        // a detector run over it) even for frames that never had legible text at
        // all, in exchange for never scoring or logging text that isn't near a
        // vehicle in the first place.
        var upright: Bitmap? = null
        var vehicles: List<VehicleDetection> = emptyList()
        val detector = settings.vehicleDetector
        if (detector != null) {
            upright = uprightBitmap(proxy, rotation)
            if (upright == null) return
            vehicles = detector.detect(upright)
            if (vehicles.isEmpty()) {
                upright.recycle()
                return
            }
        }

        val vehicleBoxes = if (detector != null) vehicles.map { it.box } else null
        val match = bestMatch(text, uprightWidth, uprightHeight, vehicleBoxes, settings)
        if (match == null) {
            upright?.recycle()
            return
        }
        val box = match.box
        val normalized = box?.let {
            NormalizedBox.fromPixels(it.left, it.top, it.right, it.bottom, uprightWidth, uprightHeight)
        }

        // Feed the tracker before the logging threshold: a weak, distant read is
        // exactly the case zoom exists to improve.
        if (normalized != null && match.candidate.score >= settings.trackMinScore) {
            onObservation(
                PlateObservation(System.currentTimeMillis(), normalized, settings.zoomRatio),
                rotation,
            )
        }

        if (match.candidate.score < settings.minFrameScore) {
            upright?.recycle()
            return
        }

        var plateCrop: Bitmap? = null
        var vehicleCrop: Bitmap? = null
        var colorName: String? = null
        var tabColorName: String? = null

        // The tab's month and year are printed on it as text, and the recogniser has
        // already read every scrap of text in this frame — so this costs nothing.
        val tabReading = if (box != null && settings.tabParser != null) {
            settings.tabParser.parse(tabCandidateLines(text, box))
        } else {
            null
        }

        if (normalized != null) {
            val frame = upright ?: uprightBitmap(proxy, rotation)
            if (frame != null) {
                if (settings.wantCrops) plateCrop = crop(frame, CropGeometry.plate(normalized))
                val vehicleRect = CropGeometry.vehicle(normalized)
                if (!vehicleRect.isEmpty()) {
                    if (settings.wantCrops) vehicleCrop = crop(frame, vehicleRect)
                    colorName = estimateColor(frame, vehicleRect)
                }
                // Sampled only to cross-check a tab we actually read. Colour cannot
                // yield a year on its own: the cycle repeats every five years.
                if (tabReading != null) {
                    tabColorName = estimateColor(frame, CropGeometry.tab(normalized))
                }
                if (frame != plateCrop && frame != vehicleCrop) frame.recycle()
            }
        } else {
            upright?.recycle()
        }

        // The best-scoring detected vehicle whose plate-search region actually
        // covers where the plate was read; null (never guessed) when the gate is off.
        val bodyType = vehicles
            .filter { normalized == null || VehicleGate.plateSearchRegion(it.box).overlaps(normalized) }
            .maxByOrNull { it.score }
            ?.label
            ?.replaceFirstChar { it.uppercase() }

        onResult(
            PlateFrameResult(
                candidate = match.candidate,
                plateCrop = plateCrop,
                vehicleCrop = vehicleCrop,
                colorName = colorName,
                tabReading = tabReading,
                tabColorName = tabColorName,
                plateBox = normalized,
                bodyType = bodyType,
            ),
        )
    }

    /**
     * Text near the plate, excluding the plate itself.
     *
     * Bounded to the plate's neighbourhood so a date on a bumper sticker or a dealer
     * frame further up the car cannot be mistaken for a tab, but generously so — the
     * exact tab position varies by plate design and the box we have is around the
     * *characters*, not the physical plate.
     */
    private fun tabCandidateLines(text: Text, plate: Rect): List<RecognizedLine> {
        val region = Rect(
            plate.left - (plate.width() * TAB_SEARCH_SIDE_FACTOR).roundToInt(),
            plate.top - (plate.height() * TAB_SEARCH_ABOVE_FACTOR).roundToInt(),
            plate.right + (plate.width() * TAB_SEARCH_SIDE_FACTOR).roundToInt(),
            plate.bottom + (plate.height() * TAB_SEARCH_BELOW_FACTOR).roundToInt(),
        )
        val lines = ArrayList<RecognizedLine>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val lineBox = line.boundingBox ?: continue
                if (lineBox == plate) continue
                if (!Rect.intersects(region, lineBox)) continue
                lines += RecognizedLine(line.text, 0.6f)
            }
        }
        return lines
    }

    private class Match(val candidate: PlateCandidate, val box: Rect?)

    /**
     * Considers each text block as a whole and each of its lines separately: a plate
     * often lands as one block split into two lines, and the block text glues them
     * back together while the line box gives a tighter crop.
     *
     * When [vehicleBoxes] is non-null (the gate is on), a candidate is skipped
     * outright unless its box is near one of them — see [VehicleGate]. A candidate
     * with no box at all (rare, but ML Kit's `boundingBox` is nullable) cannot be
     * placed relative to a vehicle, so it is rejected too rather than trusted blind.
     */
    private fun bestMatch(
        text: Text,
        uprightWidth: Int,
        uprightHeight: Int,
        vehicleBoxes: List<NormalizedBox>?,
        settings: AnalyzerConfig,
    ): Match? {
        var best: Match? = null

        fun consider(raw: String, box: Rect?) {
            if (raw.isBlank()) return
            if (vehicleBoxes != null) {
                if (box == null) return
                val normalizedBox =
                    NormalizedBox.fromPixels(box.left, box.top, box.right, box.bottom, uprightWidth, uprightHeight)
                if (!VehicleGate.isNearAVehicle(normalizedBox, vehicleBoxes)) return
            }
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

    private fun NormalizedBox.toPixels(bitmap: Bitmap): Rect = Rect(
        (left * bitmap.width).roundToInt().coerceIn(0, bitmap.width),
        (top * bitmap.height).roundToInt().coerceIn(0, bitmap.height),
        (right * bitmap.width).roundToInt().coerceIn(0, bitmap.width),
        (bottom * bitmap.height).roundToInt().coerceIn(0, bitmap.height),
    )

    private fun crop(source: Bitmap, region: NormalizedBox): Bitmap? = try {
        val rect = region.toPixels(source)
        if (rect.width() < MIN_CROP_PX || rect.height() < MIN_CROP_PX) null
        else Bitmap.createBitmap(source, rect.left, rect.top, rect.width(), rect.height())
    } catch (e: Exception) {
        Log.w(TAG, "Crop $region failed", e)
        null
    }

    private fun estimateColor(source: Bitmap, region: NormalizedBox): String? {
        val rect = region.toPixels(source)
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

    private companion object {
        const val TAG = "PlateAnalyzer"
        const val FALLBACK_CONFIDENCE = 0.5f
        /** A plate this tall relative to the frame is about as good as it gets. */
        const val IDEAL_TEXT_HEIGHT_FRACTION = 0.07f
        const val MIN_CROP_PX = 8
        const val TAB_SEARCH_SIDE_FACTOR = 0.45f
        const val TAB_SEARCH_ABOVE_FACTOR = 1.30f
        const val TAB_SEARCH_BELOW_FACTOR = 0.40f
    }
}
