package com.example.api

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.Log
import com.example.util.TimeEngine
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.launch
import com.example.data.Task

object DynamicCommandManager {
    private const val TAG = "DynamicCommandManager"

    val currentTimelineFlow = kotlinx.coroutines.flow.MutableStateFlow<List<TimelineEvent>>(emptyList())
    val currentStatusFlow = kotlinx.coroutines.flow.MutableStateFlow<String>("IDLE")
    val currentTimerModeFlow = kotlinx.coroutines.flow.MutableStateFlow<String>("pomodoro")

    @SuppressLint("StaticFieldLeak")
    @Volatile
    private var applicationContext: Context? = null

    @Volatile
    var activeEmail: String = ""

    @Volatile
    var activeSessionId: String = ""

    fun initialize(context: Context, email: String) {
        applicationContext = context.applicationContext
        activeEmail = email
        
        // Try to load activeSessionId from SharedPreferences if empty
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        activeSessionId = prefs.getString("active_session_id_rtdb", "") ?: ""

        // Try to recover the existing session timeline from SharedPreferences
        val timelineJson = prefs.getString("session_timeline_json", "[]") ?: "[]"
        try {
            val arr = org.json.JSONArray(timelineJson)
            val list = mutableListOf<TimelineEvent>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val cmd = obj.optString("command", "")
                val ts = obj.optLong("timestamp", 0L)
                if (cmd.isNotEmpty()) {
                    list.add(TimelineEvent(deviceId = Build.MODEL, event = cmd, timestamp = ts))
                }
            }
            currentTimelineFlow.value = list
            Log.d(TAG, "Loaded ${list.size} events from session_timeline_json SharedPreferences during initialization.")
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing session_timeline_json SharedPreferences in initialize", e)
        }
    }

    fun resetToIdle() {
        currentTimelineFlow.value = emptyList()
        currentStatusFlow.value = "IDLE"
        activeSessionId = ""
        applicationContext?.let { ctx ->
            ctx.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                .edit()
                .remove("session_timeline_json")
                .remove("active_session_id_rtdb")
                .apply()
        }
    }

    fun executeMidSessionCommand(
        action: String,
        currentTimeline: List<TimelineEvent>,
        timerMode: String,
        currentTask: String,
        currentTag: String
    ) {
        val context = applicationContext
        val email = activeEmail
        if (context == null || email.isBlank()) {
            Log.e(TAG, "DynamicCommandManager not initialized. Context or Email is missing.")
            return
        }

        val myDevice = Build.MODEL
        val trueTime = TimeEngine.getTrueTimeMs()

        // Append the new action: Create a new TimelineEvent with myDevice, the triggered action, and trueTime.
        val newEvent = TimelineEvent(deviceId = myDevice, event = action, timestamp = trueTime)
        val updatedTimeline = currentTimeline + newEvent

        // Generate Session_ID ONLY if action is "start", otherwise pass existing.
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        if (action.lowercase() == "start") {
            activeSessionId = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("active_session_id_rtdb", activeSessionId).apply()
        } else if (activeSessionId.isEmpty()) {
            activeSessionId = prefs.getString("active_session_id_rtdb", "") ?: ""
            if (activeSessionId.isEmpty()) {
                activeSessionId = java.util.UUID.randomUUID().toString()
                prefs.edit().putString("active_session_id_rtdb", activeSessionId).apply()
            }
        }
        val sessionId = activeSessionId

        // Status mapping:
        // "start" -> "Focusing"
        // "paused" -> "Paused"
        // "resumed" -> "Focusing"
        // "break_started" -> "Break"
        // "break_ended" / "end" / "completed" -> "IDLE"
        val statusStr = when (action.lowercase()) {
            "start", "resumed" -> "Focusing"
            "paused" -> "Paused"
            "break_started" -> "Break"
            "break_ended", "end", "completed" -> "IDLE"
            else -> "Focusing"
        }

        // Update local state flows
        this.currentTimelineFlow.value = updatedTimeline
        this.currentStatusFlow.value = statusStr
        this.currentTimerModeFlow.value = timerMode

        // Save updated timeline to SharedPreferences so it persists across process death
        try {
            val arr = org.json.JSONArray()
            for (ev in updatedTimeline) {
                val obj = org.json.JSONObject().apply {
                    put("command", ev.event)
                    put("timestamp", ev.timestamp)
                }
                arr.put(obj)
            }
            prefs.edit().putString("session_timeline_json", arr.toString()).apply()
            Log.d(TAG, "Successfully synced updatedTimeline of size ${updatedTimeline.size} to session_timeline_json SharedPreferences.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save updated timeline to prefs", e)
        }

        // Trigger Foreground Service and WakeLock
        if (statusStr == "Focusing" || statusStr == "Paused" || statusStr == "Break") {
            try {
                com.example.service.KeepAliveService.updateNotification(context)
                com.example.util.WakeLockManager.acquire(context)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update KeepAliveService notification", e)
            }
        } else if (statusStr == "IDLE") {
            try {
                com.example.service.KeepAliveService.updateNotification(context)
                com.example.util.WakeLockManager.release()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop/update KeepAliveService notification", e)
            }
        }

        try {
            val dbUrl = FirebaseConfig.getDatabaseUrl(context)
            if (dbUrl.isEmpty()) {
                Log.e(TAG, "Firebase DB URL is empty.")
                return
            }
            val database = FirebaseDatabase.getInstance(dbUrl)
            val sanitizedEmail = DevicePresenceManager.sanitizeEmail(email)

            // Database Path: FOCUS_TIMMER/USER/{sanitizedEmail}/ACTIVE_FOCUS_TIMER
            val activeRef = database.getReference("FOCUS_TIMMER")
                .child("USER")
                .child(sanitizedEmail)
                .child("ACTIVE_FOCUS_TIMER")

            val lifeOSPrefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val username = lifeOSPrefs.getString("current_username", "") ?: ""
            val cachedNickname = lifeOSPrefs.getString("user_nickname_${username}", "") ?: ""
            val cachedName = lifeOSPrefs.getString("user_name_${username}", "") ?: ""
            val cachedEmoji = lifeOSPrefs.getString("user_emoji_${username}", "👤") ?: "👤"
            val nameToUse = if (cachedNickname.isNotEmpty()) cachedNickname else if (cachedName.isNotEmpty()) cachedName else username.ifEmpty { email.substringBefore("@") }

            val payload = mapOf(
                "Command_Device_Name" to myDevice,
                "Status" to statusStr,
                "Timer_Mode" to timerMode,
                "Session_ID" to sessionId,
                "Current_Task" to currentTask,
                "Current_Tag" to currentTag,
                "Timeline" to updatedTimeline,
                "User_Display_Name" to nameToUse,
                "User_Emoji" to cachedEmoji
            )

            activeRef.updateChildren(payload).addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Log.d(TAG, "Successfully updated active focus timer payload in RTDB.")
                    // Trigger dynamic focus stats update to sync Todays_Focus_Ms in Realtime Database for peers
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        try {
                            DevicePresenceManager.updateDeviceFocusStats(context, email)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error updating device focus stats in executeMidSessionCommand", e)
                        }
                    }
                } else {
                    Log.e(TAG, "Failed to update active focus timer payload in RTDB.", task.exception)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing mid session command", e)
        }
    }

    private var activeFocusTimerRef: com.google.firebase.database.DatabaseReference? = null
    private var activeFocusTimerListener: com.google.firebase.database.ValueEventListener? = null

    fun startListeningToActiveFocusTimer(context: Context, email: String) {
        // Disabled: No longer reading the user's own focus/timer state from Firebase to prevent feedback loops.
    }

    fun forceReadActiveFocusTimerAndCalibrate(context: Context, email: String) {
        // Disabled: No longer reading/calibrating user's own state from Firebase.
    }

    fun stopListeningToActiveFocusTimer() {
        activeFocusTimerRef?.let { ref ->
            activeFocusTimerListener?.let { listener ->
                ref.removeEventListener(listener)
            }
        }
        activeFocusTimerRef = null
        activeFocusTimerListener = null
    }

    fun calculateFocusMsFromTimeline(timeline: List<TimelineEvent>): Long {
        if (timeline.isEmpty()) return 0L
        
        var accumulatedMs = 0L
        var lastResumeTs = 0L
        var isRunning = false

        for (event in timeline) {
            val action = event.event.lowercase().trim()
            val ts = event.timestamp
            
            if (action == "start" || action == "resume" || action == "resumed" || action == "break_ended" || action == "break end" || action == "break_end") {
                lastResumeTs = ts
                isRunning = true
            } else if (action == "pause" || action == "paused" || action == "break_started" || action == "break start" || action == "end" || action == "completed" || action == "session_end") {
                if (isRunning) {
                    accumulatedMs += (ts - lastResumeTs)
                    isRunning = false
                }
            }
        }
        
        if (isRunning) {
            val trueTime = TimeEngine.getTrueTimeMs()
            accumulatedMs += (trueTime - lastResumeTs)
        }
        
        return accumulatedMs
    }

    fun calibrateLocalState(
        context: Context,
        statusStr: String,
        timerMode: String,
        currentTask: String,
        currentTag: String,
        timeline: List<TimelineEvent>
    ) {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val isLocalCommander = prefs.getBoolean("is_command_device", true)
        val isLocalTimerRunning = com.example.util.FocusTimerManager.isTimerRunning.value
        val isLocalStopwatchActive = com.example.util.FocusTimerManager.isStopwatchActive.value

        if (isLocalCommander && (isLocalTimerRunning || isLocalStopwatchActive)) {
            Log.d(TAG, "calibrateLocalState: Local device is the commander and the timer is actively running. Skipping calibration to prevent feedback jitter.")
            return
        }

        // Set passive calibration in progress to true, so local actions don't claim command device
        com.example.util.FocusTimerManager.isPassiveCalibrationInProgress = true

        try {
            val totalFocusMs = calculateFocusMsFromTimeline(timeline)
            val focusMinsSetting = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                .getInt("pomodoro_focus_duration_mins", 25)
            
            // Log for clarity
            Log.d(TAG, "Calibrating local state: statusStr='$statusStr', timerMode='$timerMode', totalFocusMs=$totalFocusMs")

            // Sync attachments
            if (currentTask.isNotEmpty()) {
                val db = com.example.data.AppDatabase.getInstance(context)
                // Retrieve task from local DB or create a placeholder if it doesn't exist
                val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
                scope.launch {
                    try {
                        val tasks = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            db.taskDao().getAllTasksDirect()
                        }
                        val task = tasks.firstOrNull { it.title.equals(currentTask, ignoreCase = true) }
                        if (task != null) {
                            com.example.util.FocusTimerManager.setAttachedTask(task)
                        } else {
                            com.example.util.FocusTimerManager.setAttachedTask(com.example.data.Task(title = currentTask))
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to resolve attached task", e)
                    }
                }
            } else {
                com.example.util.FocusTimerManager.setAttachedTask(null)
            }
            
            if (currentTag.isNotEmpty()) {
                com.example.util.FocusTimerManager.setAttachedTag(currentTag)
            }

            if (timerMode.lowercase() == "stopwatch") {
                if (statusStr.lowercase() != "idle") {
                    com.example.util.FocusTimerManager.setTabFocusTimerSelected(false)
                    com.example.util.FocusTimerManager.setWasStartedFromStopwatch(true)
                }

                when (statusStr.lowercase()) {
                    "focusing" -> {
                        val elapsedSeconds = (totalFocusMs / 1000).toInt()
                        if (!com.example.util.FocusTimerManager.isStopwatchActive.value) {
                            com.example.util.FocusTimerManager.setStopwatchSeconds(elapsedSeconds)
                        }
                        com.example.util.FocusTimerManager.setStopwatchActive(true)
                        com.example.util.FocusTimerManager.setFocusPhase(true)
                        if (!com.example.util.FocusTimerManager.isStopwatchActive.value) {
                            com.example.util.FocusTimerManager.startStopwatch(context, stopActiveAlarm = false, isResuming = true)
                        }
                    }
                    "paused" -> {
                        val elapsedSeconds = (totalFocusMs / 1000).toInt()
                        if (!com.example.util.FocusTimerManager.isStopwatchActive.value) {
                            com.example.util.FocusTimerManager.setStopwatchSeconds(elapsedSeconds)
                        }
                        com.example.util.FocusTimerManager.setStopwatchActive(false)
                        if (com.example.util.FocusTimerManager.isStopwatchActive.value) {
                            com.example.util.FocusTimerManager.pauseStopwatch(context)
                        }
                    }
                    "break" -> {
                        com.example.util.FocusTimerManager.setStopwatchActive(false)
                        com.example.util.FocusTimerManager.setFocusPhase(false)
                        if (com.example.util.FocusTimerManager.isStopwatchActive.value) {
                            com.example.util.FocusTimerManager.pauseStopwatch(context)
                        }
                    }
                    "idle" -> {
                        com.example.util.FocusTimerManager.resetStopwatch(context)
                    }
                }
            } else {
                if (statusStr.lowercase() != "idle") {
                    com.example.util.FocusTimerManager.setTabFocusTimerSelected(true)
                    com.example.util.FocusTimerManager.setWasStartedFromStopwatch(false)
                }

                val timerDurationSecs = focusMinsSetting * 60
                val elapsedSecs = (totalFocusMs / 1000).toInt()
                val secondsLeft = (timerDurationSecs - elapsedSecs).coerceAtLeast(0)

                when (statusStr.lowercase()) {
                    "focusing" -> {
                        if (!com.example.util.FocusTimerManager.isTimerRunning.value) {
                            com.example.util.FocusTimerManager.setTimerSecondsLeft(secondsLeft)
                        }
                        com.example.util.FocusTimerManager.setFocusPhase(true)
                        if (!com.example.util.FocusTimerManager.isTimerRunning.value) {
                            com.example.util.FocusTimerManager.startTimer(context, stopActiveAlarm = false, isResuming = true)
                        }
                    }
                    "paused" -> {
                        if (!com.example.util.FocusTimerManager.isTimerRunning.value) {
                            com.example.util.FocusTimerManager.setTimerSecondsLeft(secondsLeft)
                        }
                        if (com.example.util.FocusTimerManager.isTimerRunning.value) {
                            com.example.util.FocusTimerManager.pauseTimer(context)
                        }
                    }
                    "break" -> {
                        com.example.util.FocusTimerManager.setFocusPhase(false)
                        if (com.example.util.FocusTimerManager.isTimerRunning.value) {
                            com.example.util.FocusTimerManager.pauseTimer(context)
                        }
                    }
                    "idle" -> {
                        com.example.util.FocusTimerManager.resetTimer(context)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during local state calibration", e)
        } finally {
            com.example.util.FocusTimerManager.isPassiveCalibrationInProgress = false
        }
    }
}
