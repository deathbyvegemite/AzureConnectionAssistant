package com.deathbyvegemite.platewatch.core.tab

/**
 * Washington's registration tab colour cycle.
 *
 * **This cannot tell you what year a tab is, and there is no function here that
 * returns one.** The cycle is five colours long and then repeats, so a colour maps to
 * a whole family of years spaced five apart. That is not a limitation of the camera or
 * of this code — it is how the scheme is defined:
 *
 * | Year | 2020 | 2021 | 2022 | 2023 | 2024 | 2025 | 2026 | ... |
 * |------|------|------|------|------|------|------|------|-----|
 * | Tab  | white| blue | red  | green| black| white| blue | ... |
 *
 * A blue tab is 2021 *or* 2026 *or* 2031. Worse, the month — which is printed on the
 * same tab — is not encoded in the colour at all, so no amount of colour sampling will
 * ever yield a month.
 *
 * Two of the five colours being white and black is the final nail: those are precisely
 * the two that survive least well through a moving camera, and Washington plates have
 * a white background for a white tab to disappear into.
 *
 * So colour is used here for exactly one thing: **corroborating a tab whose text we
 * actually read.** A tab that reads "2026" but is not blue is worth a second look —
 * recolouring an expired tab is a known and very cheap forgery.
 */
object TabColorCycle {

    /** The cycle repeats every this many years. */
    const val CYCLE_LENGTH = 5

    /** Anchor year, from the Department of Licensing's published schedule. */
    private const val ANCHOR_YEAR = 2020

    private val CYCLE = listOf("white", "blue", "red", "green", "black")

    /** The colour a tab for [year] should be. */
    fun expectedColor(year: Int): String =
        CYCLE[Math.floorMod(year - ANCHOR_YEAR, CYCLE_LENGTH)]

    /**
     * Every year within [searchRadius] of [aroundYear] that a tab of this colour could
     * belong to.
     *
     * Returns a list, never a single year, because a single year is not obtainable.
     * An unrecognised colour returns empty.
     */
    fun candidateYears(colorName: String?, aroundYear: Int, searchRadius: Int = 6): List<Int> {
        val normalized = normalize(colorName) ?: return emptyList()
        return ((aroundYear - searchRadius)..(aroundYear + searchRadius))
            .filter { expectedColor(it) == normalized }
    }

    /**
     * Whether a sampled colour is consistent with a year we read from the tab's text.
     *
     * [Consistency.MISMATCH] is the interesting one: it means the printed year and the
     * paint disagree.
     */
    fun checkConsistency(readYear: Int?, sampledColorName: String?): Consistency {
        if (readYear == null) return Consistency.UNKNOWN
        val sampled = normalize(sampledColorName) ?: return Consistency.UNKNOWN
        return if (sampled == expectedColor(readYear)) Consistency.CONSISTENT else Consistency.MISMATCH
    }

    enum class Consistency { CONSISTENT, MISMATCH, UNKNOWN }

    /**
     * Maps the estimator's colour names onto the five cycle colours.
     *
     * Greys and silvers are deliberately *not* mapped: a washed-out sample sits exactly
     * between white and black, which are two different years, so guessing either would
     * be inventing information.
     */
    private fun normalize(name: String?): String? = when (name?.lowercase()?.trim()) {
        "white" -> "white"
        "black" -> "black"
        "blue", "dark blue", "teal" -> "blue"
        "red", "maroon", "pink" -> "red"
        "green" -> "green"
        else -> null
    }
}
