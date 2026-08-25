package com.school.attendance

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.school.attendance.databinding.ActivityLoginBinding
import com.school.attendance.network.ApiClient
import com.school.attendance.network.AuthManager
import org.json.JSONObject
import java.io.IOException

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Auto-redirect if already logged in
        if (AuthManager.isLoggedIn()) {
            startActivity(Intent(this, MarkTeacherAttendanceActivity::class.java))
            finish()
            return
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnLogin.setOnClickListener { attemptLogin() }
        binding.tvForgotPassword.setOnClickListener {
            startActivity(Intent(this, ForgotPasswordActivity::class.java))
        }
        binding.tvGoRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
    }

    private fun attemptLogin() {
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()

        binding.tilEmail.error = null
        binding.tilPassword.error = null

        var isValid = true
        if (email.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.tilEmail.error = "Enter a valid email"
            isValid = false
        }
        if (password.isEmpty() || password.length < 6) {
            binding.tilPassword.error = "Password must be at least 6 characters"
            isValid = false
        }
        if (!isValid) return

        setLoading(true)

        Thread {
            try {
                val body = JSONObject().apply {
                    put("email", email)
                    put("password", password)
                }
                val response = ApiClient.post("/auth/login", body)
                val respStr = response.body?.string() ?: ""
                runOnUiThread {
                    setLoading(false)
                    if (response.isSuccessful) {
                        val json = JSONObject(respStr)
                        val token = json.getString("token")
                        AuthManager.saveToken(token)
                        startActivity(Intent(this, MarkTeacherAttendanceActivity::class.java))
                        finish()
                    } else {
                        val msg = try { JSONObject(respStr).getString("detail") } catch (e: Exception) { "Login failed" }
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setLoading(false)
                    Toast.makeText(this, "Connection failed: ${e.localizedMessage ?: e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnLogin.isEnabled = !loading
    }
}
