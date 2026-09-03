package com.school.attendance.database

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

data class LocalAttendanceRecord(
    val id: Long,
    val rollNo: String,
    val studentName: String,
    val className: String,
    val month: String,
    val year: Int,
    val daysCount: Int,
    val presentCount: Int,
    val absentCount: Int,
    val attendanceJson: String,
    val schoolName: String,
    val schoolCode: String,
    val teacherId: String,
    val savedTimestamp: Long,
    val isSynced: Boolean
)

data class LocalSaveResult(
    val insertedCount: Int,
    val updatedCount: Int,
    val totalProcessed: Int
)

/**
 * Local SQLite database helper to persist student attendance data directly on the Android device.
 * Enforces a strict 1-month retention policy by automatically purging records older than 30 days.
 */
class LocalAttendanceDbHelper(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {

    companion object {
        private const val TAG = "LOCAL_DB"
        private const val DATABASE_NAME = "school_attendance_local.db"
        private const val DATABASE_VERSION = 2

        const val TABLE_NAME = "student_attendance_local"
        const val COLUMN_ID = "id"
        const val COLUMN_ROLL_NO = "roll_no"
        const val COLUMN_STUDENT_NAME = "student_name"
        const val COLUMN_CLASS_NAME = "class_name"
        const val COLUMN_MONTH = "month"
        const val COLUMN_YEAR = "year"
        const val COLUMN_DAYS_COUNT = "days_count"
        const val COLUMN_PRESENT_COUNT = "present_count"
        const val COLUMN_ABSENT_COUNT = "absent_count"
        const val COLUMN_ATTENDANCE_JSON = "attendance_json"
        const val COLUMN_SCHOOL_NAME = "school_name"
        const val COLUMN_SCHOOL_CODE = "school_code"
        const val COLUMN_TEACHER_ID = "teacher_id"
        const val COLUMN_SAVED_TIMESTAMP = "saved_timestamp"
        const val COLUMN_IS_SYNCED = "is_synced"

        // 30 Days in Milliseconds (1 Month Retention)
        private const val ONE_MONTH_MILLIS = 30L * 24L * 60L * 60L * 1000L
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTableSql = """
            CREATE TABLE IF NOT EXISTS $TABLE_NAME (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_ROLL_NO TEXT NOT NULL,
                $COLUMN_STUDENT_NAME TEXT NOT NULL,
                $COLUMN_CLASS_NAME TEXT NOT NULL,
                $COLUMN_MONTH TEXT NOT NULL,
                $COLUMN_YEAR INTEGER NOT NULL,
                $COLUMN_DAYS_COUNT INTEGER NOT NULL,
                $COLUMN_PRESENT_COUNT INTEGER NOT NULL,
                $COLUMN_ABSENT_COUNT INTEGER NOT NULL,
                $COLUMN_ATTENDANCE_JSON TEXT NOT NULL,
                $COLUMN_SCHOOL_NAME TEXT,
                $COLUMN_SCHOOL_CODE TEXT,
                $COLUMN_TEACHER_ID TEXT,
                $COLUMN_SAVED_TIMESTAMP INTEGER NOT NULL,
                $COLUMN_IS_SYNCED INTEGER DEFAULT 0,
                UNIQUE($COLUMN_SCHOOL_CODE, $COLUMN_CLASS_NAME, $COLUMN_ROLL_NO, $COLUMN_MONTH, $COLUMN_YEAR)
            )
        """.trimIndent()
        db.execSQL(createTableSql)
        Log.i(TAG, "Initialized local SQLite table: $TABLE_NAME")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        try {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
        } catch (_: Exception) {}
        onCreate(db)
    }

    /**
     * Checks if a record exists with (schoolCode, className, rollNo, month, year).
     * If exists: updates existing record.
     * If not: inserts new record.
     * Returns LocalSaveResult with counts of inserted and updated records.
     */
    fun saveAttendanceRecords(
        records: List<Map<String, Any>>,
        className: String,
        month: String,
        year: Int,
        daysCount: Int,
        schoolName: String,
        schoolCode: String,
        teacherId: String
    ): LocalSaveResult {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        var insertedCount = 0
        var updatedCount = 0

        db.beginTransaction()
        try {
            for (item in records) {
                val rollNo = item["roll_no"]?.toString()?.trim() ?: ""
                val studentName = item["student_name"]?.toString()?.trim() ?: ""
                if (rollNo.isEmpty() && studentName.isEmpty()) continue

                val presentCount = (item["present_count"] as? Number)?.toInt() ?: 0
                val absentCount = (item["absent_count"] as? Number)?.toInt() ?: 0
                val attendanceJson = item["attendance_json"]?.toString() ?: "[]"

                // 1. Check if record already exists for this school_code, class_name, roll_no, month, year
                val checkCursor = db.query(
                    TABLE_NAME,
                    arrayOf(COLUMN_ID),
                    "$COLUMN_SCHOOL_CODE = ? AND $COLUMN_CLASS_NAME = ? AND $COLUMN_ROLL_NO = ? AND $COLUMN_MONTH = ? AND $COLUMN_YEAR = ?",
                    arrayOf(schoolCode, className, rollNo, month, year.toString()),
                    null,
                    null,
                    null
                )

                val exists = checkCursor.moveToFirst()
                val existingId = if (exists) checkCursor.getLong(0) else -1L
                checkCursor.close()

                if (exists && existingId != -1L) {
                    // Record exists -> UPDATE
                    val updateValues = ContentValues().apply {
                        put(COLUMN_STUDENT_NAME, studentName)
                        put(COLUMN_DAYS_COUNT, daysCount)
                        put(COLUMN_PRESENT_COUNT, presentCount)
                        put(COLUMN_ABSENT_COUNT, absentCount)
                        put(COLUMN_ATTENDANCE_JSON, attendanceJson)
                        put(COLUMN_SCHOOL_NAME, schoolName)
                        put(COLUMN_TEACHER_ID, teacherId)
                        put(COLUMN_SAVED_TIMESTAMP, now)
                        put(COLUMN_IS_SYNCED, 0)
                    }

                    db.update(
                        TABLE_NAME,
                        updateValues,
                        "$COLUMN_ID = ?",
                        arrayOf(existingId.toString())
                    )
                    updatedCount++
                    Log.d(TAG, "Updated existing local record: Roll $rollNo ($studentName)")
                } else {
                    // Record does not exist -> INSERT (Upload)
                    val insertValues = ContentValues().apply {
                        put(COLUMN_ROLL_NO, rollNo)
                        put(COLUMN_STUDENT_NAME, studentName)
                        put(COLUMN_CLASS_NAME, className)
                        put(COLUMN_MONTH, month)
                        put(COLUMN_YEAR, year)
                        put(COLUMN_DAYS_COUNT, daysCount)
                        put(COLUMN_PRESENT_COUNT, presentCount)
                        put(COLUMN_ABSENT_COUNT, absentCount)
                        put(COLUMN_ATTENDANCE_JSON, attendanceJson)
                        put(COLUMN_SCHOOL_NAME, schoolName)
                        put(COLUMN_SCHOOL_CODE, schoolCode)
                        put(COLUMN_TEACHER_ID, teacherId)
                        put(COLUMN_SAVED_TIMESTAMP, now)
                        put(COLUMN_IS_SYNCED, 0)
                    }

                    db.insert(TABLE_NAME, null, insertValues)
                    insertedCount++
                    Log.d(TAG, "Inserted new local record: Roll $rollNo ($studentName)")
                }
            }
            db.setTransactionSuccessful()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving records to local SQLite: ${e.message}", e)
        } finally {
            db.endTransaction()
        }

        // Automatic 1-month retention policy cleanup
        val purgedCount = purgeOldRecords()
        if (purgedCount > 0) {
            Log.i(TAG, "Retention Cleanup: Purged $purgedCount records older than 1 month")
        }

        return LocalSaveResult(
            insertedCount = insertedCount,
            updatedCount = updatedCount,
            totalProcessed = insertedCount + updatedCount
        )
    }

    /**
     * Enforces the 1-month retention policy: deletes any records saved more than 30 days ago.
     */
    fun purgeOldRecords(): Int {
        val cutoffTimestamp = System.currentTimeMillis() - ONE_MONTH_MILLIS
        val db = writableDatabase
        return try {
            db.delete(TABLE_NAME, "$COLUMN_SAVED_TIMESTAMP < ?", arrayOf(cutoffTimestamp.toString()))
        } catch (e: Exception) {
            Log.e(TAG, "Error purging old records: ${e.message}", e)
            0
        }
    }

    /**
     * Retrieves all unsynced records (is_synced = 0) for month-end sync to PostgreSQL.
     */
    fun getUnsyncedRecords(): List<LocalAttendanceRecord> {
        val list = mutableListOf<LocalAttendanceRecord>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_NAME,
            null,
            "$COLUMN_IS_SYNCED = 0",
            null,
            null,
            null,
            "$COLUMN_YEAR ASC, $COLUMN_MONTH ASC, $COLUMN_ROLL_NO ASC"
        )

        cursor.use { c ->
            val idIdx = c.getColumnIndexOrThrow(COLUMN_ID)
            val rollIdx = c.getColumnIndexOrThrow(COLUMN_ROLL_NO)
            val nameIdx = c.getColumnIndexOrThrow(COLUMN_STUDENT_NAME)
            val classIdx = c.getColumnIndexOrThrow(COLUMN_CLASS_NAME)
            val monthIdx = c.getColumnIndexOrThrow(COLUMN_MONTH)
            val yearIdx = c.getColumnIndexOrThrow(COLUMN_YEAR)
            val daysIdx = c.getColumnIndexOrThrow(COLUMN_DAYS_COUNT)
            val pIdx = c.getColumnIndexOrThrow(COLUMN_PRESENT_COUNT)
            val aIdx = c.getColumnIndexOrThrow(COLUMN_ABSENT_COUNT)
            val jsonIdx = c.getColumnIndexOrThrow(COLUMN_ATTENDANCE_JSON)
            val sNameIdx = c.getColumnIndexOrThrow(COLUMN_SCHOOL_NAME)
            val sCodeIdx = c.getColumnIndexOrThrow(COLUMN_SCHOOL_CODE)
            val tIdIdx = c.getColumnIndexOrThrow(COLUMN_TEACHER_ID)
            val timeIdx = c.getColumnIndexOrThrow(COLUMN_SAVED_TIMESTAMP)
            val syncIdx = c.getColumnIndexOrThrow(COLUMN_IS_SYNCED)

            while (c.moveToNext()) {
                list.add(
                    LocalAttendanceRecord(
                        id = c.getLong(idIdx),
                        rollNo = c.getString(rollIdx),
                        studentName = c.getString(nameIdx),
                        className = c.getString(classIdx),
                        month = c.getString(monthIdx),
                        year = c.getInt(yearIdx),
                        daysCount = c.getInt(daysIdx),
                        presentCount = c.getInt(pIdx),
                        absentCount = c.getInt(aIdx),
                        attendanceJson = c.getString(jsonIdx),
                        schoolName = c.getString(sNameIdx) ?: "",
                        schoolCode = c.getString(sCodeIdx) ?: "",
                        teacherId = c.getString(tIdIdx) ?: "",
                        savedTimestamp = c.getLong(timeIdx),
                        isSynced = c.getInt(syncIdx) == 1
                    )
                )
            }
        }
        return list
    }

    /**
     * Marks specific records as synced (is_synced = 1) after successful direct JDBC push.
     */
    fun markRecordsSynced(ids: List<Long>) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put(COLUMN_IS_SYNCED, 1)
            }
            for (id in ids) {
                db.update(TABLE_NAME, values, "$COLUMN_ID = ?", arrayOf(id.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Marks all records for a specific class/month/year as synced.
     */
    fun markMonthSynced(className: String, month: String, year: Int) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_IS_SYNCED, 1)
        }
        db.update(
            TABLE_NAME,
            values,
            "$COLUMN_CLASS_NAME = ? AND $COLUMN_MONTH = ? AND $COLUMN_YEAR = ?",
            arrayOf(className, month, year.toString())
        )
    }

    /**
     * Retrieves all saved records for a specific school, class, month, and year.
     */
    fun getRecordsForClass(schoolCode: String, className: String, month: String, year: Int): List<LocalAttendanceRecord> {
        val list = mutableListOf<LocalAttendanceRecord>()
        val db = readableDatabase
        val selection = if (schoolCode.isNotEmpty()) {
            "$COLUMN_SCHOOL_CODE = ? AND $COLUMN_CLASS_NAME = ? AND $COLUMN_MONTH = ? AND $COLUMN_YEAR = ?"
        } else {
            "$COLUMN_CLASS_NAME = ? AND $COLUMN_MONTH = ? AND $COLUMN_YEAR = ?"
        }
        val selectionArgs = if (schoolCode.isNotEmpty()) {
            arrayOf(schoolCode, className, month, year.toString())
        } else {
            arrayOf(className, month, year.toString())
        }

        val cursor = db.query(
            TABLE_NAME,
            null,
            selection,
            selectionArgs,
            null,
            null,
            "$COLUMN_ROLL_NO ASC"
        )

        cursor.use { c ->
            val idIdx = c.getColumnIndexOrThrow(COLUMN_ID)
            val rollIdx = c.getColumnIndexOrThrow(COLUMN_ROLL_NO)
            val nameIdx = c.getColumnIndexOrThrow(COLUMN_STUDENT_NAME)
            val classIdx = c.getColumnIndexOrThrow(COLUMN_CLASS_NAME)
            val monthIdx = c.getColumnIndexOrThrow(COLUMN_MONTH)
            val yearIdx = c.getColumnIndexOrThrow(COLUMN_YEAR)
            val daysIdx = c.getColumnIndexOrThrow(COLUMN_DAYS_COUNT)
            val pIdx = c.getColumnIndexOrThrow(COLUMN_PRESENT_COUNT)
            val aIdx = c.getColumnIndexOrThrow(COLUMN_ABSENT_COUNT)
            val jsonIdx = c.getColumnIndexOrThrow(COLUMN_ATTENDANCE_JSON)
            val sNameIdx = c.getColumnIndexOrThrow(COLUMN_SCHOOL_NAME)
            val sCodeIdx = c.getColumnIndexOrThrow(COLUMN_SCHOOL_CODE)
            val tIdIdx = c.getColumnIndexOrThrow(COLUMN_TEACHER_ID)
            val timeIdx = c.getColumnIndexOrThrow(COLUMN_SAVED_TIMESTAMP)
            val syncIdx = c.getColumnIndexOrThrow(COLUMN_IS_SYNCED)

            while (c.moveToNext()) {
                list.add(
                    LocalAttendanceRecord(
                        id = c.getLong(idIdx),
                        rollNo = c.getString(rollIdx),
                        studentName = c.getString(nameIdx),
                        className = c.getString(classIdx),
                        month = c.getString(monthIdx),
                        year = c.getInt(yearIdx),
                        daysCount = c.getInt(daysIdx),
                        presentCount = c.getInt(pIdx),
                        absentCount = c.getInt(aIdx),
                        attendanceJson = c.getString(jsonIdx),
                        schoolName = c.getString(sNameIdx) ?: "",
                        schoolCode = c.getString(sCodeIdx) ?: "",
                        teacherId = c.getString(tIdIdx) ?: "",
                        savedTimestamp = c.getLong(timeIdx),
                        isSynced = c.getInt(syncIdx) == 1
                    )
                )
            }
        }
        return list
    }

    /**
     * Returns the total count of locally stored records.
     */
    fun getTotalCount(): Int {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_NAME", null)
        var count = 0
        cursor.use {
            if (it.moveToFirst()) count = it.getInt(0)
        }
        return count
    }
}

