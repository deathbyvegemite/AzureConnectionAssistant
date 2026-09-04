package com.deathbyvegemite.platewatch.ui.log

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deathbyvegemite.platewatch.data.db.PlateSummary
import com.deathbyvegemite.platewatch.data.db.SightingEntity
import com.deathbyvegemite.platewatch.ui.formatConfidence
import com.deathbyvegemite.platewatch.ui.formatDateTime
import com.deathbyvegemite.platewatch.ui.rememberAppContainer
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    onBack: () -> Unit,
    onOpenSighting: (Long) -> Unit,
    onOpenWatchlist: () -> Unit,
) {
    val container = rememberAppContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    val sightingsFlow = remember(query) {
        if (query.isBlank()) container.repository.observeRecent() else container.repository.search(query)
    }
    val sightings by sightingsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val repeats by remember { container.repository.observeRepeatPlates() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val total by remember { container.repository.observeCount() }
        .collectAsStateWithLifecycle(initialValue = 0)

    val csvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        if (uri != null) scope.launch {
            val content = container.repository.exportCsv()
            context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
        }
    }

    val jsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) scope.launch {
            val content = container.repository.exportJson()
            context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Log · $total") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenWatchlist) { Icon(Icons.Default.Visibility, "Watchlist") }
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Export CSV") },
                            onClick = { menuOpen = false; csvLauncher.launch("platewatch-log.csv") },
                        )
                        DropdownMenuItem(
                            text = { Text("Export JSON") },
                            onClick = { menuOpen = false; jsonLauncher.launch("platewatch-log.json") },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Delete everything") },
                            onClick = { menuOpen = false; confirmClear = true },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Sightings") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Repeat plates") })
            }

            if (tab == 0) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search plate, street, make") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                )
                if (sightings.isEmpty()) {
                    EmptyState(
                        if (query.isBlank()) "Nothing logged yet." else "No matches for \"$query\".",
                    )
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(sightings, key = { it.id }) { sighting ->
                            SightingRow(sighting) { onOpenSighting(sighting.id) }
                        }
                    }
                }
            } else {
                if (repeats.isEmpty()) {
                    EmptyState("No plate has been seen more than once yet.")
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(repeats, key = { it.plate }) { summary -> RepeatRow(summary) }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete the whole log?") },
            text = { Text("Every sighting and every saved crop goes. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch { container.repository.deleteEverything() }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EmptyState(message: String) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SightingRow(sighting: SightingEntity, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        sighting.plate,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp,
                    )
                    if (sighting.flagged) {
                        Icon(
                            Icons.Default.Flag,
                            "Flagged",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
                Text(
                    formatDateTime(sighting.firstSeenEpochMs),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val descriptors = listOfNotNull(
                    sighting.vehicleColor,
                    sighting.vehicleMake,
                    sighting.vehicleModel,
                    sighting.address,
                )
                if (descriptors.isNotEmpty()) {
                    Text(
                        descriptors.joinToString(" · "),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                formatConfidence(sighting.confidence),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun RepeatRow(summary: PlateSummary) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    summary.plate,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 19.sp,
                )
                Text(
                    "first ${formatDateTime(summary.firstSeenEpochMs)} · last ${formatDateTime(summary.lastSeenEpochMs)}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                listOfNotNull(summary.vehicleColor, summary.vehicleMake, summary.vehicleModel)
                    .takeIf { it.isNotEmpty() }
                    ?.let {
                        Text(
                            it.joinToString(" · "),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
            }
            Text(
                "${summary.sightings}×",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}
