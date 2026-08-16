package dk.lasse.karatecliprecorder.learningartwork

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import dk.lasse.karatecliprecorder.enso.EnsoVariant

/** Compact reusable activity entry; text stays outside the layered learning artwork. */
class LearningActivityEntryView(
    context: Context,
    title: String,
    activityType: LearningActivityType,
    ensoVariant: EnsoVariant,
    onClick: () -> Unit,
) : LinearLayout(context) {
    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(10.dp(), 8.dp(), 14.dp(), 8.dp())
        background = GradientDrawable().apply {
            setColor(Color.rgb(252, 250, 247))
            cornerRadius = 14.dp().toFloat()
            setStroke(1.dp(), Color.rgb(226, 219, 210))
        }
        elevation = 2.dp().toFloat()
        isClickable = true
        isFocusable = true
        contentDescription = "$title, ${activityType.accessibilityLabel}"
        setOnClickListener { onClick() }

        addView(LearningPathArtworkView(context).apply {
            setArtwork(
                foreground = LearningArtworkForeground.JAPANESE_COUNTING,
                activityType = activityType,
                ensoVariant = ensoVariant,
            )
        }, LayoutParams(112.dp(), 112.dp()))

        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(12.dp(), 0, 0, 0)
            addView(TextView(context).apply {
                text = title
                textSize = 17f
                setTextColor(Color.rgb(24, 24, 24))
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
            }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(TextView(context).apply {
                text = activityType.displayLabel
                textSize = 14f
                setTextColor(Color.rgb(92, 92, 92))
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                setPadding(0, 4.dp(), 0, 0)
            }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.5f
    }

    private val LearningActivityType.displayLabel: String
        get() = when (this) {
            LearningActivityType.PRACTICE -> "Practice"
            LearningActivityType.TEST -> "Test"
        }

    private val LearningActivityType.accessibilityLabel: String
        get() = when (this) {
            LearningActivityType.PRACTICE -> "practice activity"
            LearningActivityType.TEST -> "test activity"
        }

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()
}
