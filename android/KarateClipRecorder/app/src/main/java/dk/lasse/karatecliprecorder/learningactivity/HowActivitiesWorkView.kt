package dk.lasse.karatecliprecorder.learningactivity

import android.content.Context
import android.graphics.Typeface
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

internal class ActivityWalkthrough {
    var step = 0
        private set
    val complete get() = step == 3
    fun next() { if (!complete) step++ }
    fun previous() { if (step in 1..2) step-- }
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
    private var saveError = false

    init {
        setHeader(path.title, "${path.activities.indexOfFirst { it.id == activity.definition.id } + 1} / ${path.activities.size}")
        setContext(activity.section.title, "Learn")
        setHeading(activity.definition.title, "Try the steps you will use in other activities.")
        repository.touchActiveLearningActivity(path.id, activity.definition.id)
        render()
    }

    private fun render() {
        val titles = listOf("Before you begin", "Follow the instruction", "Review what happened", "Walkthrough complete")
        val descriptions = listOf(
            "Each activity starts by explaining what you will do. Read the instructions, then use the action at the bottom when you are ready. Try it now.",
            "This is where the activity happens. You might read, answer a question or practise a movement. Here, try the buttons below: Previous lets you review, and Show feedback takes you forward. The Back arrow returns to your tutorial.",
            "Some activities show feedback after an attempt. Others finish without a separate result. This walkthrough has no score: you are practising how to move through an activity. Select Finish walkthrough to save your progress.",
            "You have tried starting an activity, following its instructions and reaching the end. Your progress is saved. Return to the tutorial to choose your next activity.",
        )
        setRunnerContent(SettingsCardView(context).apply {
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            addView(TextView(context).apply {
                text = titles[walkthrough.step]
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(context, R.color.app_text_primary))
                ViewCompat.setAccessibilityHeading(this, true)
            })
            addView(TextView(context).apply {
                text = if (saveError) "Your progress could not be saved. Try again, or use Back to return to the tutorial." else descriptions[walkthrough.step]
                textSize = 16f
                setPadding(0, pad / 2, 0, 0)
                setTextColor(ContextCompat.getColor(context, R.color.app_text_secondary))
                ViewCompat.setAccessibilityLiveRegion(this, ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE)
            })
        })
        setActions(
            secondary = if (walkthrough.step in 1..2) ActivityShellAction("Previous") {
                saveError = false; walkthrough.previous(); render()
            } else null,
            primary = ActivityShellAction(if (saveError) "Try saving again" else listOf("Start walkthrough", "Show feedback", "Finish walkthrough", "Return to tutorial")[walkthrough.step]) {
                if (walkthrough.complete) {
                    onFinished()
                } else {
                    if (walkthrough.step == 2 && !saveCompletion()) return@ActivityShellAction
                    walkthrough.next(); render()
                }
            },
        )
    }

    private fun saveCompletion(): Boolean {
        return try {
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
            saveError = true; render(); false
        }
    }
}
