package com.deathbyvegemite.platewatch.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.deathbyvegemite.platewatch.ui.capture.CaptureScreen
import com.deathbyvegemite.platewatch.ui.detail.SightingDetailScreen
import com.deathbyvegemite.platewatch.ui.log.LogScreen
import com.deathbyvegemite.platewatch.ui.settings.SettingsScreen
import com.deathbyvegemite.platewatch.ui.watchlist.WatchlistScreen

object Routes {
    const val CAPTURE = "capture"
    const val LOG = "log"
    const val SETTINGS = "settings"
    const val WATCHLIST = "watchlist"
    const val DETAIL = "sighting/{id}"

    fun detail(id: Long) = "sighting/$id"
}

@Composable
fun PlateWatchNavHost() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.CAPTURE) {
        composable(Routes.CAPTURE) {
            CaptureScreen(
                onOpenLog = { navController.navigate(Routes.LOG) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenSighting = { id -> navController.navigate(Routes.detail(id)) },
            )
        }
        composable(Routes.LOG) {
            LogScreen(
                onBack = { navController.popBackStack() },
                onOpenSighting = { id -> navController.navigate(Routes.detail(id)) },
                onOpenWatchlist = { navController.navigate(Routes.WATCHLIST) },
            )
        }
        composable(Routes.WATCHLIST) {
            WatchlistScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(
            route = Routes.DETAIL,
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { entry ->
            SightingDetailScreen(
                sightingId = entry.arguments?.getLong("id") ?: 0L,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
