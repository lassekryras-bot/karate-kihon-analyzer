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
import dk.lasse.karatecliprecorder.learningartwork.LearningArtworkForeground
import dk.lasse.karatecliprecorder.learningartwork.LearningPathArtworkView
import dk.lasse.karatecliprecorder.learningpath.LearningPath
import dk.lasse.karatecliprecorder.learningpath.DraftLearningPathDefinition
import dk.lasse.karatecliprecorder.learningpath.RecentLearningResolver
import dk.lasse.karatecliprecorder.learningpath.RecentLearningTarget
import dk.lasse.karatecliprecorder.profile.ProfileAvatarButton
import dk.lasse.karatecliprecorder.profile.Profile
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
 * The product landing screen. It is deliberately a passive view: constructing it never touches
 * CameraX, MediaPipe, or runtime permissions. Training infrastructure is entered only by a user
 * action supplied through the callbacks below.
 */
class HomeScreenView(
    context: Context,
    private val profileRepository: ProfileRepository,
    private val onProfile: () -> Unit,
    private val learningPaths: List<LearningPath>,
    private val karateBasics: DraftLearningPathDefinition,
    private val onContinue: (RecentLearningTarget) -> Unit,
    onLearn: () -> Unit,
    onPractice: () -> Unit,
    onSkillCoach: () -> Unit,
    onTrain: () -> Unit,
    onProgress: () -> Unit,
    onSettings: () -> Unit,
) : FrameLayout(context) {
    private val red = ContextCompat.getColor(context, R.color.app_accent)
    private val ink = ContextCompat.getColor(context, R.color.app_text_primary)
    private val muted = ContextCompat.getColor(context, R.color.app_text_secondary)
    private val paper = ContextCompat.getColor(context, R.color.home_card_surface)
    private val backgroundColor = ContextCompat.getColor(context, R.color.app_background)
    private val border = ContextCompat.getColor(context, R.color.app_border)
    private val dividerColor = ContextCompat.getColor(context, R.color.app_divider)
    private val continueCardHost = FrameLayout(context)
    private val mainHeader = MainPageHeader(
        context = context,
        title = "Karate Kihon Analyzer",
        subtitle = "Welcome ${profileRepository.activeProfile().name}",
        trailingSlot = ProfileAvatarButton(context, profileRepository, onProfile),
    )
    private val profileListener: (Profile) -> Unit = {
        mainHeader.setSubtitle("Welcome ${it.name}")
        renderContinueCard()
    }
    private var observingProfile = false

    init {
        setBackgroundColor(backgroundColor)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(continueCardHost, LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
            ))
            addView(sectionLabel("QUICK ACTIONS"))
            addView(quickActions(onLearn, onPractice, onSkillCoach))
            addView(sectionLabel("TODAY'S FOCUS"))
            addView(focusCard())
            addView(sectionLabel("RECENT ACTIVITY"))
            addView(activityCard())
            addView(sectionLabel("LEARNING PROGRESS"))
            addView(progressCard())
        }
        addView(StickyHeaderPageLayout(
            context = context,
            header = mainHeader,
            body = content,
            bottomContentClearanceDp = AppBottomNavigationView.CONTENT_CLEARANCE_DP,
        ), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        renderContinueCard()
        val navigation = AppBottomNavigationView(
            context = context,
            selectedDestination = AppDestination.HOME,
            onHome = {},
            onTrain = onTrain,
            onProgress = onProgress,
            onSettings = onSettings,
        )
        addView(navigation, LayoutParams(LayoutParams.MATCH_PARENT, AppBottomNavigationView.BASE_HEIGHT_DP.dp(), Gravity.BOTTOM))
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
        super.onDetachedFromWindow()
    }

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
        continueCardHost.removeAllViews()
        continueCardHost.addView(
            continueCard(content) { onContinue(target) },
            FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
    }

    private fun continueCard(content: ContinueLearningContent, onClick: () -> Unit) = card().apply {
        orientation = LinearLayout.VERTICAL
        setPadding(16.dp(), 15.dp(), 16.dp(), 16.dp())
        addView(label("Continue learning", 15f, Typeface.BOLD).apply {
            setTextColor(red)
        })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            addView(continueArtwork(content), LinearLayout.LayoutParams(0, 164.dp(), 0.38f).apply {
                marginEnd = 10.dp()
            })

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = 164.dp()
                addView(label(content.lessonTitle, 20f, Typeface.BOLD).apply {
                    setTextColor(ink)
                })
                addView(label(content.category, 14f).apply {
                    setTextColor(muted)
                    setPadding(0, 2.dp(), 0, 12.dp())
                })
                addView(label("", 14f, Typeface.BOLD).apply {
                    text = progressCopy(content)
                })
                addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = content.totalSteps.coerceAtLeast(1)
                    progress = content.currentStep.coerceIn(0, max)
                    progressTintList = android.content.res.ColorStateList.valueOf(red)
                    progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(225, 218, 210))
                }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 7.dp()).apply {
                    topMargin = 5.dp()
                    bottomMargin = 12.dp()
                })
                addView(actionButton("Continue ›", onClick), LinearLayout.LayoutParams(120.dp(), 44.dp()).apply {
                    gravity = Gravity.END
                })
            }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 0.62f))
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = 4.dp()
        })
    }

    private fun continueArtwork(content: ContinueLearningContent): View {
        val artwork = content.artwork
        val enso = content.ensoVariant
        if (artwork != null && enso != null) {
            return LearningPathArtworkView(context).apply { setPathArtwork(artwork, enso) }
        }
        return FrameLayout(context).apply {
            addView(FrameLayout(context).apply {
                background = GradientDrawable().apply {
                    setColor(ContextCompat.getColor(context, R.color.progress_pale_fill))
                    shape = GradientDrawable.OVAL
                }
                addView(AppIconView(context, AppIcon.KARATE, sizeDp = 58).apply {
                    setIconColor(red)
                }, FrameLayout.LayoutParams(58.dp(), 58.dp(), Gravity.CENTER))
            }, FrameLayout.LayoutParams(112.dp(), 112.dp(), Gravity.CENTER))
        }
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
                Triple(R.drawable.ic_learn_torii, "Learn\nStep-by-step lessons", onLearn),
                Triple(R.drawable.ic_practice, "Practice\nFree practice & drills", onPractice),
                Triple(R.drawable.ic_skill_coach_target, "Skill Coach\nTechnique feedback", onCoach),
            )
            actions.forEachIndexed { index, (iconRes, copy, callback) ->
                addView(card().apply {
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
                    }, LayoutParams(32.dp(), 32.dp()))
                    addView(label(copy, 14f, Typeface.BOLD, Gravity.CENTER).apply {
                        setTextColor(if (index == 0) ink else red)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                        topMargin = 8.dp()
                    })
                }, LinearLayout.LayoutParams(0, 142.dp(), 1f).apply {
                    if (index > 0) marginStart = 8.dp()
                })
            }
        }

    private fun focusCard() = card().apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(label("🥋", 34f, gravity = Gravity.CENTER), LinearLayout.LayoutParams(58.dp(), 58.dp()))
        addView(label("Work on\nChūdan Punch\nTarget awareness", 16f, Typeface.BOLD), LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(label("›", 30f, gravity = Gravity.CENTER), LinearLayout.LayoutParams(30.dp(), 50.dp()))
    }

    private fun activityCard() = card().apply {
        orientation = LinearLayout.VERTICAL
        activityRow("◎", "Skill Coach — Jōdan Punch", "10 punches analyzed  ·  2 days ago")
        divider()
        activityRow("✦", "Practice — Speed & Control", "8 min session  ·  3 days ago")
        divider()
        activityRow("▰", "Learn — Chūdan Punch", "Step 2: Target awareness  ·  4 days ago")
    }

    private fun LinearLayout.activityRow(icon: String, title: String, detail: String) {
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(label(icon, 23f, Typeface.BOLD, Gravity.CENTER).apply { setTextColor(red) }, LinearLayout.LayoutParams(42.dp(), 54.dp()))
            addView(label("$title\n$detail", 14f, Typeface.BOLD).apply { setTextColor(ink) }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(label("›", 25f, gravity = Gravity.CENTER), LinearLayout.LayoutParams(25.dp(), 54.dp()))
        })
    }

    private fun LinearLayout.divider() = addView(View(context).apply { setBackgroundColor(dividerColor) }, LayoutParams(LayoutParams.MATCH_PARENT, 1.dp()))

    private fun progressCard() = card().apply {
        orientation = LinearLayout.VERTICAL
        progressRow("Jōdan Punch", 4, 7)
        progressRow("Chūdan Punch", 2, 6)
        addView(label("Gedan Punch                         Not started", 14f, Typeface.BOLD))
    }

    private fun LinearLayout.progressRow(title: String, value: Int, max: Int) {
        addView(label("$title                                      $value / $max steps", 14f, Typeface.BOLD))
        addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            this.max = max
            progress = value
            progressTintList = android.content.res.ColorStateList.valueOf(red)
        }, LayoutParams(LayoutParams.MATCH_PARENT, 10.dp()).apply { bottomMargin = 12.dp() })
    }

    private fun sectionLabel(text: String) = label(text, 13f, Typeface.BOLD).apply {
        setTextColor(muted)
        setPadding(2.dp(), 18.dp(), 0, 8.dp())
    }

    private fun actionButton(text: String, onClick: () -> Unit) = label(text, 17f, Typeface.BOLD, Gravity.CENTER).apply {
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
        elevation = 2.dp().toFloat()
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

    private fun outlinedCircle() = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(paper)
        setStroke(2.dp(), ink)
    }

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()
}
