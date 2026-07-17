package com.example.api

import android.content.Context
import android.util.Log
import com.example.util.TimeEngine
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

data class ArenaRankModel(
    val email: String,
    val displayName: String,
    val totalFocusMs: Long,
    val activeStreak: Int,
    val xpScore: Int,
    val topSubject: String,
    val isMe: Boolean = false,
    val rank: Int = 0
)

object ArenaLeaderboardEngine {
    private const val TAG = "ArenaLeaderboardEngine"

    private val _leaderboardFlow = MutableStateFlow<List<ArenaRankModel>>(emptyList())
    val leaderboardFlow: StateFlow<List<ArenaRankModel>> = _leaderboardFlow.asStateFlow()

    private var friendsListener: ValueEventListener? = null
    private var friendsRef: com.google.firebase.database.DatabaseReference? = null
    private val activeWeeklyListeners = mutableMapOf<String, Pair<com.google.firebase.database.DatabaseReference, ValueEventListener>>()

    // Temporary storage for individual peer raw weekly stats
    private val rawWeeklyStatsMap = mutableMapOf<String, PeerWeeklyRawStats>()

    private data class PeerWeeklyRawStats(
        val email: String,
        val displayName: String,
        val totalFocusMs: Long,
        val activeStreak: Int,
        val topSubject: String
    )

    fun startListening(context: Context, myEmail: String) {
        if (myEmail.isBlank()) {
            Log.e(TAG, "Cannot start leaderboard listening: blank email")
            return
        }
        
        try {
            val dbUrl = FirebaseConfig.getDatabaseUrl(context)
            if (dbUrl.isEmpty()) {
                Log.e(TAG, "Database URL is empty, cannot load leaderboard.")
                return
            }

            val database = FirebaseDatabase.getInstance(dbUrl)
            val mySanitized = DevicePresenceManager.sanitizeEmail(myEmail)
            
            // Cleanup existing listeners if any
            stopListening()

            val currentRoomId = FocusLockerManager.uiState.value.roomId
            if (currentRoomId.isNotBlank()) {
                // LINKED TO ROOM ID: Listen to the participants of the current shared room
                val rRef = database.getReference("FOCUS_TIMMER")
                    .child("SHARED_ROOMS")
                    .child(currentRoomId)
                    .child("Participants")

                friendsRef = rRef

                val listener = object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val peerEmails = mutableListOf<String>()
                        peerEmails.add(myEmail.lowercase().trim()) // Always include myself

                        if (snapshot.exists()) {
                            for (child in snapshot.children) {
                                val sanitized = child.key ?: continue
                                val rawEmail = sanitized.replace("_dot_", ".").replace("_at_", "@").lowercase().trim()
                                if (rawEmail.isNotBlank() && rawEmail != myEmail.lowercase().trim()) {
                                    peerEmails.add(rawEmail)
                                }
                            }
                        }

                        val deduplicatedEmails = peerEmails.distinct()
                        Log.d(TAG, "Leaderboard peer set updated from Room ID $currentRoomId: $deduplicatedEmails. Syncing weekly listeners...")
                        syncWeeklyListeners(context, database, deduplicatedEmails, myEmail)
                    }

                    override fun onCancelled(error: DatabaseError) {
                        Log.e(TAG, "Room participants listener cancelled for leaderboard", error.toException())
                    }
                }

                rRef.addValueEventListener(listener)
                friendsListener = listener
            } else {
                // FALLBACK TO FRIENDS LIST
                val fRef = database.getReference("FOCUS_TIMMER")
                    .child("USER")
                    .child(mySanitized)
                    .child("FRIENDS_LIST")

                friendsRef = fRef

                val listener = object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val peerEmails = mutableListOf<String>()
                        peerEmails.add(myEmail.lowercase().trim()) // Always include myself

                        if (snapshot.exists()) {
                            for (child in snapshot.children) {
                                val key = child.key ?: continue
                                val valueStr = child.getValue(String::class.java)
                                val friendId = if (valueStr != null && valueStr.contains("@")) {
                                    valueStr.lowercase().trim()
                                } else {
                                    key.lowercase().trim()
                                }
                                if (friendId.isNotBlank() && friendId != myEmail.lowercase().trim()) {
                                    peerEmails.add(friendId)
                                }
                            }
                        }

                        val deduplicatedEmails = peerEmails.distinct()
                        Log.d(TAG, "Leaderboard peer set updated from Friends: $deduplicatedEmails. Syncing weekly listeners...")
                        syncWeeklyListeners(context, database, deduplicatedEmails, myEmail)
                    }

                    override fun onCancelled(error: DatabaseError) {
                        Log.e(TAG, "Friends list listener cancelled for leaderboard", error.toException())
                    }
                }

                fRef.addValueEventListener(listener)
                friendsListener = listener
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error starting leaderboard listening", e)
        }
    }

    fun stopListening() {
        try {
            friendsRef?.removeEventListener(friendsListener ?: return)
        } catch (e: Exception) {
            // ignore
        }
        friendsRef = null
        friendsListener = null

        // Remove all weekly listeners
        for ((ref, listener) in activeWeeklyListeners.values) {
            try {
                ref.removeEventListener(listener)
            } catch (e: Exception) {
                // ignore
            }
        }
        activeWeeklyListeners.clear()
        rawWeeklyStatsMap.clear()
        _leaderboardFlow.value = emptyList()
    }

    private fun syncWeeklyListeners(
        context: Context,
        database: FirebaseDatabase,
        peerEmails: List<String>,
        myEmail: String
    ) {
        val currentWeekStr = WeeklyStatsUpdater.getYearAndWeekNumber(TimeEngine.getTrueTimeMs())

        // 1. Remove listeners for peers no longer in our set
        val iterator = activeWeeklyListeners.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val email = entry.key
            if (!peerEmails.contains(email)) {
                val (ref, listener) = entry.value
                try {
                    ref.removeEventListener(listener)
                } catch (e: Exception) {
                    // ignore
                }
                iterator.remove()
                rawWeeklyStatsMap.remove(email)
            }
        }

        // 2. Add listeners for new peers
        for (email in peerEmails) {
            if (!activeWeeklyListeners.containsKey(email)) {
                try {
                    val sanitized = DevicePresenceManager.sanitizeEmail(email)
                    val weeklyRef = database.getReference("FOCUS_TIMMER")
                        .child("USER")
                        .child(sanitized)
                        .child("WEEKLY_STATS")
                        .child(currentWeekStr)

                    val listener = object : ValueEventListener {
                        override fun onDataChange(snapshot: DataSnapshot) {
                            if (!snapshot.exists()) {
                                // Default or placeholder stats if the node doesn't exist yet
                                val defaultName = if (email == myEmail) {
                                    val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                                    val username = prefs.getString("current_username", "") ?: ""
                                    val cachedNickname = prefs.getString("user_nickname_$username", "") ?: ""
                                    val cachedName = prefs.getString("user_name_$username", "") ?: ""
                                    val resolved = if (cachedNickname.isNotEmpty()) {
                                        cachedNickname
                                    } else if (cachedName.isNotEmpty()) {
                                        cachedName
                                    } else if (username.isNotEmpty() && username != "Guest") {
                                        username
                                    } else {
                                        email.substringBefore("@")
                                    }
                                    resolved
                                } else {
                                    email.substringBefore("@")
                                }
                                rawWeeklyStatsMap[email] = PeerWeeklyRawStats(
                                    email = email,
                                    displayName = defaultName,
                                    totalFocusMs = 0L,
                                    activeStreak = 0,
                                    topSubject = "None"
                                )
                                computeAndEmitLeaderboard(myEmail)
                                return
                            }

                            val totalFocusMs = snapshot.child("Total_Focus_Ms").getValue(Long::class.java) ?: 0L
                            val activeStreak = snapshot.child("ActiveStreak").getValue(Int::class.java) 
                                ?: snapshot.child("activeStreak").getValue(Int::class.java) ?: 0
                            val rawName = snapshot.child("DisplayName").getValue(String::class.java)
                                ?: snapshot.child("displayName").getValue(String::class.java)
                            val displayName = if (!rawName.isNullOrBlank()) rawName else email.substringBefore("@")

                            // Find top subject from Subject_Breakdown
                            var topSubjectName = "None"
                            var maxFocusMs = 0L
                            val breakdownSnapshot = snapshot.child("Subject_Breakdown")
                            if (breakdownSnapshot.exists()) {
                                for (subChild in breakdownSnapshot.children) {
                                    val subName = subChild.key ?: continue
                                    val subMs = subChild.getValue(Long::class.java) ?: 0L
                                    if (subMs > maxFocusMs) {
                                        maxFocusMs = subMs
                                        topSubjectName = subName
                                    }
                                }
                            }

                            rawWeeklyStatsMap[email] = PeerWeeklyRawStats(
                                email = email,
                                displayName = displayName,
                                totalFocusMs = totalFocusMs,
                                activeStreak = activeStreak,
                                topSubject = topSubjectName
                            )
                            computeAndEmitLeaderboard(myEmail)
                        }

                        override fun onCancelled(error: DatabaseError) {
                            Log.e(TAG, "Weekly stats listener cancelled for $email", error.toException())
                        }
                    }

                    weeklyRef.addValueEventListener(listener)
                    activeWeeklyListeners[email] = Pair(weeklyRef, listener)

                } catch (e: Exception) {
                    Log.e(TAG, "Error starting weekly stats listener for $email", e)
                }
            }
        }

        // Trigger an initial calculation in case some exist but no changes are fired
        computeAndEmitLeaderboard(myEmail)
    }

    private fun computeAndEmitLeaderboard(myEmail: String) {
        val rawList = rawWeeklyStatsMap.values
            .distinctBy { it.email.lowercase().trim() }
            .filter {
                it.displayName.lowercase() != "guest" &&
                !it.email.lowercase().contains("guest")
            }.toList()
        if (rawList.isEmpty()) {
            _leaderboardFlow.value = emptyList()
            return
        }

        // Calculate XP and create ArenaRankModel
        val rankModels = rawList.map { raw ->
            val totalFocusHours = raw.totalFocusMs / 3600000.0
            
            // XP Formula: XP = (Total Focus Hours * 100) * (1 + (0.1 * Active Streak Days))
            val xpScore = ((totalFocusHours * 100) * (1.0 + (0.1 * raw.activeStreak))).roundToInt()

            ArenaRankModel(
                email = raw.email,
                displayName = raw.displayName,
                totalFocusMs = raw.totalFocusMs,
                activeStreak = raw.activeStreak,
                xpScore = xpScore,
                topSubject = raw.topSubject,
                isMe = (raw.email == myEmail)
            )
        }

        // Sort descending by totalFocusMs, fallback to XP
        val sortedList = rankModels.sortedWith(
            compareByDescending<ArenaRankModel> { it.totalFocusMs }
                .thenByDescending { it.xpScore }
        )

        // Assign ranks (1-indexed)
        val finalRankedList = sortedList.mapIndexed { index, model ->
            model.copy(rank = index + 1)
        }

        _leaderboardFlow.value = finalRankedList
    }
}
