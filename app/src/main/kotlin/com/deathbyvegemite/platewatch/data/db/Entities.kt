package com.deathbyvegemite.platewatch.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One logged encounter with one vehicle.
 *
 * Seeing the same plate again later, or in a different street, deliberately creates
 * a *second* row rather than updating this one — for a patrol log the repeat is the
 * interesting part.
 */
@Entity(
    tableName = "sightings",
    indices = [Index("plate"), Index("firstSeenEpochMs"), Index("flagged")],
)
data class SightingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** Repaired, canonical plate text, e.g. `BK47QT`. */
    val plate: String,
    /** Exactly what the recogniser produced, kept so a bad read can be audited. */
    val rawPlate: String,
    val regionId: String,
    val formatId: String?,
    /** 0..1, blending how many frames agreed with how good each was. */
    val confidence: Float,
    val readCount: Int,

    val firstSeenEpochMs: Long,
    val lastSeenEpochMs: Long,

    val latitude: Double?,
    val longitude: Double?,
    val accuracyMeters: Float?,
    val speedMps: Float?,
    val bearingDegrees: Float?,
    val address: String?,

    /** Estimated from pixels above the plate; everything else is entered by hand. */
    val vehicleColor: String?,
    val vehicleMake: String? = null,
    val vehicleModel: String? = null,
    val vehicleBodyType: String? = null,

    val plateImagePath: String? = null,
    val vehicleImagePath: String? = null,

    val notes: String? = null,
    val flagged: Boolean = false,
)

/** A plate you want to be told about the moment it turns up. */
@Entity(
    tableName = "watchlist",
    indices = [Index(value = ["plate"], unique = true)],
)
data class WatchlistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val plate: String,
    val label: String? = null,
    val addedAtEpochMs: Long,
)

/** Rolled-up view of one plate across every time it has been seen. */
data class PlateSummary(
    val plate: String,
    val sightings: Int,
    val firstSeenEpochMs: Long,
    val lastSeenEpochMs: Long,
    val vehicleColor: String?,
    val vehicleMake: String?,
    val vehicleModel: String?,
)
