package com.mymav.miniapps.shop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
fun ShopMiniScreen(session: GlassesSession, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Ready — capture a product (no checkout)") }
    var cameraReady by remember { mutableStateOf(false) }
    remember { ShopScanEngine.bindPhone(context); true }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Shop", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Recognition → SerpAPI Google Shopping offers on HUD. " +
                "Never invents store prices. No checkout.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "HUD: ${ShopScanEngine.phase} · AI: ${if (MymavAi.isConfigured()) "ok" else "missing"} · " +
                "Prices: ${if (MymavShopOffers.isConfigured()) "SerpAPI ok" else "setup needed"}",
            style = MaterialTheme.typography.labelLarge,
        )
        if (!MymavShopOffers.isConfigured()) {
            Text(
                MymavShopOffers.setupNeededMessage(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        ShopCameraPreview(
            onPermission = { ok ->
                if (!ok) {
                    ShopScanEngine.markPermissionNeeded()
                    status = "Camera permission needed"
                }
            },
            onCameraReady = { cameraReady = it },
        )
        Text(status, style = MaterialTheme.typography.bodySmall)
        ShopScanEngine.lastError.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        ShopScanEngine.lastResult?.let { r ->
            Text(
                "ID: ${r.title} · ${r.brand} ${r.variant} ${r.size}".trim(),
                style = MaterialTheme.typography.bodySmall,
            )
            if (r.offers.isNotEmpty()) {
                val o = ShopScanEngine.currentOffer()
                Text(
                    "Offer ${ShopScanEngine.offerIndex + 1}/${r.offers.size}: " +
                        "${o?.retailer} ${o?.priceLabel()} (${o?.matchKind})",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = {
                        ShopScanEngine.prevOffer(session)
                        status = "Offer ${ShopScanEngine.offerIndex + 1}/${r.offers.size}"
                    }, modifier = Modifier.weight(1f)) { Text("Previous") }
                    OutlinedButton(onClick = {
                        ShopScanEngine.nextOffer(session)
                        status = "Offer ${ShopScanEngine.offerIndex + 1}/${r.offers.size}"
                    }, modifier = Modifier.weight(1f)) { Text("Next") }
                }
                Button(
                    onClick = { status = ShopScanEngine.openCurrentOfferOnPhone(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open on phone") }
            } else {
                Text(r.offerDisclaimer, style = MaterialTheme.typography.bodySmall)
            }
        }
        Button(
            onClick = {
                scope.launch {
                    status = "Capturing phone CameraX…"
                    ShopScanEngine.pushPhaseHud(session, ShopScanPhase.Camera, "Capturing the product label...")
                    val (jpeg, err) = suspendCancellableCoroutine { cont ->
                        captureShopJpeg(context) { bytes, e ->
                            if (cont.isActive) cont.resume(bytes to e)
                        }
                    }
                    if (jpeg == null) {
                        status = err ?: "Capture failed"
                        ShopScanEngine.markNoCamera(err ?: "Capture failed")
                        ShopScanEngine.pushPhaseHud(session, ShopScanPhase.NoCamera, err ?: "")
                        return@launch
                    }
                    status = "Identifying + looking up prices…"
                    val result = ShopScanEngine.runLiveScan(session, jpeg)
                    status = when {
                        result == null -> ShopScanEngine.lastError.ifBlank { "Scan failed" }
                        result.offers.isNotEmpty() ->
                            "Offers: ${result.offers.size} · ${result.title}"
                        !result.providerReady -> "ID ok — SerpAPI setup needed for live prices"
                        result.matchQuality == "ambiguous" -> "Ambiguous — ${result.offerDisclaimer}"
                        else -> "No verified prices · ${result.title}"
                    }
                }
            },
            enabled = cameraReady &&
                ShopScanEngine.phase != ShopScanPhase.Analysing &&
                ShopScanEngine.phase != ShopScanPhase.LookingUp,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Capture product (phone CameraX) → offers HUD") }
        Button(
            onClick = {
                scope.launch {
                    status = "Trying glasses / simulator frame…"
                    val result = ShopScanEngine.runLiveScan(session, jpeg = null)
                    status = when {
                        result == null -> ShopScanEngine.lastError.ifBlank { "No glasses frame" }
                        result.offers.isNotEmpty() -> "Offers: ${result.offers.size}"
                        else -> result.offerDisclaimer.ifBlank { result.title }
                    }
                }
            },
            enabled = ShopScanEngine.phase != ShopScanPhase.Analysing &&
                ShopScanEngine.phase != ShopScanPhase.LookingUp,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Analyse glasses / sim frame → HUD") }
        Button(onClick = {
            ShopScanEngine.cancel()
            status = "Cancelled"
            ShopScanEngine.pushPhaseHud(session, ShopScanPhase.Cancelled)
        }) { Text("Cancel scan") }
        Button(onClick = {
            ShopScanEngine.reset()
            ShopScanEngine.pushReadyHud(session)
            status = "Ready"
        }) { Text("Reset ready HUD") }
        Button(onClick = onBack) { Text("Back to home") }
    }
}
