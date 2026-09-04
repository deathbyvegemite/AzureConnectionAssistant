package com.deathbyvegemite.platewatch.core.tab

import com.deathbyvegemite.platewatch.core.plate.RecognizedLine

/**
 * Reads the month and year printed on a registration tab.
 *
 * This is the honest way to get a tab's date: the month and year are *written on the
 * tab as text*, and the recogniser is already running over the whole frame, so pulling
 * them out costs nothing extra. Colour is not involved and cannot be — see
 * [TabColorCycle] for why.
 *
 * The parser accepts several renderings ("SEP 26", "SEP2026", "09 2026") rather than
 * betting on one, because tab artwork changes between issues and a parser pinned to a
 * single layout quietly stops working the year it changes.
 */
class TabTextParser(private val referenceYear: Int) {

    fun parse(lines: List<RecognizedLine>): TabReading? =
        lines.mapNotNull { parseLine(it) }.maxByOrNull { it.confidence }

    /**
     * Collects every candidate in the line before deciding anything.
     *
     * A single left-to-right pass cannot work: in "09 2026" the month arrives before
     * there is any year to disambiguate it against, while in "SEP 26" the trailing
     * number is a year. Gathering first and resolving after handles both.
     */
    private fun parseLine(line: RecognizedLine): TabReading? {
        val tokens = tokenize(line.text)
        if (tokens.isEmpty()) return null

        var namedMonth: Int? = null
        var attachedYear: Int? = null
        var attachedWasFourDigit = false
        val fourDigitYears = mutableListOf<Int>()
        val shortNumbers = mutableListOf<Int>()

        for (token in tokens) {
            val split = splitMonthPrefix(token)
            if (split != null) {
                if (namedMonth == null) namedMonth = split.first
                val rest = split.second
                if (rest != null && attachedYear == null) {
                    resolveYear(rest)?.let {
                        attachedYear = it
                        attachedWasFourDigit = rest.length == 4
                    }
                }
                continue
            }
            if (!token.all { it.isDigit() }) continue
            when (token.length) {
                4 -> resolveYear(token)?.let { fourDigitYears += it }
                2, 1 -> shortNumbers += token.toInt()
            }
        }

        var month = namedMonth
        var year = attachedYear
        var yearWasFourDigit = attachedWasFourDigit

        if (year == null && fourDigitYears.isNotEmpty()) {
            year = fourDigitYears.first()
            yearWasFourDigit = true
        }

        // Short numbers are ambiguous. With a year already known, a 1..12 value is a
        // month; without one, the number is far more likely to be the year itself.
        if (year != null) {
            if (month == null) month = shortNumbers.firstOrNull { it in 1..12 }
        } else {
            for (candidate in shortNumbers) {
                val resolved = resolveYear(candidate.toString().padStart(2, '0'))
                if (resolved != null) {
                    year = resolved
                    yearWasFourDigit = false
                    break
                }
            }
            if (month == null && year != null) {
                month = shortNumbers.firstOrNull { it in 1..12 && 2000 + it != year }
            }
        }

        if (month == null && year == null) return null

        val confidence = when {
            month != null && year != null && yearWasFourDigit -> 0.95f
            month != null && year != null -> 0.85f
            year != null && yearWasFourDigit -> 0.55f
            year != null -> 0.30f
            else -> 0.25f
        }

        return TabReading(month = month, year = year, raw = line.text.trim(), confidence = confidence)
    }

    /** `SEP26` -> (9, "26"); `SEP` -> (9, null); anything else -> null. */
    private fun splitMonthPrefix(token: String): Pair<Int, String?>? {
        if (token.length < 3) return null
        val month = monthFromName(token.substring(0, 3)) ?: return null
        val rest = token.substring(3)
        return month to rest.ifEmpty { null }
    }

    /**
     * Matches a three-letter month, repairing the digit-for-letter substitutions a
     * recogniser makes on small text (`5EP` for `SEP`, `0CT` for `OCT`).
     *
     * Deliberately an exact match after repair, with no fuzzy tolerance: at three
     * characters, allowing a single edit would turn `CAR` into March.
     */
    private fun monthFromName(token: String): Int? {
        if (token.length != 3) return null
        val repaired = token.map { c ->
            when (c) {
                '0' -> 'O'; '1' -> 'I'; '2' -> 'Z'; '4' -> 'A'
                '5' -> 'S'; '6' -> 'G'; '7' -> 'T'; '8' -> 'B'
                else -> c
            }
        }.joinToString("")
        val index = MONTHS.indexOf(repaired)
        return if (index >= 0) index + 1 else null
    }

    /**
     * Accepts a four-digit year, or a two-digit one resolved into the current century.
     * Anything implausibly far from now is rejected rather than guessed at — a stray
     * `1998` on a bumper sticker is not a tab.
     */
    private fun resolveYear(token: String): Int? {
        if (!token.all { it.isDigit() }) return null
        val year = when (token.length) {
            4 -> token.toInt()
            2 -> 2000 + token.toInt()
            else -> return null
        }
        return if (year in (referenceYear - MAX_YEARS_PAST)..(referenceYear + MAX_YEARS_FUTURE)) year else null
    }

    private fun tokenize(text: String): List<String> =
        text.uppercase()
            .map { if (it in 'A'..'Z' || it in '0'..'9') it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotEmpty() }

    private companion object {
        val MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")

        /** An expired tab years old is still worth logging; a decade old is a misread. */
        const val MAX_YEARS_PAST = 8
        const val MAX_YEARS_FUTURE = 2
    }
}
