package com.deathbyvegemite.platewatch.core

import com.deathbyvegemite.platewatch.core.sighting.AggregateResult
import com.deathbyvegemite.platewatch.core.sighting.AggregatorConfig
import com.deathbyvegemite.platewatch.core.sighting.PlateReading
import com.deathbyvegemite.platewatch.core.sighting.SightingAggregator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SightingAggregatorTest {

    private val sydneyLat = -33.8688
    private val sydneyLon = 151.2093

    private fun reading(
        plate: String,
        atMs: Long,
        score: Float = 0.8f,
        lat: Double? = sydneyLat,
        lon: Double? = sydneyLon,
    ) = PlateReading(
        plate = plate,
        raw = plate,
        formatId = "au-lldll",
        score = score,
        timestampMs = atMs,
        latitude = lat,
        longitude = lon,
    )

    @Test
    fun `one frame is never enough to log a plate`() {
        val agg = SightingAggregator()
        assertNull(agg.offer(reading("BK47QT", 1_000)))
    }

    @Test
    fun `confirms once the configured number of frames agree`() {
        val agg = SightingAggregator(AggregatorConfig(minConfirmations = 3))
        assertNull(agg.offer(reading("BK47QT", 1_000)))
        assertNull(agg.offer(reading("BK47QT", 1_300)))
        val result = agg.offer(reading("BK47QT", 1_600))
        assertIs<AggregateResult.Confirmed>(result)
        assertEquals("BK47QT", result.plate)
        assertEquals(3, result.readCount)
        assertEquals(1_000, result.firstSeenMs)
        assertTrue(result.confidence in 0f..1f)
    }

    @Test
    fun `further frames of the same drive-by reinforce instead of duplicating`() {
        val agg = SightingAggregator(AggregatorConfig(minConfirmations = 2))
        agg.offer(reading("BK47QT", 1_000))
        assertIs<AggregateResult.Confirmed>(agg.offer(reading("BK47QT", 1_200)))
        repeat(20) { i -> 
            val r = agg.offer(reading("BK47QT", 1_400L + i * 100))
            assertIs<AggregateResult.Reinforced>(r)
        }
    }

    @Test
    fun `reinforcement carries the database row id back to the caller`() {
        val agg = SightingAggregator(AggregatorConfig(minConfirmations = 2))
        agg.offer(reading("BK47QT", 1_000))
        assertIs<AggregateResult.Confirmed>(agg.offer(reading("BK47QT", 1_200)))
        agg.attachSightingId("BK47QT", 99L)
        val r = agg.offer(reading("BK47QT", 1_400))
        assertIs<AggregateResult.Reinforced>(r)
        assertEquals(99L, r.sightingId)
    }

    @Test
    fun `evidence spread too thinly over time is not trusted`() {
        val agg = SightingAggregator(
            AggregatorConfig(minConfirmations = 3, confirmWindowMs = 2_000, pendingTtlMs = 60_000),
        )
        assertNull(agg.offer(reading("BK47QT", 0)))
        assertNull(agg.offer(reading("BK47QT", 1_000)))
        // Third frame lands outside the window, so the window restarts rather than confirming.
        assertNull(agg.offer(reading("BK47QT", 5_000)))
    }

    @Test
    fun `a half-seen plate is forgotten once it goes stale`() {
        val agg = SightingAggregator(AggregatorConfig(minConfirmations = 3, pendingTtlMs = 4_000))
        agg.offer(reading("BK47QT", 0))
        agg.offer(reading("BK47QT", 500))
        assertEquals(listOf("BK47QT"), agg.pendingPlates())
        agg.expire(10_000)
        assertTrue(agg.pendingPlates().isEmpty())
    }

    @Test
    fun `single-character variants pool together and the majority spelling wins`() {
        val agg = SightingAggregator(AggregatorConfig(minConfirmations = 3, fuzzyMerge = true))
        assertNull(agg.offer(reading("BK47QT", 1_000)))
        assertNull(agg.offer(reading("BK47QI", 1_200)))   // misread final character
        val result = agg.offer(reading("BK47QT", 1_400))
        assertIs<AggregateResult.Confirmed>(result)
        assertEquals("BK47QT", result.plate)
        assertEquals(3, result.readCount)
    }

    @Test
    fun `fuzzy merge can be switched off`() {
        val agg = SightingAggregator(AggregatorConfig(minConfirmations = 3, fuzzyMerge = false))
        assertNull(agg.offer(reading("BK47QT", 1_000)))
        assertNull(agg.offer(reading("BK47QI", 1_200)))
        assertNull(agg.offer(reading("BK47QT", 1_400)))   // only two of these agree
    }

    @Test
    fun `the same car somewhere else later is a new sighting`() {
        val agg = SightingAggregator(AggregatorConfig(minConfirmations = 2, dedupWindowMs = 120_000))
        agg.offer(reading("BK47QT", 0))
        assertIs<AggregateResult.Confirmed>(agg.offer(reading("BK47QT", 500)))

        // Three minutes later and five kilometres away: genuinely a second encounter.
        val far = 5_000.0 / 111_320.0
        val t = 180_000L
        assertNull(agg.offer(reading("BK47QT", t, lat = sydneyLat + far)))
        val second = agg.offer(reading("BK47QT", t + 500, lat = sydneyLat + far))
        assertIs<AggregateResult.Confirmed>(second)
    }

    @Test
    fun `sitting behind the same car at a long light does not create a second sighting`() {
        val agg = SightingAggregator(AggregatorConfig(minConfirmations = 2, dedupWindowMs = 120_000))
        agg.offer(reading("BK47QT", 0))
        assertIs<AggregateResult.Confirmed>(agg.offer(reading("BK47QT", 500)))

        // Three minutes on — past the time window — but we have barely moved.
        val nudge = 20.0 / 111_320.0
        val r = agg.offer(reading("BK47QT", 180_000, lat = sydneyLat + nudge))
        assertIs<AggregateResult.Reinforced>(r)
    }

    @Test
    fun `different plates are tracked independently`() {
        val agg = SightingAggregator(AggregatorConfig(minConfirmations = 2))
        assertNull(agg.offer(reading("BK47QT", 0)))
        assertNull(agg.offer(reading("XY99ZZ", 100)))
        assertIs<AggregateResult.Confirmed>(agg.offer(reading("BK47QT", 200)))
        assertIs<AggregateResult.Confirmed>(agg.offer(reading("XY99ZZ", 300)))
    }

    @Test
    fun `reset clears everything`() {
        val agg = SightingAggregator(AggregatorConfig(minConfirmations = 3))
        agg.offer(reading("BK47QT", 0))
        agg.reset()
        assertTrue(agg.pendingPlates().isEmpty())
        assertNull(agg.offer(reading("BK47QT", 100)))
    }

    @Test
    fun `confidence rises as more frames keep agreeing`() {
        val agg = SightingAggregator(AggregatorConfig(minConfirmations = 2))
        agg.offer(reading("BK47QT", 0))
        val atConfirmation = (agg.offer(reading("BK47QT", 200)) as AggregateResult.Confirmed).confidence

        var latest = atConfirmation
        for (i in 2..9) {
            latest = (agg.offer(reading("BK47QT", i * 200L)) as AggregateResult.Reinforced).confidence
        }
        assertTrue(latest > atConfirmation, "confidence stalled at $latest")
        assertTrue(latest <= 1f)
    }

    @Test
    fun `poor quality frames never reach the confidence of clean ones`() {
        fun confidenceFor(score: Float): Float {
            val agg = SightingAggregator(AggregatorConfig(minConfirmations = 3))
            var last: AggregateResult? = null
            for (i in 0..2) last = agg.offer(reading("BK47QT", i * 200L, score = score))
            return (last as AggregateResult.Confirmed).confidence
        }
        assertTrue(confidenceFor(0.95f) > confidenceFor(0.40f))
    }

    @Test
    fun `works with no location fix at all`() {
        val agg = SightingAggregator(AggregatorConfig(minConfirmations = 2))
        assertNull(agg.offer(reading("BK47QT", 0, lat = null, lon = null)))
        assertIs<AggregateResult.Confirmed>(agg.offer(reading("BK47QT", 200, lat = null, lon = null)))
    }
}
