package com.mymav.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mymav.glasses.api.HomeBoardCatalog
import com.mymav.glasses.api.MymavHudPrefs
import com.mymav.glasses.api.PanelTint

/**
 * Settings -> Glasses panels -> Panel colours.
 *
 * Colour is independent of the panel style: nothing here touches the style, and choosing a style never touches
 * these choices. A default tint covers every glasses surface; a Home widget (by its stable id) or an app can
 * override it, and an app's override is inherited by its cards, buttons and pills.
 */
@Composable
fun GlassColoursCard() {
    val context = LocalContext.current
    val prefs = MymavHudPrefs
    // Bumped on every change so the rows re-read the stored values.
    var tick by remember { mutableIntStateOf(0) }
    var strength by remember { mutableIntStateOf(prefs.tintStrength()) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var showWidgets by remember { mutableStateOf(false) }
    var showApps by remember { mutableStateOf(false) }
    @Suppress("UNUSED_EXPRESSION") tick

    val defaultKey = prefs.defaultTintKey()
    val widgets = remember { HomeBoardCatalog.widgets.map { it.id to it.title } }
    val apps = remember { HomeBoardCatalog.widgets.map { it.id to it.title } + ("ask" to "Ask MyMav") }

    GlassCard {
        CardTitle(
            "Panel colours",
            "Tint the glasses panels. Colour is separate from the panel style, so changing one keeps the other. " +
                "Text, logos, rings and the mascot are never recoloured; corner reflections stay silver-white.",
        )
        TintPreview(defaultKey, strength)

        SectionLabel("Default for all glasses panels")
        TintPicker(
            currentKey = defaultKey,
            allowDefault = false,
            onPick = { prefs.saveDefaultTint(context, it ?: PanelTint.NEUTRAL); tick++ },
        )

        SectionLabel("Tint strength: $strength%")
        Slider(
            value = strength.toFloat(),
            onValueChange = { strength = (Math.round(it / 5f) * 5); prefs.saveTintStrength(context, strength) },
            valueRange = prefs.TINT_MIN.toFloat()..prefs.TINT_MAX.toFloat(),
        )

        OverrideSection(
            title = "Home widgets",
            open = showWidgets,
            onToggle = { showWidgets = !showWidgets },
            items = widgets,
            scopeOf = { prefs.widgetScope(it) },
            expanded = expanded,
            onExpand = { expanded = if (expanded == it) null else it },
            defaultKey = defaultKey,
            onChanged = { tick++ },
        )
        OverrideSection(
            title = "Apps (cards, buttons and pills inherit)",
            open = showApps,
            onToggle = { showApps = !showApps },
            items = apps,
            scopeOf = { prefs.appScope(it) },
            expanded = expanded,
            onExpand = { expanded = if (expanded == it) null else it },
            defaultKey = defaultKey,
            onChanged = { tick++ },
        )

        OutlinedButton(onClick = {
            prefs.resetColours(context)
            strength = prefs.tintStrength()
            expanded = null
            tick++
        }) { Text("Reset colours") }
        Text("Reset colours clears the default tint, the strength and every widget/app override. The panel style and opacity are kept.", color = MyMavColors.TextMuted, fontSize = 12.sp)
    }
}

@Composable
private fun OverrideSection(
    title: String,
    open: Boolean,
    onToggle: () -> Unit,
    items: List<Pair<String, String>>,
    scopeOf: (String) -> String,
    expanded: String?,
    onExpand: (String) -> Unit,
    defaultKey: String,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clickable { onToggle() }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = MyMavColors.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(if (open) "Hide" else "Show", color = MyMavColors.Accent, fontSize = 13.sp)
    }
    if (!open) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { (id, label) ->
            val scope = scopeOf(id)
            val override = MymavHudPrefs.tintOverride(scope)
            val shown = override ?: defaultKey
            Column {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x0DFFFFFF))
                        .clickable { onExpand(scope) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Swatch(shown, 22)
                    Text(label, color = MyMavColors.Text, fontSize = 14.sp, modifier = Modifier.padding(start = 10.dp).weight(1f))
                    Text(if (override == null) "Default" else PanelTint.labelOf(override), color = MyMavColors.TextMuted, fontSize = 12.sp)
                }
                if (expanded == scope) {
                    Box(Modifier.padding(top = 6.dp, start = 6.dp)) {
                        TintPicker(
                            currentKey = override,
                            allowDefault = true,
                            onPick = { MymavHudPrefs.saveTintOverride(context, scope, it); onChanged() },
                        )
                    }
                }
            }
        }
    }
}

/** Preset swatches (+ "Use default" for overrides) and a custom colour picker. [onPick] null = use default. */
@Composable
private fun TintPicker(currentKey: String?, allowDefault: Boolean, onPick: (String?) -> Unit) {
    var customOpen by remember { mutableStateOf(false) }
    val currentArgb = PanelTint.argbOf(currentKey)
    val isCustom = currentKey != null && currentKey.startsWith("#")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (allowDefault) {
            KitChip("Use default", currentKey == null, { onPick(null) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            PanelTint.Preset.entries.forEach { p ->
                SwatchButton(p.key, p.label, currentKey == p.key) { onPick(p.key) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            SwatchButton(if (isCustom) currentKey!! else "#7F7F7F", "Custom", isCustom) { customOpen = !customOpen }
            Text(
                if (isCustom) "Custom ${currentKey}" else if (customOpen) "Pick a colour below" else "Custom colour…",
                color = MyMavColors.TextMuted, fontSize = 12.sp,
            )
        }
        if (customOpen) {
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(currentArgb ?: 0xFF3FA9F5.toInt(), hsv)
            var h by remember(customOpen) { mutableStateOf(hsv[0]) }
            var s by remember(customOpen) { mutableStateOf(if (currentArgb == null) 0.6f else hsv[1]) }
            var v by remember(customOpen) { mutableStateOf(if (currentArgb == null) 0.95f else hsv[2]) }
            fun push() {
                onPick(PanelTint.customKey(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))))
            }
            Text("Hue", color = MyMavColors.TextMuted, fontSize = 12.sp)
            Slider(value = h, onValueChange = { h = it; push() }, valueRange = 0f..360f)
            Text("Saturation", color = MyMavColors.TextMuted, fontSize = 12.sp)
            Slider(value = s, onValueChange = { s = it; push() }, valueRange = 0f..1f)
            Text("Brightness", color = MyMavColors.TextMuted, fontSize = 12.sp)
            Slider(value = v, onValueChange = { v = it; push() }, valueRange = 0.3f..1f)
            Text("Changes reach the glasses in a moment; dragging is batched so the glasses are not redrawn on every movement.", color = MyMavColors.TextMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun SwatchButton(key: String, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onClick() }) {
        Swatch(key, 30, selected)
        Text(label, color = if (selected) MyMavColors.Accent else MyMavColors.TextMuted, fontSize = 9.sp, maxLines = 1)
    }
}

@Composable
private fun Swatch(key: String, size: Int, selected: Boolean = false) {
    val argb = PanelTint.argbOf(key)
    val fill = if (argb == null) Brush.linearGradient(listOf(Color(0xFFC0C7CC), Color(0xFF6C767D))) else Brush.linearGradient(listOf(Color(argb), Color(argb)))
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(fill)
            .border(if (selected) 2.dp else 1.dp, if (selected) MyMavColors.Accent else MyMavColors.HairlineStrong, CircleShape),
    )
}

/** The real packaged surface of the selected style, recoloured with the same transform the glasses use. */
@Composable
private fun TintPreview(tintKey: String, strength: Int) {
    val context = LocalContext.current
    val style = MymavHudPrefs.panelStyle()
    val opacity = MymavHudPrefs.glassOpacityPercent()
    val bitmap = remember(style, tintKey, strength, opacity) {
        runCatching {
            val bmp = context.assets.open("glass/${style.dir}/surface_298x135_r18.png").use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inPremultiplied = false })
            } ?: return@runCatching null
            val w = bmp.width
            val h = bmp.height
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            PanelTint.argbOf(tintKey)?.let { PanelTint.apply(px, it, strength) }
            val f = opacity / 100f
            for (i in px.indices) {
                val a = ((px[i] ushr 24) * f).toInt().coerceIn(0, 255)
                px[i] = (a shl 24) or (px[i] and 0xFFFFFF)
            }
            Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        }.getOrNull()
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF3B5F7C), Color(0xFF8A98A0), Color(0xFF2B3A33)))),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Preview of the ${style.label} panel with the selected colour",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxWidth(0.62f).height(100.dp),
            )
        }
        Text("Health  1,103 steps", color = Color.White, fontSize = 14.sp)
        Text("${style.label} · preview", color = Color(0xCCFFFFFF), fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp))
    }
}
