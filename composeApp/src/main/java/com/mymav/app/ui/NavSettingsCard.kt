package com.mymav.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mymav.glasses.api.MymavNav

/** Settings > Navigation: map style (2D / 3D), 2D orientation, speed units and default travel mode. Applies to a running journey too. */
@Composable
fun NavSettingsCard() {
    var tick by remember { mutableIntStateOf(0) }
    @Suppress("UNUSED_EXPRESSION") tick
    val dim = MymavNav.dim()
    GlassCard {
        CardTitle("Map style", "Applies to the phone and the glasses, also during a journey.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KitChip("3D perspective", dim == MymavNav.Dim.D3, { MymavNav.setDim(MymavNav.Dim.D3); tick++ })
            KitChip("2D top-down", dim == MymavNav.Dim.D2, { MymavNav.setDim(MymavNav.Dim.D2); tick++ })
        }
        if (dim == MymavNav.Dim.D2) {
            Text("2D direction", color = MyMavColors.TextMuted, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KitChip("Heading-up", !MymavNav.northUp(), { MymavNav.setNorthUp(false); tick++ })
                KitChip("North-up", MymavNav.northUp(), { MymavNav.setNorthUp(true); tick++ })
            }
        }
        Text("Speed units", color = MyMavColors.TextMuted, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KitChip("km/h", !MymavNav.mph(), { if (MymavNav.mph()) MymavNav.toggleUnits(); tick++ })
            KitChip("mph", MymavNav.mph(), { if (!MymavNav.mph()) MymavNav.toggleUnits(); tick++ })
        }
        Text("Default travel mode", color = MyMavColors.TextMuted, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MymavNav.Travel.values().forEach { t -> KitChip(t.label, MymavNav.travel() == t, { MymavNav.setTravel(t); tick++ }) }
        }
        KitButton("Clear recent destinations", onClick = { MymavNav.clearRecents(); tick++ }, kind = KitButtonKind.Quiet, modifier = Modifier)
    }
}
