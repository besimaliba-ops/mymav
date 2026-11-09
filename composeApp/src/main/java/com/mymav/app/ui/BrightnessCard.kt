package com.mymav.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
import com.mymav.glasses.mav2.Mav2Brightness
import kotlinx.coroutines.delay

/** Settings -> Glasses brightness: the real display brightness of the glasses (or simulator), plus auto brightness. */
@Composable
internal fun BrightnessCard() {
    var level by remember { mutableStateOf<Int?>(null) }   // null = glasses not connected
    var auto by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    // Follow the glasses: they may change on their own (auto brightness, the glasses' own controls) or reconnect.
    LaunchedEffect(Unit) {
        while (true) {
            if (!dragging) {
                level = Mav2Brightness.get()
                auto = Mav2Brightness.autoOn() ?: false
            }
            delay(1500)
        }
    }
    GlassCard {
        CardTitle("Glasses brightness", "How bright the glasses display is. This changes the glasses themselves, not just MyMav's panels. The lowest setting is 5% so the display never goes fully dark.")
        val v = level
        if (v == null) {
            Text("Glasses not connected, so brightness can't be read or changed.", color = MyMavColors.TextMuted, fontSize = 13.sp, modifier = Modifier.testTag("brightness_disconnected"))
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Brightness", color = MyMavColors.Text, fontSize = 15.sp)
                Text("$v%", color = MyMavColors.Accent, fontSize = 15.sp, modifier = Modifier.testTag("brightness_value"))
            }
            Slider(
                value = v.toFloat(), valueRange = 5f..100f, enabled = !auto, modifier = Modifier.testTag("brightness_slider"),
                onValueChange = { dragging = true; level = it.toInt(); Mav2Brightness.set(it.toInt()) },
                onValueChangeFinished = {
                    failed = !Mav2Brightness.set(level ?: v)
                    dragging = false
                },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(if (auto) "Auto brightness is on" else "Auto brightness is off", color = MyMavColors.Text, fontSize = 14.sp)
                Switch(checked = auto, onCheckedChange = { want ->
                    if (Mav2Brightness.setAuto(want)) { auto = want; failed = false } else failed = true
                })
            }
            if (auto) Text("Turn auto brightness off to set the level yourself.", color = MyMavColors.TextMuted, fontSize = 12.sp)
            if (failed) Text("The glasses didn't accept that change. Try again.", color = MyMavColors.TextMuted, fontSize = 12.sp)
        }
    }
}
