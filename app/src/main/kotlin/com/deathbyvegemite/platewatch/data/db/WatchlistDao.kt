package com.deathbyvegemite.platewatch.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchlistDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: WatchlistEntity): Long

    @Query("DELETE FROM watchlist WHERE plate = :plate")
    suspend fun remove(plate: String)

    @Query("SELECT * FROM watchlist ORDER BY addedAtEpochMs DESC")
    fun observeAll(): Flow<List<WatchlistEntity>>

    @Query("SELECT plate FROM watchlist")
    fun observePlates(): Flow<List<String>>

    @Query("SELECT * FROM watchlist WHERE plate = :plate LIMIT 1")
    suspend fun find(plate: String): WatchlistEntity?
}
