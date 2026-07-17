package com.example.api

import android.content.Context
import android.util.Log
import com.example.util.TimeEngine
import com.example.util.FocusTimerManager
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ParticipantInfo(
    val email: String,
    val sanitizedEmail: String,
    val displayName: String,
    val joinTimestamp: Long
)

data class FocusLockerUiModel(
    val roomId: String = "",
    val roomName: String = "",
    val hostEmail: String = "",
    val participants: List<ParticipantInfo> = emptyList(),
    val isHost: Boolean = false
)

object FocusLockerManager {
    private const val TAG = "FocusLockerManager"

    private val _uiState = MutableStateFlow(FocusLockerUiModel())
    val uiState: StateFlow<FocusLockerUiModel> = _uiState.asStateFlow()

    private var roomListener: ValueEventListener? = null
    private var roomRef: com.google.firebase.database.DatabaseReference? = null
    


    fun getFallbackDisplayName(email: String): String {
        val clean = email.substringBefore("@").replace(".", "_")
        val prefix = clean.substringBefore("_")
        return prefix.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString()
        }
    }

    fun createRoom(
        context: Context,
        myEmail: String,
        roomName: String,
        onSuccess: (String) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        if (myEmail.isBlank()) {
            onFailure(IllegalArgumentException("Email cannot be blank"))
            return
        }

        try {
            val dbUrl = FirebaseConfig.getDatabaseUrl(context)
            if (dbUrl.isEmpty()) {
                onFailure(IllegalStateException("Database URL is empty"))
                return
            }

            val database = FirebaseDatabase.getInstance(dbUrl)
            val roomId = "ROOM_${System.currentTimeMillis()}"
            val sanitizedMyEmail = DevicePresenceManager.sanitizeEmail(myEmail)
            val trueTime = TimeEngine.getTrueTimeMs()

            val roomRef = database.getReference("FOCUS_TIMMER")
                .child("SHARED_ROOMS")
                .child(roomId)

            val payload = mapOf(
                "Host_Email" to myEmail,
                "Room_Name" to roomName,
                "Participants" to mapOf(sanitizedMyEmail to trueTime)
            )

            roomRef.setValue(payload).addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Log.d(TAG, "Successfully created shared room: $roomId")
                    joinRoom(context, myEmail, roomId)
                    onSuccess(roomId)
                } else {
                    onFailure(task.exception ?: Exception("Failed to write room state"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in createRoom", e)
            onFailure(e)
        }
    }

    fun joinRoom(context: Context, myEmail: String, roomId: String) {
        if (myEmail.isBlank() || roomId.isBlank()) return

        try {
            val dbUrl = FirebaseConfig.getDatabaseUrl(context)
            if (dbUrl.isEmpty()) return

            val database = FirebaseDatabase.getInstance(dbUrl)
            val sanitizedMyEmail = DevicePresenceManager.sanitizeEmail(myEmail)
            val trueTime = TimeEngine.getTrueTimeMs()

            // Update participant list first
            database.getReference("FOCUS_TIMMER")
                .child("SHARED_ROOMS")
                .child(roomId)
                .child("Participants")
                .child(sanitizedMyEmail)
                .setValue(trueTime)

            // Start listening to the room
            listenToRoom(context, myEmail, roomId)

        } catch (e: Exception) {
            Log.e(TAG, "Error joining room", e)
        }
    }

    fun leaveRoom(context: Context, myEmail: String) {
        val currentRoomId = _uiState.value.roomId
        if (currentRoomId.isBlank() || myEmail.isBlank()) return

        try {
            val dbUrl = FirebaseConfig.getDatabaseUrl(context)
            if (dbUrl.isEmpty()) return

            val database = FirebaseDatabase.getInstance(dbUrl)
            val sanitizedMyEmail = DevicePresenceManager.sanitizeEmail(myEmail)

            // Remove participant node
            database.getReference("FOCUS_TIMMER")
                .child("SHARED_ROOMS")
                .child(currentRoomId)
                .child("Participants")
                .child(sanitizedMyEmail)
                .removeValue()

            // If the leaving user is the host, end the room entirely
            if (_uiState.value.isHost) {
                database.getReference("FOCUS_TIMMER")
                    .child("SHARED_ROOMS")
                    .child(currentRoomId)
                    .removeValue()
            }

            // Cleanup local state
            stopListening()

        } catch (e: Exception) {
            Log.e(TAG, "Error leaving room", e)
        }
    }

    private fun listenToRoom(context: Context, myEmail: String, roomId: String) {
        stopListening()

        try {
            val dbUrl = FirebaseConfig.getDatabaseUrl(context)
            if (dbUrl.isEmpty()) return

            val database = FirebaseDatabase.getInstance(dbUrl)
            val ref = database.getReference("FOCUS_TIMMER")
                .child("SHARED_ROOMS")
                .child(roomId)

            roomRef = ref

            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!snapshot.exists()) {
                        // Room deleted remotely (e.g. host deleted/left)
                        stopListening()
                        return
                    }

                    val hostEmail = snapshot.child("Host_Email").getValue(String::class.java) ?: ""
                    val roomName = snapshot.child("Room_Name").getValue(String::class.java) ?: ""

                    val participantsList = mutableListOf<ParticipantInfo>()
                    val participantsSnapshot = snapshot.child("Participants")
                    if (participantsSnapshot.exists()) {
                        for (child in participantsSnapshot.children) {
                            val sanitized = child.key ?: continue
                            val joinTs = child.getValue(Long::class.java) ?: 0L
                            
                            // Reconstruct plain email or approximate it
                            val rawEmail = sanitized.replace("_dot_", ".").replace("_at_", "@")
                            val displayName = getFallbackDisplayName(rawEmail)
                            participantsList.add(
                                ParticipantInfo(
                                    email = rawEmail,
                                    sanitizedEmail = sanitized,
                                    displayName = displayName,
                                    joinTimestamp = joinTs
                                )
                            )
                        }
                    }

                    val isHost = (myEmail == hostEmail)

                    _uiState.value = FocusLockerUiModel(
                        roomId = roomId,
                        roomName = roomName,
                        hostEmail = hostEmail,
                        participants = participantsList,
                        isHost = isHost
                    )
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Room listener cancelled", error.toException())
                }
            }

            ref.addValueEventListener(listener)
            roomListener = listener

        } catch (e: Exception) {
            Log.e(TAG, "Error starting room listener", e)
        }
    }

    fun stopListening() {
        roomListener?.let { listener ->
            roomRef?.removeEventListener(listener)
        }
        roomListener = null
        roomRef = null
        _uiState.value = FocusLockerUiModel()
    }
}
