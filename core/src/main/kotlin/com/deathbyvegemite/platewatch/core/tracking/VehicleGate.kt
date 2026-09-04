package com.deathbyvegemite.platewatch.core.tracking

/**
 * Decides whether a piece of read text is close enough to a detected vehicle to be
 * trusted as a plate.
 *
 * This exists because a plate-shaped run of characters is not evidence of a plate —
 * it is evidence that *something in frame produced text matching a plate mask*. A
 * dashcam-compilation video playing on a phone screen, a road sign, a search box, a
 * caption overlay: all of these can and do glue together into something that scores
 * as an `LLDD`-shaped plate, because the mask only checks character classes, not
 * where the text came from. A vehicle detector supplies the missing check: is there
 * even a car, truck, bus or motorcycle in this frame at all, and is the text roughly
 * where the plate on such a thing would be.
 *
 * A detector's box is not itself a plate-finder — it wraps the vehicle's body, and on
 * many vehicles it is drawn tight to the bumper with the plate sitting right on, or a
 * little below, that lower edge. So the search area is the vehicle box widened a
 * little and extended further down, not the vehicle box itself.
 */
object VehicleGate {

    /** Where a plate belonging to [vehicleBox] is expected to be. */
    fun plateSearchRegion(vehicleBox: NormalizedBox): NormalizedBox = NormalizedBox(
        vehicleBox.left - vehicleBox.width * SIDE_MARGIN,
        vehicleBox.top,
        vehicleBox.right + vehicleBox.width * SIDE_MARGIN,
        vehicleBox.bottom + vehicleBox.height * BELOW_MARGIN,
    ).clamped()

    /** Does [textBox] fall within plate-search range of any detected vehicle? */
    fun isNearAVehicle(textBox: NormalizedBox, vehicleBoxes: List<NormalizedBox>): Boolean =
        vehicleBoxes.any { plateSearchRegion(it).overlaps(textBox) }

    private const val SIDE_MARGIN = 0.15f
    private const val BELOW_MARGIN = 0.30f
}
