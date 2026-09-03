package com.deathbyvegemite.platewatch.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Keeps the newest usable fix on hand so a confirmed plate can be stamped with a
 * position immediately.
 *
 * Uses the platform [LocationManager] rather than Play Services' fused provider, so
 * the app works on a phone with no Google Play installed — which is a normal thing
 * for a sideloaded app to run into.
 */
class LocationTracker(private val context: Context) {

    private val manager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val _location = MutableStateFlow<Location?>(null)
    val location: StateFlow<Location?> = _location.asStateFlow()

    private var running = false

    /**
     * Implemented as a full class rather than a lambda on purpose: three of these
     * callbacks only became default methods in API 30, and a SAM-converted lambda
     * would throw [AbstractMethodError] on an Android 8 or 9 phone.
     */
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (isBetterThanCurrent(location)) _location.value = location
        }

        @Suppress("DEPRECATION")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** @return true if at least one provider accepted the request. */
    fun start(): Boolean {
        if (running) return true
        val lm = manager ?: return false
        if (!hasPermission()) return false

        var started = false
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (!lm.isProviderEnabled(provider)) continue
                lm.requestLocationUpdates(
                    provider,
                    MIN_INTERVAL_MS,
                    MIN_DISTANCE_M,
                    listener,
                    Looper.getMainLooper(),
                )
                lm.getLastKnownLocation(provider)?.let { if (isBetterThanCurrent(it)) _location.value = it }
                started = true
            } catch (e: SecurityException) {
                Log.w(TAG, "Location permission withdrawn for $provider", e)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Provider $provider unavailable", e)
            }
        }
        running = started
        return started
    }

    fun stop() {
        if (!running) return
        runCatching { manager?.removeUpdates(listener) }
        running = false
    }

    /**
     * Prefer a fix that is meaningfully newer, or one that is more accurate. Without
     * this, a stale network fix can keep overwriting a good GPS one.
     */
    private fun isBetterThanCurrent(candidate: Location): Boolean {
        val current = _location.value ?: return true
        val ageMs = candidate.time - current.time
        if (ageMs > STALE_AFTER_MS) return true
        if (ageMs < -STALE_AFTER_MS) return false
        if (!candidate.hasAccuracy()) return ageMs > 0
        if (!current.hasAccuracy()) return true
        return candidate.accuracy <= current.accuracy || ageMs > 0 && candidate.accuracy < current.accuracy * 1.5f
    }

    private companion object {
        const val TAG = "LocationTracker"
        const val MIN_INTERVAL_MS = 1_000L
        const val MIN_DISTANCE_M = 5f
        const val STALE_AFTER_MS = 15_000L
    }
}
