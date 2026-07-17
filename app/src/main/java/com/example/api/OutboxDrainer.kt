package com.example.api

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.*
import com.example.ui.FocusRecord
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Configuration for the Firebase Realtime Database.
 * Contains URL constants for future online synchronization and integration.
 */
object FirebaseConfig {
    const val DATABASE_URL = "https://lifeosca.asia-southeast1.firebasedatabase.app/"

    fun getDatabaseUrl(context: Context): String {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        var url = prefs.getString("custom_firebase_db_url", DATABASE_URL) ?: DATABASE_URL
        if (!url.endsWith("/")) {
            url += "/"
        }
        return url
    }

    fun setDatabaseUrl(context: Context, url: String) {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val oldUrl = prefs.getString("custom_firebase_db_url", DATABASE_URL) ?: DATABASE_URL
        if (oldUrl != url) {
            prefs.edit().putString("custom_firebase_db_url", url).commit()
            // Reset and re-initialize FirebaseClient with the new URL
            com.example.api.Firebase.resetFirebase(context)
        }
    }
}

object OutboxDrainer {
    private val processMutex = Mutex()

    fun getDeviceId(context: Context): String {
        return com.example.util.DeviceIdProvider.getDeviceId(context)
    }

    fun start(context: Context) {
        try {
            val constraints = androidx.work.Constraints.Builder()
                .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                .build()
            val workRequest = androidx.work.OneTimeWorkRequest.Builder(OutboxDrainerWorker::class.java)
                .setConstraints(constraints)
                .build()
            androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
                "OutboxDrainerWork",
                androidx.work.ExistingWorkPolicy.REPLACE,
                workRequest
            )
            Log.d("OutboxDrainer", "Enqueued OneTimeWorkRequest for OutboxDrainerWorker.")
        } catch (e: Exception) {
            Log.e("OutboxDrainer", "Failed to enqueue WorkManager work", e)
        }
    }

    fun startOutboxActiveSyncDaemon(context: Context) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            while (isActive) {
                delay(10000) // Poll every 10 seconds
                if (com.example.util.NetworkChecker.isOnline(context)) {
                    try {
                        val db = AppDatabase.getInstance(context)
                        val items = db.outboxQueueDao().getPendingQueueDirect()
                        if (items.isNotEmpty()) {
                            Log.d("OutboxDrainer", "Active sync daemon: Found ${items.size} pending outbox items. Draining...")
                            processQueue(context, items)
                        }
                    } catch (e: Exception) {
                        Log.e("OutboxDrainer", "Active sync daemon exception", e)
                    }
                }
            }
            Log.d("OutboxDrainer", "OutboxDrainer active sync daemon started.")
        }
    }

    suspend fun initializePresenceAndTraps(context: Context) {
        // Disabled: Presence tracking and peer updates are completely removed
    }

    suspend fun executeSafeBootSequence(context: Context) {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val username = prefs.getString("current_username", null)
        val isLoggedIn = prefs.getBoolean("is_logged_in", false)
        if (username.isNullOrBlank() || !isLoggedIn) {
            Log.d("OutboxDrainer", "Boot sequence skipped: No user currently logged in.")
            return
        }

        // Run local storage quota prune janitor on every boot
        try {
            executeQuotaPruneProtocol(context)
        } catch (e: Exception) {
            Log.e("OutboxDrainer", "Failed to run quota prune protocol on boot", e)
        }

        Log.d("OutboxDrainer", "Read-Before-Write Boot Sequence skipped (Peer tracking disabled).")
    }

    /**
     * AUTOMATED STORAGE QUOTA JANITOR
     * Deletes local history vault sessions older than 180 days to stay under the storage benchmark.
     */
    suspend fun executeQuotaPruneProtocol(context: Context) {
        try {
            val db = AppDatabase.getInstance(context)
            val cutoffTimeMs = System.currentTimeMillis() - (180L * 24 * 60 * 60 * 1000L) // 180 Days TTL
            db.openHelper.writableDatabase.execSQL("DELETE FROM local_history_vault WHERE start_time_ms < $cutoffTimeMs")
            Log.d("OutboxDrainer", "Quota Janitor successfully pruned old historical sessions older than 180 days.")
        } catch (e: Exception) {
            Log.e("OutboxDrainer", "Quota Janitor failed to prune older sessions: ", e)
        }
    }

    suspend fun processQueue(context: Context, items: List<OutboxQueue>) {
        processMutex.withLock {
            val db = AppDatabase.getInstance(context)
            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val username = prefs.getString("current_username", null)
            val isLoggedIn = prefs.getBoolean("is_logged_in", false)
            
            if (username.isNullOrBlank() || !isLoggedIn) {
                Log.d("OutboxDrainer", "Drainer paused: No user currently logged in.")
                return
            }

            var currentDelayMs = 2000L
            val maxDelayMs = 60000L // Cap at 1 minute

            val timerDao = db.outboxQueueDao()
            var anySuccess = false
            for (item in items) {
                if (item.status != "PENDING") continue
                
                if (!kotlin.coroutines.coroutineContext.isActive) {
                    break
                }

                if (!com.example.util.NetworkChecker.isOnline(context)) {
                    Log.d("OutboxDrainer", "[Outbox] Device offline. Suspending drainer until network returns.")
                    break // STOP the loop completely to save battery!
                }

                timerDao.updateStatus(item.queue_id, "PROCESSING")
                
                var success = false
                try {
                    success = uploadItem(context, username, item)
                } catch (e: Exception) {
                    Log.e("OutboxDrainer", "Error uploading queue item ${item.queue_id}", e)
                }
                
                if (success) {
                    timerDao.deleteQueueItemById(item.queue_id)
                    Log.d("OutboxDrainer", "Successfully processed and deleted queue item ${item.queue_id}")
                    currentDelayMs = 2000L // Reset delay on success
                    anySuccess = true
                } else {
                    timerDao.incrementRetryCount(item.queue_id)
                    val newRetryCount = item.retry_count + 1
                    if (newRetryCount > 5) {
                        timerDao.updateStatus(item.queue_id, "QUARANTINED")
                        Log.e("OutboxDrainer", "Queue item ${item.queue_id} failed more than 5 times. Quarantined as QUARANTINED.")
                    } else {
                        timerDao.updateStatus(item.queue_id, "PENDING")
                        Log.d("OutboxDrainer", "Queue item ${item.queue_id} failed. Status reset to PENDING.")
                    }
                    
                    Log.d("OutboxDrainer", "[Outbox] Upload failed. Backing off for ${currentDelayMs}ms")
                    delay(currentDelayMs)
                    currentDelayMs = minOf(currentDelayMs * 2, maxDelayMs) // Double the wait time
                }
            }
            if (anySuccess) {
                Log.d("OutboxDrainer", "Drained outbox successfully. Pinging profile update bypassed.")
                // Firebase.triggerProfileUpdated(context, username)
            }

            // Re-evaluate pending queue items. If empty, update upload status to COMPLETED
            val remainingPending = db.outboxQueueDao().getPendingQueueDirect()
            if (remainingPending.isEmpty()) {
                Log.d("OutboxDrainer", "All pending outbox items processed. Setting local_device_upload_status to COMPLETED.")
                prefs.edit().putString("local_device_upload_status", "COMPLETED").apply()
            }
        }
    }

    private suspend fun uploadItem(context: Context, username: String, item: OutboxQueue): Boolean {
        if (item.routing_target == "FIRESTORE_DIRECT_VAULT") {
            val payload = JSONObject(item.payload_json)
            val recordId = payload.optString("recordId")
            
            try {
                val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                val dbId = prefs.getString("custom_firestore_database_id", "(default)") ?: "(default)"
                val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance(
                    com.google.firebase.FirebaseApp.getInstance(), 
                    "main"
                )

                if (item.action_type == "DELETE_SESSION") {
                    val dateString = payload.optString("dateString")
                    val deletionMap = hashMapOf<String, Any>(
                        "recordId" to recordId,
                        "isDeleted" to true,
                        "sourceDeviceId" to getDeviceId(context),
                        "lastModifiedMs" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                        "timestamp" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                    )

                    firestore.collection("users").document(username)
                        .collection("daily_records").document(dateString)
                        .collection("sessions").document(recordId)
                        .set(deletionMap, com.google.firebase.firestore.SetOptions.merge())
                        .awaitTask()

                    firestore.collection("users").document(username)
                        .collection("focus_history").document(recordId)
                        .set(deletionMap, com.google.firebase.firestore.SetOptions.merge())
                        .awaitTask()

                    Log.d("OutboxDrainer", "Successfully saved DELETE_SESSION tombstone for $recordId to Firestore.")
                    return true
                }

                if (item.action_type == "UPDATE_SESSION") {
                    val dateString = payload.optString("dateString")
                    val subject = payload.optString("subject")
                    val taskTitle = payload.optString("taskTitle")
                    val startTimeFormatted = payload.optString("startTimeFormatted")
                    val endTimeFormatted = payload.optString("endTimeFormatted")
                    val durationMinutes = payload.optInt("durationMinutes")
                    val durationSeconds = payload.optLong("durationSeconds")
                    val notes = payload.optString("notes")
                    val lastModifiedMs = payload.optLong("lastModifiedMs", System.currentTimeMillis())

                    val sessionMap = hashMapOf(
                        "recordId" to recordId,
                        "username" to username,
                        "dateString" to dateString,
                        "sourceDeviceId" to getDeviceId(context),
                        "subject" to subject,
                        "taskTitle" to taskTitle,
                        "startTimeMs" to (durationSeconds * 1000L),
                        "endTimeMs" to (durationSeconds * 1000L),
                        "totalFocusMs" to (durationSeconds * 1000L),
                        "totalBreakMs" to 0L,
                        "pauseCount" to 0,
                        "durationFormatted" to com.example.util.TimeEngine.formatDuration(durationSeconds * 1000L),
                        "startTimeFormatted" to startTimeFormatted,
                        "endTimeFormatted" to endTimeFormatted,
                        "mode" to payload.optString("mode", "POMODORO"),
                        "notes" to notes,
                        "lastModifiedMs" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                        "isDeleted" to false,
                        "timestamp" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                    )

                    firestore.collection("users").document(username)
                        .collection("daily_records").document(dateString)
                        .collection("sessions").document(recordId)
                        .set(sessionMap, com.google.firebase.firestore.SetOptions.merge())
                        .awaitTask()

                    firestore.collection("users").document(username)
                        .collection("focus_history").document(recordId)
                        .set(sessionMap, com.google.firebase.firestore.SetOptions.merge())
                        .awaitTask()

                    Log.d("OutboxDrainer", "Successfully saved UPDATE_SESSION $recordId to Firestore.")
                    return true
                }

                val dateString = payload.optString("dateString")
                val subject = payload.optString("subject")
                val taskTitle = payload.optString("taskTitle")
                
                var totalFocusMs = payload.optLong("totalFocusMs", 0L)
                var startTimeFormatted = payload.optString("startTimeFormatted", "")
                var endTimeFormatted = payload.optString("endTimeFormatted", "")
                var durationFormatted = payload.optString("durationFormatted", "")
                var startTimeMs = payload.optLong("startTimeMs", 0L)
                var endTimeMs = payload.optLong("endTimeMs", 0L)
                var totalBreakMs = payload.optLong("totalBreakMs", 0L)
                var pauseCount = payload.optInt("pauseCount", 0)

                if (payload.has("metrics")) {
                    val metrics = payload.optJSONObject("metrics")
                    if (metrics != null) {
                        if (totalFocusMs == 0L) totalFocusMs = metrics.optLong("totalFocusMs", 0L)
                        if (startTimeFormatted.isEmpty()) startTimeFormatted = metrics.optString("startTimeFormatted", "")
                        if (endTimeFormatted.isEmpty()) endTimeFormatted = metrics.optString("endTimeFormatted", "")
                        if (durationFormatted.isEmpty()) durationFormatted = metrics.optString("durationFormatted", "")
                        if (startTimeMs == 0L) startTimeMs = metrics.optLong("startTimeMs", 0L)
                        if (endTimeMs == 0L) endTimeMs = metrics.optLong("endTimeMs", 0L)
                        if (totalBreakMs == 0L) totalBreakMs = metrics.optLong("totalBreakMs", 0L)
                        if (pauseCount == 0) pauseCount = metrics.optInt("pauseCount", 0)
                    }
                }

                val sessionMap = hashMapOf(
                    "recordId" to recordId,
                    "username" to username,
                    "dateString" to dateString,
                    "sourceDeviceId" to getDeviceId(context),
                    "subject" to (subject ?: "Study"),
                    "taskTitle" to (taskTitle ?: "General Focus"),
                    "startTimeMs" to startTimeMs,
                    "endTimeMs" to endTimeMs,
                    "totalFocusMs" to totalFocusMs,
                    "totalBreakMs" to totalBreakMs,
                    "pauseCount" to pauseCount,
                    "durationFormatted" to durationFormatted,
                    "startTimeFormatted" to startTimeFormatted,
                    "endTimeFormatted" to endTimeFormatted,
                    "mode" to payload.optString("mode", "POMODORO"),
                    "lastModifiedMs" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                    "isDeleted" to false,
                    "timestamp" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                )

                // Write directly to Cloud Firestore users/$uid/daily_records/$date/sessions/$id
                firestore.collection("users").document(username)
                    .collection("daily_records").document(dateString)
                    .collection("sessions").document(recordId)
                    .set(sessionMap, com.google.firebase.firestore.SetOptions.merge())
                    .awaitTask()
                Log.d("OutboxDrainer", "Saved session to users/$username/daily_records/$dateString/sessions/$recordId successfully in Firestore database: $dbId")

                // Also save to focus_history for simple and efficient historical pulling
                firestore.collection("users").document(username)
                    .collection("focus_history").document(recordId)
                    .set(sessionMap, com.google.firebase.firestore.SetOptions.merge())
                    .awaitTask()

                // Execute atomic FieldValue.increment() on daily stats: users/$uid/daily_records/$date
                val dailyStatsRef = firestore.collection("users").document(username)
                    .collection("daily_records").document(dateString)

                val dailyStatsUpdates = hashMapOf(
                    "total_focus_ms" to com.google.firebase.firestore.FieldValue.increment(totalFocusMs),
                    "total_break_ms" to com.google.firebase.firestore.FieldValue.increment(totalBreakMs),
                    "session_count" to com.google.firebase.firestore.FieldValue.increment(1L),
                    "last_updated" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                )

                dailyStatsRef.set(dailyStatsUpdates, com.google.firebase.firestore.SetOptions.merge()).awaitTask()
                Log.d("OutboxDrainer", "Atomic FieldValue.increment() on daily stats document completed successfully.")

                // Update Local History Vault's sync status
                try {
                    val db = AppDatabase.getInstance(context)
                    val vaultRecord = db.localHistoryVaultDao().getRecordById(recordId)
                    if (vaultRecord != null) {
                        db.localHistoryVaultDao().insertRecord(vaultRecord.copy(is_synced_to_firestore = 1))
                        Log.d("OutboxDrainer", "Local SQL history vault sync flag set to 1 (synced) for record: $recordId.")
                    }
                } catch (dbEx: Exception) {
                    Log.e("OutboxDrainer", "Failed to update sync status in local history vault", dbEx)
                }

                return true
            } catch (firestoreEx: Exception) {
                Log.e("OutboxDrainer", "Firestore DIRECT VAULT sync exception for session $recordId", firestoreEx)
                return false
            }
        }

        val api = Firebase.api
        val payload = JSONObject(item.payload_json)
        val db = AppDatabase.getInstance(context)
        val myDeviceId = getDeviceId(context)

        when (item.action_type) {
            "START", "PAUSE", "RESUME" -> {
                return true
            }
            "ARCHIVE_SESSION" -> {
                val recordId = payload.optString("recordId")
                val dateString = payload.optString("dateString")
                val subject = payload.optString("subject")
                val taskTitle = payload.optString("taskTitle")
                
                var totalFocusMs = payload.optLong("totalFocusMs", 0L)
                var startTimeFormatted = payload.optString("startTimeFormatted", "")
                var endTimeFormatted = payload.optString("endTimeFormatted", "")
                var durationFormatted = payload.optString("durationFormatted", "")
                var startTimeMs = payload.optLong("startTimeMs", 0L)
                var endTimeMs = payload.optLong("endTimeMs", 0L)
                var totalBreakMs = payload.optLong("totalBreakMs", 0L)
                var pauseCount = payload.optInt("pauseCount", 0)

                if (payload.has("metrics")) {
                    val metrics = payload.optJSONObject("metrics")
                    if (metrics != null) {
                        if (totalFocusMs == 0L) totalFocusMs = metrics.optLong("totalFocusMs", 0L)
                        if (startTimeFormatted.isEmpty()) startTimeFormatted = metrics.optString("startTimeFormatted", "")
                        if (endTimeFormatted.isEmpty()) endTimeFormatted = metrics.optString("endTimeFormatted", "")
                        if (durationFormatted.isEmpty()) durationFormatted = metrics.optString("durationFormatted", "")
                        if (startTimeMs == 0L) startTimeMs = metrics.optLong("startTimeMs", 0L)
                        if (endTimeMs == 0L) endTimeMs = metrics.optLong("endTimeMs", 0L)
                        if (totalBreakMs == 0L) totalBreakMs = metrics.optLong("totalBreakMs", 0L)
                        if (pauseCount == 0) pauseCount = metrics.optInt("pauseCount", 0)
                    }
                }
                
                val modeVal = if (payload.has("mode")) {
                    payload.optString("mode")
                } else if (payload.has("metrics") && payload.optJSONObject("metrics")?.has("mode") == true) {
                    payload.optJSONObject("metrics")?.optString("mode") ?: "POMODORO"
                } else {
                    "POMODORO"
                }

                val sessionMap = hashMapOf(
                    "recordId" to recordId,
                    "username" to username,
                    "dateString" to dateString,
                    "sourceDeviceId" to getDeviceId(context),
                    "subject" to (subject ?: "Study"),
                    "taskTitle" to (taskTitle ?: "General Focus"),
                    "startTimeMs" to startTimeMs,
                    "endTimeMs" to endTimeMs,
                    "totalFocusMs" to totalFocusMs,
                    "totalBreakMs" to totalBreakMs,
                    "pauseCount" to pauseCount,
                    "durationFormatted" to durationFormatted,
                    "startTimeFormatted" to startTimeFormatted,
                    "endTimeFormatted" to endTimeFormatted,
                    "mode" to modeVal,
                    "lastModifiedMs" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                    "timestamp" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                )

                // 1. FIRST WRITE NEW JSON WITH SESSION ID TO CLOUD FIRESTORE AND CONFIRM IT!
                try {
                    val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance(
                        com.google.firebase.FirebaseApp.getInstance(), 
                        "main"
                    )
                    
                    // A. Save focus history record in Firestore
                    firestore.collection("users").document(username)
                        .collection("focus_history").document(recordId)
                        .set(sessionMap, com.google.firebase.firestore.SetOptions.merge())
                        .awaitTask()
                        
                    Log.d("OutboxDrainer", "Confirmed Focus Record write under users/$username/focus_history/$recordId")

                    // B. Also save daily record
                    firestore.collection("users").document(username)
                        .collection("daily_records").document(dateString)
                        .collection("sessions").document(recordId)
                        .set(sessionMap, com.google.firebase.firestore.SetOptions.merge())
                        .awaitTask()

                    // C. Increment daily statistics
                    val dailyStatsRef = firestore.collection("users").document(username)
                        .collection("daily_records").document(dateString)

                    val dailyStatsUpdates = hashMapOf(
                        "total_focus_ms" to com.google.firebase.firestore.FieldValue.increment(totalFocusMs),
                        "total_break_ms" to com.google.firebase.firestore.FieldValue.increment(totalBreakMs),
                        "session_count" to com.google.firebase.firestore.FieldValue.increment(1L),
                        "last_updated" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                    )
                    dailyStatsRef.set(dailyStatsUpdates, com.google.firebase.firestore.SetOptions.merge()).awaitTask()
                    
                } catch (firestoreEx: Exception) {
                    Log.e("OutboxDrainer", "Firestore write was NOT confirmed for session $recordId, aborting RTDB wipe!", firestoreEx)
                    return false // Return false to retry this action later
                }

                // Update Local History Vault's sync status
                try {
                    val vaultRecord = db.localHistoryVaultDao().getRecordById(recordId)
                    if (vaultRecord != null) {
                        db.localHistoryVaultDao().insertRecord(vaultRecord.copy(is_synced_to_firestore = 1))
                        Log.d("OutboxDrainer", "Local SQL history vault sync flag set to 1 (synced).")
                    }
                } catch (dbEx: java.lang.Exception) {
                    Log.e("OutboxDrainer", "Failed to update sync status in local history vault", dbEx)
                }
                
                return true
            }
            "WIPE" -> {
                return true
            }
            else -> return true // Unknown event, discard to unblock queue
        }
    }

    private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
        addOnCompleteListener { task ->
            if (task.isSuccessful) {
                cont.resume(task.result)
            } else {
                cont.resumeWithException(task.exception ?: Exception("Task failed"))
            }
        }
    }
}

class OutboxDrainerWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d("OutboxDrainerWorker", "Starting WorkManager-backed outbox drain...")
        return try {
            val db = AppDatabase.getInstance(appContext)
            val items = db.outboxQueueDao().getPendingQueueDirect()
            if (items.isNotEmpty()) {
                Log.d("OutboxDrainerWorker", "Found ${items.size} pending items to drain via WorkManager.")
                OutboxDrainer.processQueue(appContext, items)
            }
            Result.success()
        } catch (e: Exception) {
            Log.e("OutboxDrainerWorker", "Failed to drain outbox via WorkManager: ${e.message}", e)
            Result.retry()
        }
    }
}
