package com.example.api

import android.content.Context
import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

object PeerLiveSphereManager {
    private const val TAG = "PeerLiveSphereManager"

    private val _peerLiveStates = MutableStateFlow<Map<String, PeerLiveState>>(emptyMap())
    val peerLiveStates: StateFlow<Map<String, PeerLiveState>> = _peerLiveStates.asStateFlow()

    private val activeListeners = ConcurrentHashMap<String, Pair<DatabaseReference, ValueEventListener>>()
    private var friendsListRef: DatabaseReference? = null
    private var friendsListListener: ValueEventListener? = null

    private var sharedRoomsRef: DatabaseReference? = null
    private var sharedRoomsListener: ValueEventListener? = null

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob())
    private var roomCollectJob: kotlinx.coroutines.Job? = null

    private var regularFriends = emptyList<String>()
    private var activeRoomFriends = emptyList<String>()

    private fun updateActiveSphereListenersCombined(
        context: Context,
        database: FirebaseDatabase,
        myEmail: String
    ) {
        // Combined set of all unique friends: regular friends + all participants of joined rooms
        val uniquePeers = mutableSetOf<String>()
        regularFriends.forEach { uniquePeers.add(it.lowercase().trim()) }
        activeRoomFriends.forEach { uniquePeers.add(it.lowercase().trim()) }

        // Make sure the current user is added so "my self" can also be observed via PeerLiveSphere if appropriate,
        // and keeping in sync with user request: "i should see my self and also my friends too from all the rooms i joined"
        if (myEmail.isNotBlank()) {
            uniquePeers.add(myEmail.lowercase().trim())
        }

        val peerList = uniquePeers.toList()
        Log.d(TAG, "Syncing combined Live Sphere peers (friends + rooms + self): $peerList")
        syncFriendListeners(context, database, peerList)
    }

    fun startListeningToFriends(context: Context, myEmail: String) {
        if (myEmail.isBlank()) return
        cleanUpListeners()

        try {
            val dbUrl = FirebaseConfig.getDatabaseUrl(context)
            if (dbUrl.isEmpty()) {
                Log.e(TAG, "Database URL is empty, cannot listen to friends.")
                return
            }
            val database = FirebaseDatabase.getInstance(dbUrl)
            val mySanitized = DevicePresenceManager.sanitizeEmail(myEmail)

            // 1. Listen to the user's regular Friends List
            val fRef = database.getReference("FOCUS_TIMMER")
                .child("USER")
                .child(mySanitized)
                .child("FRIENDS_LIST")

            friendsListRef = fRef

            val friendsListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val newFriends = mutableListOf<String>()
                    if (snapshot.exists()) {
                        for (child in snapshot.children) {
                            val key = child.key ?: continue
                            val valueStr = child.getValue(String::class.java)
                            val friendId = if (valueStr != null && valueStr.contains("@")) {
                                valueStr.lowercase().trim()
                            } else if (key.contains("@") || key.contains("_")) {
                                key.lowercase().trim()
                            } else {
                                key.lowercase().trim()
                            }
                            if (friendId.isNotBlank()) {
                                newFriends.add(friendId)
                            }
                        }
                    }

                    regularFriends = newFriends.map { it.lowercase().trim() }.distinct()
                    Log.d(TAG, "Regular friends list updated: $regularFriends")
                    updateActiveSphereListenersCombined(context, database, myEmail)
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Friends list listener cancelled", error.toException())
                }
            }

            fRef.addValueEventListener(friendsListener)
            friendsListListener = friendsListener

            // 2. Listen to SHARED_ROOMS to dynamically find ALL rooms the user has joined, and their participants
            val rRef = database.getReference("FOCUS_TIMMER")
                .child("SHARED_ROOMS")
            sharedRoomsRef = rRef

            val roomsListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val roomFriends = mutableSetOf<String>()
                    if (snapshot.exists()) {
                        for (roomSnapshot in snapshot.children) {
                            val participantsSnapshot = roomSnapshot.child("Participants")
                            if (participantsSnapshot.exists() && participantsSnapshot.hasChild(mySanitized)) {
                                // The user is a participant of this room! Collect all other participants
                                for (pChild in participantsSnapshot.children) {
                                    val sanitizedParticipant = pChild.key ?: continue
                                    val rawParticipantEmail = sanitizedParticipant.replace("_dot_", ".").replace("_at_", "@").lowercase().trim()
                                    if (rawParticipantEmail.isNotBlank()) {
                                        roomFriends.add(rawParticipantEmail)
                                    }
                                }
                            }
                        }
                    }

                    activeRoomFriends = roomFriends.toList().map { it.lowercase().trim() }.distinct()
                    Log.d(TAG, "Dynamic room friends updated: $activeRoomFriends")
                    updateActiveSphereListenersCombined(context, database, myEmail)
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "SHARED_ROOMS listener cancelled", error.toException())
                }
            }

            rRef.addValueEventListener(roomsListener)
            sharedRoomsListener = roomsListener

            // 3. Keep collecting FocusLockerManager current room changes to force update if needed
            roomCollectJob = scope.launch {
                FocusLockerManager.uiState.collect { roomState ->
                    Log.d(TAG, "Room UI state collected in PeerLiveSphereManager: ${roomState.roomId}")
                    updateActiveSphereListenersCombined(context, database, myEmail)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error starting listening to friends and rooms", e)
        }
    }

    private fun syncFriendListeners(context: Context, database: FirebaseDatabase, friends: List<String>) {
        val iterator = activeListeners.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val friendId = entry.key
            if (!friends.contains(friendId)) {
                val (ref, listener) = entry.value
                ref.removeEventListener(listener)
                iterator.remove()
                
                val current = _peerLiveStates.value.toMutableMap()
                current.remove(friendId)
                _peerLiveStates.value = current
                Log.d(TAG, "Removed listener and state for: $friendId")
            }
        }

        for (friendId in friends) {
            if (!activeListeners.containsKey(friendId)) {
                try {
                    val friendSanitized = DevicePresenceManager.sanitizeEmail(friendId)
                    val activeRef = database.getReference("FOCUS_TIMMER")
                        .child("USER")
                        .child(friendSanitized)

                    val listener = object : ValueEventListener {
                        override fun onDataChange(snapshot: DataSnapshot) {
                            if (!snapshot.exists()) {
                                updatePeerState(friendId, null)
                                return
                            }

                            val timerSnapshot = snapshot.child("ACTIVE_FOCUS_TIMER")
                            val currentTask = timerSnapshot.child("Current_Task").getValue(String::class.java) ?: "Relaxing"
                            val currentTag = timerSnapshot.child("Current_Tag").getValue(String::class.java) ?: "Study"
                            val timerMode = timerSnapshot.child("Timer_Mode").getValue(String::class.java) ?: "pomodoro"
                            val status = timerSnapshot.child("Status").getValue(String::class.java) ?: "Relaxing"
                            val lastUpdated = timerSnapshot.child("Last_Updated").getValue(Long::class.java)
                                ?: com.example.util.TimeEngine.getTrueTimeMs()

                            val displayName = timerSnapshot.child("User_Display_Name").getValue(String::class.java)
                                ?: timerSnapshot.child("displayName").getValue(String::class.java)
                                ?: timerSnapshot.child("nickname").getValue(String::class.java)
                                ?: getFallbackDisplayName(friendId)

                            val emojiVal = timerSnapshot.child("User_Emoji").getValue(String::class.java)
                                ?: timerSnapshot.child("emoji").getValue(String::class.java)
                                ?: ""

                            val timelineList = mutableListOf<TimelineEvent>()
                            val timelineSnapshot = timerSnapshot.child("Timeline")
                            if (timelineSnapshot.exists()) {
                                for (child in timelineSnapshot.children) {
                                    val devId = child.child("deviceId").getValue(String::class.java) ?: ""
                                    val ev = child.child("event").getValue(String::class.java) ?: ""
                                    val ts = child.child("timestamp").getValue(Long::class.java) ?: 0L
                                    timelineList.add(TimelineEvent(devId, ev, ts))
                                }
                            }

                            val devicesMap = mutableMapOf<String, com.example.api.DeviceStats>()
                            val devicesSnapshot = snapshot.child("DEVICES_LOGGED_IN")
                            if (devicesSnapshot.exists()) {
                                for (deviceChild in devicesSnapshot.children) {
                                    val devKey = deviceChild.key ?: continue
                                    val isLoggedIn = deviceChild.child("Login_status").getValue(Boolean::class.java) ?: true
                                    val uploadStatus = deviceChild.child("Upload_Status").getValue(String::class.java) ?: "COMPLETED"
                                    val allTimeMs = deviceChild.child("All_Time_Focus_Ms").getValue(Long::class.java) ?: 0L
                                    val lastUpdateStr = deviceChild.child("Last_Update_Time_and_Date").getValue(String::class.java) ?: ""
                                    val past30Ms = deviceChild.child("Past_30_Days_Focus_Ms").getValue(Long::class.java) ?: 0L
                                    val past7Ms = deviceChild.child("Past_7_Days_Focus_Ms").getValue(Long::class.java) ?: 0L
                                    val todayMs = deviceChild.child("Todays_Focus_Ms").getValue(Long::class.java) ?: 0L

                                    val lastUpdateDate = if (lastUpdateStr.isNotEmpty()) {
                                        lastUpdateStr.substringBefore(" ")
                                    } else {
                                        ""
                                    }

                                    devicesMap[devKey] = com.example.api.DeviceStats(
                                        isLoggedIn = isLoggedIn,
                                        uploadStatus = uploadStatus,
                                        deviceName = devKey,
                                        allTimeFocusMs = allTimeMs,
                                        lastUpdateDate = lastUpdateDate,
                                        past30DaysFocusMs = past30Ms,
                                        past7DaysFocusMs = past7Ms,
                                        todayFocusMs = todayMs
                                    )
                                }
                            }

                            val state = PeerLiveState(
                                userId = friendId,
                                displayName = displayName,
                                currentTag = currentTag,
                                currentTask = currentTask,
                                timerMode = timerMode,
                                status = status,
                                timeline = timelineList,
                                lastUpdated = lastUpdated,
                                customEmoji = emojiVal,
                                devices = devicesMap
                            )

                            updatePeerState(friendId, state)
                        }

                        override fun onCancelled(error: DatabaseError) {
                            Log.e(TAG, "Listener cancelled for friend: $friendId", error.toException())
                        }
                    }

                    activeRef.addValueEventListener(listener)
                    activeListeners[friendId] = Pair(activeRef, listener)
                    Log.d(TAG, "Added listener for friend: $friendId")
                } catch (e: Exception) {
                    Log.e(TAG, "Error setting up listener for friend: $friendId", e)
                }
            }
        }
    }

    private fun updatePeerState(friendId: String, state: PeerLiveState?) {
        val current = _peerLiveStates.value.toMutableMap()
        if (state != null) {
            current[friendId] = state
        } else {
            current[friendId] = PeerLiveState(
                userId = friendId,
                displayName = getFallbackDisplayName(friendId),
                currentTag = "Study",
                currentTask = "Relaxing",
                timerMode = "pomodoro",
                status = "Relaxing",
                timeline = emptyList(),
                lastUpdated = com.example.util.TimeEngine.getTrueTimeMs()
            )
        }
        _peerLiveStates.value = current
    }

    fun cleanUpListeners() {
        try {
            roomCollectJob?.cancel()
            roomCollectJob = null

            friendsListRef?.let { ref ->
                friendsListListener?.let { listener ->
                    ref.removeEventListener(listener)
                }
            }
            friendsListRef = null
            friendsListListener = null

            sharedRoomsRef?.let { ref ->
                sharedRoomsListener?.let { listener ->
                    ref.removeEventListener(listener)
                }
            }
            sharedRoomsRef = null
            sharedRoomsListener = null

            for ((_, pair) in activeListeners) {
                val (ref, listener) = pair
                ref.removeEventListener(listener)
            }
            activeListeners.clear()
            _peerLiveStates.value = emptyMap()

            regularFriends = emptyList()
            activeRoomFriends = emptyList()

            Log.d(TAG, "Successfully cleaned up all peer Firebase listeners.")
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up peer listeners", e)
        }
    }

    private fun getFallbackDisplayName(userId: String): String {
        val clean = userId.substringBefore("@").replace(".", "_")
        val prefix = clean.substringBefore("_")
        return prefix.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString()
        }
    }
}
