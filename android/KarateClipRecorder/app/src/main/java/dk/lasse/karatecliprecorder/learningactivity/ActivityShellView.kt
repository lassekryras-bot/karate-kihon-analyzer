package dk.lasse.karatecliprecorder.learningactivity

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dk.lasse.karatecliprecorder.R

enum class ActivityShellState { READY, ACTIVE, COMPLETE, RESULT, ERROR }

data class ActivityShellAction(
    val label: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/** Reusable focused activity frame. Activity-specific content is supplied through its slots. */
@SuppressLint("ViewConstructor")
class ActivityShellView(
    context: Context,
    onExit: () -> Unit,
) : FrameLayout(context) {
    private val red = ContextCompat.getColor(context, R.color.app_accent)
    private val ink = ContextCompat.getColor(context, R.color.app_text_primary)
    private val muted = ContextCompat.getColor(context, R.color.app_text_secondary)
    private val border = ContextCompat.getColor(context, R.color.app_border)
    private val surface = ContextCompat.getColor(context, R.color.app_card_surface)
    private val backgroundColor = ContextCompat.getColor(context, R.color.app_background)

    private val body = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }
    private val pathTitle = label("", 19f, Typeface.BOLD, Gravity.CENTER)
    private val pathPosition = label("", 16f, Typeface.BOLD, Gravity.CENTER)
    private val contextLine = label("", 12f, Typeface.BOLD)
    private val activityTitle = label("", 27f, Typeface.BOLD)
    private val activitySubtitle = label("", 16f)
    private val runnerSlot = FrameLayout(context)
    private val progressSlot = FrameLayout(context)
    private val actionBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(surface)
        elevation = 8.dp().toFloat()
    }

    init {
        setBackgroundColor(backgroundColor)
        body.setPadding(20.dp(), 6.dp(), 20.dp(), 112.dp())
        actionBar.setPadding(16.dp(), 12.dp(), 16.dp(), 12.dp())

        body.addView(topBar(onExit), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            56.dp(),
        ))
        body.addView(contextLine, matchWrap().apply { topMargin = 16.dp() })
        body.addView(activityTitle, matchWrap().apply { topMargin = 12.dp() })
        body.addView(activitySubtitle, matchWrap().apply { topMargin = 5.dp() })
        body.addView(runnerSlot, matchWrap().apply { topMargin = 18.dp() })
        body.addView(progressSlot, matchWrap().apply { topMargin = 16.dp() })

        addView(ScrollView(context).apply {
            clipToPadding = false
            isFillViewport = true
            addView(body)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(actionBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val navigationBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            body.setPadding(20.dp(), systemBars.top + 6.dp(), 20.dp(), navigationBars.bottom + 112.dp())
            actionBar.setPadding(16.dp(), 12.dp(), 16.dp(), navigationBars.bottom + 12.dp())
            insets
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewCompat.requestApplyInsets(this)
    }

    fun setHeader(title: String, position: String) {
        pathTitle.text = title
        val numeratorEnd = position.indexOf('/').takeIf { it > 0 }?.let { slash ->
            position.substring(0, slash).trimEnd().length
        } ?: 0
        pathPosition.text = SpannableString(position).also { styled ->
            if (numeratorEnd > 0) {
                styled.setSpan(ForegroundColorSpan(red), 0, numeratorEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    fun setContext(section: String, category: String) {
        val copy = "$section   •   $category"
        contextLine.text = SpannableString(copy).also { styled ->
            styled.setSpan(ForegroundColorSpan(red), 0, section.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            styled.setSpan(
                ForegroundColorSpan(ink),
                section.length,
                copy.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        contextLine.contentDescription = "$section, $category"
    }

    fun setHeading(title: String, subtitle: String) {
        activityTitle.text = title
        activitySubtitle.text = subtitle
        activitySubtitle.setTextColor(muted)
    }

    fun setRunnerContent(view: View) {
        runnerSlot.removeAllViews()
        runnerSlot.addView(view, FrameLayout.LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT,
        ))
        runnerSlot.visibility = View.VISIBLE
    }

    fun setProgressContent(view: View?) {
        progressSlot.removeAllViews()
        if (view == null) {
            progressSlot.visibility = View.GONE
        } else {
            progressSlot.addView(view, FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
            ))
            progressSlot.visibility = View.VISIBLE
        }
    }

    fun setActions(secondary: ActivityShellAction?, primary: ActivityShellAction) {
        actionBar.removeAllViews()
        secondary?.let { action ->
            actionBar.addView(actionButton(action, primary = false), LinearLayout.LayoutParams(
                0,
                54.dp(),
                1f,
            ).apply { marginEnd = 10.dp() })
        }
        actionBar.addView(actionButton(primary, primary = true), LinearLayout.LayoutParams(
            0,
            54.dp(),
            if (secondary == null) 1f else 1.35f,
        ))
    }

    private fun topBar(onExit: () -> Unit) = FrameLayout(context).apply {
        addView(ImageButton(context).apply {
            setImageResource(R.drawable.ic_tabler_arrow_left)
            imageTintList = ColorStateList.valueOf(ink)
            background = null
            contentDescription = "Back"
            setPadding(12.dp(), 12.dp(), 12.dp(), 12.dp())
            setOnClickListener { onExit() }
        }, FrameLayout.LayoutParams(48.dp(), 48.dp(), Gravity.START or Gravity.CENTER_VERTICAL))
        addView(pathTitle, FrameLayout.LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ).apply {
            marginStart = 58.dp()
            marginEnd = 76.dp()
        })
        addView(pathPosition.apply {
            background = GradientDrawable().apply {
                setColor(surface)
                cornerRadius = 18.dp().toFloat()
                setStroke(1.dp(), border)
            }
            setPadding(14.dp(), 7.dp(), 14.dp(), 7.dp())
        }, FrameLayout.LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
            Gravity.END or Gravity.CENTER_VERTICAL,
        ))
    }

    private fun actionButton(action: ActivityShellAction, primary: Boolean) = TextView(context).apply {
        text = action.label
        textSize = 17f
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        gravity = Gravity.CENTER
        isEnabled = action.enabled
        isClickable = action.enabled
        isFocusable = action.enabled
        alpha = if (action.enabled) 1f else 0.42f
        setTextColor(if (primary) android.graphics.Color.WHITE else ink)
        background = GradientDrawable().apply {
            setColor(if (primary) red else surface)
            cornerRadius = 14.dp().toFloat()
            if (!primary) setStroke(1.dp(), border)
        }
        setOnClickListener { if (action.enabled) action.onClick() }
    }

    private fun label(text: String, size: Float, style: Int = Typeface.NORMAL, gravity: Int = Gravity.START) =
        TextView(context).apply {
            this.text = text
            textSize = size
            setTextColor(ink)
            typeface = Typeface.create("sans-serif", style)
            this.gravity = gravity
        }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()
}
