package dk.lasse.karatecliprecorder.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import dk.lasse.karatecliprecorder.R
import kotlin.math.roundToInt

/**
 * Reusable speech-bubble view for Sensei dialogue on Home.
 *
 * Features:
 * - Rounded warm-white surface with subtle border
 * - Small directional tail pointing toward Sensei (down-left)
 * - Dynamic, localized text supporting optional emphasized opening title
 * - Auto-resizes with content
 * - Accessible as talkback text
 */
class SenseiSpeechBubbleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val surfacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.app_card_surface)
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.dp().toFloat()
        color = ContextCompat.getColor(context, R.color.app_border)
    }

    private val bubblePath = Path()
    private val bubbleBounds = RectF()

    private val titleView = TextView(context).apply {
        textSize = 15f
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        setTextColor(ContextCompat.getColor(context, R.color.app_text_primary))
        visibility = View.GONE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private val bodyView = TextView(context).apply {
        textSize = 14f
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        setTextColor(ContextCompat.getColor(context, R.color.app_text_primary))
        setLineSpacing(2.dp().toFloat(), 1.15f)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    init {
        setWillNotDraw(false)
        val contentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            addView(titleView, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(bodyView, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }

        val hPadding = 14.dp()
        val vPadding = 12.dp()
        val tailExtra = TAIL_HEIGHT_DP.dp()
        setPadding(hPadding, vPadding, hPadding, vPadding + tailExtra)

        addView(contentLayout, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun setMessage(message: SenseiMessage) {
        if (!message.title.isNullOrBlank()) {
            titleView.text = message.title
            titleView.visibility = View.VISIBLE
            bodyView.setPadding(0, 3.dp(), 0, 0)
            contentDescription = "${message.title} ${message.body}"
        } else {
            titleView.text = null
            titleView.visibility = View.GONE
            bodyView.setPadding(0, 0, 0, 0)
            contentDescription = message.body
        }
        bodyView.text = message.body
        requestLayout()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updatePath(w.toFloat(), h.toFloat())
    }

    private fun updatePath(width: Float, height: Float) {
        bubblePath.reset()
        val radius = CORNER_RADIUS_DP.dp().toFloat()
        val tailHeight = TAIL_HEIGHT_DP.dp().toFloat()
        val tailWidth = TAIL_WIDTH_DP.dp().toFloat()
        val strokeOffset = strokePaint.strokeWidth / 2f

        val rectBottom = height - tailHeight - strokeOffset
        val rectTop = strokeOffset
        val rectLeft = strokeOffset
        val rectRight = width - strokeOffset

        if (rectRight <= rectLeft || rectBottom <= rectTop) return

        bubbleBounds.set(rectLeft, rectTop, rectRight, rectBottom)

        // Directional tail at bottom-left pointing towards Sensei (down and left)
        val tailStartX = (rectLeft + radius).coerceAtLeast(rectLeft + 12.dp())
        val tailTipX = (tailStartX - 10.dp()).coerceAtLeast(strokeOffset)
        val tailTipY = height - strokeOffset
        val tailEndX = tailStartX + tailWidth

        // Construct closed path with rounded rect and integrated tail
        bubblePath.moveTo(rectLeft + radius, rectTop)
        bubblePath.lineTo(rectRight - radius, rectTop)
        bubblePath.quadTo(rectRight, rectTop, rectRight, rectTop + radius)
        bubblePath.lineTo(rectRight, rectBottom - radius)
        bubblePath.quadTo(rectRight, rectBottom, rectRight - radius, rectBottom)

        // Bottom edge toward tail
        bubblePath.lineTo(tailEndX, rectBottom)
        bubblePath.lineTo(tailTipX, tailTipY)
        bubblePath.lineTo(tailStartX, rectBottom)

        bubblePath.lineTo(rectLeft + radius, rectBottom)
        bubblePath.quadTo(rectLeft, rectBottom, rectLeft, rectBottom - radius)
        bubblePath.lineTo(rectLeft, rectTop + radius)
        bubblePath.quadTo(rectLeft, rectTop, rectLeft + radius, rectTop)
        bubblePath.close()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!bubblePath.isEmpty) {
            canvas.drawPath(bubblePath, surfacePaint)
            canvas.drawPath(bubblePath, strokePaint)
        }
    }

    private fun Int.dp() = (this * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val CORNER_RADIUS_DP = 14
        private const val TAIL_HEIGHT_DP = 10
        private const val TAIL_WIDTH_DP = 14
    }
}

