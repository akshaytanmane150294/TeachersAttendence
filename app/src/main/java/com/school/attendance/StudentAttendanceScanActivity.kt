package com.school.attendance

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.school.attendance.databinding.ActivityStudentAttendanceScanBinding
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StudentAttendanceScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStudentAttendanceScanBinding
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val storage: FirebaseStorage by lazy { FirebaseStorage.getInstance() }
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    
    private var capturedBitmap: Bitmap? = null
    private var schoolName: String = ""
    private var schoolCode: String = ""
    private var teacherName: String = ""
    private var photoFile: File? = null

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            photoFile?.let { file ->
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) {
                    capturedBitmap = bitmap
                    binding.ivAttendancePaper.setImageBitmap(bitmap)
                    startAutoProcess()
                } else {
                    Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStudentAttendanceScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        schoolName = intent.getStringExtra("schoolName") ?: "Govt School"
        schoolCode = intent.getStringExtra("schoolCode") ?: "N/A"
        teacherName = intent.getStringExtra("teacherName") ?: "Unknown"

        binding.btnCapturePaper.setOnClickListener {
            openCamera()
        }
    }

    private fun openCamera() {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        photoFile = createImageFile()
        photoFile?.let {
            val photoURI: Uri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                it
            )
            intent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI)
            cameraLauncher.launch(intent)
        }
    }

    private fun createImageFile(): File? {
        val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val storageDir: File? = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile("JPEG_${timeStamp}_", ".jpg", storageDir)
    }

    private fun startAutoProcess() {
        val bitmap = capturedBitmap ?: return
        setLoading(true, "Scanning paper in High Quality...")
        
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val attendanceData = mutableListOf<Pair<String, Int>>()
                
                // OCR Logic: Find all numbers and group them by rows
                val allElements = visionText.textBlocks.flatMap { it.lines }.flatMap { it.elements }
                val rows = mutableListOf<MutableList<com.google.mlkit.vision.text.Text.Element>>()
                
                // Sort by top position to identify rows
                val sortedElements = allElements.sortedBy { it.boundingBox?.top ?: 0 }
                
                for (element in sortedElements) {
                    var added = false
                    for (row in rows) {
                        val rowTop = row[0].boundingBox?.top ?: 0
                        val rowHeight = row[0].boundingBox?.height() ?: 20
                        if (Math.abs((element.boundingBox?.top ?: 0) - rowTop) < rowHeight * 0.7) {
                            row.add(element)
                            added = true
                            break
                        }
                    }
                    if (!added) rows.add(mutableListOf(element))
                }

                // Process rows for RollNo and Status
                for (row in rows) {
                    val rowTextElements = row.sortedBy { it.boundingBox?.left ?: 0 }
                    val digits = rowTextElements.map { it.text.filter { char -> char.isDigit() } }.filter { it.isNotEmpty() }
                    
                    if (digits.size >= 2) {
                        val roll = digits.first()
                        val statusText = digits.last()
                        // Accept 1/0, or try to interpret if status is messy
                        val status = if (statusText == "1") 1 else 0
                        attendanceData.add(roll to status)
                    }
                }
                
                if (attendanceData.isEmpty()) {
                    setLoading(false)
                    Toast.makeText(this, "No data found. Detected text: ${visionText.text.take(30)}...", Toast.LENGTH_LONG).show()
                } else {
                    binding.tvStatus.text = "Found ${attendanceData.size} students. Uploading..."
                    uploadPaperAndData(attendanceData)
                }
            }
            .addOnFailureListener { e ->
                setLoading(false)
                Toast.makeText(this, "Scan failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun uploadPaperAndData(dataList: List<Pair<String, Int>>) {
        val bitmap = capturedBitmap ?: return
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val fileName = "student_papers/${schoolCode}_${today}_${System.currentTimeMillis()}.jpg"
        val storageRef = storage.reference.child(fileName)

        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 50, baos) // 50% quality to save space
        val bytes = baos.toByteArray()

        storageRef.putBytes(bytes).addOnSuccessListener {
            storageRef.downloadUrl.addOnSuccessListener { uri ->
                saveToFirestore(dataList, today, uri.toString())
            }
        }.addOnFailureListener {
            saveToFirestore(dataList, today, "no_image_url")
        }
    }

    private fun saveToFirestore(dataList: List<Pair<String, Int>>, date: String, imageUrl: String) {
        val batch = firestore.batch()
        val teacherUid = auth.currentUser?.uid ?: "unknown"

        dataList.forEach { (rollNo, status) ->
            val data = hashMapOf(
                "rollNumber" to rollNo,
                "username" to teacherName,
                "school name" to schoolName,
                "schoolcode" to schoolCode,
                "date" to date,
                "status" to status,
                "markedBy" to teacherUid,
                "paperImageUrl" to imageUrl,
                "timestamp" to System.currentTimeMillis()
            )
            
            val docRef = firestore.collection("student").document("${schoolCode}_${date}_Roll_$rollNo")
            batch.set(docRef, data)
        }

        batch.commit()
            .addOnSuccessListener {
                setLoading(false)
                binding.tvStatus.text = "Success! ${dataList.size} students uploaded."
                Toast.makeText(this, "Attendance marked successfully", Toast.LENGTH_LONG).show()
                binding.root.postDelayed({ finish() }, 2000)
            }
            .addOnFailureListener { e ->
                setLoading(false)
                Toast.makeText(this, "Upload failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
    }

    private fun setLoading(loading: Boolean, statusText: String = "Processing...") {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnCapturePaper.isEnabled = !loading
        if (loading) {
            binding.tvStatus.text = statusText
        }
    }
}
