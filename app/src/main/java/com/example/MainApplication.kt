package com.example

import android.app.Application

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        com.example.api.Firebase.ensureFirebaseInitialized(this)
    }
}
