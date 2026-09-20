package dk.lasse.karatecliprecorder.movement

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import dk.lasse.karatecliprecorder.R
import kotlin.math.abs

/**
 * Graph renderer for Movement Presentation Player.
 * Synchronizes with MovementTimelineState.
 */
class MovementGraphView(
    context: Context,
    private val timelineState: MovementTimelineState,
    var isExpanded: Boolean = false,
) : View(context), MovementTimelineState.TimelineListener {

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * density
        typeface = Typeface.DEFAULT
    }

    private val inkColor = ContextCompat.getColor(context, R.color.app_text_primary)
    private val secondaryColor = ContextCompat.getColor(context, R.color.app_text_secondary)
    private val accentColor = ContextCompat.getColor(context, R.color.app_accent)
    private val gridColor = ContextCompat.getColor(context, R.color.app_divider)

    var plotDefinition: MovementPlotDefinition? = null
        set(value) {
            field = value
            contentDescription = if (value != null && value.samples.isNotEmpty()) {
                value.accessibleSummary
            } else {
                "No graph evidence available for this selection."
            }
            invalidate()
        }

    init {
        timelineState.addListener(this)
        isFocusable = true
    }

    override fun onTimestampChanged(timestampUs: Long, progress: Double) {
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        timelineState.removeListener(this)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isExpanded) return false
        val plot = plotDefinition ?: return false
        if (plot.samples.isEmpty()) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                timelineState.setPlaying(false)
                parent?.requestDisallowInterceptTouchEvent(true)
                val left = paddingLeft + 48f * density
                val right = width - paddingRight - 16f * density
                val graphWidth = right - left
                if (graphWidth > 0) {
                    val ratio = ((event.x - left) / graphWidth).coerceIn(0f, 1f)
                    val t = timelineState.playbackStartUs + (ratio * timelineState.durationUs).toLong()
                    timelineState.seekUs(t)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val plot = plotDefinition
        if (plot == null || plot.samples.isEmpty()) {
            drawUnavailable(canvas)
            return
        }

        val left = paddingLeft + 48f * density
        val top = paddingTop + 28f * density
        val right = width - paddingRight - 16f * density
        val bottom = height - paddingBottom - 28f * density
        val graphW = right - left
        val graphH = bottom - top
        if (graphW <= 0 || graphH <= 0) return

        // Header label
        textPaint.color = inkColor
        textPaint.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText("${plot.label} (${plot.unit})", left, top - 10f * density, textPaint)

        // Min / Max values
        val minVal = if (plot.includeZero) 0.0 else plot.minValue
        val maxVal = maxOf(plot.maxValue, minVal + 0.001)
        val valRange = maxVal - minVal

        fun xForTime(timeUs: Long): Float {
            val ratio = if (timelineState.durationUs > 0) {
                (timeUs - timelineState.playbackStartUs).toFloat() / timelineState.durationUs.toFloat()
            } else 0f
            return left + ratio.coerceIn(0f, 1f) * graphW
        }

        fun yForVal(v: Double): Float {
            val ratio = ((v - minVal) / valRange).toFloat()
            return bottom - ratio.coerceIn(0f, 1f) * graphH
        }

        // Axes and grid
        paint.color = gridColor
        paint.strokeWidth = 1f * density
        paint.style = Paint.Style.STROKE

        // Bottom baseline
        canvas.drawLine(left, bottom, right, bottom, paint)
        // Top boundary
        canvas.drawLine(left, top, right, top, paint)

        // Axis labels
        textPaint.color = secondaryColor
        textPaint.typeface = Typeface.DEFAULT
        canvas.drawText("%.1f".format(minVal), paddingLeft + 4f * density, bottom + 4f * density, textPaint)
        canvas.drawText("%.1f".format(maxVal), paddingLeft + 4f * density, top + 10f * density, textPaint)

        val startSec = timelineState.playbackStartUs / 1_000_000.0
        val endSec = timelineState.playbackEndUs / 1_000_000.0
        canvas.drawText("${"%.2f".format(startSec)} s", left, bottom + 18f * density, textPaint)
        val endText = "${"%.2f".format(endSec)} s"
        val endTextW = textPaint.measureText(endText)
        canvas.drawText(endText, right - endTextW, bottom + 18f * density, textPaint)

        // Plot curve
        val path = Path()
        plot.samples.forEachIndexed { i, s ->
            val px = xForTime(s.timestampUs)
            val py = yForVal(s.value)
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        paint.color = accentColor
        paint.strokeWidth = 2.5f * density
        paint.style = Paint.Style.STROKE
        canvas.drawPath(path, paint)

        // Peak marker ring
        plot.peakSample?.let { peak ->
            val pkX = xForTime(peak.timestampUs)
            val pkY = yForVal(peak.value)
            paint.color = inkColor
            paint.strokeWidth = 2f * density
            paint.style = Paint.Style.STROKE
            canvas.drawCircle(pkX, pkY, 7f * density, paint)
        }

        // Timeline cursor
        val cursorTime = timelineState.currentTimestampUs
        val cursorX = xForTime(cursorTime)
        paint.color = inkColor
        paint.strokeWidth = 1.5f * density
        paint.style = Paint.Style.STROKE
        canvas.drawLine(cursorX, top, cursorX, bottom, paint)

        // Find nearest sample to cursor
        val nearestSample = plot.samples.minByOrNull { abs(it.timestampUs - cursorTime) }
        if (nearestSample != null) {
            val dotY = yForVal(nearestSample.value)
            paint.style = Paint.Style.FILL
            paint.color = accentColor
            canvas.drawCircle(cursorX, dotY, 4.5f * density, paint)

            // Current sample readout
            val readout = "${"%.1f".format(nearestSample.value)} ${plot.unit}"
            textPaint.color = inkColor
            textPaint.typeface = Typeface.DEFAULT_BOLD
            val readoutW = textPaint.measureText(readout)
            val textX = (cursorX + 6f * density).coerceAtMost(right - readoutW)
            canvas.drawText(readout, textX, top + 16f * density, textPaint)
        }
    }

    private fun drawUnavailable(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        textPaint.color = secondaryColor
        textPaint.typeface = Typeface.DEFAULT
        val msg = "No graph evidence available for this selection."
        val textW = textPaint.measureText(msg)
        canvas.drawText(msg, cx - textW / 2f, cy, textPaint)
    }
}
