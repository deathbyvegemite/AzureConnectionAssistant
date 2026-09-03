package com.deathbyvegemite.platewatch.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [SightingEntity::class, WatchlistEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class PlateWatchDatabase : RoomDatabase() {

    abstract fun sightingDao(): SightingDao
    abstract fun watchlistDao(): WatchlistDao

    companion object {
        fun build(context: Context): PlateWatchDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                PlateWatchDatabase::class.java,
                "platewatch.db",
            ).build()
    }
}
