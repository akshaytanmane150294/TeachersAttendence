package com.school.attendance

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.BlockThreshold
import com.google.ai.client.generativeai.type.HarmCategory
import com.google.ai.client.generativeai.type.SafetySetting
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.school.attendance.databinding.ActivityStudentAttendanceScanBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StudentAttendanceScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStudentAttendanceScanBinding
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    // API Key from screenshot
    private val GEMINI_API_KEY = "AQ.Ab8RN6KLrlUxt7SOWuxqMnZnKEEJHkus9KL19p1Zcquu5yd3QQ"

    private val generativeModel = GenerativeModel(
        modelName = "gemini-1.5-flash",
        apiKey = GEMINI_API_KEY,
        generationConfig = generationConfig {
            temperature = 0.1f
            topK = 1
            topP = 0.95f
            responseMimeType = "application/json"
        },
        safetySettings = listOf(
            SafetySetting(HarmCategory.HARASSMENT, BlockThreshold.NONE),
            SafetySetting(HarmCategory.HATE_SPEECH, BlockThreshold.NONE),
            SafetySetting(HarmCategory.SEXUALLY_EXPLICIT, BlockThreshold.NONE),
            SafetySetting(HarmCategory.DANGEROUS_CONTENT, BlockThreshold.NONE)
        )
    )

    private var capturedBitmap: Bitmap? = null
    private var schoolCode: String = ""
    private var className: String = "5A"
    private var photoFile: File? = null
    private var photoPath: String? = null

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val fileToLoad = photoFile ?: photoPath?.let { File(it) }
            if (fileToLoad != null) {
                try {
                    val bitmap = decodeSampledAndRotatedBitmap(fileToLoad, maxDimension = 3000)
                    if (bitmap != null) {
                        capturedBitmap = bitmap
                        binding.ivAttendancePaper.setImageBitmap(bitmap)
                        startGenAIProcess()
                    }
                    Log.d("ScanActivity", "Camera result processed")
                } catch (e: Exception) {
                    Log.e("ScanActivity", "Bitmap error: ${e.message}")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStudentAttendanceScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        savedInstanceState?.let {
            photoPath = it.getString("photoPath")
            photoPath?.let { path -> photoFile = File(path) }
        }

        schoolCode = intent.getStringExtra("schoolCode") ?: "N/A"
        className = intent.getStringExtra("className") ?: "5A"

        binding.btnCapturePaper.setOnClickListener {
            openCamera()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("photoPath", photoPath)
    }

    private fun openCamera() {
        try {
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            val file = createImageFile() ?: return
            photoFile = file
            photoPath = file.absolutePath
            val photoURI = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            intent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI)
            cameraLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Camera Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun createImageFile(): File? {
        return try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            File.createTempFile("JPEG_${timeStamp}_", ".jpg", storageDir)
        } catch (e: Exception) { null }
    }

    private fun decodeSampledAndRotatedBitmap(file: File, maxDimension: Int): Bitmap? {
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, boundsOptions)
        var sampleSize = 1
        while (boundsOptions.outWidth / (sampleSize * 2) >= maxDimension || boundsOptions.outHeight / (sampleSize * 2) >= maxDimension) { sampleSize *= 2 }
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val rawBitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOptions) ?: return null
        val exif = ExifInterface(file.absolutePath)
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val rotationDegrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rotationDegrees == 0f) return rawBitmap
        val matrix = Matrix().apply { postRotate(rotationDegrees) }
        return Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
    }

    private fun startGenAIProcess() {
        val bitmap = capturedBitmap ?: return
        setLoading(true, "AI is analyzing the sheet... (this may take a moment)")

        lifecycleScope.launch {
            try {
                val prompt = content {
                    image(bitmap)
                    text("""
                        Task: Extract school attendance data from this image.
                        Context: The image is a monthly attendance sheet for a class.

                        Requirements:
                        1. Identify the Month (as an integer 1-12) and Year (e.g. 2024).
                        2. For every student row, extract:
                           - Roll Number (as a string)
                           - Student Name
                           - Attendance status for every day of that month (1 for Present, 0 for Absent).

                        Note: Solid black dots, ticks, or '1' represent 'Present' (1). Empty cells or '0' represent 'Absent' (0).

                        Return the data STRICTLY as a JSON object with this structure:
                        {
                          "month": 6,
                          "year": 2026,
                          "students": [
                            {
                              "rollNo": "101",
                              "name": "Aarav Sharma",
                              "attendance": {
                                "2026-06-01": 1,
                                "2026-06-02": 0
                              }
                            }
                          ]
                        }

                        Important: Output ONLY the JSON object. Do not include markdown code blocks (```json).
                    """.trimIndent())
                }

                val response = withContext(Dispatchers.IO) { generativeModel.generateContent(prompt) }
                val jsonText = response.text ?: throw Exception("AI response empty")

                Log.d("ScanActivity", "RAW AI Response: $jsonText")

                val students = parseAiResponse(jsonText)
                setLoading(false)

                if (students.isEmpty()) {
                    Toast.makeText(this@StudentAttendanceScanActivity, "AI read the sheet but found no records.", Toast.LENGTH_LONG).show()
                } else {
                    showConfirmationDialog(students)
                }

            } catch (e: Exception) {
                Log.e("ScanActivity", "GenAI Error", e)
                setLoading(false)
                Toast.makeText(this@StudentAttendanceScanActivity, "AI Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun parseAiResponse(jsonString: String): List<StudentResult> {
        val result = mutableListOf<StudentResult>()
        try {
            val start = jsonString.indexOf("{")
            val end = jsonString.lastIndexOf("}")
            if (start == -1 || end == -1) return result

            val cleanedJson = jsonString.substring(start, end + 1)
            val root = JSONObject(cleanedJson)
            val studentsArray = root.optJSONArray("students") ?: return result

            for (i in 0 until studentsArray.length()) {
                val sObj = studentsArray.getJSONObject(i)
                val roll = sObj.optString("rollNo", "")
                val name = sObj.optString("name", "Unknown")
                val attObj = sObj.optJSONObject("attendance") ?: JSONObject()

                val attendanceMap = mutableMapOf<String, Int>()
                val keys = attObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = attObj.get(key)
                    attendanceMap[key] = if (value.toString() == "1" || value.toString().lowercase() == "true") 1 else 0
                }
                if (roll.isNotEmpty()) result.add(StudentResult(roll, name, attendanceMap))
            }
        } catch (e: Exception) {
            Log.e("ScanActivity", "Parse error: ${e.message}")
        }
        return result
    }

    private fun showConfirmationDialog(students: List<StudentResult>) {
        AlertDialog.Builder(this)
            .setTitle("Confirm AI Data")
            .setMessage("${students.size} students identified. Save to database?")
            .setPositiveButton("Save") { _, _ -> saveToFirebase(students) }
            .setNegativeButton("Retake", null)
            .show()
    }

    private fun saveToFirebase(students: List<StudentResult>) {
        setLoading(true, "Syncing...")
        val batch = firestore.batch()
        for (s in students) {
            val ref = firestore.collection("students").document(s.rollNo)
            val attMap = hashMapOf<String, Any>()
            s.attendance.forEach { (date, value) -> attMap[date] = value }
            
            val data = hashMapOf<String, Any>(
                "name" to s.name,
                "rollNo" to (s.rollNo.toIntOrNull() ?: 0),
                "class" to className,
                "schoolCode" to schoolCode,
                "attendance" to attMap,
                "lastUpdated" to System.currentTimeMillis()
            )
            batch.set(ref, data, SetOptions.merge())
        }
        batch.commit().addOnCompleteListener { task ->
            setLoading(false)
            if (task.isSuccessful) {
                photoFile?.delete()
                Toast.makeText(this, "Attendance Saved!", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this, "Failed: ${task.exception?.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setLoading(loading: Boolean, text: String = "") {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnCapturePaper.isEnabled = !loading
        if (loading) binding.tvStatus.text = text
    }

    data class StudentResult(val rollNo: String, val name: String, val attendance: Map<String, Int>)
}
