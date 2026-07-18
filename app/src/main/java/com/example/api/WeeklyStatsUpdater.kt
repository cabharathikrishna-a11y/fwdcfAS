package com.example.api

import android.content.Context
import android.util.Log
import com.example.api.FirebaseConfig
import com.example.api.DevicePresenceManager
import com.example.util.TimeEngine
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import java.util.Calendar
import java.util.Locale

object WeeklyStatsUpdater {
    private const val TAG = "WeeklyStatsUpdater"

    fun getYearAndWeekNumber(timestampMs: Long): String {
        val cal = Calendar.getInstance(Locale.US)
        cal.timeInMillis = timestampMs
        cal.minimalDaysInFirstWeek = 4
        cal.firstDayOfWeek = Calendar.MONDAY
        val year = cal.get(Calendar.YEAR)
        val weekNo = cal.get(Calendar.WEEK_OF_YEAR)
        return "${year}_W${String.format(Locale.US, "%02d", weekNo)}"
    }

    suspend fun updateWeeklyStats(
        context: Context,
        email: String,
        focusDurationMs: Long,
        currentTag: String
    ) {
        if (email.isBlank() || focusDurationMs <= 0L) {
            Log.d(TAG, "Empty email or zero duration, skipping weekly stats update. Duration: $focusDurationMs ms")
            return
        }

        try {
            val dbUrl = FirebaseConfig.getDatabaseUrl(context)
            if (dbUrl.isEmpty()) {
                Log.e(TAG, "Database URL is empty, skipping weekly stats update.")
                return
            }

            val database = FirebaseDatabase.getInstance(dbUrl)
            val sanitizedEmail = DevicePresenceManager.sanitizeEmail(email)
            
            val trueTime = TimeEngine.getTrueTimeMs()
            val yearWeekStr = getYearAndWeekNumber(trueTime)

            // Get current display name and emoji from app_prefs
            val appPrefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val currentUsername = appPrefs.getString("current_username", "Guest") ?: "Guest"
            val cachedNickname = appPrefs.getString("user_nickname_$currentUsername", "") ?: ""
            val cachedName = appPrefs.getString("user_name_$currentUsername", "") ?: ""
            val displayName = if (cachedNickname.isNotEmpty()) cachedNickname else if (cachedName.isNotEmpty()) cachedName else currentUsername
            val cachedEmoji = appPrefs.getString("user_emoji_$currentUsername", "") ?: ""

            val weeklyRef = database.getReference("FOCUS_TIMMER")
                .child("USER")
                .child(sanitizedEmail)
                .child("WEEKLY_STATS")
                .child(yearWeekStr)

            // Sanitize key path for Firebase RTDB: periods, dollars, pounds, hash, left-bracket, and right-bracket are forbidden
            val sanitizedTag = currentTag.trim()
                .ifBlank { "Study" }
                .replace(".", "_")
                .replace("$", "_")
                .replace("[", "_")
                .replace("]", "_")
                .replace("#", "_")
                .replace("/", "_")

            // Query local Room database for the consistency streak index
            var activeStreak = 0
            try {
                val db = com.example.data.AppDatabase.getInstance(context)
                val allLocalRecords = db.localHistoryVaultDao().getAllHistoryDirect()
                activeStreak = AnalyticsVaultEngine.calculateDailyConsistencyStreak(context, allLocalRecords)
                Log.d(TAG, "Calculated streak for weekly stats updater: $activeStreak")
            } catch (dbEx: Exception) {
                Log.e(TAG, "Failed to calculate active streak from Room", dbEx)
            }

            val updates = mapOf(
                "Total_Focus_Ms" to ServerValue.increment(focusDurationMs),
                "Subject_Breakdown/$sanitizedTag" to ServerValue.increment(focusDurationMs),
                "Last_Updated" to trueTime,
                "DisplayName" to displayName,
                "ActiveStreak" to activeStreak,
                "CustomEmoji" to cachedEmoji
            )

            weeklyRef.updateChildren(updates)
            Log.d(TAG, "Successfully triggered atomic weekly stats update for $yearWeekStr, tag: $sanitizedTag")
            
            // Update device timings in Firebase
            DevicePresenceManager.updateDeviceFocusStats(context, email)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating weekly stats node in RTDB", e)
        }
    }
}
