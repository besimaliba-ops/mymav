package com.mymav.glasses.mav2.hud

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.everysight.mav2.sdk.uikit.data.M2Touch
import com.everysight.mav2.sdk.uikit.drawables.M2RectFilled
import com.everysight.mav2.sdk.uikit.drawables.M2RectOutline
import com.everysight.mav2.sdk.uikit.drawables.ext.setDimensions
import com.everysight.mav2.sdk.utils.M2Color
import com.mymav.glasses.api.GlassesHud
import com.mymav.glasses.api.HudAccentTone
import java.io.ByteArrayOutputStream

/**
 * Shared look for Shopping / Recipes / Notepad: coloured header, rows with badges or
 * thumbnails, a detail card, action pills, HOME pill (gaze) + long-tap to leave.
 * Swipe forward/back (or up/down) moves focus, Tap activates, Long tap = home.
 */
class ListAppScreen(private val hud: GlassesHud.ListApp) : HudScreen(tag = "mymav-list-${hud.appId}") {
    override val glassScope: String? = com.mymav.glasses.api.MymavHudPrefs.appScope(hud.appId)

    override val enterFade: Boolean = false

    private val tone = hud.tone.toM2Color()
    private val detail = hud.detail
    private val rowCount = if (detail == null) hud.rows.size else 0
    private val total = rowCount + hud.pills.size
    private val focused = hud.focusedIndex.coerceIn(0, (total - 1).coerceAtLeast(0))

    override fun build() {
        add(HudPrimitives.fullBleedBackground())
        drawHeader()
        if (detail != null) drawDetail(detail) else drawRows()
        drawPills()
        add(HudPrimitives.labelInk(if (detail != null) "Tap to select - swipe to move" else "Tap to open - swipe to move", 16f, 358f, 0.7f, HudTheme.InkMuted))
        HudPrimitives.drawHomePill(this, hud.homeDwellProgress)
    }

    private fun drawHeader() {
        val title = (detail?.title ?: hud.title).takeForHud(if (detail != null) 22 else 14)
        val status = if (detail != null) detail.pageLabel else hud.statusLine
        GlassKit.header(this, title, hud.appId.takeIf { it in setOf("shopping", "recipes", "notepad", "nav") }, status.takeForHud(24))
    }

    private fun drawRows() {
        if (hud.rows.isEmpty()) {
            GlassKit.card(this, 16f, 76f, 608f, 150f, radius = 18f)
            add(
                M2RectFilled(tone.withAlpha(0.25f)).apply {
                    setDimensions(40f, 100f, 56f, 56f); softCorners(28f)
                },
            )
            add(HudPrimitives.centeredPillLabel("+", 40f, 100f, 56f, 56f, 1.1f, tone, bold = true))
            add(HudPrimitives.label(hud.emptyTitle.takeForHud(30), 116f, 100f, 1.2f, M2Color.White, bold = true, maxWidth = 490f))
            if (hud.emptyHint.isNotBlank()) {
                add(HudPrimitives.labelInk(hud.emptyHint.takeForHud(44), 116f, 138f, 0.8f, HudTheme.InkMuted, maxWidth = 490f))
            }
            return
        }
        val visible = 4
        val focusRow = focused.coerceAtMost(hud.rows.size - 1)
        val start = (focusRow - 1).coerceIn(0, (hud.rows.size - visible).coerceAtLeast(0))
        val rowH = 52f
        val gap = 4f
        for (i in 0 until visible) {
            val index = start + i
            val row = hud.rows.getOrNull(index) ?: break
            val y = 70f + i * (rowH + gap)
            val selected = focused < rowCount && index == focused
            val rc = row.tone.toM2Color()
            GlassKit.card(this, 16f, y, 608f, rowH, selected, radius = 14f)
            val done = row.checked == true
            // colour stripe
            add(
                M2RectFilled(rc.withAlpha(if (done) 0.35f else 0.95f)).apply {
                    setDimensions(16f, y + 8f, 5f, rowH - 16f); softCorners(2.5f)
                },
            )
            // thumbnail or badge
            val bx = 32f
            val bs = 40f
            var drewImage = false
            row.imageBytes?.let { img ->
                cropped(img, 44, 44)?.let { c ->
                    createHudImage(c.bytes, c.w, c.h, bx - 2f, y + 4f, 44f, 44f, cornerRadius = 10f)?.let { add(it); drewImage = true }
                }
            }
            if (!drewImage) {
                add(
                    M2RectFilled(rc.withAlpha(if (done) 0.25f else 0.9f)).apply {
                        setDimensions(bx, y + (rowH - bs) / 2f, bs, bs); softCorners(bs / 2f)
                    },
                )
                add(
                    HudPrimitives.centeredPillLabel(
                        row.badge.take(2).uppercase().ifBlank { row.title.take(1).uppercase() },
                        bx, y + (rowH - bs) / 2f, bs, bs, 0.75f, M2Color.White, bold = true,
                    ),
                )
            }
            val tx = 84f
            val trailW = if (row.trailing.isNotBlank() || done) 90f else 0f
            val titleColor = when {
                done -> HudTheme.TextMuted
                else -> M2Color.White
            }
            if (row.subtitle.isNotBlank()) {
                add(HudPrimitives.label(row.title.takeForHud(30), tx, y + 3f, 0.85f, titleColor, bold = true, maxWidth = 530f - trailW))
                add(HudPrimitives.labelInk(row.subtitle.takeForHud(44), tx, y + 27f, 0.7f, HudTheme.InkMuted, maxWidth = 530f - trailW))
            } else {
                add(HudPrimitives.label(row.title.takeForHud(32), tx, y + 12f, 0.95f, titleColor, bold = true, maxWidth = 530f - trailW))
            }
            if (done) {
                add(HudPrimitives.label("Done", 606f - HudText.width("Done", 0.75f), y + 14f, 0.75f, HudTheme.Gain))
            } else if (row.trailing.isNotBlank()) {
                val w = HudText.width(row.trailing.takeForHud(12), 0.75f)
                add(HudPrimitives.label(row.trailing.takeForHud(12), 606f - w, y + 14f, 0.75f, rc))
            }
        }
        // scroll bar
        if (hud.rows.size > visible) {
            val trackH = visible * (rowH + gap) - gap
            val thumbH = (trackH * visible / hud.rows.size).coerceAtLeast(18f)
            val thumbY = 70f + (trackH - thumbH) * (start.toFloat() / (hud.rows.size - visible).coerceAtLeast(1))
            add(M2RectFilled(HudTheme.PanelBorder.withAlpha(0.6f)).apply { setDimensions(628f, 70f, 3f, trackH); softCorners(1.5f) })
            add(M2RectFilled(tone.withAlpha(0.9f)).apply { setDimensions(628f, thumbY, 3f, thumbH); softCorners(1.5f) })
        }
    }

    private fun drawDetail(d: com.mymav.glasses.api.ListDetail) {
        GlassKit.card(this, 16f, 70f, 608f, 222f)
        var tx = 32f
        var tw = 576f
        val img = d.imageBytes
        if (img != null) {
            cropped(img, 190, 142)?.let { c ->
                createHudImage(c.bytes, c.w, c.h, 30f, 82f, c.w.toFloat(), c.h.toFloat(), cornerRadius = 12f)?.let { add(it) }
                tx = 238f
                tw = 624f - 14f - tx
            }
        }
        val lineH = 24f
        val maxLines = ((222f - 24f) / lineH).toInt()
        val lines = ArrayList<String>()
        for (para in d.body.split('\n')) {
            if (para.isBlank()) { lines += ""; continue }
            lines += HudText.wrap(para, tw, 0.75f, maxLines)
            if (lines.size >= maxLines) break
        }
        lines.take(maxLines).forEachIndexed { i, l ->
            if (l.isNotEmpty()) add(HudPrimitives.label(l, tx, 80f + i * lineH, 0.75f, if (i == 0 && img != null) tone else M2Color.White, bold = false))
        }
    }

    private fun drawPills() {
        val n = hud.pills.size
        if (n == 0) return
        val y = 304f
        val h = 38f
        val gap = 10f
        val w = ((608f - (n - 1) * gap) / n).coerceAtMost(170f)
        val totalW = n * w + (n - 1) * gap
        val x0 = 16f + (608f - totalW) / 2f
        hud.pills.forEachIndexed { i, pill ->
            val x = x0 + i * (w + gap)
            val selected = focused == rowCount + i
            val t = pill.tone ?: hud.tone
            if (selected) {
                GlassKit.button(this, pill.label, x, y, w, h, selected = true)
                add(M2RectOutline(HudTheme.FocusGlow).apply {
                    setDimensions(x - 2f, y - 2f, w + 4f, h + 4f)
                    softCorners(h / 2f + 2f)
                    setStyle(1.5f)
                })
            } else {
                GlassKit.card(this, x, y, w, h, radius = h / 2f)
                add(HudPrimitives.centeredPillLabel(pill.label, x, y, w, h, 0.85f, M2Color.White, tracked = true))
            }
        }
    }

    private fun pillDark(t: HudAccentTone): UInt = when (t) {
        HudAccentTone.Green, HudAccentTone.Spotify -> 0xFF1F5A27u
        HudAccentTone.Red -> 0xFF7A2020u
        HudAccentTone.Blue -> 0xFF1D4F85u
        HudAccentTone.Orange -> 0xFF8A4A08u
        HudAccentTone.Purple -> 0xFF4D2A7Au
        HudAccentTone.Yellow -> 0xFF6B5200u
        HudAccentTone.Teal -> 0xFF0C5560u
    }

    private fun pillBright(t: HudAccentTone): UInt = when (t) {
        HudAccentTone.Green, HudAccentTone.Spotify -> 0xFF2E9E3Fu
        HudAccentTone.Red -> 0xFFC93636u
        HudAccentTone.Blue -> 0xFF2F7FD0u
        HudAccentTone.Orange -> 0xFFD9770Au
        HudAccentTone.Purple -> 0xFF7E4BC4u
        HudAccentTone.Yellow -> 0xFFB48900u
        HudAccentTone.Teal -> 0xFF14A0B3u
    }

    override fun onTouch(touch: M2Touch) {
        super.onTouch(touch)
        when (touch) {
            M2Touch.Up, M2Touch.Backward -> hud.actions.onFocusChanged?.invoke((focused - 1).coerceAtLeast(0))
            M2Touch.Down, M2Touch.Forward -> hud.actions.onFocusChanged?.invoke((focused + 1).coerceAtMost((total - 1).coerceAtLeast(0)))
            M2Touch.Tap -> hud.actions.onSelect?.invoke()
            M2Touch.LongTap -> hud.actions.onHomeRequested?.invoke()
            else -> Unit
        }
    }

    private class Cropped(val bytes: ByteArray, val w: Int, val h: Int)

    private fun cropped(bytes: ByteArray, w: Int, h: Int): Cropped? {
        val key = Triple(System.identityHashCode(bytes), w, h)
        synchronized(cache) { cache[key]?.let { return it } }
        val out = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= w * 2 && bounds.outHeight / (sample * 2) >= h * 2) sample *= 2
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return@runCatching null
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val srcAspect = decoded.width.toFloat() / decoded.height
            val dstAspect = w.toFloat() / h
            val src = if (srcAspect > dstAspect) {
                val cw = (decoded.height * dstAspect).toInt().coerceAtLeast(1)
                val l = ((decoded.width - cw) / 2).coerceAtLeast(0)
                Rect(l, 0, l + cw, decoded.height)
            } else {
                val ch = (decoded.width / dstAspect).toInt().coerceAtLeast(1)
                val t = ((decoded.height - ch) / 2).coerceAtLeast(0)
                Rect(0, t, decoded.width, t + ch)
            }
            Canvas(bmp).drawBitmap(decoded, src, RectF(0f, 0f, w.toFloat(), h.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            val st = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, st)
            decoded.recycle(); bmp.recycle()
            Cropped(st.toByteArray(), w, h)
        }.getOrNull() ?: return null
        synchronized(cache) {
            if (cache.size > 24) cache.clear()
            cache[key] = out
        }
        return out
    }

    private companion object {
        val cache = HashMap<Triple<Int, Int, Int>, Cropped>()
    }
}
