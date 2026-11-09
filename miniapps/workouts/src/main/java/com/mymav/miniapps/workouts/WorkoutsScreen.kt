package com.mymav.miniapps.workouts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mymav.glasses.api.GlassesHomeController
import com.mymav.glasses.api.GlassesSession
import com.mymav.glasses.api.WeightBodyPart
import com.mymav.glasses.api.WorkoutKind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Teal = Color(0xFF4DD0E1)
private val Panel = Color(0xFFE8F5F7)

@Composable
fun WorkoutsMiniScreen(session: GlassesSession, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember {
        WorkoutsMiniApp.ensureStore(context)
        WorkoutsMiniApp.store!!
    }
    var selected by remember { mutableStateOf(WorkoutKind.Running) }
    var note by remember { mutableStateOf("Pick a mode · Start pushes the glasses HUD.") }
    var days by remember { mutableStateOf(store.loadDays()) }
    var selectedDayId by remember {
        mutableStateOf(store.selectedDayId() ?: days.firstOrNull()?.id.orEmpty())
    }
    var history by remember { mutableStateOf(store.loadHistory()) }
    var newDayName by remember { mutableStateOf("") }
    var historyTab by remember { mutableStateOf(0) } // 0 week, 1 month

    val selectedDay = days.firstOrNull { it.id == selectedDayId } ?: days.firstOrNull()
    var plan by remember(selectedDayId, days) {
        mutableStateOf(selectedDay?.exercises ?: emptyList())
    }
    var highlightPart by remember {
        mutableStateOf(plan.firstOrNull()?.bodyPart ?: WeightBodyPart.Chest)
    }

    fun persistDays(next: List<WorkoutDayPlan>) {
        days = next
        store.saveDays(next)
    }

    fun persistPlan(next: List<WeightPlanExercise>) {
        plan = next
        val day = selectedDay ?: return
        val updated = days.map {
            if (it.id == day.id) it.copy(exercises = next) else it
        }
        persistDays(updated)
        store.setSelectedDayId(day.id)
        highlightPart = next.lastOrNull()?.bodyPart ?: highlightPart
    }

    fun selectDay(id: String) {
        selectedDayId = id
        store.setSelectedDayId(id)
        val day = days.firstOrNull { it.id == id }
        plan = day?.exercises ?: emptyList()
        highlightPart = plan.firstOrNull()?.bodyPart ?: WeightBodyPart.Chest
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Workouts", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "Run · Lift — pace HUD & weight session on glass.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF6B7280),
        )
        Text(note, style = MaterialTheme.typography.bodySmall, color = Color(0xFF6B7280))

        ModeCard(
            title = "Running",
            detail = "Stay-on-pace glasslet · AHEAD/BEHIND · pace slider",
            selected = selected == WorkoutKind.Running,
            onClick = { selected = WorkoutKind.Running },
        )
        ModeCard(
            title = "Weight training",
            detail = "Days · kg · sets/reps · body silhouette · push to glasses",
            selected = selected == WorkoutKind.WeightTraining,
            onClick = { selected = WorkoutKind.WeightTraining },
        )

        if (selected == WorkoutKind.WeightTraining) {
            Text("Training days", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                days.forEach { day ->
                    val on = day.id == selectedDayId
                    Text(
                        text = day.name,
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (on) Teal else Teal.copy(alpha = 0.12f))
                            .clickable { selectDay(day.id) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        color = if (on) Color.White else Color(0xFF0F766E),
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = newDayName,
                    onValueChange = { newDayName = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("New day name") },
                    placeholder = { Text("Push / Pull / Legs") },
                )
                Button(
                    onClick = {
                        val name = newDayName.trim().ifBlank { "Day ${days.size + 1}" }
                        val day = WorkoutDayPlan(name = name)
                        persistDays(days + day)
                        selectDay(day.id)
                        newDayName = ""
                        note = "Created day “$name”"
                    },
                ) { Text("Add day") }
            }
            if (selectedDay != null && days.size > 1) {
                TextButton(
                    onClick = {
                        val next = days.filter { it.id != selectedDay.id }
                        persistDays(next)
                        selectDay(next.first().id)
                        note = "Removed ${selectedDay.name}"
                    },
                ) { Text("Delete “${selectedDay.name}”") }
            }

            WeightSessionBuilder(
                plan = plan,
                highlightPart = highlightPart,
                onHighlight = { highlightPart = it },
                onPlanChange = { persistPlan(it) },
            )

            HistorySection(
                history = history,
                tab = historyTab,
                onTab = { historyTab = it },
            )
        }

        Button(
            onClick = {
                GlassesHomeController.markMiniAppOpen("workouts")
                when (selected) {
                    WorkoutKind.Running -> {
                        WorkoutsMiniApp.startRunning(session)
                        note = "Running pace HUD on glasses"
                    }
                    WorkoutKind.WeightTraining -> {
                        if (plan.isNotEmpty()) persistPlan(plan)
                        WorkoutsMiniApp.startWeightSession(session)
                        note = "Weight session on glasses · tap/1s for next set"
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when (selected) {
                    WorkoutKind.Running -> "Start Running on glasses"
                    WorkoutKind.WeightTraining -> "Start · push “${selectedDay?.name ?: "day"}” to glasses"
                },
            )
        }

        if (selected == WorkoutKind.WeightTraining) {
            OutlinedButton(
                onClick = {
                    if (plan.isNotEmpty()) persistPlan(plan)
                    store.logSelectedDayComplete()
                    history = store.loadHistory()
                    note = "Logged ${selectedDay?.name ?: "session"} to history"
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Log completed session (sets · reps · kg)")
            }
            OutlinedButton(
                onClick = {
                    if (plan.isNotEmpty()) persistPlan(plan)
                    GlassesHomeController.markMiniAppOpen("workouts")
                    WorkoutsMiniApp.startWeightSession(session)
                    note = "Session pushed to glasses"
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Push session to glasses")
            }
        }

        OutlinedButton(
            onClick = {
                GlassesHomeController.markMiniAppOpen("workouts")
                WorkoutsMiniApp.pushSelectHud(session)
                note = "Selection HUD on glasses"
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Show selection on glasses")
        }

        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Back to home")
        }
    }
}

@Composable
private fun HistorySection(
    history: List<WorkoutHistoryEntry>,
    tab: Int,
    onTab: (Int) -> Unit,
) {
    val filtered = if (tab == 0) history.inCurrentWeek() else history.inCurrentMonth()
    val fmt = remember { SimpleDateFormat("EEE d MMM · HH:mm", Locale.getDefault()) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Records", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HistoryChip(label = "This week", selected = tab == 0, onClick = { onTab(0) })
            HistoryChip(label = "This month", selected = tab == 1, onClick = { onTab(1) })
        }
        if (filtered.isEmpty()) {
            Text(
                "No logged sessions yet. Finish a day, then tap “Log completed session”.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF6B7280),
            )
        } else {
            filtered.take(12).forEach { entry ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Panel)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "${entry.dayName} · ${fmt.format(Date(entry.completedAtMs))}",
                        fontWeight = FontWeight.SemiBold,
                    )
                    entry.sets.groupBy { it.exerciseName }.forEach { (name, sets) ->
                        val kg = sets.firstOrNull()?.weightKg ?: 0f
                        val reps = sets.firstOrNull()?.reps ?: 0
                        Text(
                            "$name · ${sets.size}×$reps @ ${kg.toInt()} kg",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF374151),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WeightSessionBuilder(
    plan: List<WeightPlanExercise>,
    highlightPart: WeightBodyPart,
    onHighlight: (WeightBodyPart) -> Unit,
    onPlanChange: (List<WeightPlanExercise>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Session builder", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Text(
            "Add from catalog · sets · reps · weight kg",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF6B7280),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BodySilhouette(active = highlightPart, height = 200.dp)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    highlightPart.label,
                    fontWeight = FontWeight.SemiBold,
                    color = Teal,
                )
                Text(
                    if (plan.isEmpty()) "Add an exercise" else "${plan.size} in session",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF6B7280),
                )
            }
        }

        Text("Catalog", fontWeight = FontWeight.Medium)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WeightExerciseCatalog.entries.forEach { entry ->
                CatalogChip(
                    label = entry.name,
                    onClick = {
                        val next = plan + WeightPlanExercise.fromCatalog(entry)
                        onPlanChange(next)
                        onHighlight(entry.defaultBodyPart)
                    },
                )
            }
        }

        plan.forEachIndexed { index, ex ->
            ExerciseEditorCard(
                exercise = ex,
                selected = ex.bodyPart == highlightPart && plan.getOrNull(index) == ex,
                onSelect = { onHighlight(ex.bodyPart) },
                onChange = { updated ->
                    val next = plan.toMutableList().also { it[index] = updated }
                    onPlanChange(next)
                    onHighlight(updated.bodyPart)
                },
                onRemove = {
                    onPlanChange(plan.filterIndexed { i, _ -> i != index })
                },
            )
        }

        if (plan.isEmpty()) {
            Text(
                "Empty session → glasses use demo Bench press 5×5 chest.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF6B7280),
            )
        }
    }
}

@Composable
private fun CatalogChip(label: String, onClick: () -> Unit) {
    Text(
        text = "+ $label",
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Teal.copy(alpha = 0.15f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        color = Color(0xFF0F766E),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun HistoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) Teal else Teal.copy(alpha = 0.15f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        color = if (selected) Color.White else Color(0xFF0F766E),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun ExerciseEditorCard(
    exercise: WeightPlanExercise,
    selected: Boolean,
    onSelect: () -> Unit,
    onChange: (WeightPlanExercise) -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Teal.copy(alpha = 0.12f) else Panel)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) Teal else Color(0xFFD0D5DD),
                shape = RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onSelect)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(exercise.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            TextButton(onClick = onRemove) { Text("Remove") }
        }

        Text("Body part", style = MaterialTheme.typography.labelMedium, color = Color(0xFF6B7280))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            WeightBodyPart.entries.forEach { part ->
                val on = exercise.bodyPart == part
                Text(
                    text = part.label,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (on) Teal else Color.White)
                        .border(1.dp, if (on) Teal else Color(0xFFD0D5DD), RoundedCornerShape(12.dp))
                        .clickable {
                            onChange(exercise.copy(bodyPart = part))
                            onSelect()
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    color = if (on) Color.White else Color(0xFF374151),
                    fontSize = 12.sp,
                )
            }
        }

        StepperRow(
            label = "Sets",
            value = exercise.sets,
            range = 1..10,
            onChange = { onChange(exercise.copy(sets = it)) },
        )
        StepperRow(
            label = "Reps",
            value = exercise.reps,
            range = 1..30,
            onChange = { onChange(exercise.copy(reps = it)) },
        )
        StepperRow(
            label = "Weight kg",
            value = exercise.weightKg.toInt().coerceIn(0, 500),
            range = 0..500,
            step = 5,
            onChange = { onChange(exercise.copy(weightKg = it.toFloat())) },
        )
    }
}

@Composable
private fun StepperRow(
    label: String,
    value: Int,
    range: IntRange,
    step: Int = 1,
    onChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$label: $value", fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onChange((value - step).coerceIn(range.first, range.last)) },
                enabled = value > range.first,
            ) { Text("−") }
            OutlinedButton(
                onClick = { onChange((value + step).coerceIn(range.first, range.last)) },
                enabled = value < range.last,
            ) { Text("+") }
        }
    }
}

@Composable
private fun ModeCard(
    title: String,
    detail: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Teal.copy(alpha = 0.18f) else Panel)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) Teal else Color(0xFFD0D5DD),
                shape = RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = Color(0xFF6B7280))
    }
}
