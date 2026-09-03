package com.deathbyvegemite.platewatch.ui.detail

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deathbyvegemite.platewatch.data.db.SightingEntity
import com.deathbyvegemite.platewatch.ui.formatConfidence
import com.deathbyvegemite.platewatch.ui.formatCoordinates
import com.deathbyvegemite.platewatch.ui.formatDateTime
import com.deathbyvegemite.platewatch.ui.formatSpeed
import com.deathbyvegemite.platewatch.ui.rememberAppContainer
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SightingDetailScreen(sightingId: Long, onBack: () -> Unit) {
    val container = rememberAppContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val sighting by remember(sightingId) { container.repository.observeById(sightingId) }
        .collectAsStateWithLifecycle(initialValue = null)

    var make by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var bodyType by remember { mutableStateOf("") }
    var color by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var loadedFor by remember { mutableStateOf(-1L) }

    // Seed the editable fields once, so typing is not clobbered by the Room flow
    // re-emitting after every save.
    LaunchedEffect(sighting?.id) {
        val s = sighting ?: return@LaunchedEffect
        if (loadedFor != s.id) {
            make = s.vehicleMake.orEmpty()
            model = s.vehicleModel.orEmpty()
            bodyType = s.vehicleBodyType.orEmpty()
            color = s.vehicleColor.orEmpty()
            notes = s.notes.orEmpty()
            loadedFor = s.id
        }
    }

    val current = sighting

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(current?.plate ?: "Sighting") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    current?.let { s ->
                        IconButton(onClick = {
                            scope.launch { container.repository.setFlagged(s.id, !s.flagged) }
                        }) {
                            // One icon, two tints: importing both the filled and
                            // outlined `Flag` would collide on the simple name.
                            Icon(
                                Icons.Default.Flag,
                                if (s.flagged) "Flagged" else "Not flagged",
                                tint = if (s.flagged) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        IconButton(onClick = {
                            scope.launch { container.repository.addToWatchlist(s.plate, null) }
                        }) { Icon(Icons.Default.Visibility, "Add to watchlist") }
                        IconButton(onClick = {
                            scope.launch {
                                container.repository.deleteSighting(s.id)
                                onBack()
                            }
                        }) { Icon(Icons.Default.Delete, "Delete") }
                    }
                },
            )
        },
    ) { padding ->
        if (current == null) {
            Column(
                Modifier.padding(padding).fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { Text("This sighting is gone.") }
            return@Scaffold
        }

        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            Text(
                current.plate,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 34.sp,
            )
            Text(
                "read as \"${current.rawPlate}\" · ${current.readCount} frames · ${formatConfidence(current.confidence)} confident",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            CropImage(current.plateImagePath, "Plate crop")
            CropImage(current.vehicleImagePath, "Vehicle crop")

            Spacer(Modifier.height(16.dp))
            DetailRow("First seen", formatDateTime(current.firstSeenEpochMs))
            DetailRow("Last seen", formatDateTime(current.lastSeenEpochMs))
            DetailRow("Address", current.address ?: "not resolved")
            DetailRow("Coordinates", formatCoordinates(current.latitude, current.longitude) ?: "no fix")
            DetailRow("GPS accuracy", current.accuracyMeters?.let { "±${it.toInt()} m" } ?: "—")
            DetailRow("Speed at capture", formatSpeed(current.speedMps) ?: "—")
            DetailRow("Heading", current.bearingDegrees?.let { "${it.toInt()}°" } ?: "—")
            DetailRow("Plate format", current.formatId ?: "—")

            if (current.latitude != null && current.longitude != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    val label = Uri.encode(current.plate)
                    val uri = Uri.parse("geo:${current.latitude},${current.longitude}?q=${current.latitude},${current.longitude}($label)")
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                }) {
                    Icon(Icons.Default.Map, null)
                    Text("  Open in maps")
                }
            }

            Spacer(Modifier.height(24.dp))
            Text("Vehicle details", fontWeight = FontWeight.Bold)
            Text(
                "Colour is estimated from the pixels above the plate. Make and model are " +
                    "yours to fill in — three seconds of typing beats a wrong guess.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            EditField("Colour", color) { color = it }
            EditField("Make", make) { make = it }
            EditField("Model", model) { model = it }
            EditField("Body type", bodyType) { bodyType = it }
            EditField("Notes", notes, singleLine = false) { notes = it }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    scope.launch {
                        container.repository.setVehicleDetails(
                            id = current.id,
                            make = make,
                            model = model,
                            bodyType = bodyType,
                            color = color,
                            notes = notes,
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save details") }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun CropImage(path: String?, label: String) {
    if (path.isNullOrEmpty()) return
    val bitmap = remember(path) {
        runCatching {
            if (File(path).exists()) BitmapFactory.decodeFile(path) else null
        }.getOrNull()
    } ?: return

    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.padding(8.dp)) {
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = label,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)),
            )
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            modifier = Modifier.width(140.dp),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, fontSize = 13.sp)
    }
}

@Composable
private fun EditField(
    label: String,
    value: String,
    singleLine: Boolean = true,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}
