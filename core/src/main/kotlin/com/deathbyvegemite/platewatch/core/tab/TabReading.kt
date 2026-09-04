package com.deathbyvegemite.platewatch.core.tab

/**
 * What we managed to read off a registration tab.
 *
 * Both fields are nullable and independently so: it is common to catch the year and
 * miss the month, or the reverse.
 */
data class TabReading(
    /** 1..12, or null if not read. */
    val month: Int?,
    /** Full four-digit year, or null if not read. */
    val year: Int?,
    /** The text this came from, kept so a disputed reading can be checked. */
    val raw: String,
    /** 0..1. Month *and* year together score far higher than either alone. */
    val confidence: Float,
) {
    val isUsable: Boolean get() = month != null || year != null
}

enum class TabStatus {
    /** Printed expiry is in the future. */
    VALID,

    /** Expires this month or next — worth a friendly note, not a report. */
    EXPIRING_SOON,

    /** Printed expiry has passed. */
    EXPIRED,

    /** Nothing legible, or not enough to judge. */
    UNKNOWN,
}

object TabExpiry {

    /**
     * Registration runs to the end of the printed month, so a tab reading September
     * 2026 is still valid throughout September 2026.
     *
     * A reading with no year is [TabStatus.UNKNOWN] regardless of month: a bare "SEP"
     * could be this year or six years ago.
     */
    fun evaluate(
        reading: TabReading?,
        nowYear: Int,
        nowMonth: Int,
        soonWithinMonths: Int = 1,
    ): TabStatus {
        val year = reading?.year ?: return TabStatus.UNKNOWN
        // Without a month, only a year strictly in the past is safe to call expired.
        val month = reading.month ?: return if (year < nowYear) TabStatus.EXPIRED else TabStatus.UNKNOWN

        val monthsRemaining = (year - nowYear) * 12 + (month - nowMonth)
        return when {
            monthsRemaining < 0 -> TabStatus.EXPIRED
            monthsRemaining <= soonWithinMonths -> TabStatus.EXPIRING_SOON
            else -> TabStatus.VALID
        }
    }
}
