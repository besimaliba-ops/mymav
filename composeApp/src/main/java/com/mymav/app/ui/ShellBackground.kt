package com.mymav.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Shell backdrop — stays dark end to end (the old gradient faded to near-white). */
val MyMavShellGradient: Brush = Brush.verticalGradient(
    listOf(Color(0xFF0D141C), MyMavColors.Night, MyMavColors.Void),
)

/** Faint cyan bloom top-left, like light catching the lens. */
private val ShellBloom: Brush = Brush.radialGradient(
    colors = listOf(Color(0x2245D6F5), Color.Transparent),
    center = Offset(0f, 0f),
    radius = 1100f,
)

@Composable
fun MyMavShellBackground(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MyMavShellGradient)
            .background(ShellBloom),
        content = content,
    )
}
