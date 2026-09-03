package com.school.attendance.database

import android.content.Context
import android.util.Log
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp
import java.util.Calendar
import java.util.Date
import kotlin.random.Random

data class TeacherProfile(
    val teacherCode: String,
    val username: String,
    val fullName: String,
    val mobileNo: String,
    val udiseId: String,
    val schoolName: String,
    val schoolCode: String,
    val designation: String,
    val tokenExpiryMillis: Long = System.currentTimeMillis() + (7L * 24 * 60 * 60 * 1000L)
)

sealed class TokenResult {
    data class Success(val token: String, val teacherCode: String, val teacherName: String) : TokenResult()
    data class Error(val message: String) : TokenResult()
}

sealed class LoginResult {
    data class Success(val profile: TeacherProfile) : LoginResult()
    data class TokenRequired(val message: String) : LoginResult()
    data class Error(val message: String) : LoginResult()
}

sealed class RegisterResult {
    data class Success(val token: String, val profile: TeacherProfile) : RegisterResult()
    data class Error(val message: String) : RegisterResult()
}

sealed class DirectUploadResult {
    data class Success(val count: Int, val message: String) : DirectUploadResult()
    data class Error(val message: String) : DirectUploadResult()
    data class WindowClosed(val message: String) : DirectUploadResult()
}

object DirectDbManager {
    private const val TAG = "DirectDbManager"
    private const val PREFS_NAME = "direct_db_prefs"
    private const val KEY_LAST_HOST = "last_db_host"

    // Default PostgreSQL DB Credentials
    private const val DB_PORT = 5432
    private const val DB_NAME = "AttendenceSystem"
    private const val DB_USER = "postgres"
    private const val DB_PASS = "1502"

    // Candidate network addresses to connect to host PC PostgreSQL
    private val CANDIDATE_HOSTS = listOf(
        "127.0.0.1",       // USB Cable via ADB reverse (Primary)
        "10.50.26.212",    // Current Wi-Fi LAN IP (IIT Bhilai)
        "192.168.137.1",   // Windows Mobile Hotspot Gateway
        "10.0.2.2",        // Android Emulator Gateway
        "192.168.43.1",    // Phone Hotspot Gateway
        "10.169.144.54",   // Previous Wi-Fi IP
        "localhost"
    )

    private var cachedHost: String? = null

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        cachedHost = prefs.getString(KEY_LAST_HOST, null)
    }

    private fun saveSuccessfulHost(context: Context?, host: String) {
        cachedHost = host
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()?.putString(KEY_LAST_HOST, host)?.apply()
    }

    /**
     * Obtains a direct PostgreSQL JDBC connection by testing candidate hosts
     */
    fun getConnection(context: Context? = null): Connection {
        try {
            Class.forName("org.postgresql.Driver")
        } catch (e: ClassNotFoundException) {
            Log.e(TAG, "PostgreSQL Driver not found: ${e.message}")
            throw RuntimeException("PostgreSQL JDBC driver not available", e)
        }

        val hostsToTry = mutableListOf<String>()
        cachedHost?.let { hostsToTry.add(it) }
        CANDIDATE_HOSTS.forEach { if (it != cachedHost) hostsToTry.add(it) }

        var lastException: Exception? = null
        for (host in hostsToTry) {
            val url = "jdbc:postgresql://$host:$DB_PORT/$DB_NAME?sslmode=disable&connectTimeout=4&socketTimeout=10"
            try {
                Log.d(TAG, "Attempting direct PostgreSQL connection to $host:$DB_PORT...")
                val conn = DriverManager.getConnection(url, DB_USER, DB_PASS)
                if (conn != null && !conn.isClosed) {
                    Log.i(TAG, "✅ Direct PostgreSQL connection SUCCESSFUL via $host!")
                    saveSuccessfulHost(context, host)
                    return conn
                }
            } catch (e: Exception) {
                Log.w(TAG, "Connection failed for $host: ${e.message}")
                lastException = e
            }
        }
        throw lastException ?: RuntimeException("Could not connect to PostgreSQL database on any network address")
    }

    /**
     * Generates a security token for an authorized teacher in mst_teacher.
     * If user is not found or invalid -> returns "You aren't Authorized".
     * If valid -> generates token, updates mst_teacher, inserts into admin_tokens, and returns the token.
     */
    fun generateTeacherToken(usernameOrCode: String, passwordInput: String, context: Context? = null): TokenResult {
        val trimmedIdentifier = usernameOrCode.trim()
        val trimmedPassword = passwordInput.trim()

        if (trimmedIdentifier.isEmpty() || trimmedPassword.isEmpty()) {
            return TokenResult.Error("Username and password are required")
        }

        var conn: Connection? = null
        try {
            conn = getConnection(context)
            conn.autoCommit = false

            // 1. Search teacher in mst_teacher
            val selectSql = """
                SELECT teacher_code, username, password_hash, mobile_no, name_eng, name_hin, 
                       designation_name_eng, udise_id, current_udise_id, status
                FROM mst_teacher
                WHERE (LOWER(username) = LOWER(?) OR LOWER(teacher_code) = LOWER(?) OR CAST(mobile_no AS TEXT) = ?)
                  AND (status IS NULL OR status = true)
                LIMIT 1
            """.trimIndent()

            val ps = conn.prepareStatement(selectSql)
            ps.setString(1, trimmedIdentifier)
            ps.setString(2, trimmedIdentifier)
            ps.setString(3, trimmedIdentifier)

            val rs = ps.executeQuery()
            if (!rs.next()) {
                rs.close()
                ps.close()
                return TokenResult.Error("You aren't Authorized")
            }

            val teacherCode = rs.getString("teacher_code") ?: ""
            val storedUsername = rs.getString("username")
            val storedPasswordHash = rs.getString("password_hash")
            val mobileNo = rs.getLong("mobile_no")
            val nameEng = rs.getString("name_eng") ?: rs.getString("name_hin") ?: teacherCode
            rs.close()
            ps.close()

            // 2. Validate Password
            val isPasswordValid = verifyPassword(trimmedPassword, storedPasswordHash, teacherCode, mobileNo)
            if (!isPasswordValid) {
                return TokenResult.Error("You aren't Authorized")
            }

            // If password_hash was not set, store hash now for future logins
            if (storedPasswordHash.isNullOrEmpty()) {
                val newHash = md5Hex(trimmedPassword)
                val updatePwdSql = "UPDATE mst_teacher SET password_hash = ? WHERE teacher_code = ?"
                val pwdStmt = conn.prepareStatement(updatePwdSql)
                pwdStmt.setString(1, newHash)
                pwdStmt.setString(2, teacherCode)
                pwdStmt.executeUpdate()
                pwdStmt.close()
            }

            // 3. Generate Random Token (Format: AT-XXXX-XXXX)
            val randomPart1 = "%04X".format(Random.nextInt(0x10000))
            val randomPart2 = "%04X".format(Random.nextInt(0x10000))
            val tokenPlain = "AT-$randomPart1-$randomPart2"
            val tokenHash = md5Hex(tokenPlain)

            val now = Timestamp(System.currentTimeMillis())
            val sevenDaysLater = Timestamp(System.currentTimeMillis() + (7L * 24 * 60 * 60 * 1000))

            // 4. Update existing active tokens in admin_tokens to inactive
            val deactivateSql = "UPDATE admin_tokens SET is_active = FALSE WHERE teacher_code = ? AND is_active = TRUE"
            val deactStmt = conn.prepareStatement(deactivateSql)
            deactStmt.setString(1, teacherCode)
            deactStmt.executeUpdate()
            deactStmt.close()

            // 5. Insert new record into admin_tokens table
            val insertTokenSql = """
                INSERT INTO admin_tokens (teacher_code, username, token_plain, token_hash, token_valid_from, token_valid_until, is_active, created_at)
                VALUES (?, ?, ?, ?, ?, ?, TRUE, NOW())
            """.trimIndent()
            val insStmt = conn.prepareStatement(insertTokenSql)
            insStmt.setString(1, teacherCode)
            insStmt.setString(2, storedUsername ?: trimmedIdentifier)
            insStmt.setString(3, tokenPlain)
            insStmt.setString(4, tokenHash)
            insStmt.setTimestamp(5, now)
            insStmt.setTimestamp(6, sevenDaysLater)
            insStmt.executeUpdate()
            insStmt.close()

            // 6. Update mst_teacher table with login_token_hash and validity
            val updateTeacherSql = """
                UPDATE mst_teacher
                SET login_token_hash = ?,
                    token_valid_from = ?,
                    token_valid_until = ?,
                    username = COALESCE(username, ?)
                WHERE teacher_code = ?
            """.trimIndent()
            val upTeacherStmt = conn.prepareStatement(updateTeacherSql)
            upTeacherStmt.setString(1, tokenHash)
            upTeacherStmt.setTimestamp(2, now)
            upTeacherStmt.setTimestamp(3, sevenDaysLater)
            upTeacherStmt.setString(4, trimmedIdentifier)
            upTeacherStmt.setString(5, teacherCode)
            upTeacherStmt.executeUpdate()
            upTeacherStmt.close()

            conn.commit()
            Log.i(TAG, "✅ Successfully generated token for teacher $teacherCode: $tokenPlain")

            return TokenResult.Success(
                token = tokenPlain,
                teacherCode = teacherCode,
                teacherName = nameEng
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error in generateTeacherToken: ${e.message}", e)
            try { conn?.rollback() } catch (_: Exception) {}
            return TokenResult.Error("Database connection failed: ${e.localizedMessage ?: e.message}")
        } finally {
            try { conn?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Direct PostgreSQL Registration for Teacher (UDISE ID, Teacher ID, Name, Mobile, Password).
     * Directly inserts/updates mst_teacher table via JDBC, generates 7-day valid secure token,
     * and saves into admin_tokens without any API.
     */
    fun registerTeacherDirect(
        udiseId: String,
        teacherId: String,
        name: String,
        mobileNumber: String,
        passwordInput: String,
        context: Context? = null
    ): RegisterResult {
        val trimmedUdise = udiseId.trim()
        val trimmedTeacherId = teacherId.trim().uppercase()
        val trimmedName = name.trim()
        val trimmedMobile = mobileNumber.trim()
        val trimmedPassword = passwordInput.trim()

        if (trimmedUdise.isEmpty() || trimmedTeacherId.isEmpty() || trimmedName.isEmpty() || trimmedMobile.isEmpty()) {
            return RegisterResult.Error("Please fill all required fields")
        }

        var conn: Connection? = null
        try {
            conn = getConnection(context)
            conn.autoCommit = false

            // 1. Check if teacher_code already exists
            val checkSql = "SELECT teacher_code FROM mst_teacher WHERE LOWER(teacher_code) = LOWER(?)"
            val checkStmt = conn.prepareStatement(checkSql)
            checkStmt.setString(1, trimmedTeacherId)
            val rs = checkStmt.executeQuery()
            val exists = rs.next()
            rs.close()
            checkStmt.close()

            val passwordHash = if (trimmedPassword.isNotEmpty()) md5Hex(trimmedPassword) else md5Hex(trimmedMobile)
            val mobileLong = trimmedMobile.toLongOrNull() ?: 0L
            val udiseLong = trimmedUdise.toLongOrNull() ?: 0L

            if (exists) {
                // Update existing record
                val updateSql = """
                    UPDATE mst_teacher 
                    SET name_eng = ?, mobile_no = ?, udise_id = ?, current_udise_id = ?, password_hash = ?, status = true
                    WHERE LOWER(teacher_code) = LOWER(?)
                """.trimIndent()
                val upStmt = conn.prepareStatement(updateSql)
                upStmt.setString(1, trimmedName)
                upStmt.setLong(2, mobileLong)
                upStmt.setLong(3, udiseLong)
                upStmt.setLong(4, udiseLong)
                upStmt.setString(5, passwordHash)
                upStmt.setString(6, trimmedTeacherId)
                upStmt.executeUpdate()
                upStmt.close()
            } else {
                // Insert new teacher into mst_teacher
                val insertSql = """
                    INSERT INTO mst_teacher 
                    (teacher_code, username, name_eng, mobile_no, udise_id, current_udise_id, password_hash, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, true)
                """.trimIndent()
                val insStmt = conn.prepareStatement(insertSql)
                insStmt.setString(1, trimmedTeacherId)
                insStmt.setString(2, trimmedTeacherId)
                insStmt.setString(3, trimmedName)
                insStmt.setLong(4, mobileLong)
                insStmt.setLong(5, udiseLong)
                insStmt.setLong(6, udiseLong)
                insStmt.setString(7, passwordHash)
                insStmt.executeUpdate()
                insStmt.close()
            }

            // Also insert / upsert into users table if table exists for complete compatibility
            try {
                val userInsertSql = """
                    INSERT INTO users (uid, full_name, employee_id, email, password, school_name, school_code)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (employee_id) DO UPDATE 
                    SET full_name = EXCLUDED.full_name, school_code = EXCLUDED.school_code
                """.trimIndent()
                val uStmt = conn.prepareStatement(userInsertSql)
                uStmt.setString(1, trimmedTeacherId)
                uStmt.setString(2, trimmedName)
                uStmt.setString(3, trimmedTeacherId)
                uStmt.setString(4, "$trimmedTeacherId@school.gov.in")
                uStmt.setString(5, passwordHash)
                uStmt.setString(6, "Govt School ($trimmedUdise)")
                uStmt.setString(7, trimmedUdise)
                uStmt.executeUpdate()
                uStmt.close()
            } catch (_: Exception) {}

            // 2. Generate Random Token (Format: AT-XXXX-XXXX)
            val randomPart1 = "%04X".format(Random.nextInt(0x10000))
            val randomPart2 = "%04X".format(Random.nextInt(0x10000))
            val tokenPlain = "AT-$randomPart1-$randomPart2"
            val tokenHash = md5Hex(tokenPlain)

            val now = Timestamp(System.currentTimeMillis())
            val sevenDaysLater = Timestamp(System.currentTimeMillis() + (7L * 24 * 60 * 60 * 1000L))

            // 3. Deactivate old tokens & Insert new token into admin_tokens
            try {
                val deactStmt = conn.prepareStatement("UPDATE admin_tokens SET is_active = FALSE WHERE teacher_code = ? AND is_active = TRUE")
                deactStmt.setString(1, trimmedTeacherId)
                deactStmt.executeUpdate()
                deactStmt.close()

                val insTokenStmt = conn.prepareStatement("""
                    INSERT INTO admin_tokens (teacher_code, username, token_plain, token_hash, token_valid_from, token_valid_until, is_active, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, TRUE, NOW())
                """.trimIndent())
                insTokenStmt.setString(1, trimmedTeacherId)
                insTokenStmt.setString(2, trimmedTeacherId)
                insTokenStmt.setString(3, tokenPlain)
                insTokenStmt.setString(4, tokenHash)
                insTokenStmt.setTimestamp(5, now)
                insTokenStmt.setTimestamp(6, sevenDaysLater)
                insTokenStmt.executeUpdate()
                insTokenStmt.close()
            } catch (_: Exception) {}

            // 4. Update mst_teacher with token
            val upTeacherStmt = conn.prepareStatement("""
                UPDATE mst_teacher 
                SET login_token_hash = ?, token_valid_from = ?, token_valid_until = ?, username = ?
                WHERE teacher_code = ?
            """.trimIndent())
            upTeacherStmt.setString(1, tokenHash)
            upTeacherStmt.setTimestamp(2, now)
            upTeacherStmt.setTimestamp(3, sevenDaysLater)
            upTeacherStmt.setString(4, trimmedTeacherId)
            upTeacherStmt.setString(5, trimmedTeacherId)
            upTeacherStmt.executeUpdate()
            upTeacherStmt.close()

            conn.commit()

            val profile = TeacherProfile(
                teacherCode = trimmedTeacherId,
                username = trimmedTeacherId,
                fullName = trimmedName,
                mobileNo = trimmedMobile,
                udiseId = trimmedUdise,
                schoolName = "Govt School ($trimmedUdise)",
                schoolCode = trimmedUdise,
                designation = "Teacher",
                tokenExpiryMillis = sevenDaysLater.time
            )

            // Save to AuthManager
            com.school.attendance.network.AuthManager.saveTeacherProfile(
                teacherCode = profile.teacherCode,
                fullName = profile.fullName,
                schoolName = profile.schoolName,
                schoolCode = profile.schoolCode,
                udiseId = profile.udiseId,
                designation = profile.designation,
                mobileNo = profile.mobileNo,
                tokenExpiryMillis = profile.tokenExpiryMillis
            )
            com.school.attendance.network.AuthManager.saveToken(tokenPlain)

            Log.i(TAG, "✅ Successfully registered teacher $trimmedTeacherId directly in PostgreSQL with Token: $tokenPlain")
            return RegisterResult.Success(tokenPlain, profile)
        } catch (e: Exception) {
            Log.e(TAG, "Error in registerTeacherDirect: ${e.message}", e)
            try { conn?.rollback() } catch (_: Exception) {}
            return RegisterResult.Error("Registration failed: ${e.localizedMessage ?: e.message}")
        } finally {
            try { conn?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Offline Login with Username + Password + Token directly against PostgreSQL.
     */
    fun loginWithToken(
        usernameOrCode: String,
        passwordInput: String,
        tokenInput: String = "",
        context: Context? = null
    ): LoginResult {
        val trimmedIdentifier = usernameOrCode.trim()
        val trimmedPassword = passwordInput.trim()
        val trimmedToken = tokenInput.trim().uppercase()

        if (trimmedIdentifier.isEmpty() || trimmedPassword.isEmpty()) {
            return LoginResult.Error("Please enter username and password")
        }

        var conn: Connection? = null
        try {
            conn = getConnection(context)

            // 1. Fetch teacher record from mst_teacher
            val selectSql = """
                SELECT teacher_code, username, password_hash, mobile_no, name_eng, name_hin, 
                       designation_name_eng, udise_id, current_udise_id, login_token_hash, 
                       token_valid_from, token_valid_until, status
                FROM mst_teacher
                WHERE (LOWER(username) = LOWER(?) OR LOWER(teacher_code) = LOWER(?) OR CAST(mobile_no AS TEXT) = ?)
                  AND (status IS NULL OR status = true)
                LIMIT 1
            """.trimIndent()

            val ps = conn.prepareStatement(selectSql)
            ps.setString(1, trimmedIdentifier)
            ps.setString(2, trimmedIdentifier)
            ps.setString(3, trimmedIdentifier)

            val rs = ps.executeQuery()
            if (!rs.next()) {
                rs.close()
                ps.close()
                return LoginResult.Error("You aren't Authorized")
            }

            val teacherCode = rs.getString("teacher_code") ?: ""
            val storedUsername = rs.getString("username") ?: trimmedIdentifier
            val storedPasswordHash = rs.getString("password_hash")
            val mobileNo = rs.getLong("mobile_no")
            val nameEng = rs.getString("name_eng") ?: rs.getString("name_hin") ?: teacherCode
            val designation = rs.getString("designation_name_eng") ?: "Teacher"
            val udiseId = rs.getString("current_udise_id") ?: rs.getString("udise_id") ?: ""
            val loginTokenHash = rs.getString("login_token_hash")
            val tokenValidUntil = rs.getTimestamp("token_valid_until")
            rs.close()
            ps.close()

            // 2. Validate Password
            if (!verifyPassword(trimmedPassword, storedPasswordHash, teacherCode, mobileNo)) {
                return LoginResult.Error("You aren't Authorized")
            }

            // 3. Validate Token
            val currentTime = System.currentTimeMillis()
            var isTokenValid = false

            // If token is already valid within 7 days in mst_teacher, don't require token input!
            if (tokenValidUntil != null && tokenValidUntil.time >= currentTime && !loginTokenHash.isNullOrEmpty()) {
                isTokenValid = true
            } else if (trimmedToken.isNotEmpty()) {
                val inputTokenHash = md5Hex(trimmedToken)
                // A. Check directly in mst_teacher
                if (!loginTokenHash.isNullOrEmpty() &&
                    (loginTokenHash.equals(inputTokenHash, ignoreCase = true) || loginTokenHash.equals(trimmedToken, ignoreCase = true))
                ) {
                    if (tokenValidUntil == null || tokenValidUntil.time >= currentTime) {
                        isTokenValid = true
                    }
                }

                // B. Also verify in admin_tokens table
                if (!isTokenValid) {
                    val checkAdminTokensSql = """
                        SELECT id FROM admin_tokens
                        WHERE teacher_code = ? 
                          AND (token_plain = ? OR token_hash = ?)
                          AND is_active = TRUE
                          AND (token_valid_until IS NULL OR token_valid_until >= NOW())
                        LIMIT 1
                    """.trimIndent()
                    val tokenStmt = conn.prepareStatement(checkAdminTokensSql)
                    tokenStmt.setString(1, teacherCode)
                    tokenStmt.setString(2, trimmedToken)
                    tokenStmt.setString(3, inputTokenHash)
                    val tokenRs = tokenStmt.executeQuery()
                    if (tokenRs.next()) {
                        isTokenValid = true
                    }
                    tokenRs.close()
                    tokenStmt.close()
                }

                if (!isTokenValid) {
                    return LoginResult.Error("Security Token is invalid or expired. Please generate a new token.")
                }
            } else {
                // Token has expired or not yet generated, and user did not enter one
                return LoginResult.TokenRequired("Your 7-day security token has expired or not yet generated. Please generate a token.")
            }

            if (!isTokenValid) {
                return LoginResult.Error("Security Token is invalid or expired.")
            }

            // 4. Fetch School Name from schools table if available
            var schoolName = "Government School"
            var schoolCode = udiseId
            if (udiseId.isNotEmpty()) {
                val schoolSql = "SELECT name, code FROM schools WHERE code = ? OR id::text = ? LIMIT 1"
                try {
                    val schoolStmt = conn.prepareStatement(schoolSql)
                    schoolStmt.setString(1, udiseId)
                    schoolStmt.setString(2, udiseId)
                    val sRs = schoolStmt.executeQuery()
                    if (sRs.next()) {
                        schoolName = sRs.getString("name") ?: schoolName
                        schoolCode = sRs.getString("code") ?: udiseId
                    }
                    sRs.close()
                    schoolStmt.close()
                } catch (_: Exception) {}
            }

            val expiryMillis = tokenValidUntil?.time ?: (System.currentTimeMillis() + (7L * 24 * 60 * 60 * 1000L))

            val profile = TeacherProfile(
                teacherCode = teacherCode,
                username = storedUsername,
                fullName = nameEng,
                mobileNo = mobileNo.toString(),
                udiseId = udiseId,
                schoolName = schoolName,
                schoolCode = schoolCode,
                designation = designation,
                tokenExpiryMillis = expiryMillis
            )

            Log.i(TAG, "✅ Direct DB Login SUCCESSFUL for: $nameEng ($teacherCode)")
            return LoginResult.Success(profile)
        } catch (e: Exception) {
            Log.e(TAG, "Error in loginWithToken: ${e.message}", e)
            return LoginResult.Error("Database error: ${e.localizedMessage ?: e.message}")
        } finally {
            try { conn?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Password verification logic
     */
    private fun verifyPassword(passwordInput: String, storedHash: String?, teacherCode: String, mobileNo: Long): Boolean {
        if (storedHash.isNullOrEmpty()) {
            // Initial setup password checks: matches teacher_code, mobile_no, 123456, or any non-empty password
            return passwordInput.equals(teacherCode, ignoreCase = true) ||
                   passwordInput == mobileNo.toString() ||
                   passwordInput == "123456" ||
                   passwordInput == "admin" ||
                   passwordInput == "teacher" ||
                   passwordInput.length >= 4
        }

        // 1. Plain match
        if (passwordInput == storedHash) return true

        // 2. MD5 match
        val md5Input = md5Hex(passwordInput)
        if (md5Input.equals(storedHash, ignoreCase = true)) return true

        // 3. Case insensitive
        if (passwordInput.equals(storedHash, ignoreCase = true)) return true

        return false
    }

    /**
     * MD5 Hex helper
     */
    fun md5Hex(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Returns true if current date is within the allowed 5-day upload window (Days 1 to 5 of the month).
     */
    fun isUploadWindowOpen(): Boolean {
        val day = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
        return day in 1..5
    }

    /**
     * Returns true if current date is in the month-end window (last 5 days of the month).
     */
    fun isMonthEndWindow(): Boolean {
        val cal = Calendar.getInstance()
        val currentDay = cal.get(Calendar.DAY_OF_MONTH)
        val maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        return currentDay >= (maxDay - 5)
    }

    /**
     * Directly uploads student attendance records to PostgreSQL table 'student_attendance' via JDBC without any API.
     * Restricts execution to the first 5 days of the month unless bypassWindowCheck is set.
     */
    fun uploadStudentAttendanceDirect(
        records: List<Map<String, Any>>,
        className: String,
        month: String,
        year: Int,
        daysCount: Int,
        schoolName: String,
        schoolCode: String,
        teacherId: String,
        context: Context,
        bypassWindowCheck: Boolean = false
    ): DirectUploadResult {
        if (!bypassWindowCheck && !isUploadWindowOpen()) {
            val day = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
            return DirectUploadResult.WindowClosed(
                "Central Database Upload is only enabled during the first 5 days of the month (1st to 5th).\n\nCurrent Date: Day $day\n\nYour data is saved safely in your local device storage and will sync at month-end."
            )
        }

        var conn: Connection? = null
        try {
            conn = getConnection(context)
            if (conn == null) {
                return DirectUploadResult.Error("Cannot establish direct JDBC connection to PostgreSQL.")
            }

            // Ensure student_attendance table exists
            val createTableSql = """
                CREATE TABLE IF NOT EXISTS student_attendance (
                    id              SERIAL PRIMARY KEY,
                    roll_no         TEXT NOT NULL,
                    student_name    TEXT NOT NULL,
                    class_name      TEXT DEFAULT '5A',
                    month           TEXT DEFAULT '',
                    year            INTEGER DEFAULT 2026,
                    days_count      INTEGER DEFAULT 31,
                    present_count   INTEGER DEFAULT 0,
                    absent_count    INTEGER DEFAULT 0,
                    attendance      TEXT DEFAULT '',
                    school_name     TEXT DEFAULT '',
                    school_code     TEXT DEFAULT '',
                    teacher_id      TEXT DEFAULT '',
                    created_at      TIMESTAMP DEFAULT NOW(),
                    updated_at      TIMESTAMP DEFAULT NOW(),
                    UNIQUE (roll_no, class_name, month, year)
                );
            """.trimIndent()
            val stmt = conn.createStatement()
            stmt.execute(createTableSql)
            stmt.close()

            val upsertSql = """
                INSERT INTO student_attendance 
                (roll_no, student_name, class_name, month, year, days_count, present_count, absent_count, attendance, school_name, school_code, teacher_id, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
                ON CONFLICT (roll_no, class_name, month, year)
                DO UPDATE SET
                    student_name = EXCLUDED.student_name,
                    days_count = EXCLUDED.days_count,
                    present_count = EXCLUDED.present_count,
                    absent_count = EXCLUDED.absent_count,
                    attendance = EXCLUDED.attendance,
                    school_name = EXCLUDED.school_name,
                    school_code = EXCLUDED.school_code,
                    teacher_id = EXCLUDED.teacher_id,
                    updated_at = NOW();
            """.trimIndent()

            val ps = conn.prepareStatement(upsertSql)
            var insertedCount = 0

            for (r in records) {
                val roll = r["roll_no"]?.toString()?.trim() ?: ""
                val name = r["student_name"]?.toString()?.trim() ?: ""
                if (roll.isEmpty() && name.isEmpty()) continue

                val pCnt = (r["present_count"] as? Number)?.toInt() ?: 0
                val aCnt = (r["absent_count"] as? Number)?.toInt() ?: 0
                val attJson = r["attendance_json"]?.toString() ?: "[]"

                ps.setString(1, roll)
                ps.setString(2, name)
                ps.setString(3, className)
                ps.setString(4, month)
                ps.setInt(5, year)
                ps.setInt(6, daysCount)
                ps.setInt(7, pCnt)
                ps.setInt(8, aCnt)
                ps.setString(9, attJson)
                ps.setString(10, schoolName)
                ps.setString(11, schoolCode)
                ps.setString(12, teacherId)

                ps.addBatch()
                insertedCount++
            }

            ps.executeBatch()
            ps.close()

            // Also mark local records as synced
            try {
                val localDb = LocalAttendanceDbHelper(context)
                localDb.markMonthSynced(className, month, year)
            } catch (e: Exception) {
                Log.w(TAG, "Could not update local sync status: ${e.message}")
            }

            Log.i(TAG, "✅ [DIRECT JDBC UPLOAD] Successfully upserted $insertedCount student records directly to PostgreSQL (No API)!")
            return DirectUploadResult.Success(insertedCount, "Successfully saved $insertedCount records to central PostgreSQL!")
        } catch (e: Exception) {
            Log.e(TAG, "❌ [DIRECT JDBC UPLOAD ERROR] ${e.message}", e)
            return DirectUploadResult.Error("Database direct upload error: ${e.localizedMessage ?: e.message}")
        } finally {
            try { conn?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Month-End Synchronization: Pushes all unsynced records from local SQLite to PostgreSQL via direct JDBC.
     */
    fun syncLocalToRemoteDirect(context: Context, force: Boolean = false): DirectUploadResult {
        if (!force && !isMonthEndWindow() && !isUploadWindowOpen()) {
            val day = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
            return DirectUploadResult.WindowClosed(
                "Month-End Sync is active during the last 5 days of the month.\nCurrent Day: $day.\nAll your attendance records remain safely stored in local device SQLite."
            )
        }

        val localDb = LocalAttendanceDbHelper(context)
        val unsyncedList = localDb.getUnsyncedRecords()
        if (unsyncedList.isEmpty()) {
            return DirectUploadResult.Success(0, "All local attendance records are already synced!")
        }

        var conn: Connection? = null
        try {
            conn = getConnection(context)
            if (conn == null) {
                return DirectUploadResult.Error("Cannot connect directly to PostgreSQL database.")
            }

            val upsertSql = """
                INSERT INTO student_attendance 
                (roll_no, student_name, class_name, month, year, days_count, present_count, absent_count, attendance, school_name, school_code, teacher_id, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
                ON CONFLICT (roll_no, class_name, month, year)
                DO UPDATE SET
                    student_name = EXCLUDED.student_name,
                    days_count = EXCLUDED.days_count,
                    present_count = EXCLUDED.present_count,
                    absent_count = EXCLUDED.absent_count,
                    attendance = EXCLUDED.attendance,
                    school_name = EXCLUDED.school_name,
                    school_code = EXCLUDED.school_code,
                    teacher_id = EXCLUDED.teacher_id,
                    updated_at = NOW();
            """.trimIndent()

            val ps = conn.prepareStatement(upsertSql)
            val syncedIds = mutableListOf<Long>()

            for (r in unsyncedList) {
                ps.setString(1, r.rollNo)
                ps.setString(2, r.studentName)
                ps.setString(3, r.className)
                ps.setString(4, r.month)
                ps.setInt(5, r.year)
                ps.setInt(6, r.daysCount)
                ps.setInt(7, r.presentCount)
                ps.setInt(8, r.absentCount)
                ps.setString(9, r.attendanceJson)
                ps.setString(10, r.schoolName)
                ps.setString(11, r.schoolCode)
                ps.setString(12, r.teacherId)

                ps.addBatch()
                syncedIds.add(r.id)
            }

            ps.executeBatch()
            ps.close()

            // Mark these records as synced in local SQLite
            localDb.markRecordsSynced(syncedIds)

            Log.i(TAG, "✅ [MONTH-END SYNC] Pushed ${syncedIds.size} records from SQLite to PostgreSQL via direct JDBC!")
            return DirectUploadResult.Success(syncedIds.size, "Successfully synced ${syncedIds.size} local records to central PostgreSQL!")
        } catch (e: Exception) {
            Log.e(TAG, "❌ [MONTH-END SYNC ERROR] ${e.message}", e)
            return DirectUploadResult.Error("Month-end sync error: ${e.localizedMessage ?: e.message}")
        } finally {
            try { conn?.close() } catch (_: Exception) {}
        }
    }
}
