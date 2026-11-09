package com.mymav.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mymav.glasses.api.GazeKeyboardCatalog
import com.mymav.glasses.api.HomeBoardCatalog
import com.mymav.glasses.api.HomeWidget
import com.mymav.glasses.api.HomeWidgetSize
import com.mymav.glasses.api.HudAccentTone
import com.mymav.glasses.api.KeyboardMode
import com.mymav.glasses.api.MiniAppRegistry

private val LensInk = Color(0xFF0A1218)
private val LensPanel = Color(0xF0141C26)
private val LensBorder = Color(0x664A5A6B)
private val LensFocus = Color(0xFF45D6F5)
private val LensMuted = Color(0xFF93A3B5)

/**
 * Soft lens-frame mock of the 640×400 glasses HUD for phone preview.
 */
@Composable
fun HudLensPreview(
    mode: LensPreviewMode,
    selectedAppId: String?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(640f / 400f)
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF1A2834), LensInk, Color(0xFF05080C)),
                ),
            )
            .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(28.dp))
            .padding(10.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF0D1520))
                .border(1.dp, LensBorder, RoundedCornerShape(18.dp)),
        ) {
            when (mode) {
                LensPreviewMode.Home -> HomeBoardLensContent()
                LensPreviewMode.App -> AppHudLensContent(selectedAppId)
                LensPreviewMode.Keyboard -> KeyboardLensContent()
            }
        }
    }
}

enum class LensPreviewMode { Home, App, Keyboard }

@Composable
private fun HomeBoardLensContent() {
    val pageIndex = 0
    val placed = HomeBoardCatalog.layoutPage(pageIndex)
    val pageCount = HomeBoardCatalog.pageCount()
    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SoftChip("MyMav · HOME")
            SoftChip("92%")
        }
        Spacer(Modifier.height(8.dp))
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val gap = 6.dp
            val cellW = (maxWidth - gap) / 2
            val cellH = (maxHeight - gap) / 2
            placed.forEach { p ->
                WidgetGlassTile(
                    widget = p.widget,
                    modifier = Modifier
                        .offset(
                            x = cellW * p.col + gap * p.col,
                            y = cellH * p.row + gap * p.row,
                        )
                        .width(cellW * p.colSpan + gap * (p.colSpan - 1))
                        .height(cellH * p.rowSpan + gap * (p.rowSpan - 1)),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(pageCount.coerceAtMost(6)) { i ->
                Box(
                    modifier = Modifier
                        .padding(horizontal = 3.dp)
                        .size(if (i == pageIndex) 7.dp else 5.dp)
                        .clip(CircleShape)
                        .background(if (i == pageIndex) LensFocus else LensBorder),
                )
            }
        }
    }
}

@Composable
private fun AppHudLensContent(selectedAppId: String?) {
    val app = selectedAppId?.let { MiniAppRegistry.get(it) }
        ?: MiniAppRegistry.all().firstOrNull()
    val widget = HomeBoardCatalog.widgets.firstOrNull { it.id == app?.descriptor?.id }
        ?: HomeBoardCatalog.widgets.firstOrNull()
    Column(
        modifier = Modifier.fillMaxSize().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SoftChip((app?.descriptor?.title ?: "Mini-app").uppercase())
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(18.dp))
                .background(LensPanel)
                .border(1.dp, LensFocus.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
                .padding(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    app?.descriptor?.title ?: "App",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                )
                Text(
                    app?.descriptor?.subtitle ?: "",
                    color = LensMuted,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    widget?.metric?.ifBlank { "—" } ?: "—",
                    color = accentColor(widget?.accent ?: HudAccentTone.Teal),
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp,
                )
                Text(
                    widget?.metricDetail ?: "HUD preview",
                    color = LensMuted,
                    fontSize = 11.sp,
                )
                Text(
                    "Size ${widget?.size?.label ?: "1×1"} · glasses enter surface",
                    color = LensMuted.copy(alpha = 0.8f),
                    fontSize = 10.sp,
                )
            }
        }
    }
}

@Composable
private fun KeyboardLensContent() {
    val mode = KeyboardMode.Emoji
    val categoryId = "smileys"
    val pageIndex = 0
    val buffer = "Hello, 😎"
    val (keys, pageCount) = GazeKeyboardCatalog.visibleKeys(mode, categoryId, pageIndex, "")
    val emojiKeys = keys.filter { it.startsWith("emoji_") }
    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        SoftChip("KEYBOARD")
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(LensPanel)
                .border(1.dp, LensBorder, RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(buffer, color = Color.White, fontSize = 13.sp)
        }
        Text(
            "EMOJI · ${pageIndex + 1}/$pageCount",
            color = LensMuted,
            fontSize = 9.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            emojiKeys.take(6).forEachIndexed { i, keyId ->
                val label = GazeKeyboardCatalog.labelForKey(keyId, mode, categoryId, pageIndex, "")
                val focused = i == 3
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(LensPanel)
                        .border(
                            if (focused) 1.5.dp else 1.dp,
                            if (focused) LensFocus else LensBorder,
                            RoundedCornerShape(8.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, fontSize = 12.sp)
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("DEL", "CLEAR", "ABC", "Emojis", "Search").forEachIndexed { i, label ->
                val active = i == 1
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(26.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (active) LensFocus.copy(alpha = 0.25f) else LensPanel)
                        .border(1.dp, if (active) LensFocus else LensBorder, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun WidgetGlassTile(widget: HomeWidget, modifier: Modifier = Modifier) {
    val previewBmp = remember(widget.id, widget.previewImageBytes) {
        val bytes = widget.previewImageBytes ?: return@remember null
        if (widget.previewImageWidth <= 0 || widget.previewImageHeight <= 0) return@remember null
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(LensPanel)
            .border(1.dp, LensBorder, RoundedCornerShape(14.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accentColor(widget.accent)),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        widget.title.uppercase(),
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        widget.subtitle,
                        color = LensMuted,
                        fontSize = 8.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (previewBmp != null) {
                    Image(
                        bitmap = previewBmp,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(6.dp)),
                    )
                }
            }
        }
        Column {
            Text(
                widget.metric.ifBlank { "—" },
                color = accentColor(widget.accent),
                fontSize = if (widget.size == HomeWidgetSize.Small) 14.sp else 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(widget.metricDetail, color = LensMuted, fontSize = 8.sp)
        }
    }
}

@Composable
private fun SoftChip(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(LensPanel)
            .border(1.dp, LensBorder, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, color = Color.White, fontSize = 9.sp, maxLines = 1)
    }
}

private fun accentColor(tone: HudAccentTone): Color = when (tone) {
    HudAccentTone.Teal -> Color(0xFF4DD0E1)
    HudAccentTone.Green, HudAccentTone.Spotify -> Color(0xFF66BB6A)
    HudAccentTone.Yellow -> Color(0xFFFFD54F)
    HudAccentTone.Red -> Color(0xFFEF5350)
    HudAccentTone.Blue -> Color(0xFF64B5F6)
    HudAccentTone.Orange -> Color(0xFFFFB74D)
    HudAccentTone.Purple -> Color(0xFFBA68C8)
}
