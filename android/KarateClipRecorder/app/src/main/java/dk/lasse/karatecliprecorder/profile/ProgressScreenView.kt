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
    onHome: () -> Unit,
    onTrain: () -> Unit,
    onSettings: () -> Unit,
) : FrameLayout(context) {
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val mainHeader = MainPageHeader(
        context = context,
        title = "Progress",
        subtitle = "${repository.activeProfile().name}'s learning and training activity",
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
        val profile = repository.activeProfile()
        mainHeader.setSubtitle("${profile.name}'s learning and training activity")
        val progress = repository.learningProgress(profile.id)
        val sessions = repository.trainingSessions(profile.id)
        content.addView(SettingsSectionView(context, "OVERVIEW").apply {
            addRow(SettingsRowView(context, AppIcon.CHART_BAR, "Learning activities", "Profile-specific completion state").apply {
                setStatus("${progress.count { it.status == LearningStatus.COMPLETED }} completed", ContextCompat.getColor(context, R.color.app_text_secondary))
            })
            addRow(SettingsRowView(context, AppIcon.KARATE, "Training sessions", "Saved practice and coaching sessions").apply {
                setStatus(sessions.size.toString(), ContextCompat.getColor(context, R.color.app_text_secondary))
            })
            addRow(SettingsRowView(context, AppIcon.CAMERA, "Calibration", "Body and camera calibration records").apply {
                setStatus(if (repository.calibrations(profile.id).isEmpty()) "Not calibrated" else "Ready", ContextCompat.getColor(context, R.color.app_text_secondary))
            })
        })
    }

    private fun Int.dp() = context.dp(this)
}
