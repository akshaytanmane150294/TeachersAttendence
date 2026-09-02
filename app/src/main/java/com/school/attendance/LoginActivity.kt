package com.school.attendance

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.school.attendance.database.DirectDbManager
import com.school.attendance.database.LoginResult
import com.school.attendance.database.TokenResult
import com.school.attendance.databinding.ActivityLoginBinding
import com.school.attendance.network.AuthManager

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        DirectDbManager.init(this)

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Prefill previous teacher code if available
        val savedTeacherCode = AuthManager.getTeacherCode()
        if (savedTeacherCode.isNotEmpty()) {
            binding.etUsername.setText(savedTeacherCode)
        }

        // 1. Generate Token Button
        binding.btnGenerateToken.setOnClickListener {
            handleGenerateToken()
        }

        // 2. Copy Token Button
        binding.btnCopyToken.setOnClickListener {
            val token = binding.tvGeneratedToken.text.toString().trim()
            if (token.isNotEmpty()) {
                copyToClipboard(token)
                binding.etToken.setText(token)
                Toast.makeText(this, "Token copied to clipboard!", Toast.LENGTH_SHORT).show()
            }
        }

        // 3. Paste icon on Token input
        binding.tilToken.setEndIconOnClickListener {
            pasteFromClipboard()
        }

        // 4. Login Button
        binding.btnLogin.setOnClickListener {
            attemptLogin()
        }
    }

    /**
     * Handles Token Generation: Checks mst_teacher directly via JDBC.
     * If user is not found or invalid: displays "You aren't Authorized".
     * If user is found: generates token, saves in admin_tokens, updates mst_teacher, displays token.
     */
    private fun handleGenerateToken() {
        val username = binding.etUsername.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()

        binding.tilUsername.error = null
        binding.tilPassword.error = null

        var isValid = true
        if (username.isEmpty()) {
            binding.tilUsername.error = "Enter Teacher Code / Username"
            isValid = false
        }
        if (password.isEmpty()) {
            binding.tilPassword.error = "Enter Password"
            isValid = false
        }
        if (!isValid) return

        setTokenLoading(true)

        Thread {
            try {
                val result = DirectDbManager.generateTeacherToken(username, password, this)
                runOnUiThread {
                    setTokenLoading(false)
                    when (result) {
                        is TokenResult.Success -> {
                            // Display token card in UI
                            binding.cardGeneratedToken.visibility = View.VISIBLE
                            binding.tvGeneratedToken.text = result.token

                            // Auto copy to clipboard & auto fill token field
                            copyToClipboard(result.token)
                            binding.etToken.setText(result.token)

                            Toast.makeText(
                                this,
                                "Token generated: ${result.token} (Copied!)",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        is TokenResult.Error -> {
                            // Show "You aren't Authorized" or specific error message
                            Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    setTokenLoading(false)
                    Toast.makeText(this, "Database error: ${t.localizedMessage ?: t.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /**
     * Attempts offline login using Username + Password + Token directly against PostgreSQL.
     */
    private fun attemptLogin() {
        val username = binding.etUsername.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()
        val token = binding.etToken.text.toString().trim()

        binding.tilUsername.error = null
        binding.tilPassword.error = null
        binding.tilToken.error = null

        var isValid = true
        if (username.isEmpty()) {
            binding.tilUsername.error = "Enter Teacher Code / Username"
            isValid = false
        }
        if (password.isEmpty()) {
            binding.tilPassword.error = "Enter Password"
            isValid = false
        }
        // Token is optional if 7-day token is already active in database!
        if (!isValid) return

        setLoginLoading(true)

        Thread {
            try {
                val result = DirectDbManager.loginWithToken(username, password, token, this)
                runOnUiThread {
                    setLoginLoading(false)
                    when (result) {
                        is LoginResult.Success -> {
                            val profile = result.profile
                            // Save teacher profile locally in AuthManager
                            AuthManager.saveTeacherProfile(
                                teacherCode = profile.teacherCode,
                                fullName = profile.fullName,
                                schoolName = profile.schoolName,
                                schoolCode = profile.schoolCode,
                                udiseId = profile.udiseId,
                                designation = profile.designation,
                                mobileNo = profile.mobileNo,
                                tokenExpiryMillis = profile.tokenExpiryMillis
                            )

                            Toast.makeText(
                                this,
                                "Login Successful! Welcome, ${profile.fullName}",
                                Toast.LENGTH_SHORT
                            ).show()

                            // Navigate to MarkTeacherAttendanceActivity (which directly shows the Student Attendance options page)
                            startActivity(Intent(this, MarkTeacherAttendanceActivity::class.java))
                            finish()
                        }
                        is LoginResult.TokenRequired -> {
                            binding.tilToken.error = "7-Day Token Expired. Please click 'Generate Token'."
                            Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                        }
                        is LoginResult.Error -> {
                            Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    setLoginLoading(false)
                    Toast.makeText(this, "Database error: ${t.localizedMessage ?: t.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Security Token", text)
        clipboard.setPrimaryClip(clip)
    }

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipData = clipboard.primaryClip
        if (clipData != null && clipData.itemCount > 0) {
            val pasteText = clipData.getItemAt(0).text?.toString() ?: ""
            binding.etToken.setText(pasteText)
            Toast.makeText(this, "Pasted from clipboard", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setTokenLoading(loading: Boolean) {
        binding.tokenProgressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnGenerateToken.isEnabled = !loading
    }

    private fun setLoginLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnLogin.isEnabled = !loading
    }
}
