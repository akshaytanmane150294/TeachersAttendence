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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.school.attendance.databinding.ActivityMarkTeacherAttendanceBinding
import com.school.attendance.models.Teacher
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MarkTeacherAttendanceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMarkTeacherAttendanceBinding
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val storage: FirebaseStorage by lazy { FirebaseStorage.getInstance() }
    
    private var teacher: Teacher? = null
    private var capturedBitmap: Bitmap? = null

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
                updateLocationUI() // Update UI to show photo captured status
                checkReadyToSubmit()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMarkTeacherAttendanceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadTeacherProfile()

        binding.tvLocationStatus.text = "Please capture a photo to mark attendance"

        binding.btnCapture.setOnClickListener {
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            cameraLauncher.launch(intent)
        }

        binding.btnSubmitAttendance.setOnClickListener {
            submitAttendance()
        }

        binding.btnStudentAttendance.setOnClickListener {
            if (teacher == null) {
                Toast.makeText(this, "Please wait, loading profile...", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = Intent(this, StudentAttendanceScanActivity::class.java)
            intent.putExtra("schoolName", teacher?.schoolName)
            intent.putExtra("schoolCode", teacher?.schoolCode)
            intent.putExtra("teacherName", teacher?.fullName)
            intent.putExtra("className", "5A")
            startActivity(intent)
        }

        binding.btnLogout.setOnClickListener {
            performLogout()
        }

        requestPermissions()
    }

    private fun performLogout() {
        auth.signOut()
        startActivity(Intent(this, LoginActivity::class.java))
        finishAffinity()
    }

    private fun loadTeacherProfile() {
        val uid = auth.currentUser?.uid ?: return
        val email = auth.currentUser?.email ?: ""
        
        // Try multiple collections including the one you requested "Teachers"
        val collections = listOf("Teachers", "teachers", "AttendencTable")
        
        fun tryLoad(index: Int) {
            if (index >= collections.size) {
                // If all database loads fail, use default placeholders
                binding.tvSchoolNameDisplay.text = "School: Govt School"
                binding.tvTeacherNameDisplay.text = "User: $email"
                return
            }
            
            firestore.collection(collections[index]).document(uid).get()
                .addOnSuccessListener { doc ->
                    if (doc.exists()) {
                        teacher = doc.toObject(Teacher::class.java)
                        // Verify if fields are loaded
                        if (teacher?.schoolName.isNullOrEmpty()) {
                            // If object mapping failed, try manual extraction
                            val name = doc.getString("fullName") ?: doc.getString("username") ?: ""
                            val school = doc.getString("schoolName") ?: doc.getString("school name") ?: ""
                            val code = doc.getString("schoolCode") ?: doc.getString("schoolcode") ?: ""
                            
                            teacher = Teacher(
                                uid = uid,
                                fullName = name,
                                schoolName = school,
                                schoolCode = code
                            )
                        }
                        updateProfileUI()
                    } else {
                        tryLoad(index + 1)
                    }
                }
                .addOnFailureListener {
                    tryLoad(index + 1)
                }
        }
        
        tryLoad(0)
    }

    private fun updateProfileUI() {
        teacher?.let {
            binding.tvSchoolNameDisplay.text = it.schoolName.ifEmpty { "School Name" }
            binding.tvTeacherNameDisplay.text = "Teacher: ${it.fullName}".ifEmpty { "Teacher Name" }
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()
        // Location permission removed as per request
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA)
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 100)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Camera granted, nothing else needed
            } else {
                Toast.makeText(this, "Camera permission is required for attendance", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun getCurrentLocation() {
        // Location logic completely disabled and commented out
        /*
        val availability = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this)
        if (availability != ConnectionResult.SUCCESS) {
            binding.tvLocationStatus.text = "Google Play Services not available. Error code: $availability"
            return
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            binding.tvLocationStatus.text = "Location permission not granted"
            return
        }

        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    currentLocation = location
                    updateLocationUI()
                    checkReadyToSubmit()
                } else {
                    binding.tvLocationStatus.text = "Unable to get current location. Please turn on GPS."
                }
            }.addOnFailureListener { e ->
                binding.tvLocationStatus.text = "Location Error: ${e.localizedMessage}"
            }
        } catch (e: Exception) {
            binding.tvLocationStatus.text = "GPS initialization failed"
        }
        */
    }

    private fun updateLocationUI() {
        // Location UI updates disabled as per request
        /*
        val t = teacher ?: return
        val loc = currentLocation ?: return
        
        val myLoc = "My Loc: ${String.format("%.4f", loc.latitude)}, ${String.format("%.4f", loc.longitude)}"
        binding.tvLocationStatus.text = "Location Captured: $myLoc"
        binding.tvLocationStatus.setTextColor(resources.getColor(R.color.success, theme))
        */
        binding.tvLocationStatus.text = "Photo captured successfully!"
        binding.tvLocationStatus.setTextColor(resources.getColor(R.color.success, theme))
    }

    private fun checkReadyToSubmit() {
        // Now only requires captured photo to enable submission
        binding.btnSubmitAttendance.isEnabled = capturedBitmap != null
    }

    private fun submitAttendance() {
        val bitmap = capturedBitmap ?: return
        val uid = auth.currentUser?.uid ?: return
        val email = auth.currentUser?.email ?: "Unknown"
        
        setLoading(true)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val documentId = "${uid}_$today"

        // Use document(id).get() to check for existing entry - prevents duplicate entries for same UID on same day
        firestore.collection("AttendencTable")
            .document(documentId)
            .get()
            .addOnSuccessListener { doc ->
                if (doc.exists()) {
                    setLoading(false)
                    // Show specific message for already marked users
                    showSuccessUI("You Already Marked Attendence.")
                } else {
                    val attendanceData = hashMapOf(
                        "username" to (teacher?.fullName ?: email),
                        "school name" to (teacher?.schoolName ?: "Govt School"),
                        "schoolcode" to (teacher?.schoolCode ?: "N/A"),
                        "date" to today,
                        "status" to 1,
                        "timestamp" to System.currentTimeMillis(),
                        "userId" to uid,
                        "markedBy" to uid
                    )

                    firestore.collection("AttendencTable")
                        .document(documentId)
                        .set(attendanceData)
                        .addOnSuccessListener {
                            uploadImageInBackground(uid, bitmap, today)
                            setLoading(false)
                            showSuccessUI("Marked Successfully")
                        }
                        .addOnFailureListener { e ->
                            setLoading(false)
                            Toast.makeText(this, "Failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                        }
                }
            }
            .addOnFailureListener { e ->
                setLoading(false)
                Toast.makeText(this, "Error checking status", Toast.LENGTH_SHORT).show()
            }
    }

    private fun showSuccessUI(message: String) {
        // Hide marking controls to make space for info
        binding.btnCapture.visibility = View.GONE
        binding.btnSubmitAttendance.visibility = View.GONE
        binding.tvLocationStatus.visibility = View.GONE
        
        // Update the message text in the UI
        binding.tvSuccessMessage.text = message
        
        // Show success layout, Student Attendance button and LOGOUT button
        binding.layoutSuccessInfo.visibility = View.VISIBLE
        binding.btnStudentAttendance.visibility = View.VISIBLE
        binding.btnLogout.visibility = View.VISIBLE
    }

    private fun uploadImageInBackground(uid: String, bitmap: Bitmap, date: String) {
        val fileName = "attendance_${uid}_$date.jpg"
        val storageRef = storage.reference.child("attendance_images/$fileName")

        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, baos)
        val data = baos.toByteArray()

        storageRef.putBytes(data)
            .addOnSuccessListener { /* Success */ }
            .addOnFailureListener { /* Silently ignore storage errors to not block user */ }
    }



    private fun showSuccessPrompt() {
        // Removed dialog as per request to show info directly in app UI
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnSubmitAttendance.isEnabled = !loading && capturedBitmap != null
        binding.btnCapture.isEnabled = !loading
    }
}
