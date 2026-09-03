package com.deathbyvegemite.platewatch.core.color

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Estimates a human-readable body colour from a patch of pixels sampled just above
 * the plate.
 *
 * This is a genuine estimate, not a measurement: street lighting, headlights and a
 * dirty windscreen all shift it. Treat "dark blue" as a search filter, not evidence.
 */
object VehicleColor {

    /**
     * Per-channel median of packed `0xRRGGBB` pixels. The median shrugs off the
     * chrome trim, brake light and number-plate glare that would drag a mean around.
     */
    fun dominantColor(pixels: IntArray): Int? {
        if (pixels.isEmpty()) return null
        val reds = IntArray(pixels.size)
        val greens = IntArray(pixels.size)
        val blues = IntArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            reds[i] = (p shr 16) and 0xFF
            greens[i] = (p shr 8) and 0xFF
            blues[i] = p and 0xFF
        }
        reds.sort(); greens.sort(); blues.sort()
        val mid = pixels.size / 2
        return (reds[mid] shl 16) or (greens[mid] shl 8) or blues[mid]
    }

    /** Name a packed `0xRRGGBB` colour. */
    fun name(rgb: Int): String = name((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)

    fun name(r: Int, g: Int, b: Int): String {
        val (hue, sat, value) = toHsv(r, g, b)

        // Achromatic first: most cars on the road are some shade of nothing.
        if (value < 0.16f) return "Black"
        if (sat < 0.13f) return when {
            value > 0.80f -> "White"
            value > 0.52f -> "Silver"
            else -> "Grey"
        }
        if (sat < 0.25f && value > 0.62f && hue in 20f..65f) return "Beige"

        return when {
            hue < 15f || hue >= 345f -> if (value < 0.42f) "Maroon" else "Red"
            hue < 45f -> if (value < 0.50f) "Brown" else "Orange"
            hue < 70f -> if (value < 0.45f) "Brown" else "Yellow"
            hue < 160f -> "Green"
            hue < 200f -> "Teal"
            hue < 255f -> if (value < 0.42f) "Dark Blue" else "Blue"
            hue < 290f -> "Purple"
            else -> if (value < 0.45f) "Maroon" else "Pink"
        }
    }

    /** Hue in degrees 0..360, saturation and value on 0..1. */
    internal fun toHsv(r: Int, g: Int, b: Int): Triple<Float, Float, Float> {
        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f
        val cMax = max(rf, max(gf, bf))
        val cMin = min(rf, min(gf, bf))
        val delta = cMax - cMin

        val hue = when {
            delta < 1e-6f -> 0f
            cMax == rf -> 60f * (((gf - bf) / delta) % 6f)
            cMax == gf -> 60f * (((bf - rf) / delta) + 2f)
            else -> 60f * (((rf - gf) / delta) + 4f)
        }.let { if (it < 0f) it + 360f else it }

        val sat = if (cMax < 1e-6f) 0f else delta / cMax
        return Triple(hue, sat, cMax)
    }

    /**
     * How washed-out a sample is. A patch with almost no variation is usually sky,
     * shadow or blown-out glare rather than bodywork, and is worth discarding.
     */
    fun isLowInformation(pixels: IntArray): Boolean {
        if (pixels.size < 16) return true
        var minLuma = 255
        var maxLuma = 0
        for (p in pixels) {
            val luma = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
            if (luma < minLuma) minLuma = luma
            if (luma > maxLuma) maxLuma = luma
        }
        return abs(maxLuma - minLuma) < 8
    }
}
