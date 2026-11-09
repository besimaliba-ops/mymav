package com.mymav.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed as listItemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mymav.glasses.api.HomeBoardCatalog
import com.mymav.glasses.api.HomeWidgetSize
import com.mymav.glasses.api.HudAccentTone
import com.mymav.glasses.api.MiniApp
import com.mymav.glasses.api.MiniAppOrderStore
import com.mymav.glasses.api.MiniAppRegistry
import kotlin.math.roundToInt

private val TileTop = Color(0xFF19232E)
private val TileBottom = Color(0xFF111821)
private val TileBorder = MyMavColors.Hairline
private val Ink = MyMavColors.Text
private val Muted = MyMavColors.TextMuted
private val SelectedStroke = MyMavColors.Accent

@Composable
fun AppsScreen(
    orderStore: MiniAppOrderStore,
    contentPadding: PaddingValues,
    onOpenMini: (String) -> Unit,
    onOrderChanged: () -> Unit,
) {
    var apps by remember { mutableStateOf(MiniAppRegistry.all()) }
    var note by remember {
        mutableStateOf("")
    }
    var sizeMenuFor by remember { mutableStateOf<MiniApp?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val uiPrefs = remember { ctx.getSharedPreferences("mymav_ui", android.content.Context.MODE_PRIVATE) }
    var listMode by remember { mutableStateOf(uiPrefs.getBoolean("apps_list", false)) }
    var query by remember { mutableStateOf("") }
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOverIndex by remember { mutableIntStateOf(-1) }
    var dragAccumX by remember { mutableFloatStateOf(0f) }
    var dragAccumY by remember { mutableFloatStateOf(0f) }
    val gridState = rememberLazyGridState()
    val density = LocalDensity.current
    val cellApproxPx = with(density) { 120.dp.toPx() }

    val shown = if (query.isBlank()) apps else apps.filter { it.descriptor.title.contains(query.trim(), ignoreCase = true) }

    fun persistOrder(ids: List<String>) {
        orderStore.saveOrder(ids)
        apps = MiniAppRegistry.all()
        onOrderChanged()
    }

    fun persistSize(id: String, size: HomeWidgetSize) {
        orderStore.saveSize(id, size)
        apps = MiniAppRegistry.all()
        onOrderChanged()
        note = "Size for glasses home · ${size.label}"
    }

    MyMavShellBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .testTag("screen_apps")
                .padding(contentPadding),
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Spacer(modifier = Modifier.height(8.dp))
                ScreenHeader("Apps", "Tap to open \u00b7 hold or \u2191\u2193 to reorder")
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KitTextField(value = query, onValueChange = { query = it }, label = "Search apps", placeholder = "Search apps", modifier = Modifier.weight(1f))
                    KitChip("Grid", !listMode, { listMode = false; uiPrefs.edit().putBoolean("apps_list", false).apply() })
                    KitChip("List", listMode, { listMode = true; uiPrefs.edit().putBoolean("apps_list", true).apply() })
                }
                if (note.isNotBlank()) Text(
                    note,
                    color = MyMavColors.Accent,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (shown.isEmpty()) {
                Text("No apps match \"$query\".", color = MyMavColors.TextMuted, modifier = Modifier.padding(24.dp))
            } else if (listMode) {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    listItemsIndexed(shown, key = { _, app -> app.descriptor.id }) { _, app ->
                        val index = apps.indexOf(app)
                        AppRow(
                            appId = app.descriptor.id,
                            title = app.descriptor.title,
                            status = HomeBoardCatalog.statusLine(app.descriptor.id),
                            sizeLabel = HomeBoardCatalog.sizeOf(app.descriptor.id).label,
                            glyph = appGlyph(app.descriptor.id),
                            accent = appAccent(app.descriptor.id),
                            canMove = query.isBlank(),
                            onUp = { if (index > 0) { persistOrder(MiniAppRegistry.reorder(index, index - 1)); note = "Moved up \u00b7 saved to glasses home" } },
                            onDown = { if (index < apps.lastIndex) { persistOrder(MiniAppRegistry.reorder(index, index + 1)); note = "Moved down \u00b7 saved to glasses home" } },
                            onOpen = { onOpenMini(app.descriptor.id) },
                            onMenu = { sizeMenuFor = app },
                        )
                    }
                    item { Spacer(Modifier.height(90.dp)) }
                }
            } else LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = gridState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f),
            ) {
                itemsIndexed(shown, key = { _, app -> app.descriptor.id }) { shownIndex, app ->
                    val index = apps.indexOf(app)
                    val size = HomeBoardCatalog.sizeOf(app.descriptor.id)
                    val status = HomeBoardCatalog.statusLine(app.descriptor.id)
                    val accent = appAccent(app.descriptor.id)
                    val selected = draggingIndex == index || dragOverIndex == index
                    AppTile(
                        appId = app.descriptor.id,
                        title = app.descriptor.title,
                        status = status,
                        sizeLabel = size.label,
                        selected = selected,
                        glyph = appGlyph(app.descriptor.id),
                        accent = accent,
                        onOpen = { onOpenMini(app.descriptor.id) },
                        onMenu = { sizeMenuFor = app },
                        modifier = if (query.isNotBlank()) Modifier else Modifier.pointerInput(app.descriptor.id, apps.size) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggingIndex = index
                                    dragOverIndex = index
                                    dragAccumX = 0f
                                    dragAccumY = 0f
                                    note = "Dragging ${app.descriptor.title}…"
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragAccumX += dragAmount.x
                                    dragAccumY += dragAmount.y
                                    val colDelta = (dragAccumX / cellApproxPx).roundToInt()
                                    val rowDelta = (dragAccumY / cellApproxPx).roundToInt()
                                    val target = (index + colDelta + rowDelta * 3)
                                        .coerceIn(0, apps.lastIndex)
                                    dragOverIndex = target
                                },
                                onDragEnd = {
                                    val from = draggingIndex
                                    val to = dragOverIndex
                                    if (from >= 0 && to >= 0 && from != to) {
                                        persistOrder(MiniAppRegistry.reorder(from, to))
                                        note = "Reordered · persisted to glasses home"
                                    }
                                    draggingIndex = -1
                                    dragOverIndex = -1
                                },
                                onDragCancel = {
                                    draggingIndex = -1
                                    dragOverIndex = -1
                                },
                            )
                        },
                    )
                }
                item(span = { GridItemSpan(3) }) {
                    Text(
                        "Tap ••• on a tile to set its size on the glasses home (1×1, 1×2, 2×1, 2×2).",
                        color = MyMavColors.TextFaint,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
        }
    }

    sizeMenuFor?.let { app ->
        val current = HomeBoardCatalog.sizeOf(app.descriptor.id)
        AlertDialog(
            onDismissRequest = { sizeMenuFor = null },
            title = { Text("Size for glasses home · ${app.descriptor.title}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Current: ${current.label}. This packs the glasses home widget board.")
                    HomeWidgetSize.entries.forEach { size ->
                        TextButton(onClick = {
                            persistSize(app.descriptor.id, size)
                            sizeMenuFor = null
                        }) {
                            Text(if (size == current) "● ${size.label}" else size.label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { sizeMenuFor = null }) { Text("Close") }
            },
        )
    }
}

@Composable
private fun AppTile(
    appId: String,
    title: String,
    status: String,
    sizeLabel: String,
    selected: Boolean,
    glyph: String,
    accent: Color,
    onOpen: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(0.78f)
            .shadow(if (selected) 10.dp else 0.dp, shape, clip = false)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(TileTop, TileBottom)))
            .border(width = if (selected) 2.dp else 1.dp, color = if (selected) SelectedStroke else TileBorder, shape = shape)
            .clickable(onClick = onOpen)
            .padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            // the same jewel badge the glasses show on their home board
            AppBadge(appId, glyph, accent, size = 52.dp)
            Text(
                "\u2022\u2022\u2022",
                color = Muted,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onMenu).padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(status, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                sizeLabel,
                color = accent,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(8.dp)).background(accent.copy(alpha = 0.14f)).padding(horizontal = 7.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun AppRow(
    appId: String,
    title: String,
    status: String,
    sizeLabel: String,
    glyph: String,
    accent: Color,
    canMove: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onOpen: () -> Unit,
    onMenu: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(TileTop, TileBottom)))
            .border(1.dp, TileBorder, shape)
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppBadge(appId, glyph, accent, size = 44.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(status, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            sizeLabel,
            color = accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(accent.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 3.dp),
        )
        if (canMove) {
            Text("\u2191", color = Muted, fontSize = 18.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onUp).padding(horizontal = 6.dp, vertical = 4.dp))
            Text("\u2193", color = Muted, fontSize = 18.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onDown).padding(horizontal = 6.dp, vertical = 4.dp))
        }
        Text("\u2022\u2022\u2022", color = Muted, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onMenu).padding(horizontal = 4.dp, vertical = 4.dp))
    }
}

/** Match glasses home widget accent bars (HomeBoardCatalog). */
private fun appAccent(id: String): Color {
    val tone = HomeBoardCatalog.widgets.firstOrNull { it.id == id }?.accent
        ?: HomeBoardCatalog.defaultWidgets.firstOrNull { it.id == id }?.accent
        ?: HudAccentTone.Teal
    return phoneAccent(tone)
}

fun phoneAccent(tone: HudAccentTone): Color = when (tone) {
    HudAccentTone.Teal -> Color(0xFF4DD0E1)
    HudAccentTone.Green -> Color(0xFF66BB6A)
    HudAccentTone.Spotify -> Color(0xFF1DB954)
    HudAccentTone.Yellow -> Color(0xFFFFD54F)
    HudAccentTone.Red -> Color(0xFFEF5350)
    HudAccentTone.Blue -> Color(0xFF64B5F6)
    HudAccentTone.Orange -> Color(0xFFFFB74D)
    HudAccentTone.Purple -> Color(0xFFBA68C8)
}

/** Consistent monogram glyph (the old mix of emoji, symbols and "Bag" text read as broken). */
private fun appGlyph(id: String): String {
    val title = MiniAppRegistry.get(id)?.descriptor?.title ?: id
    return title.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "•"
}
