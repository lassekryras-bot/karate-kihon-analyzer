package dk.lasse.karatecliprecorder.skillcoach

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import dk.lasse.karatecliprecorder.AppBottomNavigationView
import dk.lasse.karatecliprecorder.AppDestination
import dk.lasse.karatecliprecorder.AppIcon
import dk.lasse.karatecliprecorder.AppIconView
import dk.lasse.karatecliprecorder.MainPageHeader
import dk.lasse.karatecliprecorder.R
import dk.lasse.karatecliprecorder.SettingsRowView
import dk.lasse.karatecliprecorder.SettingsSectionView
import dk.lasse.karatecliprecorder.StickyHeaderPageLayout
import dk.lasse.karatecliprecorder.profile.ProfileAvatarButton
import dk.lasse.karatecliprecorder.profile.ProfileRepository

/** Passive Skill Coach workspace. Rendering it never starts capture or analysis infrastructure. */
class SkillCoachScreenView(
    context: Context,
    repository: ProfileRepository,
    state: SkillCoachLandingState,
    onAction: (SkillCoachAction) -> Unit,
    onProfile: () -> Unit,
    onHome: () -> Unit,
    onTrain: () -> Unit,
    onProgress: () -> Unit,
    onSettings: () -> Unit,
) : FrameLayout(context) {
    private val accent = ContextCompat.getColor(context, R.color.app_accent)
    private val ink = ContextCompat.getColor(context, R.color.app_text_primary)
    private val muted = ContextCompat.getColor(context, R.color.app_text_secondary)
    private val border = ContextCompat.getColor(context, R.color.app_border)

    init {
        setBackgroundColor(ContextCompat.getColor(context, R.color.app_background))
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(sectionLabel("PERSONALIZED GUIDANCE", first = true))
            addView(guidanceCard(state.guidance, onAction))
            addView(toolSection(state.tools, onAction))
            addView(recentSection(state.recentAnalyses, onAction))
        }
        val header = MainPageHeader(
            context = context,
            title = "Skill Coach",
            subtitle = "Improve what matters most.",
            trailingSlot = ProfileAvatarButton(context, repository, onProfile),
        )
        addView(StickyHeaderPageLayout(
            context = context,
            header = header,
            body = body,
            topContentPaddingDp = 16,
            bottomContentClearanceDp = AppBottomNavigationView.CONTENT_CLEARANCE_DP,
        ), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(AppBottomNavigationView(
            context = context,
            selectedDestination = AppDestination.TRAIN,
            onHome = onHome,
            onTrain = onTrain,
            onProgress = onProgress,
            onSettings = onSettings,
        ), LayoutParams(
            LayoutParams.MATCH_PARENT,
            AppBottomNavigationView.BASE_HEIGHT_DP.dp(),
            Gravity.BOTTOM,
        ))
    }

    private fun guidanceCard(
        guidance: PersonalizedGuidanceState,
        onAction: (SkillCoachAction) -> Unit,
    ) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(18.dp(), 13.dp(), 18.dp(), 14.dp())
        background = GradientDrawable().apply {
            setColor(ContextCompat.getColor(context, R.color.skill_coach_guidance_surface))
            cornerRadius = 16.dp().toFloat()
            setStroke(1.dp(), border)
        }
        addView(label(guidance.recommendation, 22f, Typeface.BOLD).apply {
            setLineSpacing(2.dp().toFloat(), 1f)
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        if (guidance.illustration == SkillCoachIllustration.SENSEI_SPEAKING) {
            addView(SenseiGuideView(context, guidance.senseiSpeech), LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                SenseiGuideView.STANDARD_HEIGHT_DP.dp(),
            ).apply { topMargin = 4.dp() })
        }
        addView(primaryAction(guidance.ctaLabel) { onAction(guidance.ctaAction) }, LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT,
            48.dp(),
        ).apply { topMargin = 6.dp() })
    }

    private fun toolSection(
        tools: List<SkillCoachToolState>,
        onAction: (SkillCoachAction) -> Unit,
    ) = SettingsSectionView(context, "CHOOSE FOR YOURSELF").apply {
        tools.forEach { tool ->
            addRow(SettingsRowView(context, tool.icon(), tool.title, tool.subtitle).apply {
                configureAsNavigation(onClick = { onAction(tool.action) })
            })
        }
    }

    private fun recentSection(
        recentAnalyses: List<SkillCoachRecentAnalysisState>,
        onAction: (SkillCoachAction) -> Unit,
    ) = SettingsSectionView(context, "RECENT").apply {
        if (recentAnalyses.isEmpty()) {
            addRow(SettingsRowView(
                context,
                AppIcon.CLOCK,
                "No analyses yet",
                "Your completed reviews will appear here.",
            ).apply { clearAction() })
        } else {
            recentAnalyses.forEach { recent ->
                addRow(SettingsRowView(context, AppIcon.CLOCK, recent.title, recent.detail).apply {
                    configureAsNavigation(value = "View", onClick = { onAction(recent.action) })
                })
            }
        }
    }

    private fun SkillCoachToolState.icon(): AppIcon = when (action) {
        SkillCoachAction.TECHNIQUE_REVIEW -> AppIcon.KARATE
        SkillCoachAction.FOCUS_ON_ONE_THING -> AppIcon.TARGET
        SkillCoachAction.RECORD_AND_ANALYZE -> AppIcon.CAMERA
        else -> AppIcon.KARATE
    }

    private fun primaryAction(text: String, onClick: () -> Unit) = label(
        text,
        16f,
        Typeface.BOLD,
        Gravity.CENTER,
    ).apply {
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            setColor(accent)
            cornerRadius = 11.dp().toFloat()
        }
        isClickable = true
        isFocusable = true
        contentDescription = text
        setOnClickListener { onClick() }
        ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Button::class.java.name
            }
        })
    }

    private fun sectionLabel(text: String, first: Boolean = false) = label(text, 12f, Typeface.BOLD).apply {
        setTextColor(muted)
        letterSpacing = 0.08f
        ViewCompat.setAccessibilityHeading(this, true)
        setPadding(2.dp(), if (first) 0 else 22.dp(), 0, 8.dp())
    }

    private fun label(
        text: String,
        size: Float,
        style: Int = Typeface.NORMAL,
        gravity: Int = Gravity.START,
    ) = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(ink)
        typeface = Typeface.create("sans-serif", style)
        this.gravity = gravity
    }

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()
}
