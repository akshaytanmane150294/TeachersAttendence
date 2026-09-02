package com.school.attendance.network

import android.content.Context
import android.content.SharedPreferences
import com.auth0.android.jwt.JWT

object AuthManager {
    private const val PREF_NAME = "auth_prefs"
    private const val KEY_TOKEN = "jwt_token"

    private lateinit var prefs: SharedPreferences

    private const val KEY_TEACHER_CODE = "teacher_code"
    private const val KEY_TEACHER_NAME = "teacher_name"
    private const val KEY_SCHOOL_NAME = "school_name"
    private const val KEY_SCHOOL_CODE = "school_code"
    private const val KEY_UDISE_ID = "udise_id"
    private const val KEY_DESIGNATION = "designation"
    private const val KEY_MOBILE_NO = "mobile_no"
    private const val KEY_TOKEN_EXPIRY_MILLIS = "token_expiry_millis"
    private const val KEY_IS_OFFLINE_LOGGED_IN = "is_offline_logged_in"

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun saveToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
    }

    fun getToken(): String? = prefs.getString(KEY_TOKEN, null)

    fun saveTeacherProfile(
        teacherCode: String,
        fullName: String,
        schoolName: String,
        schoolCode: String,
        udiseId: String,
        designation: String = "Teacher",
        mobileNo: String = "",
        tokenExpiryMillis: Long = System.currentTimeMillis() + (7L * 24 * 60 * 60 * 1000L)
    ) {
        prefs.edit()
            .putString(KEY_TEACHER_CODE, teacherCode)
            .putString(KEY_TEACHER_NAME, fullName)
            .putString(KEY_SCHOOL_NAME, schoolName)
            .putString(KEY_SCHOOL_CODE, schoolCode)
            .putString(KEY_UDISE_ID, udiseId)
            .putString(KEY_DESIGNATION, designation)
            .putString(KEY_MOBILE_NO, mobileNo)
            .putLong(KEY_TOKEN_EXPIRY_MILLIS, tokenExpiryMillis)
            .putBoolean(KEY_IS_OFFLINE_LOGGED_IN, true)
            .putString(KEY_TOKEN, "offline_token_$teacherCode")
            .apply()
    }

    fun getTeacherName(): String = prefs.getString(KEY_TEACHER_NAME, "Teacher") ?: "Teacher"
    fun getTeacherCode(): String = prefs.getString(KEY_TEACHER_CODE, "") ?: ""
    fun getSchoolName(): String = prefs.getString(KEY_SCHOOL_NAME, "School") ?: "School"
    fun getSchoolCode(): String = prefs.getString(KEY_SCHOOL_CODE, "") ?: ""
    fun getUdiseId(): String = prefs.getString(KEY_UDISE_ID, "") ?: ""
    fun getDesignation(): String = prefs.getString(KEY_DESIGNATION, "Teacher") ?: "Teacher"
    fun getMobileNo(): String = prefs.getString(KEY_MOBILE_NO, "") ?: ""
    fun getTokenExpiryMillis(): Long = prefs.getLong(KEY_TOKEN_EXPIRY_MILLIS, 0L)

    fun isTokenExpired(): Boolean {
        val expiry = prefs.getLong(KEY_TOKEN_EXPIRY_MILLIS, 0L)
        if (expiry == 0L) return false
        return System.currentTimeMillis() > expiry
    }

    fun clearToken() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_TEACHER_CODE)
            .remove(KEY_TEACHER_NAME)
            .remove(KEY_SCHOOL_NAME)
            .remove(KEY_SCHOOL_CODE)
            .remove(KEY_UDISE_ID)
            .remove(KEY_DESIGNATION)
            .remove(KEY_MOBILE_NO)
            .remove(KEY_TOKEN_EXPIRY_MILLIS)
            .remove(KEY_IS_OFFLINE_LOGGED_IN)
            .apply()
    }

    fun isLoggedIn(): Boolean {
        val hasToken = !getToken().isNullOrEmpty()
        val isOffline = prefs.getBoolean(KEY_IS_OFFLINE_LOGGED_IN, false)
        val hasTeacher = getTeacherCode().isNotEmpty()
        if (!hasTeacher || (!isOffline && !hasToken)) return false
        return !isTokenExpired()
    }

    fun getUserId(): String? {
        val teacherCode = prefs.getString(KEY_TEACHER_CODE, null)
        if (!teacherCode.isNullOrEmpty()) return teacherCode

        val token = getToken() ?: return null
        return try {
            JWT(token).subject
        } catch (e: Exception) {
            null
        }
    }

    fun getUserEmail(): String? {
        val teacherCode = prefs.getString(KEY_TEACHER_CODE, null)
        if (!teacherCode.isNullOrEmpty()) return "$teacherCode@school.gov.in"

        val token = getToken() ?: return null
        return try {
            JWT(token).getClaim("email").asString()
        } catch (e: Exception) {
            null
        }
    }
}
