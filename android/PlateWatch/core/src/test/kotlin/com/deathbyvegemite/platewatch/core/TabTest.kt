package com.deathbyvegemite.platewatch.core

import com.deathbyvegemite.platewatch.core.plate.RecognizedLine
import com.deathbyvegemite.platewatch.core.tab.TabColorCycle
import com.deathbyvegemite.platewatch.core.tab.TabExpiry
import com.deathbyvegemite.platewatch.core.tab.TabReading
import com.deathbyvegemite.platewatch.core.tab.TabStatus
import com.deathbyvegemite.platewatch.core.tab.TabTextParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TabTextParserTest {

    private val parser = TabTextParser(referenceYear = 2026)

    private fun read(vararg text: String) = parser.parse(text.map { RecognizedLine(it, 0.8f) })

    @Test
    fun `reads a month name and four-digit year`() {
        val r = assertNotNull(read("SEP 2026"))
        assertEquals(9, r.month)
        assertEquals(2026, r.year)
        assertTrue(r.confidence > 0.9f)
    }

    @Test
    fun `reads a month name and two-digit year`() {
        val r = assertNotNull(read("SEP 26"))
        assertEquals(9, r.month)
        assertEquals(2026, r.year)
    }

    @Test
    fun `reads month and year glued into one token`() {
        val r = assertNotNull(read("SEP26"))
        assertEquals(9, r.month)
        assertEquals(2026, r.year)
    }

    @Test
    fun `reads a numeric month ahead of the year`() {
        // The case a single left-to-right pass gets wrong.
        val r = assertNotNull(read("09 2026"))
        assertEquals(9, r.month)
        assertEquals(2026, r.year)
    }

    @Test
    fun `repairs digit-for-letter confusion in the month name`() {
        assertEquals(9, read("5EP 2026")?.month)
        assertEquals(10, read("0CT 2026")?.month)
        assertEquals(8, read("AU6 2026")?.month)
    }

    @Test
    fun `does not invent a month from a similar looking word`() {
        // CAR is one edit from MAR. Fuzzy matching here would be a disaster.
        assertNull(read("CAR")?.month)
        assertNull(read("BAR 2026")?.month)
    }

    @Test
    fun `a bare two-digit number is read as a year not a month`() {
        val r = assertNotNull(read("26"))
        assertEquals(2026, r.year)
        assertNull(r.month)
        assertTrue(r.confidence < 0.5f, "a bare two-digit number should be low confidence")
    }

    @Test
    fun `rejects years too far from now to be a tab`() {
        assertNull(read("1998"))
        assertNull(read("2050"))
    }

    @Test
    fun `accepts a tab that expired a few years ago`() {
        assertEquals(2023, read("MAR 2023")?.year)
    }

    @Test
    fun `year and month together outrank either alone`() {
        val both = assertNotNull(read("SEP 2026"))
        val yearOnly = assertNotNull(read("2026"))
        val monthOnly = assertNotNull(read("SEP"))
        assertTrue(both.confidence > yearOnly.confidence)
        assertTrue(yearOnly.confidence > monthOnly.confidence)
    }

    @Test
    fun `ignores text with nothing date-like in it`() {
        assertNull(read("WASHINGTON"))
        assertNull(read("EVERGREEN STATE"))
        assertNull(read(""))
    }

    @Test
    fun `picks the most confident reading across several lines`() {
        val r = assertNotNull(read("26", "SEP 2026", "WASHINGTON"))
        assertEquals(9, r.month)
        assertEquals(2026, r.year)
    }

    @Test
    fun `keeps the raw text for auditing`() {
        assertEquals("SEP 2026", read("SEP 2026")?.raw)
    }
}

class TabExpiryTest {

    private fun reading(month: Int?, year: Int?) =
        TabReading(month = month, year = year, raw = "test", confidence = 0.9f)

    @Test
    fun `a future expiry is valid`() {
        assertEquals(TabStatus.VALID, TabExpiry.evaluate(reading(12, 2026), nowYear = 2026, nowMonth = 9))
    }

    @Test
    fun `registration runs to the end of the printed month`() {
        // A tab reading September 2026 is still good on 30 September 2026.
        assertEquals(
            TabStatus.EXPIRING_SOON,
            TabExpiry.evaluate(reading(9, 2026), nowYear = 2026, nowMonth = 9),
        )
    }

    @Test
    fun `last month is expired`() {
        assertEquals(TabStatus.EXPIRED, TabExpiry.evaluate(reading(8, 2026), nowYear = 2026, nowMonth = 9))
    }

    @Test
    fun `next month counts as expiring soon`() {
        assertEquals(
            TabStatus.EXPIRING_SOON,
            TabExpiry.evaluate(reading(10, 2026), nowYear = 2026, nowMonth = 9),
        )
    }

    @Test
    fun `expiry spanning a year boundary is handled`() {
        assertEquals(TabStatus.EXPIRED, TabExpiry.evaluate(reading(12, 2025), nowYear = 2026, nowMonth = 1))
        assertEquals(
            TabStatus.EXPIRING_SOON,
            TabExpiry.evaluate(reading(1, 2027), nowYear = 2026, nowMonth = 12),
        )
    }

    @Test
    fun `a year with no month is only called expired when the whole year has passed`() {
        assertEquals(TabStatus.EXPIRED, TabExpiry.evaluate(reading(null, 2024), nowYear = 2026, nowMonth = 9))
        assertEquals(TabStatus.UNKNOWN, TabExpiry.evaluate(reading(null, 2026), nowYear = 2026, nowMonth = 9))
    }

    @Test
    fun `a month with no year tells us nothing`() {
        assertEquals(TabStatus.UNKNOWN, TabExpiry.evaluate(reading(9, null), nowYear = 2026, nowMonth = 9))
    }

    @Test
    fun `no reading at all is unknown`() {
        assertEquals(TabStatus.UNKNOWN, TabExpiry.evaluate(null, nowYear = 2026, nowMonth = 9))
    }
}

class TabColorCycleTest {

    @Test
    fun `matches the published Washington schedule`() {
        assertEquals("white", TabColorCycle.expectedColor(2020))
        assertEquals("blue", TabColorCycle.expectedColor(2021))
        assertEquals("red", TabColorCycle.expectedColor(2022))
        assertEquals("green", TabColorCycle.expectedColor(2023))
        assertEquals("black", TabColorCycle.expectedColor(2024))
    }

    @Test
    fun `the cycle repeats every five years`() {
        for (year in 2015..2040) {
            assertEquals(TabColorCycle.expectedColor(year), TabColorCycle.expectedColor(year + 5))
        }
    }

    @Test
    fun `colour alone can never identify a single year`() {
        // The whole reason colour cannot answer "what year is this tab".
        val candidates = TabColorCycle.candidateYears("blue", aroundYear = 2026)
        assertTrue(candidates.size > 1, "expected several candidate years, got $candidates")
        assertTrue(candidates.containsAll(listOf(2021, 2026, 2031)))
    }

    @Test
    fun `washed out samples are refused rather than guessed`() {
        // Silver and grey sit between white and black, which are different years.
        assertTrue(TabColorCycle.candidateYears("Silver", 2026).isEmpty())
        assertTrue(TabColorCycle.candidateYears("Grey", 2026).isEmpty())
        assertTrue(TabColorCycle.candidateYears(null, 2026).isEmpty())
        assertTrue(TabColorCycle.candidateYears("Beige", 2026).isEmpty())
    }

    @Test
    fun `near neighbours of the cycle colours still map`() {
        assertTrue(TabColorCycle.candidateYears("Dark Blue", 2026).contains(2026))
        assertTrue(TabColorCycle.candidateYears("Maroon", 2026).contains(2027))
    }

    @Test
    fun `colour corroborates a year we actually read`() {
        assertEquals(
            TabColorCycle.Consistency.CONSISTENT,
            TabColorCycle.checkConsistency(2026, "Blue"),
        )
    }

    @Test
    fun `a recoloured tab shows up as a mismatch`() {
        // A 2023 tab painted to pass as current.
        assertEquals(
            TabColorCycle.Consistency.MISMATCH,
            TabColorCycle.checkConsistency(2023, "Blue"),
        )
    }

    @Test
    fun `no year or no usable colour means no verdict`() {
        assertEquals(TabColorCycle.Consistency.UNKNOWN, TabColorCycle.checkConsistency(null, "Blue"))
        assertEquals(TabColorCycle.Consistency.UNKNOWN, TabColorCycle.checkConsistency(2026, "Silver"))
    }
}
