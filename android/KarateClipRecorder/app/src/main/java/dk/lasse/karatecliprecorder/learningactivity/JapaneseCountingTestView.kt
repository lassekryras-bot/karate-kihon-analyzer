package dk.lasse.karatecliprecorder.learningactivity

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import dk.lasse.karatecliprecorder.AppIcon
import dk.lasse.karatecliprecorder.AppIconView
import dk.lasse.karatecliprecorder.R
import dk.lasse.karatecliprecorder.learning.CountResult
import dk.lasse.karatecliprecorder.learning.CountStatus
import dk.lasse.karatecliprecorder.learning.CountTrainingSession
import dk.lasse.karatecliprecorder.learning.JapaneseCountLesson

/** Microphone counting test presented without the camera/debug training surface. */
@SuppressLint("ViewConstructor")
class JapaneseCountingTestView(
    context: Context,
    onExit: () -> Unit,
    private val pathPosition: String = "2 / 2",
    private val onPlayExample: () -> Unit,
    private val onStartListening: () -> Unit,
    private val onStopListening: () -> Unit,
    private val onTryAgain: () -> Unit,
    private val onFinish: () -> Unit,
) : FrameLayout(context) {
    private val red = ContextCompat.getColor(context, R.color.app_accent)
    private val ink = ContextCompat.getColor(context, R.color.app_text_primary)
    private val muted = ContextCompat.getColor(context, R.color.app_text_secondary)
    private val border = ContextCompat.getColor(context, R.color.app_border)
    private val surface = ContextCompat.getColor(context, R.color.app_card_surface)
    private val warmSurface = ContextCompat.getColor(context, R.color.home_card_surface)
    private val paleRed = ContextCompat.getColor(context, R.color.progress_pale_fill)
    private val shell = ActivityShellView(context, onExit)

    var presentation: JapaneseCountingTestPresentation =
        JapaneseCountingTestPresentation.fromSession(CountTrainingSession())
        private set

    init {
        addView(shell, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        shell.setHeader("Japanese Counting", pathPosition)
        shell.setContext("TEST", "JAPANESE")
        render(CountTrainingSession())
    }

    fun render(session: CountTrainingSession) {
        presentation = JapaneseCountingTestPresentation.fromSession(session)
        shell.setHeader("Japanese Counting", pathPosition)
        when (presentation.shellState) {
            ActivityShellState.READY -> renderReady()
            ActivityShellState.ACTIVE -> renderActive()
            ActivityShellState.RESULT -> renderResult()
            ActivityShellState.ERROR -> renderError()
            ActivityShellState.COMPLETE -> error("The test completes from its successful result.")
        }
    }

    private fun renderReady() {
        shell.setHeading(
            "Count from 1 to 10",
            "Say the full Japanese count without prompts. The app will listen and check the order.",
        )
        shell.setRunnerContent(introCard())
        shell.setProgressContent(null)
        shell.setActions(
            secondary = ActivityShellAction("Play example", onClick = onPlayExample),
            primary = ActivityShellAction("Start test  →", onClick = onStartListening),
        )
    }

    private fun renderActive() {
        val session = presentation.session
        shell.setHeading(
            "Count from 1 to 10",
            if (presentation.isFinalizing) "Checking your count…" else "The microphone is listening.",
        )
        shell.setRunnerContent(listeningCard(session))
        shell.setProgressContent(countProgress(presentation.recognizedCount))
        shell.setActions(
            secondary = null,
            primary = ActivityShellAction(
                label = if (presentation.isFinalizing) "Checking…" else "Stop listening",
                enabled = presentation.isListening,
                onClick = onStopListening,
            ),
        )
    }

    private fun renderResult() {
        val session = presentation.session
        if (presentation.isSuccessful) {
            shell.setHeading("Great job!", "The complete Japanese count was recognized in the correct order.")
            shell.setRunnerContent(resultContent(session, successful = true))
            shell.setProgressContent(countProgress(session.expectedSequence.size))
            shell.setActions(
                secondary = ActivityShellAction("Count again", onClick = onTryAgain),
                primary = ActivityShellAction("Finish  →", onClick = onFinish),
            )
        } else {
            shell.setHeading("Almost there", "One or more numbers were missing or out of order.")
            shell.setRunnerContent(resultContent(session, successful = false))
            shell.setProgressContent(null)
            shell.setActions(
                secondary = ActivityShellAction("Play example", onClick = onPlayExample),
                primary = ActivityShellAction("Try again  →", onClick = onTryAgain),
            )
        }
    }

    private fun renderError() {
        shell.setHeading("We couldn’t check that count", "${presentation.session.errorMessage()} You can try again.")
        shell.setRunnerContent(card().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18.dp(), 18.dp(), 18.dp(), 18.dp())
            addView(microphoneBadge())
            addView(label("No result was saved", 19f, Typeface.BOLD, Gravity.CENTER).apply {
                setPadding(0, 14.dp(), 0, 5.dp())
            })
            addView(label(
                "Check that microphone access is available, then count clearly from 1 to 10.",
                15f,
                gravity = Gravity.CENTER,
            ))
        })
        shell.setProgressContent(null)
        shell.setActions(
            secondary = ActivityShellAction("Play example", onClick = onPlayExample),
            primary = ActivityShellAction("Try again  →", onClick = onTryAgain),
        )
    }

    private fun introCard() = card().apply {
        orientation = LinearLayout.VERTICAL
        setPadding(18.dp(), 18.dp(), 18.dp(), 18.dp())
        addView(microphoneBadge())
        addView(label("How the test works", 19f, Typeface.BOLD, Gravity.CENTER).apply {
            setTextColor(red)
            setPadding(0, 14.dp(), 0, 10.dp())
        })
        listOf(
            "Play the full example again if you need it.",
            "Press Start test, then count from 1 to 10 in Japanese.",
            "Brief pauses are fine; the app checks the complete order.",
            "The microphone stops automatically after ten recognized numbers.",
        ).forEach { copy ->
            addView(label("•  $copy", 15f).apply { setPadding(0, 5.dp(), 0, 5.dp()) })
        }
        addView(label("Microphone required  ·  Camera not used", 13f, Typeface.BOLD, Gravity.CENTER).apply {
            setTextColor(muted)
            setPadding(0, 15.dp(), 0, 0)
        })
    }

    private fun listeningCard(session: CountTrainingSession) = card().apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        minimumHeight = 330.dp()
        setPadding(18.dp(), 28.dp(), 18.dp(), 24.dp())
        addView(microphoneBadge(active = presentation.isListening))
        addView(label(
            if (presentation.isFinalizing) "Checking your count" else "Listening…",
            25f,
            Typeface.BOLD,
            Gravity.CENTER,
        ).apply { setPadding(0, 16.dp(), 0, 8.dp()) })
        val transcript = session.partialTranscripts.lastOrNull().orEmpty()
        addView(label(
            transcript.ifBlank { "Start with ichi and continue through jū." },
            16f,
            gravity = Gravity.CENTER,
        ).apply { setTextColor(if (transcript.isBlank()) muted else ink) })
        addView(label(
            "${presentation.recognizedCount} of ${session.expectedSequence.size} heard",
            14f,
            Typeface.BOLD,
            Gravity.CENTER,
        ).apply {
            setTextColor(red)
            setPadding(0, 18.dp(), 0, 0)
        })
    }

    private fun resultContent(session: CountTrainingSession, successful: Boolean) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(card().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18.dp(), 18.dp(), 18.dp(), 18.dp())
            addView(label(
                if (successful) "All ten numbers were heard" else "Your result",
                19f,
                Typeface.BOLD,
            ).apply { setTextColor(red) })
            session.selectedTranscript?.takeIf(String::isNotBlank)?.let { transcript ->
                addView(label("Heard: $transcript", 14f).apply {
                    setTextColor(muted)
                    setPadding(0, 6.dp(), 0, 12.dp())
                })
            }
            session.countResults.forEach { addView(resultRow(it)) }
        })
    }

    private fun resultRow(result: CountResult) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 7.dp(), 0, 7.dp())
        val lessonItem = JapaneseCountLesson.items.getOrNull(result.expectedPosition - 1)
        addView(label(result.expectedNumber, 16f, Typeface.BOLD), LinearLayout.LayoutParams(34.dp(), -2))
        addView(label(lessonItem?.standardJapanese.orEmpty(), 15f), LinearLayout.LayoutParams(0, -2, 1f))
        val statusText = when (result.status) {
            CountStatus.CORRECT -> "Correct"
            CountStatus.INCORRECT -> "Try again"
            CountStatus.MISSING -> "Missing"
        }
        addView(label(statusText, 14f, Typeface.BOLD).apply {
            setTextColor(if (result.status == CountStatus.CORRECT) red else muted)
        })
    }

    private fun countProgress(completedCount: Int) = countingNumberProgress(
        context, completedCount, presentation.highlightedIndex,
    )

    private fun microphoneBadge(active: Boolean = false) = FrameLayout(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (active) red else paleRed)
        }
        addView(AppIconView(context, AppIcon.MICROPHONE, 38).apply {
            setIconColor(if (active) Color.WHITE else red)
        }, FrameLayout.LayoutParams(38.dp(), 38.dp(), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(78.dp(), 78.dp()).apply { gravity = Gravity.CENTER_HORIZONTAL }
    }

    private fun CountTrainingSession.errorMessage(): String = when (error?.name) {
        "MICROPHONE_PERMISSION_DENIED" -> "Microphone permission is required for this test."
        "RECOGNITION_SERVICE_UNAVAILABLE" -> "Speech recognition is unavailable on this device."
        "LANGUAGE_NOT_SUPPORTED", "LANGUAGE_UNAVAILABLE" -> "Japanese speech recognition is unavailable."
        "NETWORK" -> "Speech recognition needs a working network connection."
        else -> "No complete count was detected."
    }

    private fun card() = LinearLayout(context).apply {
        background = GradientDrawable().apply {
            setColor(warmSurface)
            cornerRadius = 24.dp().toFloat()
            setStroke(1.dp(), border)
        }
        elevation = 0f
    }

    private fun label(text: String, size: Float, style: Int = Typeface.NORMAL, gravity: Int = Gravity.START) =
        TextView(context).apply {
            this.text = text
            textSize = size
            setTextColor(ink)
            typeface = Typeface.create("sans-serif", style)
            this.gravity = gravity
            setLineSpacing(2.dp().toFloat(), 1f)
        }

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()
}
