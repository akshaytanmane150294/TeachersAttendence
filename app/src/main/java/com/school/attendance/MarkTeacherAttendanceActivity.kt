package com.school.attendance

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.school.attendance.databinding.ActivityMarkTeacherAttendanceBinding
import com.school.attendance.network.ApiClient
import com.school.attendance.network.AuthManager
import org.json.JSONObject
import java.io.IOException

class MarkTeacherAttendanceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMarkTeacherAttendanceBinding
    private var capturedBitmap: Bitmap? = null

    private var teacherName: String = ""
    private var schoolName: String = ""
    private var schoolCode: String = ""

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val bitmap = if (android.os.Build.VERSION.SDK_INT >= 33) {
                result.data?.extras?.getParcelable("data", Bitmap::class.java)
            } else {
                @Suppress("DEPRECATION")
                result.data?.extras?.get("data") as? Bitmap
            }
            if (bitmap != null) {
                capturedBitmap = bitmap
                binding.ivSchoolPhoto.setImageBitmap(bitmap)
                binding.tvLocationStatus.text = "Photo captured successfully!"
                binding.tvLocationStatus.setTextColor(resources.getColor(R.color.success, theme))
                checkReadyToSubmit()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!AuthManager.isLoggedIn()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finishAffinity()
            return
        }

        binding = ActivityMarkTeacherAttendanceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Directly display the post-attendance screen with "Student Attendance (Scan Paper)" button
        showSuccessUI("You Already Marked Attendance.")

        loadTeacherProfile()

        binding.btnStudentAttendance.setOnClickListener {
            val intent = Intent(this, StudentAttendanceScanActivity::class.java)
            intent.putExtra("schoolName", schoolName.ifEmpty { AuthManager.getSchoolName() })
            intent.putExtra("schoolCode", schoolCode.ifEmpty { AuthManager.getSchoolCode() })
            intent.putExtra("teacherName", teacherName.ifEmpty { AuthManager.getTeacherName() })
            intent.putExtra("className", "5A")
            startActivity(intent)
        }

        binding.btnTopProfile.setOnClickListener {
            com.school.attendance.dialogs.TeacherProfileDialog.show(this)
        }

        binding.btnLogout.setOnClickListener { performLogout() }
    }

    private fun performLogout() {
        AuthManager.clearToken()
        startActivity(Intent(this, LoginActivity::class.java))
        finishAffinity()
    }

    private fun loadTeacherProfile() {
        // 1. First load from offline cached profile
        val cachedTeacher = AuthManager.getTeacherName()
        val cachedSchool = AuthManager.getSchoolName()
        val cachedCode = AuthManager.getSchoolCode()
        if (cachedTeacher.isNotEmpty() && cachedTeacher != "Teacher") {
            teacherName = cachedTeacher
            schoolName = cachedSchool
            schoolCode = cachedCode
            binding.tvSchoolNameDisplay.text = schoolName
            binding.tvTeacherNameDisplay.text = "Teacher: $teacherName"
            binding.tvTopAvatarInitial.text = teacherName.trim().firstOrNull()?.uppercase() ?: "T"
        }

        // 2. Also try API if server is reachable
        Thread {
            try {
                val response = ApiClient.get("/auth/me", withAuth = true)
                val respStr = response.body?.string() ?: ""
                runOnUiThread {
                    if (response.isSuccessful) {
                        val json = JSONObject(respStr)
                        teacherName = json.optString("full_name", teacherName.ifEmpty { "Teacher" })
                        schoolName  = json.optString("school_name", schoolName.ifEmpty { "School" })
                        schoolCode  = json.optString("school_code", schoolCode)
                        binding.tvSchoolNameDisplay.text = schoolName
                        binding.tvTeacherNameDisplay.text = "Teacher: $teacherName"

                        // Check if already marked today
                        checkTodayStatus()
                    } else if (teacherName.isEmpty()) {
                        binding.tvSchoolNameDisplay.text = "School"
                        binding.tvTeacherNameDisplay.text = "Teacher"
                    }
                }
            } catch (e: Exception) {
                // If server is offline, keep the offline loaded profile
                runOnUiThread {
                    if (teacherName.isEmpty() || teacherName == "Teacher") {
                        binding.tvSchoolNameDisplay.text = schoolName.ifEmpty { "Offline Mode" }
                    }
                }
            }
        }.start()
    }

    private fun checkTodayStatus() {
        Thread {
            try {
                val response = ApiClient.get("/attendance/check-today", withAuth = true)
                val respStr = response.body?.string() ?: ""
                runOnUiThread {
                    if (response.isSuccessful) {
                        val json = JSONObject(respStr)
                        if (json.optBoolean("marked", false)) {
                            showSuccessUI("You Already Marked Attendance.")
                        }
                    }
                }
            } catch (e: IOException) { /* ignore */ }
        }.start()
    }

    private fun checkReadyToSubmit() {
        binding.btnSubmitAttendance.isEnabled = capturedBitmap != null
    }

    private fun submitAttendance() {
        setLoading(true)
        Thread {
            try {
                val body = JSONObject()
                val response = ApiClient.post("/attendance/mark", body, withAuth = true)
                val respStr = response.body?.string() ?: ""
                runOnUiThread {
                    setLoading(false)
                    if (response.isSuccessful) {
                        val json = JSONObject(respStr)
                        val status = json.optString("status", "")
                        val message = if (status == "already_marked") "You Already Marked Attendance." else "Marked Successfully"
                        showSuccessUI(message)
                    } else {
                        val msg = try { JSONObject(respStr).getString("detail") } catch (e: Exception) { "Failed to mark attendance" }
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

    private fun showSuccessUI(message: String) {
        binding.btnCapture.visibility = View.GONE
        binding.btnSubmitAttendance.visibility = View.GONE
        binding.tvLocationStatus.visibility = View.GONE
        binding.tvSuccessMessage.text = message
        binding.layoutSuccessInfo.visibility = View.VISIBLE
        binding.btnStudentAttendance.visibility = View.VISIBLE
        binding.btnLogout.visibility = View.VISIBLE
    }

    private fun requestCameraPermission() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 100)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED)) {
            Toast.makeText(this, "Camera permission is required for attendance", Toast.LENGTH_LONG).show()
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnSubmitAttendance.isEnabled = !loading && capturedBitmap != null
        binding.btnCapture.isEnabled = !loading
    }
}