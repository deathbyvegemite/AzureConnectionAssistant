package com.deathbyvegemite.platewatch.data.repo

import com.deathbyvegemite.platewatch.core.export.CsvExport
import com.deathbyvegemite.platewatch.core.export.JsonExport
import com.deathbyvegemite.platewatch.core.export.SightingRecord
import com.deathbyvegemite.platewatch.data.db.PlateSummary
import com.deathbyvegemite.platewatch.data.db.SightingDao
import com.deathbyvegemite.platewatch.data.db.SightingEntity
import com.deathbyvegemite.platewatch.data.db.WatchlistDao
import com.deathbyvegemite.platewatch.data.db.WatchlistEntity
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.TimeUnit

class SightingRepository(
    private val sightings: SightingDao,
    private val watchlist: WatchlistDao,
    private val photos: PhotoStore,
) {

    fun observeRecent(): Flow<List<SightingEntity>> = sightings.observeRecent()
    fun search(query: String): Flow<List<SightingEntity>> = sightings.search(query)
    fun observeById(id: Long): Flow<SightingEntity?> = sightings.observeById(id)
    fun observeByPlate(plate: String): Flow<List<SightingEntity>> = sightings.observeByPlate(plate)
    fun observeRepeatPlates(): Flow<List<PlateSummary>> = sightings.observeRepeatPlates()
    fun observeCount(): Flow<Int> = sightings.observeCount()

    fun observeWatchlist(): Flow<List<WatchlistEntity>> = watchlist.observeAll()
    fun observeWatchlistPlates(): Flow<List<String>> = watchlist.observePlates()

    suspend fun record(sighting: SightingEntity): Long = sightings.insert(sighting)

    suspend fun reinforce(id: Long, lastSeenEpochMs: Long, readCount: Int, confidence: Float) =
        sightings.reinforce(id, lastSeenEpochMs, readCount, confidence)

    suspend fun setAddress(id: Long, address: String?) = sightings.setAddress(id, address)

    suspend fun setFlagged(id: Long, flagged: Boolean) = sightings.setFlagged(id, flagged)

    suspend fun setVehicleDetails(
        id: Long,
        make: String?,
        model: String?,
        bodyType: String?,
        color: String?,
        notes: String?,
    ) = sightings.setVehicleDetails(
        id = id,
        make = make?.trim()?.ifEmpty { null },
        model = model?.trim()?.ifEmpty { null },
        bodyType = bodyType?.trim()?.ifEmpty { null },
        color = color?.trim()?.ifEmpty { null },
        notes = notes?.trim()?.ifEmpty { null },
    )

    suspend fun addToWatchlist(plate: String, label: String?) {
        val normalized = normalizePlate(plate)
        if (normalized.isEmpty()) return
        watchlist.upsert(
            WatchlistEntity(
                plate = normalized,
                label = label?.trim()?.ifEmpty { null },
                addedAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun removeFromWatchlist(plate: String) = watchlist.remove(normalizePlate(plate))

    suspend fun deleteSighting(id: Long) {
        sightings.byId(id)?.let {
            photos.delete(it.plateImagePath)
            photos.delete(it.vehicleImagePath)
        }
        sightings.delete(id)
    }

    suspend fun deleteEverything() {
        val all = sightings.allForExport()
        all.forEach { photos.delete(it.plateImagePath); photos.delete(it.vehicleImagePath) }
        sightings.deleteAll()
        photos.deleteOrphans(emptySet())
    }

    /**
     * Deletes sightings past their retention age, and the crops that belong to them.
     * Returns how many rows went. A [retentionDays] of 0 or less keeps everything.
     */
    suspend fun purgeExpired(retentionDays: Int, nowMs: Long = System.currentTimeMillis()): Int {
        if (retentionDays <= 0) return 0
        val cutoff = nowMs - TimeUnit.DAYS.toMillis(retentionDays.toLong())
        (sightings.plateImagePathsOlderThan(cutoff) + sightings.vehicleImagePathsOlderThan(cutoff))
            .forEach(photos::delete)
        return sightings.deleteOlderThan(cutoff)
    }

    suspend fun exportCsv(): String = CsvExport.write(sightings.allForExport().map(::toRecord))

    suspend fun exportJson(): String = JsonExport.write(sightings.allForExport().map(::toRecord))

    fun storageBytes(): Long = photos.totalBytes()

    private fun toRecord(e: SightingEntity) = SightingRecord(
        id = e.id,
        plate = e.plate,
        rawPlate = e.rawPlate,
        regionId = e.regionId,
        formatId = e.formatId,
        confidence = e.confidence,
        readCount = e.readCount,
        firstSeenEpochMs = e.firstSeenEpochMs,
        lastSeenEpochMs = e.lastSeenEpochMs,
        latitude = e.latitude,
        longitude = e.longitude,
        accuracyMeters = e.accuracyMeters,
        speedMps = e.speedMps,
        bearingDegrees = e.bearingDegrees,
        address = e.address,
        vehicleColor = e.vehicleColor,
        vehicleMake = e.vehicleMake,
        vehicleModel = e.vehicleModel,
        vehicleBodyType = e.vehicleBodyType,
        notes = e.notes,
        flagged = e.flagged,
        tabMonth = e.tabMonth,
        tabYear = e.tabYear,
        tabStatus = e.tabStatus,
        tabColor = e.tabColor,
        tabColorMismatch = e.tabColorMismatch,
    )

    companion object {
        /** Watchlist entries are typed by hand, so accept `ab-12 cd` for `AB12CD`. */
        fun normalizePlate(input: String): String =
            input.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }
    }
}
