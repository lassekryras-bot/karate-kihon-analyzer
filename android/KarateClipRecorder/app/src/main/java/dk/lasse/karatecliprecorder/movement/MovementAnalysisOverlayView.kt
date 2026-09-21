package dk.lasse.karatecliprecorder.movement

import android.content.Context
import android.graphics.*
import android.view.View
import androidx.core.content.ContextCompat
import dk.lasse.karateanalyzer.core.PoseFrame
import dk.lasse.karateanalyzer.core.PoseLandmarkId
import dk.lasse.karateanalyzer.geometry.Alignment
import dk.lasse.karateanalyzer.geometry.AppliedPixelCrop
import dk.lasse.karateanalyzer.geometry.CanvasRect
import dk.lasse.karateanalyzer.geometry.ContentScaleMode
import dk.lasse.karateanalyzer.geometry.FrameGeometry
import dk.lasse.karateanalyzer.geometry.NormalizedCrop
import dk.lasse.karateanalyzer.geometry.OverlayCoordinateTransformer
import dk.lasse.karateanalyzer.geometry.ResolvedDisplayTransform
import dk.lasse.karateanalyzer.geometry.SourceNormalizedPoint
import dk.lasse.karateanalyzer.geometry.ZoomPan
import dk.lasse.karatecliprecorder.R
import dk.lasse.karatecliprecorder.training.BodySide
import kotlin.math.abs

/**
 * Overlay view that renders analyzer-provided geometry:
 * target lines, active arm segments, and impact markers.
 * Does not perform karate math; strictly converts normalized coordinates into pixels.
 */
class MovementAnalysisOverlayView(
    context: Context,
    private val timelineState: MovementTimelineState,
) : View(context), MovementTimelineState.TimelineListener {

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * density
        typeface = Typeface.DEFAULT_BOLD
    }

    private val primaryColor = ContextCompat.getColor(context, R.color.app_text_primary)
    private val accentColor = ContextCompat.getColor(context, R.color.app_accent)
    private val secondaryColor = ContextCompat.getColor(context, R.color.app_text_secondary)
    private val badgeBgColor = 0xCC1E1E1E.toInt()

    var overlayDefinition: MovementOverlayDefinition? = null
        set(value) {
            field = value
            invalidate()
        }

    var frames: List<PoseFrame> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var activeSide: BodySide = BodySide.UNKNOWN
        set(value) {
            field = value
            invalidate()
        }

    var showAllRays: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var videoBoundsProvider: () -> RectF? = { RectF(0f, 0f, width.toFloat(), height.toFloat()) }

    init {
        timelineState.addListener(this)
    }

    override fun onTimestampChanged(timestampUs: Long, progress: Double) {
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        timelineState.removeListener(this)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val overlay = overlayDefinition ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val rect = videoBoundsProvider() ?: return
        if (rect.width() <= 0f || rect.height() <= 0f) return

        val frameGeom = overlay.frameGeometry
        val imageBounds = CanvasRect(rect.left, rect.top, rect.right, rect.bottom)
        val viewportBounds = CanvasRect(0f, 0f, w, h)
        val displayTransform = ResolvedDisplayTransform(
            viewportBounds = viewportBounds,
            displayedImageBounds = imageBounds,
            contentClippingBounds = imageBounds.intersect(viewportBounds),
            scaleX = rect.width() / frameGeom.sourceWidth.toFloat(),
            scaleY = rect.height() / frameGeom.sourceHeight.toFloat(),
            selectedCrop = NormalizedCrop.FULL,
            appliedPixelCrop = AppliedPixelCrop(0, 0, frameGeom.sourceWidth, frameGeom.sourceHeight),
            contentScaleMode = ContentScaleMode.FIT,
            alignment = Alignment.CENTER,
            zoomPan = ZoomPan(),
            frameGeometry = frameGeom,
        )
        fun toPx(pt: PointF): PointF {
            val canvasPt = OverlayCoordinateTransformer.sourceToCanvas(
                SourceNormalizedPoint(pt.x, pt.y),
                displayTransform,
            )
            return PointF(canvasPt.x, canvasPt.y)
        }

        canvas.save()
        canvas.clipRect(rect)

        // Draw arm segments matching current timeline timestamp
        val curArm: OverlayArm? = if (frames.isNotEmpty() && activeSide != BodySide.UNKNOWN) {
            val curFrame = frames.minByOrNull { abs(it.timestampMs * 1000L - timelineState.currentTimestampUs) }
            val shoulderId = when (activeSide) {
                BodySide.LEFT -> PoseLandmarkId.LEFT_SHOULDER
                BodySide.RIGHT -> PoseLandmarkId.RIGHT_SHOULDER
                else -> PoseLandmarkId.RIGHT_SHOULDER
            }
            val elbowId = when (activeSide) {
                BodySide.LEFT -> PoseLandmarkId.LEFT_ELBOW
                BodySide.RIGHT -> PoseLandmarkId.RIGHT_ELBOW
                else -> PoseLandmarkId.RIGHT_ELBOW
            }
            val wristId = when (activeSide) {
                BodySide.LEFT -> PoseLandmarkId.LEFT_WRIST
                BodySide.RIGHT -> PoseLandmarkId.RIGHT_WRIST
                else -> PoseLandmarkId.RIGHT_WRIST
            }
            val sh = curFrame?.landmarks?.get(shoulderId)?.position
            val el = curFrame?.landmarks?.get(elbowId)?.position
            val wr = curFrame?.landmarks?.get(wristId)?.position
            if (sh != null && el != null && wr != null) {
                OverlayArm(activeSide.name, PointF(sh.x, sh.y), PointF(el.x, el.y), PointF(wr.x, wr.y))
            } else {
                overlay.arm
            }
        } else {
            overlay.arm
        }

        curArm?.let { arm ->
            val sh = toPx(arm.shoulder)
            val el = toPx(arm.elbow)
            val wr = toPx(arm.wrist)

            // Shoulder to Elbow
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f * density
            paint.color = primaryColor
            canvas.drawLine(sh.x, sh.y, el.x, el.y, paint)

            // Elbow to Wrist
            paint.strokeWidth = 3.5f * density
            paint.color = accentColor
            canvas.drawLine(el.x, el.y, wr.x, wr.y, paint)

            // Joint dots
            paint.style = Paint.Style.FILL
            paint.color = primaryColor
            canvas.drawCircle(sh.x, sh.y, 4f * density, paint)
            canvas.drawCircle(el.x, el.y, 4.5f * density, paint)

            // Striking fist
            paint.color = accentColor
            canvas.drawCircle(wr.x, wr.y, 7f * density, paint)
        }

        // Draw target rays (render only closest ray by default to avoid visual clutter)
        val raysToDraw = if (showAllRays) overlay.rays else overlay.rays.filter { it.isClosest }
        raysToDraw.forEach { ray ->
            val orig = toPx(ray.origin)
            val tgt = ray.targetPoint?.let { toPx(it) }

            if (tgt != null) {
                paint.style = Paint.Style.STROKE
                if (ray.isClosest) {
                    paint.color = accentColor
                    paint.strokeWidth = 2.5f * density
                    paint.pathEffect = null
                } else {
                    paint.color = secondaryColor
                    paint.strokeWidth = 1.5f * density
                    paint.pathEffect = DashPathEffect(floatArrayOf(8f * density, 6f * density), 0f)
                }

                canvas.drawLine(orig.x, orig.y, tgt.x, tgt.y, paint)
                paint.pathEffect = null

                // Target level label
                val label = when (ray.targetType) {
                    "JODAN" -> "Jōdan"
                    "CHUDAN" -> "Chūdan"
                    "GEDAN" -> "Gedan"
                    else -> ray.targetType
                }
                textPaint.color = if (ray.isClosest) accentColor else secondaryColor
                canvas.drawText(label, tgt.x + 4f * density, tgt.y + 4f * density, textPaint)
            }
        }
        canvas.restore()

        // Canonical impact frame indicator badge
        val impactUs = overlay.canonicalImpactUs
        if (impactUs != null && abs(timelineState.currentTimestampUs - impactUs) <= 60_000L) {
            val badgeText = "Impact Frame"
            textPaint.color = Color.WHITE
            val badgeW = textPaint.measureText(badgeText) + 16f * density
            val badgeH = 24f * density
            val bx = 16f * density
            val by = 16f * density

            paint.style = Paint.Style.FILL
            paint.color = badgeBgColor
            canvas.drawRoundRect(bx, by, bx + badgeW, by + badgeH, 6f * density, 6f * density, paint)

            canvas.drawText(badgeText, bx + 8f * density, by + 16f * density, textPaint)
        }
    }
}
