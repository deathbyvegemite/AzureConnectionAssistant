package com.deathbyvegemite.platewatch.core.export

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Spreadsheet-friendly export. Opens cleanly in Excel, Sheets and LibreOffice. */
object CsvExport {

    val COLUMNS: List<String> = listOf(
        "id", "plate", "raw_plate", "region", "format", "confidence", "reads",
        "first_seen", "last_seen", "latitude", "longitude", "accuracy_m",
        "speed_kmh", "bearing_deg", "address", "colour", "make", "model",
        "body_type", "tab_month", "tab_year", "tab_status", "tab_colour",
        "tab_colour_mismatch", "flagged", "notes",
    )

    fun write(records: List<SightingRecord>, zone: ZoneId = ZoneId.systemDefault()): String {
        val out = StringBuilder()
        out.append(COLUMNS.joinToString(",")).append('\n')
        for (r in records) {
            val cells = listOf(
                r.id.toString(),
                r.plate,
                r.rawPlate,
                r.regionId,
                r.formatId.orEmpty(),
                fixed(r.confidence.toDouble(), 3),
                r.readCount.toString(),
                timestamp(r.firstSeenEpochMs, zone),
                timestamp(r.lastSeenEpochMs, zone),
                r.latitude?.let { fixed(it, 6) }.orEmpty(),
                r.longitude?.let { fixed(it, 6) }.orEmpty(),
                r.accuracyMeters?.let { fixed(it.toDouble(), 1) }.orEmpty(),
                r.speedMps?.let { fixed(it.toDouble() * 3.6, 1) }.orEmpty(),
                r.bearingDegrees?.let { fixed(it.toDouble(), 0) }.orEmpty(),
                r.address.orEmpty(),
                r.vehicleColor.orEmpty(),
                r.vehicleMake.orEmpty(),
                r.vehicleModel.orEmpty(),
                r.vehicleBodyType.orEmpty(),
                r.tabMonth?.toString().orEmpty(),
                r.tabYear?.toString().orEmpty(),
                r.tabStatus.orEmpty(),
                r.tabColor.orEmpty(),
                r.tabColorMismatch?.let { if (it) "yes" else "no" }.orEmpty(),
                if (r.flagged) "yes" else "no",
                r.notes.orEmpty(),
            )
            out.append(cells.joinToString(",") { escape(it) }).append('\n')
        }
        return out.toString()
    }

    /**
     * Always formats with [Locale.ROOT]. The default locale would happily write
     * `-33,865143` on a European phone and quietly corrupt every export.
     */
    internal fun fixed(value: Double, decimals: Int): String =
        String.format(Locale.ROOT, "%.${decimals}f", value)

    internal fun timestamp(epochMs: Long, zone: ZoneId): String =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(epochMs).atZone(zone))

    internal fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
}
