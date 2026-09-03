package com.deathbyvegemite.platewatch.core

import com.deathbyvegemite.platewatch.core.plate.PlateRegions
import com.deathbyvegemite.platewatch.core.plate.PlateTextParser
import com.deathbyvegemite.platewatch.core.plate.RecognizedLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlateTextParserTest {

    private val au = PlateTextParser(PlateRegions.AU)
    private val generic = PlateTextParser(PlateRegions.GENERIC)

    @Test
    fun `reads a clean Australian plate`() {
        val best = au.best(listOf(RecognizedLine("BK47QT", 0.9f)))
        assertNotNull(best)
        assertEquals("BK47QT", best.plate)
        assertEquals(0, best.coercions)
        assertEquals("au-lldll", best.formatId)
    }

    @Test
    fun `repairs letter-digit confusion using the format mask`() {
        // The recogniser read the leading B as an 8 and the O as a zero.
        val best = au.best(listOf(RecognizedLine("8K47QT", 0.8f)))
        assertNotNull(best)
        assertEquals("BK47QT", best.plate)
        assertEquals(1, best.coercions)
        assertEquals("8K47QT", best.raw)
    }

    @Test
    fun `repairs a zero that should have been a letter O`() {
        val best = au.best(listOf(RecognizedLine("0N56XY", 0.8f)))
        assertNotNull(best)
        assertEquals("ON56XY", best.plate)
    }

    @Test
    fun `pulls the plate out of a line that also carries the state name`() {
        val best = au.best(listOf(RecognizedLine("NSW  BK47QT", 0.85f)))
        assertNotNull(best)
        assertEquals("BK47QT", best.plate)
    }

    @Test
    fun `ignores state names and slogans`() {
        assertNull(au.best(listOf(RecognizedLine("NEW SOUTH WALES", 0.9f))))
        assertNull(au.best(listOf(RecognizedLine("VICTORIA", 0.9f))))
        assertNull(au.best(listOf(RecognizedLine("THE PLACE TO BE", 0.9f))))
    }

    @Test
    fun `ignores text that is too short to be a plate`() {
        assertNull(au.best(listOf(RecognizedLine("AB12", 0.9f))))
    }

    @Test
    fun `joins fragments that the recogniser split apart`() {
        val best = au.best(listOf(RecognizedLine("BK47 QT", 0.8f)))
        assertNotNull(best)
        assertEquals("BK47QT", best.plate)
    }

    @Test
    fun `prefers an exact-length match over a substring match`() {
        val candidates = au.parse(listOf(RecognizedLine("XBK47QT", 0.8f)))
        assertTrue(candidates.isNotEmpty())
        // BK47QT sits inside the 7-char token; whichever wins, an exact fit of the
        // same quality must never score below a partial one.
        val exact = au.best(listOf(RecognizedLine("BK47QT", 0.8f)))!!
        val partial = candidates.first { it.plate == "BK47QT" }
        assertTrue(exact.score > partial.score, "exact ${exact.score} !> partial ${partial.score}")
    }

    @Test
    fun `a cleaner reading outscores one needing repairs`() {
        val clean = au.best(listOf(RecognizedLine("BK47QT", 0.8f)))!!
        val repaired = au.best(listOf(RecognizedLine("8K47QT", 0.8f)))!!
        assertTrue(clean.score > repaired.score)
    }

    @Test
    fun `generic region still refuses all-letter and all-digit tokens`() {
        assertNull(generic.best(listOf(RecognizedLine("PARKING", 0.9f))))
        assertNull(generic.best(listOf(RecognizedLine("12345678", 0.9f))))
        assertNotNull(generic.best(listOf(RecognizedLine("XJ9021", 0.9f))))
    }

    @Test
    fun `higher OCR confidence produces a higher score`() {
        val low = au.best(listOf(RecognizedLine("BK47QT", 0.1f)))!!
        val high = au.best(listOf(RecognizedLine("BK47QT", 0.95f)))!!
        assertTrue(high.score > low.score)
    }

    @Test
    fun `scores always stay within zero and one`() {
        val samples = listOf("BK47QT", "8K47QT", "1AB2CD", "123ABC", "1ABC234", "NSW BK47QT")
        for (s in samples) {
            for (c in au.parse(listOf(RecognizedLine(s, 1.0f)))) {
                assertTrue(c.score in 0f..1f, "$s -> ${c.plate} scored ${c.score}")
            }
        }
    }

    @Test
    fun `recognises the other common Australian layouts`() {
        assertEquals("1AB2CD", au.best(listOf(RecognizedLine("1AB2CD", 0.9f)))?.plate)
        assertEquals("123ABC", au.best(listOf(RecognizedLine("123ABC", 0.9f)))?.plate)
        assertEquals("1ABC234", au.best(listOf(RecognizedLine("1ABC234", 0.9f)))?.plate)
    }

    @Test
    fun `UK current format is recognised`() {
        val uk = PlateTextParser(PlateRegions.UK)
        assertEquals("AB12CDE", uk.best(listOf(RecognizedLine("AB12 CDE", 0.9f)))?.plate)
    }
}
