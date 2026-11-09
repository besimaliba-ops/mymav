package com.mymav.miniapps.tasks

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.mymav.glasses.api.GlassesHomeController
import com.mymav.glasses.api.GlassesHud
import com.mymav.glasses.api.GlassesSession
import com.mymav.glasses.api.MiniApp
import com.mymav.glasses.api.HomeWidgetPreviewStore
import com.mymav.glasses.api.MiniAppDescriptor
import com.mymav.glasses.api.MymavActions
import com.mymav.glasses.api.MymavTaskIntent
import com.mymav.glasses.api.TaskHudItem
import com.mymav.glasses.api.TasksBoardHudActions
import com.mymav.glasses.api.TasksBoardMode

object TasksMiniApp : MiniApp {
    override val descriptor = MiniAppDescriptor(
        id = "tasks",
        title = "Tasks",
        subtitle = "Daily & weekly to-dos with due alerts",
        route = "mini/tasks",
        requiredCapabilities = listOf("local-store", "notifications", "hud"),
    )

    /** Optional store set from phone UI; [ensureStore] fills from application context. */
    @Volatile
    var store: TaskStore? = null

    @Volatile
    private var appContext: Context? = null

    private var focusIndex: Int = 0
    private var boardMode: TasksBoardMode = TasksBoardMode.List
    private var pendingTitle: String? = null
    private var pendingDueTaskId: String? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var selectRunnable: Runnable? = null

    fun ensureStore(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (store == null) {
            store = TaskStore(app)
        }
        installAiBridge()
    }

    private fun timeOf(t: MavTask) =
        if (t.dueMinuteOfDay >= 0) String.format("%02d:%02d", t.dueMinuteOfDay / 60, t.dueMinuteOfDay % 60) else ""

    /** Home widget = today's tasks in due order; MyMav AI can add tasks. */
    private fun installAiBridge() {
        HomeWidgetPreviewStore.provide("tasks") {
            val today = storeOrNull()?.today() ?: return@provide null
            HomeWidgetPreviewStore.Preview(
                title = "Tasks",
                subtitle = "Today",
                metric = today.size.toString(),
                metricDetail = "today",
                lines = today.map { timeOf(it) + "|" + it.title },
            )
        }
        MymavActions.tasks = object : MymavActions.TaskBridge {
            override fun add(title: String, dueEpochMs: Long, hasTime: Boolean): String {
                val st = storeOrNull() ?: return "Tasks isn't ready yet."
                val minute = if (hasTime && dueEpochMs > 0L) {
                    val c = java.util.Calendar.getInstance().apply { timeInMillis = dueEpochMs }
                    c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
                } else -1
                st.upsert(
                    MavTask(
                        title = title,
                        cadence = TaskCadence.Once,
                        dueMinuteOfDay = minute,
                        dueEpochMs = dueEpochMs,
                        notifyWhenDue = hasTime,
                    ),
                )
                HomeWidgetPreviewStore.invalidate("tasks")
                val label = MymavTaskIntent.whenLabel(MymavTaskIntent.Parsed(title, dueEpochMs, hasTime))
                return "Added \"$title\"$label."
            }

            override fun todayLines(): List<String> =
                storeOrNull()?.today()?.map { timeOf(it) + "|" + it.title }.orEmpty()
        }
    }

    private fun storeOrNull(): TaskStore? {
        store?.let { return it }
        val ctx = appContext ?: return null
        return TaskStore(ctx).also { store = it }
    }

    override fun onEnter(session: GlassesSession) {
        appContext?.let { ensureStore(it) }
        GlassesHomeController.markMiniAppOpen("tasks")
        focusIndex = 0
        boardMode = TasksBoardMode.List
        pushListHud(session)
    }

    override fun onLeave(session: GlassesSession) {
        cancelSelectGaze()
    }

    fun pushListHud(session: GlassesSession) {
        pushBoard(session)
    }

    private fun pushBoard(session: GlassesSession, selectProgress: Float = 0f) {
        cancelSelectGaze()
        val st = storeOrNull()
        val items = st?.load()?.map { task ->
            TaskHudItem(task.id, task.title, task.dueLabel(), task.done)
        } ?: emptyList()
        val maxFocus = (items.size + 3).coerceAtLeast(3)
        focusIndex = focusIndex.coerceIn(0, maxFocus)
        val open = items.count { !it.done }
        val hud = GlassesHud.TasksBoard(
            items = items,
            focusedIndex = focusIndex,
            mode = boardMode,
            selectDwellProgress = selectProgress,
            openCount = open,
            statusLine = when (boardMode) {
                TasksBoardMode.AddTitle -> "Type title · Done to continue"
                TasksBoardMode.SetDue -> "0900 · 1200 · 1800 or HHMM"
                TasksBoardMode.List -> "$open open"
            },
            actions = TasksBoardHudActions(
                onFocusChanged = { idx ->
                    focusIndex = idx.coerceIn(0, maxFocus)
                    boardMode = TasksBoardMode.List
                    // Do not auto-fire — only Tap or real LOS via feedSelectGaze.
                    pushBoard(session)
                },
                onToggleDone = { id ->
                    st?.toggleDone(id)
                    boardMode = TasksBoardMode.List
                    pushBoard(session)
                },
                onAddRequested = { startAddTitle(session) },
                onSetDueRequested = { id -> startSetDue(session, id) },
                onBack = { GlassesHomeController.requestHomeFromApp() },
                onHomeRequested = { GlassesHomeController.requestHomeFromApp() },
                onGazeNormalized = { x, y ->
                    feedSelectGaze(session)
                    GlassesHomeController.feedAppHomeGaze(x, y)
                },
                onSelectFocused = { handleSelectFocused(session, items) },
            ),
        )
        session.replaceHud(hud)
    }

    private fun handleSelectFocused(session: GlassesSession, items: List<TaskHudItem>) {
        cancelSelectGaze()
        val n = items.size
        when {
            focusIndex < n -> {
                items.getOrNull(focusIndex)?.id?.let { storeOrNull()?.toggleDone(it) }
                boardMode = TasksBoardMode.List
                pushBoard(session)
            }
            focusIndex == n -> startAddTitle(session)
            focusIndex == n + 1 -> {
                val id = taskIdForPillActions(items)
                if (id != null) {
                    startSetDue(session, id)
                } else {
                    boardMode = TasksBoardMode.List
                    pushBoard(session)
                }
            }
            focusIndex == n + 2 -> {
                taskIdForPillActions(items)?.let { storeOrNull()?.toggleDone(it) }
                boardMode = TasksBoardMode.List
                pushBoard(session)
            }
            else -> GlassesHomeController.requestHomeFromApp()
        }
    }

    private fun taskIdForPillActions(items: List<TaskHudItem>): String? {
        if (items.isEmpty()) return null
        val row = if (focusIndex < items.size) focusIndex else 0
        return items.getOrNull(row)?.id
    }

    private fun startAddTitle(session: GlassesSession) {
        cancelSelectGaze()
        boardMode = TasksBoardMode.AddTitle
        pendingTitle = null
        pendingDueTaskId = null
        GlassesHomeController.openKeyboard(
            title = "New task",
            searchSubmitsBuffer = false,
            onDone = { title ->
                val trimmed = title.trim()
                if (trimmed.isBlank()) {
                    boardMode = TasksBoardMode.List
                    pushBoard(session)
                    return@openKeyboard
                }
                pendingTitle = trimmed
                boardMode = TasksBoardMode.SetDue
                pushBoard(session)
                openDueKeyboard(session, forNewTask = true)
            },
            onCancel = {
                boardMode = TasksBoardMode.List
                pendingTitle = null
                GlassesHomeController.markMiniAppOpen("tasks")
                pushBoard(session)
            },
        )
    }

    private fun startSetDue(session: GlassesSession, taskId: String) {
        cancelSelectGaze()
        pendingDueTaskId = taskId
        boardMode = TasksBoardMode.SetDue
        pushBoard(session)
        openDueKeyboard(session, forNewTask = false)
    }

    private fun openDueKeyboard(session: GlassesSession, forNewTask: Boolean) {
        GlassesHomeController.openKeyboard(
            title = "Due HHMM (24h)",
            initial = "0900",
            searchSubmitsBuffer = false,
            onDone = { raw ->
                val minute = parseDueMinuteOfDay(raw)
                val st = storeOrNull()
                if (st == null || minute == null) {
                    boardMode = TasksBoardMode.List
                    pendingTitle = null
                    pendingDueTaskId = null
                    GlassesHomeController.markMiniAppOpen("tasks")
                    pushBoard(session)
                    return@openKeyboard
                }
                if (forNewTask) {
                    val title = pendingTitle?.trim().orEmpty()
                    if (title.isNotBlank()) {
                        st.upsert(
                            MavTask(
                                title = title,
                                cadence = TaskCadence.Once,
                                dueMinuteOfDay = minute,
                                notifyWhenDue = true,
                            ),
                        )
                    }
                } else {
                    val id = pendingDueTaskId
                    if (id != null) {
                        val existing = st.load().firstOrNull { it.id == id }
                        if (existing != null) {
                            st.upsert(existing.copy(dueMinuteOfDay = minute, cadence = TaskCadence.Once))
                        }
                    }
                }
                pendingTitle = null
                pendingDueTaskId = null
                boardMode = TasksBoardMode.List
                GlassesHomeController.markMiniAppOpen("tasks")
                pushBoard(session)
            },
            onCancel = {
                pendingTitle = null
                pendingDueTaskId = null
                boardMode = TasksBoardMode.List
                GlassesHomeController.markMiniAppOpen("tasks")
                pushBoard(session)
            },
        )
    }

    private fun parseDueMinuteOfDay(raw: String): Int? {
        val digits = raw.filter { it.isDigit() }
        if (digits.length < 3) return null
        val padded = digits.take(4).padStart(4, '0')
        val h = padded.substring(0, 2).toIntOrNull() ?: return null
        val m = padded.substring(2, 4).toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    /** Arm 1s select only while LOS is actively feeding gaze (not on focus alone). */
    private fun feedSelectGaze(session: GlassesSession) {
        if (selectRunnable != null) return
        val fire = Runnable {
            handleSelectFocused(
                session,
                storeOrNull()?.load()?.map {
                    TaskHudItem(it.id, it.title, it.dueLabel(), it.done)
                } ?: emptyList(),
            )
            selectRunnable = null
        }
        selectRunnable = fire
        mainHandler.postDelayed(fire, 1_000L)
    }

    private fun cancelSelectGaze() {
        selectRunnable?.let { mainHandler.removeCallbacks(it) }
        selectRunnable = null
    }
}
