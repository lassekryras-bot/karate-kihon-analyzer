package dk.lasse.karatecliprecorder

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat

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
        elevation = 12.dp().toFloat()
        setPadding(12.dp(), 6.dp(), 12.dp(), 6.dp())
        setBackgroundColor(ContextCompat.getColor(context, R.color.app_card_surface))

        listOf(
            navigationItem("Home", AppIcon.HOME, selectedDestination == AppDestination.HOME, onHome),
            navigationItem("Train", AppIcon.KARATE_BELT, selectedDestination == AppDestination.TRAIN, onTrain),
            navigationItem("Progress", AppIcon.CHART_BAR, selectedDestination == AppDestination.PROGRESS, onProgress),
            navigationItem("Settings", AppIcon.SETTINGS, selectedDestination == AppDestination.SETTINGS, onSettings),
        ).forEach { item ->
            addView(item, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
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
            topMargin = 4.dp()
        })
    }

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()
}
