package com.deathbyvegemite.platewatch.ui.capture

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.deathbyvegemite.platewatch.capture.CameraControls
import com.deathbyvegemite.platewatch.capture.StillImage
import com.deathbyvegemite.platewatch.core.tracking.FrameGeometry
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/** Awaits the camera provider without blocking the main thread. */
suspend fun Context.awaitCameraProvider(): ProcessCameraProvider = suspendCoroutine { cont ->
    val future = ProcessCameraProvider.getInstance(this)
    future.addListener(
        {
            try {
                cont.resume(future.get())
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
        },
        ContextCompat.getMainExecutor(this),
    )
}

/** Analysis stream sizes offered in Settings. */
enum class AnalysisResolution(val id: String, val label: String, val size: Size) {
    P720("720p", "720p — coolest, least battery", Size(1280, 720)),
    P1080("1080p", "1080p — recommended", Size(1920, 1080)),
    P2160("2160p", "4K — most detail, hottest phone", Size(3840, 2160));

    companion object {
        fun byId(id: String?): AnalysisResolution = entries.firstOrNull { it.id == id } ?: P1080
    }
}

/** Everything the screen and view model need from a successful bind. */
class BoundCamera(
    val camera: Camera,
    val analysis: ImageAnalysis?,
    val capture: ImageCapture?,
)

/**
 * Binds preview, and analysis only while capture is running — an idle app should not
 * be feeding frames to a text recogniser.
 *
 * Every use case asks for 16:9 so they all share one field of view. That is what
 * lets a box found on the analysis frame be cut, by proportion, out of the
 * full-resolution still.
 */
suspend fun bindCamera(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    analyzer: ImageAnalysis.Analyzer?,
    analysisExecutor: Executor,
    resolution: AnalysisResolution,
    wantStills: Boolean,
): BoundCamera? {
    val provider = context.awaitCameraProvider()

    val preview = Preview.Builder()
        .setResolutionSelector(sixteenByNine(null))
        .build()
        .also { it.surfaceProvider = previewView.surfaceProvider }

    val useCases = mutableListOf<UseCase>(preview)
    var analysis: ImageAnalysis? = null
    var capture: ImageCapture? = null

    if (analyzer != null) {
        analysis = ImageAnalysis.Builder()
            .setResolutionSelector(sixteenByNine(resolution.size))
            // Always work on the newest frame; a queued backlog would mean logging
            // plates from where the car was several seconds ago.
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also { it.setAnalyzer(analysisExecutor, analyzer) }
        useCases += analysis

        if (wantStills) {
            // ~9 megapixels at 16:9. Enough that a plate 7 % of the frame tall is
            // ~160 px tall in the crop; asking for the sensor's full 200 MP would
            // take over a second a shot and buy nothing legible.
            capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setResolutionSelector(sixteenByNine(Size(4096, 2304)))
                .setJpegQuality(92)
                .build()
            useCases += capture
        }
    }

    return try {
        provider.unbindAll()
        val camera = provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            *useCases.toTypedArray(),
        )
        BoundCamera(camera, analysis, capture)
    } catch (e: Exception) {
        Log.w("CameraPreview", "Camera bind failed", e)
        null
    }
}

private fun sixteenByNine(preferred: Size?): ResolutionSelector =
    ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
        .apply {
            if (preferred != null) {
                setResolutionStrategy(
                    ResolutionStrategy(preferred, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
                )
            }
        }
        .build()

/**
 * [CameraControls] over a bound CameraX camera.
 *
 * On a Galaxy S25 Ultra the logical back camera reports a zoom range that spans the
 * physical lenses; requesting a ratio is enough, and the phone decides whether that
 * is a sensor crop or a lens switch. The policy's default ceiling keeps it on the
 * former.
 */
class CameraXControls(
    private val bound: BoundCamera,
    private val callbackExecutor: Executor,
) : CameraControls {

    private val control get() = bound.camera.cameraControl
    private val info get() = bound.camera.cameraInfo

    override val maxZoomRatio: Float
        get() = info.zoomState.value?.maxZoomRatio ?: 1f

    override fun setZoomRatio(ratio: Float) {
        val clamped = ratio.coerceIn(info.zoomState.value?.minZoomRatio ?: 1f, maxZoomRatio)
        runCatching { control.setZoomRatio(clamped) }
            .onFailure { Log.d(TAG, "Zoom request rejected", it) }
    }

    override fun meterAt(x: Float, y: Float, rotationDegrees: Int) {
        val analysis = bound.analysis ?: return
        // The factory addresses the analysis surface in sensor orientation; the point
        // arrives upright, so it is mapped over first. Field check: the metering
        // region should land on the plate in the preview. If it lands on the sky,
        // this mapping is the first place to look.
        val (sx, sy) = FrameGeometry.uprightToSensor(x, y, rotationDegrees)
        val point = SurfaceOrientedMeteringPointFactory(1f, 1f, analysis).createPoint(sx, sy, POINT_SIZE)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(AUTO_CANCEL_SECONDS, TimeUnit.SECONDS)
            .build()
        runCatching { control.startFocusAndMetering(action) }
            .onFailure { Log.d(TAG, "Metering request rejected", it) }
    }

    override fun cancelMetering() {
        runCatching { control.cancelFocusAndMetering() }
    }

    override fun setExposureCompensation(index: Int) {
        val state = info.exposureState
        if (!state.isExposureCompensationSupported) return
        val range = state.exposureCompensationRange
        val clamped = index.coerceIn(range.lower, range.upper)
        runCatching { control.setExposureCompensationIndex(clamped) }
            .onFailure { Log.d(TAG, "Exposure compensation rejected", it) }
    }

    override fun captureStill(onResult: (StillImage?) -> Unit) {
        val capture = bound.capture
        if (capture == null) {
            onResult(null)
            return
        }
        capture.takePicture(
            callbackExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val still = try {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                        StillImage(bytes, image.imageInfo.rotationDegrees, image.width, image.height)
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not read still", e)
                        null
                    } finally {
                        image.close()
                    }
                    onResult(still)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.d(TAG, "Still capture failed", exception)
                    onResult(null)
                }
            },
        )
    }

    private companion object {
        const val TAG = "CameraXControls"
        /** Metering region size as a fraction of the frame; roughly a plate's width. */
        const val POINT_SIZE = 0.12f
        const val AUTO_CANCEL_SECONDS = 3L
    }
}
