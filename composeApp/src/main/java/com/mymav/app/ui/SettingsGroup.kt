package com.mymav.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One collapsible settings section: a heading row (title, one-line summary, chevron) that opens to show the cards inside it.
 * Only the heading is visible while closed, so the page reads as a short list of topics instead of one long scroll.
 */
@Composable
fun SettingsGroup(
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val rot = animateFloatAsState(if (expanded) 90f else 0f, label = "chevron")
    val shape = RoundedCornerShape(20.dp)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Brush.verticalGradient(listOf(MyMavColors.SurfaceHigh, MyMavColors.Surface)))
                .border(1.dp, if (expanded) MyMavColors.Accent.copy(alpha = 0.55f) else MyMavColors.Hairline, shape)
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(10.dp).clip(CircleShape).background(if (expanded) MyMavColors.Accent else MyMavColors.TextFaint),
            )
            Column(Modifier.weight(1f)) {
                Text(title, color = MyMavColors.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(summary, color = MyMavColors.TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("›", color = MyMavColors.TextMuted, fontSize = 26.sp, modifier = Modifier.rotate(rot.value))
        }
        AnimatedVisibility(expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
        }
    }
}
