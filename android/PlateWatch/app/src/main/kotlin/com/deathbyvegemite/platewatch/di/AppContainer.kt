package com.deathbyvegemite.platewatch.di

import android.content.Context
import com.deathbyvegemite.platewatch.capture.NoopVehicleClassifier
import com.deathbyvegemite.platewatch.capture.VehicleClassifier
import com.deathbyvegemite.platewatch.data.db.PlateWatchDatabase
import com.deathbyvegemite.platewatch.data.prefs.SettingsStore
import com.deathbyvegemite.platewatch.data.repo.PhotoStore
import com.deathbyvegemite.platewatch.data.repo.SightingRepository
import com.deathbyvegemite.platewatch.location.LocationTracker
import com.deathbyvegemite.platewatch.location.ReverseGeocoder

/**
 * Hand-rolled dependency container.
 *
 * The graph is a dozen objects deep; a DI framework would add build complexity and
 * annotation processing for no benefit at this size.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    private val database by lazy { PlateWatchDatabase.build(appContext) }
    private val photoStore by lazy { PhotoStore(appContext) }

    val settingsStore by lazy { SettingsStore(appContext) }

    val repository by lazy {
        SightingRepository(database.sightingDao(), database.watchlistDao(), photoStore)
    }

    val photos: PhotoStore get() = photoStore

    val locationTracker by lazy { LocationTracker(appContext) }
    val reverseGeocoder by lazy { ReverseGeocoder(appContext) }

    /** Swap in a real implementation to get automatic make/model. See `docs/MAKE_AND_MODEL.md`. */
    val vehicleClassifier: VehicleClassifier = NoopVehicleClassifier
}
