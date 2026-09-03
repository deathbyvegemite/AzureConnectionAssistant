package com.deathbyvegemite.platewatch.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val TIME_ONLY = DateTimeFormatter.ofPattern("HH:mm:ss")
private val DATE_TIME = DateTimeFormatter.ofPattern("d MMM, HH:mm:ss")

fun formatTime(epochMs: Long): String =
    TIME_ONLY.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

fun formatDateTime(epochMs: Long): String =
    DATE_TIME.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

fun formatConfidence(confidence: Float): String = "${(confidence * 100).roundToInt()}%"

fun formatCoordinates(latitude: Double?, longitude: Double?): String? {
    if (latitude == null || longitude == null) return null
    return String.format(Locale.getDefault(), "%.5f, %.5f", latitude, longitude)
}

fun formatSpeed(speedMps: Float?): String? =
    speedMps?.let { "${(it * 3.6f).roundToInt()} km/h" }
