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
