package com.school.attendance.network

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object ApiClient {
    private val CANDIDATE_URLS = listOf(
        "http://127.0.0.1:8000",       // USB Cable via ADB reverse (Primary)
        "http://10.50.26.212:8000",    // Current Wi-Fi LAN IP (IIT Bhilai)
        "http://10.169.144.54:8000",   // Previous Wi-Fi LAN IP
        "http://192.168.43.1:8000",    // Phone Hotspot Gateway
        "http://192.168.137.1:8000",   // Windows Mobile Hotspot Gateway
        "http://10.0.2.2:8000",        // Android Emulator
        "http://localhost:8000"
    )

    var currentBaseUrl: String? = null
    val JSON = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun getOrderedUrls(): List<String> {
        val list = mutableListOf<String>()
        currentBaseUrl?.let { list.add(it) }
        CANDIDATE_URLS.forEach { if (it != currentBaseUrl) list.add(it) }
        return list
    }

    fun post(endpoint: String, body: JSONObject, withAuth: Boolean = false): Response {
        val rb = body.toString().toRequestBody(JSON)
        var lastException: Exception? = null

        for (baseUrl in getOrderedUrls()) {
            try {
                Log.d("ApiClient", "POST: Connecting to $baseUrl$endpoint ...")
                val reqBuilder = Request.Builder()
                    .url("$baseUrl$endpoint")
                    .post(rb)
                if (withAuth) {
                    AuthManager.getToken()?.let { reqBuilder.addHeader("Authorization", "Bearer $it") }
                }
                val response = client.newCall(reqBuilder.build()).execute()
                currentBaseUrl = baseUrl
                Log.d("ApiClient", "POST SUCCESS via $baseUrl (HTTP ${response.code})")
                return response
            } catch (e: Exception) {
                Log.w("ApiClient", "POST failed for $baseUrl: ${e.message}")
                lastException = e
            }
        }
        throw lastException ?: java.io.IOException("Failed to connect to server")
    }

    fun get(endpoint: String, withAuth: Boolean = false): Response {
        var lastException: Exception? = null

        for (baseUrl in getOrderedUrls()) {
            try {
                Log.d("ApiClient", "GET: Connecting to $baseUrl$endpoint ...")
                val reqBuilder = Request.Builder()
                    .url("$baseUrl$endpoint")
                    .get()
                if (withAuth) {
                    AuthManager.getToken()?.let { reqBuilder.addHeader("Authorization", "Bearer $it") }
                }
                val response = client.newCall(reqBuilder.build()).execute()
                currentBaseUrl = baseUrl
                Log.d("ApiClient", "GET SUCCESS via $baseUrl (HTTP ${response.code})")
                return response
            } catch (e: Exception) {
                Log.w("ApiClient", "GET failed for $baseUrl: ${e.message}")
                lastException = e
            }
        }
        throw lastException ?: java.io.IOException("Failed to connect to server")
    }
}
