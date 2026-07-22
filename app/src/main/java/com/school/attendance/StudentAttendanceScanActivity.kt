package com.school.attendance

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.BlockThreshold
import com.google.ai.client.generativeai.type.HarmCategory
import com.google.ai.client.generativeai.type.RequestOptions
import com.google.ai.client.generativeai.type.SafetySetting
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.school.attendance.databinding.ActivityStudentAttendanceScanBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil

class StudentAttendanceScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStudentAttendanceScanBinding
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    // Using the Free Tier Gemini API Key
    private val GEMINI_API_KEY = ""

    private val generativeModel by lazy {
        GenerativeModel(
            modelName = "gemini-3.6-flash",
            apiKey = GEMINI_API_KEY,
            generationConfig = generationConfig {
                temperature = 0.0f
                responseMimeType = "application/json"
            },
            safetySettings = listOf(
                SafetySetting(HarmCategory.HARASSMENT, BlockThreshold.NONE),
                SafetySetting(HarmCategory.HATE_SPEECH, BlockThreshold.NONE),
                SafetySetting(HarmCategory.SEXUALLY_EXPLICIT, BlockThreshold.NONE),
                SafetySetting(HarmCategory.DANGEROUS_CONTENT, BlockThreshold.NONE)
            ),
            requestOptions = RequestOptions(apiVersion = "v1beta")
        )
    }

    private var capturedBitmap: Bitmap? = null
    private var schoolCode: String = ""
    private var className: String = "5A"
    private var photoFile: File? = null
    private var photoPath: String? = null

    // The month/year this scan represents. Defaults to the current calendar
    // month, but you should pass explicit "year"/"month" Intent extras from
    // wherever this Activity is launched (e.g. a month picker) so a scan done
    // late or early doesn't get filed under the wrong month.
    private var scanYear: Int = 0
    private var scanMonth: Int = 0

    // FIX: Cropping removed. We now send the FULL image on both API calls and
    // only tell the model, via the prompt, which day-columns to report for
    // each call. This avoids column/roll-number columns getting cut off mid
    // character (which was causing roll numbers like "101" to be misread as
    // "1" and then rejected by the roster check below).

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchCamera()
        } else {
            Toast.makeText(
                this,
                "Camera permission is required to scan attendance sheets",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val fullPhotoCameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) {
            Toast.makeText(this, "Photo capture cancelled", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        processCapturedPhotoFile()
    }

    private val thumbnailCameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) {
            Toast.makeText(this, "Photo capture cancelled", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }

        val bitmap = if (android.os.Build.VERSION.SDK_INT >= 33) {
            result.data?.extras?.getParcelable("data", Bitmap::class.java)
        } else {
            @Suppress("DEPRECATION")
            result.data?.extras?.get("data") as? Bitmap
        }

        if (bitmap == null) {
            Toast.makeText(this, "Could not read captured photo", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }

        try {
            val file = createImageFile()
            if (file != null) {
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
                photoFile = file
                photoPath = file.absolutePath
            }
            capturedBitmap = bitmap
            binding.ivAttendancePaper.setImageBitmap(bitmap)
            startGenAIProcess()
        } catch (e: Exception) {
            Log.e("ScanActivity", "Thumbnail save error", e)
            Toast.makeText(this, "Failed to save photo: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
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

        val cal = Calendar.getInstance()
        scanYear = intent.getIntExtra("year", cal.get(Calendar.YEAR))
        scanMonth = intent.getIntExtra("month", cal.get(Calendar.MONTH) + 1)

        binding.btnCapturePaper.setOnClickListener {
            openCamera()
        }
    }

    override fun onResume() {
        super.onResume()
        // Automatic camera opening removed to ensure stability.
        // The user must click the "Take Picture" button.
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("photoPath", photoPath)
    }

    private fun openCamera() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED -> launchCamera()
            else -> requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchCamera() {
        try {
            if (launchFullPhotoCamera()) return
            if (launchThumbnailCamera()) return
            Toast.makeText(this, "No camera app found on this device", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e("ScanActivity", "Camera Error", e)
            Toast.makeText(this, "Camera Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun launchFullPhotoCamera(): Boolean {
        val file = createImageFile() ?: return false
        photoFile = file
        photoPath = file.absolutePath

        val authority = "${applicationContext.packageName}.fileprovider"
        val photoUri = try {
            FileProvider.getUriForFile(this, authority, file)
        } catch (e: Exception) {
            Log.e("ScanActivity", "FileProvider error", e)
            return false
        }

        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val cameraApps = packageManager.queryIntentActivities(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY
        )
        if (cameraApps.isEmpty()) return false

        for (resolveInfo in cameraApps) {
            grantUriPermission(
                resolveInfo.activityInfo.packageName,
                photoUri,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        fullPhotoCameraLauncher.launch(intent)
        return true
    }

    private fun launchThumbnailCamera(): Boolean {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (intent.resolveActivity(packageManager) == null) return false
        thumbnailCameraLauncher.launch(intent)
        return true
    }

    private fun processCapturedPhotoFile() {
        val fileToLoad = photoFile ?: photoPath?.let { File(it) }
        Log.d("ScanActivity", "Processing photo file: ${fileToLoad?.absolutePath}")

        if (fileToLoad == null || !fileToLoad.exists() || fileToLoad.length() == 0L) {
            Log.e("ScanActivity", "File is null or empty")
            Toast.makeText(this, "Could not read captured photo", Toast.LENGTH_LONG).show()
            return
        }

        try {
            // Increased to 3000 for better accuracy with small dots in grids
            val bitmap = decodeSampledAndRotatedBitmap(fileToLoad, maxDimension = 3000)
            if (bitmap != null) {
                capturedBitmap = bitmap
                binding.ivAttendancePaper.setImageBitmap(bitmap)
                Log.d("ScanActivity", "Bitmap loaded successfully, starting AI...")
                startGenAIProcess()
            } else {
                Log.e("ScanActivity", "Bitmap decoding returned null")
                Toast.makeText(this, "Failed to process photo", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e("ScanActivity", "Bitmap error: ${e.message}", e)
            Toast.makeText(this, "Bitmap error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun createImageFile(): File? {
        return try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                ?: cacheDir
            if (!storageDir.exists()) storageDir.mkdirs()
            File.createTempFile("ATT_${timeStamp}_", ".jpg", storageDir)
        } catch (e: Exception) {
            Log.e("ScanActivity", "createImageFile failed", e)
            null
        }
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

    private fun daysInMonth(year: Int, month: Int): Int {
        val cal = Calendar.getInstance()
        cal.set(year, month - 1, 1)
        return cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    }

    /**
     * FIX: Builds the exact list of day numbers this call is responsible for,
     * e.g. [1,2,...,16]. Passing the explicit list (not just "roughly 1-16")
     * lets us instruct the model to output a key for EVERY one of these days,
     * present or absent, so blank/absent cells no longer get silently omitted
     * from the JSON (which was the main cause of gaps in the merged data).
     */
    private fun buildPrompt(days: List<Int>): String {
        val dayListStr = days.joinToString(", ")
        return """
            Task: Transcribe this school attendance sheet with 100% precision.

            You are given the FULL attendance sheet image. For this pass, ONLY
            report data for these exact day columns: $dayListStr
            Ignore all other day columns in the image — do not report them here.

            Requirements — return JSON with this shape:
            {
              "students": [
                { "rollNo": "...", "name": "...", "attendance": { "<day>": 0 or 1 } }
              ]
            }

            CRITICAL — DO NOT OMIT ANY DAY:
            For every student row, the "attendance" object MUST contain one key
            for EVERY day number listed above ($dayListStr) — no exceptions.
            - If the cell has any visible ink mark (dot, tick, circle, 'P'), the
              value is 1 (present).
            - If the cell is blank/empty or the mark is ambiguous, the value is
              0 (absent) — but you MUST still include the key. Never skip a day
              just because it looks blank. A missing key is treated as a bug.

            Key 'attendance' by the plain day number as a string, e.g. "1", "2".
            'rollNo' must be transcribed exactly as printed — read every digit
            carefully (e.g. "101" is NOT "1"; do not truncate leading digits).

            STRICT ACCURACY RULES:
            1. ROW COMPLETENESS: Include every student row visible in the image,
               in the exact order they appear. Do not skip or merge rows.
            2. COLUMN ALIGNMENT: Double-check each mark against its column
               header (the day number) before recording it. Do not shift
               columns.
            3. MARK DETECTION:
               - Present (1): any visible ink mark — dot, tick, circle, or 'P' —
                 even a small or light pen dot.
               - Absent (0): cell is visibly empty, OR the mark is an ambiguous
                 smudge/stray pen touch with no clear dot (prefer 0 if unsure).

            Return ONLY valid JSON, no commentary, no markdown fences.
        """.trimIndent()
    }

    private fun startGenAIProcess() {
        val bitmap = capturedBitmap ?: run {
            Log.e("ScanActivity", "AI Process aborted: capturedBitmap is null")
            return
        }
        setLoading(true, "AI is analyzing the sheet... (this may take a moment)")

        // FIX: dynamic split so this works for 28/29/30/31-day months, not
        // just a hardcoded "16".
        val maxDay = daysInMonth(scanYear, scanMonth)
        val splitPoint = ceil(maxDay / 2.0).toInt() // e.g. 31 -> 16, 28 -> 14
        val firstHalfDays = (1..splitPoint).toList()
        val secondHalfDays = (splitPoint + 1..maxDay).toList()

        lifecycleScope.launch {
            try {
                // FIX: full image sent both times — no cropping — so roll
                // number / name columns are never cut mid-digit.
                Log.d("ScanActivity", "Calling Gemini API (days ${firstHalfDays.first()}-${firstHalfDays.last()})...")
                val leftResponse = withContext(Dispatchers.IO) {
                    generativeModel.generateContent(content {
                        image(bitmap)
                        text(buildPrompt(firstHalfDays))
                    })
                }

                Log.d("ScanActivity", "Calling Gemini API (days ${secondHalfDays.first()}-${secondHalfDays.last()})...")
                val rightResponse = withContext(Dispatchers.IO) {
                    generativeModel.generateContent(content {
                        image(bitmap)
                        text(buildPrompt(secondHalfDays))
                    })
                }

                val leftText = leftResponse.text ?: ""
                val rightText = rightResponse.text ?: ""
                Log.d("ScanActivity", "Left raw: $leftText")
                Log.d("ScanActivity", "Right raw: $rightText")

                if (leftText.isEmpty() && rightText.isEmpty()) {
                    throw Exception("AI response was empty. Please check your API key and connection.")
                }

                val leftStudents = parseAiResponse(leftText, scanYear, scanMonth)
                val rightStudents = parseAiResponse(rightText, scanYear, scanMonth)

                setLoading(true, "Checking roll numbers against roster...")
                val validRollNumbers = loadValidRollNumbers()

                val merged = mergeStudentResults(leftStudents, rightStudents, validRollNumbers)

                // FIX: diagnostic — warn (per student) if the merged record
                // still doesn't cover the full month, so gaps are visible in
                // Logcat immediately instead of being discovered later in
                // Firestore.
                merged.forEach { s ->
                    if (s.attendance.size < maxDay) {
                        val gotDays = s.attendance.keys.map { it.substringAfterLast("-").toInt() }.sorted()
                        Log.w(
                            "ScanActivity",
                            "Roll ${s.rollNo}: only ${s.attendance.size}/$maxDay days captured. Got days: $gotDays"
                        )
                    }
                }

                setLoading(false)

                if (merged.isEmpty()) {
                    Log.w("ScanActivity", "No students parsed from JSON")
                    Toast.makeText(this@StudentAttendanceScanActivity, "AI read the sheet but found no valid records.", Toast.LENGTH_LONG).show()
                } else {
                    Log.d("ScanActivity", "Found ${merged.size} students")
                    if (leftStudents.size != rightStudents.size) {
                        Log.w("ScanActivity", "Row count mismatch between halves: left=${leftStudents.size} right=${rightStudents.size} — review carefully")
                    }
                    showConfirmationDialog(merged)
                }

            } catch (e: Exception) {
                Log.e("ScanActivity", "GenAI Error", e)
                setLoading(false)
                Toast.makeText(this@StudentAttendanceScanActivity, "AI Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Parses a single call's JSON response. Attendance keys are expected to be
     * plain day numbers (e.g. "1", "17"); they're converted here to real
     * 'YYYY-MM-DD' keys using the scan's known year/month, and validated
     * against the actual number of days in that month.
     */
    private fun parseAiResponse(jsonString: String, year: Int, month: Int): List<StudentResult> {
        val result = mutableListOf<StudentResult>()
        val maxDay = daysInMonth(year, month)
        try {
            val cleaned = jsonString.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

            val start = cleaned.indexOf("{")
            val end = cleaned.lastIndexOf("}")
            if (start == -1 || end == -1) {
                Log.e("ScanActivity", "No JSON brackets found in: $cleaned")
                return result
            }

            val cleanedJson = cleaned.substring(start, end + 1)
            val root = JSONObject(cleanedJson)
            val studentsArray = root.optJSONArray("students") ?: return result

            for (i in 0 until studentsArray.length()) {
                val sObj = studentsArray.getJSONObject(i)
                val roll = sObj.optString("rollNo", "").trim()
                val name = sObj.optString("name", "Unknown")
                val attObj = sObj.optJSONObject("attendance") ?: JSONObject()

                val attendanceMap = mutableMapOf<String, Int>()
                val keys = attObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val dayNum = key.filter { it.isDigit() }.toIntOrNull() ?: continue
                    if (dayNum < 1 || dayNum > maxDay) continue // drop hallucinated/out-of-range days
                    val value = attObj.get(key)
                    val status = if (value.toString() == "1" || value.toString().lowercase() == "true") 1 else 0
                    val dateKey = String.format(Locale.US, "%04d-%02d-%02d", year, month, dayNum)
                    attendanceMap[dateKey] = status
                }
                if (roll.isNotEmpty()) {
                    result.add(StudentResult(roll, name, attendanceMap))
                }
            }
        } catch (e: Exception) {
            Log.e("ScanActivity", "Parse error: ${e.message}")
        }
        return result
    }

    /**
     * Loads the known valid roll numbers for this class/school from Firestore,
     * so a misread roll number (e.g. AI reading "101" as "1") gets rejected
     * instead of silently creating a phantom student document. Returns an
     * empty set if the roster can't be loaded or doesn't exist yet — callers
     * should treat an empty set as "no filtering possible yet".
     */
    private suspend fun loadValidRollNumbers(): Set<String> {
        return try {
            val snapshot = suspendCancellableCoroutine<com.google.firebase.firestore.QuerySnapshot> { cont ->
                firestore.collection("students")
                    .whereEqualTo("class", className)
                    .whereEqualTo("schoolCode", schoolCode)
                    .get()
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
            snapshot.documents.map { it.id }.toSet()
        } catch (e: Exception) {
            Log.e("ScanActivity", "Roster load failed: ${e.message}")
            emptySet()
        }
    }

    /**
     * Merges the two half-sheet results by rollNo, combining attendance maps,
     * and rejects roll numbers that don't match the known class roster (when
     * one is available) to prevent misread digits from creating phantom
     * student documents like "1", "2", "3".
     */
    private fun mergeStudentResults(
        left: List<StudentResult>,
        right: List<StudentResult>,
        validRollNumbers: Set<String>
    ): List<StudentResult> {
        val byRoll = LinkedHashMap<String, StudentResult>()

        for (s in left + right) {
            if (validRollNumbers.isNotEmpty() && s.rollNo !in validRollNumbers) {
                Log.w("ScanActivity", "Rejected rollNo '${s.rollNo}' — not found in class roster (likely misread)")
                continue
            }
            val existing = byRoll[s.rollNo]
            byRoll[s.rollNo] = if (existing == null) {
                s
            } else {
                existing.copy(attendance = existing.attendance + s.attendance)
            }
        }

        // FIX: previously this filtered out any student whose attendance was
        // ALL zeros ("no marks == treat as blank row"), which silently
        // dropped legitimately fully-absent students from Firestore entirely.
        // We only want to drop rows that have literally NO data at all (e.g.
        // a stray misread row with an empty attendance map) — not rows that
        // are validly all-absent.
        return byRoll.values.filter { it.attendance.isNotEmpty() }
    }

    private fun showConfirmationDialog(students: List<StudentResult>) {
        AlertDialog.Builder(this)
            .setTitle("Confirm AI Data")
            .setMessage("${students.size} students identified for ${scanMonth}/${scanYear}. Save to database?")
            .setPositiveButton("Save") { _, _ -> saveToFirebase(students) }
            .setNegativeButton("Retake", null)
            .show()
    }

    /**
     * Saves each student's profile plus a per-month attendance record.
     * Schema:
     *   students/{rollNo}                                   -> profile (name, class, schoolCode)
     *   students/{rollNo}/attendanceRecords/{yyyy-MM}        -> one doc per month:
     *       days: { "1": 1, "2": 0, ... }   (plain day-of-month keys)
     *       presentCount, absentCount, totalMarkedDays       (human-readable summary)
     * This keeps each month self-contained and readable at a glance instead of
     * one ever-growing flat map of every date across all months/years.
     */
    private fun saveToFirebase(students: List<StudentResult>) {
        setLoading(true, "Syncing...")
        val batch = firestore.batch()
        val monthKey = String.format(Locale.US, "%04d-%02d", scanYear, scanMonth)

        for (s in students) {
            val studentRef = firestore.collection("students").document(s.rollNo)

            val profileData = hashMapOf<String, Any>(
                "name" to s.name,
                "rollNo" to (s.rollNo.toIntOrNull() ?: 0),
                "class" to className,
                "schoolCode" to schoolCode,
                "lastUpdated" to System.currentTimeMillis()
            )
            batch.set(studentRef, profileData, SetOptions.merge())

            val daysMap = hashMapOf<String, Any>()
            s.attendance.forEach { (fullDate, value) ->
                val dayNum = fullDate.substringAfterLast("-").toInt().toString()
                daysMap[dayNum] = value
            }
            val presentCount = s.attendance.values.count { it == 1 }
            val totalMarked = s.attendance.size

            val monthRef = studentRef.collection("attendanceRecords").document(monthKey)
            val monthData = hashMapOf<String, Any>(
                "days" to daysMap,
                "presentCount" to presentCount,
                "absentCount" to (totalMarked - presentCount),
                "totalMarkedDays" to totalMarked,
                "monthLabel" to monthKey,
                "lastUpdated" to System.currentTimeMillis()
            )
            batch.set(monthRef, monthData, SetOptions.merge())
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
