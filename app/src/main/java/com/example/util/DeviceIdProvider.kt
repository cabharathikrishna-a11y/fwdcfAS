package com.example.util

import android.content.Context
import java.util.UUID

object DeviceIdProvider {
    @Volatile
    private var cachedDeviceId: String? = null

    fun getDeviceId(context: Context): String {
        return cachedDeviceId ?: synchronized(this) {
            cachedDeviceId ?: run {
                val prefs = context.applicationContext.getSharedPreferences("device_prefs", Context.MODE_PRIVATE)
                var deviceId = prefs.getString("device_id", null)
                if (deviceId == null) {
                    val randomTag = UUID.randomUUID().toString().replace("-", "").take(6).lowercase()
                    deviceId = "APK_ROOM_$randomTag"
                    prefs.edit().putString("device_id", deviceId).apply()
                }
                cachedDeviceId = deviceId
                deviceId
            }
        }
    }

    fun forceSetDeviceId(context: Context, newId: String) {
        synchronized(this) {
            val prefs = context.applicationContext.getSharedPreferences("device_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("device_id", newId).apply()
            cachedDeviceId = newId
        }
    }
}
