package com.deathbyvegemite.platewatch.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.deathbyvegemite.platewatch.PlateWatchApp
import com.deathbyvegemite.platewatch.di.AppContainer

/**
 * The log, detail, settings and watchlist screens are thin views over Room flows with
 * no state worth surviving a rotation, so they read the container directly rather
 * than each carrying a ViewModel that would do nothing but forward calls.
 *
 * Capture is the exception and keeps a real ViewModel: it owns the aggregator and the
 * session counters, which must outlive recomposition.
 */
@Composable
fun rememberAppContainer(): AppContainer {
    val context = LocalContext.current
    return remember(context) { (context.applicationContext as PlateWatchApp).container }
}
