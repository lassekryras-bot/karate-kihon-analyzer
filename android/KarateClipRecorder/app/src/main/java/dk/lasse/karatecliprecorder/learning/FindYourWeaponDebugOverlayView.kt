package dk.lasse.karatecliprecorder.learning

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PointF
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

class FindYourWeaponDebugOverlayView(context: Context) : View(context) {
    private var overlay: FindYourWeaponDebugOverlay? = null
    private var showDebugGuides = false

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

    private val middleLimitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(245, 255, 126, 72)
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(12f, 12f), 0f)
    }

    private val thumbInsidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 78, 201, 111)
        style = Paint.Style.FILL
    }

    private val thumbOutsidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 255, 90, 90)
        style = Paint.Style.FILL
    }

    private val knuckleArrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(250, 255, 214, 64)
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun setOverlay(
        overlay: FindYourWeaponDebugOverlay?,
        showDebugGuides: Boolean = false,
    ) {
        if (this.overlay == overlay && this.showDebugGuides == showDebugGuides) return
        this.overlay = overlay
        this.showDebugGuides = showDebugGuides
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = overlay ?: return
        if (width <= 0 || height <= 0 || current.inputWidth <= 0 || current.inputHeight <= 0) return

        if (showDebugGuides) {
            val boundaryStartX = current.boundaryStartX
            val boundaryStartY = current.boundaryStartY
            val boundaryEndX = current.boundaryEndX
            val boundaryEndY = current.boundaryEndY
            if (boundaryStartX != null && boundaryStartY != null && boundaryEndX != null && boundaryEndY != null) {
                val start = current.map(boundaryStartX, boundaryStartY)
                val end = current.map(boundaryEndX, boundaryEndY)
                val extended = extend(start, end)
                canvas.drawLine(extended.first.x, extended.first.y, extended.second.x, extended.second.y, shadowPaint)
                canvas.drawLine(extended.first.x, extended.first.y, extended.second.x, extended.second.y, linePaint)
            }

            val middleBoundaryStartX = current.middleBoundaryStartX
            val middleBoundaryStartY = current.middleBoundaryStartY
            val middleBoundaryEndX = current.middleBoundaryEndX
            val middleBoundaryEndY = current.middleBoundaryEndY
            if (
                middleBoundaryStartX != null &&
                middleBoundaryStartY != null &&
                middleBoundaryEndX != null &&
                middleBoundaryEndY != null
            ) {
                val start = current.map(middleBoundaryStartX, middleBoundaryStartY)
                val end = current.map(middleBoundaryEndX, middleBoundaryEndY)
                val extended = extend(start, end)
                canvas.drawLine(extended.first.x, extended.first.y, extended.second.x, extended.second.y, shadowPaint)
                canvas.drawLine(extended.first.x, extended.first.y, extended.second.x, extended.second.y, middleLimitPaint)
            }

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

        current.highlightPoints.forEachIndexed { index, point ->
            val mapped = current.map(point.x, point.y)
            drawKnuckleArrow(canvas, index, mapped)
        }
    }

    private fun drawKnuckleArrow(canvas: Canvas, index: Int, target: PointF) {
        val horizontalOffset = if (index % 2 == 0) -86f else 86f
        val start = PointF(
            (target.x + horizontalOffset).coerceIn(24f, width - 24f),
            (target.y - 132f).coerceIn(24f, height - 24f),
        )
        drawArrow(canvas, start, target, shadowPaint)
        drawArrow(canvas, start, target, knuckleArrowPaint)
    }

    private fun drawArrow(canvas: Canvas, start: PointF, end: PointF, paint: Paint) {
        canvas.drawLine(start.x, start.y, end.x, end.y, paint)

        val angle = atan2(end.y - start.y, end.x - start.x)
        val headLength = 34f
        val headAngle = Math.toRadians(28.0).toFloat()
        for (direction in listOf(-1f, 1f)) {
            val wingAngle = angle + direction * headAngle
            val wingX = end.x - headLength * cos(wingAngle)
            val wingY = end.y - headLength * sin(wingAngle)
            canvas.drawLine(end.x, end.y, wingX, wingY, paint)
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
