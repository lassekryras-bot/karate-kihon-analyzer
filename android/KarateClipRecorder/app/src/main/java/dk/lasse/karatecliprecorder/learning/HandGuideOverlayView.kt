package dk.lasse.karatecliprecorder.learning

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.min

class HandGuideOverlayView(context: Context) : View(context) {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(32, 33, 150, 243)
        style = Paint.Style.FILL
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 33, 150, 243)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 8f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerX = width / 2f
        val centerY = height / 2f
        val guideHeight = min(width, height) * 0.54f
        val palmWidth = guideHeight * 0.34f
        val palmHeight = guideHeight * 0.34f
        val palmTop = centerY - palmHeight * 0.18f
        val palm = RectF(
            centerX - palmWidth / 2f,
            palmTop,
            centerX + palmWidth / 2f,
            palmTop + palmHeight,
        )

        drawFinger(canvas, centerX - palmWidth * 0.42f, palm.top + palmHeight * 0.10f, guideHeight * 0.24f, palmWidth * 0.17f)
        drawFinger(canvas, centerX - palmWidth * 0.16f, palm.top - guideHeight * 0.14f, guideHeight * 0.33f, palmWidth * 0.17f)
        drawFinger(canvas, centerX + palmWidth * 0.08f, palm.top - guideHeight * 0.18f, guideHeight * 0.36f, palmWidth * 0.17f)
        drawFinger(canvas, centerX + palmWidth * 0.32f, palm.top - guideHeight * 0.11f, guideHeight * 0.30f, palmWidth * 0.17f)

        val thumb = RectF(
            palm.left - palmWidth * 0.34f,
            palm.top + palmHeight * 0.22f,
            palm.left + palmWidth * 0.10f,
            palm.top + palmHeight * 0.78f,
        )
        canvas.drawRoundRect(thumb, palmWidth * 0.16f, palmWidth * 0.16f, fillPaint)
        canvas.drawRoundRect(thumb, palmWidth * 0.16f, palmWidth * 0.16f, strokePaint)

        canvas.drawOval(palm, fillPaint)
        canvas.drawOval(palm, strokePaint)

        val wrist = RectF(
            centerX - palmWidth * 0.30f,
            palm.bottom - strokePaint.strokeWidth,
            centerX + palmWidth * 0.30f,
            palm.bottom + guideHeight * 0.16f,
        )
        canvas.drawRoundRect(wrist, palmWidth * 0.08f, palmWidth * 0.08f, fillPaint)
        canvas.drawRoundRect(wrist, palmWidth * 0.08f, palmWidth * 0.08f, strokePaint)
    }

    private fun drawFinger(canvas: Canvas, centerX: Float, baseY: Float, height: Float, width: Float) {
        val finger = RectF(
            centerX - width / 2f,
            baseY - height,
            centerX + width / 2f,
            baseY,
        )
        canvas.drawRoundRect(finger, width / 2f, width / 2f, fillPaint)
        canvas.drawRoundRect(finger, width / 2f, width / 2f, strokePaint)
    }
}
