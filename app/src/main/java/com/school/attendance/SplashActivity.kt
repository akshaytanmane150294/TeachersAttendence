package com.school.attendance

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Schools upload complete, proceeding directly to Login
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }
}
