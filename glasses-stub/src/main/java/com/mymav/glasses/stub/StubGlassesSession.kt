package com.mymav.glasses.stub

import android.content.Context
import com.mymav.glasses.api.ConnectionState
import com.mymav.glasses.api.GlassesSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class StubGlassesSession(
    private val appContext: Context,
) : GlassesSession {
    private val _connectionState = MutableStateFlow(ConnectionState.Idle)
    private val _statusMessage = MutableStateFlow("Stub backend — build with -Pmaverick.useSdk=true for real SDK")
    private val _simulatorHost = MutableStateFlow("127.0.0.1")
    private val _hasSdkKey = MutableStateFlow(false)
    private var hudTitle: String? = null

    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    override val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()
    override val simulatorHost: StateFlow<String> = _simulatorHost.asStateFlow()
    override val hasSdkKey: StateFlow<Boolean> = _hasSdkKey.asStateFlow()
    override val backendName: String = "stub"

    override fun init() {
        _connectionState.value = ConnectionState.Initializing
        _hasSdkKey.value = detectSdkKey()
        _connectionState.value = ConnectionState.Ready
        _statusMessage.value = if (_hasSdkKey.value) {
            "Stub ready (sdk.key found). Use real SDK build to connect ws://${_simulatorHost.value}:8765/ws"
        } else {
            "Stub ready. Place sdk.key in composeApp assets (gitignored)."
        }
    }

    override fun stop() {
        _connectionState.value = ConnectionState.Stopped
        _statusMessage.value = "Stopped"
        hudTitle = null
    }

    override fun setSimulatorHost(host: String) {
        _simulatorHost.value = host.trim().ifEmpty { "127.0.0.1" }
    }

    override fun connectSimulator() {
        _connectionState.value = ConnectionState.Connecting
        _statusMessage.value =
            "Stub: would connect to ws://${_simulatorHost.value}:8765/ws (enable maverick.useSdk)"
        _connectionState.value = ConnectionState.Connected
    }

    override fun connectDevice() {
        _connectionState.value = ConnectionState.Connecting
        _statusMessage.value = "Stub: would open BLE configure / device connect"
        _connectionState.value = ConnectionState.Connected
    }

    override fun disconnect() {
        _connectionState.value = ConnectionState.Ready
        _statusMessage.value = "Disconnected (stub)"
        hudTitle = null
    }

    override fun showConfigureUi() {
        _statusMessage.value = "Stub: configure UI unavailable without SDK"
    }

    override fun replaceHud(screenId: String, title: String, lines: List<String>) {
        hudTitle = title
        _statusMessage.value = "Stub HUD [$screenId]: $title — ${lines.joinToString(" | ")}"
    }

    override fun clearHud() {
        hudTitle = null
        _statusMessage.value = "Stub HUD cleared"
    }

    private fun detectSdkKey(): Boolean {
        return try {
            appContext.assets.open("sdk.key").use { it.available() > 0 }
        } catch (_: Exception) {
            val alt = File(appContext.filesDir, "sdk.key")
            alt.exists() && alt.length() > 0
        }
    }
}
