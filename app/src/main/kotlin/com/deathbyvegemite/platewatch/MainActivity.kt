package com.deathbyvegemite.platewatch

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.deathbyvegemite.platewatch.ui.PlateWatchNavHost
import com.deathbyvegemite.platewatch.ui.theme.PlateWatchTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val settingsStore = (application as PlateWatchApp).container.settingsStore

        // The phone is in a cradle and nobody is going to tap it to wake it up.
        lifecycleScope.launch {
            settingsStore.settings.collect { settings ->
                if (settings.keepScreenOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }

        setContent {
            PlateWatchTheme {
                PlateWatchNavHost()
            }
        }
    }
}
