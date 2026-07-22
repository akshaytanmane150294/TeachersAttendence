package com.school.attendance

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.school.attendance.databinding.ActivityMarkAttendanceBinding
import com.school.attendance.models.AttendanceRecord
import java.text.SimpleDateFormat
import java.util.Locale

class MarkAttendanceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMarkAttendanceBinding
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMarkAttendanceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Pre-fill today's date
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(java.util.Date())
        binding.etDate.setText(today)

        binding.btnSaveAttendance.setOnClickListener { saveAttendance() }
    }

    private fun saveAttendance() {
        val studentName = binding.etStudentName.text.toString().trim()
        val className = binding.etClassName.text.toString().trim()
        val date = binding.etDate.text.toString().trim()
        val status = when (binding.chipGroupStatus.checkedChipId) {
            binding.chipAbsent.id -> 0
            else -> 1
        }

        if (studentName.isEmpty() || className.isEmpty() || date.isEmpty()) {
            Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
            return
        }

        val uid = auth.currentUser?.uid
        if (uid == null) {
            Toast.makeText(this, "Session expired. Please login again.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setLoading(true)
        val record = AttendanceRecord(
            username = studentName,
            schoolname = className,
            date = date,
            status = status,
            markedBy = uid,
            userId = uid
        )

        val docRef = firestore.collection("AttendencTable").document()
        record.id = docRef.id

        docRef.set(record)
            .addOnSuccessListener {
                setLoading(false)
                Toast.makeText(this, "Attendance saved", Toast.LENGTH_SHORT).show()
                finish()
            }
            .addOnFailureListener { e ->
                setLoading(false)
                Toast.makeText(this, "Failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnSaveAttendance.isEnabled = !loading
    }
}
