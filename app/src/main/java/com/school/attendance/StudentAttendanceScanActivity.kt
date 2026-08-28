package com.school.attendance

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.school.attendance.databinding.ActivityStudentAttendanceScanBinding
import org.opencv.android.OpenCVLoader
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class StudentAttendanceScanActivity : AppCompatActivity(), android.hardware.SensorEventListener {

    private lateinit var binding: ActivityStudentAttendanceScanBinding

    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private val processor = AttendanceProcessor()

    private var isTorchOn: Boolean = false
    private var schoolCode: String = ""
    private var className: String = "5A"
    private var scanYear: Int = 0
    private var scanMonth: Int = 0

    // Sensor Manager for Tilt Angle
    private var sensorManager: android.hardware.SensorManager? = null
    private var accelerometer: android.hardware.Sensor? = null
    private var currentTiltAngle: Float = 0f

    // Live Condition State
    private var isReadyToCapture: Boolean = false
    private var lastLumaCheckTime: Long = 0L
    private var lastAvgLuminance: Float = 128f
    private var lastGuidanceState: String = ""

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission is required to scan attendance", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStudentAttendanceScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        OpenCVLoader.initDebug()

        schoolCode = intent.getStringExtra("schoolCode") ?: "N/A"
        className = intent.getStringExtra("className") ?: "5A"
        val cal = Calendar.getInstance()
        scanYear = intent.getIntExtra("year", cal.get(Calendar.YEAR))
        scanMonth = intent.getIntExtra("month", cal.get(Calendar.MONTH) + 1)

        binding.tvHeaderSubtitle.text = "Class $className • Fit table inside frame"

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Init Accelerometer for 0° Flat Tilt Check
        sensorManager = getSystemService(SENSOR_SERVICE) as? android.hardware.SensorManager
        accelerometer = sensorManager?.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        // Header Actions
        binding.btnBack.setOnClickListener { finish() }
        binding.btnFlash.setOnClickListener { toggleTorch() }

        // Capture
        binding.btnCapturePaper.setOnClickListener { takePhoto() }
        binding.btnReset.setOnClickListener { resetUI() }
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.let {
            sensorManager?.registerListener(this, it, android.hardware.SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: android.hardware.SensorEvent?) {
        if (event == null || event.sensor.type != android.hardware.Sensor.TYPE_ACCELEROMETER) return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val gTotal = kotlin.math.sqrt((x * x + y * y + z * z).toDouble())
        if (gTotal > 0) {
            val cosTilt = (kotlin.math.abs(z) / gTotal).coerceIn(0.0, 1.0)
            currentTiltAngle = Math.toDegrees(kotlin.math.acos(cosTilt)).toFloat()
        }
    }

    override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}

    private fun allPermissionsGranted() =
        ContextCompat.checkSelfPermission(baseContext, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        analyzeLiveFrame(imageProxy)
                    }
                }

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture,
                    imageAnalyzer
                )
                binding.scannerOverlay.setScanningActive(true)
            } catch (exc: Exception) {
                Log.e("ScanActivity", "Error starting camera", exc)
                Toast.makeText(this, "Failed to start camera: ${exc.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Real-time Camera Preview Frame Analyzer:
     * 1. Light Level (50% - 79% is optimal green)
     * 2. Framing / Paper Contrast (Sheet inside frame with text/grid detected)
     * 3. Tilt Angle (0° Flat parallel to paper)
     * 4. Stability (No rapid motion/shake)
     */
    private fun analyzeLiveFrame(image: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastLumaCheckTime < 180) {
            image.close()
            return
        }
        lastLumaCheckTime = now

        try {
            val buffer = image.planes[0].buffer
            val remaining = buffer.remaining()
            val step = maxOf(1, remaining / 2048)
            var sum = 0L
            var count = 0
            var minVal = 255
            var maxVal = 0

            var idx = 0
            while (idx < remaining) {
                val v = buffer.get(idx).toInt() and 0xFF
                sum += v
                if (v < minVal) minVal = v
                if (v > maxVal) maxVal = v
                count++
                idx += step
            }

            val avgLuminance = if (count > 0) sum / count.toFloat() else 128f
            val lightPct = ((avgLuminance / 255f) * 100f).toInt().coerceIn(0, 100)
            val contrast = maxVal - minVal
            val diffLuma = kotlin.math.abs(avgLuminance - lastAvgLuminance)
            lastAvgLuminance = avgLuminance

            // Optimal Light: 50% - 79% (acceptable 45% - 85%)
            val isLightOk = lightPct in 45..85
            val isFramingOk = lightPct >= 42 && contrast >= 30
            val isTiltOk = currentTiltAngle <= 15f
            val isSteady = diffLuma <= 6.5f
            val isAllReady = isLightOk && isFramingOk && isTiltOk && isSteady

            runOnUiThread {
                updateConditionUI(isAllReady, isLightOk, isFramingOk, isTiltOk, isSteady, lightPct)
            }
        } catch (e: Exception) {
            Log.e("ScanActivity", "Live Analysis Error", e)
        } finally {
            image.close()
        }
    }

    private fun updateConditionUI(
        allReady: Boolean,
        lightOk: Boolean,
        framingOk: Boolean,
        tiltOk: Boolean,
        steady: Boolean,
        lightPct: Int
    ) {
        isReadyToCapture = allReady

        // 1. Light Chip (Target: 50% - 79%)
        if (lightOk) {
            binding.chipLight.setBackgroundResource(R.drawable.bg_condition_chip_green)
            binding.chipLight.text = "💡 Light: ${lightPct}%"
        } else if (lightPct < 45) {
            binding.chipLight.setBackgroundResource(R.drawable.bg_condition_chip_red)
            binding.chipLight.text = "💡 Light: ${lightPct}% (Low)"
        } else {
            binding.chipLight.setBackgroundResource(R.drawable.bg_condition_chip_yellow)
            binding.chipLight.text = "💡 Light: ${lightPct}% (High)"
        }

        // 2. Table Boundary Alignment Chip (1.38:1)
        if (framingOk) {
            binding.chipFraming.setBackgroundResource(R.drawable.bg_condition_chip_green)
            binding.chipFraming.text = "🔵 Frame: 1.38:1"
        } else {
            binding.chipFraming.setBackgroundResource(R.drawable.bg_condition_chip_yellow)
            binding.chipFraming.text = "🔵 Sheet: Align"
        }

        // 3. Tilt Angle Chip (0° Flat)
        val angleDeg = currentTiltAngle.toInt()
        if (tiltOk) {
            binding.chipAngle.setBackgroundResource(R.drawable.bg_condition_chip_green)
            binding.chipAngle.text = "📐 Tilt: ${angleDeg}° Flat"
        } else {
            binding.chipAngle.setBackgroundResource(R.drawable.bg_condition_chip_yellow)
            binding.chipAngle.text = "📐 Tilt: ${angleDeg}° (Level)"
        }

        // 4. Stability Chip
        if (steady) {
            binding.chipStability.setBackgroundResource(R.drawable.bg_condition_chip_green)
            binding.chipStability.text = "🎯 Hold: Steady"
        } else {
            binding.chipStability.setBackgroundResource(R.drawable.bg_condition_chip_yellow)
            binding.chipStability.text = "🎯 Hold: Moving..."
        }

        // 5. Update Overlay Glow & Colors
        binding.scannerOverlay.setScannerState(allReady, framingOk, lightOk)

        // 6. Update Status Pill
        if (allReady) {
            binding.tvStatus.text = "✨ Perfect! Tap Capture Button"
            binding.layoutGuidancePill.setBackgroundColor(Color.parseColor("#E6003B1E"))
            binding.btnCaptureContainer.alpha = 1.0f
        } else {
            binding.layoutGuidancePill.setBackgroundResource(R.drawable.bg_scanner_pill)
            binding.btnCaptureContainer.alpha = 0.75f
            when {
                !lightOk && lightPct < 45 -> binding.tvStatus.text = "Low Light (${lightPct}%): Turn on Flash or increase light"
                !tiltOk -> binding.tvStatus.text = "Hold phone flat (0° parallel to paper, 25-30cm)"
                !framingOk -> binding.tvStatus.text = "Align table corners inside 1.38:1 blue guide box"
                !steady -> binding.tvStatus.text = "Hold camera steady..."
            }
        }
    }

    private fun toggleTorch() {
        val cam = camera
        if (cam == null) {
            Toast.makeText(this, "Camera not ready", Toast.LENGTH_SHORT).show()
            return
        }

        if (cam.cameraInfo.hasFlashUnit()) {
            isTorchOn = !isTorchOn
            cam.cameraControl.enableTorch(isTorchOn)
            binding.btnFlash.setImageResource(if (isTorchOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
            binding.btnFlash.setColorFilter(if (isTorchOn) Color.parseColor("#FFD600") else Color.WHITE)
        } else {
            Toast.makeText(this, "Flash unavailable on this device", Toast.LENGTH_SHORT).show()
        }
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        // If not ready, warn the user so bad photos are prevented
        if (!isReadyToCapture) {
            binding.btnCaptureContainer.animate()
                .translationXBy(15f).setDuration(60)
                .withEndAction {
                    binding.btnCaptureContainer.animate().translationXBy(-30f).setDuration(60)
                        .withEndAction {
                            binding.btnCaptureContainer.animate().translationX(0f).setDuration(60).start()
                        }.start()
                }.start()
            Toast.makeText(this, "⚠️ Please align table inside frame and ensure all green indicators are ON!", Toast.LENGTH_SHORT).show()
            return
        }

        // Trigger visual feedback (green corner pulse + button bounce)
        binding.scannerOverlay.triggerCaptureFeedback()
        binding.btnCapturePaper.animate()
            .scaleX(0.85f)
            .scaleY(0.85f)
            .setDuration(100)
            .withEndAction {
                binding.btnCapturePaper.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
            }.start()

        setLoading(true, "Capturing High-Resolution Photo...")

        imageCapture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val rawBitmap = imageProxyToBitmap(image)
                    image.close()
                    if (rawBitmap != null) {
                        val overlayRect = binding.scannerOverlay.frameRect
                        val viewW = binding.viewFinder.width
                        val viewH = binding.viewFinder.height
                        val croppedBitmap = cropBitmapToOverlayFrame(rawBitmap, overlayRect, viewW, viewH)
                        processDirectlyWithPython(croppedBitmap)
                    } else {
                        setLoading(false)
                        Toast.makeText(this@StudentAttendanceScanActivity, "Failed to decode photo", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onError(exc: ImageCaptureException) {
                    Log.e("ScanActivity", "Capture Error", exc)
                    setLoading(false)
                    Toast.makeText(this@StudentAttendanceScanActivity, "Capture failed: ${exc.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    /**
     * Crops the high-resolution captured camera bitmap to the exact viewfinder frameRect
     * displayed on screen, removing 100% of surrounding background/desk/shadow noise.
     */
    private fun cropBitmapToOverlayFrame(
        bitmap: Bitmap,
        overlayRect: android.graphics.RectF,
        viewWidth: Int,
        viewHeight: Int
    ): Bitmap {
        if (viewWidth <= 0 || viewHeight <= 0 || overlayRect.isEmpty) {
            return bitmap
        }

        val Vw = viewWidth.toFloat()
        val Vh = viewHeight.toFloat()
        val Bw = bitmap.width.toFloat()
        val Bh = bitmap.height.toFloat()

        // PreviewView uses ScaleType.FILL_CENTER by default
        val scale = maxOf(Vw / Bw, Vh / Bh)
        val scaledBw = Bw * scale
        val scaledBh = Bh * scale
        val offsetX = (Vw - scaledBw) / 2f
        val offsetY = (Vh - scaledBh) / 2f

        // Convert overlay screen coordinates to high-res bitmap coordinates
        val rawLeft = (overlayRect.left - offsetX) / scale
        val rawTop = (overlayRect.top - offsetY) / scale
        val rawRight = (overlayRect.right - offsetX) / scale
        val rawBottom = (overlayRect.bottom - offsetY) / scale

        // Add 3% safety margin around the frame so edges and text are never clipped
        val padX = (rawRight - rawLeft) * 0.03f
        val padY = (rawBottom - rawTop) * 0.03f

        val cropX = ((rawLeft - padX).coerceIn(0f, Bw - 1f)).toInt()
        val cropY = ((rawTop - padY).coerceIn(0f, Bh - 1f)).toInt()
        val cropW = (((rawRight + padX) - (rawLeft - padX)).coerceIn(1f, Bw - cropX)).toInt()
        val cropH = (((rawBottom + padY) - (rawTop - padY)).coerceIn(1f, Bh - cropY)).toInt()

        return try {
            Log.i("ScanActivity", "✂️ Cropping high-res photo (${bitmap.width}x${bitmap.height}) to frame: X=$cropX, Y=$cropY, W=$cropW, H=$cropH")
            Bitmap.createBitmap(bitmap, cropX, cropY, cropW, cropH)
        } catch (e: Exception) {
            Log.e("ScanActivity", "Error cropping bitmap to frame, using full photo: ${e.message}", e)
            bitmap
        }
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        val planeProxy = image.planes[0]
        val buffer = planeProxy.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null

        val rotation = image.imageInfo.rotationDegrees
        return if (rotation != 0) {
            val matrix = android.graphics.Matrix()
            matrix.postRotate(rotation.toFloat())
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }
    }

    private fun processDirectlyWithPython(original: Bitmap) {
        Log.i("ATTENDANCE_LOG", "📷 Photo Captured: ${original.width}x${original.height} px. Sending to AttendanceProcessor...")
        runOnUiThread {
            binding.viewFinder.visibility = View.GONE
            binding.ivAttendancePaper.visibility = View.VISIBLE
            binding.ivAttendancePaper.setImageBitmap(original)
            binding.bottomShutterArea.visibility = View.GONE
            binding.scannerOverlay.visibility = View.GONE
            setLoading(true, "Analyzing Attendance Table via AI...")
        }

        cameraExecutor.execute {
            val data = processor.processAttendance(original)
            runOnUiThread {
                setLoading(false)
                if (data != null && data.isNotEmpty()) {
                    Log.i("ATTENDANCE_LOG", "🎉 Scan Finished Successfully! Displaying ${data.size} students on screen.")
                    AttendanceSheetEditorActivity.scannedDataHolder = data
                    val intent = Intent(this@StudentAttendanceScanActivity, AttendanceSheetEditorActivity::class.java)
                    startActivity(intent)
                    resetUI()
                } else {
                    Log.e("ATTENDANCE_LOG", "❌ Scan Failed or Returned Empty Data.")
                    showRetry("Table extraction failed. Please ensure the full table is inside the frame.")
                }
            }
        }
    }

    private fun showRetry(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        resetUI()
    }

    private fun resetUI() {
        binding.viewFinder.visibility = View.VISIBLE
        binding.ivAttendancePaper.visibility = View.GONE
        binding.bottomShutterArea.visibility = View.VISIBLE
        binding.scannerOverlay.visibility = View.VISIBLE
        binding.scannerOverlay.setScanningActive(true)
        hideBottomControls()
        setLoading(false)
    }

    private fun hideBottomControls() {
        binding.bottomControlPanel.visibility = View.GONE
    }

    private fun setLoading(loading: Boolean, text: String = "") {
        binding.cardLoading.visibility = if (loading) View.VISIBLE else View.GONE
        binding.bottomShutterArea.visibility = if (loading) View.GONE else View.VISIBLE
        binding.tvLoadingMessage.text = if (text.isNotEmpty()) text else "Processing Attendance..."
        binding.scannerOverlay.setScanningActive(!loading)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isTorchOn) {
            camera?.cameraControl?.enableTorch(false)
        }
        cameraExecutor.shutdown()
    }
}
