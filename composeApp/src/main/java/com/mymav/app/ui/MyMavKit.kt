package com.mymav.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val CardShape = RoundedCornerShape(24.dp)
private val ButtonShape = RoundedCornerShape(14.dp)

/** Smoked-glass card: faint top sheen, hairline edge. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(
                Brush.verticalGradient(
                    listOf(MyMavColors.SurfaceHigh.copy(alpha = 0.92f), MyMavColors.Surface.copy(alpha = 0.92f)),
                ),
            )
            .border(1.dp, MyMavColors.Hairline, CardShape)
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
fun ScreenHeader(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.displaySmall,
            color = MyMavColors.Text,
        )
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MyMavColors.TextMuted)
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MyMavColors.TextFaint,
        modifier = modifier,
    )
}

/** Card heading. Long explanations sit behind a small "i" so cards stay short; short hints show straight away. */
@Composable
fun CardTitle(title: String, detail: String? = null) {
    val long = detail != null && detail.length > 56
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MyMavColors.Text, modifier = Modifier.weight(1f))
            if (long) {
                Text(
                    "i",
                    color = if (open) MyMavColors.OnAccent else MyMavColors.TextMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(if (open) MyMavColors.Accent else Color(0x14FFFFFF))
                        .clickable { open = !open }
                        .padding(top = 3.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        if (detail != null && (!long || open)) {
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MyMavColors.TextMuted)
        }
    }
}

enum class KitButtonKind { Primary, Tonal, Quiet }

/** Single-line, fixed-height button — labels ellipsise instead of wrapping into pills. */
@Composable
fun KitButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: KitButtonKind = KitButtonKind.Tonal,
    enabled: Boolean = true,
) {
    val colors = when (kind) {
        KitButtonKind.Primary -> ButtonDefaults.buttonColors(
            containerColor = MyMavColors.Accent,
            contentColor = MyMavColors.OnAccent,
            disabledContainerColor = MyMavColors.SurfaceHigh,
            disabledContentColor = MyMavColors.TextFaint,
        )
        KitButtonKind.Tonal -> ButtonDefaults.buttonColors(
            containerColor = Color(0x14FFFFFF),
            contentColor = MyMavColors.Text,
            disabledContainerColor = Color(0x0AFFFFFF),
            disabledContentColor = MyMavColors.TextFaint,
        )
        KitButtonKind.Quiet -> ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = MyMavColors.TextMuted,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = MyMavColors.TextFaint,
        )
    }
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 46.dp),
        shape = ButtonShape,
        colors = colors,
        border = if (kind == KitButtonKind.Tonal) BorderStroke(1.dp, MyMavColors.Hairline) else null,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        elevation = null,
    ) {
        Text(
            text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** Two equal-width buttons on one row. */
@Composable
fun KitButtonRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(9.dp)
            .clip(CircleShape)
            .background(color),
    )
}

/** Selectable capsule (segmented control item). */
@Composable
fun KitChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg by animateColorAsState(
        if (selected) MyMavColors.Accent else Color(0x12FFFFFF),
        animationSpec = tween(180),
        label = "chipBg",
    )
    val fg by animateColorAsState(
        if (selected) MyMavColors.OnAccent else MyMavColors.Text,
        animationSpec = tween(180),
        label = "chipFg",
    )
    Text(
        text,
        color = fg,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(1.dp, if (selected) Color.Transparent else MyMavColors.Hairline, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
fun KitTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        label = { Text(label) },
        placeholder = { if (placeholder != null) Text(placeholder, color = MyMavColors.TextFaint) },
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MyMavColors.Accent,
            unfocusedBorderColor = MyMavColors.HairlineStrong,
            focusedLabelColor = MyMavColors.Accent,
            unfocusedLabelColor = MyMavColors.TextMuted,
            cursorColor = MyMavColors.Accent,
            focusedTextColor = MyMavColors.Text,
            unfocusedTextColor = MyMavColors.Text,
            focusedContainerColor = Color(0x0DFFFFFF),
            unfocusedContainerColor = Color(0x0DFFFFFF),
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Small key/value line for diagnostics (kept out of the main flow). */
@Composable
fun MetaLine(key: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(key, color = MyMavColors.TextFaint, fontSize = 12.sp, modifier = Modifier.weight(0.38f))
        Text(
            value,
            color = MyMavColors.TextMuted,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.62f),
        )
    }
}
