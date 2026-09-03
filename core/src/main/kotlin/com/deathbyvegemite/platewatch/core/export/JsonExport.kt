package com.deathbyvegemite.platewatch.core.export

import java.time.ZoneId

/**
 * Lossless JSON export, for handing the log to another system.
 *
 * Hand-rolled rather than pulled from a library so that the `core` module stays
 * dependency-free and behaves identically on the JVM and on Android.
 */
object JsonExport {

    fun write(
        records: List<SightingRecord>,
        zone: ZoneId = ZoneId.systemDefault(),
        exportedAtEpochMs: Long = System.currentTimeMillis(),
    ): String {
        val out = StringBuilder()
        out.append("{\n")
        out.append("  \"exportedAt\": ").append(quote(CsvExport.timestamp(exportedAtEpochMs, zone))).append(",\n")
        out.append("  \"count\": ").append(records.size).append(",\n")
        out.append("  \"sightings\": [\n")
        records.forEachIndexed { index, r ->
            val fields = listOf(
                "\"id\": ${r.id}",
                "\"plate\": ${quote(r.plate)}",
                "\"rawPlate\": ${quote(r.rawPlate)}",
                "\"region\": ${quote(r.regionId)}",
                "\"format\": ${quoteOrNull(r.formatId)}",
                "\"confidence\": ${CsvExport.fixed(r.confidence.toDouble(), 3)}",
                "\"reads\": ${r.readCount}",
                "\"firstSeen\": ${quote(CsvExport.timestamp(r.firstSeenEpochMs, zone))}",
                "\"lastSeen\": ${quote(CsvExport.timestamp(r.lastSeenEpochMs, zone))}",
                "\"firstSeenEpochMs\": ${r.firstSeenEpochMs}",
                "\"lastSeenEpochMs\": ${r.lastSeenEpochMs}",
                "\"latitude\": ${numberOrNull(r.latitude, 6)}",
                "\"longitude\": ${numberOrNull(r.longitude, 6)}",
                "\"accuracyMeters\": ${numberOrNull(r.accuracyMeters?.toDouble(), 1)}",
                "\"speedKmh\": ${numberOrNull(r.speedMps?.let { it.toDouble() * 3.6 }, 1)}",
                "\"bearingDegrees\": ${numberOrNull(r.bearingDegrees?.toDouble(), 0)}",
                "\"address\": ${quoteOrNull(r.address)}",
                "\"colour\": ${quoteOrNull(r.vehicleColor)}",
                "\"make\": ${quoteOrNull(r.vehicleMake)}",
                "\"model\": ${quoteOrNull(r.vehicleModel)}",
                "\"bodyType\": ${quoteOrNull(r.vehicleBodyType)}",
                "\"flagged\": ${r.flagged}",
                "\"notes\": ${quoteOrNull(r.notes)}",
            )
            out.append("    {").append(fields.joinToString(", "))
            out.append(if (index == records.lastIndex) "}\n" else "},\n")
        }
        out.append("  ]\n}\n")
        return out.toString()
    }

    private fun quoteOrNull(value: String?): String = value?.let { quote(it) } ?: "null"

    private fun numberOrNull(value: Double?, decimals: Int): String =
        if (value == null || value.isNaN() || value.isInfinite()) "null"
        else CsvExport.fixed(value, decimals)

    internal fun quote(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (c in value) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append(String.format(java.util.Locale.ROOT, "\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
