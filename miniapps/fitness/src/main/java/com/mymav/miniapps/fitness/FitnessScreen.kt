package com.mymav.miniapps.fitness

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import com.mymav.glasses.api.FitnessHudPage
import com.mymav.glasses.api.GlassesHomeController
import com.mymav.glasses.api.GlassesSession
import com.mymav.glasses.api.HealthHeartSnapshot
import com.mymav.glasses.api.HealthHeartTracks
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val Teal = Color(0xFF0E7C8C)
private val StepsGreen = Color(0xFF34C759)
private val ActiveCyan = Color(0xFF32B8DC)
private val EatenCoral = Color(0xFFF2786B)

@Composable
fun FitnessMiniScreen(session: GlassesSession, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember {
        HealthStatsStore(context).also {
            HealthStatsStore.shared = it
            FitnessMiniApp.attachContext(context)
        }
    }
    val reader = remember { SamsungHealthReader(context) }
    var tab by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var uiEpoch by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    val statusLine = remember(uiEpoch) { store.statusMessage }
    val today = remember(uiEpoch) { store.today }
    val week = remember(uiEpoch) { store.week }
    val workouts = remember(uiEpoch) { store.workouts }
    val snap = remember(uiEpoch) { store.snapshot() }
    val permissionProblem = statusLine.isNotBlank() && !statusLine.startsWith("Live")
    val eatenMissing = today.anyAvailable && today.eatenKcal == null

    fun bumpUi() {
        uiEpoch++
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) { _ ->
        scope.launch {
            refreshing = true
            reader.refresh(store)
            bumpUi()
            refreshing = false
        }
    }

    LaunchedEffect(Unit) {
        FitnessMiniApp.attachContext(context)
        refreshing = true
        reader.refresh(store)
        bumpUi()
        refreshing = false
        FitnessHealthPoller.start(context, session, scope)
    }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { FitnessHealthPoller.stop() }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Health", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { showSettings = !showSettings }) {
                Text(if (showSettings) "Done" else "Settings", color = Teal)
            }
        }
        val freshness = when (snap.kind) {
            HealthHeartSnapshot.Kind.Live -> "Today · Auto-refresh on · ${store.readingLabel()}"
            HealthHeartSnapshot.Kind.Cached -> "Today · Cached ${store.readingLabel()}"
            HealthHeartSnapshot.Kind.Stale -> "Today · Stale ${store.readingLabel()}"
            HealthHeartSnapshot.Kind.None -> "Today · Auto-refresh on"
        }
        Text(
            freshness,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        if (permissionProblem) {
            NoticeCard(statusLine, MaterialTheme.colorScheme.error)
        } else if (eatenMissing) {
            NoticeCard("Calories eaten unavailable. Grant Nutrition in Settings.", Teal)
        }
        if (showSettings) {
            SettingsPane(
                store = store,
                statusLine = statusLine,
                refreshing = refreshing,
                onRequestPermissions = { permissionLauncher.launch(reader.allPermissions) },
                onGoalsChanged = { bumpUi() },
                modifier = Modifier.weight(1f),
            )
        } else {
            ScrollableTabRow(selectedTabIndex = tab, modifier = Modifier.fillMaxWidth(), edgePadding = 0.dp) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Daily") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Weekly") })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Workouts") })
                Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text("Sleep") })
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (tab) {
                    0 -> DailyPane(today, snap, store)
                    1 -> WeeklyCard(week, store)
                    2 -> WorkoutsCard(workouts)
                    3 -> SleepCard(today.sleepHours, week.sleepHours)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            GlassesHomeController.markMiniAppOpen("fitness")
                            pushGlassesHud(session, store, pageForTab(tab))
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Teal),
                    ) { Text("Show on glasses") }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                refreshing = true
                                reader.refresh(store)
                                bumpUi()
                                refreshing = false
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !refreshing,
                    ) { Text(if (refreshing) "Refreshing…" else "Refresh") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        if (store.readingAt > 0L) "Updated ${store.readingLabel()}" else "No successful update yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Permissions & data sources",
                        style = MaterialTheme.typography.bodySmall,
                        color = Teal,
                        modifier = Modifier.clickable { showSettings = true },
                    )
                }
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text("Back to home")
                }
            }
        }
    }
}

@Composable
private fun NoticeCard(text: String, tint: Color) {
    Card(
        colors = CardDefaults.cardColors(containerColor = tint.copy(alpha = 0.12f)),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    ) {
        Text(text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = tint)
    }
}

@Composable
private fun DailyPane(today: DailyStats, snap: HealthHeartSnapshot, store: HealthStatsStore) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        BoxWithConstraints(Modifier.padding(16.dp)) {
            val wide = maxWidth >= 500.dp
            if (wide) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    HeartRings(snap, Modifier.size(160.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Daily progress", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        MetricGoalRow("Steps", StepsGreen, store.formatSteps(today.steps), snap.stepGoal?.let { store.formatSteps(it) })
                        MetricGoalRow("Active burnt", ActiveCyan, store.formatKcal(today.activeKcal), snap.activeGoal?.let { store.formatKcal(it) })
                        MetricGoalRow("Calories eaten", EatenCoral, store.formatKcal(today.eatenKcal), snap.eatenGoal?.let { store.formatKcal(it) })
                    }
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HeartRings(snap, Modifier.size(168.dp))
                    Text("Daily progress", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    MetricGoalRow("Steps", StepsGreen, store.formatSteps(today.steps), snap.stepGoal?.let { store.formatSteps(it) })
                    MetricGoalRow("Active burnt", ActiveCyan, store.formatKcal(today.activeKcal), snap.activeGoal?.let { store.formatKcal(it) })
                    MetricGoalRow("Calories eaten", EatenCoral, store.formatKcal(today.eatenKcal), snap.eatenGoal?.let { store.formatKcal(it) })
                }
            }
        }
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Total burnt today", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                today.totalKcal?.let { "${it.roundToInt()} kcal" } ?: "--",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text("Active + resting energy so far — not a full-day forecast.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        MetricRow("Distance", store.formatDistance(today.distanceKm), Modifier.padding(16.dp))
    }
}

@Composable
private fun HeartRings(snap: HealthHeartSnapshot, modifier: Modifier) {
    Canvas(modifier) {
        val c = drawContext.canvas.nativeCanvas
        c.drawColor(android.graphics.Color.TRANSPARENT)
        HealthHeartTracks.draw(
            canvas = c,
            cx = size.width / 2f,
            cy = size.height / 2f,
            size = size.minDimension,
            steps = snap.stepsProgress(),
            active = snap.activeProgress(),
            eaten = snap.eatenProgress(),
            light = true,
        )
    }
}

@Composable
private fun MetricGoalRow(label: String, swatch: Color, value: String, goal: String?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.size(10.dp).clip(CircleShape).background(swatch))
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            if (goal != null) "$value / $goal" else value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun SettingsPane(
    store: HealthStatsStore,
    statusLine: String,
    refreshing: Boolean,
    onRequestPermissions: () -> Unit,
    onGoalsChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val goals = store.goals
    var stepGoal by remember { mutableStateOf(goals.steps?.toString() ?: "") }
    var activeGoal by remember { mutableStateOf(goals.activeKcal?.roundToInt()?.toString() ?: "") }
    var eatenGoal by remember { mutableStateOf(goals.eatenKcal?.roundToInt()?.toString() ?: "") }
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Permissions & data sources", style = MaterialTheme.typography.titleMedium)
        Text(
            statusLine.ifBlank { "Health Connect is the data source. Samsung Health reaches MyMav after it syncs to Health Connect." },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onRequestPermissions, colors = ButtonDefaults.buttonColors(containerColor = Teal)) {
            Text("Health Connect permissions")
        }
        Text(
            "Steps, active energy, total energy, distance, workouts and sleep need Health Connect read access. Calories eaten also need Nutrition read so MyMav meals and Samsung copies are not double-counted.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("Daily goals", style = MaterialTheme.typography.titleMedium)
        Text(
            "Leave a field empty for no target. Rings stay neutral until you set a goal. Food intake is a budget, not a score to beat.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        GoalField("Steps goal", stepGoal) { stepGoal = it.filter { ch -> ch.isDigit() } }
        GoalField("Active kcal goal", activeGoal) { activeGoal = it.filter { ch -> ch.isDigit() } }
        GoalField("Calories eaten goal", eatenGoal) { eatenGoal = it.filter { ch -> ch.isDigit() } }
        Button(
            onClick = {
                store.setGoals(
                    HealthGoals(
                        steps = stepGoal.toLongOrNull(),
                        activeKcal = activeGoal.toDoubleOrNull(),
                        eatenKcal = eatenGoal.toDoubleOrNull(),
                    ),
                )
                onGoalsChanged()
            },
            enabled = !refreshing,
            colors = ButtonDefaults.buttonColors(containerColor = Teal),
        ) { Text("Save goals") }
    }
}

@Composable
private fun GoalField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

private fun pageForTab(tab: Int): FitnessHudPage = when (tab) {
    1 -> FitnessHudPage.Weekly
    2 -> FitnessHudPage.Workouts
    3 -> FitnessHudPage.Sleep
    else -> FitnessHudPage.Daily
}

private fun pushGlassesHud(
    session: GlassesSession,
    store: HealthStatsStore,
    page: FitnessHudPage,
) {
    session.replaceHud(
        FitnessHudBuilder.build(
            store = store,
            page = page,
            onPageChanged = { next ->
                session.replaceHud(
                    FitnessHudBuilder.build(
                        store = store,
                        page = next,
                        onPageChanged = { p -> pushGlassesHud(session, store, p) },
                    ),
                )
            },
        ),
    )
}

@Composable
private fun WeeklyCard(week: WeeklyStats, store: HealthStatsStore) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Last 7 days", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            MetricRow("Steps", store.formatSteps(week.steps))
            MetricRow("Distance", store.formatDistance(week.distanceKm))
            MetricRow("Active energy", store.formatKcal(week.activeKcal))
            MetricRow("Calories eaten", store.formatKcal(week.eatenKcal))
            MetricRow("Total energy", store.formatKcal(week.totalKcal))
            MetricRow("Workouts", week.workoutsCount?.toString() ?: "--")
            week.daily.forEachIndexed { index, day ->
                Text(
                    "Day ${index + 1}: ${store.formatSteps(day.steps)} · ${store.formatDistance(day.distanceKm)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun WorkoutsCard(workouts: List<WorkoutEntry>) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Workouts", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (workouts.isEmpty()) {
                Text("No workouts in the last 7 days.")
            } else {
                workouts.forEach { w ->
                    val dist = if (w.distanceKm > 0.05) " · ${"%.2f".format(w.distanceKm)} km" else ""
                    val kcal = if (w.activeKcal > 0) " · ${w.activeKcal.toInt()} kcal" else ""
                    Text(
                        "${w.title} · ${w.durationLabel()}$dist$kcal",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun SleepCard(todayHours: Double?, weekHours: Double?) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Sleep", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            MetricRow("Last night", todayHours?.let { String.format("%.1f h", it) } ?: "--")
            MetricRow("7-day total", weekHours?.let { String.format("%.1f h", it) } ?: "--")
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}
