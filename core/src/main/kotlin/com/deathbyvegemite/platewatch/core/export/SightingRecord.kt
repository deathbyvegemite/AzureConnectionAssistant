package com.deathbyvegemite.platewatch.core.export

/**
 * A flat, storage-agnostic view of one logged sighting. The Android layer maps its
 * Room entity onto this so that export formatting stays pure and testable.
 */
data class SightingRecord(
    val id: Long,
    val plate: String,
    val rawPlate: String,
    val regionId: String,
    val formatId: String?,
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
    val vehicleColor: String?,
    val vehicleMake: String?,
    val vehicleModel: String?,
    val vehicleBodyType: String?,
    val notes: String?,
    val flagged: Boolean,

    /** Expiry month printed on the registration tab, 1..12, when it was legible. */
    val tabMonth: Int? = null,
    /** Expiry year printed on the registration tab, when it was legible. */
    val tabYear: Int? = null,
    /** VALID / EXPIRING_SOON / EXPIRED / UNKNOWN, derived from the printed date. */
    val tabStatus: String? = null,
    /** Sampled tab colour. Corroboration only — the colour cycle repeats every 5 years. */
    val tabColor: String? = null,
    /** True when the printed year and the tab colour disagree. */
    val tabColorMismatch: Boolean? = null,
)
