package com.deathbyvegemite.platewatch.core.sighting

/** Tunables for how eagerly the app commits a plate to the log. */
data class AggregatorConfig(
    /** Frames that must agree before a plate is written to the log. */
    val minConfirmations: Int = 3,
    /** Those frames must all land inside this window. */
    val confirmWindowMs: Long = 6_000,
    /** A half-seen plate is forgotten after this long with no further frames. */
    val pendingTtlMs: Long = 8_000,
    /** Re-seeing a plate within this long counts as the same drive-by. */
    val dedupWindowMs: Long = 120_000,
    /** ...as does re-seeing it within this many metres of the last sighting. */
    val dedupRadiusMeters: Double = 150.0,
    /** Merge readings that differ by a single character (`8KL4Q7` vs `BKL4Q7`). */
    val fuzzyMerge: Boolean = true,
)

/** One frame's worth of evidence, ready to be pooled with its neighbours. */
data class PlateReading(
    val plate: String,
    val raw: String,
    val formatId: String,
    val score: Float,
    val timestampMs: Long,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

sealed interface AggregateResult {
    /** Enough frames agreed, and this is not a repeat of one we just logged: write a row. */
    data class Confirmed(
        val plate: String,
        val reading: PlateReading,
        val readCount: Int,
        val confidence: Float,
        val firstSeenMs: Long,
    ) : AggregateResult

    /** Same car, same drive-by — top up the row we already wrote. */
    data class Reinforced(
        val plate: String,
        val readCount: Int,
        val confidence: Float,
        val sightingId: Long?,
        val lastSeenMs: Long,
    ) : AggregateResult
}

/**
 * Pools per-frame readings into sightings.
 *
 * Two jobs, both of which matter more than the OCR itself:
 *
 *  1. **Consensus.** A plate is only logged once several frames agree, so one bad
 *     frame at 60 km/h never becomes a permanent record of the wrong car.
 *  2. **Deduplication.** A car you follow for a kilometre is one sighting, not
 *     four hundred. The same plate seen again later, or somewhere else, *is* a new
 *     sighting — that repeat is the whole point of a patrol log.
 *
 * Deliberately free of Android and of any clock of its own: every method takes the
 * timestamps it needs, which makes the behaviour fully testable.
 */
class SightingAggregator(private val config: AggregatorConfig = AggregatorConfig()) {

    private class Pending(var firstMs: Long) {
        val variants = LinkedHashMap<String, Int>()
        var reads = 0
        var scoreSum = 0f
        var lastMs = firstMs
        var best: PlateReading? = null

        fun add(reading: PlateReading) {
            variants[reading.plate] = (variants[reading.plate] ?: 0) + 1
            reads++
            scoreSum += reading.score
            lastMs = reading.timestampMs
            if (best == null || reading.score > best!!.score) best = reading
        }

        /** The spelling the most frames agreed on. */
        fun dominant(): String = variants.maxByOrNull { it.value }!!.key
        fun meanScore(): Float = if (reads == 0) 0f else scoreSum / reads
    }

    private class Recent(
        var lastMs: Long,
        var latitude: Double?,
        var longitude: Double?,
        var reads: Int,
        var scoreSum: Float,
        var sightingId: Long? = null,
    ) {
        fun meanScore(): Float = if (reads == 0) 0f else scoreSum / reads
    }

    private val pending = LinkedHashMap<String, Pending>()
    private val recent = LinkedHashMap<String, Recent>()

    /**
     * Feed in one frame. Returns an [AggregateResult] when the caller should touch
     * the database, or `null` while we are still gathering evidence.
     */
    fun offer(reading: PlateReading): AggregateResult? {
        expire(reading.timestampMs)

        recent[reading.plate]?.let { seen ->
            if (isSameDriveBy(seen, reading)) {
                seen.lastMs = reading.timestampMs
                seen.reads++
                seen.scoreSum += reading.score
                reading.latitude?.let { seen.latitude = it }
                reading.longitude?.let { seen.longitude = it }
                return AggregateResult.Reinforced(
                    plate = reading.plate,
                    readCount = seen.reads,
                    confidence = confidenceOf(seen.reads, seen.meanScore()),
                    sightingId = seen.sightingId,
                    lastSeenMs = reading.timestampMs,
                )
            }
            // Same plate, but long enough ago or far enough away to be a fresh
            // encounter. Drop it and let it re-confirm as its own sighting.
            recent.remove(reading.plate)
        }

        val key = bucketKeyFor(reading.plate)
        val bucket = pending.getOrPut(key) { Pending(reading.timestampMs) }
        bucket.add(reading)

        if (bucket.reads < config.minConfirmations) return null
        if (bucket.lastMs - bucket.firstMs > config.confirmWindowMs) {
            // Evidence dribbled in too slowly to trust as one car; restart the window.
            pending.remove(key)
            return null
        }

        val plate = bucket.dominant()
        val evidence = bucket.best!!
        val confidence = confidenceOf(bucket.reads, bucket.meanScore())
        pending.remove(key)
        recent[plate] = Recent(
            lastMs = bucket.lastMs,
            latitude = evidence.latitude,
            longitude = evidence.longitude,
            reads = bucket.reads,
            scoreSum = bucket.scoreSum,
        )
        return AggregateResult.Confirmed(
            plate = plate,
            reading = evidence.copy(plate = plate),
            readCount = bucket.reads,
            confidence = confidence,
            firstSeenMs = bucket.firstMs,
        )
    }

    /** Tell the aggregator which database row a confirmed plate landed in. */
    fun attachSightingId(plate: String, id: Long) {
        recent[plate]?.sightingId = id
    }

    /** Drop stale state. Safe to call on a timer when no frames are arriving. */
    fun expire(nowMs: Long) {
        pending.entries.removeAll { nowMs - it.value.lastMs > config.pendingTtlMs }
        recent.entries.removeAll { nowMs - it.value.lastMs > config.dedupWindowMs * MAX_RECENT_MULTIPLIER }
    }

    /** Forget everything — used when capture stops or the region setting changes. */
    fun reset() {
        pending.clear()
        recent.clear()
    }

    /** Plates currently being gathered but not yet confirmed, for the live overlay. */
    fun pendingPlates(): List<String> = pending.values.map { it.dominant() }

    private fun isSameDriveBy(seen: Recent, reading: PlateReading): Boolean {
        if (reading.timestampMs - seen.lastMs <= config.dedupWindowMs) return true
        val lat = reading.latitude ?: return false
        val lon = reading.longitude ?: return false
        val seenLat = seen.latitude ?: return false
        val seenLon = seen.longitude ?: return false
        return GeoMath.distanceMeters(seenLat, seenLon, lat, lon) <= config.dedupRadiusMeters
    }

    /**
     * Find the pending bucket this reading belongs to, allowing a single-character
     * difference so that `BK47QT` and `8K47QT` accumulate together instead of each
     * falling one frame short of confirmation.
     */
    private fun bucketKeyFor(plate: String): String {
        if (!config.fuzzyMerge) return plate
        if (pending.containsKey(plate)) return plate
        return pending.keys.firstOrNull { key ->
            key.length == plate.length && TextDistance.levenshtein(key, plate, 1) == 1
        } ?: plate
    }

    /**
     * Blends *how many* frames agreed with *how good* each of those frames was, so
     * that ten blurry reads and two crisp ones do not report the same certainty.
     */
    private fun confidenceOf(reads: Int, meanScore: Float): Float {
        val support = (reads.toFloat() / (config.minConfirmations * 2f)).coerceIn(0f, 1f)
        return (0.5f * support + 0.5f * meanScore).coerceIn(0f, 1f)
    }

    private companion object {
        /**
         * How long a confirmed plate stays remembered, as a multiple of
         * [AggregatorConfig.dedupWindowMs]. This also bounds the distance rule: past
         * this point the same plate always starts a fresh sighting, however close by
         * it is — which is what you want, since a car still parked there an hour
         * later is a new and interesting fact, not a duplicate.
         */
        const val MAX_RECENT_MULTIPLIER = 3
    }
}
