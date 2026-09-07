package dk.lasse.karatecliprecorder

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
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

internal object AppChromeStyle {
    val SURFACE_COLOR_RES = R.color.app_card_surface
    const val ELEVATION_DP = 4
}

/** Sticky header for the four primary bottom-navigation destinations. */
class MainPageHeader(
    context: Context,
    title: String,
    subtitle: String? = null,
    trailingSlot: View? = null,
) : LinearLayout(context) {
    private val titleView = headerText(title, if (title.length > 20) 25f else 29f, Typeface.BOLD)
    private val subtitleView = headerText(subtitle.orEmpty(), 15f, Typeface.NORMAL).apply {
        setTextColor(ContextCompat.getColor(context, R.color.app_text_secondary))
        visibility = if (subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
    }
    private val trailingHost = FrameLayout(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(ContextCompat.getColor(context, AppChromeStyle.SURFACE_COLOR_RES))
        elevation = context.pageDp(AppChromeStyle.ELEVATION_DP).toFloat()

        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(titleView)
            addView(subtitleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.pageDp(2)
            })
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(trailingHost, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = context.pageDp(12)
        })
        setTrailingSlot(trailingSlot)
        installStatusBarInsets(horizontalDp = 20, topDp = 12, bottomDp = 12)
    }

    fun setTitle(title: String) {
        titleView.text = title
        titleView.textSize = if (title.length > 20) 25f else 29f
    }

    fun setSubtitle(subtitle: String?) {
        subtitleView.text = subtitle.orEmpty()
        subtitleView.visibility = if (subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    fun setTrailingSlot(slot: View?) {
        trailingHost.removeAllViews()
        if (slot == null) {
            trailingHost.visibility = View.GONE
        } else {
            trailingHost.visibility = View.VISIBLE
            (slot.parent as? ViewGroup)?.removeView(slot)
            trailingHost.addView(slot, FrameLayout.LayoutParams(
                context.pageDp(48),
                context.pageDp(48),
                Gravity.CENTER,
            ))
        }
    }

    private fun headerText(copy: String, size: Float, style: Int) = TextView(context).apply {
        text = copy
        textSize = size
        typeface = Typeface.create("sans-serif", style)
        setTextColor(ContextCompat.getColor(context, R.color.app_text_primary))
        if (style == Typeface.BOLD) ViewCompat.setAccessibilityHeading(this, true)
    }
}

/** Sticky back/title header for activities, details, and settings/profile subsections. */
class SubPageHeader(
    context: Context,
    title: String,
    subtitle: String? = null,
    onBack: () -> Unit,
    trailingSlot: View? = null,
) : FrameLayout(context) {
    private val titleView = headerText(title, 21f, Typeface.BOLD, Gravity.CENTER)
    private val subtitleView = headerText(subtitle.orEmpty(), 14f, Typeface.NORMAL, Gravity.CENTER).apply {
        setTextColor(ContextCompat.getColor(context, R.color.app_text_secondary))
        visibility = if (subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
    }
    private val trailingHost = FrameLayout(context)

    init {
        setBackgroundColor(ContextCompat.getColor(context, AppChromeStyle.SURFACE_COLOR_RES))
        elevation = context.pageDp(AppChromeStyle.ELEVATION_DP).toFloat()

        addView(ImageButton(context).apply {
            setImageResource(R.drawable.ic_tabler_arrow_left)
            imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.app_text_primary))
            background = null
            contentDescription = "Back"
            setPadding(context.pageDp(12), context.pageDp(12), context.pageDp(12), context.pageDp(12))
            setOnClickListener { onBack() }
        }, LayoutParams(context.pageDp(48), context.pageDp(48), Gravity.START or Gravity.CENTER_VERTICAL))

        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(titleView, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(subtitleView, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.pageDp(1)
            })
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            marginStart = context.pageDp(58)
            marginEnd = context.pageDp(58)
        })

        addView(trailingHost, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))
        setTrailingSlot(trailingSlot)
        minimumHeight = context.pageDp(56)
        installStatusBarInsets(horizontalDp = 16, topDp = 8, bottomDp = 8)
    }

    fun setTitle(title: String) {
        titleView.text = title
    }

    fun setSubtitle(subtitle: String?) {
        subtitleView.text = subtitle.orEmpty()
        subtitleView.visibility = if (subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    fun setTrailingSlot(slot: View?) {
        trailingHost.removeAllViews()
        if (slot == null) {
            trailingHost.visibility = View.GONE
        } else {
            trailingHost.visibility = View.VISIBLE
            (slot.parent as? ViewGroup)?.removeView(slot)
            trailingHost.addView(slot)
        }
    }

    private fun headerText(copy: String, size: Float, style: Int, gravity: Int) = TextView(context).apply {
        text = copy
        textSize = size
        typeface = Typeface.create("sans-serif", style)
        setTextColor(ContextCompat.getColor(context, R.color.app_text_primary))
        this.gravity = gravity
        if (style == Typeface.BOLD) ViewCompat.setAccessibilityHeading(this, true)
    }
}

/** Keeps a shared header fixed while only the supplied page body scrolls. */
class StickyHeaderPageLayout(
    context: Context,
    header: View,
    body: View? = null,
    horizontalContentPaddingDp: Int = 20,
    topContentPaddingDp: Int = 12,
    bottomContentClearanceDp: Int = 28,
) : LinearLayout(context) {
    val content = LinearLayout(context).apply { orientation = VERTICAL }
    val scroller = ScrollView(context).apply {
        isFillViewport = true
        clipToPadding = false
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    init {
        orientation = VERTICAL
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(scroller, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        body?.let(content::addView)
        setBackgroundColor(ContextCompat.getColor(context, R.color.app_background))

        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val navigation = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            content.setPadding(
                context.pageDp(horizontalContentPaddingDp),
                context.pageDp(topContentPaddingDp),
                context.pageDp(horizontalContentPaddingDp),
                navigation.bottom + context.pageDp(bottomContentClearanceDp),
            )
            insets
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewCompat.requestApplyInsets(this)
    }
}

private fun View.installStatusBarInsets(horizontalDp: Int, topDp: Int, bottomDp: Int) {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val safeTop = insets.getInsets(
            WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout(),
        )
        view.setPadding(
            safeTop.left + context.pageDp(horizontalDp),
            safeTop.top + context.pageDp(topDp),
            safeTop.right + context.pageDp(horizontalDp),
            context.pageDp(bottomDp),
        )
        insets
    }
    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = ViewCompat.requestApplyInsets(view)
        override fun onViewDetachedFromWindow(view: View) = Unit
    })
}

internal fun View.installNavigationBarInsets(horizontalDp: Int, topDp: Int, bottomDp: Int) {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val navigation = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
        view.setPadding(
            context.pageDp(horizontalDp),
            context.pageDp(topDp),
            context.pageDp(horizontalDp),
            navigation.bottom + context.pageDp(bottomDp),
        )
        insets
    }
    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = ViewCompat.requestApplyInsets(view)
        override fun onViewDetachedFromWindow(view: View) = Unit
    })
}

private fun Context.pageDp(value: Int) = (value * resources.displayMetrics.density).toInt()
