package com.mymav.glasses.mav2.hud

import com.everysight.mav2.sdk.uikit.data.M2Touch
import com.everysight.mav2.sdk.uikit.drawables.M2RectFilled
import com.everysight.mav2.sdk.uikit.drawables.ext.setDimensions
import com.everysight.mav2.sdk.uikit.screens.M2Screen
import com.everysight.mav2.sdk.utils.M2Color
import com.mymav.glasses.api.GlassesHomeController
import com.mymav.glasses.api.GlassesHud
import com.mymav.glasses.api.HudAccentTone
import com.mymav.glasses.api.WorkoutKind

/**
 * Workouts enter — soft glass choice: Running vs Weight training.
 */
class WorkoutSelectScreen(
    private val hud: GlassesHud.WorkoutSelect,
) : HudScreen(tag = "mymav-workout-select") {
    override val glassScope: String? = com.mymav.glasses.api.MymavHudPrefs.appScope("workouts")

    private val accent = HudAccentTone.Teal.toM2Color()
    private val background = HudPrimitives.fullBleedBackground()
    private val focused = hud.focusedIndex.coerceIn(0, 1)

    override fun build() {
        add(background)
        GlassKit.appPanel(this)
        GlassKit.header(this, "Workouts", "workouts", hud.statusLine.takeForHud(30))
        drawChoice(0, "Running", "Stay on pace", "RUN", 78f)
        drawChoice(1, "Weight training", "Sets, reps and body part", "LIFT", 186f)
        HudPrimitives.drawHomePill(this, hud.homeDwellProgress)
        GlassKit.footer(this, "Swipe to choose - Tap to start", "", y = 354f)
    }

    private fun drawChoice(index: Int, title: String, detail: String, metric: String, y: Float) {
        val selected = index == focused
        GlassKit.card(this, 32f, y, 576f, 92f, selected)
        add(HudPrimitives.label(title, 52f, y + 14f, 1.3f, M2Color.White, bold = selected, maxWidth = 380f))
        add(HudPrimitives.labelInk(detail, 52f, y + 54f, 0.8f, HudTheme.InkMuted, maxWidth = 380f))
        GlassKit.button(this, metric, 464f, y + 28f, 124f)
    }

    override fun onTouch(touch: M2Touch) {
        super.onTouch(touch)
        when (touch) {
            M2Touch.Backward -> hud.actions.onFocusChanged?.invoke((focused - 1).coerceAtLeast(0))
            M2Touch.Forward, M2Touch.Down ->
                hud.actions.onFocusChanged?.invoke((focused + 1).coerceAtMost(1))
            M2Touch.Tap -> {
                val kind = if (focused == 0) WorkoutKind.Running else WorkoutKind.WeightTraining
                hud.actions.onSelect?.invoke(kind)
            }
            M2Touch.Up -> {
                // Sim / no-LOS: arm 1s HOME pill dwell.
                GlassesHomeController.rememberAppHud(hud)
                GlassesHomeController.simulateHomePillGazeDwell()
            }
            M2Touch.LongTap -> hud.actions.onHomeRequested?.invoke()
        }
    }
}
