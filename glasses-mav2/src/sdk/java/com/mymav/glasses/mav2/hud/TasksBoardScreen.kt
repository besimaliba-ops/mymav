package com.mymav.glasses.mav2.hud

import com.everysight.mav2.sdk.uikit.data.M2Touch
import com.everysight.mav2.sdk.uikit.drawables.M2RectFilled
import com.everysight.mav2.sdk.uikit.drawables.M2RectOutline
import com.everysight.mav2.sdk.uikit.drawables.ext.setDimensions
import com.everysight.mav2.sdk.uikit.screens.M2Screen
import com.everysight.mav2.sdk.utils.M2Color
import com.mymav.glasses.api.GlassesHud
import com.mymav.glasses.api.HudAccentTone
import com.mymav.glasses.api.TasksBoardMode

/**
 * Tasks list on glass — checkbox rows, ADD/DUE/DONE/BACK pills, tap or 1s gaze to activate.
 */
class TasksBoardScreen(
    private val hud: GlassesHud.TasksBoard,
) : HudScreen(tag = "mymav-tasks-board") {
    override val glassScope: String? = com.mymav.glasses.api.MymavHudPrefs.appScope("tasks")

    private val accent = HudAccentTone.Orange.toM2Color()
    private val rowCount = hud.items.size
    private val maxFocus = (rowCount + 3).coerceAtLeast(3)
    private val focused = hud.focusedIndex.coerceIn(0, maxFocus)

    private fun isPillFocus(): Boolean = focused >= rowCount

    private fun pillIndex(): Int = (focused - rowCount).coerceIn(0, 3)

    override fun build() {
        add(HudPrimitives.fullBleedBackground())
        // Geometry (rows y=68 step 52, pills y=300) is shared with gaze hit-testing: keep it.
        GlassKit.header(this, "Tasks", "tasks", when (hud.mode) {
            TasksBoardMode.AddTitle -> "New task"
            TasksBoardMode.SetDue -> "Due time (24h)"
            TasksBoardMode.List -> hud.statusLine.ifBlank { "${hud.openCount} open" }
        }.takeForHud(24))

        val visibleRows = 4
        if (hud.items.isEmpty()) {
            GlassKit.card(this, 16f, 68f, 608f, 88f)
            GlassKit.message(this, "No tasks yet", "", 84f)
        } else {
            val start = (focused.coerceAtMost(rowCount - 1) - 1).coerceAtLeast(0)
            val window = (start until (start + visibleRows)).filter { it < hud.items.size }
            window.forEachIndexed { row, index ->
                val item = hud.items[index]
                val y = 68f + row * 52f
                val selected = !isPillFocus() && index == focused
                GlassKit.card(this, 16f, y, 608f, 48f, selected, radius = 14f)
                // Checkbox drawn as shapes (the HUD font has no tick glyphs).
                add(
                    M2RectOutline(if (item.done) HudTheme.Gain else HudTheme.InkMuted).apply {
                        setDimensions(30f, y + 14f, 20f, 20f)
                        softCorners(5f)
                        setStyle(2f)
                    },
                )
                if (item.done) {
                    add(
                        M2RectFilled(HudTheme.Gain).apply {
                            setDimensions(35f, y + 19f, 10f, 10f)
                            softCorners(3f)
                        },
                    )
                }
                add(HudPrimitives.label(item.title.takeForHud(30), 64f, y + 3f, 0.85f, M2Color.White, bold = true, maxWidth = 540f))
                if (item.dueLabel.isNotBlank()) {
                    add(HudPrimitives.labelInk(item.dueLabel.takeForHud(32), 64f, y + 26f, 0.7f, HudTheme.InkMuted, maxWidth = 540f))
                }
            }
        }

        val pillY = 300f
        val pillW = 140f
        val pillH = 36f
        val pillGap = 12f
        listOf("ADD", "DUE", "DONE", "BACK").forEachIndexed { i, label ->
            val x = 16f + i * (pillW + pillGap)
            val selected = isPillFocus() && pillIndex() == i
            if (selected) {
                GlassKit.button(this, label, x, pillY, pillW, pillH, selected = true)
                add(M2RectOutline(HudTheme.FocusGlow).apply {
                    setDimensions(x - 2f, pillY - 2f, pillW + 4f, pillH + 4f)
                    softCorners(pillH / 2f + 2f)
                    setStyle(1.5f)
                })
            } else {
                GlassKit.card(this, x, pillY, pillW, pillH, radius = pillH / 2f)
                add(HudPrimitives.centeredPillLabel(label, x, pillY, pillW, pillH, 0.85f, M2Color.White, tracked = true))
            }
        }

        GlassKit.progress(this, hud.selectDwellProgress, 346f, x = 16f, w = 608f)
        add(HudPrimitives.labelInk("Tap or look 1s to select", 16f, 356f, 0.7f, HudTheme.InkMuted))
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
                val next = (focused + 1).coerceAtMost(maxFocus)
                hud.actions.onFocusChanged?.invoke(next)
            }
            M2Touch.Tap -> hud.actions.onSelectFocused?.invoke()
            M2Touch.Backward -> hud.actions.onBack?.invoke()
            M2Touch.LongTap -> {
                val home = hud.actions.onHomeRequested
                if (home != null) home.invoke() else hud.actions.onBack?.invoke()
            }
            else -> Unit
        }
    }
}
