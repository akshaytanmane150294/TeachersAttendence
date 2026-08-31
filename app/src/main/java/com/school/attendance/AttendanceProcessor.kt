package com.school.attendance

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Environment
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
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
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

        // Zero Python API calls - 100% On-Device Computer Vision & ML Kit OCR
        Log.i(TAG, "📱 Running 100% Offline On-Device Processor (Direct on phone, No Python API)...")
        Log.i("ATTENDANCE_STEP", "📱 Running 100% On-Device Processor...")
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
        val oriented = ensureLandscape(fullBitmap)
        val rectified = rectifyTableGrid(oriented)

        val result1 = extractFromOriented(rectified)
        if (result1 != null && result1.isNotEmpty()) {
            return result1
        }

        val rotated180 = rotateBitmap(rectified, 180f)
        val result2 = extractFromOriented(rotated180)
        return result2 ?: result1
    }

    /**
     * Tightly detects the 4 corners of the attendance table grid using OpenCV
     * and perspective-warps it to standard 2000x1450 resolution (removes all desk/background).
     */
    private fun rectifyTableGrid(bitmap: Bitmap): Bitmap {
        try {
            val src = Mat()
            Utils.bitmapToMat(bitmap, src)
            val gray = Mat()
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)

            val binImg = Mat()
            Imgproc.adaptiveThreshold(gray, binImg, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, 21, 5.0)

            val w = src.cols()
            val h = src.rows()
            val hKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size((w * 0.04).toDouble(), 1.0))
            val hLines = Mat()
            Imgproc.morphologyEx(binImg, hLines, Imgproc.MORPH_OPEN, hKernel)

            val vKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(1.0, (h * 0.04).toDouble()))
            val vLines = Mat()
            Imgproc.morphologyEx(binImg, vLines, Imgproc.MORPH_OPEN, vKernel)

            val tableGrid = Mat()
            Core.bitwise_or(hLines, vLines, tableGrid)

            val contours = mutableListOf<MatOfPoint>()
            Imgproc.findContours(tableGrid, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            contours.sortByDescending { Imgproc.contourArea(it) }

            if (contours.isNotEmpty() && Imgproc.contourArea(contours[0]) > (w * h * 0.10)) {
                val mainC = MatOfPoint2f(*contours[0].toArray())
                val rotRect = Imgproc.minAreaRect(mainC)
                val pts = Array(4) { Point() }
                rotRect.points(pts)

                // Order points: Top-Left, Top-Right, Bottom-Right, Bottom-Left
                val sortedByY = pts.sortedBy { it.y }
                val topPts = sortedByY.take(2).sortedBy { it.x }
                val bottomPts = sortedByY.takeLast(2).sortedBy { it.x }

                val tl = topPts[0]
                val tr = topPts[1]
                val bl = bottomPts[0]
                val br = bottomPts[1]

                // Expand corners outward by 0.5% from centroid so outer border lines are never sliced
                val cx = (tl.x + tr.x + br.x + bl.x) / 4.0
                val cy = (tl.y + tr.y + br.y + bl.y) / 4.0
                fun expandPt(p: Point): Point = Point(
                    (p.x + (p.x - cx) * 0.005).coerceIn(0.0, w - 1.0),
                    (p.y + (p.y - cy) * 0.005).coerceIn(0.0, h - 1.0)
                )

                val targetW = 2000.0
                val targetH = 1450.0

                val srcMat = MatOfPoint2f(expandPt(tl), expandPt(tr), expandPt(br), expandPt(bl))
                val dstMat = MatOfPoint2f(
                    Point(0.0, 0.0),
                    Point(targetW - 1.0, 0.0),
                    Point(targetW - 1.0, targetH - 1.0),
                    Point(0.0, targetH - 1.0)
                )

                val M = Imgproc.getPerspectiveTransform(srcMat, dstMat)
                val warped = Mat()
                Imgproc.warpPerspective(src, warped, M, Size(targetW, targetH))

                val resultBmp = Bitmap.createBitmap(targetW.toInt(), targetH.toInt(), Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(warped, resultBmp)
                Log.i("DATA_LOG", "✂️ Tightly cropped & perspective warped table to: ${resultBmp.width}x${resultBmp.height}")
                return resultBmp
            }
        } catch (e: Exception) {
            Log.e("DATA_LOG", "Error in rectifyTableGrid: ${e.message}", e)
        }
        return bitmap
    }

    private fun extractFromOriented(oriented: Bitmap): List<Map<String, Any>>? {
        return try {
            Log.i("DATA_LOG", "📷 [STEP 1] Starting Circle-Based OMR Analysis on standard ${oriented.width}x${oriented.height} resolution...")

            val src = Mat()
            Utils.bitmapToMat(oriented.copy(Bitmap.Config.ARGB_8888, false), src)
            val gray = Mat()
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)

            val width = gray.cols()
            val height = gray.rows()

            // 1. Detect candidate black circles: Threshold 148.0 (captures ink in all lighting conditions)
            val binInv = Mat()
            Imgproc.threshold(gray, binInv, 148.0, 255.0, Imgproc.THRESH_BINARY_INV)
            val contours = mutableListOf<MatOfPoint>()
            Imgproc.findContours(binInv, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

            val candidateCenters = mutableListOf<Pair<Double, Double>>()
            for (c in contours) {
                val rect = Imgproc.boundingRect(c)
                val bw = rect.width
                val bh = rect.height
                val area = Imgproc.contourArea(c)
                val aspect = if (bh > 0) bw.toDouble() / bh else 0.0
                // User rule: Diameter 20-35px, Radius 10-17.5px (R in 11..15px)
                if (bw in 16..36 && bh in 16..36 && aspect in 0.65..1.45 && area in 180.0..1000.0) {
                    val cx = rect.x + bw / 2.0
                    val cy = rect.y + bh / 2.0
                    if (cx in 330.0..1880.0 && cy in 80.0..1440.0) {
                        candidateCenters.add(Pair(cx, cy))
                    }
                }
            }

            if (candidateCenters.isEmpty()) {
                Log.w("DATA_LOG", "⚠️ No black circles detected.")
                return null
            }

            // 2. Derive Dynamic Student Rows along Y
            val sortedYs = candidateCenters.map { it.second }.sorted()
            val rowCenters = mutableListOf<Double>()
            var currYCluster = mutableListOf(sortedYs[0])
            for (i in 1 until sortedYs.size) {
                val y = sortedYs[i]
                if (y - currYCluster.last() < 20.0) {
                    currYCluster.add(y)
                } else {
                    rowCenters.add(currYCluster.average())
                    currYCluster = mutableListOf(y)
                }
            }
            if (currYCluster.isNotEmpty()) {
                rowCenters.add(currYCluster.average())
            }

            // 3. Derive Dynamic Day Columns along X
            val sortedXs = candidateCenters.map { it.first }.sorted()
            val colCenters = mutableListOf<Double>()
            var currXCluster = mutableListOf(sortedXs[0])
            for (i in 1 until sortedXs.size) {
                val x = sortedXs[i]
                if (x - currXCluster.last() < 20.0) {
                    currXCluster.add(x)
                } else {
                    colCenters.add(currXCluster.average())
                    currXCluster = mutableListOf(x)
                }
            }
            if (currXCluster.isNotEmpty()) {
                colCenters.add(currXCluster.average())
            }

            Log.i("DATA_LOG", "📊 Detected ${rowCenters.size} dynamic student rows and ${colCenters.size} day columns from black circles")

            // Read raw gray bytes once for instant sampling
            val grayBytes = ByteArray(width * height)
            gray.get(0, 0, grayBytes)

            val R = 13 // User rule: Radius R = 11 to 15px (Diameter 22 to 34px)
            val totalStudents = rowCenters.size
            val numDays = minOf(31, colCenters.size)
            val finalResults = mutableListOf<Map<String, Any>>()

            for (sIdx in 0 until totalStudents) {
                val rollNo = (101 + sIdx).toString()
                val studentName = "Student $rollNo"
                val ry = rowCenters[sIdx]
                val ryInt = Math.round(ry).toInt()

                val attendance = mutableListOf<Int>()
                for (d in 0 until numDays) {
                    val cx = colCenters[d]
                    val cxInt = Math.round(cx).toInt()

                    // Sample local paper background in cell margin outside R (distance 16 to 22 px)
                    val bgSamples = ArrayList<Int>(32)
                    for (dy in -20..20 step 4) {
                        val py = ryInt + dy
                        if (py < 0 || py >= height) continue
                        for (dx in -20..20 step 4) {
                            val px = cxInt + dx
                            if (px < 0 || px >= width) continue
                            val dSq = dx * dx + dy * dy
                            if (dSq in (16 * 16)..(22 * 22)) {
                                bgSamples.add(grayBytes[py * width + px].toInt() and 0xFF)
                            }
                        }
                    }
                    val bg = if (bgSamples.isNotEmpty()) {
                        bgSamples.sort()
                        bgSamples[bgSamples.size / 2].toDouble()
                    } else {
                        220.0
                    }

                    // Sample circular disk of radius R = 13 px (Diameter 22-34 px)
                    var circleSum = 0.0
                    var darkCount = 0
                    var totalCirclePixels = 0

                    for (dy in -R..R) {
                        val py = ryInt + dy
                        if (py < 0 || py >= height) continue
                        for (dx in -R..R) {
                            val px = cxInt + dx
                            if (px < 0 || px >= width) continue
                            if (dx * dx + dy * dy <= R * R) {
                                val v = grayBytes[py * width + px].toInt() and 0xFF
                                circleSum += v
                                totalCirclePixels++
                                if (v < bg - 25.0) {
                                    darkCount++
                                }
                            }
                        }
                    }

                    val circleMean = if (totalCirclePixels > 0) circleSum / totalCirclePixels else bg
                    val circleDarkRatio = if (totalCirclePixels > 0) darkCount.toDouble() / totalCirclePixels else 0.0

                    // Black circle diameter 20-35px, radius 11-15px rule -> 1 (Green) else 0 (Red)
                    val isMarked = circleDarkRatio >= 0.28 || (bg - circleMean) >= 30.0
                    attendance.add(if (isMarked) 1 else 0)
                }

                val presentCount = attendance.count { it == 1 }
                val absentCount = attendance.count { it == 0 }

                finalResults.add(
                    mapOf(
                        "rowIndex" to sIdx + 1,
                        "rollNo" to rollNo,
                        "name" to studentName,
                        "attendance" to attendance,
                        "presentCount" to presentCount,
                        "absentCount" to absentCount
                    )
                )
            }

            Log.i("DATA_LOG", "\n==========================================================================================")
            Log.i("DATA_LOG", "📋 [STEP 5] ATTENDANCE EXTRACTION COMPLETE - TOTAL STUDENTS: ${finalResults.size}")
            Log.i("DATA_LOG", "==========================================================================================")
            Log.i("DATA_LOG", String.format("%-8s %-16s %-8s %-8s %s", "Roll No", "Student Name", "Present", "Absent", "Day Marks (D1..D31)"))
            Log.i("DATA_LOG", "------------------------------------------------------------------------------------------")
            for (res in finalResults) {
                val roll = res["rollNo"] ?: ""
                val name = res["name"] ?: ""
                val pCount = res["presentCount"] ?: 0
                val aCount = res["absentCount"] ?: 0
                val attList = (res["attendance"] as? List<*>)?.map { if (it == 1) "P" else "A" }?.joinToString(" ") ?: ""
                Log.i("DATA_LOG", String.format("%-8s %-16s %-8s %-8s %s", roll, name, pCount, aCount, attList))
            }
            Log.i("DATA_LOG", "==========================================================================================\n")
            Log.i("ATTENDANCE_STEP", "🎉 Extraction Complete! Found ${finalResults.size} students with full marks.")

            saveAnnotatedDebugCircles(oriented, rowCenters, colCenters, finalResults)
            finalResults
        } catch (e: Exception) {
            Log.e("DATA_LOG", "Error in extractFromOriented: ${e.message}", e)
            null
        }
    }

    /**
     * Annotates the attendance sheet: Green dot for black circles (diameter 22-26px), Red dot for empty cells
     */
    private fun saveAnnotatedDebugCircles(
        original: Bitmap,
        rowCenters: List<Double>,
        colCenters: List<Double>,
        results: List<Map<String, Any>>
    ) {
        try {
            val annotBitmap = original.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(annotBitmap)

            val paintGreen = Paint().apply {
                color = Color.parseColor("#00E676") // Bright Green dot for black circle (Present)
                style = Paint.Style.FILL
                isAntiAlias = true
            }
            val paintRed = Paint().apply {
                color = Color.parseColor("#FF1744") // Bright Red dot for empty cell (Absent)
                style = Paint.Style.FILL
                isAntiAlias = true
            }
            val paintGreenBox = Paint().apply {
                color = Color.parseColor("#00E676")
                style = Paint.Style.STROKE
                strokeWidth = 5f
                isAntiAlias = true
            }
            val paintCornerDot = Paint().apply {
                color = Color.parseColor("#00E676")
                style = Paint.Style.FILL
                isAntiAlias = true
            }
            val paintCornerRing = Paint().apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 2.5f
                isAntiAlias = true
            }

            // Draw outer green boundary box and corner circles
            val bw = annotBitmap.width.toFloat()
            val bh = annotBitmap.height.toFloat()
            canvas.drawRect(0f, 0f, bw, bh, paintGreenBox)

            val corners = listOf(
                Pair(0f, 0f),
                Pair(bw, 0f),
                Pair(bw, bh),
                Pair(0f, bh)
            )
            for ((cx, cy) in corners) {
                canvas.drawCircle(cx, cy, 14f, paintCornerDot)
                canvas.drawCircle(cx, cy, 14f, paintCornerRing)
            }

            // Draw Green dots for Present (R=12 circle detected), Red dots for Empty
            for (r in 0 until minOf(rowCenters.size, results.size)) {
                val cy = rowCenters[r].toFloat()
                val attList = results[r]["attendance"] as? List<*> ?: emptyList<Any>()
                for (c in 0 until minOf(colCenters.size, attList.size)) {
                    val cx = colCenters[c].toFloat()
                    val isPresent = attList[c] == 1
                    if (isPresent) {
                        canvas.drawCircle(cx, cy, 7f, paintGreen)
                    } else {
                        canvas.drawCircle(cx, cy, 3f, paintRed)
                    }
                }
            }

            // 1. Save to outer output folder directly on device storage with timestamp
            val timeStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val timeFilename = "debug_latest_$timeStr.png"

            val outputDirs = listOf(
                File("/sdcard/output"),
                File(Environment.getExternalStorageDirectory(), "output"),
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "output"),
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "output"),
                File("/sdcard/Download/SchoolAttendance/output")
            )

            for (dir in outputDirs) {
                try {
                    if (!dir.exists()) dir.mkdirs()
                    // Save timestamped file
                    val timeFile = File(dir, timeFilename)
                    FileOutputStream(timeFile).use { out ->
                        annotBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    // Also update latest pointer
                    val debugFile = File(dir, "debug_latest.png")
                    FileOutputStream(debugFile).use { out ->
                        annotBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    Log.i("DATA_LOG", "💾 Saved timestamped debug image to: ${timeFile.absolutePath}")
                } catch (ignored: Exception) { }
            }
        } catch (e: Exception) {
            Log.e("DATA_LOG", "Error saving debug image: ${e.message}", e)
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

    /**
     * High-Accuracy OMR Bubble Detector inspired by OpenScanVision:
     * 1. Local Background Sampling (from margins/ring around bubble, completely immune to shadows)
     * 2. Centroid Micro-Search (snaps to black circle center in case of minor cell shift)
     * 3. Weighted Circular Disk Kernel (maximum weight at center, zero at edge - eliminates grid border line interference)
     * 4. Inner Core Fill Ratio (verifies solid ink coverage inside 6-8px center)
     * 5. Dual-Metric Decision Score
     */
    private fun detectCellMark(
        grayBytes: ByteArray,
        width: Int,
        height: Int,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int
    ): Boolean {
        val cw = x2 - x1
        val ch = y2 - y1
        if (cw < 6 || ch < 6) return false

        val cx = (x1 + x2) / 2
        val cy = (y1 + y2) / 2

        // Bubble geometry based on cell size
        val minDim = minOf(cw, ch)
        val bubbleR = (minDim * 0.32).toInt().coerceIn(6, 14)
        val innerR = (bubbleR * 0.55).toInt().coerceIn(3, 7)
        val ringInner = bubbleR + 2
        val ringOuter = (minDim * 0.48).toInt().coerceAtLeast(ringInner + 1)

        // 1. Sample Local Paper Background from outer ring / margins (staying inside cell borders)
        val bgSamples = ArrayList<Int>(48)
        for (dy in -ringOuter..ringOuter) {
            val py = cy + dy
            if (py <= y1 + 1 || py >= y2 - 1 || py < 0 || py >= height) continue
            for (dx in -ringOuter..ringOuter) {
                val px = cx + dx
                if (px <= x1 + 1 || px >= x2 - 1 || px < 0 || px >= width) continue
                val distSq = dx * dx + dy * dy
                if (distSq >= ringInner * ringInner && distSq <= ringOuter * ringOuter) {
                    val v = grayBytes[py * width + px].toInt() and 0xFF
                    bgSamples.add(v)
                }
            }
        }
        val localBg = if (bgSamples.isNotEmpty()) {
            bgSamples.sort()
            // 75th percentile of background samples represents clean unshaded paper
            bgSamples[(bgSamples.size * 3) / 4].toFloat()
        } else {
            220f
        }

        // 2. Centroid Micro-Search: snap to darkest center in ±3px window
        var bestCx = cx
        var bestCy = cy
        var minIntensity = 255
        val searchR = (bubbleR * 0.35).toInt().coerceIn(2, 4)

        for (sdy in -searchR..searchR) {
            val sy = cy + sdy
            if (sy <= y1 + 1 || sy >= y2 - 1 || sy < 0 || sy >= height) continue
            for (sdx in -searchR..searchR) {
                val sx = cx + sdx
                if (sx <= x1 + 1 || sx >= x2 - 1 || sx < 0 || sx >= width) continue
                val v = grayBytes[sy * width + sx].toInt() and 0xFF
                if (v < minIntensity) {
                    minIntensity = v
                    bestCx = sx
                    bestCy = sy
                }
            }
        }

        // Only shift center if there is noticeable ink signal
        if (localBg - minIntensity < 18f) {
            bestCx = cx
            bestCy = cy
        }

        // 3. Weighted Circular Disk Kernel: center weight = 1.0, edge weight = 0.0
        var weightedSum = 0.0
        var totalWeight = 0.0

        for (dy in -bubbleR..bubbleR) {
            val py = bestCy + dy
            if (py <= y1 || py >= y2 || py < 0 || py >= height) continue
            for (dx in -bubbleR..bubbleR) {
                val px = bestCx + dx
                if (px <= x1 || px >= x2 || px < 0 || px >= width) continue
                val distSq = dx * dx + dy * dy
                if (distSq <= bubbleR * bubbleR) {
                    val dist = Math.sqrt(distSq.toDouble())
                    val weight = 1.0 - (dist / bubbleR)
                    val grayVal = grayBytes[py * width + px].toInt() and 0xFF
                    weightedSum += grayVal * weight
                    totalWeight += weight
                }
            }
        }

        val weightedIntensity = if (totalWeight > 0) (weightedSum / totalWeight).toFloat() else localBg
        val normalizedDarkness = ((localBg - weightedIntensity) / maxOf(1f, localBg)).coerceIn(0f, 1f)

        // 4. Inner Core Fill Ratio (Center 6-8px)
        val inkThresh = localBg - maxOf(28f, localBg * 0.25f)
        var darkCount = 0
        var totalCore = 0

        for (dy in -innerR..innerR) {
            val py = bestCy + dy
            if (py <= y1 || py >= y2 || py < 0 || py >= height) continue
            for (dx in -innerR..innerR) {
                val px = bestCx + dx
                if (px <= x1 || px >= x2 || px < 0 || px >= width) continue
                val distSq = dx * dx + dy * dy
                if (distSq <= innerR * innerR) {
                    val grayVal = grayBytes[py * width + px].toInt() and 0xFF
                    totalCore++
                    if (grayVal <= inkThresh) {
                        darkCount++
                    }
                }
            }
        }

        val fillRatio = if (totalCore > 0) (darkCount.toFloat() / totalCore) else 0f

        // 5. Dual-Metric Combined Decision Score
        val combinedScore = 0.60f * normalizedDarkness + 0.40f * fillRatio
        return combinedScore >= 0.28f || (normalizedDarkness >= 0.24f && fillRatio >= 0.18f) || fillRatio >= 0.40f
    }

    private fun identifyTableStructure(
        rawHLines: List<Int>,
        rawVLines: List<Int>,
        bounds: Rect
    ): Triple<List<Int>, List<Int>, TableStructure> {
        val validH = rawHLines.filter { it in (bounds.y + 5)..(bounds.y + bounds.height - 5) }
        val tableTop = if (validH.isNotEmpty()) validH.first() else bounds.y + 20
        val tableBottom = if (validH.isNotEmpty()) validH.last() else bounds.y + bounds.height - 20
        val totalH = tableBottom - tableTop

        // Attendance sheet: 1 Header Row + 25 Student Rows = exactly 26 rows
        val targetRows = 26
        val exactRowH = totalH.toDouble() / targetRows
        val hLines = (0..targetRows).map { (tableTop + it * exactRowH).toInt() }

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

        val totalDaysWidth = tableRight - dayStart
        // Exactly 31 Day Columns (1..31) + Column P + Column A = 33 columns
        val targetCols = 33
        val exactDayW = totalDaysWidth.toDouble() / targetCols

        val dayColEdges = (0..targetCols).map { (dayStart + it * exactDayW).toInt() }
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
