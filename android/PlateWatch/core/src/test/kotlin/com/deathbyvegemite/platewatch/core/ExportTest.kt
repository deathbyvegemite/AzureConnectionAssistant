package com.deathbyvegemite.platewatch.core

import com.deathbyvegemite.platewatch.core.export.CsvExport
import com.deathbyvegemite.platewatch.core.export.JsonExport
import com.deathbyvegemite.platewatch.core.export.SightingRecord
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExportTest {

    private val utc = ZoneId.of("UTC")

    private fun record(
        id: Long = 1,
        plate: String = "BK47QT",
        address: String? = "12 Smith St, Newtown",
        notes: String? = null,
        tabMonth: Int? = 9,
        tabYear: Int? = 2026,
        tabStatus: String? = "VALID",
        tabColorMismatch: Boolean? = false,
    ) = SightingRecord(
        id = id,
        plate = plate,
        rawPlate = plate,
        regionId = "AU",
        formatId = "au-lldll",
        confidence = 0.875f,
        readCount = 5,
        firstSeenEpochMs = 1_700_000_000_000,
        lastSeenEpochMs = 1_700_000_004_000,
        latitude = -33.865143,
        longitude = 151.209900,
        accuracyMeters = 6.5f,
        speedMps = 12.5f,
        bearingDegrees = 187f,
        address = address,
        vehicleColor = "Silver",
        vehicleMake = "Toyota",
        vehicleModel = "Hilux",
        vehicleBodyType = "Ute",
        notes = notes,
        flagged = false,
        tabMonth = tabMonth,
        tabYear = tabYear,
        tabStatus = tabStatus,
        tabColor = "Blue",
        tabColorMismatch = tabColorMismatch,
    )

    @Test
    fun `csv has a header and one line per record`() {
        val csv = CsvExport.write(listOf(record(1), record(2)), utc)
        val lines = csv.trim().lines()
        assertEquals(3, lines.size)
        assertEquals(CsvExport.COLUMNS.joinToString(","), lines[0])
    }

    @Test
    fun `csv quotes fields containing commas and doubles embedded quotes`() {
        val csv = CsvExport.write(listOf(record(notes = "said \"hello\", then left")), utc)
        assertTrue(csv.contains("\"said \"\"hello\"\", then left\""))
        assertTrue(csv.contains("\"12 Smith St, Newtown\""))
    }

    @Test
    fun `csv converts metres per second to kilometres per hour`() {
        val csv = CsvExport.write(listOf(record()), utc)
        assertTrue(csv.contains("45.0"), "expected 12.5 m/s to render as 45.0 km/h: $csv")
    }

    @Test
    fun `csv numbers use dots regardless of the phone locale`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val csv = CsvExport.write(listOf(record()), utc)
            assertTrue(csv.contains("-33.865143"), "latitude was localised: $csv")
            assertFalse(csv.contains("-33,865143"), "latitude used a decimal comma: $csv")
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `csv writes timestamps in ISO-8601 with an offset`() {
        val csv = CsvExport.write(listOf(record()), utc)
        assertTrue(csv.contains("2023-11-14T22:13:20Z"), csv)
    }

    @Test
    fun `empty log still produces a usable header row`() {
        val csv = CsvExport.write(emptyList(), utc)
        assertEquals(CsvExport.COLUMNS.joinToString(","), csv.trim())
    }

    /**
     * Splits one CSV row the way a spreadsheet would, honouring quoted fields.
     *
     * A plain `split(",")` misaligns every column after the address, because the
     * address legitimately contains a comma — which is the whole reason the writer
     * quotes it.
     */
    private fun splitCsvRow(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"'); i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> { fields += current.toString(); current.clear() }
                else -> current.append(c)
            }
            i++
        }
        fields += current.toString()
        return fields
    }

    @Test
    fun `quoted fields survive a round trip through a csv reader`() {
        val csv = CsvExport.write(listOf(record(notes = "said \"hello\", then left")), utc)
        val header = splitCsvRow(csv.lines()[0])
        val row = splitCsvRow(csv.lines()[1])
        assertEquals(header.size, row.size, "column count mismatch: $csv")
        assertEquals("12 Smith St, Newtown", row[header.indexOf("address")])
        assertEquals("said \"hello\", then left", row[header.indexOf("notes")])
    }

    @Test
    fun `csv carries the registration tab columns`() {
        val csv = CsvExport.write(listOf(record()), utc)
        val header = splitCsvRow(csv.lines()[0])
        assertTrue(header.containsAll(listOf("tab_month", "tab_year", "tab_status", "tab_colour", "tab_colour_mismatch")))
        val row = splitCsvRow(csv.lines()[1])
        assertEquals("9", row[header.indexOf("tab_month")])
        assertEquals("2026", row[header.indexOf("tab_year")])
        assertEquals("VALID", row[header.indexOf("tab_status")])
        assertEquals("no", row[header.indexOf("tab_colour_mismatch")])
    }

    @Test
    fun `an unread tab exports as empty rather than a guess`() {
        val csv = CsvExport.write(
            listOf(record(tabMonth = null, tabYear = null, tabStatus = "UNKNOWN", tabColorMismatch = null)),
            utc,
        )
        val header = splitCsvRow(csv.lines()[0])
        val row = splitCsvRow(csv.lines()[1])
        assertEquals("", row[header.indexOf("tab_month")])
        assertEquals("", row[header.indexOf("tab_year")])
        assertEquals("", row[header.indexOf("tab_colour_mismatch")])
    }

    @Test
    fun `json carries the tab fields with real nulls`() {
        val json = JsonExport.write(listOf(record(tabMonth = null, tabYear = null)), utc, 1_700_000_000_000)
        assertTrue(json.contains("\"tabMonth\": null"), json)
        assertTrue(json.contains("\"tabYear\": null"), json)
        assertTrue(json.contains("\"tabStatus\": \"VALID\""), json)
    }

    @Test
    fun `json escapes quotes newlines and control characters`() {
        val json = JsonExport.write(
            listOf(record(notes = "line one\nline \"two\"\tend")),
            utc,
            exportedAtEpochMs = 1_700_000_000_000,
        )
        assertTrue(json.contains("""line one\nline \"two\"\tend"""), json)
    }

    @Test
    fun `json reports the record count and omits missing values as null`() {
        val json = JsonExport.write(listOf(record(address = null)), utc, 1_700_000_000_000)
        assertTrue(json.contains("\"count\": 1"))
        assertTrue(json.contains("\"address\": null"))
        assertTrue(json.contains("\"make\": \"Toyota\""))
    }

    @Test
    fun `json separates records with commas but not after the last one`() {
        val json = JsonExport.write(listOf(record(1), record(2)), utc, 1_700_000_000_000)
        assertFalse(json.contains("},\n  ]"), "trailing comma before the closing bracket: $json")
        assertEquals(1, Regex("""\},\n""").findAll(json).count())
    }
}
