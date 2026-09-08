package dk.lasse.karatecliprecorder.profile

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Bundle
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import dk.lasse.karatecliprecorder.R
import kotlin.math.abs
import kotlin.math.roundToInt

/** Portraits slide over a fixed selection frame; selection commits only after settling. */
internal class AvatarCarouselLayout(context: Context, private val onMove: (Int) -> Unit) : FrameLayout(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var offset = 0f
    private var settling: ValueAnimator? = null
    private val gutter get() = dp(14)
    private val cardWidth get() = (width - gutter * 2) * 0.29f
    private val step get() = cardWidth + dp(10)

    init {
        setWillNotDraw(false)
        isFocusable = true
        contentDescription = "Character carousel"
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
        for (index in 0 until childCount) getChildAt(index).measure(
            MeasureSpec.makeMeasureSpec(((measuredWidth - gutter * 2) * 0.29f).roundToInt(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec((measuredHeight - dp(12)).coerceAtLeast(0), MeasureSpec.EXACTLY),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            val x = ((width - child.measuredWidth) / 2f + (index - 2) * step).roundToInt()
            child.layout(x, dp(6), x + child.measuredWidth, height - dp(6))
            child.translationX = offset
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2).toFloat()
        paint.color = ContextCompat.getColor(context, R.color.app_accent)
        val left = (width - cardWidth) / 2f - dp(3)
        canvas.drawRoundRect(left, dp(3).toFloat(), width - left, height - dp(3).toFloat(), dp(12).toFloat(), dp(12).toFloat(), paint)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        paint.style = Paint.Style.FILL
        val surface = ContextCompat.getColor(context, R.color.app_card_surface)
        val clear = surface and 0x00FFFFFF
        val fadeWidth = gutter + (width - gutter * 2) * 0.12f
        paint.shader = LinearGradient(gutter.toFloat(), 0f, fadeWidth, 0f, surface, clear, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, fadeWidth, height.toFloat(), paint)
        paint.shader = LinearGradient(width - fadeWidth, 0f, (width - gutter).toFloat(), 0f, clear, surface, Shader.TileMode.CLAMP)
        canvas.drawRect(width - fadeWidth, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
        paint.color = ContextCompat.getColor(context, R.color.app_text_secondary)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2).toFloat()
        paint.strokeCap = Paint.Cap.ROUND
        val centerY = height / 2f
        val arm = dp(4).toFloat()
        val firstPortraitLeft = (width - cardWidth) / 2f - step
        val edge = firstPortraitLeft / 2f - arm / 2f
        val rise = dp(6).toFloat()
        canvas.drawLine(edge + arm, centerY - rise, edge, centerY, paint)
        canvas.drawLine(edge, centerY, edge + arm, centerY + rise, paint)
        canvas.drawLine(width - edge - arm, centerY - rise, width - edge, centerY, paint)
        canvas.drawLine(width - edge, centerY, width - edge - arm, centerY + rise, paint)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (settling != null) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y }
            MotionEvent.ACTION_MOVE -> if (abs(event.x - downX) > touchSlop && abs(event.x - downX) > abs(event.y - downY)) {
                parent.requestDisallowInterceptTouchEvent(true)
                return true
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (settling != null) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y }
            MotionEvent.ACTION_MOVE -> moveTo((event.x - downX).coerceIn(-step, step))
            MotionEvent.ACTION_UP -> {
                parent.requestDisallowInterceptTouchEvent(false)
                selectOffset(AvatarCarouselModel.settledDelta(offset, step))
            }
            MotionEvent.ACTION_CANCEL -> { parent.requestDisallowInterceptTouchEvent(false); selectOffset(0) }
        }
        return true
    }

    fun selectOffset(delta: Int) {
        if (settling != null) return
        val target = delta.coerceIn(-2, 2)
        settling = ValueAnimator.ofFloat(offset, -target * step).apply {
            duration = 180
            addUpdateListener { moveTo(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    settling = null
                    moveTo(0f)
                    if (!cancelled && target != 0) onMove(target)
                }
            })
            start()
        }
    }

    private fun moveTo(value: Float) {
        offset = value
        for (index in 0 until childCount) getChildAt(index).translationX = value
        invalidate()
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isScrollable = true
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean = when (action) {
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> { selectOffset(1); true }
        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> { selectOffset(-1); true }
        else -> super.performAccessibilityAction(action, arguments)
    }

    override fun onDetachedFromWindow() {
        settling?.cancel()
        super.onDetachedFromWindow()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
