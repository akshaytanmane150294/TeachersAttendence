package com.school.attendance

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.school.attendance.databinding.ActivityRegisterBinding
import com.school.attendance.network.ApiClient
import com.school.attendance.network.AuthManager
import org.json.JSONObject
import java.io.IOException

class RegisterActivity : AppCompatActivity() {
    private val NAME_REGEX = Regex("^[A-Za-z ]{3,50}$")
    private val EMPLOYEE_REGEX = Regex("^[A-Za-z0-9_-]{3,20}$")
    private val LOCATION_REGEX = Regex("^[A-Za-z ]{2,40}$")
    private val PASSWORD_REGEX = Regex("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@#\$%^&+=!]).{8,}$")

    private lateinit var binding: ActivityRegisterBinding

    data class School(val name: String, val code: String, val lat: Double, val lng: Double) {
        override fun toString(): String = name
    }

    private val defaultSchools = listOf(
        School("Bhilai Nagar Higher Secondary School", "BNHSS001", 21.1920, 81.3700),
        School("DAV Public School Bhilai", "DAVBHILAI", 21.1950, 81.3810),
        School("Govt Boys Higher Secondary School Sector 9", "GBHSS009", 21.2001, 81.3695),
        School("Govt Girls Higher Secondary School Sector 6", "GGHSS006", 21.1875, 81.3840),
        School("Govt Higher Secondary School Bhilai", "GHSS001", 21.1938, 81.3786),
        School("Govt Higher Secondary School Charoda", "GHSSC001", 21.2200, 81.3600),
        School("Govt Higher Secondary School Durg", "GHSSD001", 21.1890, 81.2860),
        School("Govt Primary School Risali", "GPSR001", 21.1800, 81.4000),
        School("Kendriya Vidyalaya Bhilai Steel Plant", "KVBSP001", 21.2030, 81.3750),
        School("Swami Atmanand Govt English Medium School", "SAGES001", 21.2100, 81.3900)
    )

    private var schools = mutableListOf<School>()
    private var selectedSchool: School? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize with default schools immediately
        schools.addAll(defaultSchools)
        setupSchoolDropdown()

        // Then fetch latest from server
        fetchSchools()

        binding.btnRegister.setOnClickListener { attemptRegister() }
        binding.tvGoLogin.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    private fun fetchSchools() {
        Thread {
            try {
                val response = ApiClient.get("/schools")
                val respStr = response.body?.string() ?: ""
                runOnUiThread {
                    if (response.isSuccessful) {
                        val json = JSONObject(respStr)
                        val arr = json.getJSONArray("schools")
                        if (arr.length() > 0) {
                            schools.clear()
                            for (i in 0 until arr.length()) {
                                val s = arr.getJSONObject(i)
                                schools.add(School(s.getString("name"), s.optString("code",""), s.optDouble("lat",0.0), s.optDouble("lng",0.0)))
                            }
                            setupSchoolDropdown()
                        }
                    }
                }
            } catch (e: Exception) {
                // Keep default schools if network fetch fails
            }
        }.start()
    }

    private fun setupSchoolDropdown() {
        val names = schools.map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, names)
        binding.actvSchool.setAdapter(adapter)
        binding.actvSchool.setOnClickListener { binding.actvSchool.showDropDown() }
        binding.actvSchool.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) binding.actvSchool.showDropDown() }
        binding.actvSchool.setOnItemClickListener { _, _, position, _ ->
            if (position in schools.indices) {
                selectedSchool = schools[position]
                binding.actvSchool.error = null
            }
        }
    }

    private fun attemptRegister() {
        val fullName   = binding.etFullName.text.toString().trim()
        val employeeId = binding.etEmployeeId.text.toString().trim()
        val city       = binding.etCity.text.toString().trim()
        val district   = binding.etDistrict.text.toString().trim()
        val state      = binding.etState.text.toString().trim()
        val email      = binding.etEmail.text.toString().trim()
        val password   = binding.etPassword.text.toString().trim()
        val confirmPwd = binding.etConfirmPassword.text.toString().trim()

        if (!NAME_REGEX.matches(fullName)) { binding.etFullName.error = "Enter valid full name"; return }
        if (!EMPLOYEE_REGEX.matches(employeeId)) { binding.etEmployeeId.error = "Employee ID must be 4-10 digits"; return }
        if (!LOCATION_REGEX.matches(city)) { binding.etCity.error = "Enter valid city"; return }
        if (!LOCATION_REGEX.matches(state)) { binding.etState.error = "Enter valid state"; return }
        if (selectedSchool == null) { binding.actvSchool.error = "Please select a school"; return }
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) { binding.etEmail.error = "Enter a valid email"; return }
        if (!PASSWORD_REGEX.matches(password)) { binding.etPassword.error = "Password must contain Uppercase, Lowercase, Number & Special Char"; return }
        if (password != confirmPwd) { binding.etConfirmPassword.error = "Passwords do not match"; return }

        setLoading(true)
        val school = selectedSchool!!
        Thread {
            try {
                val body = JSONObject().apply {
                    put("full_name", fullName); put("employee_id", employeeId)
                    put("email", email); put("password", password)
                    put("city", city); put("district", district); put("state", state)
                    put("school_name", school.name); put("school_code", school.code)
                    put("school_lat", school.lat); put("school_lng", school.lng)
                }
                val response = ApiClient.post("/auth/register", body)
                val respStr = response.body?.string() ?: ""
                runOnUiThread {
                    setLoading(false)
                    if (response.isSuccessful) {
                        AuthManager.saveToken(JSONObject(respStr).getString("token"))
                        val intent = Intent(this, StudentAttendanceScanActivity::class.java).apply {
                            putExtra("teacherName", fullName)
                            putExtra("className", "5A")
                        }
                        startActivity(intent)
                        finish()
                    } else {
                        val msg = try { JSONObject(respStr).getString("detail") } catch (e: Exception) { "Registration failed" }
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { setLoading(false); Toast.makeText(this, "Connection failed: ${e.localizedMessage ?: e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnRegister.isEnabled = !loading
    }
}