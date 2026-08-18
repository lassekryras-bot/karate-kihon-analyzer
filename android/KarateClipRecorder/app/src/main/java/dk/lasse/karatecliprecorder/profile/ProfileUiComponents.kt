package dk.lasse.karatecliprecorder.profile

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import dk.lasse.karatecliprecorder.R

internal fun Context.profileText(text: String, size: Float, bold: Boolean = false, gravity: Int = Gravity.START) =
    TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(ContextCompat.getColor(context, R.color.app_text_primary))
        typeface = android.graphics.Typeface.create("sans-serif", if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        this.gravity = gravity
    }

internal class GradientPreviewView(
    context: Context,
    private val colors: IntArray,
) : android.view.View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        paint.shader = LinearGradient(0f, 0f, width.toFloat(), 0f, colors, null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), height / 2f, height / 2f, paint)
    }
}

/** Keeps child taps intact while intercepting deliberate horizontal carousel swipes. */
internal class AvatarCarouselLayout(
    context: Context,
    private val onMove: (Int) -> Unit,
) : LinearLayout(context) {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (kotlin.math.abs(dx) > touchSlop && kotlin.math.abs(dx) > kotlin.math.abs(dy)) return true
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val dx = event.x - downX
            if (kotlin.math.abs(dx) > touchSlop) onMove(if (dx < 0) 1 else -1) else performClick()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}

internal fun Context.skinGradientView() = GradientPreviewView(this, intArrayOf(
    AvatarPalette.skin(0f), AvatarPalette.skin(0.25f), AvatarPalette.skin(0.5f),
    AvatarPalette.skin(0.75f), AvatarPalette.skin(1f),
))

internal fun Context.hairGradientView() = GradientPreviewView(this, intArrayOf(
    AvatarPalette.hair(0f), AvatarPalette.hair(0.2f), AvatarPalette.hair(0.4f),
    AvatarPalette.hair(0.6f), AvatarPalette.hair(0.8f), AvatarPalette.hair(1f),
))

internal fun Context.profileSectionLabel(text: String) = profileText(text, 12f, bold = true).apply {
    letterSpacing = 0.08f
    setTextColor(ContextCompat.getColor(context, R.color.app_text_secondary))
    setPadding(context.dp(2), context.dp(22), 0, context.dp(8))
}

internal fun Context.primaryProfileButton(text: String, onClick: () -> Unit) = profileText(
    text, 17f, bold = true, gravity = Gravity.CENTER,
).apply {
    setTextColor(android.graphics.Color.WHITE)
    background = android.graphics.drawable.GradientDrawable().apply {
        setColor(ContextCompat.getColor(context, R.color.app_accent))
        cornerRadius = context.dp(12).toFloat()
    }
    isClickable = true
    isFocusable = true
    setOnClickListener { onClick() }
}

internal fun Context.outlinedChoice(text: String, selected: Boolean, onClick: () -> Unit) = profileText(
    text, 15f, bold = selected, gravity = Gravity.CENTER,
).apply {
    val accent = ContextCompat.getColor(context, R.color.app_accent)
    if (selected) setTextColor(accent)
    background = android.graphics.drawable.GradientDrawable().apply {
        setColor(ContextCompat.getColor(context, R.color.app_card_surface))
        cornerRadius = context.dp(10).toFloat()
        setStroke(context.dp(if (selected) 2 else 1), if (selected) accent else ContextCompat.getColor(context, R.color.app_border))
    }
    isClickable = true
    isFocusable = true
    setOnClickListener { onClick() }
}

internal fun android.view.View.dp(value: Int) = (value * resources.displayMetrics.density).toInt()
internal fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()
