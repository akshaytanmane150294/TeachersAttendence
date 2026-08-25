package com.school.attendance

import android.app.Application
import com.school.attendance.network.AuthManager

class SchoolAttendanceApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AuthManager.init(this)
    }
}
