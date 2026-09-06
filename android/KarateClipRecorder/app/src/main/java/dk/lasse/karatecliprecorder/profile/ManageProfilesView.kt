package dk.lasse.karatecliprecorder.profile

import android.content.Context
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import dk.lasse.karatecliprecorder.AppIcon
import dk.lasse.karatecliprecorder.R
import dk.lasse.karatecliprecorder.SettingsCardView
import dk.lasse.karatecliprecorder.SettingsRowView
import dk.lasse.karatecliprecorder.StickyHeaderPageLayout
import dk.lasse.karatecliprecorder.SubPageHeader

class ManageProfilesView(
    context: Context,
    private val repository: ProfileRepository,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (Profile) -> Unit,
) : FrameLayout(context) {
    init {
        setBackgroundColor(ContextCompat.getColor(context, R.color.app_background))
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(context.profileSectionLabel("TRAINEES"))
            addView(SettingsCardView(context).apply {
                repository.listProfiles().forEach { profile ->
                    addSettingsRow(SettingsRowView(
                        context, AppIcon.INFO_CIRCLE, profile.name,
                        "${profile.ageGroup.displayName} · ${profile.beltRank.displayName} belt",
                    ).apply { configureAsNavigation(value = if (profile.id == repository.activeProfile().id) "Active" else null, onClick = { onEdit(profile) }) })
                }
            })
            addView(context.primaryProfileButton("+  Add profile", onAdd), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 52.dp()).apply {
                topMargin = 16.dp()
            })
        }
        addView(StickyHeaderPageLayout(
            context = context,
            header = SubPageHeader(
                context = context,
                title = "Manage profiles",
                subtitle = "Add, edit, or remove local trainee profiles.",
                onBack = onBack,
            ),
            body = content,
            topContentPaddingDp = 10,
        ), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun Int.dp() = context.dp(this)
}
