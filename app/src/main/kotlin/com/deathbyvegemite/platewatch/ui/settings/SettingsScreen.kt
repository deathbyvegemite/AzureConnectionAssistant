package com.deathbyvegemite.platewatch.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deathbyvegemite.platewatch.core.plate.PlateRegions
import com.deathbyvegemite.platewatch.data.prefs.CaptureSettings
import com.deathbyvegemite.platewatch.ui.rememberAppContainer
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val container = rememberAppContainer()
    val scope = rememberCoroutineScope()
    val settings by remember { container.settingsStore.settings }
        .collectAsStateWithLifecycle(initialValue = CaptureSettings())

    fun edit(transform: (CaptureSettings) -> CaptureSettings) {
        scope.launch { container.settingsStore.update(transform) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            SectionHeader("Reading")

            RegionPicker(settings.regionId) { id -> edit { it.copy(regionId = id) } }

            SliderSetting(
                title = "Frames that must agree",
                value = settings.minConfirmations.toFloat(),
                range = 1f..8f,
                steps = 6,
                display = "${settings.minConfirmations}",
                explanation = "Higher misses more plates but almost never writes down the wrong one.",
            ) { edit { s -> s.copy(minConfirmations = it.roundToInt()) } }

            SliderSetting(
                title = "Minimum single-frame quality",
                value = settings.minFrameScore,
                range = 0.3f..0.9f,
                steps = 11,
                display = "${(settings.minFrameScore * 100).roundToInt()}%",
                explanation = "Discards weak readings before they get a vote.",
            ) { edit { s -> s.copy(minFrameScore = it) } }

            SliderSetting(
                title = "Frames analysed per second",
                value = settings.analysisFps.toFloat(),
                range = 1f..12f,
                steps = 10,
                display = "${settings.analysisFps} fps",
                explanation = "The main lever on battery drain and how warm the phone gets.",
            ) { edit { s -> s.copy(analysisFps = it.roundToInt()) } }

            SwitchSetting(
                title = "Pool near-identical readings",
                checked = settings.fuzzyMerge,
                explanation = "Treats BK47QT and 8K47QT as the same car while deciding.",
            ) { edit { s -> s.copy(fuzzyMerge = it) } }

            SectionHeader("Repeat sightings")

            SliderSetting(
                title = "Same-encounter window",
                value = settings.dedupWindowSeconds.toFloat(),
                range = 15f..600f,
                steps = 0,
                display = "${settings.dedupWindowSeconds}s",
                explanation = "Re-seeing a plate inside this window tops up the existing entry rather than adding a new one.",
            ) { edit { s -> s.copy(dedupWindowSeconds = it.roundToInt()) } }

            SliderSetting(
                title = "Same-encounter radius",
                value = settings.dedupRadiusMeters.toFloat(),
                range = 25f..500f,
                steps = 0,
                display = "${settings.dedupRadiusMeters} m",
                explanation = "Stops a long red light turning into a dozen entries for the car in front.",
            ) { edit { s -> s.copy(dedupRadiusMeters = it.roundToInt()) } }

            SectionHeader("Recording")

            SwitchSetting(
                title = "Save plate and vehicle crops",
                checked = settings.savePhotos,
                explanation = "Small JPEGs kept in app-private storage so a reading can be checked by eye later.",
            ) { edit { s -> s.copy(savePhotos = it) } }

            SliderSetting(
                title = "Keep sightings for",
                value = settings.retentionDays.toFloat(),
                range = 0f..180f,
                steps = 0,
                display = if (settings.retentionDays == 0) "forever" else "${settings.retentionDays} days",
                explanation = "Old sightings and their crops are deleted automatically once a day. Zero keeps everything.",
            ) { edit { s -> s.copy(retentionDays = it.roundToInt()) } }

            SwitchSetting(
                title = "Look up street addresses",
                checked = settings.resolveAddresses,
                explanation = "The only thing this app uses the network for. Turn it off and you keep coordinates only.",
            ) { edit { s -> s.copy(resolveAddresses = it) } }

            SectionHeader("While driving")

            SwitchSetting(
                title = "Keep the screen on",
                checked = settings.keepScreenOn,
                explanation = "Required for capture to keep running — Android stops camera access when the app is not in front.",
            ) { edit { s -> s.copy(keepScreenOn = it) } }

            SwitchSetting(
                title = "Alert on watchlist plates",
                checked = settings.alertOnWatchlist,
                explanation = "A distinct tone and vibration so you do not have to look at the screen.",
            ) { edit { s -> s.copy(alertOnWatchlist = it) } }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Spacer(Modifier.height(20.dp))
    Text(title.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    HorizontalDivider(Modifier.padding(vertical = 6.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RegionPicker(selectedId: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val selected = PlateRegions.byId(selectedId)

    Column(Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 10.dp)) {
        Text("Plate formats", fontWeight = FontWeight.Medium)
        Text(selected.label, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        Text(
            "Which layouts to look for. Personalised plates rarely match any of them — " +
                "switch to Generic if you are missing too many.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            PlateRegions.all.forEach { region ->
                DropdownMenuItem(
                    text = { Text(region.label) },
                    onClick = { open = false; onSelect(region.id) },
                )
            }
        }
    }
}

@Composable
private fun SwitchSetting(
    title: String,
    checked: Boolean,
    explanation: String,
    onChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(explanation, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderSetting(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    display: String,
    explanation: String,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(display, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
        Text(explanation, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
        )
    }
}
