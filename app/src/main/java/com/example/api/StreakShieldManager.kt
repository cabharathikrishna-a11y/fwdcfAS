package com.example.api

import android.content.Context
import android.util.Log
import com.example.data.AppDatabase
import com.example.data.LocalShieldsVault
import com.example.util.TimeEngine
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import java.util.UUID

object StreakShieldManager {
    private const val TAG = "StreakShieldManager"

    fun getLocalShieldsFlow(context: Context): Flow<List<LocalShieldsVault>> {
        val db = AppDatabase.getInstance(context)
        return db.localShieldsVaultDao().getAllShields()
    }

    suspend fun getLocalUnconsumedShields(context: Context): List<LocalShieldsVault> {
        val db = AppDatabase.getInstance(context)
        return db.localShieldsVaultDao().getUnconsumedShieldsSync()
    }

    /**
     * Gifts a shield to a friend.
     * Rules:
     * 1. Sender pays 500 XP (tracked via /FOCUS_TIMMER/USER/{sender}/DEDUCTED_XP in RTDB).
     * 2. Recipient can hold maximum of 2 unconsumed shields.
     */
    fun giftShield(
        context: Context,
        senderEmail: String,
        senderName: String,
        friendEmail: String,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        if (senderEmail.isBlank() || friendEmail.isBlank()) {
            onFailure(IllegalArgumentException("Emails cannot be blank"))
            return
        }

        try {
            val dbUrl = FirebaseConfig.getDatabaseUrl(context)
            if (dbUrl.isEmpty()) {
                onFailure(IllegalStateException("Database URL is empty"))
                return
            }

            val database = FirebaseDatabase.getInstance(dbUrl)
            val friendSanitized = DevicePresenceManager.sanitizeEmail(friendEmail)
            val senderSanitized = DevicePresenceManager.sanitizeEmail(senderEmail)

            val friendShieldsRef = database.getReference("FOCUS_TIMMER")
                .child("USER")
                .child(friendSanitized)
                .child("SHIELDS")

            // 1. Check if recipient has < 2 unconsumed shields
            friendShieldsRef.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    var unconsumedCount = 0
                    if (snapshot.exists()) {
                        for (child in snapshot.children) {
                            val isConsumed = child.child("Is_Consumed").getValue(Boolean::class.java) 
                                ?: child.child("is_consumed").getValue(Boolean::class.java) 
                                ?: false
                            if (!isConsumed) {
                                unconsumedCount++
                            }
                        }
                    }

                    if (unconsumedCount >= 2) {
                        onFailure(Exception("This peer already has the maximum of 2 unconsumed Streak Shields!"))
                        return
                    }

                    // 2. Perform the gift and deduct 500 XP
                    val uuid = UUID.randomUUID().toString()
                    val trueTime = TimeEngine.getTrueTimeMs()

                    val shieldPayload = mapOf(
                        "Donor_Email" to senderEmail,
                        "Donor_Name" to senderName,
                        "Granted_Timestamp" to trueTime,
                        "Is_Consumed" to false,
                        "Consumed_Date" to null
                    )

                    // Write shield node
                    friendShieldsRef.child(uuid).setValue(shieldPayload).addOnCompleteListener { writeTask ->
                        if (writeTask.isSuccessful) {
                            // Deduct 500 XP from sender
                            val senderDeductedXpRef = database.getReference("FOCUS_TIMMER")
                                .child("USER")
                                .child(senderSanitized)
                                .child("DEDUCTED_XP")

                            senderDeductedXpRef.addListenerForSingleValueEvent(object : ValueEventListener {
                                override fun onDataChange(xpSnapshot: DataSnapshot) {
                                    val currentDeducted = xpSnapshot.getValue(Int::class.java) ?: 0
                                    senderDeductedXpRef.setValue(currentDeducted + 500).addOnCompleteListener { xpTask ->
                                        if (xpTask.isSuccessful) {
                                            Log.d(TAG, "Shield successfully gifted to $friendEmail. 500 XP deducted.")
                                            onSuccess()
                                        } else {
                                            Log.w(TAG, "Shield gifted but failed to deduct XP from sender")
                                            onSuccess() // Still succeed since the shield was written
                                        }
                                    }
                                }

                                override fun onCancelled(error: DatabaseError) {
                                    onSuccess()
                                }
                            })
                        } else {
                            onFailure(writeTask.exception ?: Exception("Failed to write shield node"))
                        }
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    onFailure(error.toException())
                }
            })

        } catch (e: Exception) {
            Log.e(TAG, "Error in giftShield", e)
            onFailure(e)
        }
    }

    /**
     * Fetches shields from cloud RTDB, performs auto-decay, and caches remaining locally.
     */
    fun fetchAndSyncShields(context: Context, myEmail: String, onComplete: (() -> Unit)? = null) {
        if (myEmail.isBlank()) return

        try {
            val dbUrl = FirebaseConfig.getDatabaseUrl(context)
            if (dbUrl.isEmpty()) return

            val database = FirebaseDatabase.getInstance(dbUrl)
            val mySanitized = DevicePresenceManager.sanitizeEmail(myEmail)

            val myShieldsRef = database.getReference("FOCUS_TIMMER")
                .child("USER")
                .child(mySanitized)
                .child("SHIELDS")

            myShieldsRef.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val db = AppDatabase.getInstance(context)
                            val dao = db.localShieldsVaultDao()
                            val trueTime = TimeEngine.getTrueTimeMs()

                            val activeCloudShields = mutableListOf<LocalShieldsVault>()

                            if (snapshot.exists()) {
                                for (child in snapshot.children) {
                                    val uuid = child.key ?: continue
                                    val donorEmail = child.child("Donor_Email").getValue(String::class.java) ?: ""
                                    val donorName = child.child("Donor_Name").getValue(String::class.java) ?: ""
                                    val grantedTs = child.child("Granted_Timestamp").getValue(Long::class.java) ?: 0L
                                    val isConsumed = child.child("Is_Consumed").getValue(Boolean::class.java) 
                                        ?: child.child("is_consumed").getValue(Boolean::class.java) 
                                        ?: false
                                    val consumedDate = child.child("Consumed_Date").getValue(String::class.java)

                                    // Auto-Decay (30-Day TTL)
                                    // If shield is over 30 days old, clean it from cloud
                                    if ((trueTime - grantedTs) > 2592000000L) {
                                        Log.d(TAG, "Auto-decay: Shield $uuid is older than 30 days. Deleting from cloud.")
                                        myShieldsRef.child(uuid).removeValue()
                                        // Also delete locally if it exists
                                        dao.deleteShield(uuid)
                                        continue
                                    }

                                    val localShield = LocalShieldsVault(
                                        uuid = uuid,
                                        donor_email = donorEmail,
                                        donor_name = donorName,
                                        granted_timestamp = grantedTs,
                                        is_consumed = isConsumed,
                                        consumed_date = consumedDate
                                    )
                                    activeCloudShields.add(localShield)
                                }
                            }

                            // Batch update locally
                            if (activeCloudShields.isNotEmpty()) {
                                dao.insertShields(activeCloudShields)
                            }

                            Log.d(TAG, "Synced ${activeCloudShields.size} shields from RTDB cloud.")
                            withContext(Dispatchers.Main) {
                                onComplete?.invoke()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error in fetchAndSyncShields internal block", e)
                        }
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "fetchAndSyncShields listener cancelled", error.toException())
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error in fetchAndSyncShields", e)
        }
    }
}
