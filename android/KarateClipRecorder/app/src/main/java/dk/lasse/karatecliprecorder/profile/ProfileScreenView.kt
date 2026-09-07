package dk.lasse.karatecliprecorder.profile

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import dk.lasse.karatecliprecorder.AppIcon
import dk.lasse.karatecliprecorder.R
import dk.lasse.karatecliprecorder.SettingsRowView
import dk.lasse.karatecliprecorder.SettingsSectionView
import dk.lasse.karatecliprecorder.StickyHeaderPageLayout
import dk.lasse.karatecliprecorder.SubPageHeader

/** Passive secondary destination for active-trainee identity and switching. */
class ProfileScreenView(
    context: Context,
    private val repository: ProfileRepository,
    private val onBack: () -> Unit,
    private val onAddProfile: () -> Unit,
    private val onEditProfile: (Profile) -> Unit,
    private val onManageProfiles: () -> Unit,
    private val onUnavailable: (String) -> Unit,
) : FrameLayout(context) {
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val subHeader = SubPageHeader(
        context = context,
        title = "Profile",
        subtitle = "Manage the active trainee and profile details.",
        onBack = onBack,
    )
    private val listener: (Profile) -> Unit = { render() }
    private var observing = false

    init {
        setBackgroundColor(ContextCompat.getColor(context, R.color.app_background))
        addView(StickyHeaderPageLayout(context, subHeader, body = content), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        render()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!observing) {
            observing = true
            repository.addActiveProfileListener(listener)
        }
    }

    override fun onDetachedFromWindow() {
        if (observing) repository.removeActiveProfileListener(listener)
        observing = false
        super.onDetachedFromWindow()
    }

    private fun render() {
        content.removeAllViews()
        val active = repository.activeProfile()
        content.addView(activeCard(active), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = 4.dp()
        })
        content.addView(context.profileSectionLabel("SWITCH TRAINEE"))
        content.addView(profileSwitcher(active))
        content.addView(informationSection(active))
        content.addView(SettingsSectionView(context, "PROFILE MANAGEMENT").apply {
            addRow(SettingsRowView(context, AppIcon.SETTINGS, "Manage profiles", "Add, edit, or remove profiles").apply {
                configureAsNavigation(onClick = onManageProfiles)
            })
        })
    }

    private fun activeCard(profile: Profile) = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(16.dp(), 14.dp(), 16.dp(), 14.dp())
        background = cardBackground()
        elevation = 2.dp().toFloat()
        addView(AvatarView(context).apply { setProfile(profile) }, LinearLayout.LayoutParams(128.dp(), 148.dp()).apply {
            marginEnd = 16.dp()
        })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(context.profileText(profile.name, 23f, bold = true))
            val rank = buildList {
                add("${profile.beltRank.displayName} belt")
                profile.experienceLevel?.let { add(it.displayName) }
            }.joinToString(" · ")
            addView(context.profileText(rank, 14f).apply {
                setTextColor(ContextCompat.getColor(context, R.color.app_text_secondary))
                setPadding(0, 4.dp(), 0, 14.dp())
            })
            addView(context.profileText("●  Currently training", 14f).apply {
                setTextColor(ContextCompat.getColor(context, R.color.app_accent))
            })
        }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun profileSwitcher(active: Profile) = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            repository.listProfiles().forEach { profile ->
                addView(profileTile(profile, profile.id == active.id), LinearLayout.LayoutParams(110.dp(), 138.dp()).apply {
                    marginEnd = 10.dp()
                })
            }
            addView(addTile(), LinearLayout.LayoutParams(110.dp(), 138.dp()))
        })
    }

    private fun profileTile(profile: Profile, selected: Boolean) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(8.dp(), 7.dp(), 8.dp(), 8.dp())
        background = tileBackground(selected)
        isClickable = true
        isFocusable = true
        contentDescription = if (selected) "${profile.name}, active profile" else "Switch to ${profile.name}"
        setOnClickListener { repository.switchActiveProfile(profile.id) }
        addView(AvatarView(context).apply {
            setProfile(profile)
            bottomCropFraction = 0.12f
        }, LinearLayout.LayoutParams(82.dp(), 92.dp()))
        addView(context.profileText(if (selected) "✓ ${profile.name}" else profile.name, 14f, bold = true, gravity = Gravity.CENTER),
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun addTile() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = tileBackground(false)
        isClickable = true
        isFocusable = true
        contentDescription = "Add profile"
        setOnClickListener { onAddProfile() }
        addView(context.profileText("+", 38f, gravity = Gravity.CENTER).apply {
            setTextColor(ContextCompat.getColor(context, R.color.app_accent))
        }, LinearLayout.LayoutParams(72.dp(), 82.dp()))
        addView(context.profileText("Add profile", 14f, bold = true, gravity = Gravity.CENTER).apply {
            setTextColor(ContextCompat.getColor(context, R.color.app_accent))
        })
    }

    private fun informationSection(profile: Profile) = SettingsSectionView(context, "YOUR INFORMATION").apply {
        addRow(SettingsRowView(context, AppIcon.INFO_CIRCLE, "Personal", "Name, age group, character/avatar").apply {
            configureAsNavigation(onClick = { onEditProfile(profile) })
        })
        addRow(SettingsRowView(context, AppIcon.KARATE_BELT, "Karate", "Belt rank, experience level, dominant side").apply {
            configureAsNavigation(onClick = { onEditProfile(profile) })
        })
        val calibrationStatus = if (repository.calibrations(profile.id).isEmpty()) "Not calibrated" else "Ready"
        addRow(SettingsRowView(context, AppIcon.CAMERA, "Body & calibration", "Height and calibration status").apply {
            configureAsNavigation(value = calibrationStatus, onClick = { onUnavailable("Body & calibration") })
        })
        val progress = repository.learningProgress(profile.id)
        val trainingSummary = if (progress.isEmpty()) "No activity yet" else "${progress.count { it.status == LearningStatus.COMPLETED }} completed"
        addRow(SettingsRowView(context, AppIcon.CHART_BAR, "Training", "Current focus and profile-specific progress").apply {
            configureAsNavigation(value = trainingSummary, onClick = { onUnavailable("Training profile details") })
        })
    }

    private fun cardBackground() = GradientDrawable().apply {
        setColor(ContextCompat.getColor(context, R.color.app_card_surface))
        cornerRadius = 16.dp().toFloat()
        setStroke(1.dp(), ContextCompat.getColor(context, R.color.app_border))
    }

    private fun tileBackground(selected: Boolean) = GradientDrawable().apply {
        setColor(ContextCompat.getColor(context, R.color.app_card_surface))
        cornerRadius = 14.dp().toFloat()
        setStroke((if (selected) 2 else 1).dp(), ContextCompat.getColor(context, if (selected) R.color.app_accent else R.color.app_border))
    }

    private fun Int.dp() = context.dp(this)
}
