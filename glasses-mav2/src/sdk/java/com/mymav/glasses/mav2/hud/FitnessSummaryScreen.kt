package com.mymav.glasses.mav2.hud

import com.everysight.mav2.sdk.utils.M2Color
import com.everysight.mav2.sdk.uikit.data.M2Touch
import com.mymav.glasses.api.FitnessHudPage
import com.mymav.glasses.api.GlassesHomeController
import com.mymav.glasses.api.GlassesHud
import java.util.Locale

/**
 * Health glasslet — Daily / Weekly / Workouts / Sleep on the shared glass design ([GlassKit]):
 * header, segmented tabs, hero value, aligned rows.
 */
class FitnessSummaryScreen(
    private val hud: GlassesHud.FitnessSummary,
) : HudScreen(tag = "mymav-health-${hud.page.name.lowercase()}") {
    override val glassScope: String? = com.mymav.glasses.api.MymavHudPrefs.appScope("fitness")

    override fun build() {
        add(HudPrimitives.fullBleedBackground())
        GlassKit.appPanel(this, accent = M2Color.Pink, glass = true)
        GlassKit.header(this, "Health", "fitness", hud.subtitle.takeForHud(24))

        val pages = FitnessHudPage.entries
        GlassKit.tabs(
            this,
            pages.map { it.name },
            pages.indexOf(hud.page).coerceAtLeast(0),
            y = 72f,
            h = 32f,
            glass = true,
        )

        val hasHero = hud.heroValue.isNotBlank()
        val heroScale = 3.0f
        var rowsTop = 132f
        if (hasHero) {
            GlassKit.hero(this, hud.heroValue, hud.heroLabel.takeForHud(16), 114f, vs = heroScale)
            rowsTop = 114f + HudText.lineHeight(heroScale, true) + 12f
        }
        // The hero already shows the headline steps count, so do not repeat it as a row.
        val shownRows = hud.rows.map { toRow(it) }.filterNot { r ->
            hasHero && r.label.equals("Steps", ignoreCase = true) &&
                r.value.filter { it.isDigit() } == hud.heroValue.filter { it.isDigit() }
        }
        GlassKit.rows(
            this,
            shownRows.take(if (hasHero) 4 else 5),
            rowsTop,
            rowH = 34f,
            x = 40f,
            w = 560f,
            valueRight = 590f,
            showUnit = false,
        )
        if (hud.rows.isEmpty() && !hasHero) {
            add(HudPrimitives.label("No health data yet", 32f, 190f, 0.8f))
            add(HudPrimitives.labelInk("Sync Health Connect on the phone", 32f, 226f, 0.58f, HudTheme.InkMuted))
        }

        HudPrimitives.drawHomePill(this, hud.homeDwellProgress)
        GlassKit.footer(
            this,
            // The phone's status text can carry a long, truncated technical suffix in brackets; show the readable head only.
            hudSafe(hud.statusLine).substringBefore('(').trim().takeIf { t -> t.any { it.isLetterOrDigit() } } ?: "Side gaze changes tab - HOME 1s to leave",
            hud.page.name.uppercase(Locale.US),
        )
    }

    /**
     * The builder supplies display strings ("Steps 577" / "today", "0.32 km" / "distance"); split them into
     * label, value and unit so the columns align across every tab.
     */
    private fun toRow(row: com.mymav.glasses.api.FitnessHudRow): GlassKit.Row {
        val primary = row.primary.trim()
        val secondary = row.secondary.trim()
        val icon = runCatching { HealthHudIcons.icon(row.icon, 26) }.getOrNull()
        val idx = primary.indexOfFirst { it.isDigit() }
        return if (idx > 0) {
            val label = primary.substring(0, idx).trim()
            val value = primary.substring(idx).trim()
            val redundant = secondary.isBlank() || label.contains(secondary, ignoreCase = true)
            GlassKit.Row(icon, label, value, if (redundant) "" else secondary)
        } else {
            val label = secondary.replaceFirstChar { it.uppercase() }.ifBlank { primary }
            GlassKit.Row(icon, label, if (secondary.isBlank()) "" else primary)
        }
    }

    override fun onTouch(touch: M2Touch) {
        super.onTouch(touch)
        when (touch) {
            M2Touch.Forward, M2Touch.Down -> cyclePage(+1)
            M2Touch.Backward -> cyclePage(-1)
            M2Touch.Up -> {
                GlassesHomeController.rememberAppHud(hud)
                GlassesHomeController.simulateHomePillGazeDwell()
            }
            M2Touch.LongTap -> hud.actions.onHomeRequested?.invoke()
            M2Touch.Tap -> cyclePage(+1)
        }
    }

    private fun cyclePage(delta: Int) {
        val pages = FitnessHudPage.entries
        val idx = pages.indexOf(hud.page).coerceAtLeast(0)
        val next = pages[(idx + delta).mod(pages.size)]
        hud.actions.onPageChanged?.invoke(next)
    }
}

