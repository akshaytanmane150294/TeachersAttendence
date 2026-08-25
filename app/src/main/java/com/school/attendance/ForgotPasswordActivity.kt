package com.school.attendance

import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.school.attendance.databinding.ActivityForgotPasswordBinding
import com.school.attendance.network.ApiClient
import org.json.JSONObject
import java.io.IOException

class ForgotPasswordActivity : AppCompatActivity() {

    private lateinit var binding: ActivityForgotPasswordBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityForgotPasswordBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnSendReset.setOnClickListener { sendReset() }
        binding.tvBackToLogin.setOnClickListener { finish() }
    }

    private fun sendReset() {
        val email = binding.etEmail.text.toString().trim()
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            Toast.makeText(this, "Enter a valid email", Toast.LENGTH_SHORT).show()
            return
        }
        setLoading(true)
        Thread {
            try {
                val body = JSONObject().apply { put("email", email) }
                val response = ApiClient.post("/auth/forgot-password", body)
                val respStr = response.body?.string() ?: ""
                runOnUiThread {
                    setLoading(false)
                    if (response.isSuccessful) {
                        val json = JSONObject(respStr)
                        val tempPwd = json.getString("temp_password")
                        Toast.makeText(this, "Temporary password: $tempPwd\nPlease login with this password and change it.", Toast.LENGTH_LONG).show()
                        finish()
                    } else {
                        val msg = try { JSONObject(respStr).getString("detail") } catch (e: Exception) { "Could not reset password" }
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: IOException) {
                runOnUiThread {
                    setLoading(false)
                    Toast.makeText(this, "Cannot connect to server.", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnSendReset.isEnabled = !loading
    }
}