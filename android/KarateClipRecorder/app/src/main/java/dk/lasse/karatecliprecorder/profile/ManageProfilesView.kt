package dk.lasse.karatecliprecorder.profile

import android.app.AlertDialog
import android.content.Context
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
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
    private val onProfilesChanged: () -> Unit,
    private val onClearTrainingHistory: (Profile) -> Unit,
) : FrameLayout(context) {
    init {
        setBackgroundColor(ContextCompat.getColor(context, R.color.app_background))
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(context.profileSectionLabel("TRAINEES"))
            addView(SettingsCardView(context).apply {
                repository.listProfiles().forEach { profile ->
                    addSettingsRow(SettingsRowView(
                        context, AppIcon.USER, profile.name,
                        "${profile.ageGroup.displayName} · ${profile.beltRank.displayName} belt" +
                            if (profile.id == repository.activeProfile().id) "\nCurrently selected profile" else "",
                    ).apply { configureAsNavigation(onClick = { showProfileActions(profile) }) })
                }
            })
        }
        addView(StickyHeaderPageLayout(
            context = context,
            header = SubPageHeader(
                context = context,
                title = "Manage profiles",
                subtitle = "Manage existing profiles.",
                onBack = onBack,
            ),
            body = content,
            topContentPaddingDp = 10,
        ), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun showProfileActions(profile: Profile) {
        AlertDialog.Builder(context)
            .setTitle("Manage ${profile.name}")
            .setItems(arrayOf(
                "Reset learning progress",
                "Remove calibration data",
                "Delete coaching history",
                "Clear training history",
                "Delete profile",
            )) { _, which ->
                when (which) {
                    0 -> confirmResetLearning(profile)
                    3 -> onClearTrainingHistory(profile)
                    4 -> confirmDelete(profile)
                    else -> Toast.makeText(context, "Coming soon", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(profile: Profile) {
        AlertDialog.Builder(context)
            .setTitle("Delete ${profile.name}?")
            .setMessage("This removes this profile and its learning progress, sessions and calibration data from this device.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                repository.deleteProfile(profile.id)
                onProfilesChanged()
            }
            .show()
    }

    private fun confirmResetLearning(profile: Profile) {
        AlertDialog.Builder(context)
            .setTitle("Reset ${profile.name}'s learning progress?")
            .setMessage("All lessons will return to not started. Your profile, training history and calibration data will stay. This cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Reset progress") { _, _ ->
                repository.resetLearningProgress(profile.id)
                Toast.makeText(context, "Learning progress reset", Toast.LENGTH_SHORT).show()
                onProfilesChanged()
            }
            .show()
    }

}
