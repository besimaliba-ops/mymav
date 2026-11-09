package com.mymav.miniapps.identify

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.mymav.glasses.api.MymavShopOffers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

@Composable
fun IdentifyMiniScreen(session: GlassesSession, onBack: () -> Unit) {
    val status by session.statusMessage.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var note by remember {
        mutableStateOf("Phone CameraX preferred. Specific product ID — not generic categories.")
    }
    var cameraReady by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Identify", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Brand · fragrance/name · EDT/EDP · size from front label + barcode. " +
                "Uncertain → re-capture prompt. Find prices opens Shop with live SerpAPI offers.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Glasses: $status · AI: ${if (MymavAi.isConfigured()) "ok" else "missing"} · " +
                "Prices: ${if (MymavShopOffers.isConfigured()) "SerpAPI ok" else "setup needed"}",
            style = MaterialTheme.typography.bodySmall,
        )
        IdentifyCameraPreview(
            onPermission = { ok ->
                if (!ok) note = "Camera permission needed"
            },
            onCameraReady = { cameraReady = it },
        )
        Text(note, style = MaterialTheme.typography.bodySmall)
        IdentifyEngine.lastResult?.let { r ->
            Text(
                "Last: ${r.label} · ${if (r.confirmed) "confirmed" else "uncertain"}" +
                    if (r.needsCloserLook) " · ${r.recaptureHint}" else "",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Button(
            onClick = {
                scope.launch {
                    busy = true
                    note = "Capturing phone CameraX…"
                    val (jpeg, err) = suspendCancellableCoroutine { cont ->
                        captureIdentifyJpeg(context) { bytes, e ->
                            if (cont.isActive) cont.resume(bytes to e)
                        }
                    }
                    if (jpeg == null) {
                        note = err ?: "Capture failed"
                        busy = false
                        return@launch
                    }
                    note = "Identifying (source: phone CameraX)…"
                    val result = IdentifyEngine.runIdentify(
                        session,
                        jpeg = jpeg,
                        phoneSourceLabel = com.mymav.glasses.api.MymavCapture.PHONE_TEST_LABEL,
                    )
                    note = when {
                        result.needsCloserLook ->
                            "${result.recaptureHint.ifBlank { "Move closer to front label" }} — ${result.label}"
                        result.confirmed ->
                            "Confirmed: ${result.label} (${(result.confidence * 100).toInt()}%)"
                        else ->
                            "Uncertain: ${result.label} — ${result.detail}"
                    }
                    busy = false
                }
            },
            enabled = cameraReady && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Capture (phone CameraX) → HUD") }

        OutlinedButton(
            onClick = {
                IdentifyEngine.lastResult?.let {
                    IdentifyEngine.pushResultHud(session, it)
                    note = "Rescan from glasses footer or Capture again"
                }
            },
            enabled = !busy && IdentifyEngine.lastResult != null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Rescan · re-show result HUD") }
        Button(
            onClick = {
                scope.launch {
                    busy = true
                    note = "Looking up live prices via Shop…"
                    note = IdentifyEngine.findPrices(session)
                    busy = false
                }
            },
            enabled = !busy && IdentifyEngine.lastResult != null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Find prices") }

        Button(
            onClick = {
                scope.launch {
                    busy = true
                    note = "Trying glasses/sim frame only…"
                    val result = IdentifyEngine.runIdentify(session, jpeg = null)
                    note = "Result: ${result.label} — ${result.detail} [source=${result.source}]"
                    busy = false
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Identify from glasses/sim frame (optional)") }

        Button(
            onClick = {
                IdentifyMiniApp.onEnter(session)
                note = "Identify enter glasslet on lens"
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Show enter HUD") }

        Button(onClick = onBack) { Text("Back to home") }
    }
}
