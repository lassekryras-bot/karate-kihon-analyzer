package dk.lasse.karatecliprecorder.learningactivity

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import dk.lasse.karatecliprecorder.R
import dk.lasse.karatecliprecorder.learningpath.DraftLearningPathDefinition
import dk.lasse.karatecliprecorder.learningpath.ResolvedDraftActivity
import dk.lasse.karatecliprecorder.profile.LearningStatus
import dk.lasse.karatecliprecorder.profile.ProfileRepository

enum class DraftShellStage(val displayName: String) {
    INTRO("Intro"),
    ACTIVITY("Activity"),
    RESULT("Result"),
    COMPLETE("Complete"),
}

/** Temporary developer shell shared by all unfinished Karate Basics activities. */
class DraftPlaceholderActivityView(
    context: Context,
    private val repository: ProfileRepository,
    private val path: DraftLearningPathDefinition,
    private val activity: ResolvedDraftActivity,
    private val onReturnToPath: () -> Unit,
    private val onCompletedAndReturn: () -> Unit,
) : ActivityShellView(context, onExit = onReturnToPath) {
    private var stage = DraftShellStage.INTRO
    private val ink = ContextCompat.getColor(context, R.color.app_text_primary)
    private val muted = ContextCompat.getColor(context, R.color.app_text_secondary)
    private val red = ContextCompat.getColor(context, R.color.app_accent)
    private val surface = ContextCompat.getColor(context, R.color.app_card_surface)
    private val border = ContextCompat.getColor(context, R.color.app_border)

    init {
        val position = path.activities.indexOfFirst { it.id == activity.definition.id } + 1
        setHeader(path.title, "$position / ${path.activities.size}")
        setContext(activity.section.title, "Draft / Placeholder")
        setHeading(
            activity.definition.title,
            "This is a structural placeholder. Finished lesson content and training analysis will be added later.",
        )
        repository.touchActiveLearningActivity(path.id, activity.definition.id)
        render()
    }

    private fun render() {
        setRunnerContent(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp(), 16.dp(), 16.dp(), 16.dp())
            background = GradientDrawable().apply {
                setColor(surface)
                cornerRadius = 15.dp().toFloat()
                setStroke(1.dp(), border)
            }
            addView(label("Current shell stage", 13f, Typeface.BOLD).apply { setTextColor(muted) })
            addView(label(stage.displayName, 25f, Typeface.BOLD).apply {
                setTextColor(red)
                setPadding(0, 3.dp(), 0, 10.dp())
            })
            addView(label(stageDescription(), 15f).apply {
                setTextColor(ink)
                setPadding(0, 0, 0, 18.dp())
            })
            addView(label("Development controls", 13f, Typeface.BOLD).apply { setTextColor(muted) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(devButton("Mark complete") { markComplete() }, LinearLayout.LayoutParams(0, 48.dp(), 1f).apply {
                    marginEnd = 5.dp()
                })
                addView(devButton("Reset activity") { resetActivity() }, LinearLayout.LayoutParams(0, 48.dp(), 1f).apply {
                    marginStart = 5.dp()
                })
            }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = 8.dp() })
        })

        if (stage == DraftShellStage.COMPLETE) {
            setActions(
                secondary = ActivityShellAction("Return to path", onClick = onReturnToPath),
                primary = ActivityShellAction("Next stage", onClick = onCompletedAndReturn),
            )
        } else {
            setActions(
                secondary = ActivityShellAction(
                    label = "Previous stage",
                    enabled = stage != DraftShellStage.INTRO,
                    onClick = {
                        stage = DraftShellStage.entries[stage.ordinal - 1]
                        render()
                    },
                ),
                primary = ActivityShellAction("Next stage") {
                    stage = DraftShellStage.entries[stage.ordinal + 1]
                    if (stage == DraftShellStage.COMPLETE) saveComplete()
                    render()
                },
            )
        }
    }

    private fun markComplete() {
        stage = DraftShellStage.COMPLETE
        saveComplete()
        render()
    }

    private fun saveComplete() {
        repository.saveActiveLearningProgress(path.id, activity.definition.id, LearningStatus.COMPLETED)
    }

    private fun resetActivity() {
        repository.saveActiveLearningProgress(
            path.id,
            activity.definition.id,
            LearningStatus.NOT_STARTED,
            completedAt = null,
        )
        stage = DraftShellStage.INTRO
        render()
    }

    private fun stageDescription(): String = when (stage) {
        DraftShellStage.INTRO -> "Introduce the goal and explain what the trainee will do."
        DraftShellStage.ACTIVITY -> "Run the future lesson, interaction, or physical training experience."
        DraftShellStage.RESULT -> "Present the trainee's future feedback and result summary."
        DraftShellStage.COMPLETE -> "Activity complete. Progress is saved to the active profile and unlocked activities are now available."
    }

    private fun devButton(text: String, onClick: () -> Unit) = label(text, 14f, Typeface.BOLD).apply {
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            setColor(surface)
            cornerRadius = 11.dp().toFloat()
            setStroke(1.dp(), border)
        }
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun label(text: String, size: Float, style: Int = Typeface.NORMAL) = TextView(context).apply {
        this.text = text
        textSize = size
        typeface = Typeface.create("sans-serif", style)
        setTextColor(ink)
    }

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()
}
