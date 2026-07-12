package dk.lasse.karatecliprecorder.learning

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PointF
import android.view.View
import kotlin.math.max

class FindYourWeaponDebugOverlayView(context: Context) : View(context) {
    private var overlay: FindYourWeaponDebugOverlay? = null

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 0, 0, 0)
        strokeWidth = 9f
        strokeCap = Paint.Cap.ROUND
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(245, 255, 214, 64)
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(22f, 12f), 0f)
    }

    private val thumbInsidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 78, 201, 111)
        style = Paint.Style.FILL
    }

    private val thumbOutsidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 255, 90, 90)
        style = Paint.Style.FILL
    }

    fun setOverlay(overlay: FindYourWeaponDebugOverlay?) {
        if (this.overlay == overlay) return
        this.overlay = overlay
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = overlay ?: return
        if (width <= 0 || height <= 0 || current.inputWidth <= 0 || current.inputHeight <= 0) return

        val start = current.map(current.boundaryStartX, current.boundaryStartY)
        val end = current.map(current.boundaryEndX, current.boundaryEndY)
        val extended = extend(start, end)
        canvas.drawLine(extended.first.x, extended.first.y, extended.second.x, extended.second.y, shadowPaint)
        canvas.drawLine(extended.first.x, extended.first.y, extended.second.x, extended.second.y, linePaint)

        val thumbX = current.thumbTipX
        val thumbY = current.thumbTipY
        if (thumbX != null && thumbY != null) {
            val thumb = current.map(thumbX, thumbY)
            canvas.drawCircle(thumb.x, thumb.y, 13f, shadowPaint)
            canvas.drawCircle(
                thumb.x,
                thumb.y,
                9f,
                if (current.thumbInsideBoundary == true) thumbInsidePaint else thumbOutsidePaint,
            )
        }
    }

    private fun FindYourWeaponDebugOverlay.map(normalizedX: Float, normalizedY: Float): PointF {
        val scale = max(width / inputWidth.toFloat(), height / inputHeight.toFloat())
        val renderedWidth = inputWidth * scale
        val renderedHeight = inputHeight * scale
        val offsetX = (width - renderedWidth) / 2f
        val offsetY = (height - renderedHeight) / 2f
        return PointF(
            offsetX + normalizedX * inputWidth * scale,
            offsetY + normalizedY * inputHeight * scale,
        )
    }

    private fun extend(start: PointF, end: PointF): Pair<PointF, PointF> {
        val dx = end.x - start.x
        val dy = end.y - start.y
        if (!dx.isFinite() || !dy.isFinite() || (dx == 0f && dy == 0f)) return start to end
        val extraBefore = 0.55f
        val extraAfter = 1.10f
        return PointF(start.x - dx * extraBefore, start.y - dy * extraBefore) to
            PointF(end.x + dx * extraAfter, end.y + dy * extraAfter)
    }
}
