package com.school.attendance

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
    
    private var capturedBitmap: Bitmap? = null
    private var schoolName: String = ""
    private var schoolCode: String = ""
    private var teacherName: String = ""
    private var className: String = "5A" 
    private var photoFile: File? = null
    private var photoPath: String? = null

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val fileToLoad = photoFile ?: photoPath?.let { File(it) }
            fileToLoad?.let { file ->
                try {
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    if (bitmap != null) {
                        capturedBitmap = bitmap
                        binding.ivAttendancePaper.setImageBitmap(bitmap)
                        startAutoProcess()
                    } else {
                        Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "Error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
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

        schoolName = intent.getStringExtra("schoolName") ?: "Govt School"
        schoolCode = intent.getStringExtra("schoolCode") ?: "N/A"
        teacherName = intent.getStringExtra("teacherName") ?: "Unknown"
        className = intent.getStringExtra("className") ?: "5A"

        binding.btnCapturePaper.setOnClickListener { openCamera() }
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
            val photoURI = FileProvider.getUriForFile(this, "com.school.attendance.fileprovider", file)
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

    private fun startAutoProcess() {
        val originalBitmap = capturedBitmap ?: return
        setLoading(true, "Scanning monthly sheet...")
        
        // Use higher resolution for precision grid analysis
        val maxDimension = 2000 
        val scale = Math.min(maxDimension.toFloat() / originalBitmap.width, maxDimension.toFloat() / originalBitmap.height).coerceAtMost(1.0f)
        val bitmap = if (scale < 1.0f) {
            Bitmap.createScaledBitmap(originalBitmap, (originalBitmap.width * scale).toInt(), (originalBitmap.height * scale).toInt(), true)
        } else {
            originalBitmap
        }
        
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val allElements = visionText.textBlocks.flatMap { it.lines }.flatMap { it.elements }
                if (allElements.isEmpty()) {
                    setLoading(false)
                    Toast.makeText(this, "No text found", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }

                // 1. Group rows with center-Y logic
                val rows = mutableListOf<MutableList<com.google.mlkit.vision.text.Text.Element>>()
                val sortedElements = allElements.sortedBy { it.boundingBox?.top ?: 0 }
                for (el in sortedElements) {
                    var added = false
                    val cy = el.boundingBox?.centerY() ?: 0
                    val h = el.boundingBox?.height() ?: 20
                    for (row in rows) {
                        if (Math.abs(cy - (row[0].boundingBox?.centerY() ?: 0)) < h * 0.4) {
                            row.add(el)
                            added = true
                            break
                        }
                    }
                    if (!added) rows.add(mutableListOf(el))
                }

                // 2. Detect Date Context
                val fullText = visionText.text.uppercase()
                val calendar = java.util.Calendar.getInstance()
                var sheetYear = calendar.get(java.util.Calendar.YEAR)
                var sheetMonth = calendar.get(java.util.Calendar.MONTH) + 1
                
                val monthNames = listOf("JANUARY", "FEBRUARY", "MARCH", "APRIL", "MAY", "JUNE", "JULY", "AUGUST", "SEPTEMBER", "OCTOBER", "NOVEMBER", "DECEMBER")
                monthNames.forEachIndexed { index, name -> if (fullText.contains(name)) sheetMonth = index + 1 }
                Regex("\\b(202[4-9])\\b").find(fullText)?.let { sheetYear = it.value.toInt() }

                val monthPrefix = "$sheetYear-${String.format("%02d", sheetMonth)}-"
                val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

                // 3. Grid Anchors (Headers 1-31) - Multi-point interpolation
                val detectedHeaderDays = mutableMapOf<Int, Int>() 
                var headerSlope = 0f
                var headerIdx = -1
                
                for ((i, row) in rows.withIndex()) {
                    val rowNums = row.mapNotNull { it.text.filter { c -> c.isDigit() }.toIntOrNull() }
                    if (rowNums.contains(1) && rowNums.contains(2) && rowNums.contains(3)) {
                        headerIdx = i
                        row.forEach { el ->
                            val d = el.text.filter { c -> c.isDigit() }.toIntOrNull()
                            if (d != null && d in 1..31) detectedHeaderDays[d] = el.boundingBox?.centerX() ?: 0
                        }
                        
                        val headerElements = row.filter { detectedHeaderDays.containsKey(it.text.filter { c -> c.isDigit() }.toIntOrNull()) }
                            .sortedBy { it.boundingBox?.left ?: 0 }
                        if (headerElements.size >= 2) {
                            val first = headerElements.first()
                            val last = headerElements.last()
                            val dx = (last.boundingBox?.centerX() ?: 0) - (first.boundingBox?.centerX() ?: 0)
                            val dy = (last.boundingBox?.centerY() ?: 0) - (first.boundingBox?.centerY() ?: 0)
                            if (dx != 0) headerSlope = dy.toFloat() / dx
                        }
                        break
                    }
                }

                if (detectedHeaderDays.isEmpty()) {
                    setLoading(false)
                    Toast.makeText(this, "Header 1-31 not found", Toast.LENGTH_LONG).show()
                    return@addOnSuccessListener
                }

                // Advanced Interpolation for Day Anchors
                val dayAnchors = mutableMapOf<Int, Int>()
                val sortedKnownDays = detectedHeaderDays.keys.sorted()
                for (d in 1..31) {
                    if (detectedHeaderDays.containsKey(d)) {
                        dayAnchors[d] = detectedHeaderDays[d]!!
                    } else {
                        val prev = sortedKnownDays.filter { it < d }.lastOrNull()
                        val next = sortedKnownDays.filter { it > d }.firstOrNull()
                        if (prev != null && next != null) {
                            val x1 = detectedHeaderDays[prev]!!
                            val x2 = detectedHeaderDays[next]!!
                            dayAnchors[d] = x1 + (x2 - x1) * (d - prev) / (next - prev)
                        } else if (prev != null) {
                            val x1 = detectedHeaderDays[prev]!!
                            val gap = if (sortedKnownDays.size > 1) (detectedHeaderDays[sortedKnownDays.last()]!! - detectedHeaderDays[sortedKnownDays.first()]!!) / (sortedKnownDays.last() - sortedKnownDays.first()) else 40
                            dayAnchors[d] = x1 + gap * (d - prev)
                        } else {
                            val x2 = detectedHeaderDays[next!!]!!
                            val gap = if (sortedKnownDays.size > 1) (detectedHeaderDays[sortedKnownDays.last()]!! - detectedHeaderDays[sortedKnownDays.first()]!!) / (sortedKnownDays.last() - sortedKnownDays.first()) else 40
                            dayAnchors[d] = x2 - gap * (next - d)
                        }
                    }
                }

                // 4. Extract Student Data
                val students = mutableListOf<StudentResult>()
                val day1X = dayAnchors[1] ?: 0
                val cal = java.util.Calendar.getInstance()
                cal.set(sheetYear, sheetMonth - 1, 1)
                val maxDaysInMonth = cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)

                for (i in headerIdx + 1 until rows.size) {
                    val row = rows[i].sortedBy { it.boundingBox?.left ?: 0 }
                    val rollEl = row.find { it.text.filter { c -> c.isDigit() }.length in 1..3 } ?: continue
                    val roll = rollEl.text.filter { it.isDigit() }.toInt()
                    if (roll > 100) continue 

                    val nameElements = row.filter { 
                        val cx = it.boundingBox?.centerX() ?: 0
                        cx > (rollEl.boundingBox?.right ?: 0) && cx < day1X - 10 
                    }
                    val namePart = nameElements.joinToString(" ") { it.text }.trim()
                    if (namePart.isEmpty() || namePart.contains("YEAR", true)) continue

                    // Calculate Row Context (Adaptive)
                    val rowTextElements = mutableListOf(rollEl)
                    rowTextElements.addAll(nameElements)
                    val ry = rowTextElements.map { it.boundingBox?.centerY() ?: 0 }.average().toInt()
                    val rx = rollEl.boundingBox?.centerX() ?: 0

                    // Adaptive Thresholding: Sample the "white" paper color near the name
                    var paperLuma = 200
                    try {
                        val sampleX = (rollEl.boundingBox?.right ?: 0) + 5
                        if (sampleX < bitmap.width) {
                            val pixel = bitmap.getPixel(sampleX, ry)
                            paperLuma = ((pixel shr 16 and 0xFF) + (pixel shr 8 and 0xFF) + (pixel and 0xFF)) / 3
                        }
                    } catch (e: Exception) {}
                    val dynamicThreshold = (paperLuma * 0.75).toInt().coerceIn(70, 140)

                    // 5. High-Precision Local Search Dot Detection
                    val attendance = mutableMapOf<String, Int>()
                    for (day in 1..31) {
                        if (day > maxDaysInMonth) continue
                        val date = "$monthPrefix${String.format("%02d", day)}"
                        if (date > todayStr) continue 

                        val ax = dayAnchors[day] ?: continue
                        val targetY = ry + (headerSlope * (ax - rx)).toInt()
                        
                        // Refined Contrast-Based Detection
                        var isPresent = false
                        try {
                            // 1. Find Average brightness of the center (10x10)
                            var centerLumaSum = 0
                            var centerCount = 0
                            val cSize = 4
                            for (dx in -cSize..cSize) {
                                for (dy in -cSize..cSize) {
                                    val px = ax + dx
                                    val py = targetY + dy
                                    if (px in 0 until bitmap.width && py in 0 until bitmap.height) {
                                        val p = bitmap.getPixel(px, py)
                                        centerLumaSum += ((p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF)) / 3
                                        centerCount++
                                    }
                                }
                            }
                            val avgCenterLuma = if (centerCount > 0) centerLumaSum / centerCount else 255
                            
                            // 2. Sample corner luma to detect "paper" vs "dot"
                            var cornerLumaSum = 0
                            var cornerCount = 0
                            val cr = 8
                            val corners = listOf(Pair(-cr, -cr), Pair(cr, -cr), Pair(-cr, cr), Pair(cr, cr))
                            for (c in corners) {
                                val px = ax + c.first
                                val py = targetY + c.second
                                if (px in 0 until bitmap.width && py in 0 until bitmap.height) {
                                    val p = bitmap.getPixel(px, py)
                                    cornerLumaSum += ((p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF)) / 3
                                    cornerCount++
                                }
                            }
                            val avgCornerLuma = if (cornerCount > 0) cornerLumaSum / cornerCount else 255
                            
                            // A dot has high contrast between center and corners
                            // And the center must be dark relative to dynamic threshold
                            if (avgCenterLuma < dynamicThreshold && (avgCornerLuma - avgCenterLuma) > 35) {
                                isPresent = true
                            }
                        } catch (e: Exception) {}
                        
                        attendance[date] = if (isPresent) 1 else 0
                    }
                    students.add(StudentResult(roll.toString(), namePart, attendance))
                }

                if (students.isEmpty()) {
                    setLoading(false)
                    Toast.makeText(this, "No student rows identified", Toast.LENGTH_SHORT).show()
                } else {
                    val futureMsg = if (todayStr.startsWith(monthPrefix)) " (Skipping dates after today)" else ""
                    binding.tvStatus.text = "Syncing ${students.size} students$futureMsg..."
                    saveData(students)
                }
            }
    }

    private fun saveData(students: List<StudentResult>) {
        var done = 0
        students.forEach { s ->
            val ref = firestore.collection("students").document(s.rollNo)
            firestore.runTransaction { tx ->
                val snap = tx.get(ref)
                @Suppress("UNCHECKED_CAST")
                val existing = snap.get("attendance") as? Map<String, Any> ?: emptyMap()
                val updates = mutableMapOf<String, Any>()
                
                s.attendance.forEach { (d, status) ->
                    if (!existing.containsKey(d)) updates["attendance.$d"] = status
                }

                if (!snap.exists()) {
                    tx.set(ref, hashMapOf(
                        "name" to s.name,
                        "rollNo" to (s.rollNo.toIntOrNull() ?: 0),
                        "class" to className,
                        "schoolCode" to schoolCode,
                        "attendance" to s.attendance,
                        "lastUpdated" to System.currentTimeMillis()
                    ))
                } else if (updates.isNotEmpty()) {
                    tx.update(ref, "lastUpdated", System.currentTimeMillis())
                    updates.forEach { (k, v) -> tx.update(ref, k, v) }
                }
                null
            }.addOnCompleteListener {
                if (++done == students.size) {
                    setLoading(false)
                    Toast.makeText(this, "Sync Complete", Toast.LENGTH_LONG).show()
                    binding.root.postDelayed({ finish() }, 2000)
                }
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
