package dk.lasse.karatecliprecorder.home

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import dk.lasse.karatecliprecorder.R
import dk.lasse.karatecliprecorder.artwork.FullColorSvgView
import kotlin.math.roundToInt

/**
 * Dedicated hero area for Home composition:
 * - Layer 1: Background slot (reserved for future dojo background artwork)
 * - Layer 2: Sensei character illustration (left/center-left, occupying ~42-48% width, belt & gesture visible)
 * - Layer 3: Speech bubble (positioned beside/above presenting hand, occupying ~45-52% width)
 */
class SenseiHomeHeroView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val backgroundLayer = FrameLayout(context).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private val senseiArtwork = FullColorSvgView(
        context = context,
        resourceId = R.raw.sensei_home_welcome,
        mirrored = false,
    ).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private val speechBubble = SenseiSpeechBubbleView(context)

    init {
        minimumHeight = HERO_MIN_HEIGHT_DP.dp()
        clipChildren = false
        clipToPadding = false

        // Layer 1: Background slot (for future dojo artwork)
        addView(backgroundLayer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Layer 2: Sensei artwork positioned on lower-left / center-left
        addView(senseiArtwork, LayoutParams(SENSEI_VIEW_WIDTH_DP.dp(), SENSEI_VIEW_HEIGHT_DP.dp(), Gravity.START or Gravity.BOTTOM).apply {
            marginStart = 0
            bottomMargin = 0
        })

        // Layer 3: Speech bubble positioned beside / above Sensei's presenting hand
        addView(speechBubble, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            marginEnd = 12.dp()
            topMargin = 12.dp()
        })
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        if (availableWidth > 0) {
            // Visible Sensei target is ~42-48% of screen width.
            // In the 1024x1024 SVG, Sensei occupies ~63% of width (43..686),
            // so setting the view to ~70% width produces ~44% visible character width.
            val senseiViewDim = (availableWidth * 0.70f).roundToInt().coerceIn(240.dp(), 340.dp())
            val senseiLp = senseiArtwork.layoutParams as? LayoutParams
            if (senseiLp != null && (senseiLp.width != senseiViewDim || senseiLp.height != senseiViewDim)) {
                senseiLp.width = senseiViewDim
                senseiLp.height = senseiViewDim
                senseiArtwork.layoutParams = senseiLp
            }

            // Speech bubble target width is ~45-52% of available width
            val bubbleWidth = (availableWidth * 0.50f).roundToInt().coerceIn(165.dp(), 240.dp())
            val bubbleLp = speechBubble.layoutParams as? LayoutParams
            if (bubbleLp != null && bubbleLp.width != bubbleWidth) {
                bubbleLp.width = bubbleWidth
                speechBubble.layoutParams = bubbleLp
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    fun setMessage(message: SenseiMessage) {
        speechBubble.setMessage(message)
    }

    private fun Int.dp() = (this * resources.displayMetrics.density).roundToInt()

    companion object {
        const val HERO_MIN_HEIGHT_DP = 260
        private const val SENSEI_VIEW_WIDTH_DP = 270
        private const val SENSEI_VIEW_HEIGHT_DP = 270
    }
}
