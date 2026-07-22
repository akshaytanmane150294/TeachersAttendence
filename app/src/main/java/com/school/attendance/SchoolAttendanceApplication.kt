package com.school.attendance

import android.app.Application
import com.google.firebase.Firebase
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.initialize

class SchoolAttendanceApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Firebase initialization
        Firebase.initialize(context = this)
        
        // Temporarily disabled App Check to fix SecurityException/DEVELOPER_ERROR
        /*
        Firebase.appCheck.installAppCheckProviderFactory(
            DebugAppCheckProviderFactory.getInstance()
        )
        */
    }
}
