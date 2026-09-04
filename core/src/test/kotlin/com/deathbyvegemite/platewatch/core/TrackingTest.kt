package com.deathbyvegemite.platewatch.core

import com.deathbyvegemite.platewatch.core.tracking.CropGeometry
import com.deathbyvegemite.platewatch.core.tracking.FrameGeometry
import com.deathbyvegemite.platewatch.core.tracking.MeteringDecision
import com.deathbyvegemite.platewatch.core.tracking.MeteringPolicy
import com.deathbyvegemite.platewatch.core.tracking.NormalizedBox
import com.deathbyvegemite.platewatch.core.tracking.PlateObservation
import com.deathbyvegemite.platewatch.core.tracking.PlateTracker
import com.deathbyvegemite.platewatch.core.tracking.ZoomPolicy
import com.deathbyvegemite.platewatch.core.tracking.VehicleGate
import com.deathbyvegemite.platewatch.core.tracking.ZoomPolicyConfig
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A plate box of [height] centred at ([cx], [cy]), with a 4.5:1 aspect. */
private fun plate(cx: Float, cy: Float, height: Float): NormalizedBox {
    val w = height * 4.5f
    return NormalizedBox(cx - w / 2, cy - height / 2, cx + w / 2, cy + height / 2)
}

private fun near(expected: Float, actual: Float, tol: Float = 0.02f, msg: String = "") =
    assertTrue(abs(expected - actual) <= tol, "$msg expected $expected, got $actual")

class FrameGeometryTest {

    @Test
    fun `identity at zero rotation`() {
        assertEquals(0.2f to 0.7f, FrameGeometry.uprightToSensor(0.2f, 0.7f, 0))
    }

    @Test
    fun `portrait phone maps upright to sensor with a quarter turn`() {
        // Top-left of the upright image is the top-right of the sensor buffer.
        val (x, y) = FrameGeometry.uprightToSensor(0f, 0f, 90)
        near(0f, x); near(1f, y)
        // The upright centre is still the centre.
        val (cx, cy) = FrameGeometry.uprightToSensor(0.5f, 0.5f, 90)
        near(0.5f, cx); near(0.5f, cy)
    }

    @Test
    fun `every rotation is a bijection on the unit square`() {
        for (rot in listOf(0, 90, 180, 270)) {
            for (x in listOf(0f, 0.25f, 0.5f, 1f)) for (y in listOf(0f, 0.4f, 1f)) {
                val (sx, sy) = FrameGeometry.uprightToSensor(x, y, rot)
                assertTrue(sx in 0f..1f && sy in 0f..1f, "rot $rot mapped ($x,$y) outside the square")
            }
        }
    }

    @Test
    fun `a box survives rotation as its bounding box`() {
        val box = NormalizedBox(0.1f, 0.2f, 0.4f, 0.3f)
        val rotated = FrameGeometry.uprightToSensor(box, 90)
        // Width and height swap under a quarter turn.
        near(box.height, rotated.width); near(box.width, rotated.height)
    }
}

class CropGeometryTest {

    @Test
    fun `vehicle region sits directly above the plate and is wider than it`() {
        val box = plate(0.5f, 0.6f, 0.08f)
        val v = CropGeometry.vehicle(box)
        assertTrue(v.bottom <= box.top, "vehicle crop overlaps the plate")
        assertTrue(v.width > box.width)
        near(box.centerX, v.centerX)
    }

    @Test
    fun `padding widens the vehicle region without moving its centre or crossing the plate`() {
        val box = plate(0.5f, 0.6f, 0.08f)
        val base = CropGeometry.vehicle(box)
        val padded = CropGeometry.vehicle(box, padding = 0.3f)
        assertTrue(padded.width > base.width)
        assertTrue(padded.top < base.top, "padding should push the far edge further away")
        assertTrue(padded.bottom <= box.top, "padding should never push the near edge past the plate")
        near(base.centerX, padded.centerX)
    }

    @Test
    fun `regions are clamped to the frame`() {
        val box = plate(0.05f, 0.05f, 0.08f)  // plate jammed into the top-left corner
        for (r in listOf(CropGeometry.plate(box), CropGeometry.vehicle(box), CropGeometry.tab(box))) {
            assertTrue(r.left >= 0f && r.top >= 0f && r.right <= 1f && r.bottom <= 1f, "$r escaped the frame")
        }
    }

    @Test
    fun `tab region is at the upper right of the plate`() {
        val box = plate(0.5f, 0.5f, 0.08f)
        val t = CropGeometry.tab(box)
        assertTrue(t.centerX > box.centerX)
        assertTrue(t.centerY < box.centerY)
    }
}

class PlateTrackerTest {

    @Test
    fun `first observation has no motion`() {
        val t = PlateTracker()
        val s = t.observe(PlateObservation(0, plate(0.5f, 0.5f, 0.05f), 1f))
        assertEquals(0f, s.velocityX); assertEquals(0f, s.growthRate); assertEquals(1, s.observations)
    }

    @Test
    fun `estimates lateral velocity from successive frames`() {
        val t = PlateTracker()
        t.observe(PlateObservation(0, plate(0.5f, 0.5f, 0.05f), 1f))
        val s = t.observe(PlateObservation(200, plate(0.6f, 0.5f, 0.05f), 1f))
        // Moved 0.1 frame-widths in 0.2 s -> 0.5/s, smoothed 50 % from zero -> 0.25.
        near(0.25f, s.velocityX, 0.01f)
        assertEquals(2, s.observations)
    }

    @Test
    fun `a zoom change alone produces no apparent motion or growth`() {
        // The car has not moved; only the zoom went from 1x to 2x. Every apparent
        // coordinate doubles, and a naive tracker would report a lunge.
        val t = PlateTracker()
        t.observe(PlateObservation(0, plate(0.6f, 0.5f, 0.04f), 1f))
        val s = t.observe(PlateObservation(200, plate(0.7f, 0.5f, 0.08f), 2f))
        near(0f, s.velocityX, 0.01f, "velocity")
        near(0f, s.growthRate, 0.05f, "growth")
        near(0.04f, s.height, 0.001f, "1x height")
    }

    @Test
    fun `an approaching car shows positive growth`() {
        val t = PlateTracker()
        t.observe(PlateObservation(0, plate(0.5f, 0.5f, 0.04f), 1f))
        val s = t.observe(PlateObservation(500, plate(0.5f, 0.5f, 0.06f), 1f))
        assertTrue(s.growthRate > 0f)
    }

    @Test
    fun `a long gap starts a new track`() {
        val t = PlateTracker()
        t.observe(PlateObservation(0, plate(0.5f, 0.5f, 0.05f), 1f))
        val s = t.observe(PlateObservation(5_000, plate(0.2f, 0.5f, 0.05f), 1f))
        assertEquals(1, s.observations)
        assertEquals(0f, s.velocityX)
    }

    @Test
    fun `current goes stale and reset clears it`() {
        val t = PlateTracker()
        t.observe(PlateObservation(0, plate(0.5f, 0.5f, 0.05f), 1f))
        assertNotNull(t.current(300))
        assertNull(t.current(3_000))
        t.observe(PlateObservation(3_100, plate(0.5f, 0.5f, 0.05f), 1f))
        t.reset()
        assertNull(t.current(3_100))
    }
}

class ZoomPolicyTest {

    private val cfg = ZoomPolicyConfig(maxStepIn = 10f, hysteresis = 0f)  // step limits off unless a test wants them
    private fun tracked(vararg obs: PlateObservation) =
        PlateTracker().also { t -> obs.forEach(t::observe) }.current(obs.last().timestampMs)

    @Test
    fun `no track means full field of view`() {
        assertEquals(1f, ZoomPolicy(cfg).decide(null, 2.4f))
    }

    @Test
    fun `a single frame is not enough to act on`() {
        val track = tracked(PlateObservation(0, plate(0.5f, 0.5f, 0.02f), 1f))
        assertEquals(1f, ZoomPolicy(cfg).decide(track, 1f))
    }

    @Test
    fun `a small centred stationary plate is zoomed towards the ideal height`() {
        val track = tracked(
            PlateObservation(0, plate(0.5f, 0.5f, 0.02f), 1f),
            PlateObservation(200, plate(0.5f, 0.5f, 0.02f), 1f),
        )
        // ideal 0.07 / 0.02 = 3.5x, capped by maxZoom 2.5.
        near(2.5f, ZoomPolicy(cfg).decide(track, 1f))
    }

    @Test
    fun `never zooms past the point where the plate leaves the frame`() {
        // Plate 0.3 to the right of centre. At zoom z it sits at 0.3z from centre;
        // the edge margin is 0.12, so it must stay under 0.38 -> z < 1.27.
        val track = tracked(
            PlateObservation(0, plate(0.8f, 0.5f, 0.02f), 1f),
            PlateObservation(200, plate(0.8f, 0.5f, 0.02f), 1f),
        )
        val z = ZoomPolicy(cfg).decide(track, 1f)
        assertTrue(z <= 1.27f, "zoomed to $z, which pushes the plate out of frame")
        assertTrue(z >= 1f)
    }

    @Test
    fun `cross traffic is left alone`() {
        // Small plate sweeping across the frame at 1 frame-width per second.
        val track = tracked(
            PlateObservation(0, plate(0.3f, 0.5f, 0.02f), 1f),
            PlateObservation(100, plate(0.4f, 0.5f, 0.02f), 1f),
            PlateObservation(200, plate(0.5f, 0.5f, 0.02f), 1f),
        )
        val z = ZoomPolicy(cfg).decide(track, 1f)
        assertTrue(z < 1.3f, "zoomed to $z on a plate crossing at ~1 frame/s")
    }

    @Test
    fun `predicts where the plate will be when the zoom lands`() {
        // Centred now, but drifting right fast enough to be off-centre in 300 ms.
        val drifting = tracked(
            PlateObservation(0, plate(0.40f, 0.5f, 0.02f), 1f),
            PlateObservation(100, plate(0.45f, 0.5f, 0.02f), 1f),
            PlateObservation(200, plate(0.50f, 0.5f, 0.02f), 1f),
        )
        val still = tracked(
            PlateObservation(0, plate(0.50f, 0.5f, 0.02f), 1f),
            PlateObservation(200, plate(0.50f, 0.5f, 0.02f), 1f),
        )
        val policy = ZoomPolicy(cfg)
        assertTrue(policy.decide(drifting, 1f) < policy.decide(still, 1f))
    }

    @Test
    fun `a plate already large enough is not zoomed`() {
        val track = tracked(
            PlateObservation(0, plate(0.5f, 0.5f, 0.10f), 1f),
            PlateObservation(200, plate(0.5f, 0.5f, 0.10f), 1f),
        )
        assertEquals(1f, ZoomPolicy(cfg).decide(track, 1f))
    }

    @Test
    fun `zooms back out as a car approaches and grows`() {
        // Zoomed to 2x on a distant car; it has now closed to a height that would
        // read fine at 1.4x, so back off to keep it in frame.
        val track = tracked(
            PlateObservation(0, plate(0.5f, 0.5f, 0.10f), 2f),
            PlateObservation(200, plate(0.5f, 0.5f, 0.10f), 2f),
        )
        near(1.4f, ZoomPolicy(cfg).decide(track, 2f), 0.05f)
    }

    @Test
    fun `zooming in is rate limited but zooming out is not`() {
        val policy = ZoomPolicy(ZoomPolicyConfig(maxStepIn = 0.5f, hysteresis = 0f))
        val far = tracked(
            PlateObservation(0, plate(0.5f, 0.5f, 0.02f), 1f),
            PlateObservation(200, plate(0.5f, 0.5f, 0.02f), 1f),
        )
        near(1.5f, policy.decide(far, 1f), 0.01f, "first step in")
        assertEquals(1f, policy.decide(null, 2.5f), "release should be immediate")
    }

    @Test
    fun `small changes are ignored`() {
        val policy = ZoomPolicy(ZoomPolicyConfig(hysteresis = 0.15f, maxStepIn = 10f))
        val track = tracked(
            PlateObservation(0, plate(0.5f, 0.5f, 0.05f), 1f),
            PlateObservation(200, plate(0.5f, 0.5f, 0.05f), 1f),
        )
        // ideal = 0.07/0.05 = 1.4x; from 1.3x that is a 0.1 change, under hysteresis.
        assertEquals(1.3f, policy.decide(track, 1.3f))
    }

    @Test
    fun `rests at the configured base zoom, not always 1x`() {
        val cfg = ZoomPolicyConfig(baseZoom = 2.5f)
        assertEquals(2.5f, ZoomPolicy(cfg).decide(null, 1f))
        assertEquals(2.5f, ZoomPolicy(cfg).decide(null, 7f))
    }

    @Test
    fun `an actively tracked plate can zoom out below the resting level`() {
        // Resting at 2.5x does not mean tracking is forced to stay at or above it —
        // a plate that is already comfortably sized should be free to ease out
        // towards the hardware floor, same as it always could.
        val cfg = ZoomPolicyConfig(baseZoom = 2.5f, maxZoom = 10f, maxStepIn = 10f, hysteresis = 0f)
        val track = tracked(
            PlateObservation(0, plate(0.5f, 0.5f, 0.10f), 2.5f),
            PlateObservation(200, plate(0.5f, 0.5f, 0.10f), 2.5f),
        )
        val target = ZoomPolicy(cfg).decide(track, 2.5f)
        assertTrue(target < 2.5f, "expected to ease below the resting zoom, got $target")
    }

    @Test
    fun `resting zoom and the automatic ceiling are independent settings`() {
        // An unusual but legitimate configuration: rest higher than the ceiling
        // tracking is allowed to reach. The no-track case honours the explicit
        // resting choice rather than silently clamping it to maxZoom.
        val cfg = ZoomPolicyConfig(baseZoom = 5f, maxZoom = 3f)
        assertEquals(5f, ZoomPolicy(cfg).decide(null, 1f))
    }

    @Test
    fun `never returns below 1x`() {
        val policy = ZoomPolicy(cfg)
        val huge = tracked(
            PlateObservation(0, plate(0.5f, 0.5f, 0.4f), 1f),
            PlateObservation(200, plate(0.5f, 0.5f, 0.4f), 1f),
        )
        assertTrue(policy.decide(huge, 1f) >= 1f)
        assertTrue(policy.decide(huge, 2.5f) >= 1f)
    }
}

class MeteringPolicyTest {

    private fun track(cx: Float, cy: Float) = PlateTracker().also {
        it.observe(PlateObservation(0, plate(cx, cy, 0.05f), 1f))
    }.current(0)

    @Test
    fun `first sighting meters on the plate`() {
        val d = MeteringPolicy().decide(track(0.6f, 0.55f), 1f, 0)
        assertIs<MeteringDecision.Meter>(d)
        near(0.6f, d.x); near(0.55f, d.y)
    }

    @Test
    fun `a plate that has not moved is held`() {
        val p = MeteringPolicy()
        p.decide(track(0.6f, 0.55f), 1f, 0)
        assertEquals(MeteringDecision.Hold, p.decide(track(0.61f, 0.55f), 1f, 100))
    }

    @Test
    fun `movement or staleness re-meters`() {
        val p = MeteringPolicy()
        p.decide(track(0.6f, 0.55f), 1f, 0)
        assertIs<MeteringDecision.Meter>(p.decide(track(0.8f, 0.55f), 1f, 100))
        assertIs<MeteringDecision.Meter>(p.decide(track(0.8f, 0.55f), 1f, 5_000))
    }

    @Test
    fun `metering point follows the zoom`() {
        // A plate 0.1 right of centre at 1x is 0.2 right of centre at 2x.
        val d = MeteringPolicy().decide(track(0.6f, 0.5f), 2f, 0)
        assertIs<MeteringDecision.Meter>(d)
        near(0.7f, d.x)
    }

    @Test
    fun `losing the plate cancels once, then holds`() {
        val p = MeteringPolicy()
        p.decide(track(0.5f, 0.5f), 1f, 0)
        assertEquals(MeteringDecision.Cancel, p.decide(null, 1f, 100))
        assertEquals(MeteringDecision.Hold, p.decide(null, 1f, 200))
    }
}

class NormalizedBoxOverlapTest {

    @Test
    fun `identical boxes overlap`() {
        val box = NormalizedBox(0.2f, 0.2f, 0.5f, 0.5f)
        assertTrue(box.overlaps(box))
    }

    @Test
    fun `boxes that share area overlap`() {
        assertTrue(NormalizedBox(0f, 0f, 0.5f, 0.5f).overlaps(NormalizedBox(0.3f, 0.3f, 0.8f, 0.8f)))
    }

    @Test
    fun `boxes that merely touch at an edge do not overlap`() {
        // Sharing a boundary is not sharing area — this is a strict inequality test.
        assertTrue(!NormalizedBox(0f, 0f, 0.5f, 0.5f).overlaps(NormalizedBox(0.5f, 0f, 1f, 0.5f)))
    }

    @Test
    fun `disjoint boxes do not overlap`() {
        assertTrue(!NormalizedBox(0f, 0f, 0.2f, 0.2f).overlaps(NormalizedBox(0.8f, 0.8f, 1f, 1f)))
    }
}

class VehicleGateTest {

    /** A vehicle box roughly the shape a detector draws: wider than tall, lower half of frame. */
    private fun vehicle(cx: Float, cy: Float, w: Float = 0.4f, h: Float = 0.25f) =
        NormalizedBox(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)

    @Test
    fun `text sitting on the vehicle's own box is near it`() {
        val car = vehicle(0.5f, 0.6f)
        val plateText = NormalizedBox(0.45f, 0.68f, 0.55f, 0.72f)  // near the bottom edge
        assertTrue(VehicleGate.isNearAVehicle(plateText, listOf(car)))
    }

    @Test
    fun `text just below the vehicle box is near it`() {
        // The real case: a tight bumper-to-bumper bbox with the plate sitting just
        // under its lower edge.
        val car = vehicle(0.5f, 0.5f)
        val bottom = car.bottom
        val plateText = NormalizedBox(0.45f, bottom + 0.02f, 0.55f, bottom + 0.06f)
        assertTrue(VehicleGate.isNearAVehicle(plateText, listOf(car)))
    }

    @Test
    fun `text well below the vehicle is not near it`() {
        val car = vehicle(0.5f, 0.3f, h = 0.15f)
        val farBelow = NormalizedBox(0.45f, 0.9f, 0.55f, 0.95f)
        assertTrue(!VehicleGate.isNearAVehicle(farBelow, listOf(car)))
    }

    @Test
    fun `a caption above the vehicle is not near it`() {
        // This is the actual failure mode from the field: a dashcam-compilation
        // video's caption text sits well above wherever a car is in the frame.
        val car = vehicle(0.5f, 0.75f)
        val caption = NormalizedBox(0.05f, 0.05f, 0.95f, 0.18f)
        assertTrue(!VehicleGate.isNearAVehicle(caption, listOf(car)))
    }

    @Test
    fun `text beside the vehicle, not at plate height, is not near it`() {
        val car = vehicle(0.3f, 0.5f)
        val sideText = NormalizedBox(0.75f, 0.1f, 0.95f, 0.2f)
        assertTrue(!VehicleGate.isNearAVehicle(sideText, listOf(car)))
    }

    @Test
    fun `no vehicles at all means nothing is ever near one`() {
        val plateText = NormalizedBox(0.4f, 0.6f, 0.6f, 0.65f)
        assertTrue(!VehicleGate.isNearAVehicle(plateText, emptyList()))
    }

    @Test
    fun `matches any one of several vehicles`() {
        val near = vehicle(0.2f, 0.5f)
        val far = vehicle(0.9f, 0.9f, h = 0.1f)
        val textNearFirst = NormalizedBox(0.15f, 0.6f, 0.25f, 0.64f)
        assertTrue(VehicleGate.isNearAVehicle(textNearFirst, listOf(far, near)))
    }

    @Test
    fun `the search region widens sideways and extends downward, not upward`() {
        val car = vehicle(0.5f, 0.5f, w = 0.2f, h = 0.2f)
        val region = VehicleGate.plateSearchRegion(car)
        assertTrue(region.left < car.left, "should widen left")
        assertTrue(region.right > car.right, "should widen right")
        assertTrue(region.bottom > car.bottom, "should extend below")
        assertTrue(region.top == car.top, "should not reach above the vehicle")
    }

    @Test
    fun `the search region is clamped to the frame`() {
        val car = vehicle(0.05f, 0.95f, w = 0.3f, h = 0.3f)
        val region = VehicleGate.plateSearchRegion(car)
        assertTrue(region.left >= 0f && region.right <= 1f && region.bottom <= 1f)
    }
}
