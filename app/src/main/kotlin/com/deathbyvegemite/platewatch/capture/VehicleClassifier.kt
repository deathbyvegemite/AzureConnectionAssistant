package com.deathbyvegemite.platewatch.capture

import android.graphics.Bitmap

/** A guess at what the vehicle is, from the crop above the plate. */
data class VehicleGuess(
    val make: String?,
    val model: String?,
    val bodyType: String?,
    val confidence: Float,
)

/**
 * Slot for automatic make/model recognition.
 *
 * There is no free, on-device model that reads make and model off a phone camera
 * with anything like the reliability of the plate itself, so the app ships with
 * [NoopVehicleClassifier] and lets you tag make/model by hand on a sighting — which
 * takes about three seconds and is always right.
 *
 * If you have a trained classifier, implement this interface and hand it to the
 * capture view model. `docs/MAKE_AND_MODEL.md` has a worked TensorFlow Lite
 * implementation and the Gradle lines it needs.
 */
interface VehicleClassifier {
    /** @param vehicleCrop the region above the plate, already rotated upright. */
    suspend fun classify(vehicleCrop: Bitmap): VehicleGuess?
}

/** The default: colour is estimated from pixels, make and model are entered by hand. */
object NoopVehicleClassifier : VehicleClassifier {
    override suspend fun classify(vehicleCrop: Bitmap): VehicleGuess? = null
}
