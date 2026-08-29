package com.school.attendance

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

/**
 * Modern Document Scanner Overlay View (CamScanner / Google Drive Style)
 *
 * Features:
 * 1. Darkened outer mask (scrim) with a clean transparent viewport in the center.
 * 2. Bold L-shaped glowing corner brackets at all 4 corners.
 * 3. Subtle dashed table grid alignment guides for attendance register rows & columns.
 * 4. Smooth animated laser scanning line with gradient trail.
 * 5. Instant capture flash animation feedback on photo capture.
 */
class DocumentScannerOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    val frameRect = RectF()

    private val density = resources.displayMetrics.density

    // Dimensions
    private val cornerLength = 36f * density
    private val cornerStrokeWidth = 4.5f * density
    private val cornerRadius = 14f * density
    private val frameStrokeWidth = 1.5f * density

    // Paints
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80000000") // 50% dark scrim
        style = Paint.Style.FILL
    }

    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private val frameBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2979FF") // Electric Blue
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }

    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2979FF") // Electric Blue Corners
        style = Paint.Style.STROKE
        strokeWidth = cornerStrokeWidth
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC003B1E")
        style = Paint.Style.FILL
    }

    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#332979FF") // Subtle blue grid lines
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }

    private val laserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2979FF")
        strokeWidth = 2.5f * density
        style = Paint.Style.STROKE
    }

    private val laserTrailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // Scanner Status
    private var isTableReady = false
    private var isFramingValid = false
    private var isLightValid = false

    // Laser Animation
    private var laserProgress = 0f
    private var laserAnimator: ValueAnimator? = null
    private var isScanning = true

    // Capture Flash Feedback
    private var isFlashActive = false
    private val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF") // Bright flash white
        style = Paint.Style.STROKE
        strokeWidth = cornerStrokeWidth * 1.5f
        strokeCap = Paint.Cap.ROUND
    }

    init {
        // Hardware acceleration with software layer for PorterDuff.CLEAR compatibility
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        initLaserAnimation()
    }

    private fun initLaserAnimation() {
        laserAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2200
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animation ->
                laserProgress = animation.animatedValue as Float
                invalidate()
            }
        }
        laserAnimator?.start()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        calculateFrameRect(w, h)
    }

    private fun calculateFrameRect(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return

        // 94% width for full attendance register table visibility
        val frameWidth = width * 0.94f
        // Attendance sheet is landscape format with ~1.38 : 1 aspect ratio (width : height)
        val frameHeight = (frameWidth / 1.38f).coerceAtMost(height * 0.56f)

        val left = (width - frameWidth) / 2f
        // Positioned in the upper-middle scanning area above bottom shutter controls
        val top = (height - frameHeight) * 0.40f
        val right = left + frameWidth
        val bottom = top + frameHeight

        frameRect.set(left, top, right, bottom)
    }



    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (frameRect.isEmpty) return

        // A. Outer darkened mask with transparent cutout
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), maskPaint)
        canvas.drawRoundRect(frameRect, cornerRadius, cornerRadius, clearPaint)

        // B. Draw green frame boundary
        val activeBorderPaint = if (isFlashActive) flashPaint else frameBorderPaint
        canvas.drawRoundRect(frameRect, cornerRadius, cornerRadius, activeBorderPaint)

        // C. Draw bold L-shaped corner brackets
        val activeCornerPaint = if (isFlashActive) flashPaint else cornerPaint
        drawCornerBrackets(canvas, activeCornerPaint)

        // D. Draw animated laser scanning beam
        if (isScanning && !isFlashActive) {
            drawLaserBeam(canvas)
        }
    }



    private fun drawCornerBrackets(canvas: Canvas, paint: Paint) {
        val l = frameRect.left
        val t = frameRect.top
        val r = frameRect.right
        val b = frameRect.bottom
        val cr = cornerRadius
        val cl = cornerLength

        // Top-Left Corner
        val pathTL = Path().apply {
            moveTo(l, t + cl)
            lineTo(l, t + cr)
            quadTo(l, t, l + cr, t)
            lineTo(l + cl, t)
        }
        canvas.drawPath(pathTL, paint)

        // Top-Right Corner
        val pathTR = Path().apply {
            moveTo(r - cl, t)
            lineTo(r - cr, t)
            quadTo(r, t, r, t + cr)
            lineTo(r, t + cl)
        }
        canvas.drawPath(pathTR, paint)

        // Bottom-Left Corner
        val pathBL = Path().apply {
            moveTo(l, b - cl)
            lineTo(l, b - cr)
            quadTo(l, b, l + cr, b)
            lineTo(l + cl, b)
        }
        canvas.drawPath(pathBL, paint)

        // Bottom-Right Corner
        val pathBR = Path().apply {
            moveTo(r - cl, b)
            lineTo(r - cr, b)
            quadTo(r, b, r, b - cr)
            lineTo(r, b - cl)
        }
        canvas.drawPath(pathBR, paint)
    }

    private fun drawLaserBeam(canvas: Canvas) {
        val laserY = frameRect.top + frameRect.height() * laserProgress
        val left = frameRect.left + cornerStrokeWidth
        val right = frameRect.right - cornerStrokeWidth

        // Draw laser trail gradient (glowing aura behind the laser)
        val trailHeight = 24f * density
        val trailTop = (laserY - trailHeight).coerceAtLeast(frameRect.top)
        val laserGlowColor = if (isTableReady) "#4000E676" else "#402979FF"
        val trailShader = LinearGradient(
            0f, trailTop, 0f, laserY,
            Color.TRANSPARENT, Color.parseColor(laserGlowColor),
            Shader.TileMode.CLAMP
        )
        laserTrailPaint.shader = trailShader
        canvas.drawRect(left, trailTop, right, laserY, laserTrailPaint)

        // Draw sharp laser line
        canvas.drawLine(left, laserY, right, laserY, laserPaint)
    }

    /**
     * Updates the scanner border & corner colors based on live camera conditions:
     * - isReady = true -> Vibrant Emerald Green (#00E676)
     * - isReady = false -> Electric Blue (#2979FF)
     */
    fun setScannerState(isReady: Boolean, hasFraming: Boolean, hasLight: Boolean) {
        isTableReady = isReady
        isFramingValid = hasFraming
        isLightValid = hasLight

        val targetColor = when {
            isReady -> Color.parseColor("#00E676") // Emerald Green (Ready & Aligned)
            !hasLight -> Color.parseColor("#FFD600") // Low light warning yellow
            else -> Color.parseColor("#2979FF") // Electric Blue Frame
        }
        val targetFrameColor = if (isReady) Color.parseColor("#E600E676") else Color.parseColor("#CC2979FF")
        
        cornerPaint.color = targetColor
        laserPaint.color = targetColor
        frameBorderPaint.color = targetFrameColor
        invalidate()
    }

    /**
     * Trigger a brief green flash pulse when photo capture begins
     */
    fun triggerCaptureFeedback() {
        isFlashActive = true
        invalidate()
        postDelayed({
            isFlashActive = false
            invalidate()
        }, 350)
    }

    fun setScanningActive(active: Boolean) {
        isScanning = active
        if (active) {
            if (laserAnimator?.isStarted != true) {
                laserAnimator?.start()
            }
        } else {
            laserAnimator?.cancel()
        }
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (isScanning && laserAnimator?.isStarted != true) {
            laserAnimator?.start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        laserAnimator?.cancel()
    }
}
