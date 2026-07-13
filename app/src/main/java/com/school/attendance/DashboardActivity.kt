package com.school.attendance

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.school.attendance.adapters.AttendanceAdapter
import com.school.attendance.databinding.ActivityDashboardBinding
import com.school.attendance.models.AttendanceRecord
import com.school.attendance.models.Teacher

class DashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashboardBinding
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val adapter = AttendanceAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val uid = auth.currentUser?.uid
        if (uid == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        binding.rvAttendance.layoutManager = LinearLayoutManager(this)
        binding.rvAttendance.adapter = adapter

        loadTeacherProfile(uid)
        loadAttendance(uid)

        binding.swipeRefresh.setOnRefreshListener {
            loadAttendance(uid)
        }

        binding.cardMarkAttendance.setOnClickListener {
            startActivity(Intent(this, MarkTeacherAttendanceActivity::class.java))
        }

        binding.cardLogout.setOnClickListener {
            auth.signOut()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        auth.currentUser?.uid?.let { loadAttendance(it) }
    }

    private fun loadTeacherProfile(uid: String) {
        firestore.collection("Teachers").document(uid).get()
            .addOnSuccessListener { doc ->
                if (doc.exists()) {
                    val teacher = doc.toObject(Teacher::class.java)
                    if (teacher != null) {
                        binding.tvWelcome.text = "Welcome, ${teacher.fullName}"
                        binding.tvTeacherSubject.text = "ID: ${teacher.employeeId}\n${teacher.city}, ${teacher.district}, ${teacher.state}"
                    }
                } else {
                    binding.tvWelcome.text = "Profile not found in 'Teachers'"
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Profile load error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
    }

    private fun loadAttendance(uid: String) {
        binding.swipeRefresh.isRefreshing = true
        firestore.collection("AttendencTable")
            .whereEqualTo("teacherId", uid)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(50)
            .get()
            .addOnSuccessListener { snapshot ->
                binding.swipeRefresh.isRefreshing = false
                val records = snapshot.documents.mapNotNull { doc ->
                    val record = doc.toObject(AttendanceRecord::class.java)
                    // Map from AttendencTable fields if necessary or adjust model
                    record
                }
                adapter.submitList(records)
            }
            .addOnFailureListener { e ->
                binding.swipeRefresh.isRefreshing = false
                Toast.makeText(this, "Failed to load attendance: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
    }
}
