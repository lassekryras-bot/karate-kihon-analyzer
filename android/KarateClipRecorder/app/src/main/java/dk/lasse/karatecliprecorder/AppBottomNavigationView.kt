package dk.lasse.karatecliprecorder

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

enum class AppDestination {
    HOME,
    TRAIN,
    PROGRESS,
    SETTINGS,
}

/** Shared bottom navigation used by passive app destinations. */
class AppBottomNavigationView(
    context: Context,
    selectedDestination: AppDestination,
    onHome: () -> Unit,
    onTrain: () -> Unit,
    onProgress: () -> Unit,
    onSettings: () -> Unit,
) : LinearLayout(context) {
    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        elevation = AppChromeStyle.ELEVATION_DP.dp().toFloat()
        setPadding(
            HORIZONTAL_PADDING_DP.dp(),
            TOP_PADDING_DP.dp(),
            HORIZONTAL_PADDING_DP.dp(),
            BOTTOM_PADDING_DP.dp(),
        )
        setBackgroundColor(ContextCompat.getColor(context, AppChromeStyle.SURFACE_COLOR_RES))

        listOf(
            navigationItem("Home", AppIcon.HOME, selectedDestination == AppDestination.HOME, onHome),
            navigationItem("Train", AppIcon.KARATE, selectedDestination == AppDestination.TRAIN, onTrain),
            navigationItem("Progress", AppIcon.CHART_BAR, selectedDestination == AppDestination.PROGRESS, onProgress),
            navigationItem("Settings", AppIcon.SETTINGS, selectedDestination == AppDestination.SETTINGS, onSettings),
        ).forEach { item ->
            addView(item, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }

        // Android 15 enforces edge-to-edge. Keep the navigation surface at the physical bottom,
        // then reserve the system navigation inset inside it so OEM bars cannot cover controls.
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val navigationBarBottom = insets
                .getInsets(WindowInsetsCompat.Type.navigationBars())
                .bottom
            view.setPadding(
                HORIZONTAL_PADDING_DP.dp(),
                TOP_PADDING_DP.dp(),
                HORIZONTAL_PADDING_DP.dp(),
                BOTTOM_PADDING_DP.dp() + navigationBarBottom,
            )
            val safeHeight = BASE_HEIGHT_DP.dp() + navigationBarBottom
            view.layoutParams?.let { params ->
                if (params.height != safeHeight) {
                    params.height = safeHeight
                    view.layoutParams = params
                }
            }
            insets
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewCompat.requestApplyInsets(this)
    }

    private fun navigationItem(
        label: String,
        icon: AppIcon,
        selected: Boolean,
        onClick: () -> Unit,
    ) = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        minimumWidth = 48.dp()
        minimumHeight = 48.dp()
        isSelected = selected
        isClickable = true
        isFocusable = true
        contentDescription = label
        ViewCompat.setStateDescription(this, if (selected) "Selected" else "Not selected")
        setOnClickListener { onClick() }

        val tint = requireNotNull(ContextCompat.getColorStateList(context, R.color.nav_icon_tint))
        addView(AppIconView(context, icon, tint = tint).apply {
            isSelected = selected
        }, LayoutParams(24.dp(), 24.dp()))
        addView(TextView(context).apply {
            text = label
            textSize = 13f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(tint)
            isSelected = selected
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = 2.dp()
        })
    }

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()

    companion object {
        const val BASE_HEIGHT_DP = 68
        const val CONTENT_CLEARANCE_DP = BASE_HEIGHT_DP + 28
        private const val HORIZONTAL_PADDING_DP = 12
        private const val TOP_PADDING_DP = 4
        private const val BOTTOM_PADDING_DP = 0
    }
}
