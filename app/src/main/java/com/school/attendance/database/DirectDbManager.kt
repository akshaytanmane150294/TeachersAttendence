package com.school.attendance.database

import android.content.Context
import android.util.Log
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp
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
}
