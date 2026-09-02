package com.school.attendance

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.school.attendance.network.AuthManager

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AuthManager.init(this)

        // Always open Login screen as requested by user
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }
}
