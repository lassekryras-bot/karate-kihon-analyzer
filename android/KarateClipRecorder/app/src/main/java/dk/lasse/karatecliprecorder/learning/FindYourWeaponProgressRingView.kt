package dk.lasse.karatecliprecorder.learning

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.min

class FindYourWeaponProgressRingView(context: Context) : View(context) {
    private val bounds = RectF()
    private var progress = 0f
    private var accepted = false

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun setProgress(progress: Float, accepted: Boolean) {
        val nextProgress = progress.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        if (this.progress == nextProgress && this.accepted == accepted) return
        this.progress = nextProgress
        this.accepted = accepted
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height).toFloat()
        if (size <= 0f) return

        val stroke = size * 0.11f
        trackPaint.strokeWidth = stroke
        progressPaint.strokeWidth = stroke
        checkPaint.strokeWidth = stroke * 0.82f
        progressPaint.color = if (accepted) Color.rgb(66, 210, 112) else Color.WHITE

        val inset = stroke / 2f + 2f
        val left = (width - size) / 2f + inset
        val top = (height - size) / 2f + inset
        bounds.set(left, top, left + size - inset * 2f, top + size - inset * 2f)

        canvas.drawOval(bounds, trackPaint)
        canvas.drawArc(bounds, -90f, 360f * progress, false, progressPaint)

        if (accepted) {
            val centerX = width / 2f
            val centerY = height / 2f
            val radius = bounds.width() / 2f
            canvas.drawLine(centerX - radius * 0.40f, centerY, centerX - radius * 0.10f, centerY + radius * 0.30f, checkPaint)
            canvas.drawLine(centerX - radius * 0.10f, centerY + radius * 0.30f, centerX + radius * 0.45f, centerY - radius * 0.28f, checkPaint)
        }
    }
}
