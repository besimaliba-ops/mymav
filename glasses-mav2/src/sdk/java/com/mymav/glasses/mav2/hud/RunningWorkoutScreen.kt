package com.mymav.glasses.mav2.hud

import com.everysight.mav2.sdk.uikit.data.M2Touch
import com.everysight.mav2.sdk.uikit.drawables.M2RectFilled
import com.everysight.mav2.sdk.uikit.drawables.ext.setDimensions
import com.everysight.mav2.sdk.uikit.screens.M2Screen
import com.everysight.mav2.sdk.utils.M2Color
import com.mymav.glasses.api.GlassesHomeController
import com.mymav.glasses.api.GlassesHud
import com.mymav.glasses.api.HudAccentTone

/**
 * Visletic “Stay on pace” running HUD — 640×400 soft glass.
 *
 * Top: AHEAD/BEHIND + delta (teal)
 * Center: horizontal pace slider + green marker, pace /KM + TGT
 * Bottom: ELAPSED | SPLIT M | KM
 */
class RunningWorkoutScreen(
    private val hud: GlassesHud.RunningPace,
) : HudScreen(tag = "mymav-running-pace") {
    override val glassScope: String? = com.mymav.glasses.api.MymavHudPrefs.appScope("workouts")

    private val teal = HudAccentTone.Teal.toM2Color()
    private val paceGreen = HudTheme.Gain
    private val background = HudPrimitives.fullBleedBackground()

    override fun build() {
        add(background)
        GlassKit.appPanel(this, 24f, 20f, 592f, 360f)
        HudPrimitives.drawHomePill(this, hud.homeDwellProgress)

        add(HudPrimitives.labelInk(hud.statusLabel, 48f, 40f, 0.85f, HudTheme.InkMuted, maxWidth = 380f))
        add(HudPrimitives.label(hud.deltaLabel, 48f, 70f, 1.6f, teal, bold = true, maxWidth = 520f))

        val trackX = 64f
        val trackY = 168f
        val trackW = 512f
        add(
            M2RectFilled(HudTheme.InkEdgeBright.withAlpha(0.8f)).apply {
                setDimensions(trackX, trackY, trackW, 4f)
                softCorners(2f)
            },
        )
        val centerX = trackX + trackW / 2f
        add(
            M2RectFilled(M2Color.White.withAlpha(0.95f)).apply {
                setDimensions(centerX - 1.5f, trackY - 10f, 3f, 24f)
                softCorners(1.5f)
            },
        )
        val norm = hud.paceSliderNorm.coerceIn(0.05f, 0.95f)
        val markerCx = trackX + trackW * norm
        add(
            M2RectFilled(paceGreen).apply {
                setDimensions(markerCx - 7f, trackY - 16f, 14f, 36f)
                softCorners(7f)
            },
        )

        add(HudPrimitives.label(hud.paceLabel, 64f, 206f, 1.6f, paceGreen, bold = true))
        add(HudPrimitives.labelInk(hud.paceUnit, 64f + HudText.width(hud.paceLabel, 1.6f, true) + 10f, 222f, 0.85f, HudTheme.InkMuted))
        val tgt = hud.targetLabel
        add(HudPrimitives.labelInk(tgt, 576f - HudText.width(hudSafe(tgt), 0.85f), 222f, 0.85f, HudTheme.InkMuted))

        GlassKit.card(this, 40f, 270f, 560f, 94f, radius = 16f)
        drawMetric("Elapsed", hud.elapsedLabel, 60f, 280f)
        drawMetric("Split m", hud.splitMeters.toString(), 250f, 280f)
        drawMetric("Km", hud.distanceKmLabel, 430f, 280f)
    }

    private fun drawMetric(header: String, value: String, x: Float, y: Float) {
        add(HudPrimitives.labelInk(header, x, y, 0.75f, HudTheme.InkMuted))
        add(HudPrimitives.label(value, x, y + 26f, 1.3f, M2Color.White, bold = true, maxWidth = 170f))
    }

    override fun onTouch(touch: M2Touch) {
        super.onTouch(touch)
        when (touch) {
            M2Touch.Tap -> hud.actions.onCycleView?.invoke()
            M2Touch.Up -> {
                GlassesHomeController.rememberAppHud(hud)
                GlassesHomeController.simulateHomePillGazeDwell()
            }
            M2Touch.LongTap -> hud.actions.onHomeRequested?.invoke()
            else -> Unit
        }
    }
}
