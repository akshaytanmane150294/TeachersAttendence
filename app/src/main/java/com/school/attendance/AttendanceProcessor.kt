package com.school.attendance

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Production Hybrid Attendance Processor
 * 
 * 1. Primary: High-Accuracy Production Python API (FastAPI + RapidOCR / PaddleOCR + WarpPerspective)
 * 2. Fallback: Native Offline On-Device Computer Vision
 */
class AttendanceProcessor {

    data class ProcessedResult(
        val attendanceData: List<Map<String, Any>>,
        val croppedBitmap: Bitmap
    )

    private val SERVER_URLS = listOf(
        "http://127.0.0.1:8000",      // USB Cable via ADB reverse (Primary)
        "http://10.50.26.212:8000",   // Current Wi-Fi IP (IIT Bhilai)
        "http://10.169.144.54:8000",  // Previous Wi-Fi IP
        "http://192.168.43.1:8000",   // Phone Hotspot Gateway
        "http://192.168.137.1:8000",  // Windows Mobile Hotspot Gateway
        "http://10.0.2.2:8000"        // Android Emulator
    )

    private val WORKING_WIDTH = 1200

    data class TableStructure(
        val rollLeft: Int,
        val rollRight: Int,
        val nameLeft: Int,
        val nameRight: Int,
        val dayColEdges: List<Int>,
        val pLeft: Int,
        val pRight: Int,
        val aLeft: Int,
        val aRight: Int
    )

    // Step 1: Enhance + Draw Grid Overlays
    fun enhanceContrast(bitmap: Bitmap): Bitmap? {
        val TAG = "ATTENDANCE_STEP"
        Log.d(TAG, "--- STEP 1: ENHANCE & DETECT GRID ---")
        Log.d(TAG, "Input: ${bitmap.width}x${bitmap.height}")

        return try {
            val oriented = ensureLandscape(bitmap)
            enhanceContrastInternal(oriented)
        } catch (e: Exception) {
            Log.e(TAG, "Error in enhanceContrast: ${e.message}", e)
            bitmap
        }
    }

    private fun enhanceContrastInternal(oriented: Bitmap): Bitmap {
        val TAG = "ATTENDANCE_STEP"
        val workBitmap = scaleToWorkingRes(oriented)

        val src = Mat()
        Utils.bitmapToMat(workBitmap.copy(Bitmap.Config.ARGB_8888, false), src)
        val gray = Mat()
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(3.0, 3.0), 0.0)

        val bw = Mat()
        Imgproc.adaptiveThreshold(gray, bw, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, 21, 7.0)

        val displayMat = Mat()
        Imgproc.cvtColor(gray, displayMat, Imgproc.COLOR_GRAY2RGBA)

        val paperRect = findPaperBounds(gray, workBitmap.width, workBitmap.height)
        val rawHLines = detectHorizontalLines(bw, paperRect)
        val rawVLines = detectVerticalLines(bw, paperRect)

        val (hLines, vLines, structure) = identifyTableStructure(rawHLines, rawVLines, paperRect)

        if (hLines.size < 3 || vLines.size < 5) {
            Log.w(TAG, "Grid lines insufficient: H=${hLines.size}, V=${vLines.size}")
            val res = Bitmap.createBitmap(displayMat.cols(), displayMat.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(displayMat, res)
            return res
        }

        val tableTop = hLines.first()
        val tableBottom = hLines.last()
        val tableLeft = vLines.first()
        val tableRight = vLines.last()
        val headerBottom = if (hLines.size > 1) hLines[1] else tableTop + 20

        val green = Scalar(0.0, 200.0, 0.0, 255.0)
        for (y in hLines) {
            Imgproc.line(displayMat, Point(tableLeft.toDouble(), y.toDouble()), Point(tableRight.toDouble(), y.toDouble()), green, 2)
        }
        val cyan = Scalar(0.0, 200.0, 230.0, 255.0)
        for (x in vLines) {
            Imgproc.line(displayMat, Point(x.toDouble(), tableTop.toDouble()), Point(x.toDouble(), tableBottom.toDouble()), cyan, 2)
        }

        // Highlight Zones
        Imgproc.rectangle(displayMat, Point(tableLeft.toDouble(), tableTop.toDouble()), Point(tableRight.toDouble(), headerBottom.toDouble()), Scalar(180.0, 0.0, 180.0, 255.0), 3)
        Imgproc.rectangle(displayMat, Point(structure.rollLeft.toDouble(), headerBottom.toDouble()), Point(structure.rollRight.toDouble(), tableBottom.toDouble()), Scalar(0.0, 165.0, 255.0, 255.0), 3)
        Imgproc.rectangle(displayMat, Point(structure.nameLeft.toDouble(), headerBottom.toDouble()), Point(structure.nameRight.toDouble(), tableBottom.toDouble()), Scalar(255.0, 255.0, 0.0, 255.0), 3)
        val dayStartX = structure.dayColEdges.first()
        val dayEndX = structure.dayColEdges.last()
        Imgproc.rectangle(displayMat, Point(dayStartX.toDouble(), headerBottom.toDouble()), Point(dayEndX.toDouble(), tableBottom.toDouble()), Scalar(255.0, 0.0, 0.0, 255.0), 3)
        Imgproc.rectangle(displayMat, Point(structure.pLeft.toDouble(), headerBottom.toDouble()), Point(structure.aRight.toDouble(), tableBottom.toDouble()), Scalar(0.0, 120.0, 255.0, 255.0), 3)

        val res = Bitmap.createBitmap(displayMat.cols(), displayMat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(displayMat, res)
        return res
    }

    // Step 2: Main Entry - Call Python API First (with Offline Fallback)
    fun processAttendance(fullBitmap: Bitmap): List<Map<String, Any>>? {
        val TAG = "DATA_LOG"
        Log.i(TAG, "==========================================================")
        Log.i(TAG, "🚀 [STEP 1] STARTING ATTENDANCE SCAN PROCESSING")
        Log.i(TAG, "📸 Image Size: ${fullBitmap.width}x${fullBitmap.height} px")
        Log.i(TAG, "==========================================================")
        Log.i("ATTENDANCE_STEP", "🚀 [STEP 1] Image captured: ${fullBitmap.width}x${fullBitmap.height}")

        // 1. Primary: Try each Python API URL (USB ADB reverse, Wi-Fi LAN IP, Hotspots)
        for ((idx, url) in SERVER_URLS.withIndex()) {
            Log.i(TAG, "🌐 [STEP 2.${idx + 1}] Trying Python API endpoint: $url/process_attendance ...")
            Log.i("ATTENDANCE_STEP", "🌐 Connecting to: $url")
            val startTime = System.currentTimeMillis()
            val apiData = callPythonApi(fullBitmap, url)
            val duration = System.currentTimeMillis() - startTime

            if (apiData != null && apiData.isNotEmpty()) {
                Log.i(TAG, "✅ [STEP 3] Python API SUCCESS via: $url (Time: ${duration}ms)")
                Log.i(TAG, "📊 [STEP 4] Extracted Total Students: ${apiData.size}")
                Log.i("ATTENDANCE_STEP", "✅ Python API SUCCESS! Found ${apiData.size} students.")
                for (i in 0 until minOf(5, apiData.size)) {
                    val st = apiData[i]
                    Log.i(TAG, "   👉 Roll: ${st["rollNo"]} | Name: ${st["name"]} | Present: ${st["presentCount"]} | Absent: ${st["absentCount"]}")
                }
                if (apiData.size > 5) {
                    Log.i(TAG, "   ... and ${apiData.size - 5} more students")
                }
                Log.i(TAG, "==========================================================")
                return apiData
            } else {
                Log.w(TAG, "⚠️ Endpoint $url failed (${duration}ms). Trying next...")
            }
        }

        // 2. Fallback: On-Device Extraction
        Log.w(TAG, "⚠️ Python API not reachable on any endpoint. Running On-Device Processor fallback...")
        Log.w("ATTENDANCE_STEP", "⚠️ Running On-Device Fallback...")
        return processAttendanceOnDevice(fullBitmap)
    }

    private fun callPythonApi(bitmap: Bitmap, baseUrl: String): List<Map<String, Any>>? {
        val TAG = "DATA_LOG"
        return try {
            val oriented = ensureLandscape(bitmap)
            // Pre-scale large 8MP camera images to standard 2000px for 5x faster network & OCR
            val maxDim = maxOf(oriented.width, oriented.height)
            val optimizedBitmap = if (maxDim > 2000) {
                val scale = 2000f / maxDim
                Bitmap.createScaledBitmap(oriented, (oriented.width * scale).toInt(), (oriented.height * scale).toInt(), true)
            } else {
                oriented
            }

            val stream = ByteArrayOutputStream()
            optimizedBitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
            val byteArray = stream.toByteArray()
            val sizeKb = byteArray.size / 1024

            Log.i(TAG, "📤 Uploading Image Payload: ${sizeKb} KB to $baseUrl/process_attendance ...")
            Log.i("ATTENDANCE_STEP", "📤 Uploading ${sizeKb} KB...")

            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "image",
                    "attendance.jpg",
                    byteArray.toRequestBody("image/jpeg".toMediaTypeOrNull())
                )
                .build()

            val request = Request.Builder()
                .url("$baseUrl/process_attendance")
                .post(requestBody)
                .build()

            Log.i(TAG, "⏳ Waiting for Python AI response...")
            val callStart = System.currentTimeMillis()
            val response = client.newCall(request).execute()
            val callDuration = System.currentTimeMillis() - callStart

            Log.i(TAG, "📥 Server Response Received: Code ${response.code} (HTTP ${if (response.isSuccessful) "OK" else "ERROR"}) in ${callDuration}ms")
            Log.i("ATTENDANCE_STEP", "📥 Server Response: ${response.code} in ${callDuration}ms")

            if (!response.isSuccessful) {
                Log.e(TAG, "❌ HTTP Error ${response.code}: ${response.message}")
                return null
            }

            val bodyString = response.body?.string() ?: return null
            Log.i(TAG, "📄 Raw JSON Response (${bodyString.length} chars)")

            val json = JSONObject(bodyString)
            val dataArray = json.optJSONArray("data") ?: return null

            val results = mutableListOf<Map<String, Any>>()
            for (i in 0 until dataArray.length()) {
                val obj = dataArray.getJSONObject(i)
                val roll = obj.optString("roll_no", "")
                val name = obj.optString("student_name", "")
                val presentCount = obj.optInt("present_count", 0)
                val absentCount = obj.optInt("absent_count", 0)

                val attJsonArray = obj.optJSONArray("attendance")
                val attList = mutableListOf<Int>()
                if (attJsonArray != null) {
                    for (j in 0 until attJsonArray.length()) {
                        attList.add(attJsonArray.getInt(j))
                    }
                }

                results.add(
                    mapOf(
                        "rollNo" to roll,
                        "name" to name,
                        "attendance" to attList,
                        "presentCount" to presentCount,
                        "absentCount" to absentCount
                    )
                )
            }
            results
        } catch (e: Exception) {
            Log.e(TAG, "❌ Connection Error for $baseUrl: ${e.javaClass.simpleName} - ${e.message}")
            null
        }
    }

    private fun processAttendanceOnDevice(fullBitmap: Bitmap): List<Map<String, Any>>? {
        val TAG = "DATA_LOG"
        val oriented = ensureLandscape(fullBitmap)

        val result1 = extractFromOriented(oriented)
        val namesCount1 = result1?.count { !it["name"].toString().startsWith("Student ") } ?: 0

        if (result1 != null && namesCount1 >= 5) {
            return result1
        }

        val rotated180 = rotateBitmap(oriented, 180f)
        val result2 = extractFromOriented(rotated180)
        val namesCount2 = result2?.count { !it["name"].toString().startsWith("Student ") } ?: 0

        if (result2 != null && namesCount2 > namesCount1) {
            return result2
        }
        return result1 ?: result2
    }

    private fun extractFromOriented(oriented: Bitmap): List<Map<String, Any>>? {
        val TAG = "DATA_LOG"
        return try {
            val workBitmap = scaleToWorkingRes(oriented)

            val src = Mat()
            Utils.bitmapToMat(workBitmap.copy(Bitmap.Config.ARGB_8888, false), src)
            val gray = Mat()
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, gray, Size(3.0, 3.0), 0.0)

            val bw = Mat()
            Imgproc.adaptiveThreshold(gray, bw, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, 21, 7.0)

            val paperRect = findPaperBounds(gray, workBitmap.width, workBitmap.height)
            val rawHLines = detectHorizontalLines(bw, paperRect)
            val rawVLines = detectVerticalLines(bw, paperRect)

            val (hLines, vLines, structure) = identifyTableStructure(rawHLines, rawVLines, paperRect)
            if (hLines.size < 3 || vLines.size < 5) return null

            val numStudentRows = hLines.size - 1
            val numDays = structure.dayColEdges.size - 1

            val scaleX = oriented.width.toDouble() / workBitmap.width
            val scaleY = oriented.height.toDouble() / workBitmap.height

            val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(
                com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS)

            val rawRecords = mutableListOf<MutableMap<String, Any>>()
            val rawRollNumbers = mutableListOf<String>()

            for (r in 0 until numStudentRows) {
                val y1 = hLines[r]
                val y2 = hLines[r + 1]
                val rowHeight = y2 - y1
                if (rowHeight < 6) continue

                val fullY1 = (y1 * scaleY).toInt()
                val fullY2 = (y2 * scaleY).toInt()
                val fullRowH = Math.max(1, fullY2 - fullY1)

                val padY = Math.max(3, (fullRowH * 0.20).toInt())
                val cropY = Math.max(0, fullY1 - padY)
                val cropH = Math.min(oriented.height - cropY, fullRowH + 2 * padY)

                val infoX1 = Math.max(0, (structure.rollLeft * scaleX).toInt() - 4)
                val infoX2 = Math.min(oriented.width, (structure.nameRight * scaleX).toInt() + 4)
                val infoW = Math.max(1, infoX2 - infoX1)
                val infoBmp = safeCrop(oriented, Rect(infoX1, cropY, infoW, cropH))
                val rawInfoText = if (infoBmp != null) ocrText(recognizer, infoBmp) else ""

                val lower = rawInfoText.lowercase()
                val isHeader = (lower.contains("roll") || lower.contains("rol") || lower.contains("no.")) &&
                               (lower.contains("name") || lower.contains("student") || lower.contains("shudent") || lower.contains("nama")) ||
                               (r <= 2 && (lower.contains("roll") || lower.contains("name") || lower.contains("student") || lower.contains("month")))

                if (isHeader) continue
                if (r <= 2 && rawInfoText.isBlank()) continue

                val attendance = mutableListOf<Int>()
                for (d in 0 until numDays) {
                    val x1 = structure.dayColEdges[d]
                    val x2 = structure.dayColEdges[d + 1]
                    val cw = x2 - x1
                    val ch = rowHeight

                    val marginX = Math.max(2, (cw * 0.18).toInt())
                    val marginY = Math.max(2, (ch * 0.18).toInt())
                    val rx = x1 + marginX
                    val ry = y1 + marginY
                    val rw = Math.max(1, cw - 2 * marginX)
                    val rh = Math.max(1, ch - 2 * marginY)

                    val rect = Rect(rx, ry, rw, rh)
                    if (rect.x >= 0 && rect.y >= 0 &&
                        rect.x + rect.width <= bw.cols() &&
                        rect.y + rect.height <= bw.rows()) {
                        val cell = Mat(bw, rect)
                        attendance.add(if (detectInk(cell)) 1 else 0)
                    } else {
                        attendance.add(0)
                    }
                }

                val presentCount = attendance.count { it == 1 }
                val absentCount = attendance.count { it == 0 }

                if (rawInfoText.isBlank() && presentCount == 0 && r >= numStudentRows - 2) continue

                val tokens = rawInfoText.split(Regex("\\s+")).filter { it.isNotBlank() }
                val digitsFound = mutableListOf<String>()
                val nameWords = mutableListOf<String>()

                for (token in tokens) {
                    val digits = token.filter { it.isDigit() }
                    val letters = token.filter { it.isLetter() }
                    if (digits.isNotEmpty() && letters.isEmpty()) {
                        digitsFound.add(digits)
                    } else if (letters.isNotEmpty()) {
                        val isGarbage = letters.length > 3 && letters.toSet().size <= 2
                        if (!isGarbage) {
                            nameWords.add(letters.lowercase().replaceFirstChar { it.uppercase() })
                        }
                    }
                }

                val detectedRoll = digitsFound.firstOrNull() ?: ""
                val cleanName = nameWords.distinct().joinToString(" ").trim()
                rawRollNumbers.add(detectedRoll)

                rawRecords.add(
                    mutableMapOf(
                        "rollNo" to detectedRoll,
                        "name" to cleanName,
                        "attendance" to attendance,
                        "presentCount" to presentCount,
                        "absentCount" to absentCount
                    )
                )
            }

            val correctedRolls = correctRollNumberSequence(rawRollNumbers)
            val finalResults = mutableListOf<Map<String, Any>>()

            for (i in 0 until rawRecords.size) {
                val rec = rawRecords[i]
                val corrRoll = correctedRolls[i]
                val currentName = rec["name"].toString()
                val effectiveName = if (currentName.length >= 2 && !currentName.startsWith("Student ")) currentName else "Student $corrRoll"

                rec["rollNo"] = corrRoll
                rec["name"] = effectiveName
                finalResults.add(rec)
            }
            if (finalResults.isEmpty()) null else finalResults
        } catch (e: Exception) {
            null
        }
    }

    private fun safeCrop(src: Bitmap, rect: Rect): Bitmap? {
        return try {
            val x = Math.max(0, rect.x)
            val y = Math.max(0, rect.y)
            val w = Math.min(rect.width, src.width - x)
            val h = Math.min(rect.height, src.height - y)
            if (w <= 4 || h <= 4) null else Bitmap.createBitmap(src, x, y, w, h)
        } catch (e: Exception) { null }
    }

    private fun detectInk(cell: Mat): Boolean {
        if (cell.empty()) return false
        val b = Math.max(1, (Math.min(cell.rows(), cell.cols()) * 0.05).toInt())
        val innerCell = if (cell.rows() > 2 * b && cell.cols() > 2 * b) {
            Mat(cell, Rect(b, b, cell.cols() - 2 * b, cell.rows() - 2 * b))
        } else {
            cell
        }

        val totalPixels = innerCell.rows() * innerCell.cols()
        val darkPixels = Core.countNonZero(innerCell)
        val inkRatio = darkPixels.toDouble() / Math.max(1, totalPixels)
        return inkRatio >= 0.04
    }

    private fun correctRollNumberSequence(ocrRolls: List<String>): List<String> {
        val parsed = ocrRolls.map { text ->
            val digits = text.filter { it.isDigit() }
            if (digits.isNotEmpty()) digits.toIntOrNull() else null
        }

        val diffs = mutableListOf<Int>()
        for (i in 1 until parsed.size) {
            val prev = parsed[i - 1]
            val curr = parsed[i]
            if (prev != null && curr != null) {
                val d = curr - prev
                if (d in 1..3) diffs.add(d)
            }
        }

        val step = if (diffs.isNotEmpty()) {
            diffs.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: 1
        } else {
            1
        }

        var anchorIdx = parsed.indexOfFirst { it != null && it >= 100 }
        if (anchorIdx == -1) anchorIdx = parsed.indexOfFirst { it != null }

        val anchorVal = if (anchorIdx != -1 && parsed[anchorIdx] != null) parsed[anchorIdx]!! else 101
        val actualAnchorIdx = if (anchorIdx != -1) anchorIdx else 0

        val corrected = mutableListOf<String>()
        for (i in parsed.indices) {
            val expected = anchorVal + (i - actualAnchorIdx) * step
            corrected.add(expected.toString())
        }
        return corrected
    }

    private fun identifyTableStructure(
        rawHLines: List<Int>,
        rawVLines: List<Int>,
        bounds: Rect
    ): Triple<List<Int>, List<Int>, TableStructure> {
        val validH = rawHLines.filter { it in (bounds.y + 5)..(bounds.y + bounds.height - 5) }
        val hGaps = (0 until validH.size - 1).map { validH[it + 1] - validH[it] }.filter { it in 10..35 }
        val medianH = if (hGaps.isNotEmpty()) hGaps.sorted()[hGaps.size / 2].toDouble() else 16.0

        val tableTop = if (validH.isNotEmpty()) validH.first() else bounds.y + 20
        val tableBottom = if (validH.isNotEmpty()) validH.last() else bounds.y + bounds.height - 20
        val totalH = tableBottom - tableTop
        val numRows = Math.max(5, Math.round(totalH / medianH).toInt())
        val exactRowH = totalH.toDouble() / numRows
        val hLines = (0..numRows).map { (tableTop + it * exactRowH).toInt() }

        val validV = rawVLines.filter { it in (bounds.x + 5)..(bounds.x + bounds.width - 5) }
        val vGaps = (0 until validV.size - 1).map { validV[it + 1] - validV[it] }

        val searchLimit = Math.max(3, vGaps.size / 2)
        var nameColIndex = 1
        var maxGap = 0
        for (i in 0 until searchLimit) {
            if (vGaps[i] > maxGap) {
                maxGap = vGaps[i]
                nameColIndex = i
            }
        }

        val rollLeft = if (nameColIndex >= 1) validV[0] else bounds.x + 20
        val rollRight = validV[nameColIndex]
        val nameLeft = validV[nameColIndex]
        val nameRight = validV[nameColIndex + 1]
        val dayStart = nameRight
        val tableRight = if (validV.isNotEmpty()) validV.last() else bounds.x + bounds.width - 20

        val rightVGaps = (nameColIndex + 1 until validV.size - 1).map { validV[it + 1] - validV[it] }.filter { it in 10..35 }
        val medianDayW = if (rightVGaps.isNotEmpty()) rightVGaps.sorted()[rightVGaps.size / 2].toDouble() else 16.5

        val totalDaysWidth = tableRight - dayStart
        val numDays = Math.max(10, Math.round(totalDaysWidth / medianDayW).toInt())
        val exactDayW = totalDaysWidth.toDouble() / numDays

        val dayColEdges = (0..numDays).map { (dayStart + it * exactDayW).toInt() }
        val vLines = mutableListOf<Int>()
        vLines.add(rollLeft)
        if (rollRight != rollLeft && rollRight != nameRight) vLines.add(rollRight)
        vLines.addAll(dayColEdges)

        val pLeft = dayColEdges.getOrElse(dayColEdges.size - 3) { dayColEdges.last() }
        val pRight = dayColEdges.getOrElse(dayColEdges.size - 2) { dayColEdges.last() }
        val aLeft = pRight
        val aRight = dayColEdges.last()

        val structure = TableStructure(rollLeft, rollRight, nameLeft, nameRight, dayColEdges, pLeft, pRight, aLeft, aRight)
        return Triple(hLines, vLines, structure)
    }

    private fun detectHorizontalLines(bw: Mat, bounds: Rect): List<Int> {
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(30.0, 1.0))
        val lines = Mat()
        Imgproc.morphologyEx(bw, lines, Imgproc.MORPH_OPEN, kernel)
        val proj = Mat()
        Core.reduce(lines, proj, 1, Core.REDUCE_SUM, CvType.CV_32F)

        var maxVal = 0.0
        for (i in 0 until proj.rows()) {
            val v = proj.get(i, 0)[0]
            if (v > maxVal) maxVal = v
        }
        val thresh = maxVal * 0.04
        val peaks = mutableListOf<Int>()
        var inPeak = false
        var maxP = 0.0
        var maxIdx = 0
        var lastPeak = -100

        for (i in 0 until proj.rows()) {
            val v = proj.get(i, 0)[0]
            if (v > thresh) {
                if (!inPeak) {
                    inPeak = true
                    maxP = v
                    maxIdx = i
                } else if (v > maxP) {
                    maxP = v
                    maxIdx = i
                }
            } else {
                if (inPeak) {
                    inPeak = false
                    if (maxIdx - lastPeak >= 10) {
                        peaks.add(maxIdx)
                        lastPeak = maxIdx
                    }
                }
            }
        }
        if (inPeak && maxIdx - lastPeak >= 10) peaks.add(maxIdx)
        return peaks.filter { it in (bounds.y + 5)..(bounds.y + bounds.height - 5) }
    }

    private fun detectVerticalLines(bw: Mat, bounds: Rect): List<Int> {
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(1.0, 50.0))
        val lines = Mat()
        Imgproc.morphologyEx(bw, lines, Imgproc.MORPH_OPEN, kernel)
        val proj = Mat()
        Core.reduce(lines, proj, 0, Core.REDUCE_SUM, CvType.CV_32F)

        var maxVal = 0.0
        for (i in 0 until proj.cols()) {
            val v = proj.get(0, i)[0]
            if (v > maxVal) maxVal = v
        }
        val thresh = maxVal * 0.08
        val peaks = mutableListOf<Int>()
        var inPeak = false
        var maxP = 0.0
        var maxIdx = 0
        var lastPeak = -100

        for (i in 0 until proj.cols()) {
            val v = proj.get(0, i)[0]
            if (v > thresh) {
                if (!inPeak) {
                    inPeak = true
                    maxP = v
                    maxIdx = i
                } else if (v > maxP) {
                    maxP = v
                    maxIdx = i
                }
            } else {
                if (inPeak) {
                    inPeak = false
                    if (maxIdx - lastPeak >= 16) {
                        peaks.add(maxIdx)
                        lastPeak = maxIdx
                    }
                }
            }
        }
        if (inPeak && maxIdx - lastPeak >= 16) peaks.add(maxIdx)
        return peaks.filter { it in (bounds.x + 5)..(bounds.x + bounds.width - 5) }
    }

    private fun ensureLandscape(bmp: Bitmap): Bitmap {
        return if (bmp.height > bmp.width) rotateBitmap(bmp, 90f) else bmp
    }

    private fun scaleToWorkingRes(bmp: Bitmap): Bitmap {
        if (bmp.width <= WORKING_WIDTH) return bmp
        val s = WORKING_WIDTH.toFloat() / bmp.width
        return Bitmap.createScaledBitmap(bmp, WORKING_WIDTH, (bmp.height * s).toInt(), true)
    }

    private fun rotateBitmap(bmp: Bitmap, deg: Float): Bitmap {
        val m = Matrix()
        m.postRotate(deg)
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    private fun findPaperBounds(gray: Mat, imgW: Int, imgH: Int): Rect {
        return try {
            val thresh = Mat()
            Imgproc.threshold(gray, thresh, 120.0, 255.0, Imgproc.THRESH_BINARY)
            val contours = mutableListOf<MatOfPoint>()
            Imgproc.findContours(thresh, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

            var maxArea = 0.0
            var bestRect: Rect? = null
            val minArea = imgW * imgH * 0.15

            for (c in contours) {
                val area = Imgproc.contourArea(c)
                if (area > maxArea && area > minArea) {
                    val r = Imgproc.boundingRect(c)
                    if (r.width > imgW * 0.4 && r.height > imgH * 0.3) {
                        maxArea = area
                        bestRect = r
                    }
                }
            }

            bestRect ?: Rect(0, 0, imgW, imgH)
        } catch (e: Exception) {
            Rect(0, 0, imgW, imgH)
        }
    }

    private fun ocrText(recognizer: com.google.mlkit.vision.text.TextRecognizer, bmp: Bitmap): String {
        return try {
            val img = com.google.mlkit.vision.common.InputImage.fromBitmap(bmp, 0)
            com.google.android.gms.tasks.Tasks.await(recognizer.process(img)).text.trim().replace("\n", " ")
        } catch (e: Exception) {
            ""
        }
    }
}
