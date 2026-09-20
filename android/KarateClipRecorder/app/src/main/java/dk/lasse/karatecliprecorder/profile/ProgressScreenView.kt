package dk.lasse.karatecliprecorder.profile

import android.content.Context
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
    onHome: () -> Unit = {},
    onTrain: () -> Unit = {},
    onSettings: () -> Unit = {},
    private val onRecordings: () -> Unit = {},
) : FrameLayout(context) {
    val destination: AppDestination = AppDestination.PROGRESS

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
        content.addView(SettingsSectionView(context, "RECORDINGS", first = true).apply {
            addRow(SettingsRowView(context, AppIcon.KARATE, "Recordings", "Recent recordings and calendar").apply {
                configureAsNavigation(onClick = onRecordings)
            })
        })
        content.addView(SettingsSectionView(context, "MEASUREMENTS", first = true).apply {
            addRow(SettingsRowView(context, AppIcon.KARATE, "Measurement wiki", "Understand your technique measurements").apply {
                configureAsNavigation(onClick = onWiki)
            })
        })
        val active = repository.resolveActiveProfile()
        if (active != null) {
            content.addView(SettingsSectionView(context, "ACTIVE PROFILE").apply {
                addRow(SettingsRowView(context, AppIcon.SETTINGS, active.name, "${active.gender.name.lowercase().replaceFirstChar { it.uppercase() }}, ${active.ageGroup.name.lowercase().replaceFirstChar { it.uppercase() }}").apply {
                    configureAsNavigation(onClick = onProfile)
                })
            })
        }
    }
}
