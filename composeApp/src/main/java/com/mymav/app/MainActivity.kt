package com.mymav.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.mymav.app.ui.MyMavRoot
import com.mymav.app.ui.MyMavTheme
import com.mymav.miniapps.notify.NotifySessionBridge
import com.mymav.miniapps.spotify.SpotifySessionBridge
import com.mymav.miniapps.spotify.api.SpotifyAuthManager
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleSpotifyRedirect(intent)
        enableEdgeToEdge()
        val session = (application as MyMavApp).glassesSession
        NotifySessionBridge.attach(session)
        SpotifySessionBridge.attach(session)
        setContent {
            MyMavTheme {
                MyMavRoot(session = session)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The foreground is the one moment Android always allows starting the service (e.g. after a force-stop).
        GlassesLinkService.start(this, retryNow = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSpotifyRedirect(intent)
    }

    private fun handleSpotifyRedirect(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != SpotifyAuthManager.REDIRECT_SCHEME ||
            uri.host != SpotifyAuthManager.REDIRECT_HOST
        ) {
            return
        }
        lifecycleScope.launch {
            SpotifyAuthManager(applicationContext).handleRedirect(uri)
        }
    }
}
