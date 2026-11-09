package com.mymav.glasses.mav2.hud

import com.everysight.mav2.sdk.utils.M2Color
import com.everysight.mav2.sdk.uikit.data.M2Touch
import com.everysight.mav2.sdk.uikit.drawables.M2RectFilled
import com.everysight.mav2.sdk.uikit.drawables.ext.setDimensions
import com.everysight.mav2.sdk.uikit.screens.M2Screen
import com.mymav.glasses.api.GlassesHomeController
import com.mymav.glasses.api.GlassesHud
import com.mymav.glasses.api.HudAccentTone

/**
 * Weight training glasslet — current exercise, body part, sets as □/✓, reps.
 * Soft glass panels; Tap or 1s gaze checks off the next incomplete set.
 */
class WeightSessionScreen(
    private val hud: GlassesHud.WeightSession,
) : HudScreen(tag = "mymav-weight-session") {
    override val glassScope: String? = com.mymav.glasses.api.MymavHudPrefs.appScope("workouts")

    private val accent = HudAccentTone.Teal.toM2Color()
    private val maxFocus = (hud.exercises.size - 1).coerceAtLeast(0)
    private val focused = hud.focusedIndex.coerceIn(0, maxFocus)
    private val current = hud.exercises.getOrNull(focused)

    override fun build() {
        add(HudPrimitives.fullBleedBackground())
        GlassKit.appPanel(this)
        GlassKit.header(this, "Weight", "workouts", hud.statusLine.takeForHud(30))

        if (current == null) {
            GlassKit.card(this, 32f, 76f, 576f, 230f)
            GlassKit.message(this, "No exercises", "Build a session on the phone", 150f)
        } else {
            GlassKit.card(this, 32f, 76f, 576f, 190f, selected = true)
            add(HudPrimitives.label(current.name.takeForHud(30), 52f, 88f, 1.4f, M2Color.White, bold = true, maxWidth = 530f))
            val kgPart = if (current.weightKg > 0.05f) "  ${current.weightKg.toInt()} kg" else ""
            add(HudPrimitives.labelInk(current.bodyPart.label + kgPart, 52f, 126f, 0.85f, HudTheme.InkMuted, maxWidth = 530f))
            val marks = current.setsDone.joinToString("  ") { if (it) "x" else "o" }
            add(
                HudPrimitives.label(
                    marks.ifBlank { "-" }.takeForHud(40), 52f, 160f, 1.2f,
                    if (current.setsRemaining == 0) HudTheme.Gain else M2Color.White, bold = true,
                ),
            )
            add(
                HudPrimitives.labelInk(
                    "${current.reps} reps   ${current.setsCompleted}/${current.setsTotal} sets",
                    52f, 214f, 0.85f, HudTheme.InkMuted, maxWidth = 530f,
                ),
            )
            val start = (focused - 1).coerceAtLeast(0)
            val window = (start until (start + 3)).filter { it < hud.exercises.size }
            window.forEachIndexed { row, index ->
                val ex = hud.exercises[index]
                val y = 274f + row * 24f
                val selected = index == focused
                add(
                    HudPrimitives.label(
                        (if (selected) "> " else "  ") + ex.name.takeForHud(24) + "   ${ex.setsCompleted}/${ex.setsTotal}",
                        40f, y, 0.75f, if (selected) HudTheme.FocusGlow else M2Color.White, maxWidth = 440f,
                    ),
                )
            }
        }

        GlassKit.progress(this, hud.selectDwellProgress, 346f)
        GlassKit.footer(this, "Swipe to focus - Tap for next set", "", y = 358f)
        HudPrimitives.drawHomePill(this, hud.homeDwellProgress)
    }

    override fun onTouch(touch: M2Touch) {
        super.onTouch(touch)
        when (touch) {
            M2Touch.Up, M2Touch.Backward ->
                hud.actions.onFocusChanged?.invoke((focused - 1).coerceAtLeast(0))
            M2Touch.Down, M2Touch.Forward ->
                hud.actions.onFocusChanged?.invoke((focused + 1).coerceAtMost(maxFocus))
            M2Touch.Tap -> hud.actions.onSelectFocused?.invoke()
            M2Touch.LongTap -> {
                val home = hud.actions.onHomeRequested
                if (home != null) {
                    home.invoke()
                } else {
                    GlassesHomeController.rememberAppHud(hud)
                    GlassesHomeController.simulateHomePillGazeDwell()
                }
            }
            else -> Unit
        }
    }
}
