package dk.lasse.karatecliprecorder.home

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import dk.lasse.karatecliprecorder.R
import kotlin.math.roundToInt

/**
 * Primary onboarding card on Home representing the user's current step (e.g. "Your first step" / Start).
 *
 * Requirements:
 * - Rounded warm-white card surface
 * - Clear typographical hierarchy (category -> strong title -> body)
 * - Large red primary CTA button with height >= 48 dp
 * - Fully accessible
 */
class HomeOnboardingActionCard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val ink = ContextCompat.getColor(context, R.color.app_text_primary)
    private val muted = ContextCompat.getColor(context, R.color.app_text_secondary)
    private val paper = ContextCompat.getColor(context, R.color.app_card_surface)
    private val border = ContextCompat.getColor(context, R.color.app_border)
    private val accentRed = ContextCompat.getColor(context, R.color.app_accent)

    private val categoryLabel = TextView(context).apply {
        textSize = 12f
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        letterSpacing = 0.08f
        setTextColor(muted)
        visibility = View.GONE
    }

    private val titleView = TextView(context).apply {
        textSize = 20f
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        setTextColor(ink)
    }

    private val bodyView = TextView(context).apply {
        textSize = 14f
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        setTextColor(muted)
        setLineSpacing(2.dp().toFloat(), 1.15f)
    }

    private val actionButton = TextView(context).apply {
        textSize = 15f
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            setColor(accentRed)
            cornerRadius = 10.dp().toFloat()
        }
        isClickable = true
        isFocusable = true
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 48.dp()).apply {
            topMargin = 16.dp()
        }
    }

    init {
        orientation = VERTICAL
        val pad = 16.dp()
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply {
            setColor(paper)
            cornerRadius = 14.dp().toFloat()
            setStroke(1.dp(), border)
        }

        addView(categoryLabel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = 4.dp()
        })
        addView(titleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(bodyView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = 6.dp()
        })
        addView(actionButton)
    }

    fun setAction(content: HomeActionCardContent, onClick: () -> Unit) {
        if (content.category.isNotBlank()) {
            categoryLabel.text = content.category
            categoryLabel.visibility = View.VISIBLE
        } else {
            categoryLabel.visibility = View.GONE
        }
        titleView.text = content.title
        bodyView.text = content.body
        actionButton.text = content.buttonLabel
        actionButton.contentDescription = content.buttonLabel
        actionButton.setOnClickListener { onClick() }
        visibility = View.VISIBLE
    }

    fun setAction(action: HomeAction.PrimaryCta) {
        setAction(
            content = HomeActionCardContent(
                category = action.category,
                title = action.title,
                body = action.body,
                buttonLabel = action.buttonLabel,
            ),
            onClick = action.onClick,
        )
    }

    private fun Int.dp() = (this * resources.displayMetrics.density).roundToInt()
}

