package com.mymav.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The same jewel badges the glasses draw on their home board (assets/hud/premium-app-badges.png, nav-badge.png),
 * cut out once and cached, so the phone's app list matches the glasses.
 */
object AppBadges {
    // Same order and measured cell centres as the glasses' PremiumHudIcons (a 1254 px atlas, 4 columns x 5 rows).
    private val ids = listOf(
        "notify", "spotify", "conversate", "food",
        "shop", "identify", "football", "fitness",
        "workouts", "mirror", "gallery", "tasks",
        "shopping", "recipes", "notepad", "memorysight",
        "money", "mymind", "calendar", "timer",
    )
    private val cx = intArrayOf(184, 480, 772, 1072)
    private val cy = intArrayOf(138, 376, 620, 862, 1106)
    private const val OUT = 160
    private val cache = HashMap<String, ImageBitmap>()
    private var loaded = false

    @Synchronized
    fun get(context: Context, id: String): ImageBitmap? {
        if (!loaded) load(context.applicationContext)
        return cache[id]
    }

    private fun load(context: Context) {
        loaded = true
        runCatching {
            val atlas = context.assets.open("hud/premium-app-badges.png").use { BitmapFactory.decodeStream(it) }
            if (atlas != null) {
                ids.forEachIndexed { i, id ->
                    val x = cx[i % 4]; val y = cy[i / 4]
                    val cell = Bitmap.createBitmap(atlas, (x - 122).coerceAtLeast(0), (y - 122).coerceAtLeast(0),
                        244.coerceAtMost(atlas.width - (x - 122).coerceAtLeast(0)), 244.coerceAtMost(atlas.height - (y - 122).coerceAtLeast(0)))
                    cache[id] = Bitmap.createScaledBitmap(cell, OUT, OUT, true).asImageBitmap()
                    if (cell !== atlas) cell.recycle()
                }
                atlas.recycle()
            }
        }
        runCatching {
            val nav = context.assets.open("hud/nav-badge.png").use { BitmapFactory.decodeStream(it) }
            if (nav != null) cache["nav"] = Bitmap.createScaledBitmap(nav, OUT, OUT, true).asImageBitmap()
        }
    }
}

/** The glasses badge for [id]; falls back to a tinted monogram when there is no artwork for that app. */
@Composable
fun AppBadge(id: String, glyph: String, accent: Color, size: Dp = 48.dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val img = remember(id) { AppBadges.get(context, id) }
    if (img != null) {
        Image(img, contentDescription = null, contentScale = ContentScale.Fit, modifier = modifier.size(size))
    } else {
        Box(
            modifier = modifier.size(size).clip(RoundedCornerShape(size / 3))
                .background(accent.copy(alpha = 0.16f)).border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(size / 3)),
            contentAlignment = Alignment.Center,
        ) { Text(glyph, fontSize = (size.value * 0.45f).sp, fontWeight = FontWeight.Bold, color = accent) }
    }
}
