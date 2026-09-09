package dk.lasse.karatecliprecorder.profile

import android.content.Context
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import dk.lasse.karatecliprecorder.AppBottomNavigationView
import dk.lasse.karatecliprecorder.AppDestination
import dk.lasse.karatecliprecorder.AppIcon
import dk.lasse.karatecliprecorder.MainPageHeader
import dk.lasse.karatecliprecorder.R
import dk.lasse.karatecliprecorder.SettingsRowView
import dk.lasse.karatecliprecorder.SettingsSectionView
import dk.lasse.karatecliprecorder.StickyHeaderPageLayout

/** Profile-scoped top-level Progress destination. */
class ProgressScreenView(
    context: Context,
    private val repository: ProfileRepository,
    private val onProfile: () -> Unit,
    private val onWiki: () -> Unit,
    onHome: () -> Unit,
    onTrain: () -> Unit,
    onSettings: () -> Unit,
) : FrameLayout(context) {
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val mainHeader = MainPageHeader(
        context = context,
        title = "Performance",
        subtitle = "Track your technique over time",
        trailingSlot = ProfileAvatarButton(context, repository, onProfile),
    )
    private val listener: (Profile) -> Unit = { renderHeaderAndSummary(onProfile) }
    private var observing = false

    init {
        setBackgroundColor(ContextCompat.getColor(context, R.color.app_background))
        addView(StickyHeaderPageLayout(
            context = context,
            header = mainHeader,
            body = content,
            topContentPaddingDp = 16,
            bottomContentClearanceDp = AppBottomNavigationView.CONTENT_CLEARANCE_DP,
        ), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(AppBottomNavigationView(context, AppDestination.PROGRESS, onHome, onTrain, {}, onSettings),
            LayoutParams(LayoutParams.MATCH_PARENT, AppBottomNavigationView.BASE_HEIGHT_DP.dp(), Gravity.BOTTOM))
        renderHeaderAndSummary(onProfile)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!observing) { observing = true; repository.addActiveProfileListener(listener) }
    }

    override fun onDetachedFromWindow() {
        if (observing) repository.removeActiveProfileListener(listener)
        observing = false
        super.onDetachedFromWindow()
    }

    fun refresh() = renderHeaderAndSummary(onProfile)

    private fun renderHeaderAndSummary(onProfile: () -> Unit) {
        content.removeAllViews()
        mainHeader.setSubtitle("Track your technique over time")
        content.addView(SettingsSectionView(context, "MEASUREMENTS", first = true).apply {
            addRow(SettingsRowView(context, AppIcon.KARATE, "Measurement wiki", "Understand your technique measurements").apply {
                configureAsNavigation(onClick = onWiki)
            })
        })
        content.addView(SettingsSectionView(context, "ACTIVITIES").apply {
            addRow(SettingsRowView(context, AppIcon.KARATE, "Punching", "Measurement trends for your punches").apply {
                configureAsNavigation(onClick = { showEmptyPerformance("Punching") })
            })
            addRow(SettingsRowView(context, AppIcon.KARATE, "Kicking", "Measurement trends for your kicks").apply {
                configureAsNavigation(onClick = { showEmptyPerformance("Kicking") })
            })
        })
    }

    private fun showEmptyPerformance(activity: String) {
        android.app.AlertDialog.Builder(context)
            .setTitle(activity)
            .setMessage("Performance trends are coming soon. Your technique measurements will appear here over time.")
            .setPositiveButton("Done", null)
            .show()
    }

    private fun Int.dp() = context.dp(this)
}
