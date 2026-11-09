package com.mymav.glasses.mav2.hud

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.everysight.mav2.sdk.uikit.data.M2TextureFit
import com.everysight.mav2.sdk.uikit.data.M2Touch
import com.everysight.mav2.sdk.uikit.drawables.M2Image
import com.everysight.mav2.sdk.uikit.drawables.M2RectFilled
import com.everysight.mav2.sdk.uikit.drawables.M2Text
import com.everysight.mav2.sdk.uikit.drawables.ext.setDimensions
import com.everysight.mav2.sdk.uikit.drawables.M2RectOutline
import com.everysight.mav2.sdk.utils.M2Color
import com.mymav.glasses.api.GlassesHud
import com.mymav.glasses.api.HealthHeartSnapshot
import com.mymav.glasses.api.HomeBoardCatalog
import com.mymav.glasses.api.HomeWidget
import com.mymav.glasses.api.HomeWidgetSize
import com.mymav.glasses.api.PlacedWidget
import java.io.ByteArrayOutputStream

/**
 * Glasses home widget board — smoked-glass tiles on a 2×2 grid + page dots.
 *
 * Structure (page, widgets, Ask bubble) is built once. Everything that changes
 * at gaze rate — focus ring, side-dwell bars, hint text, Ask pill progress,
 * the mascot — is patched in place via [tryPatch] / [patchAvatar], so the
 * board never blanks or re-uploads textures while you look around.
 */
class HomeWidgetBoardScreen(
    initial: GlassesHud.HomeBoard,
) : HudScreen(tag = "mymav-home-board") {

    private var hud: GlassesHud.HomeBoard = initial
    private val structureKey: String = structureKeyOf(initial)

    private val tileLayouts = layoutTiles(initial.widgets, initial.pageIndex)
    private var focusedId = initial.focusedWidgetId.ifBlank { initial.widgets.firstOrNull()?.id.orEmpty() }
    private val askVisible = initial.avatarSpeech.isNotBlank()

    // ---- live (patched) layers -------------------------------------------------
    private val statusText: M2Text = HudPrimitives.label(" ", 30f, 21f, 0.56f, HudTheme.TextPrimary)
    private val focusLayer = HudPrimitives.FocusLayer()
    private val dwellZoneLeft = M2RectFilled(HudTheme.FocusGlow.withAlpha(0.12f)).apply { park() }
    private val dwellZoneRight = M2RectFilled(HudTheme.FocusGlow.withAlpha(0.12f)).apply { park() }
    private val dwellBar = M2RectFilled(HudTheme.FocusGlow.withAlpha(0.9f)).apply { park() }
    private val hintText: M2Text = HudPrimitives.labelInk(" ", 16f, 368f, 0.48f, HudTheme.InkMuted)
    private val avatarGazeBar = M2RectFilled(HudTheme.FocusGlow.withAlpha(0.9f)).apply { park() }
    private val askBars = LinkedHashMap<String, M2RectFilled>()
    /** D-pad selected Ask pill (tap activates it); gaze focus also highlights. */
    private var askSel: String? = null
    private val askRing = HudPrimitives.FocusLayer(M2Color.White)

    /**
     * Docked mascot = a small resident pose set (one 72px image per pose, only
     * one placed on-canvas at a time; the rest parked). Summoned mascot = one
     * plain image. Pose changes never upload anything.
     */
    private val cellSize: Int = initial.avatarCellSize
    private val poseImages = ArrayList<M2Image>()
    private var avatarImage: M2Image? = null
    private var avatarPlaceholder: M2RectFilled? = null
    private var currentCell: Int = -1
    private var healthPatch: HealthPatch? = null

    /** Draw size == encode size (Native 1:1); Stretch half-crops the sprite. */
    private val avatarSize: Float = when {
        initial.avatarCellSize > 0 -> initial.avatarCellSize.toFloat()
        initial.avatarImageWidth > 0 -> initial.avatarImageWidth.toFloat().coerceIn(56f, 128f)
        initial.avatarSpeech.isNotBlank() -> HomeBoardCatalog.AskLayout.SUMMON_SIZE
        else -> 72f
    }

    override fun build() {
        add(HudPrimitives.fullBleedBackground())
        drawStatusBar()

        // The big answer card owns the whole board — hide tiles so nothing shows through it.
        if (!askVisible) tileLayouts.forEach { drawTile(it) }

        add(focusLayer.wash)
        add(focusLayer.ring)
        add(dwellZoneLeft)
        add(dwellZoneRight)
        add(dwellBar)
        if (!askVisible) drawPageDots()
        add(hintText)

        if (askVisible) drawAskModal()

        add(avatarGazeBar)
        drawAvatar()

        applyLiveState()
    }

    // ---- patching ----------------------------------------------------------------

    override fun tryPatch(next: GlassesHud): Boolean {
        if (next !is GlassesHud.HomeBoard) return false
        if (structureKeyOf(next) != structureKey) return false
        hud = next
        focusedId = next.focusedWidgetId.ifBlank { focusedId }
        return runCatching {
            applyLiveState()
            patchHealthSummary()
            if (cellSize > 0) {
                patchAvatarCell(next.avatarVisible, next.avatarX, next.avatarY, next.avatarCell)
            } else {
                patchAvatar(next.avatarVisible, next.avatarX, next.avatarY, null, 0, 0)
            }
            true
        }.getOrDefault(false)
    }

    private fun applyLiveState() {
        statusText.setHudText(HudText.fit(hud.statusLabel.ifBlank { "MyMav" }, 196f, 0.56f))
        applyFocus()
        applySideDwell()
        applyAskDwell()
        applyAvatarGazeBar()
        hintText.setHudText(dwellHint() ?: " ")
    }

    private fun applyFocus() {
        // Badges are always sharp; focus is shown by the panel highlight only.
        val tile = tileLayouts.firstOrNull { it.widget.id == focusedId }
        if (tile == null || askVisible) {
            focusLayer.hide()
        } else {
            focusLayer.moveTo(tile.x, tile.y, tile.w, tile.h, HudTheme.PANEL_RADIUS)
        }
    }

    private fun applySideDwell() {
        val side = hud.dwellSide
        val on = hud.dwellProgress > 0.02f && side != null && !askVisible
        if (!on) {
            dwellZoneLeft.park()
            dwellZoneRight.park()
            dwellBar.park()
            return
        }
        val leftW = HudTheme.W * HomeBoardCatalog.GAZE_LEFT_MAX
        val rightX = HudTheme.W * HomeBoardCatalog.GAZE_RIGHT_MIN
        if (side == "left") {
            dwellZoneLeft.setDimensions(0f, 60f, leftW, 300f)
            dwellZoneLeft.softCorners(12f)
            dwellZoneRight.park()
        } else {
            dwellZoneRight.setDimensions(rightX, 60f, HudTheme.W - rightX, 300f)
            dwellZoneRight.softCorners(12f)
            dwellZoneLeft.park()
        }
        val barW = (120f * hud.dwellProgress.coerceIn(0f, 1f)).coerceAtLeast(4f)
        val barX = if (side == "left") 12f else HudTheme.W - 12f - barW
        dwellBar.setDimensions(barX, 66f, barW, 4f)
        dwellBar.softCorners(2f)
    }

    private fun applyAskDwell() {
        if (!askVisible || askBars.isEmpty()) return
        val geo = HomeBoardCatalog.AskLayout.geometry(
            hud.avatarX, hud.avatarY, hud.askExpanded, askBars.size,
        )
        val pillH = HomeBoardCatalog.AskLayout.PILL_H
        val sel = askSel ?: hud.askChoiceFocus
        val selIndex = askBars.keys.indexOf(sel)
        if (selIndex >= 0) {
            askRing.moveTo(geo.pillX(selIndex), geo.pillY, geo.pillW, pillH, pillH / 2f)
        } else {
            askRing.hide()
        }
        var index = 0
        for ((id, bar) in askBars) {
            val p = if (hud.askChoiceFocus == id) hud.askChoiceGazeProgress else 0f
            if (p > 0.02f) {
                val w = ((geo.pillW - 20f) * p.coerceIn(0f, 1f)).coerceAtLeast(4f)
                bar.setDimensions(geo.pillX(index) + 10f, geo.pillY + pillH - 7f, w, 3f)
                bar.softCorners(1.5f)
            } else {
                bar.park()
            }
            index++
        }
    }

    private fun applyAvatarGazeBar() {
        val p = hud.avatarGazeProgress
        if (p <= 0.02f || !hud.avatarVisible || hud.avatarX < 0f) {
            avatarGazeBar.park()
            return
        }
        val aw = avatarSize
        val ax = (hud.avatarX - aw / 2f).coerceIn(4f, HudTheme.W - aw - 4f)
        val ay = (hud.avatarY - aw / 2f).coerceIn(4f, HudTheme.H - aw - 8f)
        val w = ((aw - 16f) * p.coerceIn(0f, 1f)).coerceAtLeast(4f)
        avatarGazeBar.setDimensions(ax + 8f, ay + aw + 2f, w, 3f)
        avatarGazeBar.softCorners(1.5f)
    }

    private fun dwellHint(): String? = when {
        hud.avatarGazeProgress > 0.02f -> "MYMAV  ${(hud.avatarGazeProgress * 100).toInt()}%"
        hud.dwellSide == "left" && hud.dwellProgress > 0.02f -> "PREV PAGE  ${(hud.dwellProgress * 100).toInt()}%"
        hud.dwellSide == "right" && hud.dwellProgress > 0.02f -> "NEXT PAGE  ${(hud.dwellProgress * 100).toInt()}%"
        hud.openDwellProgress > 0.02f -> "OPEN  ${(hud.openDwellProgress * 100).toInt()}%"
        else -> null
    }

    // ---- static structure ------------------------------------------------------

    private fun drawStatusBar() {
        val statusPill = HudPrimitives.frostedPanel(
            16f, 12f, 228f, 36f, radius = HudTheme.PILL_RADIUS, gradient = false, glass = true,
        )
        val pillX = 372f
        val devicePill = HudPrimitives.frostedPanel(
            pillX, 12f, 624f - pillX, 36f, radius = HudTheme.PILL_RADIUS, gradient = false, glass = true,
        )
        HudPrimitives.addPanel(this, statusPill)
        HudPrimitives.addPanel(this, devicePill)
        add(statusText)
        // [phone image] 87%   |   [glasses image] 100%  (two separate live readings; "--" when unavailable)
        val phoneTxt = if (hud.phoneBatteryPercent >= 0) "${hud.phoneBatteryPercent}%" else "--"
        val glassTxt = if (hud.batteryPercent >= 0) "${hud.batteryPercent}%" else "--"
        val sc = 0.56f
        val pw = HudText.width(phoneTxt, sc)
        val gw = HudText.width(glassTxt, sc)
        val phoneImg = DeviceHudIcons.phone(24)
        val glassesImg = DeviceHudIcons.glasses(14)
        val phoneW = (phoneImg?.width ?: 14).toFloat()
        val glassesW = (glassesImg?.width ?: 24).toFloat()
        val gap = 8f
        val between = 26f
        val total = phoneW + gap + pw + between + glassesW + gap + gw
        var x = pillX + ((624f - pillX) - total) / 2f
        if (phoneImg != null) {
            createHudImage(phoneImg.bytes, phoneImg.width, phoneImg.height, x, 18f, phoneW, 24f, roundCorners = false, resident = true)
                ?.let { add(it) }
        } else {
            drawPhoneIcon(x - 4f, 20f, hud.phoneBatteryPercent)
        }
        add(HudPrimitives.label(phoneTxt, x + phoneW + gap, 21f, sc, levelColor(hud.phoneBatteryPercent, hud.phoneCharging)))
        x += phoneW + gap + pw + between / 2f
        add(M2RectFilled(0x55B8C9E0u).apply { setDimensions(x, 21f, 1f, 18f) })
        x += between / 2f
        if (glassesImg != null) {
            createHudImage(glassesImg.bytes, glassesImg.width, glassesImg.height, x, 23f, glassesW, 14f, roundCorners = false, resident = true)
                ?.let { add(it) }
        } else {
            drawGlassesIcon(x, 20f)
        }
        add(HudPrimitives.label(glassTxt, x + glassesW + gap, 21f, sc, levelColor(hud.batteryPercent, hud.glassesCharging)))
    }

    private fun levelColor(p: Int, charging: Boolean): M2Color = when {
        charging -> HudTheme.Gain
        p in 0..15 -> M2Color.Red
        p in 16..30 -> M2Color.Amber
        else -> M2Color.White
    }

    /** Fallback only (device artwork missing): smartphone outline with a level fill. */
    private fun drawPhoneIcon(x: Float, y: Float, percent: Int) {
        val w = 14f
        val h = 20f
        val bx = x + 4f
        add(M2RectOutline(0xFFFFFFFFu).apply { setDimensions(bx, y, w, h); softCorners(3f); setStyle(1.5f) })
        if (percent >= 0) {
            val fh = ((h - 8f) * percent / 100f).coerceAtLeast(1f)
            add(M2RectFilled(0xFF5BE37Au).apply { setDimensions(bx + 3f, y + h - 3f - fh, w - 6f, fh); softCorners(1f) })
        }
    }

    /** Fallback only (device artwork missing): sunglasses silhouette. */
    private fun drawGlassesIcon(x: Float, y: Float) {
        val ly = y + 5f
        add(M2RectFilled(0xFFFFFFFFu).apply { setDimensions(x, ly, 10f, 9f); softCorners(3.5f) })
        add(M2RectFilled(0xFFFFFFFFu).apply { setDimensions(x + 12f, ly, 10f, 9f); softCorners(3.5f) })
        add(M2RectFilled(0xFFFFFFFFu).apply { setDimensions(x + 9f, ly + 1.5f, 4f, 2f) })
    }

    private fun drawTile(tile: TileLayout) {
        val accent = tile.widget.accent.toM2Color()
        val bleedBytes = tile.widget.previewImageBytes
        val hasBleed = bleedBytes != null &&
            tile.widget.previewImageWidth > 0 &&
            tile.widget.previewImageHeight > 0
        // Panel colour is keyed by the widget's stable id (not its page position), so it follows the widget.
        val panel = GlassSurface.withScope(com.mymav.glasses.api.MymavHudPrefs.widgetScope(tile.widget.id)) {
            HudPrimitives.frostedPanel(
                tile.x, tile.y, tile.w, tile.h,
                radius = HudTheme.PANEL_RADIUS,
                fillAlpha = if (hasBleed) 0.5f else HudTheme.PANEL_ALPHA,
                glass = true,
            )
        }
        add(panel.fill)
        if (hasBleed && bleedBytes != null) {
            addCoverFit(bleedBytes, tile.x + 1f, tile.y + 1f, tile.w - 2f, tile.h - 2f)
            // Scrim so labels stay readable over artwork.
            add(
                M2RectFilled(HudTheme.InkPanel.withAlpha(0.6f)).apply {
                    setDimensions(tile.x + 1f, tile.y + 1f, tile.w - 2f, tile.h - 2f)
                    softCorners(HudTheme.PANEL_RADIUS - 1f)
                },
            )
        }
        panel.gloss?.let { add(it) }
        panel.gloss2?.let { add(it) }
        panel.shineTl?.let { add(it) }
        panel.shineBr?.let { add(it) }
        add(panel.outline)
        when (tile.widget.id) {
            "notify" -> { drawTileTitle(tile); drawNotifyBrandMetrics(tile) }
            "fitness" -> { drawTileTitle(tile); drawHealthSummary(tile) }
            "tasks" -> { drawListHeader(tile); drawTasksToday(tile) }
            "recipes" -> { drawListHeader(tile); drawTasksToday(tile, "No saved recipes yet") }
            "notepad" -> { drawListHeader(tile); drawTasksToday(tile, "No notes yet") }
            "shopping" -> { drawListHeader(tile); drawTasksToday(tile, "Shopping list is empty") }
            "spotify", "conversate" -> drawIconTile(tile, accent)
            "nav" -> drawNavTile(tile)
            else -> drawInfoTile(tile, accent)
        }
    }

    /** Title for stat-style tiles (Socials, Health): top-left, natural casing. */
    private fun drawTileTitle(tile: TileLayout) {
        add(HudPrimitives.label(tile.widget.title, tile.x + 16f, tile.y + 12f, 1.1f, maxWidth = tile.w - 32f))
    }

    /** List tiles: small glossy icon beside the title. */
    private fun drawListHeader(tile: TileLayout) {
        val badge = PremiumHudIcons.image(tile.widget.id, 36, tile.x + 12f, tile.y + 8f)
        badge?.let { add(it) }
        val tx = tile.x + if (badge != null) 54f else 16f
        add(HudPrimitives.label(tile.widget.title, tx, tile.y + 12f, 1.1f, maxWidth = tile.x + tile.w - 14f - tx))
    }

    /** What an app does, shown only as the honest idle state when it has published no live data. */
    private fun idleDescription(id: String): Pair<String, String> = when (id) {
        "workouts" -> "Run or lift" to "Tap to start a session"
        "memorysight" -> "Saved sights" to "Tap to open your memory"
        "money" -> "Currency rates" to "Tap to convert"
        "mymind" -> "Saved notes" to "Tap to open Mymind"
        "football" -> "Matches and scores" to "Tap for fixtures"
        "food" -> "Meals and macros" to "Tap to log a meal"
        "gallery" -> "Photos and video" to "Tap to browse"
        "mirror" -> "Phone mirror" to "Tap to start casting"
        "identify" -> "Look and identify" to "Tap to scan"
        "shop" -> "Scan to shop" to "Tap to scan an item"
        "nav" -> "Where to?" to "Tap to plan a route"
        "calendar" -> "Your schedule" to "Tap to open"
        "timer" -> "Timers" to "Tap to open"
        else -> "" to "Tap to open"
    }

    /**
     * Generic app tile: compact glossy icon + title, then the primary metric / status / preview.
     * Live data (a companion app published a preview) is shown big; otherwise an honest idle line.
     */
    private fun drawInfoTile(tile: TileLayout, accent: M2Color) {
        val w = tile.widget
        val showVid = w.previewIsVideo && w.previewImageBytes != null
        val badge = PremiumHudIcons.image(w.id, 36, tile.x + 12f, tile.y + 8f)
        badge?.let { add(it) }
        val tx = tile.x + if (badge != null) 54f else 16f
        add(HudPrimitives.label(w.title, tx, tile.y + 12f, 1.1f, bold = true, maxWidth = tile.x + tile.w - 14f - tx - if (showVid) 48f else 0f))
        if (showVid) add(HudPrimitives.label("VID", tile.x + tile.w - 52f, tile.y + 14f, 0.7f, HudTheme.FocusGlow, bold = true))
        val innerW = tile.w - 32f
        val bodyTop = tile.y + 12f + HudText.titleHeight(1.1f) + 6f
        val avail = tile.y + tile.h - 10f - bodyTop
        if (w.live && (w.metric.any { it.isLetterOrDigit() } || w.metricDetail.isNotBlank())) {
            val ms = if (avail >= 70f) 1.6f else 1.2f
            val mh = HudText.lineHeight(ms, true)
            add(HudPrimitives.label(w.metric, tile.x + 16f, bodyTop, ms, accent, bold = true, maxWidth = innerW))
            var y = bodyTop + mh + 2f
            if (w.metricDetail.isNotBlank() && y + 16f <= tile.y + tile.h) {
                add(HudPrimitives.labelInk(w.metricDetail, tile.x + 16f, y, 0.8f, HudTheme.InkMuted, maxWidth = innerW))
                y += 22f
            }
            if (w.subtitle.isNotBlank() && y + 16f <= tile.y + tile.h - 4f) {
                add(HudPrimitives.labelInk(w.subtitle, tile.x + 16f, y, 0.7f, HudTheme.InkMuted, maxWidth = innerW))
            }
        } else {
            val (desc, hint) = idleDescription(w.id)
            var y = bodyTop
            if (desc.isNotBlank()) {
                add(HudPrimitives.label(desc, tile.x + 16f, y, 0.9f, M2Color.White, bold = false, maxWidth = innerW))
                y += HudText.lineHeight(0.9f, false) + 4f
            }
            if (y + 16f <= tile.y + tile.h - 4f) {
                add(HudPrimitives.label(hint, tile.x + 16f, y, 0.8f, HudTheme.FocusGlow, bold = false, maxWidth = innerW))
            }
        }
    }

    // ---- Navigation widget -------------------------------------------------------------------------------------

    private fun centered(text: String, cx: Float, y: Float, scale: Float, color: M2Color, bold: Boolean, maxW: Float) {
        val shown = HudText.fit(text, maxW, scale, bold)
        val tw = HudText.width(shown, scale, bold)
        add(HudPrimitives.label(shown, cx - tw / 2f, y, scale, color, bold = bold, maxWidth = maxW))
    }

    /** One widget, six honest states, all read from the live journey (see NavWidget). Next turn is the most prominent item. */
    private fun drawNavTile(tile: TileLayout) {
        val w = tile.widget
        val st = w.state.ifBlank { com.mymav.glasses.api.NavWidget.IDLE }
        if (st == com.mymav.glasses.api.NavWidget.NAV) { drawNavActive(tile, w); return }
        val badge = PremiumHudIcons.image("nav", 36, tile.x + 12f, tile.y + 8f)
        badge?.let { add(it) }
        val tx = tile.x + if (badge != null) 54f else 16f
        add(HudPrimitives.label("Navigation", tx, tile.y + 12f, 1.1f, bold = true, maxWidth = tile.x + tile.w - 14f - tx))
        val innerW = tile.w - 32f
        val big = tile.h > 200f
        val s1 = if (big) 1.1f else 0.9f; val s2 = if (big) 0.9f else 0.8f
        var l1 = ""; var c1: M2Color = M2Color.White; var l2 = ""; var c2: M2Color = HudTheme.FocusGlow; var l3 = ""; var muted = false
        when (st) {
            com.mymav.glasses.api.NavWidget.CALC -> { l1 = w.title; l2 = "Finding route…" }
            com.mymav.glasses.api.NavWidget.READY -> { l1 = w.title; l2 = "${w.metric} - ${w.metricDetail}"; c2 = M2Color.White; l3 = "Tap to start" }
            com.mymav.glasses.api.NavWidget.REROUTE -> { l1 = "Updating route…"; c1 = M2Color.Amber; l2 = w.title; c2 = M2Color.White }
            com.mymav.glasses.api.NavWidget.GPS -> { l1 = "Waiting for location"; c1 = M2Color.Amber; l2 = "${w.metric} ${w.metricDetail} (stale)".trim(); muted = true }
            com.mymav.glasses.api.NavWidget.ARRIVED -> { l1 = w.title; l2 = "You've arrived"; c2 = HudTheme.Gain }
            else -> { l1 = "Where to?"; l2 = "Tap to plan a route" }
        }
        var y = tile.y + 12f + HudText.titleHeight(1.1f) + if (big) 22f else 6f
        add(HudPrimitives.label(l1, tile.x + 16f, y, s1, c1, bold = true, maxWidth = innerW))
        y += HudText.lineHeight(s1, true) + 4f
        if (l2.isNotBlank()) add(if (muted) HudPrimitives.labelInk(l2, tile.x + 16f, y, s2, HudTheme.InkMuted, maxWidth = innerW) else HudPrimitives.label(l2, tile.x + 16f, y, s2, c2, maxWidth = innerW))
        y += HudText.lineHeight(s2, false) + 4f
        if (l3.isNotBlank() && y + 16f < tile.y + tile.h) add(HudPrimitives.label(l3, tile.x + 16f, y, 0.8f, HudTheme.FocusGlow, maxWidth = innerW))
    }

    private fun drawNavActive(tile: TileLayout, w: HomeWidget) {
        val kind = w.bodyLines.getOrNull(0).orEmpty().ifBlank { "straight" }
        val arrival = w.bodyLines.getOrNull(1).orEmpty(); val remaining = w.bodyLines.getOrNull(2).orEmpty()
        val tall = tile.h > 200f; val wide = tile.w > 400f
        val x = tile.x; val y = tile.y
        val dest = "To " + w.title
        fun arrow(cx: Float, cy: Float, s: Float) {
            for ((off, col) in listOf(0.20f to M2Color.Indigo, 0.10f to M2Color.Blue, 0f to M2Color.PureCyan)) {
                val t = com.mymav.glasses.api.NavArrow.triangles(kind, cx, cy + off * s, s)
                val tr = com.everysight.mav2.sdk.uikit.drawables.M2Triangles(col).apply { setColor(col) }
                var i = 0
                while (i + 5 < t.size) { tr.addTriangle(t[i], t[i + 1], t[i + 2], t[i + 3], t[i + 4], t[i + 5]); i += 6 }
                add(tr)
            }
        }
        if (!tall) {
            val s = 30f
            add(HudPrimitives.labelInk(dest, x + 16f, y + 9f, 0.56f, HudTheme.InkMuted, maxWidth = tile.w - 32f))
            arrow(x + 16f + s + 2f, y + 22f + (tile.h - 26f) / 2f, s)
            val tx = x + 16f + s * 2f + 18f
            val rightW = if (wide) 190f else 0f
            val tw = tile.w - (tx - x) - 12f - rightW
            add(HudPrimitives.label(w.metric, tx, y + 24f, 1.3f, M2Color.White, bold = true, maxWidth = tw))
            add(HudPrimitives.label(w.metricDetail, tx, y + 24f + HudText.lineHeight(1.3f, true) + 2f, 0.72f, M2Color.White, maxWidth = tw))
            if (wide) {
                val rx = x + tile.w - rightW + 8f
                add(HudPrimitives.labelInk("Arrival", rx, y + 26f, 0.52f, HudTheme.InkMuted))
                add(HudPrimitives.label(arrival, rx, y + 40f, 1.0f, M2Color.White, bold = true, maxWidth = rightW - 20f))
                add(HudPrimitives.labelInk("Remaining", rx, y + 74f, 0.52f, HudTheme.InkMuted))
                add(HudPrimitives.label(remaining, rx, y + 88f, 1.0f, M2Color.White, bold = true, maxWidth = rightW - 20f))
            } else if (tile.h >= 120f) {
                add(HudPrimitives.labelInk("Arr $arrival - $remaining", tx, y + tile.h - 34f, 0.6f, HudTheme.InkMuted, maxWidth = tw))
            }
        } else {
            val cx = x + tile.w / 2f
            add(HudPrimitives.labelInk(dest, x + 16f, y + 10f, 0.56f, HudTheme.InkMuted, maxWidth = tile.w - 32f))
            arrow(cx, y + 78f, 46f)
            centered(w.metric, cx, y + 138f, 1.5f, M2Color.White, true, tile.w - 28f)
            centered(w.metricDetail, cx, y + 182f, 0.8f, M2Color.White, false, tile.w - 28f)
            val half = tile.w / 2f
            add(HudPrimitives.labelInk("Arrival", x + half / 2f - 20f, y + 214f, 0.52f, HudTheme.InkMuted))
            centered(arrival, x + half / 2f, y + 228f, 1.0f, M2Color.White, true, half - 12f)
            add(HudPrimitives.labelInk("Remaining", x + half + half / 2f - 28f, y + 214f, 0.52f, HudTheme.InkMuted))
            centered(remaining, x + half + half / 2f, y + 228f, 1.0f, M2Color.White, true, half - 12f)
        }
    }

    /**
     * Icon-led tile (Spotify, Conversate and every other app): large glossy icon at the left, then title,
     * a status line and one line of support. Text comes only from the widget's real state: catalogue
     * placeholders ([HomeWidget.live] == false) are never shown as data.
     */
    private fun drawIconTile(tile: TileLayout, accent: M2Color) {
        val w = tile.widget
        val iconSize = if (tile.h >= 120f) 72f else 52f
        val ix = tile.x + 16f
        val iy = tile.y + (tile.h - iconSize) / 2f
        var drawn = false
        if (w.id == "spotify") {
            val enc = BrandHudIcons.spotify(iconSize.toInt())
            createHudImage(enc.bytes, enc.width, enc.height, ix, iy, iconSize, iconSize, roundCorners = false, resident = true)?.let { add(it); drawn = true }
        } else {
            val atlas = if (iconSize >= 72f) 72 else 56
            PremiumHudIcons.image(w.id, atlas, ix + (iconSize - atlas) / 2f, tile.y + (tile.h - atlas) / 2f)
                ?.let { add(it); drawn = true }
        }
        val tx = if (drawn) ix + iconSize + 14f else ix
        val tw = tile.x + tile.w - 14f - tx

        val title = w.title
        var status = ""
        var statusInk: M2Color = accent
        var support = ""
        when (w.id) {
            "spotify" -> when (w.state) {
                "playing", "paused" -> {
                    val artist = w.metricDetail.takeIf { d -> d.any { it.isLetterOrDigit() } }.orEmpty()
                    val label = if (w.state == "playing") "Playing" else "Paused"
                    status = w.subtitle.ifBlank { label }
                    statusInk = if (w.state == "playing") HudTheme.BrandSpotify else M2Color.White
                    support = if (artist.isNotBlank()) "$artist - $label" else label
                }
                "loading" -> { status = "Connecting..."; statusInk = M2Color.White; support = "Checking Spotify" }
                "disconnected" -> { status = "Not connected"; statusInk = M2Color.Orange; support = "Open to connect Spotify" }
                "unavailable" -> { status = "Unavailable"; statusInk = M2Color.Orange; support = "Spotify is not responding" }
                else -> { status = "Tap to open"; statusInk = HudTheme.FocusGlow; support = "No playback info" }
            }
            "conversate" -> when (w.state) {
                "live" -> {
                    status = "Recording"; statusInk = M2Color.Red
                    support = "Open to stop or save"
                }
                "idle" -> {
                    status = "Idle"; statusInk = M2Color.White
                    support = listOf(w.subtitle, w.metricDetail).filter { it.isNotBlank() }.joinToString(" - ")
                }
                else -> { status = "Tap to open"; statusInk = HudTheme.FocusGlow; support = "Briefing and live cues" }
            }
            else -> if (w.live && (w.metric.isNotBlank() || w.metricDetail.isNotBlank())) {
                status = listOf(w.metric, w.metricDetail).filter { it.isNotBlank() }.joinToString(" ")
                support = w.subtitle
            } else {
                status = w.subtitle.ifBlank { "Tap to open" }
            }
        }
        val titleScale = 1.1f
        val statusScale = 0.9f
        val supportScale = 0.7f
        val hTitle = HudText.titleHeight(titleScale)
        val hStatus = HudText.lineHeight(statusScale, false)
        val hSupport = HudText.lineHeight(supportScale, false)
        val lines = if (support.isNotBlank()) 3 else 2
        val block = hTitle + 4f + hStatus + if (lines == 3) 4f + hSupport else 0f
        var y = tile.y + ((tile.h - block) / 2f).coerceAtLeast(8f)
        add(HudPrimitives.label(title, tx, y, titleScale, bold = true, maxWidth = tw))
        y += hTitle + 4f
        add(HudPrimitives.label(status, tx, y, statusScale, statusInk, bold = false, maxWidth = tw))
        if (lines == 3) {
            y += hStatus + 4f
            add(HudPrimitives.labelInk(support, tx, y, supportScale, HudTheme.InkMuted, maxWidth = tw))
        }
    }

    private fun drawPageDots() {
        val total = hud.pageCount.coerceAtLeast(1)
        if (total <= 1) return
        val step = 16f
        val dotsY = 384f
        val startX = (HudTheme.W - (total - 1) * step) / 2f
        for (i in 0 until total) {
            if (i == hud.pageIndex) {
                // Active page = short capsule.
                add(
                    M2RectFilled(HudTheme.FocusGlow).apply {
                        setDimensions(startX + i * step - 8f, dotsY, 16f, 6f)
                        softCorners(3f)
                    },
                )
            } else {
                add(
                    M2RectFilled(HudTheme.InkEdge.withAlpha(0.9f)).apply {
                        setDimensions(startX + i * step - 3f, dotsY, 6f, 6f)
                        softCorners(3f)
                    },
                )
            }
        }
    }

    /**
     * Ask MyMav: dim the board, then an opaque answer card + AUDIO · TEXT · CANCEL.
     * Geometry comes from [HomeBoardCatalog.AskLayout] so gaze hit-tests match.
     */
    private fun drawAskModal() {
        add(
            // Fully dim the board behind the bubble — a half-see-through scrim is
            // exactly what made the card read as "transparent": the dark backdrop
            // and the dark card body blended into one continuous translucent sheet.
            M2RectFilled(M2Color.Black.withAlpha(0.88f)).apply {
                setDimensions(0f, 0f, HudTheme.W, HudTheme.H)
            },
        )
        val ids = HomeBoardCatalog.AskLayout.pillIds(hud.askHasMore, hud.askCanSave, hud.askCanCook, hud.askCooking, hud.askTimerLabel.isNotBlank())
        val geo = HomeBoardCatalog.AskLayout.geometry(hud.avatarX, hud.avatarY, hud.askExpanded, ids.size)
        val bw = geo.bubbleW
        val bh = geo.bubbleH
        // Ask is its own colour scope ("app:ask"): the answer card and its action pills share one override.
        val card = GlassSurface.withScope(com.mymav.glasses.api.MymavHudPrefs.appScope("ask")) {
            HudPrimitives.frostedPanel(
                geo.bubbleX, geo.bubbleY, bw, bh,
                border = HudTheme.FocusGlow,
                radius = 18f,
                fillAlpha = 1f,
                borderAlpha = 0.9f,
                gradient = false,
                innerGlow = false,
                glass = true,
            )
        }
        HudPrimitives.addPanel(this, card)
        val textScale = 0.68f
        if (hud.askExpanded) {
            drawExpandedBody(geo, textScale)
        } else {
            val lineH = HudText.lineHeight(textScale, false) + 4f
            val lines = wrapParagraphs(hud.avatarSpeech, bw - 36f, textScale, 3)
            val blockH = lines.size * lineH
            val top = geo.bubbleY + ((bh - blockH) / 2f).coerceAtLeast(8f) + 2f
            lines.forEachIndexed { i, line ->
                add(HudPrimitives.label(line, geo.bubbleX + 18f, top + i * lineH, textScale, bold = false))
            }
        }
        if (hud.askChoiceVisible) {
            val pillH = HomeBoardCatalog.AskLayout.PILL_H
            val allLabels = mapOf(
                "more" to (if (hud.askCooking) "NEXT" else if (hud.askCanSave || hud.askPageLabel.isBlank()) "MORE" else "MORE ${hud.askPageLabel}"),
                "save" to "SAVE", "audio" to "AUDIO", "text" to "TEXT", "cancel" to "CANCEL",
                "prev" to "BACK", "cook" to "COOK", "timer" to "TMR ${hud.askTimerLabel}",
            )
            ids.forEachIndexed { index, id ->
                val px = geo.pillX(index)
                // Glass pill; the action's colour identity stays as its code-drawn edge (brightened for the additive display).
                val pill = GlassSurface.withScope(com.mymav.glasses.api.MymavHudPrefs.appScope("ask")) {
                    HudPrimitives.frostedPanel(
                        px, geo.pillY, geo.pillW, pillH,
                        border = askEdge(id),
                        radius = pillH / 2f,
                        borderAlpha = 0.9f,
                        gradient = false,
                        glass = true,
                    )
                }
                HudPrimitives.addPanel(this, pill)
                add(
                    HudPrimitives.centeredPillLabel(
                        allLabels.getValue(id), px, geo.pillY, geo.pillW, pillH,
                        scale = if (id == "timer") 0.5f else 0.62f,
                        color = M2Color.White,
                        tracked = false,
                        bold = true,
                    ),
                )
                val bar = M2RectFilled(M2Color.Black.withAlpha(0.7f)).apply { park() }
                askBars[id] = bar
                add(bar)
            }
            add(askRing.wash)
            add(askRing.ring)
        }
    }

    /** Edge colour that keeps each Ask action's identity on its glass pill. */
    private fun askEdge(id: String): M2Color = when (id) {
        "more" -> M2Color.Green
        "save" -> M2Color.Orange
        "audio" -> M2Color.Yellow
        "text" -> M2Color.Cyan
        "cook" -> M2Color.Pink
        "timer" -> M2Color.Blue
        else -> M2Color.BlueGrey
    }

    /** Word-wrap that honours explicit line breaks (pre-wrapped pages, lists). */
    private fun wrapParagraphs(text: String, width: Float, scale: Float, maxLines: Int): List<String> {
        val out = ArrayList<String>()
        for (para in text.split('\n')) {
            if (para.isBlank()) { out += ""; continue }
            out += HudText.wrap(para, width, scale, maxLines, bold = false)
            if (out.size >= maxLines) break
        }
        while (out.isNotEmpty() && out.last().isEmpty()) out.removeAt(out.lastIndex)
        return if (out.size > maxLines) out.take(maxLines) else out
    }

    /** Large answer card: optional picture on the left, wrapped text, page marker. */
    private fun drawExpandedBody(geo: HomeBoardCatalog.AskLayout.Geometry, textScale: Float) {
        val pad = 18f
        var textX = geo.bubbleX + pad
        var textW = geo.bubbleW - pad * 2f
        val img = hud.askImageBytes
        if (img != null) {
            val iw = HomeBoardCatalog.AskLayout.EXP_IMG_W
            val ih = HomeBoardCatalog.AskLayout.EXP_IMG_H
            val ix = geo.bubbleX + pad
            val iy = geo.bubbleY + pad
            val cropped = centerCropJpeg(img, iw, ih)
            if (cropped != null) {
                createHudImage(
                    cropped.bytes, cropped.width, cropped.height,
                    ix, iy, cropped.width.toFloat(), cropped.height.toFloat(),
                    cornerRadius = 10f,
                )?.let { add(it) }
            }
            textX = ix + iw + 14f
            textW = geo.bubbleX + geo.bubbleW - pad - textX
        }
        val big = hud.askCooking && img == null
        val scale = if (big) 0.95f else textScale
        val lineH = HudText.lineHeight(scale, false) + 4f
        val maxLines = ((geo.bubbleH - pad * 2f - 24f) / lineH).toInt().coerceAtLeast(3)
        val lines = wrapParagraphs(hud.avatarSpeech, textW, scale, maxLines)
        lines.forEachIndexed { i, line ->
            if (line.isEmpty()) return@forEachIndexed
            add(HudPrimitives.label(line, textX, geo.bubbleY + pad + i * lineH, scale, bold = false))
        }
        if (hud.askPageLabel.isNotBlank()) {
            val lbl = hud.askPageLabel
            val w = HudText.width(lbl, 0.48f)
            add(
                HudPrimitives.label(
                    lbl, geo.bubbleX + geo.bubbleW - 14f - w, geo.bubbleY + geo.bubbleH - 26f, 0.48f,
                    HudTheme.TextMuted,
                ),
            )
        }
    }

    private fun drawAvatar() {
        if (!hud.avatarVisible) return
        val aw = avatarSize
        val parked = hud.avatarX < 0f || hud.avatarY < 0f
        val ax = if (parked) PARK else (hud.avatarX - aw / 2f).coerceIn(4f, HudTheme.W - aw - 4f)
        val ay = if (parked) PARK else (hud.avatarY - aw / 2f).coerceIn(4f, HudTheme.H - aw - 8f)
        val bytes = hud.avatarImageBytes
        val poses = hud.avatarPoses
        if (cellSize > 0 && !poses.isNullOrEmpty()) {
            val cell = hud.avatarCell.coerceIn(0, poses.size - 1)
            poses.forEachIndexed { index, pose ->
                val on = index == cell && !parked
                val img = createHudImage(
                    pose, cellSize, cellSize,
                    // Always full size (Native 1:1 fit); parked = moved off-canvas only.
                    if (on) ax else PARK, if (on) ay else PARK, aw, aw,
                    circular = false,
                    roundCorners = false,
                )
                if (img != null) {
                    poseImages.add(img)
                    add(img)
                } else {
                    android.util.Log.w("HomeBoard", "avatar pose $index failed")
                }
            }
            if (poseImages.size == poses.size) {
                currentCell = cell
                return
            }
            poseImages.forEach { runCatching { it.setDimensions(PARK, PARK, aw, aw) } }
            poseImages.clear()
        }
        val img = createHudImage(
            bytes,
            hud.avatarImageWidth,
            hud.avatarImageHeight,
            ax, ay, aw, aw,
            circular = false,
            roundCorners = false,
        )
        if (img != null) {
            avatarImage = img
            add(img)
        } else {
            val placeholder = M2RectFilled(HudTheme.InkPanel.withAlpha(0.01f))
            placeholder.park()
            avatarPlaceholder = placeholder
            add(placeholder)
        }
    }

    // ---- touch -----------------------------------------------------------------

    override fun onTouch(touch: M2Touch) {
        super.onTouch(touch)
        val h = hud
        if (h.askChoiceVisible) {
            val ids = HomeBoardCatalog.AskLayout.pillIds(h.askHasMore, h.askCanSave, h.askCanCook, h.askCooking, h.askTimerLabel.isNotBlank())
            val defaultSel = if (h.askHasMore) "more" else if (h.askCanCook) "cook" else "text"
            fun step(dx: Int) {
                val cur = ids.indexOf(askSel ?: h.askChoiceFocus ?: defaultSel).coerceAtLeast(0)
                askSel = ids[(cur + dx).coerceIn(0, ids.lastIndex)]
                applyAskDwell()
            }
            when (touch) {
                // Forward / Backward move the selection along AUDIO · TEXT · CANCEL.
                M2Touch.Forward -> step(1)
                M2Touch.Backward -> step(-1)
                // Tap activates the pill you're looking at / selected (default: TEXT).
                M2Touch.Tap -> when (askSel ?: h.askChoiceFocus ?: defaultSel) {
                    "more" -> h.actions.onAskMore?.invoke()
                    "save" -> h.actions.onAskSave?.invoke()
                    "prev" -> h.actions.onAskPrev?.invoke()
                    "cook" -> h.actions.onAskCook?.invoke()
                    "timer" -> h.actions.onAskTimer?.invoke()
                    "audio" -> h.actions.onAskAudio?.invoke()
                    "cancel" -> h.actions.onAskCancel?.invoke()
                    else -> h.actions.onAskText?.invoke()
                }
                // Long tap always cancels.
                M2Touch.LongTap -> h.actions.onAskCancel?.invoke()
                else -> Unit
            }
            return
        }
        val placed = placedFor(h.widgets, h.pageIndex)
        if (placed.isEmpty()) return
        val delta = FocusGridNav.delta(touch)
        if (delta != null) {
            val (dx, dy) = delta
            val next = FocusGridNav.moveWidgets(placed, focusedId, dx, dy)
            h.actions.onFocusChanged?.invoke(next)
            return
        }
        when (touch) {
            M2Touch.Tap -> h.actions.onOpenWidget?.invoke(focusedId)
            // Sim / no-LOS: Forward summons MyMav (1s avatar gaze on real LOS).
            M2Touch.Forward -> h.actions.onAvatarTapped?.invoke()
            M2Touch.LongTap -> {
                val nextPage = (h.pageIndex + 1) % h.pageCount.coerceAtLeast(1)
                h.actions.onPageChanged?.invoke(nextPage)
            }
            else -> Unit
        }
    }

    // ---- layout ----------------------------------------------------------------

    private class TileLayout(val widget: HomeWidget, val x: Float, val y: Float, val w: Float, val h: Float)

    private fun placedFor(widgets: List<HomeWidget>, pageIndex: Int): List<PlacedWidget> {
        if (widgets.isEmpty()) return emptyList()
        val fromCatalog = HomeBoardCatalog.layoutPage(pageIndex)
        return if (fromCatalog.map { it.widget.id } == widgets.map { it.id }) fromCatalog else packLocal(widgets)
    }

    private fun layoutTiles(widgets: List<HomeWidget>, pageIndex: Int): List<TileLayout> =
        placedFor(widgets, pageIndex).map { p ->
            // Draw the caller's widget (fresh metrics) at the catalog cell position.
            val widget = widgets.firstOrNull { it.id == p.widget.id } ?: p.widget
            val r = HomeBoardCatalog.cellRect(p.col, p.row, p.colSpan, p.rowSpan)
            TileLayout(widget, r.x, r.y, r.w, r.h)
        }

    private fun packLocal(widgets: List<HomeWidget>): List<PlacedWidget> {
        val occupied = Array(2) { BooleanArray(2) }
        val out = mutableListOf<PlacedWidget>()
        fun fits(col: Int, row: Int, cs: Int, rs: Int): Boolean {
            if (col + cs > 2 || row + rs > 2) return false
            for (r in row until row + rs) for (c in col until col + cs) if (occupied[r][c]) return false
            return true
        }
        fun mark(col: Int, row: Int, cs: Int, rs: Int) {
            for (r in row until row + rs) for (c in col until col + cs) occupied[r][c] = true
        }
        for (w in widgets) {
            var done = false
            outer@ for (row in 0 until 2) for (col in 0 until 2) {
                if (fits(col, row, w.size.colSpan, w.size.rowSpan)) {
                    mark(col, row, w.size.colSpan, w.size.rowSpan)
                    out.add(PlacedWidget(w, col, row, w.size.colSpan, w.size.rowSpan))
                    done = true
                    break@outer
                }
            }
            if (!done) out.add(PlacedWidget(w, 0, 0, 1, 1))
        }
        return out
    }

    // ---- avatar ----------------------------------------------------------------

    /** Move (or hide) the mascot without rebuilding the board. Texture never changes here. */
    fun patchAvatar(
        visible: Boolean,
        centerX: Float,
        centerY: Float,
        @Suppress("UNUSED_PARAMETER") imageBytes: ByteArray?,
        @Suppress("UNUSED_PARAMETER") imageWidth: Int,
        @Suppress("UNUSED_PARAMETER") imageHeight: Int,
    ): Boolean {
        val layer = poseImages.getOrNull(currentCell) ?: avatarImage
        val placeholder = avatarPlaceholder
        if (layer == null && placeholder == null) return false
        placeholder?.let { runCatching { it.park() } }
        if (!visible || centerX < 0f || centerY < 0f) {
            // Keep the size (a 1×1 then full-size resize re-crops Native textures).
            layer?.let { runCatching { it.setDimensions(PARK, PARK, avatarSize, avatarSize) } }
            avatarGazeBar.park()
            return true
        }
        val aw = avatarSize
        val ax = (centerX - aw / 2f).coerceIn(4f, HudTheme.W - aw - 4f)
        val ay = (centerY - aw / 2f).coerceIn(4f, HudTheme.H - aw - 8f)
        layer?.let { runCatching { it.setDimensions(ax, ay, aw, aw) } }
        applyAvatarGazeBar()
        return true
    }

    /** Move the docked mascot and show pose [cell] (park the old one; no upload). */
    fun patchAvatarCell(visible: Boolean, centerX: Float, centerY: Float, cell: Int): Boolean {
        if (poseImages.isEmpty()) return patchAvatar(visible, centerX, centerY, null, 0, 0)
        val next = cell.coerceIn(0, poseImages.size - 1)
        if (next != currentCell) {
            // Park every inactive pose, not just the previous one, and repeat shortly after:
            // a dropped park command otherwise leaves a stuck second mascot on the glasses.
            parkInactivePoses(next)
            currentCell = next
            poseReconcile.removeCallbacks(reconcileRunnable)
            poseReconcile.postDelayed(reconcileRunnable, 300L)
        }
        return patchAvatar(visible, centerX, centerY, null, 0, 0)
    }

    private val poseReconcile = android.os.Handler(android.os.Looper.getMainLooper())
    private val reconcileRunnable = Runnable { parkInactivePoses(currentCell) }

    private fun parkInactivePoses(keep: Int) {
        poseImages.forEachIndexed { index, image ->
            if (index != keep) runCatching { image.setDimensions(PARK, PARK, avatarSize, avatarSize) }
        }
    }

    // ---- tile content ----------------------------------------------------------

    /** WhatsApp / Instagram unread counts with official marks. */
    private fun drawNotifyBrandMetrics(tile: TileLayout) {
        val waCount = tile.widget.metric.removePrefix("WA:").trim().ifBlank { "0" }
        val igCount = tile.widget.metricDetail.removePrefix("IG:").trim().ifBlank { "0" }
        val icon = if (tile.h >= 120f) 60f else 46f
        val countScale = 1.1f
        val countW = maxOf(HudText.width(waCount, countScale), HudText.width(igCount, countScale)) + 8f
        val gap = 28f
        val pairW = icon + countW + gap + icon + countW
        val startX = tile.x + ((tile.w - pairW) / 2f).coerceAtLeast(12f)
        val contentTop = tile.y + 46f
        val y = contentTop + ((tile.y + tile.h - 12f) - contentTop - icon) / 2f
        val textY = y + (icon - HudText.lineHeight(countScale)) / 2f
        val encodePx = icon.toInt()
        drawBrandMark(BrandHudIcons.whatsApp(encodePx), startX, y, icon)
        add(HudPrimitives.label(waCount, startX + icon + 10f, textY, countScale, countInk(waCount)))
        val igX = startX + icon + countW + gap
        drawBrandMark(BrandHudIcons.instagram(encodePx), igX, y, icon)
        add(HudPrimitives.label(igCount, igX + icon + 10f, textY, countScale, countInk(igCount)))
    }

    private fun countInk(count: String): M2Color =
        if (count.toIntOrNull()?.let { it > 0 } == true) M2Color.White else M2Color.White

    /** Clean transparent brand mark, drawn 1:1 (Native fit), no plate, no clipping. */
    private fun drawBrandMark(encoded: BrandHudIcons.Encoded, x: Float, y: Float, size: Float) {
        createHudImage(
            encoded.bytes, encoded.width, encoded.height,
            x, y, size, size,
            circular = false,
            roundCorners = false,
            resident = true,
        )?.let { add(it) }
    }

    /**
     * Health tile: nested heart tracks (steps / active / eaten) plus the four daily figures.
     * Values and ring progress are patched in place — see [patchHealthSummary].
     */
    /** Align a block to [centerY] without shrinking type; clamp so it stays between [minTop] and [maxBottom]. */
    private fun centerBlock(centerY: Float, blockH: Float, minTop: Float, maxBottom: Float): Float {
        val ideal = centerY - blockH / 2f
        val maxTop = maxBottom - blockH
        return if (maxTop >= minTop) ideal.coerceIn(minTop, maxTop) else minTop
    }

    private fun drawHealthSummary(tile: TileLayout) {
        val snap = healthSnapshot()
        val wide = tile.w > 400f || tile.widget.size == HomeWidgetSize.Wide || tile.widget.size == HomeWidgetSize.Large
        val titleH = HudText.titleHeight(1.1f).coerceAtLeast(28f)
        val bottomH = HudText.boxHeight(0.58f, false) + 4f
        val ringPx = when {
            wide && tile.h > 200f -> 96
            wide -> 72
            tile.h > 200f -> 88
            else -> 70
        }
        val rx = tile.x + 10f
        val titleBottom = tile.y + 12f + titleH
        val footerTop = tile.y + tile.h - bottomH
        val ry = titleBottom + ((footerTop - 4f - titleBottom - ringPx) / 2f).coerceAtLeast(0f)
        val encoded = HealthHudIcons.activityHeart(ringPx, snap.stepsProgress(), snap.activeProgress(), snap.eatenProgress())
        val ringImage = createHudImage(
            encoded.bytes, encoded.width, encoded.height,
            rx, ry, ringPx.toFloat(), ringPx.toFloat(),
            circular = false, roundCorners = false, resident = false,
        )
        if (ringImage != null) add(ringImage)
        val values = arrayOfNulls<M2Text>(3)
        val labels = arrayOfNulls<M2Text>(3)
        val names = arrayOf("Steps", "Active", "Eaten")
        val texts = arrayOf(snap.stepsLabel(), snap.activeLabel(true), snap.eatenLabel(true))
        val dots = intArrayOf(0xFF34C759u.toInt(), 0xFF32B8DCu.toInt(), 0xFFF2786Bu.toInt())
        val total: M2Text
        val fresh: M2Text
        val wideColCenters: FloatArray?
        val wideValueY: Float
        val patchScale: Float
        val patchMaxW: Float
        if (wide) {
            val colsX = rx + ringPx + 12f
            val colsW = tile.x + tile.w - 12f - colsX
            val colW = colsW / 3f
            val valueScale = if (tile.h > 200f) 1.25f else 1.05f
            val labelScale = 0.58f
            val valueH = HudText.boxHeight(valueScale, true)
            val labelH = HudText.boxHeight(labelScale, false)
            val blockH = valueH + 2f + labelH
            val heartCy = ry + ringPx / 2f
            val valueY = centerBlock(heartCy, blockH, titleBottom + 2f, footerTop - 4f)
            val labelY = valueY + valueH + 2f
            val centers = FloatArray(3)
            names.indices.forEach { i ->
                val colLeft = colsX + i * colW
                val mid = colLeft + colW / 2f
                centers[i] = mid
                val shown = HudText.fit(texts[i], colW - 8f, valueScale, true)
                val vw = HudText.width(shown, valueScale, true)
                val vx = mid - vw / 2f
                values[i] = HudPrimitives.label(
                    shown, vx, valueY, valueScale, M2Color.White, bold = true, maxWidth = colW - 8f,
                ).also { add(it) }
                val lw = HudText.width(names[i], labelScale, false)
                val pairW = 6f + 4f + lw
                val pairX = mid - pairW / 2f
                add(M2RectFilled(dots[i].toUInt()).apply {
                    setDimensions(pairX, labelY + 4f, 6f, 6f)
                    softCorners(3f)
                })
                labels[i] = HudPrimitives.labelInk(
                    names[i], pairX + 10f, labelY, labelScale, HudTheme.InkMuted, maxWidth = colW - 8f,
                ).also { add(it) }
            }
            wideColCenters = centers
            wideValueY = valueY
            patchScale = valueScale
            patchMaxW = colW - 8f
            val by = tile.y + tile.h - bottomH
            total = HudPrimitives.label(
                "Total burnt ${snap.totalLabel(true)}",
                tile.x + 14f, by, 0.62f, M2Color.White, maxWidth = tile.w * 0.55f,
            ).also { add(it) }
            val freshLabel = snap.freshnessLabel().ifBlank { if (snap.available) "" else "No data yet" }
            val fw = HudText.width(freshLabel, 0.56f)
            fresh = HudPrimitives.label(
                freshLabel.ifBlank { " " },
                tile.x + tile.w - 14f - fw, by, 0.56f,
                if (snap.kind == HealthHeartSnapshot.Kind.Cached || snap.kind == HealthHeartSnapshot.Kind.Stale) M2Color.Orange else M2Color.White,
                maxWidth = tile.w * 0.42f,
            ).also { add(it) }
        } else {
            val tx = rx + ringPx + 8f
            val tw = tile.x + tile.w - 10f - tx
            val valueScale = 0.82f
            val valueBox = HudText.boxHeight(valueScale, true)
            val labelBox = HudText.boxHeight(0.56f, false)
            val minRow = maxOf(valueBox, labelBox)
            val avail = (footerTop - 4f) - (titleBottom + 2f)
            val slack = if (avail > 0f && minRow * 3f > avail) {
                ((avail - minRow * 3f) / 3f).coerceAtLeast(0f)
            } else {
                2f
            }
            val rowH = minRow + slack
            val blockH = rowH * 3f
            val heartCy = ry + ringPx / 2f
            val blockTop = centerBlock(heartCy, blockH, titleBottom + 2f, footerTop - 4f)
            names.indices.forEach { i ->
                val y = blockTop + i * rowH
                add(M2RectFilled(dots[i].toUInt()).apply {
                    setDimensions(tx, y + 5f, 6f, 6f)
                    softCorners(3f)
                })
                labels[i] = HudPrimitives.labelInk(
                    names[i], tx + 10f, y, 0.56f, HudTheme.InkMuted, maxWidth = tw * 0.42f,
                ).also { add(it) }
                val vw = HudText.width(texts[i], valueScale, true)
                values[i] = HudPrimitives.label(
                    texts[i], tx + tw - vw, y, valueScale, M2Color.White, bold = true, maxWidth = tw * 0.58f,
                ).also { add(it) }
            }
            val by = tile.y + tile.h - bottomH
            total = HudPrimitives.label(
                "Total burnt ${snap.totalLabel(true)}",
                tile.x + 12f, by, 0.58f, M2Color.White, maxWidth = tile.w - 24f,
            ).also { add(it) }
            fresh = HudPrimitives.label(" ", tile.x + 12f, by, 0.5f, M2Color.White)
            wideColCenters = null
            wideValueY = 0f
            patchScale = 0.82f
            patchMaxW = (tile.x + tile.w - 10f - (rx + ringPx + 8f)) * 0.58f
        }
        if (ringImage != null) {
            healthPatch = HealthPatch(
                ring = ringImage,
                ringPx = ringPx,
                values = values.filterNotNull().toTypedArray(),
                total = total,
                fresh = fresh,
                ringKey = HealthHudIcons.heartCacheKey(ringPx, snap.stepsProgress(), snap.activeProgress(), snap.eatenProgress()),
                valueMaxW = patchMaxW,
                wide = wide,
                valueScale = patchScale,
                valueY = wideValueY,
                colCenters = wideColCenters,
            )
        }
    }

    private fun healthSnapshot(): HealthHeartSnapshot {
        val w = hud.widgets.firstOrNull { it.id == "fitness" } ?: return HealthHeartSnapshot()
        return HealthHeartSnapshot.decode(w.metric, w.metricDetail, w.state, w.bodyLines, w.live)
    }

    private fun patchHealthSummary() {
        val patch = healthPatch ?: return
        val snap = healthSnapshot()
        val texts = arrayOf(snap.stepsLabel(), snap.activeLabel(true), snap.eatenLabel(true))
        patch.values.forEachIndexed { i, label ->
            val next = HudText.fit(texts.getOrElse(i) { "--" }, patch.valueMaxW, patch.valueScale, true)
            label.setHudText(next)
            val mid = patch.colCenters?.getOrNull(i)
            if (mid != null) {
                val vw = HudText.width(next, patch.valueScale, true)
                label.setXY(mid - vw / 2f, patch.valueY)
            }
        }
        patch.total.setHudText(HudText.fit("Total burnt ${snap.totalLabel(true)}", 220f, 0.58f, false))
        val freshLabel = snap.freshnessLabel().ifBlank { if (snap.available) " " else "No data yet" }
        patch.fresh.setHudText(freshLabel)
        patch.fresh.setColor(
            if (snap.kind == HealthHeartSnapshot.Kind.Cached || snap.kind == HealthHeartSnapshot.Kind.Stale) {
                M2Color.Orange
            } else {
                M2Color.White
            },
        )
        val key = HealthHudIcons.heartCacheKey(patch.ringPx, snap.stepsProgress(), snap.activeProgress(), snap.eatenProgress())
        if (key == patch.ringKey) return
        val encoded = HealthHudIcons.activityHeart(patch.ringPx, snap.stepsProgress(), snap.activeProgress(), snap.eatenProgress())
        val res = hudImageResource(encoded.bytes, encoded.width, encoded.height, resident = false) ?: return
        patch.ring.setFill(res, M2TextureFit.Native, true)
        patch.ringKey = key
    }

    private class HealthPatch(
        val ring: M2Image,
        val ringPx: Int,
        val values: Array<M2Text>,
        val total: M2Text,
        val fresh: M2Text,
        var ringKey: String,
        val valueMaxW: Float,
        val wide: Boolean,
        val valueScale: Float,
        val valueY: Float,
        val colCenters: FloatArray?,
    )

    /** Tasks tile — today's open tasks in due order ("HH:MM|Title"). */
    private fun drawTasksToday(tile: TileLayout, emptyText: String = "Nothing due today") {
        val rows = tile.widget.bodyLines
        val accent = tile.widget.accent.toM2Color()
        val top = tile.y + 46f
        val rowH = 23f
        val capacity = ((tile.y + tile.h - 10f - top) / rowH).toInt().coerceAtLeast(1)
        if (rows.isEmpty()) {
            add(HudPrimitives.label(emptyText, tile.x + 16f, top, 0.58f, HudTheme.TextPrimary, maxWidth = tile.w - 32f))
            return
        }
        val showMore = rows.size > capacity
        val shown = if (showMore) capacity - 1 else rows.size
        val timeW = HudText.width("00:00", 0.58f) + 10f
        rows.take(shown.coerceAtLeast(1)).forEachIndexed { i, row ->
            val parts = row.split('|', limit = 2)
            val time = parts.getOrElse(0) { "" }
            val title = parts.getOrElse(1) { row }
            val y = top + i * rowH
            if (time.isNotBlank()) {
                add(HudPrimitives.label(time, tile.x + 16f, y, 0.58f, accent))
                add(HudPrimitives.label(title, tile.x + 16f + timeW, y, 0.58f, HudTheme.TextPrimary, maxWidth = tile.w - 32f - timeW))
            } else {
                add(HudPrimitives.label(title, tile.x + 16f, y, 0.58f, HudTheme.TextPrimary, maxWidth = tile.w - 32f))
            }
        }
        if (showMore) {
            val y = top + shown.coerceAtLeast(1) * rowH
            add(HudPrimitives.label("+${rows.size - shown} more", tile.x + 16f, y, 0.52f, HudTheme.TextMuted))
        }
    }

    /** Center-crop source art into the tile (never stretched), encoded 1:1 for Native fit. */
    private fun addCoverFit(bytes: ByteArray, x: Float, y: Float, w: Float, h: Float) {
        val cropped = centerCropJpeg(bytes, w, h) ?: return
        createHudImage(
            cropped.bytes, cropped.width, cropped.height,
            x, y, cropped.width.toFloat(), cropped.height.toFloat(),
            cornerRadius = HudTheme.PANEL_RADIUS - 1f,
        )?.let { add(it) }
    }

    private class Cropped(val bytes: ByteArray, val width: Int, val height: Int)

    private fun centerCropJpeg(bytes: ByteArray, dstW: Float, dstH: Float): Cropped? = runCatching {
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
        val outW = dstW.toInt().coerceIn(32, 608)
        val outH = dstH.toInt().coerceIn(32, 400)
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val srcAspect = decoded.width.toFloat() / decoded.height.toFloat()
        val dstAspect = outW.toFloat() / outH.toFloat()
        val srcRect = if (srcAspect > dstAspect) {
            val cropW = (decoded.height * dstAspect).toInt().coerceAtLeast(1)
            val left = ((decoded.width - cropW) / 2).coerceAtLeast(0)
            Rect(left, 0, left + cropW, decoded.height)
        } else {
            val cropH = (decoded.width / dstAspect).toInt().coerceAtLeast(1)
            val top = ((decoded.height - cropH) / 2).coerceAtLeast(0)
            Rect(0, top, decoded.width, top + cropH)
        }
        canvas.drawBitmap(
            decoded,
            srcRect,
            RectF(0f, 0f, outW.toFloat(), outH.toFloat()),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
        )
        val stream = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, 92, stream)
        decoded.recycle()
        out.recycle()
        Cropped(stream.toByteArray(), outW, outH)
    }.getOrNull()

    companion object {

        /**
         * Everything that requires a rebuild. Gaze-rate fields (focus, dwell,
         * progress, avatar position/pose, clock text) are deliberately excluded.
         */
        fun structureKeyOf(h: GlassesHud.HomeBoard): String = buildString {
            append(HudFonts.epoch).append('#')
            // Glass look (style, opacity, colours) version: any change rebuilds the board instead of patching it.
            append(com.mymav.glasses.api.MymavHudPrefs.glassVersion).append('g')
            append(h.pageIndex).append('/').append(h.pageCount)
            append('|').append(h.batteryPercent).append('/').append(h.phoneBatteryPercent).append(h.glassesCharging).append(h.phoneCharging)
            append('|').append(h.avatarVisible)
            append('|').append(h.avatarImageWidth).append('/').append(h.avatarCellSize)
            h.avatarPoses?.firstOrNull()?.let { append('#').append(System.identityHashCode(it)) }
            // Summoned/answer mascot image: the controller reuses one cached object per (preset, cell, size), so a new
            // identity means different artwork (preset change, production vs procedural) and must rebuild, not patch.
            if (h.avatarPoses.isNullOrEmpty()) h.avatarImageBytes?.let { append('@').append(System.identityHashCode(it)) }
            append('|').append(h.avatarSpeech)
            append('|').append(h.askChoiceVisible)
            append('|').append(h.askExpanded).append(h.askHasMore).append(h.askCanSave).append(h.askCanCook).append(h.askCooking).append(h.askTimerLabel).append(h.askPageLabel)
            h.askImageBytes?.let { append('#').append(it.size).append(':').append(it.contentHashCode()) }
            if (h.avatarSpeech.isNotBlank()) {
                // Bubble geometry follows the summoned avatar position.
                append('|').append(h.avatarX.toInt()).append(',').append(h.avatarY.toInt())
            }
            h.widgets.forEach { w ->
                append("|w:").append(w.id).append(',').append(w.title).append(',').append(w.subtitle)
                append(',').append(w.accent)
                if (w.id == "fitness") {
                    // Live Health numbers / ring progress are patched in place.
                    append(',').append(w.size).append(',').append(w.previewIsVideo)
                } else {
                    append(',').append(w.metric).append(',').append(w.metricDetail)
                    append(',').append(w.size).append(',').append(w.previewIsVideo).append(',').append(w.live).append(',').append(w.state)
                    if (w.bodyLines.isNotEmpty()) append(',').append(w.bodyLines.joinToString("~"))
                }
                val b = w.previewImageBytes
                if (b != null) {
                    append(',').append(w.previewImageWidth).append('x').append(w.previewImageHeight)
                    append('#').append(b.size).append(':').append(b.contentHashCode())
                }
            }
        }
    }
}
