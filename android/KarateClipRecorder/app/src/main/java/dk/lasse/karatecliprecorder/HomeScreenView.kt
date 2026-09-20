package dk.lasse.karatecliprecorder

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import dk.lasse.karatecliprecorder.enso.EnsoVariant
import dk.lasse.karatecliprecorder.home.HomeAction
import dk.lasse.karatecliprecorder.home.HomeOnboardingActionCard
import dk.lasse.karatecliprecorder.home.HomeOnboardingController
import dk.lasse.karatecliprecorder.home.HomeOnboardingPhase
import dk.lasse.karatecliprecorder.home.SenseiHomeHeroView
import dk.lasse.karatecliprecorder.home.SenseiHomeState
import dk.lasse.karatecliprecorder.home.SenseiMessage
import dk.lasse.karatecliprecorder.learningartwork.LearningArtworkForeground
import dk.lasse.karatecliprecorder.learningartwork.LearningPathArtworkView
import dk.lasse.karatecliprecorder.learningpath.DraftLearningPathDefinition
import dk.lasse.karatecliprecorder.learningpath.LearningPath
import dk.lasse.karatecliprecorder.learningpath.RecentLearningResolver
import dk.lasse.karatecliprecorder.learningpath.RecentLearningTarget
import dk.lasse.karatecliprecorder.profile.Profile
import dk.lasse.karatecliprecorder.profile.ProfileAvatarButton
import dk.lasse.karatecliprecorder.profile.ProfileAvatarState
import dk.lasse.karatecliprecorder.profile.ProfileRepository

data class ContinueLearningContent(
    val lessonTitle: String,
    val category: String,
    val currentStep: Int,
    val totalSteps: Int,
    val progressUnit: String,
    val artwork: LearningArtworkForeground?,
    val ensoVariant: EnsoVariant?,
)

/**
 * Dojo Sensei Home screen (v0.1).
 *
 * It is deliberately a passive view: constructing it never touches CameraX, MediaPipe,
 * or runtime permissions.
 *
 * Composition:
 * - Shared AppHeaderView (sticky at top)
 * - SenseiHomeHeroView (Sensei illustration + speech bubble)
 * - HomeOnboardingActionCard (primary onboarding action CTA)
 * - Shared AppBottomNavigationView (sticky at bottom)
 */
class HomeScreenView(
    context: Context,
    private val profileRepository: ProfileRepository,
    private val onProfile: () -> Unit,
    private val learningPaths: List<LearningPath>,
    private val karateBasics: DraftLearningPathDefinition,
    private val onContinue: (RecentLearningTarget) -> Unit,
    onLearn: () -> Unit,
    onPractice: () -> Unit = {},
    onSkillCoach: () -> Unit = {},
    onTrain: () -> Unit = {},
    onProgress: () -> Unit = {},
    onSettings: () -> Unit = {},
    preferences: AppPreferences = AppPreferences(context),
    controller: HomeOnboardingController? = null,
    onCreateProfile: () -> Unit = onProfile,
    onRecordStraightPunches: (Int) -> Unit = {},
) : FrameLayout(context) {

    private val red = ContextCompat.getColor(context, R.color.app_accent)
    private val ink = ContextCompat.getColor(context, R.color.app_text_primary)
    private val muted = ContextCompat.getColor(context, R.color.app_text_secondary)
    private val paper = ContextCompat.getColor(context, R.color.app_card_surface)
    private val backgroundColor = ContextCompat.getColor(context, R.color.app_background)
    private val border = ContextCompat.getColor(context, R.color.app_border)

    internal val onboardingController: HomeOnboardingController = controller ?: HomeOnboardingController(
        context = context,
        preferences = preferences,
        profileRepository = profileRepository,
        onOpenProfile = onProfile,
        onOpenLearning = onLearn,
        onCreateProfile = onCreateProfile,
        onRecordStraightPunches = onRecordStraightPunches,
    )

    val destination: AppDestination = AppDestination.HOME

    internal val heroView = SenseiHomeHeroView(context)
    internal val actionCard = HomeOnboardingActionCard(context)

    internal val mainHeader = MainPageHeader(
        context = context,
        title = context.getString(R.string.app_display_name),
        subtitle = formatWelcome(profileRepository.resolveActiveProfile()?.name),
        trailingSlot = ProfileAvatarButton(context, profileRepository) {
            if (profileRepository.resolveActiveProfile() == null) {
                onCreateProfile()
            } else {
                onProfile()
            }
        },
    )

    private val onboardingListener: (SenseiHomeState, AppHeaderState, AppNavigationState) -> Unit = { homeState, headerState, _ ->
        render(homeState)
        setHeaderState(headerState)
    }

    private val profileListener: (Profile) -> Unit = {
        mainHeader.setSubtitle(formatWelcome(profileRepository.resolveActiveProfile()?.name))
    }
    private var observingProfile = false

    init {
        setBackgroundColor(backgroundColor)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(heroView, LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                0,
                1f,
            ))
            addView(actionCard, LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
            ).apply {
                val margin = 16.dp()
                marginStart = margin
                marginEnd = margin
                topMargin = 8.dp()
                bottomMargin = 16.dp()
            })
        }

        addView(StickyHeaderPageLayout(
            context = context,
            header = mainHeader,
            body = content,
            topContentPaddingDp = 12,
            bottomContentClearanceDp = AppBottomNavigationView.CONTENT_CLEARANCE_DP,
        ), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        onboardingController.addListener(onboardingListener)
    }

    fun render(state: SenseiHomeState) {
        heroView.setMessage(state.message)
        val action = state.action
        val content = state.actionContent
        if (action != null && content != null) {
            actionCard.setAction(content) {
                onboardingController.executeAction(action)
            }
            actionCard.visibility = View.VISIBLE
        } else if (action is HomeAction.PrimaryCta) {
            actionCard.setAction(action)
            actionCard.visibility = View.VISIBLE
        } else {
            actionCard.visibility = View.GONE
        }
    }

    fun setHeaderState(state: AppHeaderState) {
        mainHeader.setHeaderState(state)
    }

    fun showOnboardingStateA() {
        mainHeader.setHeaderState(AppHeaderState(
            title = context.getString(R.string.app_display_name),
            subtitle = context.getString(R.string.home_welcome),
            trailingAction = HeaderTrailingAction.ProfileShortcut(
                state = ProfileAvatarState.UnknownProfile,
                onClick = onProfile,
            ),
        ))
        render(SenseiHomeState(
            message = SenseiMessage(
                title = context.getString(R.string.home_sensei_welcome_title),
                body = context.getString(R.string.home_sensei_welcome_body),
            ),
            action = HomeAction.PrimaryCta(
                category = context.getString(R.string.home_first_step_label),
                title = context.getString(R.string.home_first_step_title),
                body = context.getString(R.string.home_first_step_body),
                buttonLabel = context.getString(R.string.home_first_step_start),
                onClick = onboardingController::onStartTapped,
            ),
            onboardingPhase = HomeOnboardingPhase.WELCOME,
        ))
    }

    fun showOnboardingStateB() {
        mainHeader.setHeaderState(AppHeaderState(
            title = context.getString(R.string.app_display_name),
            subtitle = formatWelcome(profileRepository.activeProfile().name),
            trailingAction = HeaderTrailingAction.ProfileShortcut(
                state = ProfileAvatarState.ActiveWithAvatar(profileRepository.activeProfile()),
                onClick = onProfile,
            ),
        ))
    }

    private fun formatWelcome(name: String?): String =
        if (!name.isNullOrBlank()) {
            context.getString(R.string.home_welcome_named, name)
        } else {
            context.getString(R.string.home_welcome)
        }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!observingProfile) {
            observingProfile = true
            profileRepository.addActiveProfileListener(profileListener)
        }
    }

    override fun onDetachedFromWindow() {
        if (observingProfile) profileRepository.removeActiveProfileListener(profileListener)
        observingProfile = false
        onboardingController.removeListener(onboardingListener)
        onboardingController.dispose()
        super.onDetachedFromWindow()
    }

    // --- Legacy architectural compatibility methods ---

    private fun renderContinueCard() {
        val target = RecentLearningResolver.resolve(
            standardPaths = learningPaths,
            karateBasics = karateBasics,
            records = profileRepository.learningProgress(),
            activeProfileExists = profileRepository.listProfiles().isNotEmpty(),
        )
        val content = ContinueLearningContent(
            lessonTitle = target.activityTitle,
            category = target.pathTitle,
            currentStep = target.completedCount,
            totalSteps = target.totalCount,
            progressUnit = if (target is RecentLearningTarget.Draft) "activities" else "steps",
            artwork = (target as? RecentLearningTarget.Standard)?.path?.artwork,
            ensoVariant = (target as? RecentLearningTarget.Standard)?.path?.ensoVariant,
        )
        val artwork = content.artwork
        val enso = content.ensoVariant
    }

    private fun continueCard(content: ContinueLearningContent, onClick: () -> Unit) = card().apply {
        orientation = LinearLayout.VERTICAL
        setPadding(16.dp(), 16.dp(), 16.dp(), 16.dp())
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(content.lessonTitle, 20f, Typeface.BOLD))
                addView(label(content.category, 14f).apply {
                    setTextColor(muted)
                    setPadding(0, 3.dp(), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            val artwork = content.artwork
            val enso = content.ensoVariant
            if (artwork != null && enso != null) {
                addView(LearningPathArtworkView(context).apply {
                    setPathArtwork(artwork, enso)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(64.dp(), 64.dp()).apply {
                    marginStart = 12.dp()
                })
            }
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = 0
        })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("", 13f).apply {
                    text = progressCopy(content)
                    setTextColor(muted)
                })
                addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = content.totalSteps.coerceAtLeast(1)
                    progress = content.currentStep.coerceIn(0, max)
                    progressTintList = android.content.res.ColorStateList.valueOf(red)
                    progressBackgroundTintList = android.content.res.ColorStateList.valueOf(border)
                }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 5.dp()).apply {
                    topMargin = 6.dp()
                })
            }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 20.dp()
            })
            addView(actionButton("Continue ›", onClick), LinearLayout.LayoutParams(104.dp(), 48.dp()))
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = 16.dp()
        })
    }

    private fun progressCopy(content: ContinueLearningContent): SpannableString {
        val current = content.currentStep.coerceAtLeast(0).toString()
        return SpannableString("$current of ${content.totalSteps.coerceAtLeast(0)} ${content.progressUnit}").apply {
            setSpan(ForegroundColorSpan(red), 0, current.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun quickActions(onLearn: () -> Unit, onPractice: () -> Unit, onCoach: () -> Unit) =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val actions = listOf(
                Triple(R.drawable.ic_learn_torii, "Learn", onLearn),
                Triple(R.drawable.ic_practice, "Practice", onPractice),
                Triple(R.drawable.ic_skill_coach_target, "Skill Coach", onCoach),
            )
            actions.forEachIndexed { index, (iconRes, copy, callback) ->
                addView(card().apply {
                    elevation = 0f
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setOnClickListener { callback() }
                    isClickable = true
                    isFocusable = true
                    contentDescription = copy.replace('\n', ' ')
                    addView(ImageView(context).apply {
                        setImageResource(iconRes)
                        imageTintList = ContextCompat.getColorStateList(context, R.color.content_icon_tint)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, LayoutParams(52.dp(), 52.dp()))
                    addView(label(copy, 14f, Typeface.BOLD, Gravity.CENTER).apply {
                        setTextColor(ink)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                        topMargin = 8.dp()
                    })
                }, LinearLayout.LayoutParams(0, 112.dp(), 1f).apply {
                    if (index > 0) marginStart = 8.dp()
                })
            }
        }

    private fun sectionLabel(text: String, first: Boolean = false) = label(text, 12f, Typeface.BOLD).apply {
        setTextColor(muted)
        letterSpacing = 0.08f
        setPadding(2.dp(), if (first) 0 else 22.dp(), 0, 8.dp())
    }

    private fun actionButton(text: String, onClick: () -> Unit) = label(text, 15f, Typeface.BOLD, Gravity.CENTER).apply {
        setTextColor(Color.WHITE)
        background = rounded(red, 10.dp().toFloat())
        setOnClickListener { onClick() }
        isClickable = true
        isFocusable = true
        contentDescription = text
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 48.dp())
    }

    private fun card() = LinearLayout(context).apply {
        setPadding(14.dp(), 14.dp(), 14.dp(), 14.dp())
        background = rounded(paper, 14.dp().toFloat(), border)
        elevation = 0f
    }

    private fun label(text: String, size: Float, style: Int = Typeface.NORMAL, gravity: Int = Gravity.START) = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(ink)
        typeface = Typeface.create("sans-serif", style)
        this.gravity = gravity
    }

    private fun rounded(color: Int, radius: Float, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        stroke?.let { setStroke(1.dp(), it) }
    }

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()
}
