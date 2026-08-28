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
        color = Color.parseColor("#99000000") // 60% black scrim
        style = Paint.Style.FILL
    }

    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private val frameBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2979FF") // Electric Blue Table Boundary
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }

    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2979FF") // Blue Corners
        style = Paint.Style.STROKE
        strokeWidth = cornerStrokeWidth
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC1A237E") // Dark Blue Pill
        style = Paint.Style.FILL
    }

    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#26FFFFFF") // 15% white dashed grid
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 6f * density), 0f)
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
        color = Color.parseColor("#00E676") // Bright success emerald
        style = Paint.Style.STROKE
        strokeWidth = cornerStrokeWidth * 1.4f
        strokeCap = Paint.Cap.ROUND
    }

    init {
        // Hardware acceleration with software layer for PorterDuff.CLEAR compatibility
        setLayerType(LAYER_TYPE_HARDWARE, null)
        initLaserAnimation()
    }

    private fun initLaserAnimation() {
        laserAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2400
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

        // 1. Draw outer darkened mask with rounded rect cutout in center
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), maskPaint)
        canvas.drawRoundRect(frameRect, cornerRadius, cornerRadius, clearPaint)

        // 2. Draw Table Guide Boundary (Blue #2979FF when aligning, Emerald Green #00E676 when ready)
        canvas.drawRoundRect(frameRect, cornerRadius, cornerRadius, frameBorderPaint)

        // 3. Draw Top Table Guide Badge Tag attached to top-left of the blue box
        drawTableGuideBadge(canvas)

        // 4. Draw subtle alignment grid lines (attendance table rows & columns hint)
        drawTableGridGuides(canvas)

        // 5. Draw bold L-shaped corner brackets (CamScanner / Google Drive style)
        val activeCornerPaint = if (isFlashActive) flashPaint else cornerPaint
        drawCornerBrackets(canvas, activeCornerPaint)

        // 6. Draw animated laser scanning beam if scanning is active
        if (isScanning && !isFlashActive) {
            drawLaserBeam(canvas)
        }
    }

    private fun drawTableGuideBadge(canvas: Canvas) {
        val badgeText = if (isTableReady) "✓ TABLE 100% ALIGNED" else "🔵 ALIGN ATTENDANCE TABLE"
        val badgeW = badgeTextPaint.measureText(badgeText) + 24f * density
        val badgeH = 22f * density
        val badgeL = frameRect.left + 12f * density
        val badgeT = frameRect.top - badgeH / 2f
        val badgeR = badgeL + badgeW
        val badgeB = badgeT + badgeH
        val badgeRect = RectF(badgeL, badgeT, badgeR, badgeB)

        badgeBgPaint.color = if (isTableReady) Color.parseColor("#E6004D25") else Color.parseColor("#E60D1B4D")
        canvas.drawRoundRect(badgeRect, 11f * density, 11f * density, badgeBgPaint)

        val textY = badgeT + badgeH / 2f - (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2f
        canvas.drawText(badgeText, badgeL + badgeW / 2f, textY, badgeTextPaint)
    }

    private fun drawTableGridGuides(canvas: Canvas) {
        val left = frameRect.left
        val right = frameRect.right
        val top = frameRect.top
        val bottom = frameRect.bottom
        val h = frameRect.height()
        val w = frameRect.width()

        // 3 horizontal guide lines (header row, middle rows)
        val row1 = top + h * 0.22f
        val row2 = top + h * 0.50f
        val row3 = top + h * 0.78f
        canvas.drawLine(left + 8f * density, row1, right - 8f * density, row1, gridPaint)
        canvas.drawLine(left + 8f * density, row2, right - 8f * density, row2, gridPaint)
        canvas.drawLine(left + 8f * density, row3, right - 8f * density, row3, gridPaint)

        // 2 vertical guide lines (names column, dates/status section)
        val col1 = left + w * 0.28f
        val col2 = left + w * 0.75f
        canvas.drawLine(col1, top + 8f * density, col1, bottom - 8f * density, gridPaint)
        canvas.drawLine(col2, top + 8f * density, col2, bottom - 8f * density, gridPaint)
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
            isReady -> Color.parseColor("#00E676") // Emerald Green (Ready)
            !hasFraming -> Color.parseColor("#2979FF") // Electric Blue (Aligning)
            !hasLight -> Color.parseColor("#FFD600") // Low light yellow
            else -> Color.parseColor("#2979FF") // Electric Blue Table Guide
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
