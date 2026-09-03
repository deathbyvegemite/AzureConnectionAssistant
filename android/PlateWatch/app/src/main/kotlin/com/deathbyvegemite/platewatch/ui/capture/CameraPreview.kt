package com.deathbyvegemite.platewatch.ui.capture

import android.content.Context
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import android.util.Size
import java.util.concurrent.Executor
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

/**
 * Binds preview, and analysis only while capture is running — an idle app should not
 * be feeding frames to a text recogniser.
 *
 * 720p is deliberate: plate glyphs are small, and dropping to 480p costs real reads,
 * while 1080p costs battery and heat for very little extra.
 */
suspend fun bindCamera(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    analyzer: ImageAnalysis.Analyzer?,
    analysisExecutor: Executor,
): Camera? {
    val provider = context.awaitCameraProvider()

    val preview = Preview.Builder().build().also {
        it.surfaceProvider = previewView.surfaceProvider
    }

    val useCases = mutableListOf<androidx.camera.core.UseCase>(preview)

    if (analyzer != null) {
        val resolution = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(1280, 720),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                ),
            )
            .build()

        useCases += ImageAnalysis.Builder()
            .setResolutionSelector(resolution)
            // Always work on the newest frame; a queued backlog would mean logging
            // plates from where the car was several seconds ago.
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also { it.setAnalyzer(analysisExecutor, analyzer) }
    }

    return try {
        provider.unbindAll()
        provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            *useCases.toTypedArray(),
        )
    } catch (e: Exception) {
        null
    }
}
