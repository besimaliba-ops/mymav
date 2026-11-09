package com.mymav.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mymav.glasses.api.MymavNav

/** Phone side of the Navigation app: same destination search as the glasses (typed here), recent destinations and travel mode. */
@Composable
fun NavMiniScreen(onBack: () -> Unit) {
    val st by MymavNav.state.collectAsState()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MymavNav.Place>>(emptyList()) }
    var msg by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    @Suppress("UNUSED_EXPRESSION") tick
    val travel = remember(tick, st) { MymavNav.travel() }
    val recents = remember(tick, st) { MymavNav.recents() }
    fun plan(p: MymavNav.Place) {
        results = emptyList(); msg = "Finding route..."
        com.mymav.glasses.api.NavigationMiniApp.planTo(p) { msg = it }
    }
    fun search() {
        val q = query.trim(); if (q.isBlank()) return
        msg = "Searching..."
        MymavNav.location { loc, why ->
            if (loc == null) { msg = why; return@location }
            Thread {
                val r = runCatching { MymavNav.places(q, loc.latitude, loc.longitude, nearby = false) }
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    r.onFailure { msg = "Search failed: ${it.message?.take(60)}" }
                    r.onSuccess { l -> if (l.isEmpty()) msg = "Nothing found for \"$q\"" else if (l.size == 1) plan(l[0]) else { results = l.take(6); msg = "" } }
                }
            }.start()
        }
    }
    Column(Modifier.fillMaxSize().background(Color(0xFF07122B)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Navigation", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("Back", color = Color(0xFF22D3EE), modifier = Modifier.clickable { onBack() }.padding(8.dp))
        }
        if (st != null) {
            Text("A journey is open: ${st!!.destName.substringBefore(',')}", color = Color(0xFFFFC107))
            Chip("Open journey") { MymavNav.requestPhoneOpen() }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MymavNav.Travel.values().forEach { t -> Chip(t.label, t == travel) { MymavNav.setTravel(t); tick++ } }
        }
        Text("Map style", color = Color(0xFFB7C4DE))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("3D", MymavNav.dim() == MymavNav.Dim.D3) { MymavNav.setDim(MymavNav.Dim.D3); tick++ }
            Chip("2D", MymavNav.dim() == MymavNav.Dim.D2) { MymavNav.setDim(MymavNav.Dim.D2); tick++ }
            if (MymavNav.dim() == MymavNav.Dim.D2) {
                Chip("North-up", MymavNav.northUp()) { MymavNav.setNorthUp(true); tick++ }
                Chip("Heading-up", !MymavNav.northUp()) { MymavNav.setNorthUp(false); tick++ }
            }
        }
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Where to?") })
        Chip("Find route") { search() }
        if (msg.isNotBlank()) Text(msg, color = Color(0xFFB7C4DE))
        if (results.isNotEmpty()) {
            Text("Pick a destination", color = Color.White, fontWeight = FontWeight.Bold)
            results.forEach { p -> Chip("${p.name.substringBefore(',')}  ${MymavNav.fmtDistance(p.meters)}") { plan(p) } }
        }
        if (recents.isNotEmpty() && results.isEmpty()) {
            Text("Recent", color = Color.White, fontWeight = FontWeight.Bold)
            recents.forEach { p -> Chip(p.name.substringBefore(',')) { plan(p) } }
            Chip("Clear recents") { MymavNav.clearRecents(); tick++ }
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(if (selected) Color(0xFF1D6FE0) else Color(0xFF12254D))
            .border(1.dp, if (selected) Color(0xFF22D3EE) else Color(0x44FFFFFF), RoundedCornerShape(50)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
    ) { Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
}
