package com.mymav.miniapps.food

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mymav.glasses.api.GlassesSession
import com.mymav.glasses.api.MymavAi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

@Composable
fun FoodMiniScreen(session: GlassesSession, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Ready — capture a meal or use glasses frame") }
    var cameraReady by remember { mutableStateOf(false) }
    var profile by remember { mutableStateOf(FoodDietaryPrefs.load(context)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Food", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Live CameraX capture → shared MyMav vision → HUD. " +
                "No placeholder success. Source is labelled phone vs glasses. No checkout.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "HUD: ${FoodScanEngine.phase} · AI: ${if (MymavAi.isConfigured()) "ok" else "missing key"}",
            style = MaterialTheme.typography.labelLarge,
        )
        HealthConnectRow()
        FoodCameraPreview(
            onPermission = { ok ->
                if (!ok) {
                    FoodScanEngine.markPermissionNeeded()
                    status = "Camera permission needed"
                } else {
                    FoodScanEngine.markReady()
                }
            },
            onCameraReady = { cameraReady = it },
        )
        Text("Dietary prefs (sent with analysis)")
        RowSwitch("Vegan", profile.vegan) {
            profile = profile.copy(vegan = it)
            FoodDietaryPrefs.save(context, profile)
        }
        RowSwitch("Vegetarian", profile.vegetarian) {
            profile = profile.copy(vegetarian = it)
            FoodDietaryPrefs.save(context, profile)
        }
        RowSwitch("Low salt", profile.lowSalt) {
            profile = profile.copy(lowSalt = it)
            FoodDietaryPrefs.save(context, profile)
        }
        OutlinedTextField(
            value = profile.allergensCsv,
            onValueChange = {
                profile = profile.copy(allergensCsv = it)
                FoodDietaryPrefs.save(context, profile)
            },
            label = { Text("Allergens to avoid (comma-separated)") },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(status, style = MaterialTheme.typography.bodySmall)
        FoodScanEngine.lastError.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Button(
            onClick = {
                scope.launch {
                    status = "Capturing phone CameraX…"
                    FoodScanEngine.pushPhaseHud(session, FoodScanPhase.Camera, "Capturing a photo of your meal...")
                    val (jpeg, err) = suspendCancellableCoroutine { cont ->
                        captureFoodJpeg(context) { bytes, e ->
                            if (cont.isActive) cont.resume(bytes to e)
                        }
                    }
                    if (jpeg == null) {
                        status = err ?: "Capture failed"
                        FoodScanEngine.markNoCamera(err ?: "Capture failed")
                        FoodScanEngine.pushPhaseHud(session, FoodScanPhase.NoCamera, err ?: "")
                        return@launch
                    }
                    status = "Analysing…"
                    val result = FoodScanEngine.runLiveScan(session, context, jpeg)
                    status = if (result != null) {
                        "Result: ${result.label} · ${result.source} · ${result.uncertainty}"
                    } else {
                        FoodScanEngine.lastError.ifBlank { "Scan failed" }
                    }
                }
            },
            enabled = cameraReady && FoodScanEngine.phase != FoodScanPhase.Analysing,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Capture meal (phone CameraX) → HUD") }
        Button(
            onClick = {
                scope.launch(Dispatchers.Main) {
                    status = "Trying glasses / simulator frame…"
                    val result = FoodScanEngine.runLiveScan(session, context, jpeg = null)
                    status = if (result != null) {
                        "Result: ${result.label} · ${result.source}"
                    } else {
                        FoodScanEngine.lastError.ifBlank { "No glasses frame" }
                    }
                }
            },
            enabled = FoodScanEngine.phase != FoodScanPhase.Analysing,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Analyse glasses / sim frame → HUD") }
        Button(
            onClick = {
                FoodScanEngine.cancel()
                status = "Cancelled"
                FoodScanEngine.pushPhaseHud(session, FoodScanPhase.Cancelled)
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Cancel scan") }
        Button(
            onClick = {
                FoodScanEngine.reset()
                FoodScanEngine.pushReadyHud(session)
                status = "Ready"
            },
        ) { Text("Reset ready HUD") }
        Button(onClick = onBack) { Text("Back to home") }
    }
}

@Composable
private fun RowSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** One-time Health Connect grant so "Save to Health" on the glasses can write meals Samsung Health syncs. */
@Composable
private fun HealthConnectRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf<Boolean?>(null) }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.health.connect.client.PermissionController.createRequestPermissionResultContract(),
    ) { result -> granted = result.containsAll(FoodHealthWriter.PERMISSIONS) }
    var available by remember { mutableStateOf(true) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        available = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            FoodHealthWriter.available(context)
        }
        granted = FoodHealthWriter.hasPermission(context)
    }
    Text(
        when {
            !available -> "Health Connect isn't available on this phone."
            granted == true -> "Samsung Health: connected (meals go via Health Connect)."
            else -> "Samsung Health: not connected. Save to Health needs this once."
        },
        style = MaterialTheme.typography.bodySmall,
    )
    if (available && granted != true) {
        Button(
            onClick = { scope.launch { launcher.launch(FoodHealthWriter.PERMISSIONS) } },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Connect Samsung Health") }
    }
}
