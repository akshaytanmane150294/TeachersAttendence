package com.school.attendance

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.school.attendance.database.DirectDbManager
import com.school.attendance.database.RegisterResult
import com.school.attendance.databinding.ActivityRegisterBinding

class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding
    private var generatedToken: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DirectDbManager.init(this)

        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Prefill default Govt School test values
        binding.etUdiseId.setText("22252601008")
        binding.etTeacherId.setText("GT100001621")
        binding.etTeacherName.setText("NEHA DEWANGAN")
        binding.etMobileNumber.setText("9589324109")
        binding.etPassword.setText("123456")

        // 1. Register Button
        binding.btnRegister.setOnClickListener {
            attemptDirectRegister()
        }

        // 2. Copy Token Button
        binding.btnCopyToken.setOnClickListener {
            val token = binding.tvGeneratedToken.text.toString().trim()
            if (token.isNotEmpty()) {
                copyToClipboard(token)
                Toast.makeText(this, "Token copied to clipboard!", Toast.LENGTH_SHORT).show()
            }
        }

        // 3. Continue to App Button
        binding.btnContinueToApp.setOnClickListener {
            val teacherName = binding.etTeacherName.text.toString().trim().ifEmpty { "NEHA DEWANGAN" }
            val intent = Intent(this, StudentAttendanceScanActivity::class.java).apply {
                putExtra("teacherName", teacherName)
                putExtra("className", "5A")
            }
            startActivity(intent)
            finish()
        }

        // 4. Back to Login
        binding.tvGoLogin.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    private fun attemptDirectRegister() {
        val udiseId = binding.etUdiseId.text.toString().trim()
        val teacherId = binding.etTeacherId.text.toString().trim()
        val teacherName = binding.etTeacherName.text.toString().trim()
        val mobileNumber = binding.etMobileNumber.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()

        binding.tilUdiseId.error = null
        binding.tilTeacherId.error = null
        binding.tilTeacherName.error = null
        binding.tilMobileNumber.error = null
        binding.tilPassword.error = null

        var isValid = true
        if (udiseId.isEmpty()) {
            binding.tilUdiseId.error = "Enter School UDISE ID"
            isValid = false
        }
        if (teacherId.isEmpty()) {
            binding.tilTeacherId.error = "Enter Teacher ID"
            isValid = false
        }
        if (teacherName.isEmpty()) {
            binding.tilTeacherName.error = "Enter Teacher Name"
            isValid = false
        }
        if (mobileNumber.isEmpty() || mobileNumber.length < 10) {
            binding.tilMobileNumber.error = "Enter valid 10-digit Mobile Number"
            isValid = false
        }
        if (password.isEmpty()) {
            binding.tilPassword.error = "Enter Password"
            isValid = false
        }
        if (!isValid) return

        setLoading(true)

        Thread {
            val result = DirectDbManager.registerTeacherDirect(
                udiseId = udiseId,
                teacherId = teacherId,
                name = teacherName,
                mobileNumber = mobileNumber,
                passwordInput = password,
                context = this
            )

            runOnUiThread {
                setLoading(false)
                when (result) {
                    is RegisterResult.Success -> {
                        generatedToken = result.token
                        copyToClipboard(result.token)

                        Toast.makeText(this, "✅ Registration Successful! Now you can login with this Token.", Toast.LENGTH_LONG).show()

                        val intent = Intent(this, LoginActivity::class.java).apply {
                            putExtra("registeredTeacherCode", result.profile.teacherCode)
                            putExtra("registeredPassword", password)
                            putExtra("registeredToken", result.token)
                            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                        startActivity(intent)
                        finish()
                    }
                    is RegisterResult.Error -> {
                        Toast.makeText(this, "❌ ${result.message}", Toast.LENGTH_LONG).show()
                        AlertDialog.Builder(this)
                            .setTitle("Registration Error ❌")
                            .setMessage(result.message)
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            }
        }.start()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Teacher Token", text)
        clipboard.setPrimaryClip(clip)
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnRegister.isEnabled = !loading
    }
}