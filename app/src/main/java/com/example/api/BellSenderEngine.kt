package com.example.api

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.example.util.TimeEngine
import com.google.firebase.database.FirebaseDatabase
import java.util.concurrent.ConcurrentHashMap

object BellSenderEngine {
    private const val TAG = "BellSenderEngine"
    
    // Key: friend_email, Value: local system timestamp of last bell sent
    private val lastSentTimestamps = ConcurrentHashMap<String, Long>()

    data class BellPayload(
        val senderName: String = "",
        val timestamp: Long = 0L,
        val isProcessed: Boolean = false,
        val nudgeType: String = "SALUTE"
    )

    fun sendBell(
        context: Context,
        myEmail: String,
        senderDisplayName: String,
        friendEmail: String,
        peerStatus: String,
        onSuccess: () -> Unit = {},
        onFailure: (String) -> Unit = {}
    ) {
        if (myEmail.isBlank() || friendEmail.isBlank()) {
            onFailure("Invalid emails")
            return
        }

        val now = System.currentTimeMillis()
        val lastSent = lastSentTimestamps[friendEmail] ?: 0L
        val cooldownMs = 5 * 60 * 1000L // 5 minutes
        if (now - lastSent < cooldownMs) {
            val remainingSecs = ((cooldownMs - (now - lastSent)) / 1000L)
            val remainingMins = remainingSecs / 60
            val remainingSecsPart = remainingSecs % 60
            val toastMsg = "Cooldown active: Give them time to focus! (${remainingMins}m ${remainingSecsPart}s remaining)"
            Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
            onFailure("Cooldown active")
            return
        }

        try {
            val dbUrl = FirebaseConfig.getDatabaseUrl(context)
            if (dbUrl.isEmpty()) {
                Log.e(TAG, "Database URL is empty, cannot send Bell.")
                onFailure("Database configuration missing")
                return
            }

            val database = FirebaseDatabase.getInstance(dbUrl)
            val friendSanitized = DevicePresenceManager.sanitizeEmail(friendEmail)
            val mySanitized = DevicePresenceManager.sanitizeEmail(myEmail)

            val isFocusing = peerStatus.equals("Focusing", ignoreCase = true) || peerStatus.equals("focusing", ignoreCase = true)
            val nudgeType = if (isFocusing) "SALUTE" else "WAKE_UP"

            val trueTime = TimeEngine.getTrueTimeMs()
            val payload = BellPayload(
                senderName = senderDisplayName,
                timestamp = trueTime,
                isProcessed = false,
                nudgeType = nudgeType
            )

            val bellRef = database.getReference("FOCUS_TIMMER")
                .child("USER")
                .child(friendSanitized)
                .child("BELLS")
                .child(mySanitized)

            bellRef.setValue(payload)
                .addOnSuccessListener {
                    Log.d(TAG, "Bell successfully sent to $friendEmail with nudgeType $nudgeType")
                    lastSentTimestamps[friendEmail] = now
                    onSuccess()
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to send Bell to $friendEmail", e)
                    onFailure(e.message ?: "Firebase write failed")
                }

        } catch (e: Exception) {
            Log.e(TAG, "Error sending Bell", e)
            onFailure(e.message ?: "Unknown error")
        }
    }
}
