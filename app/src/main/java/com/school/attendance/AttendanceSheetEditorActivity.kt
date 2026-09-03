package com.school.attendance

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.school.attendance.database.DirectDbManager
import com.school.attendance.database.DirectUploadResult
import com.school.attendance.database.LocalAttendanceDbHelper
import com.school.attendance.databinding.ActivityAttendanceSheetEditorBinding
import com.school.attendance.network.AuthManager
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class AttendanceSheetEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAttendanceSheetEditorBinding

    companion object {
        var scannedDataHolder: List<Map<String, Any>>? = null
    }

    data class StudentRow(
        var rollNo: String,
        var name: String,
        val marks: MutableList<Int>,
        var presentCount: Int = 0,
        var absentCount: Int = 0
    )

    private val allStudents = mutableListOf<StudentRow>()
    private var totalDays = 31

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAttendanceSheetEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSynchronizedScrolling()
        loadInitialData()
        setupListeners()
        renderTable()
    }

    private fun setupSynchronizedScrolling() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            var isSyncingLeft = false
            var isSyncingRight = false

            binding.scrollLeftColumn.setOnScrollChangeListener { _, _, scrollY, _, _ ->
                if (!isSyncingLeft) {
                    isSyncingRight = true
                    binding.scrollRightColumn.scrollY = scrollY
                    isSyncingRight = false
                }
            }

            binding.scrollRightColumn.setOnScrollChangeListener { _, _, scrollY, _, _ ->
                if (!isSyncingRight) {
                    isSyncingLeft = true
                    binding.scrollLeftColumn.scrollY = scrollY
                    isSyncingLeft = false
                }
            }
        }
    }

    private fun loadInitialData() {
        val incoming = scannedDataHolder
        val cal = Calendar.getInstance()
        val monthStr = intent.getStringExtra("month") ?: SimpleDateFormat("MMMM", Locale.getDefault()).format(cal.time)
        val yearVal = intent.getIntExtra("year", cal.get(Calendar.YEAR))
        val className = intent.getStringExtra("className") ?: "5A"
        val schoolCode = AuthManager.getSchoolCode()

        if (incoming != null && incoming.isNotEmpty()) {
            allStudents.clear()
            incoming.forEach { map ->
                val roll = map["rollNo"]?.toString() ?: ""
                val name = map["name"]?.toString() ?: ""
                val rawMarks = map["attendance"] as? List<*> ?: emptyList<Any>()
                val marksList = mutableListOf<Int>()

                for (m in rawMarks) {
                    when (m) {
                        is Number -> marksList.add(if (m.toInt() == 1) 1 else 0)
                        is String -> marksList.add(if (m.equals("P", true) || m == "1") 1 else 0)
                        else -> marksList.add(0)
                    }
                }

                while (marksList.size < totalDays) {
                    marksList.add(0)
                }

                val pCnt = marksList.take(totalDays).count { it == 1 }
                val aCnt = totalDays - pCnt

                allStudents.add(
                    StudentRow(
                        rollNo = roll,
                        name = name,
                        marks = marksList,
                        presentCount = pCnt,
                        absentCount = aCnt
                    )
                )
            }
        } else {
            // Check if existing records are already saved in local SQLite for this school, class, month, year
            val localDb = LocalAttendanceDbHelper(this)
            val savedRecords = localDb.getRecordsForClass(schoolCode, className, monthStr, yearVal)

            if (savedRecords.isNotEmpty()) {
                allStudents.clear()
                savedRecords.forEach { rec ->
                    val marksList = mutableListOf<Int>()
                    try {
                        val jsonArr = JSONArray(rec.attendanceJson)
                        for (idx in 0 until jsonArr.length()) {
                            marksList.add(jsonArr.optInt(idx, 0))
                        }
                    } catch (_: Exception) {}

                    while (marksList.size < totalDays) {
                        marksList.add(0)
                    }

                    val pCnt = marksList.take(totalDays).count { it == 1 }
                    val aCnt = totalDays - pCnt

                    allStudents.add(
                        StudentRow(
                            rollNo = rec.rollNo,
                            name = rec.studentName,
                            marks = marksList,
                            presentCount = pCnt,
                            absentCount = aCnt
                        )
                    )
                }
            } else {
                // Default sample data
                allStudents.clear()
                for (i in 1..25) {
                    val roll = (100 + i).toString()
                    val name = "Student $roll"
                    val marks = MutableList(totalDays) { 1 }
                    allStudents.add(StudentRow(roll, name, marks, totalDays, 0))
                }
            }
        }
        updateHeaderBadge()
    }

    private fun updateHeaderBadge() {
        binding.tvStudentCountBadge.text = "${allStudents.size} Students"
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        val teacherName = com.school.attendance.network.AuthManager.getTeacherName()
        binding.tvAvatarInitial.text = teacherName.trim().firstOrNull()?.uppercase() ?: "T"
        binding.btnProfileAvatar.setOnClickListener {
            com.school.attendance.dialogs.TeacherProfileDialog.show(this)
        }

        // Search Filter
        binding.etSearchStudent.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                renderTable(s?.toString()?.trim() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Add Student Button
        binding.btnAddStudent.setOnClickListener {
            showAddStudentDialog()
        }

        // Save Local Button (Replaced Refresh -> saves to device SQLite)
        binding.btnSaveLocal.setOnClickListener {
            saveToLocalSqlite()
        }

        // CSV Download Button
        binding.btnDownloadCsv.setOnClickListener {
            exportAndShareCsv()
        }

        // Upload to PostgreSQL Button (Direct JDBC, with Lock/Unlock State)
        updateDatabaseButtonUI()
        binding.btnUpdateDatabase.setOnClickListener {
            checkAndUploadDatabase()
        }
    }

    private fun renderTable(filterQuery: String = "") {
        binding.containerLeftRows.removeAllViews()
        binding.containerRightHeader.removeAllViews()
        binding.containerRightRows.removeAllViews()

        val filteredList = if (filterQuery.isEmpty()) {
            allStudents
        } else {
            allStudents.filter {
                it.rollNo.contains(filterQuery, ignoreCase = true) ||
                it.name.contains(filterQuery, ignoreCase = true)
            }
        }

        // 1. Build Right Header
        for (d in 1..totalDays) {
            val tvDayHeader = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(34), dpToPx(40))
                text = d.toString()
                textSize = 11f
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
            }
            binding.containerRightHeader.addView(tvDayHeader)
        }

        // P and A Header
        val tvPHeader = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(40), dpToPx(40))
            text = "P"
            textSize = 12f
            setTextColor(Color.parseColor("#86EFAC"))
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        val tvAHeader = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(40), dpToPx(40))
            text = "A"
            textSize = 12f
            setTextColor(Color.parseColor("#FCA5A5"))
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        val tvDelHeader = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(36), dpToPx(40))
            text = ""
            gravity = Gravity.CENTER
        }
        binding.containerRightHeader.addView(tvPHeader)
        binding.containerRightHeader.addView(tvAHeader)
        binding.containerRightHeader.addView(tvDelHeader)

        // 2. Build Student Rows (Left and Right synchronized)
        filteredList.forEachIndexed { sIdx, student ->
            val actualIdx = allStudents.indexOf(student)
            val rowBgColor = if (sIdx % 2 == 0) Color.WHITE else Color.parseColor("#F8FAFD")
            val rowHeightPx = dpToPx(44)

            // --- LEFT ROW: Roll + Name ---
            val leftRow = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, rowHeightPx)
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(rowBgColor)
                gravity = Gravity.CENTER_VERTICAL
            }

            val etRoll = EditText(this).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(55), LinearLayout.LayoutParams.MATCH_PARENT)
                setText(student.rollNo)
                textSize = 12f
                setTextColor(Color.parseColor("#1A237E"))
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setBackgroundColor(Color.TRANSPARENT)
                inputType = InputType.TYPE_CLASS_NUMBER
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        student.rollNo = s?.toString()?.trim() ?: ""
                    }
                    override fun afterTextChanged(s: Editable?) {}
                })
            }

            val vSep = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(1), dpToPx(24))
                setBackgroundColor(Color.parseColor("#E2E8F0"))
            }

            val etName = EditText(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                setText(student.name)
                textSize = 12f
                setTextColor(Color.parseColor("#1C1C1E"))
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(dpToPx(8), 0, dpToPx(4), 0)
                setBackgroundColor(Color.TRANSPARENT)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        student.name = s?.toString()?.trim() ?: ""
                    }
                    override fun afterTextChanged(s: Editable?) {}
                })
            }

            leftRow.addView(etRoll)
            leftRow.addView(vSep)
            leftRow.addView(etName)

            val leftDivider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1))
                setBackgroundColor(Color.parseColor("#F1F5F9"))
            }
            binding.containerLeftRows.addView(leftRow)
            binding.containerLeftRows.addView(leftDivider)

            // --- RIGHT ROW: Days 1..31 + P + A + Delete ---
            val rightRow = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, rowHeightPx)
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(rowBgColor)
                gravity = Gravity.CENTER_VERTICAL
            }

            val tvPresentCount = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(40), LinearLayout.LayoutParams.MATCH_PARENT)
                text = student.presentCount.toString()
                textSize = 13f
                setTextColor(Color.parseColor("#1B5E20"))
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
            }

            val tvAbsentCount = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(40), LinearLayout.LayoutParams.MATCH_PARENT)
                text = student.absentCount.toString()
                textSize = 13f
                setTextColor(Color.parseColor("#B71C1C"))
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
            }

            for (d in 0 until totalDays) {
                val isPresent = student.marks[d] == 1
                val tvDay = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dpToPx(30), dpToPx(30)).apply {
                        setMargins(dpToPx(2), 0, dpToPx(2), 0)
                    }
                    text = if (isPresent) "P" else "A"
                    textSize = 11f
                    setTypeface(null, Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setTextColor(if (isPresent) Color.parseColor("#1B5E20") else Color.parseColor("#B71C1C"))
                    background = ContextCompat.getDrawable(
                        this@AttendanceSheetEditorActivity,
                        if (isPresent) R.drawable.bg_cell_present else R.drawable.bg_cell_absent
                    )

                    setOnClickListener {
                        // Toggle state
                        val current = student.marks[d]
                        val next = if (current == 1) 0 else 1
                        student.marks[d] = next

                        val nowPresent = next == 1
                        text = if (nowPresent) "P" else "A"
                        setTextColor(if (nowPresent) Color.parseColor("#1B5E20") else Color.parseColor("#B71C1C"))
                        background = ContextCompat.getDrawable(
                            this@AttendanceSheetEditorActivity,
                            if (nowPresent) R.drawable.bg_cell_present else R.drawable.bg_cell_absent
                        )

                        // Update P and A counts live
                        student.presentCount = student.marks.take(totalDays).count { it == 1 }
                        student.absentCount = totalDays - student.presentCount

                        tvPresentCount.text = student.presentCount.toString()
                        tvAbsentCount.text = student.absentCount.toString()
                    }
                }
                rightRow.addView(tvDay)
            }

            rightRow.addView(tvPresentCount)
            rightRow.addView(tvAbsentCount)

            // Delete button
            val btnDelete = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(36), dpToPx(30)).apply {
                    setMargins(dpToPx(2), 0, dpToPx(4), 0)
                }
                setImageResource(R.drawable.ic_delete)
                setColorFilter(Color.parseColor("#94A3B8"))
                setPadding(dpToPx(6), dpToPx(6), dpToPx(6), dpToPx(6))
                background = ContextCompat.getDrawable(
                    this@AttendanceSheetEditorActivity,
                    android.R.drawable.list_selector_background
                )
                setOnClickListener {
                    AlertDialog.Builder(this@AttendanceSheetEditorActivity)
                        .setTitle("Remove Student")
                        .setMessage("Are you sure you want to remove ${student.name} (Roll: ${student.rollNo})?")
                        .setPositiveButton("Remove") { _, _ ->
                            allStudents.removeAt(actualIdx)
                            updateHeaderBadge()
                            renderTable(binding.etSearchStudent.text.toString().trim())
                            Toast.makeText(this@AttendanceSheetEditorActivity, "Removed student", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            rightRow.addView(btnDelete)

            val rightDivider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dpToPx(1))
                setBackgroundColor(Color.parseColor("#F1F5F9"))
            }

            binding.containerRightRows.addView(rightRow)
            binding.containerRightRows.addView(rightDivider)
        }
    }

    private fun showAddStudentDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Add New Student")

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(20), dpToPx(10), dpToPx(20), dpToPx(10))
        }

        val maxRoll = allStudents.mapNotNull { it.rollNo.toIntOrNull() }.maxOrNull() ?: 100
        val etRoll = EditText(this).apply {
            hint = "Roll Number"
            setText((maxRoll + 1).toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val etName = EditText(this).apply {
            hint = "Student Full Name (e.g. Rahul Verma)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }

        layout.addView(etRoll)
        layout.addView(etName)
        builder.setView(layout)

        builder.setPositiveButton("Add") { _, _ ->
            val roll = etRoll.text.toString().trim()
            val name = etName.text.toString().trim()
            if (roll.isNotEmpty() && name.isNotEmpty()) {
                val newMarks = MutableList(totalDays) { 1 }
                allStudents.add(StudentRow(roll, name, newMarks, totalDays, 0))
                updateHeaderBadge()
                renderTable(binding.etSearchStudent.text.toString().trim())
                Toast.makeText(this, "Added $name", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Please fill Roll No and Name", Toast.LENGTH_SHORT).show()
            }
        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }

    private fun exportAndShareCsv() {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val filename = "Monthly_Attendance_$timestamp.csv"
            val file = File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), filename)

            Log.i("DATA_LOG", "==========================================================================================")
            Log.i("DATA_LOG", "📄 [CSV EXPORT] Generating Attendance CSV for ${allStudents.size} students...")
            Log.i("DATA_LOG", "📁 Target Path: ${file.absolutePath}")
            Log.i("DATA_LOG", "------------------------------------------------------------------------------------------")
            Log.i("DATA_LOG", String.format("%-8s %-20s %-8s %-8s %s", "Roll No", "Student Name", "Present", "Absent", "Days (1..$totalDays)"))
            Log.i("DATA_LOG", "------------------------------------------------------------------------------------------")

            val writer = FileOutputStream(file)
            val header = StringBuilder("Roll No,Student Name,Present,Absent")
            for (d in 1..totalDays) {
                header.append(",D$d")
            }
            header.append("\n")
            writer.write(header.toString().toByteArray())

            allStudents.forEach { s ->
                val row = StringBuilder("\"${s.rollNo}\",\"${s.name}\",${s.presentCount},${s.absentCount}")
                val markStr = StringBuilder()
                for (d in 0 until totalDays) {
                    val m = if (s.marks[d] == 1) "P" else "A"
                    row.append(",$m")
                    markStr.append("$m ")
                }
                row.append("\n")
                writer.write(row.toString().toByteArray())
                Log.i("DATA_LOG", String.format("%-8s %-20s %-8d %-8d %s", s.rollNo, s.name, s.presentCount, s.absentCount, markStr.toString().trim()))
            }
            writer.flush()
            writer.close()

            Log.i("DATA_LOG", "------------------------------------------------------------------------------------------")
            Log.i("DATA_LOG", "✅ [CSV EXPORT COMPLETE] Successfully written ${allStudents.size} records to: ${file.name}")
            Log.i("DATA_LOG", "==========================================================================================")

            // Open Share Intent
            val uri: Uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share Attendance CSV"))
            Toast.makeText(this, "CSV Generated: ${file.name}", Toast.LENGTH_LONG).show()

        } catch (e: Exception) {
            Log.e("DATA_LOG", "❌ [CSV EXPORT ERROR] ${e.message}", e)
            Toast.makeText(this, "CSV Export Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveToLocalSqlite() {
        val teacherId = AuthManager.getTeacherCode().ifEmpty { AuthManager.getUserId() ?: "offline_teacher" }
        val schoolName = AuthManager.getSchoolName()
        val schoolCode = AuthManager.getSchoolCode()
        val cal = Calendar.getInstance()
        val monthStr = intent.getStringExtra("month") ?: SimpleDateFormat("MMMM", Locale.getDefault()).format(cal.time)
        val yearVal = intent.getIntExtra("year", cal.get(Calendar.YEAR))
        val className = intent.getStringExtra("className") ?: "5A"

        Log.i("DATA_LOG", "==========================================================================================")
        Log.i("DATA_LOG", "💾 [SQLITE SAVE/UPDATE] Storing ${allStudents.size} student records in local SQLite database...")
        Log.i("DATA_LOG", "📅 Metadata: School=$schoolCode ($schoolName) | Class=$className | Month=$monthStr $yearVal | Teacher=$teacherId")
        Log.i("DATA_LOG", "------------------------------------------------------------------------------------------")
        Log.i("DATA_LOG", String.format("%-8s %-20s %-8s %-8s %s", "Roll No", "Student Name", "Present", "Absent", "Attendance JSON"))
        Log.i("DATA_LOG", "------------------------------------------------------------------------------------------")

        val records = allStudents.map { s ->
            val attArr = JSONArray()
            s.marks.forEach { attArr.put(it) }
            val attJson = attArr.toString()
            Log.i("DATA_LOG", String.format("%-8s %-20s %-8d %-8d %s", s.rollNo, s.name, s.presentCount, s.absentCount, attJson))
            mapOf(
                "roll_no" to s.rollNo,
                "student_name" to s.name,
                "present_count" to s.presentCount,
                "absent_count" to s.absentCount,
                "attendance_json" to attJson
            )
        }

        val localDb = LocalAttendanceDbHelper(this)
        val result = localDb.saveAttendanceRecords(
            records = records,
            className = className,
            month = monthStr,
            year = yearVal,
            daysCount = totalDays,
            schoolName = schoolName,
            schoolCode = schoolCode,
            teacherId = teacherId
        )

        Log.i("DATA_LOG", "------------------------------------------------------------------------------------------")
        Log.i("DATA_LOG", "✅ [SQLITE SAVE RESULT] Total Processed: ${result.totalProcessed} | Updated: ${result.updatedCount} | Uploaded (New): ${result.insertedCount}")
        Log.i("DATA_LOG", "==========================================================================================")

        val greenColor = Color.parseColor("#16A34A") // Emerald Green

        if (result.updatedCount > 0 && result.insertedCount == 0) {
            // All records updated
            binding.btnSaveLocal.apply {
                text = "Updated"
                backgroundTintList = ColorStateList.valueOf(greenColor)
                setIconResource(R.drawable.ic_check)
            }
            Toast.makeText(this, "✅ Updated ${result.updatedCount} records in local SQLite!", Toast.LENGTH_SHORT).show()
        } else if (result.insertedCount > 0 && result.updatedCount == 0) {
            // All records newly uploaded / inserted
            binding.btnSaveLocal.apply {
                text = "Uploaded"
                backgroundTintList = ColorStateList.valueOf(greenColor)
                setIconResource(R.drawable.ic_check)
            }
            Toast.makeText(this, "✅ Uploaded ${result.insertedCount} records to local SQLite!", Toast.LENGTH_SHORT).show()
        } else if (result.updatedCount > 0 && result.insertedCount > 0) {
            // Mixed (some updated, some inserted)
            binding.btnSaveLocal.apply {
                text = "Updated"
                backgroundTintList = ColorStateList.valueOf(greenColor)
                setIconResource(R.drawable.ic_check)
            }
            Toast.makeText(this, "✅ Updated: ${result.updatedCount} | Uploaded: ${result.insertedCount}", Toast.LENGTH_SHORT).show()
        } else {
            binding.btnSaveLocal.apply {
                text = "Saved"
                backgroundTintList = ColorStateList.valueOf(greenColor)
                setIconResource(R.drawable.ic_check)
            }
        }

        AlertDialog.Builder(this)
            .setTitle(if (result.updatedCount > 0) "Data Updated ✅" else "Data Uploaded ✅")
            .setMessage(
                "Total records processed: ${result.totalProcessed}\n\n" +
                "• Updated: ${result.updatedCount}\n" +
                "• Uploaded (New): ${result.insertedCount}\n\n" +
                "📅 1-Month Retention: Records older than 30 days are automatically pruned.\n\n" +
                "🔄 Sync: Local data will sync to central database at month-end."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun updateDatabaseButtonUI() {
        val isWindowOpen = DirectDbManager.isUploadWindowOpen() || DirectDbManager.isMonthEndWindow()
        if (isWindowOpen) {
            // Upload is UNLOCKED (Days 1 to 5 or Month-End)
            binding.btnUpdateDatabase.apply {
                setIconResource(R.drawable.ic_lock_open)
                text = "Upload to DB"
                backgroundTintList = ColorStateList.valueOf(Color.parseColor("#1D4ED8")) // Active Primary Blue
            }
        } else {
            // Upload is LOCKED (Days 6 onwards until Month-End)
            binding.btnUpdateDatabase.apply {
                setIconResource(R.drawable.ic_lock)
                text = "Upload (Locked)"
                backgroundTintList = ColorStateList.valueOf(Color.parseColor("#475569")) // Slate Gray Locked
            }
        }
    }

    private fun checkAndUploadDatabase() {
        val currentDay = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
        val isWindowOpen = DirectDbManager.isUploadWindowOpen()
        val isMonthEnd = DirectDbManager.isMonthEndWindow()

        if (isWindowOpen) {
            // Days 1 to 5: Upload window is open (Unlocked)
            performDirectPostgresUpload(bypassWindow = false)
        } else if (isMonthEnd) {
            // Month-end window (Unlocked)
            AlertDialog.Builder(this)
                .setTitle("Month-End Sync 🔓")
                .setMessage("Month-End Sync window is active (Day $currentDay).\n\nDo you want to sync all student records directly to central PostgreSQL database?")
                .setPositiveButton("Sync Now") { _, _ ->
                    performDirectPostgresUpload(bypassWindow = true)
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            // Days 6 to (Month-End - 5): Upload is LOCKED
            AlertDialog.Builder(this)
                .setTitle("Upload is Locked 🔒")
                .setMessage(
                    "Central PostgreSQL Upload is locked right now.\n\n" +
                    "It automatically unlocks during:\n" +
                    "• Days 1 to 5 of the month (Starting 5 days)\n" +
                    "• Month-End Sync Window (Last 5 days)\n\n" +
                    "Today is Day $currentDay. Your data is safely saved in local SQLite storage."
                )
                .setPositiveButton("OK", null)
                .setNeutralButton("Force Unlock") { _, _ ->
                    performDirectPostgresUpload(bypassWindow = true)
                }
                .show()
        }
    }

    private fun performDirectPostgresUpload(bypassWindow: Boolean) {
        val teacherId = AuthManager.getTeacherCode().ifEmpty { AuthManager.getUserId() ?: "offline_teacher" }
        val schoolName = AuthManager.getSchoolName()
        val schoolCode = AuthManager.getSchoolCode()
        val cal = Calendar.getInstance()
        val monthStr = intent.getStringExtra("month") ?: SimpleDateFormat("MMMM", Locale.getDefault()).format(cal.time)
        val yearVal = intent.getIntExtra("year", cal.get(Calendar.YEAR))
        val className = intent.getStringExtra("className") ?: "5A"

        Log.i("DATA_LOG", "==========================================================================================")
        Log.i("DATA_LOG", "🚀 [POSTGRES DIRECT UPLOAD] Starting PostgreSQL direct upload for ${allStudents.size} students (NO API)...")
        Log.i("DATA_LOG", "📅 Metadata: School=$schoolCode ($schoolName) | Class=$className | Month=$monthStr $yearVal | Teacher=$teacherId")
        Log.i("DATA_LOG", "------------------------------------------------------------------------------------------")
        Log.i("DATA_LOG", String.format("%-8s %-20s %-8s %-8s %s", "Roll No", "Student Name", "Present", "Absent", "Target Table"))
        Log.i("DATA_LOG", "------------------------------------------------------------------------------------------")

        val progressDialog = android.app.ProgressDialog(this).apply {
            setMessage("Uploading ${allStudents.size} records directly to PostgreSQL (No API)...")
            setCancelable(false)
            show()
        }

        Thread {
            val records = allStudents.map { s ->
                val attArr = JSONArray()
                s.marks.forEach { attArr.put(it) }
                Log.i("DATA_LOG", String.format("%-8s %-20s %-8d %-8d %s", s.rollNo, s.name, s.presentCount, s.absentCount, "student_attendance (UPSERT)"))
                mapOf(
                    "roll_no" to s.rollNo,
                    "student_name" to s.name,
                    "present_count" to s.presentCount,
                    "absent_count" to s.absentCount,
                    "attendance_json" to attArr.toString()
                )
            }

            // Direct JDBC upload without any API call
            val result = DirectDbManager.uploadStudentAttendanceDirect(
                records = records,
                className = className,
                month = monthStr,
                year = yearVal,
                daysCount = totalDays,
                schoolName = schoolName,
                schoolCode = schoolCode,
                teacherId = teacherId,
                context = this,
                bypassWindowCheck = bypassWindow
            )

            runOnUiThread {
                progressDialog.dismiss()
                when (result) {
                    is DirectUploadResult.Success -> {
                        Log.i("DATA_LOG", "------------------------------------------------------------------------------------------")
                        Log.i("DATA_LOG", "✅ [POSTGRES SUCCESS] Successfully uploaded/upserted ${result.count} records to PostgreSQL table 'student_attendance'!")
                        Log.i("DATA_LOG", "==========================================================================================")
                        AlertDialog.Builder(this)
                            .setTitle("Database Updated Directly ✅")
                            .setMessage("${result.message}\n\nTotal: ${result.count} student records saved directly to PostgreSQL (No API).")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                    is DirectUploadResult.WindowClosed -> {
                        Log.i("DATA_LOG", "------------------------------------------------------------------------------------------")
                        Log.i("DATA_LOG", "⏳ [POSTGRES WINDOW CLOSED] ${result.message}")
                        Log.i("DATA_LOG", "==========================================================================================")
                        AlertDialog.Builder(this)
                            .setTitle("Upload Window Restricted ⏳")
                            .setMessage(result.message)
                            .setPositiveButton("OK", null)
                            .show()
                    }
                    is DirectUploadResult.Error -> {
                        Log.e("DATA_LOG", "------------------------------------------------------------------------------------------")
                        Log.e("DATA_LOG", "❌ [POSTGRES ERROR] ${result.message}")
                        Log.i("DATA_LOG", "==========================================================================================")
                        AlertDialog.Builder(this)
                            .setTitle("Database Error ❌")
                            .setMessage(result.message)
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            }
        }.start()
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}
