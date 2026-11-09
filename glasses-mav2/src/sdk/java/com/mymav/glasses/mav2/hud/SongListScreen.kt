package com.mymav.glasses.mav2.hud

import com.everysight.mav2.sdk.uikit.data.M2Touch
import com.everysight.mav2.sdk.uikit.drawables.M2RectFilled
import com.everysight.mav2.sdk.uikit.drawables.ext.setDimensions
import com.everysight.mav2.sdk.uikit.screens.M2Screen
import com.everysight.mav2.sdk.utils.M2Color
import com.mymav.glasses.api.GlassesHud

/**
 * Spotify Liked Songs / search hits — focus a row, Tap or 1s gaze to play.
 */
class SongListScreen(
    private val hud: GlassesHud.SongList,
) : HudScreen(tag = "mymav-song-list") {
    override val glassScope: String? = com.mymav.glasses.api.MymavHudPrefs.appScope("spotify")

    private val background = HudPrimitives.fullBleedBackground()
    private val focused = hud.focusedIndex.coerceIn(0, (hud.items.size - 1).coerceAtLeast(0))

    override fun build() {
        add(background)
        GlassKit.appPanel(this)
        GlassKit.header(this, hud.title, "spotify", hud.statusLine.ifBlank { "${hud.items.size} tracks" }.takeForHud(24))
        GlassKit.button(this, "BACK", 32f, 66f, 96f, 32f, 0.8f)

        if (hud.items.isEmpty()) {
            GlassKit.card(this, 32f, 110f, 576f, 200f)
            GlassKit.message(this, "No liked songs yet", "Connect Spotify and open Liked in the app", 170f)
        } else {
            val start = (focused - 2).coerceAtLeast(0)
            val window = (start until (start + 5)).filter { it < hud.items.size }
            window.forEachIndexed { row, index ->
                val item = hud.items[index]
                val y = 106f + row * 50f
                val selected = index == focused
                GlassKit.card(this, 32f, y, 576f, 46f, selected, radius = 14f)
                add(HudPrimitives.label(item.title.takeForHud(42), 48f, y + 3f, 0.85f, M2Color.White, bold = selected, maxWidth = 540f))
                add(HudPrimitives.labelInk(item.artist.takeForHud(42), 48f, y + 25f, 0.7f, HudTheme.InkMuted, maxWidth = 540f))
            }
        }
        GlassKit.progress(this, hud.selectDwellProgress, 356f, HudTheme.BrandSpotify)
        GlassKit.footer(this, "Swipe to focus - Tap to play", "", y = 362f)
        HudPrimitives.drawHomePill(this, hud.homeDwellProgress)
    }

    override fun onTouch(touch: M2Touch) {
        super.onTouch(touch)
        when (touch) {
            M2Touch.Up -> {
                val next = (focused - 1).coerceAtLeast(0)
                hud.actions.onFocusChanged?.invoke(next)
            }
            M2Touch.Down -> {
                val next = (focused + 1).coerceAtMost((hud.items.size - 1).coerceAtLeast(0))
                hud.actions.onFocusChanged?.invoke(next)
            }
            M2Touch.Tap -> {
                val item = hud.items.getOrNull(focused)
                if (item != null) hud.actions.onSelect?.invoke(item)
            }
            M2Touch.Backward -> hud.actions.onBack?.invoke()
            M2Touch.LongTap -> {
                val home = hud.actions.onHomeRequested
                if (home != null) home.invoke() else hud.actions.onBack?.invoke()
            }
            else -> Unit
        }
        // Gaze HOME pill samples still flow via companion when wired.
        Unit
    }
}
