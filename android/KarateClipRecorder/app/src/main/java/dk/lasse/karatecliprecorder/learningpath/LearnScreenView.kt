package dk.lasse.karatecliprecorder.learningpath

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import dk.lasse.karatecliprecorder.AppBottomNavigationView
import dk.lasse.karatecliprecorder.AppDestination
import dk.lasse.karatecliprecorder.AppIcon
import dk.lasse.karatecliprecorder.AppIconView
import dk.lasse.karatecliprecorder.MainPageHeader
import dk.lasse.karatecliprecorder.R
import dk.lasse.karatecliprecorder.StickyHeaderPageLayout
import dk.lasse.karatecliprecorder.learningartwork.LearningPathArtworkView
import dk.lasse.karatecliprecorder.profile.ProfileAvatarButton
import dk.lasse.karatecliprecorder.profile.ProfileRepository

/** Passive Train destination: choosing a path never starts camera or speech infrastructure. */
class LearnScreenView(
    context: Context,
    private val profileRepository: ProfileRepository,
    private val onProfile: () -> Unit,
    private val paths: List<LearningPath>,
    private val karateBasics: DraftLearningPathDefinition,
    private val onPathSelected: (LearningPath) -> Unit,
    private val onKarateBasicsSelected: () -> Unit,
    onHome: () -> Unit,
    onProgress: () -> Unit,
    onSettings: () -> Unit,
) : FrameLayout(context) {
    private val red = ContextCompat.getColor(context, R.color.app_accent)
    private val ink = ContextCompat.getColor(context, R.color.app_text_primary)
    private val muted = ContextCompat.getColor(context, R.color.app_text_secondary)
    private val paper = ContextCompat.getColor(context, R.color.home_card_surface)
    private val border = ContextCompat.getColor(context, R.color.app_border)
    private val backgroundColor = ContextCompat.getColor(context, R.color.app_background)
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val mainHeader = MainPageHeader(
        context = context,
        title = "Train",
        subtitle = "Build your skills step by step.",
        trailingSlot = ProfileAvatarButton(context, profileRepository, onProfile),
    )
    private val profileListener: (dk.lasse.karatecliprecorder.profile.Profile) -> Unit = { renderContent() }
    private var observing = false

    init {
        setBackgroundColor(backgroundColor)
        renderContent()

        addView(StickyHeaderPageLayout(
            context = context,
            header = mainHeader,
            body = content,
            topContentPaddingDp = 14,
            bottomContentClearanceDp = AppBottomNavigationView.CONTENT_CLEARANCE_DP,
        ), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val navigation = AppBottomNavigationView(
            context = context,
            selectedDestination = AppDestination.TRAIN,
            onHome = onHome,
            onTrain = {},
            onProgress = onProgress,
            onSettings = onSettings,
        )
        addView(navigation, LayoutParams(
            LayoutParams.MATCH_PARENT,
            AppBottomNavigationView.BASE_HEIGHT_DP.dp(),
            Gravity.BOTTOM,
        ))

    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!observing) {
            observing = true
            profileRepository.addActiveProfileListener(profileListener)
        }
    }

    override fun onDetachedFromWindow() {
        if (observing) profileRepository.removeActiveProfileListener(profileListener)
        observing = false
        super.onDetachedFromWindow()
    }

    private fun renderContent() {
        content.removeAllViews()
        val scopedPaths = profileScopedPaths()
        val currentPath = scopedPaths.first { it.id == LearningPathId.JODAN_PUNCH }
        content.addView(continueCard(currentPath) { onPathSelected(currentPath) })
        content.addView(sectionTitle("Foundations"))
        content.addView(draftPathCard())
        scopedPaths.groupBy(LearningPath::category).forEach { (category, categoryPaths) ->
            content.addView(sectionTitle(category))
            categoryPaths.forEachIndexed { index, path ->
                content.addView(pathCard(path) { onPathSelected(path) }, LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT,
                ).apply { if (index > 0) topMargin = 8.dp() })
            }
        }
    }

    private fun profileScopedPaths(): List<LearningPath> {
        return LearningPathProgressResolver.resolve(paths, profileRepository.learningProgress())
    }

    private fun draftPathCard(): LinearLayout {
        val completedIds = profileRepository.learningProgress()
            .asSequence()
            .filter { it.learningPathId == karateBasics.id && it.status == dk.lasse.karatecliprecorder.profile.LearningStatus.COMPLETED }
            .mapTo(mutableSetOf()) { it.activityId }
        val resolved = DraftLearningPathProgressResolver.resolve(
            karateBasics,
            completedIds,
            activeProfileExists = profileRepository.listProfiles().isNotEmpty(),
        )
        return card().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(15.dp(), 14.dp(), 10.dp(), 14.dp())
            minimumHeight = 118.dp()
            isClickable = true
            isFocusable = true
            contentDescription = "Karate Basics, ${resolved.completedCount} of ${resolved.totalCount} activities complete"
            setOnClickListener { onKarateBasicsSelected() }
            addView(FrameLayout(context).apply {
                background = GradientDrawable().apply {
                    setColor(ContextCompat.getColor(context, R.color.progress_pale_fill))
                    shape = GradientDrawable.OVAL
                }
                addView(AppIconView(context, AppIcon.KARATE, sizeDp = 48).apply {
                    setIconColor(red)
                }, FrameLayout.LayoutParams(48.dp(), 48.dp(), Gravity.CENTER))
            }, LayoutParams(82.dp(), 82.dp()))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(12.dp(), 0, 6.dp(), 0)
                addView(label(karateBasics.title, 19f, Typeface.BOLD))
                addView(label("Draft learning path", 14f).apply {
                    setTextColor(muted)
                    setPadding(0, 2.dp(), 0, 6.dp())
                })
                addView(label("${resolved.completedCount} / ${resolved.totalCount} activities", 14f, Typeface.BOLD).apply {
                    setTextColor(if (resolved.completedCount > 0) red else muted)
                })
                addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = resolved.totalCount.coerceAtLeast(1)
                    progress = resolved.completedCount
                    progressTintList = ColorStateList.valueOf(red)
                    progressBackgroundTintList = ColorStateList.valueOf(border)
                }, LayoutParams(LayoutParams.MATCH_PARENT, 6.dp()).apply { topMargin = 4.dp() })
            }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(AppIconView(context, AppIcon.CHEVRON_RIGHT).apply { setIconColor(ink) }, LayoutParams(24.dp(), 44.dp()))
        }
    }

    private fun continueCard(path: LearningPath, onClick: () -> Unit) = card().apply {
        orientation = LinearLayout.VERTICAL
        setPadding(16.dp(), 15.dp(), 16.dp(), 16.dp())
        isClickable = true
        isFocusable = true
        contentDescription = "Continue learning ${path.title}, ${path.progressValue} of ${path.totalSteps} steps"
        setOnClickListener { onClick() }
        addView(label("Continue learning", 17f, Typeface.BOLD).apply { setTextColor(red) })
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(pathArtwork(path), LinearLayout.LayoutParams(0, 150.dp(), 0.42f).apply { marginEnd = 8.dp() })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                addView(label(path.title, 22f, Typeface.BOLD))
                addView(label(path.category, 15f).apply {
                    setTextColor(muted)
                    setPadding(0, 2.dp(), 0, 11.dp())
                })
                addView(progressCopy(path, "of"), LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                addView(progressBar(path), LayoutParams(LayoutParams.MATCH_PARENT, 7.dp()).apply {
                    topMargin = 5.dp()
                    bottomMargin = 13.dp()
                })
                addView(actionLabel("Continue  ›", onClick), LayoutParams(132.dp(), 46.dp()).apply {
                    gravity = Gravity.END
                })
            }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 0.58f))
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun pathCard(path: LearningPath, onClick: () -> Unit) = card().apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(8.dp(), 7.dp(), 10.dp(), 7.dp())
        minimumHeight = 128.dp()
        isClickable = true
        isFocusable = true
        contentDescription = buildString {
            append(path.title).append(", ").append(path.subtitle).append(", ")
            append(if (path.progressValue == 0) "Not started" else "${path.progressValue} of ${path.totalSteps} steps")
        }
        setOnClickListener { onClick() }
        addView(pathArtwork(path), LayoutParams(118.dp(), 118.dp()))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10.dp(), 0, 6.dp(), 0)
            addView(label(path.title, 18f, Typeface.BOLD))
            addView(label(path.subtitle, 14f).apply {
                setTextColor(muted)
                setPadding(0, 2.dp(), 0, 5.dp())
            })
            if (path.progressValue == 0) {
                addView(label("Not started", 14f).apply { setTextColor(muted) })
            } else {
                addView(progressCopy(path, "/"))
                addView(progressBar(path), LayoutParams(LayoutParams.MATCH_PARENT, 6.dp()).apply { topMargin = 4.dp() })
            }
        }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(AppIconView(context, AppIcon.CHEVRON_RIGHT).apply {
            setIconColor(ink)
        }, LayoutParams(24.dp(), 44.dp()))
    }

    private fun pathArtwork(path: LearningPath) = LearningPathArtworkView(context).apply {
        setPathArtwork(path.artwork, path.ensoVariant)
        contentDescription = "${path.title} artwork"
    }

    private fun progressCopy(path: LearningPath, separator: String): TextView {
        val value = path.progressValue.toString()
        val copy = if (separator == "/") "$value / ${path.totalSteps} steps" else "$value of ${path.totalSteps} steps"
        return label("", 14f, Typeface.BOLD).apply {
            text = SpannableString(copy).also {
                it.setSpan(ForegroundColorSpan(red), 0, value.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    private fun progressBar(path: LearningPath) = ProgressBar(
        context,
        null,
        android.R.attr.progressBarStyleHorizontal,
    ).apply {
        max = path.totalSteps.coerceAtLeast(1)
        progress = path.progressValue.coerceIn(0, max)
        progressTintList = ColorStateList.valueOf(red)
        progressBackgroundTintList = ColorStateList.valueOf(border)
    }

    private fun sectionTitle(text: String) = label(text, 22f, Typeface.BOLD).apply {
        setPadding(2.dp(), 24.dp(), 0, 10.dp())
    }

    private fun card() = LinearLayout(context).apply {
        background = GradientDrawable().apply {
            setColor(paper)
            cornerRadius = 16.dp().toFloat()
            setStroke(1.dp(), border)
        }
        elevation = 2.dp().toFloat()
    }

    private fun actionLabel(text: String, onClick: () -> Unit) = label(
        text,
        17f,
        Typeface.BOLD,
        Gravity.CENTER,
    ).apply {
        setTextColor(android.graphics.Color.WHITE)
        background = GradientDrawable().apply {
            setColor(red)
            cornerRadius = 11.dp().toFloat()
        }
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun label(text: String, size: Float, style: Int = Typeface.NORMAL, gravity: Int = Gravity.START) =
        TextView(context).apply {
            this.text = text
            textSize = size
            setTextColor(ink)
            typeface = Typeface.create("sans-serif", style)
            this.gravity = gravity
        }

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()
}
