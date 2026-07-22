package com.school.attendance

import android.app.Application
import com.google.firebase.Firebase
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.initialize

class SchoolAttendanceApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Must run before any Firestore/Gemini call anywhere in the app.
        Firebase.initialize(context = this)
        Firebase.appCheck.installAppCheckProviderFactory(
            DebugAppCheckProviderFactory.getInstance()
        )
        // NOTE: DebugAppCheckProviderFactory is for development only.
        // Before you publish a release build, switch this to
        // PlayIntegrityAppCheckProviderFactory.getInstance() instead.
    }
}
