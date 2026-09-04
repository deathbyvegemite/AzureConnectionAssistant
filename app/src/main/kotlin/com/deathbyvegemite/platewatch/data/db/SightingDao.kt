package com.deathbyvegemite.platewatch.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SightingDao {

    @Insert
    suspend fun insert(sighting: SightingEntity): Long

    @Update
    suspend fun update(sighting: SightingEntity)

    @Query("SELECT * FROM sightings ORDER BY lastSeenEpochMs DESC LIMIT :limit")
    fun observeRecent(limit: Int = 500): Flow<List<SightingEntity>>

    @Query(
        """
        SELECT * FROM sightings
        WHERE plate LIKE '%' || :query || '%'
           OR address LIKE '%' || :query || '%'
           OR vehicleMake LIKE '%' || :query || '%'
           OR vehicleModel LIKE '%' || :query || '%'
        ORDER BY lastSeenEpochMs DESC LIMIT :limit
        """,
    )
    fun search(query: String, limit: Int = 500): Flow<List<SightingEntity>>

    @Query("SELECT * FROM sightings WHERE id = :id")
    fun observeById(id: Long): Flow<SightingEntity?>

    @Query("SELECT * FROM sightings WHERE id = :id")
    suspend fun byId(id: Long): SightingEntity?

    @Query("SELECT * FROM sightings WHERE plate = :plate ORDER BY lastSeenEpochMs DESC")
    fun observeByPlate(plate: String): Flow<List<SightingEntity>>

    /** Every plate seen more than once, most-frequent first — the repeat-visitor view. */
    @Query(
        """
        SELECT plate AS plate,
               COUNT(*) AS sightings,
               MIN(firstSeenEpochMs) AS firstSeenEpochMs,
               MAX(lastSeenEpochMs) AS lastSeenEpochMs,
               MAX(vehicleColor) AS vehicleColor,
               MAX(vehicleMake) AS vehicleMake,
               MAX(vehicleModel) AS vehicleModel
        FROM sightings
        GROUP BY plate
        HAVING COUNT(*) >= :minSightings
        ORDER BY sightings DESC, lastSeenEpochMs DESC
        LIMIT :limit
        """,
    )
    fun observeRepeatPlates(minSightings: Int = 2, limit: Int = 200): Flow<List<PlateSummary>>

    @Query(
        """
        UPDATE sightings
        SET lastSeenEpochMs = :lastSeenEpochMs, readCount = :readCount, confidence = :confidence
        WHERE id = :id
        """,
    )
    suspend fun reinforce(id: Long, lastSeenEpochMs: Long, readCount: Int, confidence: Float)

    @Query("UPDATE sightings SET plateImagePath = :plate, vehicleImagePath = :vehicle WHERE id = :id")
    suspend fun setCropPaths(id: Long, plate: String?, vehicle: String?)

    @Query("UPDATE sightings SET address = :address WHERE id = :id")
    suspend fun setAddress(id: Long, address: String?)

    @Query("UPDATE sightings SET flagged = :flagged WHERE id = :id")
    suspend fun setFlagged(id: Long, flagged: Boolean)

    @Query(
        """
        UPDATE sightings
        SET vehicleMake = :make, vehicleModel = :model,
            vehicleBodyType = :bodyType, vehicleColor = :color, notes = :notes
        WHERE id = :id
        """,
    )
    suspend fun setVehicleDetails(
        id: Long,
        make: String?,
        model: String?,
        bodyType: String?,
        color: String?,
        notes: String?,
    )

    @Query("SELECT * FROM sightings ORDER BY firstSeenEpochMs ASC")
    suspend fun allForExport(): List<SightingEntity>

    @Query("SELECT plateImagePath FROM sightings WHERE lastSeenEpochMs < :cutoff AND plateImagePath IS NOT NULL")
    suspend fun plateImagePathsOlderThan(cutoff: Long): List<String>

    @Query("SELECT vehicleImagePath FROM sightings WHERE lastSeenEpochMs < :cutoff AND vehicleImagePath IS NOT NULL")
    suspend fun vehicleImagePathsOlderThan(cutoff: Long): List<String>

    @Query("DELETE FROM sightings WHERE lastSeenEpochMs < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    @Query("DELETE FROM sightings WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM sightings")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM sightings")
    fun observeCount(): Flow<Int>
}
