package dk.lasse.karatecliprecorder.skillcoach

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.util.Log
import android.util.Xml
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.RawRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.PathParser
import dk.lasse.karatecliprecorder.R
import dk.lasse.karatecliprecorder.artwork.FullColorSvgView
import kotlin.math.roundToInt

/**
 * Personalized Guidance / Sensei UI Contract
 *
 * The Sensei is a stable coaching character, not flexible decorative artwork. Every guidance
 * state keeps the same render scale, top-center anchor, and visible composition, including the
 * black belt and knot. The speech bubble likewise has a fixed footprint relative to the Sensei,
 * fixed text size, and a three-line maximum. Do not resize either element or shrink speech text
 * to accommodate a message; rewrite dialogue more concisely instead.
 *
 * First-person, encouraging, or personality-led language belongs in the bubble. Factual app
 * information, analysis, recommendations, and longer explanations belong in the card copy.
 * Future Sensei poses may change the artwork, but must retain this scale and layout footprint.
 * The reusable artwork stays localization-safe: all dialogue is rendered at runtime.
 */
@SuppressLint("ViewConstructor")
class SenseiGuideView(
    context: Context,
    speech: String,
) : FrameLayout(context) {
    private val speechView = TextView(context).apply {
        text = speech
        textSize = SPEECH_TEXT_SIZE_SP
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        gravity = Gravity.CENTER
        setTextColor(ContextCompat.getColor(context, R.color.skill_coach_speech_text))
        includeFontPadding = false
        maxLines = SPEECH_MAX_LINES
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    init {
        minimumHeight = STANDARD_HEIGHT_DP.dp()
        clipChildren = true
        clipToPadding = true
        contentDescription = "Sensei says: $speech"
        // FullColorSvgView renders mirrored character with canvas.scale(-scale, scale)
        addView(FullColorSvgView(context, R.raw.sensei_speaking_blank_bubble, mirrored = true).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(ARTWORK_SIZE_DP.dp(), ARTWORK_SIZE_DP.dp(), ARTWORK_GRAVITY))
        addView(speechView)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val bubble = SenseiArtworkGeometry.speechBounds(measuredWidth, resources.displayMetrics.density)
        speechView.measure(
            MeasureSpec.makeMeasureSpec(bubble.width(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(bubble.height(), MeasureSpec.EXACTLY),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val bubble = SenseiArtworkGeometry.speechBounds(width, resources.displayMetrics.density)
        speechView.layout(bubble.left, bubble.top, bubble.right, bubble.bottom)
    }

    private fun Int.dp() = (this * resources.displayMetrics.density).roundToInt()

    companion object {
        const val STANDARD_HEIGHT_DP = 200
        internal const val ARTWORK_SIZE_DP = 240
        internal const val SPEECH_TEXT_SIZE_SP = 10.5f
        internal const val SPEECH_MAX_LINES = 3
        internal const val ARTWORK_GRAVITY = Gravity.TOP or Gravity.CENTER_HORIZONTAL
    }
}

/** Coordinates of the safe text area inside the mirrored 1024-square source artwork. */
private object SenseiArtworkGeometry {
    const val VIEWPORT_SIZE = 1024f
    private const val BUBBLE_LEFT = 55f
    private const val BUBBLE_TOP = 100f
    private const val BUBBLE_WIDTH = 490f
    private const val BUBBLE_HEIGHT = 240f

    fun speechBounds(viewWidth: Int, density: Float): Rect {
        val artworkWidth = SenseiGuideView.ARTWORK_SIZE_DP * density
        val artworkLeft = (viewWidth - artworkWidth) / 2f
        val scale = artworkWidth / VIEWPORT_SIZE
        val left = artworkLeft + BUBBLE_LEFT * scale
        val top = BUBBLE_TOP * scale
        return Rect(
            left.roundToInt(),
            top.roundToInt(),
            (left + BUBBLE_WIDTH * scale).roundToInt(),
            (top + BUBBLE_HEIGHT * scale).roundToInt(),
        )
    }
}

