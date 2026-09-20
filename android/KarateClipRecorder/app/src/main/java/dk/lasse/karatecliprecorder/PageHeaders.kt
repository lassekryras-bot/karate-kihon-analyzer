package dk.lasse.karatecliprecorder

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.text.TextUtils
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
import dk.lasse.karatecliprecorder.profile.ProfileAvatarButton
import dk.lasse.karatecliprecorder.profile.ProfileAvatarState
import dk.lasse.karatecliprecorder.recordings.QueueManagerTrayView

internal object AppChromeStyle {
    val SURFACE_COLOR_RES = R.color.app_card_surface
    const val ELEVATION_DP = 0

    fun background(context: Context, dividerGravity: Int) = LayerDrawable(arrayOf(
        ColorDrawable(ContextCompat.getColor(context, SURFACE_COLOR_RES)),
        ColorDrawable(ContextCompat.getColor(context, R.color.app_divider)),
    )).apply {
        setLayerHeight(1, context.pageDp(1).coerceAtLeast(1))
        setLayerGravity(1, dividerGravity or Gravity.FILL_HORIZONTAL)
    }
}

sealed interface HeaderLeadingAction {
    data object None : HeaderLeadingAction
    data class Back(val contentDescription: String = "Back", val onBack: () -> Unit) : HeaderLeadingAction
    data class Custom(val view: View) : HeaderLeadingAction
}

sealed interface HeaderTrailingAction {
    data object None : HeaderTrailingAction
    data class ProfileShortcut(val state: ProfileAvatarState, val onClick: () -> Unit) : HeaderTrailingAction
    data class Custom(val view: View) : HeaderTrailingAction
}

enum class HeaderAttentionTarget {
    NONE,
    LEADING,
    TRAILING_PROFILE,
}

data class AppHeaderState(
    val title: String,
    val subtitle: String? = null,
    val leadingAction: HeaderLeadingAction = HeaderLeadingAction.None,
    val trailingAction: HeaderTrailingAction = HeaderTrailingAction.None,
    val attentionTarget: HeaderAttentionTarget? = null,
)

/**
 * Unified state-driven header with stable center/title geometry independent of leading/trailing controls.
 */
open class AppHeaderView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val titleView = TextView(context).apply {
        textSize = 21f
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        setTextColor(ContextCompat.getColor(context, R.color.app_text_primary))
        gravity = Gravity.CENTER
        ellipsize = TextUtils.TruncateAt.END
        maxLines = 1
        ViewCompat.setAccessibilityHeading(this, true)
    }

    private val subtitleView = TextView(context).apply {
        textSize = 14f
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        setTextColor(ContextCompat.getColor(context, R.color.app_text_secondary))
        gravity = Gravity.CENTER
        ellipsize = TextUtils.TruncateAt.END
        maxLines = 1
        visibility = View.GONE
    }

    private val leadingHost = FrameLayout(context)
    private val trailingHost = FrameLayout(context)
    private val titleContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(titleView, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(subtitleView, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.pageDp(1)
        })
    }

    init {
        background = AppChromeStyle.background(context, Gravity.BOTTOM)
        elevation = context.pageDp(AppChromeStyle.ELEVATION_DP).toFloat()
        minimumHeight = context.pageDp(56)

        // Symmetrically reserved gutters (56dp start and 56dp end) ensure title geometry never shifts horizontally
        addView(titleContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            marginStart = context.pageDp(56)
            marginEnd = context.pageDp(56)
        })

        addView(leadingHost, LayoutParams(context.pageDp(48), context.pageDp(48), Gravity.START or Gravity.CENTER_VERTICAL))
        addView(trailingHost, LayoutParams(context.pageDp(48), context.pageDp(48), Gravity.END or Gravity.CENTER_VERTICAL))

        installStatusBarInsets(horizontalDp = 16, topDp = 8, bottomDp = 8)
    }

    fun setHeaderState(state: AppHeaderState) {
        setTitle(state.title)
        setSubtitle(state.subtitle)
        setLeadingAction(state.leadingAction)
        setTrailingAction(state.trailingAction, state.attentionTarget)
    }

    fun setTitle(title: String) {
        titleView.text = title
        titleView.textSize = if (title.length > 20) 19f else 22f
    }

    fun setSubtitle(subtitle: String?) {
        subtitleView.text = subtitle.orEmpty()
        subtitleView.visibility = if (subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    fun setLeadingAction(action: HeaderLeadingAction) {
        leadingHost.removeAllViews()
        when (action) {
            HeaderLeadingAction.None -> leadingHost.visibility = View.GONE
            is HeaderLeadingAction.Back -> {
                leadingHost.visibility = View.VISIBLE
                leadingHost.addView(createBackButton(action.contentDescription, action.onBack), LayoutParams(
                    context.pageDp(48),
                    context.pageDp(48),
                    Gravity.CENTER,
                ))
            }
            is HeaderLeadingAction.Custom -> {
                leadingHost.visibility = View.VISIBLE
                (action.view.parent as? ViewGroup)?.removeView(action.view)
                leadingHost.addView(action.view, LayoutParams(
                    LayoutParams.WRAP_CONTENT,
                    LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ))
            }
        }
    }

    fun setTrailingAction(action: HeaderTrailingAction, attention: HeaderAttentionTarget? = null) {
        trailingHost.removeAllViews()
        when (action) {
            HeaderTrailingAction.None -> trailingHost.visibility = View.GONE
            is HeaderTrailingAction.ProfileShortcut -> {
                trailingHost.visibility = View.VISIBLE
                val button = ProfileAvatarButton(context, action.state, action.onClick)
                trailingHost.addView(button, LayoutParams(
                    context.pageDp(48),
                    context.pageDp(48),
                    Gravity.CENTER,
                ))
                if (attention == HeaderAttentionTarget.TRAILING_PROFILE) {
                    button.pulseAttention()
                }
            }
            is HeaderTrailingAction.Custom -> {
                trailingHost.visibility = View.VISIBLE
                (action.view.parent as? ViewGroup)?.removeView(action.view)
                trailingHost.addView(action.view, LayoutParams(
                    LayoutParams.WRAP_CONTENT,
                    LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ))
            }
        }
    }

    fun setTrailingSlot(slot: View?) {
        if (slot == null) {
            setTrailingAction(HeaderTrailingAction.None)
        } else {
            setTrailingAction(HeaderTrailingAction.Custom(slot))
        }
    }

    private fun createBackButton(description: String, onBack: () -> Unit) = ImageButton(context).apply {
        setImageResource(R.drawable.ic_tabler_arrow_left)
        imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.app_text_primary))
        background = null
        contentDescription = description
        setPadding(context.pageDp(12), context.pageDp(12), context.pageDp(12), context.pageDp(12))
        setOnClickListener { onBack() }
    }
}

/** Sticky header for primary top-level destinations. */
class MainPageHeader(
    context: Context,
    title: String,
    subtitle: String? = null,
    trailingSlot: View? = null,
) : AppHeaderView(context) {
    init {
        setTitle(title)
        setSubtitle(subtitle)
        setTrailingSlot(trailingSlot)
    }
}

/** Sticky back/title header for activities, details, and subsections. */
class SubPageHeader(
    context: Context,
    title: String,
    subtitle: String? = null,
    onBack: () -> Unit = {},
    trailingSlot: View? = null,
) : AppHeaderView(context) {
    init {
        setTitle(title)
        setSubtitle(subtitle)
        setLeadingAction(HeaderLeadingAction.Back("Back", onBack))
        setTrailingSlot(trailingSlot)
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
        addView(QueueManagerTrayView(context), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
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
