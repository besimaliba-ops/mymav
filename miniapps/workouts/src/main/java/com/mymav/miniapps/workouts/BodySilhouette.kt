package com.mymav.miniapps.workouts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mymav.glasses.api.WeightBodyPart

private val Outline = Color(0xFF6B7280)
private val TealHighlight = Color(0xFF4DD0E1)
private val DimFill = Color(0xFFD1D5DB)

/**
 * Front-facing procedural body outline; highlights [active] region in teal.
 */
@Composable
fun BodySilhouette(
    active: WeightBodyPart?,
    modifier: Modifier = Modifier,
    height: Dp = 220.dp,
) {
    Canvas(modifier = modifier.size(width = height * 0.55f, height = height)) {
        val w = size.width
        val h = size.height
        val cx = w / 2f

        fun highlighted(part: WeightBodyPart): Boolean = active == part
        fun fillFor(part: WeightBodyPart): Color =
            if (highlighted(part)) TealHighlight.copy(alpha = 0.55f) else DimFill.copy(alpha = 0.25f)

        // Head
        drawCircle(
            color = DimFill.copy(alpha = 0.2f),
            radius = w * 0.12f,
            center = Offset(cx, h * 0.10f),
        )
        drawCircle(
            color = Outline,
            radius = w * 0.12f,
            center = Offset(cx, h * 0.10f),
            style = Stroke(width = 2.5f),
        )

        // Shoulders band
        drawOval(
            color = fillFor(WeightBodyPart.Shoulders),
            topLeft = Offset(cx - w * 0.32f, h * 0.18f),
            size = Size(w * 0.64f, h * 0.08f),
        )

        // Chest
        drawOval(
            color = fillFor(WeightBodyPart.Chest),
            topLeft = Offset(cx - w * 0.22f, h * 0.24f),
            size = Size(w * 0.44f, h * 0.14f),
        )

        // Arms (left + right)
        val armColor = fillFor(WeightBodyPart.Arms)
        drawRoundRect(
            color = armColor,
            topLeft = Offset(cx - w * 0.42f, h * 0.22f),
            size = Size(w * 0.10f, h * 0.28f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.05f),
        )
        drawRoundRect(
            color = armColor,
            topLeft = Offset(cx + w * 0.32f, h * 0.22f),
            size = Size(w * 0.10f, h * 0.28f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.05f),
        )

        // Core / back (torso mid)
        val torsoFill = when (active) {
            WeightBodyPart.Back, WeightBodyPart.Core -> TealHighlight.copy(alpha = 0.45f)
            else -> DimFill.copy(alpha = 0.2f)
        }
        drawOval(
            color = torsoFill,
            topLeft = Offset(cx - w * 0.16f, h * 0.36f),
            size = Size(w * 0.32f, h * 0.16f),
        )

        // Thighs
        val thighColor = fillFor(WeightBodyPart.Thighs)
        drawRoundRect(
            color = thighColor,
            topLeft = Offset(cx - w * 0.20f, h * 0.52f),
            size = Size(w * 0.16f, h * 0.22f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.06f),
        )
        drawRoundRect(
            color = thighColor,
            topLeft = Offset(cx + w * 0.04f, h * 0.52f),
            size = Size(w * 0.16f, h * 0.22f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.06f),
        )

        // Calves
        val calfColor = fillFor(WeightBodyPart.Calves)
        drawRoundRect(
            color = calfColor,
            topLeft = Offset(cx - w * 0.18f, h * 0.74f),
            size = Size(w * 0.12f, h * 0.16f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.05f),
        )
        drawRoundRect(
            color = calfColor,
            topLeft = Offset(cx + w * 0.06f, h * 0.74f),
            size = Size(w * 0.12f, h * 0.16f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.05f),
        )

        // Outer silhouette stroke
        val outline = Path().apply {
            moveTo(cx, h * 0.02f)
            // head approx via circle already; torso outline
            moveTo(cx - w * 0.28f, h * 0.20f)
            lineTo(cx - w * 0.40f, h * 0.22f)
            lineTo(cx - w * 0.42f, h * 0.50f)
            lineTo(cx - w * 0.28f, h * 0.50f)
            lineTo(cx - w * 0.22f, h * 0.52f)
            lineTo(cx - w * 0.20f, h * 0.74f)
            lineTo(cx - w * 0.16f, h * 0.92f)
            lineTo(cx - w * 0.06f, h * 0.92f)
            lineTo(cx - w * 0.04f, h * 0.74f)
            lineTo(cx, h * 0.52f)
            lineTo(cx + w * 0.04f, h * 0.74f)
            lineTo(cx + w * 0.06f, h * 0.92f)
            lineTo(cx + w * 0.16f, h * 0.92f)
            lineTo(cx + w * 0.20f, h * 0.74f)
            lineTo(cx + w * 0.22f, h * 0.52f)
            lineTo(cx + w * 0.28f, h * 0.50f)
            lineTo(cx + w * 0.42f, h * 0.50f)
            lineTo(cx + w * 0.40f, h * 0.22f)
            lineTo(cx + w * 0.28f, h * 0.20f)
            close()
        }
        drawPath(
            path = outline,
            color = Outline,
            style = Stroke(width = 2.5f, cap = StrokeCap.Round),
        )
    }
}
