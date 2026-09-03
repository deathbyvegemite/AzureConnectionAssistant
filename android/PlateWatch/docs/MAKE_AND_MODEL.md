# Adding automatic make and model recognition

PlateWatch ships with `NoopVehicleClassifier`, so make and model are typed in by hand.
This is how to replace that with a real model.

## Why it is not built in

Reading make and model off a phone camera is a fine-grained visual classification
problem across several thousand classes that look nearly identical from behind. There
is no permissively licensed, on-device model that does it well enough to write into a
log people will treat as fact. A classifier that is right 70% of the time produces a
log that is wrong 30% of the time and *looks* authoritative, which is worse than a
blank field.

If you have a model you trust — trained on your own footage, or licensed — the hook is
there and the pipeline already hands it a cropped image of the vehicle.

## The interface

```kotlin
interface VehicleClassifier {
    suspend fun classify(vehicleCrop: Bitmap): VehicleGuess?
}

data class VehicleGuess(
    val make: String?,
    val model: String?,
    val bodyType: String?,
    val confidence: Float,
)
```

`vehicleCrop` is the region directly above the plate, already rotated upright: roughly
2.2 plate-widths across and 2 plate-heights tall. On a car photographed from behind
that is mostly boot lid, badge and rear window.

## 1. Add the dependency

In `gradle/libs.versions.toml`:

```toml
[versions]
tflite = "2.16.1"
tfliteSupport = "0.4.4"

[libraries]
tensorflow-lite = { module = "org.tensorflow:tensorflow-lite", version.ref = "tflite" }
tensorflow-lite-support = { module = "org.tensorflow:tensorflow-lite-support", version.ref = "tfliteSupport" }
```

In `app/build.gradle.kts`:

```kotlin
implementation(libs.tensorflow.lite)
implementation(libs.tensorflow.lite.support)

android {
    androidResources {
        // Stop the build tools compressing the model — it is mmap'd at runtime.
        noCompress += "tflite"
    }
}
```

## 2. Drop in the model

Put `vehicle_classifier.tflite` and `vehicle_labels.txt` in `app/src/main/assets/`.

Labels are one per line, in the model's output order, formatted `Make|Model|BodyType`:

```
Toyota|Hilux|Ute
Toyota|Corolla|Hatch
Ford|Ranger|Ute
```

## 3. The implementation

```kotlin
package com.deathbyvegemite.platewatch.capture

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class TfLiteVehicleClassifier(
    context: Context,
    private val inputSize: Int = 224,
    private val minConfidence: Float = 0.60f,
) : VehicleClassifier {

    private val labels: List<Triple<String, String, String>> =
        context.assets.open("vehicle_labels.txt").bufferedReader().readLines()
            .map { it.split("|") }
            .map { Triple(it.getOrElse(0) { "" }, it.getOrElse(1) { "" }, it.getOrElse(2) { "" }) }

    private val interpreter: Interpreter = Interpreter(
        loadModel(context, "vehicle_classifier.tflite"),
        Interpreter.Options().apply { numThreads = 2 },
    )

    private val output = Array(1) { FloatArray(labels.size) }

    override suspend fun classify(vehicleCrop: Bitmap): VehicleGuess? = withContext(Dispatchers.Default) {
        val scaled = Bitmap.createScaledBitmap(vehicleCrop, inputSize, inputSize, true)
        val input = toInputBuffer(scaled)
        if (scaled != vehicleCrop) scaled.recycle()

        interpreter.run(input, output)

        val scores = output[0]
        val best = scores.indices.maxByOrNull { scores[it] } ?: return@withContext null
        val confidence = scores[best]
        if (confidence < minConfidence) return@withContext null

        val (make, model, body) = labels[best]
        VehicleGuess(
            make = make.ifBlank { null },
            model = model.ifBlank { null },
            bodyType = body.ifBlank { null },
            confidence = confidence,
        )
    }

    /** Float32 NHWC, normalised to 0..1. Match this to how your model was trained. */
    private fun toInputBuffer(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3)
            .order(ByteOrder.nativeOrder())
        val pixels = IntArray(inputSize * inputSize)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        for (pixel in pixels) {
            buffer.putFloat(((pixel shr 16) and 0xFF) / 255f)
            buffer.putFloat(((pixel shr 8) and 0xFF) / 255f)
            buffer.putFloat((pixel and 0xFF) / 255f)
        }
        buffer.rewind()
        return buffer
    }

    private fun loadModel(context: Context, asset: String): ByteBuffer =
        context.assets.openFd(asset).use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use { channel ->
                channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }

    fun close() = interpreter.close()
}
```

## 4. Wire it up

In `di/AppContainer.kt`:

```kotlin
val vehicleClassifier: VehicleClassifier by lazy { TfLiteVehicleClassifier(appContext) }
```

Then in `CaptureViewModel.commit()`, after the crops are saved and before the row is
written:

```kotlin
val guess = frame.vehicleCrop
    ?.takeIf { !it.isRecycled }
    ?.let { container.vehicleClassifier.classify(it) }
```

and set `vehicleMake = guess?.make`, `vehicleModel = guess?.model`,
`vehicleBodyType = guess?.bodyType` on the `SightingEntity`.

Note that `consumeFrames()` recycles both crops once `handleFrame` returns, so the
classifier has to run inside that call, not in a coroutine launched from it.

## Things that will bite you

- **Inference cost.** A 224×224 MobileNet-class model is roughly 20–40 ms per call on
  a mid-range phone. That is fine at the rate plates actually confirm (a few per
  minute), and disastrous if you ever call it per frame. Keep it in `commit()`.
- **Threshold high.** A wrong make in the log is worse than a blank one. Start at 0.7
  and only come down if you are checking the results against the saved crops.
- **Record the confidence.** `VehicleGuess.confidence` is there for a reason — put it
  somewhere in the row so a low-confidence guess can be told apart from a human entry.
- **Night footage.** Whatever accuracy you measure in daylight, expect materially
  worse after dark. Test on the footage you will actually collect.
