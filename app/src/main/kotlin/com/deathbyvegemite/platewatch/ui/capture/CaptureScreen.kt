package com.deathbyvegemite.platewatch.ui.capture

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deathbyvegemite.platewatch.capture.PlateAnalyzer
import com.deathbyvegemite.platewatch.ui.formatConfidence
import com.deathbyvegemite.platewatch.ui.rememberAppContainer
import com.deathbyvegemite.platewatch.ui.formatTime
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import kotlinx.coroutines.launch

@Composable
fun CaptureScreen(
    onOpenLog: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSighting: (Long) -> Unit,
    viewModel: CaptureViewModel = viewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val settingsContainer = rememberAppContainer()
    val scope = rememberCoroutineScope()

    var hasCamera by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        hasCamera = granted[Manifest.permission.CAMERA] == true
    }

    LaunchedEffect(Unit) {
        if (!hasCamera) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
    }

    if (!hasCamera) {
        PermissionGate(
            onRequest = {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.CAMERA,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                )
            },
        )
        return
    }

    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val recognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    val analyzer = remember(viewModel) {
        PlateAnalyzer(
            recognizer = recognizer,
            callbackExecutor = analysisExecutor,
            config = viewModel::analyzerConfig,
            onResult = viewModel::onFrameResult,
            onObservation = viewModel::onObservation,
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            analysisExecutor.shutdown()
            recognizer.close()
        }
    }

    var camera by remember { mutableStateOf<Camera?>(null) }
    var torchOn by remember { mutableStateOf(false) }

    val resolution = AnalysisResolution.byId(state.settings.analysisResolution)
    val wantStills = state.settings.hiResStills && state.settings.savePhotos

    // Rebinding on `running` means an idle app is not feeding frames to the recogniser.
    // The bound camera is handed to the view model, which owns zoom and metering.
    LaunchedEffect(state.running, resolution, wantStills) {
        val bound = bindCamera(
            context = context,
            lifecycleOwner = lifecycleOwner,
            previewView = previewView,
            analyzer = if (state.running) analyzer else null,
            analysisExecutor = analysisExecutor,
            resolution = resolution,
            wantStills = wantStills,
        )
        camera = bound?.camera
        viewModel.attachControls(bound?.let { CameraXControls(it, analysisExecutor) })
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.attachControls(null) }
    }

    LaunchedEffect(camera, torchOn) { camera?.cameraControl?.enableTorch(torchOn) }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        StatusBar(
            running = state.running,
            sessionCount = state.sessionCount,
            hasFix = state.hasLocationFix,
            accuracy = state.locationAccuracyMeters,
            pending = state.pendingPlates,
            torchOn = torchOn,
            onToggleTorch = { torchOn = !torchOn },
            onOpenLog = onOpenLog,
            onOpenSettings = onOpenSettings,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding(),
        )

        state.watchlistAlert?.let { plate ->
            WatchlistBanner(
                plate = plate,
                onDismiss = viewModel::dismissWatchlistAlert,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        ControlPanel(
            running = state.running,
            recent = state.recent,
            autoZoom = state.settings.autoZoom,
            tracking = state.tracking,
            zoomRatio = state.zoomRatio,
            baseZoom = state.settings.baseZoomRatio,
            maxZoom = state.settings.maxAutoZoom,
            onZoomStep = viewModel::onZoomStep,
            onAutoZoomChange = { enabled ->
                scope.launch { settingsContainer.settingsStore.update { it.copy(autoZoom = enabled) } }
            },
            onToggleRunning = {
                if (state.running) viewModel.onCaptureStopped() else viewModel.onCaptureStarted()
            },
            onOpenSighting = onOpenSighting,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        )
    }
}

@Composable
private fun PermissionGate(onRequest: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Camera access needed", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Text(
                "PlateWatch reads plates from the camera preview and stamps each one " +
                    "with a location. Nothing leaves the phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onRequest) { Text("Grant permissions") }
        }
    }
}

@Composable
private fun StatusBar(
    running: Boolean,
    sessionCount: Int,
    hasFix: Boolean,
    accuracy: Float?,
    pending: List<String>,
    torchOn: Boolean,
    onToggleTorch: () -> Unit,
    onOpenLog: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(12.dp),
        color = Color.Black.copy(alpha = 0.65f),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Everything in this inner row is status text, not a control — it is
                // the one part of the bar allowed to run out of room. Weighting it
                // (rather than the trailing icons) means growth here — a long
                // session count, in particular — truncates instead of silently
                // pushing GPS status, torch, log or Settings off the edge of the
                // screen. This is deliberately the *only* thing that competes for
                // space with those controls: the zoom readout that used to sit here
                // was removed rather than fought with, since the control panel below
                // already shows zoom, bigger, at all times.
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (running) "LOGGING" else "PAUSED",
                        color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "$sessionCount this session",
                        fontSize = 13.sp,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    imageVector = if (hasFix) Icons.Default.LocationOn else Icons.Default.LocationOff,
                    contentDescription = if (hasFix) "GPS fix" else "No GPS fix",
                    tint = if (hasFix) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
                accuracy?.let {
                    Text(" ±${it.toInt()}m", fontSize = 12.sp, color = Color.White, maxLines = 1)
                }
                IconButton(onClick = onToggleTorch) {
                    Icon(
                        imageVector = if (torchOn) Icons.Default.Bolt else Icons.Default.FlashOff,
                        contentDescription = "Torch",
                        tint = if (torchOn) MaterialTheme.colorScheme.secondary else Color.White,
                    )
                }
                IconButton(onClick = onOpenLog) {
                    Icon(Icons.AutoMirrored.Filled.List, "Log", tint = Color.White)
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, "Settings", tint = Color.White)
                }
            }
            if (pending.isNotEmpty()) {
                Text(
                    "reading ${pending.joinToString(" ")}",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}

@Composable
private fun WatchlistBanner(plate: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.padding(24.dp).clickable(onClick = onDismiss),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.error),
    ) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("WATCHLIST", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
            Text(plate, fontSize = 30.sp, fontFamily = FontFamily.Monospace, color = Color.White)
            Text("tap to dismiss", fontSize = 11.sp, color = Color.White.copy(alpha = 0.8f))
        }
    }
}

@Composable
private fun ControlPanel(
    running: Boolean,
    recent: List<LoggedPlate>,
    autoZoom: Boolean,
    tracking: Boolean,
    zoomRatio: Float,
    baseZoom: Float,
    maxZoom: Float,
    onZoomStep: (Int) -> Unit,
    onAutoZoomChange: (Boolean) -> Unit,
    onToggleRunning: () -> Unit,
    onOpenSighting: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(12.dp),
        color = Color.Black.copy(alpha = 0.65f),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            if (recent.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(recent, key = { it.sightingId }) { logged ->
                        RecentChip(logged, onClick = { onOpenSighting(logged.sightingId) })
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            // Always on screen — tapping +/− works whether auto-zoom is on or off,
            // and takes over from wherever tracking currently has the lens, the way a
            // camera app's own zoom does when you grab it away from autofocus.
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onZoomStep(-1) }) {
                    Icon(Icons.Default.Remove, "Zoom out", tint = Color.White)
                }
                Text(
                    "${"%.1f".format(zoomRatio)}\u00d7",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (tracking) MaterialTheme.colorScheme.secondary else Color.White,
                    modifier = Modifier.width(64.dp),
                    textAlign = TextAlign.Center,
                )
                IconButton(onClick = { onZoomStep(1) }) {
                    Icon(Icons.Default.Add, "Zoom in", tint = Color.White)
                }
                Spacer(Modifier.weight(1f))
                Text("Auto", fontSize = 12.sp, color = Color.White)
                Switch(checked = autoZoom, onCheckedChange = onAutoZoomChange)
            }
            Text(
                text = if (autoZoom) {
                    "Rests at ${"%.1f".format(baseZoom)}\u00d7, follows plates up to ${"%.1f".format(maxZoom)}\u00d7"
                } else {
                    "Manual \u2014 tracking will not move the zoom"
                },
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            Button(
                onClick = onToggleRunning,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (running) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                ),
            ) {
                Icon(if (running) Icons.Default.Stop else Icons.Default.PlayArrow, null)
                Spacer(Modifier.width(8.dp))
                Text(if (running) "Stop logging" else "Start logging", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun RecentChip(logged: LoggedPlate, onClick: () -> Unit) {
    Card(
        modifier = Modifier.clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (logged.onWatchlist) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(
                logged.plate,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
            )
            Text(
                listOfNotNull(
                    formatTime(logged.atEpochMs),
                    logged.color,
                    formatConfidence(logged.confidence),
                ).joinToString(" · "),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
