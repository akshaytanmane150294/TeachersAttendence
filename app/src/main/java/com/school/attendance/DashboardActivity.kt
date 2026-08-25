package com.school.attendance

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.school.attendance.adapters.AttendanceAdapter
import com.school.attendance.databinding.ActivityDashboardBinding
import com.school.attendance.models.AttendanceRecord
import com.school.attendance.network.ApiClient
import com.school.attendance.network.AuthManager
import org.json.JSONObject
import java.io.IOException

class DashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashboardBinding
    private val adapter = AttendanceAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!AuthManager.isLoggedIn()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rvAttendance.layoutManager = LinearLayoutManager(this)
        binding.rvAttendance.adapter = adapter

        loadTeacherProfile()
        loadAttendance()

        binding.swipeRefresh.setOnRefreshListener { loadAttendance() }

        binding.cardMarkAttendance.setOnClickListener {
            startActivity(Intent(this, MarkTeacherAttendanceActivity::class.java))
        }

        binding.cardLogout.setOnClickListener {
            AuthManager.clearToken()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        loadAttendance()
    }

    private fun loadTeacherProfile() {
        Thread {
            try {
                val response = ApiClient.get("/auth/me", withAuth = true)
                val respStr = response.body?.string() ?: ""
                runOnUiThread {
                    if (response.isSuccessful) {
                        val json = JSONObject(respStr)
                        val name       = json.optString("full_name", "Teacher")
                        val empId      = json.optString("employee_id", "")
                        val city       = json.optString("city", "")
                        val district   = json.optString("district", "")
                        val state      = json.optString("state", "")
                        binding.tvWelcome.text = "Welcome, $name"
                        binding.tvTeacherSubject.text = "ID: $empId\n$city, $district, $state"
                    } else {
                        binding.tvWelcome.text = "Welcome"
                    }
                }
            } catch (e: IOException) {
                runOnUiThread { binding.tvWelcome.text = "Server offline" }
            }
        }.start()
    }

    private fun loadAttendance() {
        binding.swipeRefresh.isRefreshing = true
        Thread {
            try {
                val response = ApiClient.get("/attendance/history", withAuth = true)
                val respStr = response.body?.string() ?: ""
                runOnUiThread {
                    binding.swipeRefresh.isRefreshing = false
                    if (response.isSuccessful) {
                        val json = JSONObject(respStr)
                        val arr = json.getJSONArray("records")
                        val records = mutableListOf<AttendanceRecord>()
                        for (i in 0 until arr.length()) {
                            val r = arr.getJSONObject(i)
                            records.add(AttendanceRecord(
                                id       = r.optInt("id").toString(),
                                username = r.optString("username", ""),
                                schoolname = r.optString("school_name", ""),
                                schoolcode = r.optString("school_code", ""),
                                date     = r.optString("date", ""),
                                status   = r.optInt("status", 1),
                                timestamp = r.optLong("timestamp", 0),
                                userId   = r.optString("user_id", "")
                            ))
                        }
                        adapter.submitList(records)
                    } else {
                        Toast.makeText(this, "Failed to load attendance history", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: IOException) {
                runOnUiThread {
                    binding.swipeRefresh.isRefreshing = false
                    Toast.makeText(this, "Cannot connect to server", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }
}