package com.school.attendance.network

import android.content.Context
import android.content.SharedPreferences
import com.auth0.android.jwt.JWT

object AuthManager {
    private const val PREF_NAME = "auth_prefs"
    private const val KEY_TOKEN = "jwt_token"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun saveToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
    }

    fun getToken(): String? = prefs.getString(KEY_TOKEN, null)

    fun clearToken() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    fun isLoggedIn(): Boolean = getToken() != null

    fun getUserId(): String? {
        val token = getToken() ?: return null
        return try {
            JWT(token).subject
        } catch (e: Exception) {
            null
        }
    }

    fun getUserEmail(): String? {
        val token = getToken() ?: return null
        return try {
            JWT(token).getClaim("email").asString()
        } catch (e: Exception) {
            null
        }
    }
}
