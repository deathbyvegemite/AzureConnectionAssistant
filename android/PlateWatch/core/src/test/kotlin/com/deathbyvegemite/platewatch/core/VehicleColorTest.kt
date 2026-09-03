package com.deathbyvegemite.platewatch.core

import com.deathbyvegemite.platewatch.core.color.VehicleColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VehicleColorTest {

    @Test
    fun `names the colours that actually turn up in a car park`() {
        assertEquals("Black", VehicleColor.name(18, 18, 20))
        assertEquals("White", VehicleColor.name(245, 245, 245))
        assertEquals("Silver", VehicleColor.name(178, 180, 182))
        assertEquals("Grey", VehicleColor.name(105, 106, 108))
        assertEquals("Red", VehicleColor.name(200, 25, 30))
        assertEquals("Blue", VehicleColor.name(40, 90, 200))
        assertEquals("Dark Blue", VehicleColor.name(18, 30, 90))
        assertEquals("Green", VehicleColor.name(40, 150, 70))
    }

    @Test
    fun `median sampling ignores a bright highlight`() {
        // Mostly dark green paint with a couple of blown-out specular pixels.
        val pixels = IntArray(20) { 0x1E5A2D } + intArrayOf(0xFFFFFF, 0xFFFFFF)
        assertEquals(0x1E5A2D, VehicleColor.dominantColor(pixels))
    }

    @Test
    fun `no pixels means no colour`() {
        assertNull(VehicleColor.dominantColor(IntArray(0)))
    }

    @Test
    fun `flat patches are rejected as uninformative`() {
        assertTrue(VehicleColor.isLowInformation(IntArray(64) { 0x808080 }))
        assertTrue(VehicleColor.isLowInformation(IntArray(4) { 0x123456 }))
        assertFalse(VehicleColor.isLowInformation(IntArray(64) { if (it % 2 == 0) 0x101010 else 0xD0D0D0 }))
    }

    @Test
    fun `hue saturation and value stay in range for every byte triple`() {
        for (r in 0..255 step 17) for (g in 0..255 step 17) for (b in 0..255 step 17) {
            val (h, s, v) = VehicleColor.toHsv(r, g, b)
            assertTrue(h in 0f..360f, "hue $h for $r/$g/$b")
            assertTrue(s in 0f..1f, "sat $s for $r/$g/$b")
            assertTrue(v in 0f..1f, "val $v for $r/$g/$b")
        }
    }

    @Test
    fun `every colour in the byte cube gets a name`() {
        for (r in 0..255 step 51) for (g in 0..255 step 51) for (b in 0..255 step 51) {
            assertTrue(VehicleColor.name(r, g, b).isNotEmpty())
        }
    }
}
