package com.mymav.miniapps.mirror

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mymav.glasses.api.GlassesHud
import com.mymav.glasses.api.GlassesSession
import com.mymav.glasses.api.HudAccentTone
import com.mymav.glasses.api.miniAppHomeActions

@Composable
fun MirrorMiniScreen(session: GlassesSession, onBack: () -> Unit) {
    var note by remember { mutableStateOf("Demo cast ready — full MediaProjection when user grants capture.") }
    var casting by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Mirror", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Mirror the phone screen onto Maverick glasses. Soft-glass phone tile + glasses HUD.")
        Text(note, style = MaterialTheme.typography.bodySmall, color = Color(0xFF6B7280))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            SoftGlassTile(
                title = "Phone",
                subtitle = if (casting) "Casting demo frame" else "Idle",
                accent = Color(0xFF64B5F6),
                modifier = Modifier.weight(1f),
            )
            SoftGlassTile(
                title = "Glasses",
                subtitle = if (casting) "HUD receiving" else "Waiting",
                accent = Color(0xFF4DD0E1),
                modifier = Modifier.weight(1f),
            )
        }

        Button(
            onClick = {
                casting = true
                note = "Demo cast → glasses (MediaProjection stub; OS dialog when live)."
                session.replaceHud(
                    GlassesHud.AppEnter(
                        appId = "mirror",
                        title = "Mirror",
                        subtitle = "Casting phone preview",
                        accent = HudAccentTone.Blue,
                        bullets = listOf(
                            "Demo frame on lens",
                            "MediaProjection path ready",
                            "Long-tap HOME to exit",
                        ),
                        metric = "LIVE",
                        actions = miniAppHomeActions(),
                        metricLabel = "cast",
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (casting) "Refresh cast HUD" else "Start demo cast") }

        OutlinedButton(
            onClick = {
                casting = false
                note = "Cast stopped."
                MirrorMiniApp.onEnter(session)
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Stop cast") }

        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back to home") }
    }
}

@Composable
private fun SoftGlassTile(
    title: String,
    subtitle: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .aspectRatio(0.72f)
            .clip(RoundedCornerShape(22.dp))
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF1A2330).copy(alpha = 0.92f), Color(0xFF0E141C).copy(alpha = 0.95f)),
                ),
            )
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(22.dp))
            .padding(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent),
            )
            Spacer(Modifier.height(8.dp))
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            Text(subtitle, color = Color(0xFFB0BEC5), fontSize = 12.sp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(accent.copy(alpha = 0.35f), Color(0xFF263238).copy(alpha = 0.8f)),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text("soft glass", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
            }
        }
    }
}
