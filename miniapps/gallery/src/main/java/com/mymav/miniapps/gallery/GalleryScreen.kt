package com.mymav.miniapps.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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

private data class DemoMedia(val id: String, val title: String, val kind: String, val color: Color)

@Composable
fun GalleryMiniScreen(session: GlassesSession, onBack: () -> Unit) {
    var note by remember { mutableStateOf("Demo thumbnails — grant READ_MEDIA_* for real gallery.") }
    val items = remember {
        listOf(
            DemoMedia("1", "Sunset quay", "PHOTO", Color(0xFFFF8A65)),
            DemoMedia("2", "City walk", "VIDEO", Color(0xFFBA68C8)),
            DemoMedia("3", "Cafe table", "PHOTO", Color(0xFF4DD0E1)),
            DemoMedia("4", "Match clip", "VIDEO", Color(0xFF66BB6A)),
            DemoMedia("5", "Desk setup", "PHOTO", Color(0xFFFFD54F)),
            DemoMedia("6", "Night ride", "VIDEO", Color(0xFF64B5F6)),
        )
    }
    var selected by remember { mutableStateOf(items.first()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Gallery", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Browse phone pictures and videos on the glasses HUD.")
        Text(note, style = MaterialTheme.typography.bodySmall, color = Color(0xFF6B7280))

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items, key = { it.id }) { media ->
                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(media.color.copy(alpha = 0.85f), Color(0xFF1A1A1A)),
                            ),
                        )
                        .border(
                            if (media.id == selected.id) 2.dp else 1.dp,
                            if (media.id == selected.id) Color.White else media.color.copy(alpha = 0.4f),
                            RoundedCornerShape(16.dp),
                        )
                        .padding(8.dp),
                    contentAlignment = Alignment.BottomStart,
                ) {
                    Column {
                        Text(media.kind, color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp)
                        Text(media.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(0.dp)
                            .align(Alignment.Center),
                    ) {
                        // tap via button below for simplicity on emulator
                    }
                }
            }
        }

        Button(
            onClick = {
                selected = items[(items.indexOf(selected) + 1) % items.size]
                note = "Showing ${selected.title} (${selected.kind}) on glasses"
                session.replaceHud(
                    GlassesHud.AppEnter(
                        appId = "gallery",
                        title = selected.title,
                        subtitle = "${selected.kind} · demo thumbnail",
                        accent = HudAccentTone.Purple,
                        bullets = listOf(
                            "Soft glass media frame",
                            "Next item with button / gaze",
                            "Picker path when permitted",
                        ),
                        metric = selected.kind.take(3),
                        actions = miniAppHomeActions(),
                        metricLabel = "media",
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Push selected to glasses") }

        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back to home") }
    }
}
