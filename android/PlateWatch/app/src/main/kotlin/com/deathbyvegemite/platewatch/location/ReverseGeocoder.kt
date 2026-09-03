package com.deathbyvegemite.platewatch.location

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Turns a fix into something a human can read out at a meeting.
 *
 * Best effort by design: it needs a network, it is rate limited by the platform, and
 * on some devices [Geocoder] is simply absent. A sighting without an address is
 * still a perfectly good sighting, so every failure path returns null quietly.
 */
class ReverseGeocoder(context: Context) {

    private val geocoder: Geocoder? =
        if (Geocoder.isPresent()) Geocoder(context, Locale.getDefault()) else null

    suspend fun addressFor(latitude: Double, longitude: Double): String? {
        val gc = geocoder ?: return null
        return withTimeoutOrNull(TIMEOUT_MS) {
            val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                fetchAsync(gc, latitude, longitude)
            } else {
                fetchBlocking(gc, latitude, longitude)
            }
            addresses?.firstOrNull()?.let(::format)
        }
    }

    private suspend fun fetchAsync(gc: Geocoder, lat: Double, lon: Double): List<Address>? =
        suspendCancellableCoroutine { cont ->
            try {
                gc.getFromLocation(lat, lon, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        if (cont.isActive) cont.resume(addresses)
                    }

                    override fun onError(errorMessage: String?) {
                        Log.d(TAG, "Geocoder error: $errorMessage")
                        if (cont.isActive) cont.resume(null)
                    }
                })
            } catch (e: Exception) {
                Log.d(TAG, "Geocoder unavailable", e)
                if (cont.isActive) cont.resume(null)
            }
        }

    @Suppress("DEPRECATION")
    private suspend fun fetchBlocking(gc: Geocoder, lat: Double, lon: Double): List<Address>? =
        withContext(Dispatchers.IO) {
            try {
                gc.getFromLocation(lat, lon, 1)
            } catch (e: Exception) {
                Log.d(TAG, "Geocoder lookup failed", e)
                null
            }
        }

    /** `12 Smith St, Newtown NSW` — street first, because that is what people say. */
    private fun format(address: Address): String? {
        val street = listOfNotNull(address.subThoroughfare, address.thoroughfare)
            .joinToString(" ")
            .ifBlank { null }
        val area = listOfNotNull(address.locality ?: address.subAdminArea, address.adminArea)
            .joinToString(" ")
            .ifBlank { null }
        val parts = listOfNotNull(street, area)
        return when {
            parts.isNotEmpty() -> parts.joinToString(", ")
            else -> address.getAddressLine(0)
        }
    }

    private companion object {
        const val TAG = "ReverseGeocoder"
        const val TIMEOUT_MS = 4_000L
    }
}
