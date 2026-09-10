package dk.lasse.karatecliprecorder.learningactivity

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import dk.lasse.karatecliprecorder.R
import dk.lasse.karatecliprecorder.SettingsCardView
import dk.lasse.karatecliprecorder.learningpath.DraftLearningPathDefinition
import dk.lasse.karatecliprecorder.learningpath.ResolvedDraftActivity
import dk.lasse.karatecliprecorder.profile.LearningProgress
import dk.lasse.karatecliprecorder.profile.LearningStatus
import dk.lasse.karatecliprecorder.profile.ProfileRepository

internal enum class ActivityWalkthroughStep { READY, TARGET, FEEDBACK, COMPLETE }

internal class ActivityWalkthrough {
    var step = ActivityWalkthroughStep.READY
        private set
    var targetTapped = false
        private set

    val shellState: ActivityShellState get() = when (step) {
        ActivityWalkthroughStep.READY -> ActivityShellState.READY
        ActivityWalkthroughStep.TARGET -> ActivityShellState.ACTIVE
        ActivityWalkthroughStep.FEEDBACK -> ActivityShellState.RESULT
        ActivityWalkthroughStep.COMPLETE -> ActivityShellState.COMPLETE
    }
    val canShowFeedback get() = step == ActivityWalkthroughStep.TARGET && targetTapped

    fun start() { if (step == ActivityWalkthroughStep.READY) step = ActivityWalkthroughStep.TARGET }
    fun tapTarget() { if (step == ActivityWalkthroughStep.TARGET) targetTapped = true }
    fun showFeedback() { if (canShowFeedback) step = ActivityWalkthroughStep.FEEDBACK }
    fun previous() { if (step == ActivityWalkthroughStep.FEEDBACK) step = ActivityWalkthroughStep.TARGET }
    fun continueToComplete() { if (step == ActivityWalkthroughStep.FEEDBACK) step = ActivityWalkthroughStep.COMPLETE }
    fun canFinish() = step == ActivityWalkthroughStep.COMPLETE
    fun finish(saveCompletion: () -> Boolean) = canFinish() && saveCompletion()
}

/** Touch-only introduction. Completion records a walkthrough, never an assessment. */
class HowActivitiesWorkView(
    context: Context,
    private val repository: ProfileRepository,
    private val path: DraftLearningPathDefinition,
    private val activity: ResolvedDraftActivity,
    private val onReturn: () -> Unit,
    private val onFinished: () -> Unit,
) : ActivityShellView(context, onExit = onReturn) {
    private val walkthrough = ActivityWalkthrough()
    private val ownerId = repository.activeProfile().id
    private val red = ContextCompat.getColor(context, R.color.app_accent)
    private val ink = ContextCompat.getColor(context, R.color.app_text_primary)
    private val muted = ContextCompat.getColor(context, R.color.app_text_secondary)
    private val paleRed = ContextCompat.getColor(context, R.color.progress_pale_fill)
    private var saveError = false

    init {
        setHeader(path.title, "${path.activities.indexOfFirst { it.id == activity.definition.id } + 1} / ${path.activities.size}")
        setContext(activity.section.title, "Learn")
        repository.touchActiveLearningActivity(path.id, activity.definition.id)
        render()
    }

    private fun render() {
        when (walkthrough.step) {
            ActivityWalkthroughStep.READY -> renderReady()
            ActivityWalkthroughStep.TARGET -> renderTarget()
            ActivityWalkthroughStep.FEEDBACK -> renderFeedback()
            ActivityWalkthroughStep.COMPLETE -> renderComplete()
        }
    }

    private fun renderReady() {
        setHeading(activity.definition.title, "Try one tiny activity.")
        setRunnerContent(card("Ready?", "You’ll do one simple task and see feedback."))
        setProgressContent(null)
        setActions(null, ActivityShellAction("Start") { walkthrough.start(); render() })
    }

    private fun renderTarget() {
        setHeading(activity.definition.title, "")
        setRunnerContent(SettingsCardView(context).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(16.dp(), 20.dp(), 16.dp(), 20.dp())
            addView(label("Tap the target", 20f, Typeface.BOLD).heading())
            addView(label(if (walkthrough.targetTapped) "Target tapped" else "Your turn", 15f, gravity = Gravity.CENTER).apply {
                setTextColor(if (walkthrough.targetTapped) red else muted)
                setPadding(0, 6.dp(), 0, 16.dp())
                ViewCompat.setAccessibilityLiveRegion(this, ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE)
            })
            addView(target(), LinearLayout.LayoutParams(136.dp(), 136.dp()).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            })
            if (!walkthrough.targetTapped) addView(label("Tap the target first", 13f, gravity = Gravity.CENTER).apply {
                setTextColor(muted)
                setPadding(0, 14.dp(), 0, 0)
            })
        })
        setProgressContent(null)
        setActions(null, ActivityShellAction("Show feedback", walkthrough.canShowFeedback) {
            walkthrough.showFeedback(); render()
        })
    }

    private fun renderFeedback() {
        setHeading(activity.definition.title, "")
        setRunnerContent(card(
            "That’s it",
            "Feedback appears after your attempt. Some activities score you; others simply confirm completion.",
        ))
        setProgressContent(null)
        setActions(
            ActivityShellAction("Previous") { walkthrough.previous(); render() },
            ActivityShellAction("Continue") { walkthrough.continueToComplete(); render() },
        )
    }

    private fun renderComplete() {
        setHeading(activity.definition.title, "")
        setRunnerContent(SettingsCardView(context).apply {
            setPadding(16.dp(), 18.dp(), 16.dp(), 18.dp())
            addView(label("You know the pattern", 20f, Typeface.BOLD).heading())
            addView(sequence(), matchWrap().apply { topMargin = 16.dp() })
            if (saveError) addView(label("Your progress could not be saved. Try again or use Back.", 14f).apply {
                setTextColor(muted)
                setPadding(0, 14.dp(), 0, 0)
                ViewCompat.setAccessibilityLiveRegion(this, ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE)
            })
        })
        setProgressContent(null)
        setActions(null, ActivityShellAction(if (saveError) "Try saving again" else "Return to tutorial") {
            if (walkthrough.finish(::saveCompletion)) onFinished()
        })
    }

    private fun target() = FrameLayout(context).apply {
        isClickable = true
        isFocusable = true
        contentDescription = if (walkthrough.targetTapped) "Karate target, tapped" else "Karate target. Tap target"
        background = oval(red)
        addView(View(context).apply { background = oval(Color.WHITE) }, FrameLayout.LayoutParams(88.dp(), 88.dp(), Gravity.CENTER))
        addView(View(context).apply { background = oval(red) }, FrameLayout.LayoutParams(52.dp(), 52.dp(), Gravity.CENTER))
        if (walkthrough.targetTapped) addView(label("✓", 24f, Typeface.BOLD, Gravity.CENTER).apply {
            setTextColor(Color.WHITE)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(52.dp(), 52.dp(), Gravity.CENTER))
        setOnClickListener {
            if (!walkthrough.targetTapped) {
                walkthrough.tapTarget()
                animate().scaleX(0.94f).scaleY(0.94f).setDuration(80).withEndAction {
                    animate().scaleX(1f).scaleY(1f).setDuration(100).withEndAction { render() }.start()
                }.start()
            }
        }
    }

    private fun sequence() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        listOf("Start", "Do", "Feedback", "Done").forEachIndexed { index, text ->
            if (index > 0) addView(label("→", 15f, Typeface.BOLD, Gravity.CENTER).apply { setTextColor(muted) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.35f))
            addView(label(text, 13f, Typeface.BOLD, Gravity.CENTER).apply {
                setTextColor(if (text == "Done") Color.WHITE else red)
                background = GradientDrawable().apply {
                    setColor(if (text == "Done") red else paleRed)
                    cornerRadius = 12.dp().toFloat()
                }
                setPadding(6.dp(), 8.dp(), 6.dp(), 8.dp())
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        contentDescription = "Start, then do, then feedback, then done"
    }

    private fun card(title: String, body: String) = SettingsCardView(context).apply {
        setPadding(16.dp(), 18.dp(), 16.dp(), 18.dp())
        addView(label(title, 20f, Typeface.BOLD).heading())
        addView(label(body, 16f).apply { setTextColor(muted); setPadding(0, 8.dp(), 0, 0) })
    }

    private fun saveCompletion(): Boolean = try {
        val existing = repository.learningProgress(ownerId).firstOrNull {
            it.learningPathId == path.id && it.activityId == activity.definition.id
        }
        if (existing?.status != LearningStatus.COMPLETED) {
            repository.saveLearningProgress(LearningProgress(ownerId, path.id, activity.definition.id,
                LearningStatus.COMPLETED, completedAt = System.currentTimeMillis()))
        }
        saveError = false
        true
    } catch (_: Exception) {
        saveError = true
        render()
        false
    }

    private fun label(text: String, size: Float, style: Int = Typeface.NORMAL, gravity: Int = Gravity.START) =
        TextView(context).apply {
            this.text = text
            textSize = size
            setTextColor(ink)
            typeface = Typeface.create("sans-serif", style)
            this.gravity = gravity
        }

    private fun TextView.heading() = apply { ViewCompat.setAccessibilityHeading(this, true) }
    private fun oval(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()
}
