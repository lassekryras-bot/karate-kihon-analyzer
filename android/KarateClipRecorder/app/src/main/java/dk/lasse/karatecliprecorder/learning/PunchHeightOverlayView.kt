package dk.lasse.karatecliprecorder.learning

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import dk.lasse.karateanalyzer.core.LandmarkSource
import dk.lasse.karateanalyzer.core.Point3
import dk.lasse.karateanalyzer.core.PunchHeightGuidanceState
import kotlin.math.max
import kotlin.math.sqrt

class PunchHeightOverlayView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private var sessionState: PunchHeightSessionState? = null
    private var debug = false

    fun setSessionState(state: PunchHeightSessionState?, debugEnabled: Boolean) {
        sessionState = state
        debug = debugEnabled
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val state = sessionState ?: return
        if (state.stage == PunchHeightSessionStage.SESSION_SETUP) drawSetup(canvas, state)
        val evaluation = state.evaluation
        if (evaluation != null) {
            drawTarget(canvas, state)
            drawArm(canvas, state)
            drawHoldProgress(canvas, evaluation.holdProgress)
        }
        if (debug) drawDebug(canvas, state)
    }

    private fun drawSetup(canvas: Canvas, state: PunchHeightSessionState) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f.dp()
        paint.color = if (state.setupEvaluation?.usable == true) GREEN else WHITE
        canvas.drawRect(width * 0.04f, height * 0.04f, width * 0.96f, height * 0.96f, paint)
        drawHoldProgress(canvas, state.setupEvaluation?.progress ?: 0f)
    }

    private fun drawTarget(canvas: Canvas, state: PunchHeightSessionState) {
        val evaluation = state.evaluation ?: return
        val target = evaluation.target ?: return
        val center = map(target.targetPoint, state)
        val axis = evaluation.bodyReference.torsoAxis
        val perpendicular = normalized(Point3(-axis.y, axis.x, 0f))
        val halfBandLength = evaluation.bodyReference.torsoLength * 0.42f
        val toleranceOffset = axis * (evaluation.bodyReference.torsoLength * target.tolerance)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f.dp()
        paint.color = CYAN
        listOf(target.targetPoint - toleranceOffset, target.targetPoint + toleranceOffset).forEach { edge ->
            val a = map(edge - perpendicular * halfBandLength, state)
            val b = map(edge + perpendicular * halfBandLength, state)
            canvas.drawLine(a.x, a.y, b.x, b.y, paint)
        }
        paint.style = Paint.Style.FILL
        paint.textSize = 18f.dp()
        canvas.drawText(target.type.name, center.x + 10f.dp(), center.y - 10f.dp(), paint)
    }

    private fun drawArm(canvas: Canvas, state: PunchHeightSessionState) {
        val evaluation = state.evaluation ?: return
        val shoulder = evaluation.shoulderPoint?.let { map(it, state) }
        val elbow = evaluation.elbowPoint?.let { map(it, state) }
        val wrist = evaluation.wristPoint?.let { map(it, state) }
        val fist = evaluation.fistCenter?.let { map(it, state) }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5f.dp()
        paint.color = if ((evaluation.elbowAngleDegrees ?: 0f) >= 165f) GREEN else ORANGE
        if (shoulder != null && elbow != null) canvas.drawLine(shoulder.x, shoulder.y, elbow.x, elbow.y, paint)
        if (elbow != null && wrist != null) canvas.drawLine(elbow.x, elbow.y, wrist.x, wrist.y, paint)
        if (elbow != null) {
            paint.style = Paint.Style.FILL
            canvas.drawCircle(elbow.x, elbow.y, 8f.dp(), paint)
        }
        if (fist != null) {
            paint.color = when (evaluation.guidance) {
                PunchHeightGuidanceState.CAPTURE_READY,
                PunchHeightGuidanceState.CORRECT_AND_HOLDING -> GREEN
                PunchHeightGuidanceState.FIST_TOO_HIGH,
                PunchHeightGuidanceState.FIST_TOO_LOW -> ORANGE
                else -> RED
            }
            canvas.drawCircle(fist.x, fist.y, 13f.dp(), paint)
            val targetPoint = evaluation.target?.targetPoint?.let { map(it, state) }
            if (targetPoint != null && evaluation.guidance in setOf(
                    PunchHeightGuidanceState.FIST_TOO_HIGH,
                    PunchHeightGuidanceState.FIST_TOO_LOW,
                )
            ) drawArrow(canvas, fist, targetPoint)
        }
    }

    private fun drawArrow(canvas: Canvas, from: ScreenPoint, to: ScreenPoint) {
        paint.color = ORANGE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f.dp()
        canvas.drawLine(from.x, from.y, to.x, to.y, paint)
        val dx = to.x - from.x
        val dy = to.y - from.y
        val length = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
        val ux = dx / length
        val uy = dy / length
        val size = 14f.dp()
        val path = Path().apply {
            moveTo(to.x, to.y)
            lineTo(to.x - ux * size - uy * size * 0.55f, to.y - uy * size + ux * size * 0.55f)
            lineTo(to.x - ux * size + uy * size * 0.55f, to.y - uy * size - ux * size * 0.55f)
            close()
        }
        paint.style = Paint.Style.FILL
        canvas.drawPath(path, paint)
    }

    private fun drawHoldProgress(canvas: Canvas, progress: Float) {
        val left = 24f.dp()
        val right = width - 24f.dp()
        val top = 28f.dp()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 8f.dp()
        paint.color = 0x88000000.toInt()
        canvas.drawLine(left, top, right, top, paint)
        paint.color = if (progress >= 1f) GREEN else CYAN
        canvas.drawLine(left, top, left + (right - left) * progress.coerceIn(0f, 1f), top, paint)
    }

    private fun drawDebug(canvas: Canvas, state: PunchHeightSessionState) {
        state.poseFrame?.landmarks?.values?.forEach { sample ->
            val point = sample.position ?: return@forEach
            val mapped = map(point, state)
            paint.style = Paint.Style.FILL
            paint.color = when {
                sample.source == LandmarkSource.PREDICTED -> YELLOW
                sample.isObserved() -> GREEN
                else -> RED
            }
            canvas.drawCircle(mapped.x, mapped.y, 3.5f.dp(), paint)
        }
        val evaluation = state.evaluation ?: return
        val body = evaluation.bodyReference
        val shoulder = map(body.shoulderPoint, state)
        val hip = map(body.hipPoint, state)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f.dp()
        paint.color = YELLOW
        canvas.drawLine(shoulder.x, shoulder.y, hip.x, hip.y, paint)
        evaluation.target?.chinEstimate?.let { chin ->
            val nose = state.poseFrame?.landmarks?.get(dk.lasse.karateanalyzer.core.PoseLandmarkId.NOSE)?.position
            val rawChin = chin.rawPoint
            if (nose != null && rawChin != null) {
                val start = map(nose, state)
                val end = map(rawChin, state)
                paint.color = YELLOW
                canvas.drawLine(start.x, start.y, end.x, end.y, paint)
            }
            chin.rawPoint?.let {
                val p = map(it, state)
                paint.color = RED
                canvas.drawCircle(p.x, p.y, 8f.dp(), paint)
            }
            chin.smoothedPoint?.let {
                val p = map(it, state)
                paint.color = GREEN
                canvas.drawCircle(p.x, p.y, 10f.dp(), paint)
            }
        }
    }

    private fun map(point: Point3, state: PunchHeightSessionState): ScreenPoint {
        val inputWidth = state.inputWidth.toFloat().coerceAtLeast(1f)
        val inputHeight = state.inputHeight.toFloat().coerceAtLeast(1f)
        val scale = max(width / inputWidth, height / inputHeight)
        val offsetX = (width - inputWidth * scale) * 0.5f
        val offsetY = (height - inputHeight * scale) * 0.5f
        return ScreenPoint(offsetX + point.x * inputWidth * scale, offsetY + point.y * inputHeight * scale)
    }

    private fun normalized(point: Point3): Point3 {
        val length = sqrt(point.x * point.x + point.y * point.y).coerceAtLeast(0.0001f)
        return Point3(point.x / length, point.y / length, 0f)
    }

    private fun Float.dp() = this * resources.displayMetrics.density
    private data class ScreenPoint(val x: Float, val y: Float)

    companion object {
        private const val WHITE = Color.WHITE
        private const val RED = 0xffff5252.toInt()
        private const val ORANGE = 0xffffab40.toInt()
        private const val YELLOW = 0xffffeb3b.toInt()
        private const val GREEN = 0xff4caf50.toInt()
        private const val CYAN = 0xff00e5ff.toInt()
    }
}
