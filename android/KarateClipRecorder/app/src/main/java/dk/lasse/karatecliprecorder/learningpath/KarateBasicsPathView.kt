package dk.lasse.karatecliprecorder.learningpath

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import dk.lasse.karatecliprecorder.AppIcon
import dk.lasse.karatecliprecorder.AppIconView
import dk.lasse.karatecliprecorder.R
import dk.lasse.karatecliprecorder.StickyHeaderPageLayout
import dk.lasse.karatecliprecorder.SubPageHeader
import dk.lasse.karatecliprecorder.profile.LearningStatus
import dk.lasse.karatecliprecorder.profile.Profile
import dk.lasse.karatecliprecorder.profile.ProfileRepository

/** Renders every Karate Basics section and dependency state from the JSON curriculum graph. */
class KarateBasicsPathView(
    context: Context,
    private val repository: ProfileRepository,
    private val path: DraftLearningPathDefinition,
    private val initialScrollY: Int,
    private val completionAnimationActivityId: String?,
    private val onScrollPositionChanged: (Int) -> Unit,
    onBack: () -> Unit,
    private val onActivitySelected: (ResolvedDraftActivity) -> Unit,
) : FrameLayout(context) {
    private val red = ContextCompat.getColor(context, R.color.progress_red)
    private val gray = ContextCompat.getColor(context, R.color.progress_gray)
    private val ink = ContextCompat.getColor(context, R.color.app_text_primary)
    private val muted = ContextCompat.getColor(context, R.color.app_text_secondary)
    private val surface = ContextCompat.getColor(context, R.color.app_card_surface)
    private val border = ContextCompat.getColor(context, R.color.app_border)
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val page = StickyHeaderPageLayout(
        context = context,
        header = SubPageHeader(
            context = context,
            title = path.title,
            subtitle = "Draft learning path",
            onBack = onBack,
        ),
        body = content,
        horizontalContentPaddingDp = 16,
        topContentPaddingDp = 10,
        bottomContentClearanceDp = 30,
    )
    private val profileListener: (Profile) -> Unit = { render() }
    private var observing = false

    init {
        setBackgroundColor(ContextCompat.getColor(context, R.color.app_background))
        addView(page, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        page.scroller.setOnScrollChangeListener { _, _, scrollY, _, _ -> onScrollPositionChanged(scrollY) }
        render()
        page.scroller.post { page.scroller.scrollTo(0, initialScrollY) }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!observing) {
            observing = true
            repository.addActiveProfileListener(profileListener)
        }
    }

    override fun onDetachedFromWindow() {
        if (observing) repository.removeActiveProfileListener(profileListener)
        observing = false
        super.onDetachedFromWindow()
    }

    private fun render() {
        val retainedScrollY = page.scroller.scrollY
        content.removeAllViews()
        val resolved = resolvedPath()
        content.addView(summaryCard(resolved))
        resolved.sections.forEach { (section, activities) ->
            content.addView(label(section.title, 21f, Typeface.BOLD).apply {
                setPadding(2.dp(), 24.dp(), 0, 8.dp())
            })
            content.addView(ProgressionTimelineLayout(context, red, gray).apply {
                activities.forEach { activity ->
                    addProgressCard(
                        card = activityCard(activity),
                        type = if (activity.definition.id == "karate-basics-challenge") {
                            LearningStepType.MILESTONE
                        } else {
                            LearningStepType.REGULAR
                        },
                        state = activity.progressState.toMarkerState(),
                        animateCompletion = activity.definition.id == completionAnimationActivityId,
                    )
                }
            }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        page.scroller.post { page.scroller.scrollTo(0, retainedScrollY.coerceAtLeast(initialScrollY)) }
    }

    private fun resolvedPath(): ResolvedDraftLearningPath {
        val completed = repository.learningProgress()
            .asSequence()
            .filter { it.learningPathId == path.id && it.status == LearningStatus.COMPLETED }
            .mapTo(mutableSetOf()) { it.activityId }
        return DraftLearningPathProgressResolver.resolve(
            path = path,
            completedActivityIds = completed,
            activeProfileExists = repository.listProfiles().isNotEmpty(),
        )
    }

    private fun summaryCard(path: ResolvedDraftLearningPath) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(16.dp(), 15.dp(), 16.dp(), 16.dp())
        background = cardBackground(border)
        elevation = 2.dp().toFloat()
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(AppIconView(context, AppIcon.KARATE, sizeDp = 42).apply { setIconColor(red) }, LayoutParams(48.dp(), 48.dp()))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(10.dp(), 0, 0, 0)
                addView(label(path.definition.title, 23f, Typeface.BOLD))
                addView(label("${path.completedCount} of ${path.totalCount} activities complete", 14f, Typeface.BOLD).apply {
                    setTextColor(if (path.completedCount > 0) red else muted)
                })
            }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        })
        addView(label(path.definition.purpose, 15f).apply {
            setTextColor(muted)
            setPadding(0, 9.dp(), 0, 10.dp())
        })
        addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = path.totalCount.coerceAtLeast(1)
            progress = path.completedCount
            progressTintList = ColorStateList.valueOf(red)
            progressBackgroundTintList = ColorStateList.valueOf(border)
        }, LayoutParams(LayoutParams.MATCH_PARENT, 7.dp()))
    }

    private fun activityCard(activity: ResolvedDraftActivity) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(14.dp(), 13.dp(), 12.dp(), 13.dp())
        background = cardBackground(if (activity.progressState == DraftActivityProgressState.AVAILABLE) red else border)
        val copy = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(activity.definition.title, 17f, Typeface.BOLD))
            addView(label(activity.statusCopy(), 13f, Typeface.BOLD).apply {
                setTextColor(if (activity.progressState == DraftActivityProgressState.AVAILABLE) red else muted)
                setPadding(0, 3.dp(), 0, 0)
            })
        }
        addView(copy, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        if (activity.progressState != DraftActivityProgressState.LOCKED) {
            addView(AppIconView(context, AppIcon.CHEVRON_RIGHT).apply { setIconColor(ink) }, LayoutParams(24.dp(), 42.dp()))
            isClickable = true
            isFocusable = true
            setOnClickListener { onActivitySelected(activity) }
        } else {
            alpha = 0.68f
        }
        contentDescription = "${activity.definition.title}, ${activity.statusCopy()}"
    }

    private fun ResolvedDraftActivity.statusCopy(): String = when (progressState) {
        DraftActivityProgressState.COMPLETED -> if (definition.type == DraftActivityType.CONDITIONAL_PROFILE) {
            "Profile ready • Replayable"
        } else {
            "Completed • Replay"
        }
        DraftActivityProgressState.AVAILABLE -> "Available"
        DraftActivityProgressState.LOCKED -> "Locked • Complete prerequisites first"
    }

    private fun DraftActivityProgressState.toMarkerState(): LearningProgressState = when (this) {
        DraftActivityProgressState.COMPLETED -> LearningProgressState.COMPLETED
        DraftActivityProgressState.AVAILABLE -> LearningProgressState.AVAILABLE
        DraftActivityProgressState.LOCKED -> LearningProgressState.LOCKED
    }

    private fun cardBackground(strokeColor: Int) = GradientDrawable().apply {
        setColor(surface)
        cornerRadius = 14.dp().toFloat()
        setStroke(1.dp(), strokeColor)
    }

    private fun label(text: String, size: Float, style: Int = Typeface.NORMAL) = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(ink)
        typeface = Typeface.create("sans-serif", style)
    }

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()
}
