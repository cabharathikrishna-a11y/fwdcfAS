package com.example.api

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.util.TimeEngine
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DevicePresenceManager {
    private const val TAG = "DevicePresenceManager"

    // Sanitize email by replacing all dots '.' with underscores '_'
    fun sanitizeEmail(email: String): String {
        return email.replace(".", "_")
    }

    fun getDeviceKey(): String {
        // Since this is native Android, use Build.MODEL
        return Build.MODEL
    }

    /**
     * Engine that runs on app launch/login to write presence data to RTDB.
     */
    fun registerPresence(context: Context, email: String) {
        if (email.isBlank()) return
        
        try {
            val dbUrl = FirebaseConfig.getDatabaseUrl(context)
            if (dbUrl.isEmpty()) {
                Log.e(TAG, "Firebase DB URL is empty. Cannot register presence.")
                return
            }
            val database = FirebaseDatabase.getInstance(dbUrl)
            val sanitizedEmail = sanitizeEmail(email)
            val deviceKey = getDeviceKey()
            
            // Database Path: FOCUS_TIMMER/USER/{user_gmail_com}/DEVICES_LOGGED_IN/{DeviceKey}
            val presenceRef = database.getReference("FOCUS_TIMMER")
                .child("USER")
                .child(sanitizedEmail)
                .child("DEVICES_LOGGED_IN")
                .child(deviceKey)

            val trueTime = TimeEngine.getTrueTimeMs()
            val formattedDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(trueTime))

            val payload = mapOf(
                "Login_status" to true,
                "Upload_Status" to "Completed",
                "Last_Update_Time_and_Date" to formattedDate
            )

            presenceRef.setValue(payload).addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Log.d(TAG, "Successfully registered presence payload for $deviceKey")
                    // Also update the focus timings for this device
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        try {
                            updateDeviceFocusStats(context, email)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to run updateDeviceFocusStats inside registerPresence", e)
                        }
                    }
                } else {
                    Log.e(TAG, "Failed to register presence for $deviceKey", task.exception)
                }
            }

            // Disconnection Rule: Attach .onDisconnect().setValue(false) to the Login_status node.
            presenceRef.child("Login_status").onDisconnect().setValue(false)

        } catch (e: Exception) {
            Log.e(TAG, "Error registering presence", e)
        }
    }

    /**
     * Calculates user focus metrics (today, past 7 days, past 30 days, all time) from the local database
     * and uploads them under the active device node in Firebase.
     */
    suspend fun updateDeviceFocusStats(context: Context, email: String) {
        if (email.isBlank()) return
        
        try {
            val db = com.example.data.AppDatabase.getInstance(context)
            val allHistory = db.localHistoryVaultDao().getAllHistoryDirect()
            
            val nowMs = System.currentTimeMillis()
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val todayStr = sdf.format(Date(nowMs))

            var todayFocusMs = 0L
            var past7DaysFocusMs = 0L
            var past30DaysFocusMs = 0L
            var allTimeFocusMs = 0L

            for (record in allHistory) {
                val duration = record.total_focus_ms
                allTimeFocusMs += duration
                
                if (record.date_string == todayStr) {
                    todayFocusMs += duration
                }
                
                if (record.start_time_ms >= nowMs - (7L * 24 * 60 * 60 * 1000)) {
                    past7DaysFocusMs += duration
                }
                
                if (record.start_time_ms >= nowMs - (30L * 24 * 60 * 60 * 1000)) {
                    past30DaysFocusMs += duration
                }
            }

            // Add currently active session's focus time accumulated so far
            val isTimerRunning = com.example.util.FocusTimerManager.isTimerRunning.value
            val isStopwatchActive = com.example.util.FocusTimerManager.isStopwatchActive.value
            val accumulatedMs = com.example.util.FocusTimerManager.accumulatedSessionTimeMs.value
            val isPendingReviewNull = com.example.util.FocusTimerManager.pendingFocusReview.value == null
            if ((isTimerRunning || isStopwatchActive || accumulatedMs > 0L) && isPendingReviewNull) {
                val isFocusPhase = com.example.util.FocusTimerManager.isFocusPhase.value
                val isPaused = com.example.util.FocusTimerManager.isPaused.value
                val activeSessionMs = if (isFocusPhase && !isPaused) {
                    accumulatedMs + com.example.util.FocusTimerManager.getCurrentChunkMs()
                } else {
                    accumulatedMs
                }
                
                if (activeSessionMs > 0) {
                    todayFocusMs += activeSessionMs
                    past7DaysFocusMs += activeSessionMs
                    past30DaysFocusMs += activeSessionMs
                    allTimeFocusMs += activeSessionMs
                }
            }

            val dbUrl = FirebaseConfig.getDatabaseUrl(context)
            if (dbUrl.isNotEmpty()) {
                val database = FirebaseDatabase.getInstance(dbUrl)
                val sanitizedEmail = sanitizeEmail(email)
                val deviceKey = getDeviceKey()
                
                val deviceRef = database.getReference("FOCUS_TIMMER")
                    .child("USER")
                    .child(sanitizedEmail)
                    .child("DEVICES_LOGGED_IN")
                    .child(deviceKey)

                val statsUpdates = mapOf(
                    "Todays_Focus_Ms" to todayFocusMs,
                    "Past_7_Days_Focus_Ms" to past7DaysFocusMs,
                    "Past_30_Days_Focus_Ms" to past30DaysFocusMs,
                    "All_Time_Focus_Ms" to allTimeFocusMs,
                    "Last_Stats_Updated" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(nowMs))
                )
                
                deviceRef.updateChildren(statsUpdates)
                Log.d(TAG, "Successfully updated device focus timings in Firebase")
                
                // Keep local focus records and today's stats 100% in sync
                com.example.util.FocusTimerManager.reloadFocusRecordsFromDb(context)
            }
        } catch (e: java.lang.Exception) {
            Log.e(TAG, "Error updating device focus timings in Firebase", e)
        }
    }

    /**
     * Active Evaluation Function: Create a function isUserActivelyLoggedIn(devicesMap).
     * Enforce these rules:
     * - Web devices (WEB_USER_) are deemed logged out if Last_Update_Time_and_Date is older than 48 hours.
     * - Native devices must have Login_status == true AND an update timestamp within 12 hours.
     */
    fun isUserActivelyLoggedIn(devicesMap: Map<String, Any>?): Boolean {
        if (devicesMap == null) return false
        
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val trueTime = TimeEngine.getTrueTimeMs()
        val fortyEightHoursMs = 48L * 60 * 60 * 1000
        val twelveHoursMs = 12L * 60 * 60 * 1000

        for ((deviceKey, deviceData) in devicesMap) {
            try {
                val data = deviceData as? Map<*, *> ?: continue
                
                // Get Last_Update_Time_and_Date
                val lastUpdateStr = data["Last_Update_Time_and_Date"] as? String ?: continue
                val lastUpdateDate = sdf.parse(lastUpdateStr) ?: continue
                val lastUpdateTimeMs = lastUpdateDate.time
                val ageMs = trueTime - lastUpdateTimeMs

                if (deviceKey.startsWith("WEB_USER_")) {
                    // Web devices: logged out if older than 48 hours
                    if (ageMs <= fortyEightHoursMs) {
                        return true
                    }
                } else {
                    // Native devices: Login_status == true AND within 12 hours
                    val loginStatus = data["Login_status"] as? Boolean ?: false
                    if (loginStatus && ageMs <= twelveHoursMs) {
                        return true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error evaluating presence for device $deviceKey", e)
            }
        }
        return false
    }
}
