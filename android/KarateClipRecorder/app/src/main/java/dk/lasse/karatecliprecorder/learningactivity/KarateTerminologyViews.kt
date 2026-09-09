package dk.lasse.karatecliprecorder.learningactivity

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import dk.lasse.karatecliprecorder.AppIcon
import dk.lasse.karatecliprecorder.AppIconView
import dk.lasse.karatecliprecorder.R
import dk.lasse.karatecliprecorder.learning.JapaneseCountLesson
import dk.lasse.karatecliprecorder.learning.SpeechRecognitionError

@SuppressLint("ViewConstructor")
class OsuMeaningUseView(
    context: Context,
    onExit: () -> Unit,
    private val pathPosition: String,
    private val onStart: () -> Unit,
    private val onPrevious: () -> Unit,
    private val onNext: () -> Unit,
    private val onReplayOsu: () -> Unit,
    private val onRestart: () -> Unit,
    private val onContinue: () -> Unit,
) : FrameLayout(context) {
    private val ui = TerminologyActivityUi(context)
    private val shell = ActivityShellView(context, onExit)
    var presentation = OsuMeaningUsePresentation()
        private set

    init {
        addView(shell, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        render(presentation)
    }

    fun render(next: OsuMeaningUsePresentation) {
        presentation = next
        shell.setHeader(GROUP_TITLE, pathPosition)
        shell.setContext("KARATE BASICS", "LEARN")
        when (next.stage) {
            OsuMeaningUseStage.READY -> renderReady()
            OsuMeaningUseStage.MEANING -> renderMeaning()
            OsuMeaningUseStage.DOJO_USE -> renderDojoUse()
            OsuMeaningUseStage.APP_USE -> renderAppUse()
            OsuMeaningUseStage.COMPLETE -> renderComplete()
        }
    }

    private fun renderReady() {
        shell.setHeading("Osu — Meaning & Use", "Learn what Osu communicates and how this app uses it.")
        shell.setRunnerContent(ui.card(
            title = "A short word with context",
            paragraphs = listOf(
                "Osu is used as a brief acknowledgement in some karate dojos.",
                "Its meaning and use vary between styles and dojos. This lesson explains the specific way it is used in this app.",
            ),
            footer = "No microphone or camera",
        ))
        shell.setProgressContent(null)
        shell.setActions(null, ActivityShellAction("Start lesson  →", onClick = onStart))
    }

    private fun renderMeaning() {
        renderLessonStep(
            subtitle = "First, learn what the word communicates.",
            title = "What does Osu mean?",
            paragraphs = listOf(
                "Osu does not have one exact English translation for every situation.",
                "In a dojo it may communicate acknowledgement, attention, or readiness.",
            ),
            showReplay = true,
        )
    }

    private fun renderDojoUse() {
        renderLessonStep(
            subtitle = "Usage depends on the dojo and situation.",
            title = "Follow your dojo",
            paragraphs = listOf(
                "Some karate styles use Osu often, while others use it differently or not at all.",
                "When training with an instructor, follow the conventions they teach.",
            ),
        )
    }

    private fun renderAppUse() {
        renderLessonStep(
            subtitle = "In this app, Osu confirms that you are ready.",
            title = "Ready? — Osu",
            paragraphs = listOf(
                "When the app asks “Ready?”, answer “Osu” to mean:",
                "“I heard the instruction, and I am ready to begin.”",
                "You will practise that exchange in the next activity.",
            ),
            showReplay = true,
        )
    }

    private fun renderLessonStep(
        subtitle: String,
        title: String,
        paragraphs: List<String>,
        showReplay: Boolean = false,
    ) {
        shell.setHeading("Osu — Meaning & Use", subtitle)
        shell.setRunnerContent(ui.card(title, paragraphs).apply {
            if (showReplay) addView(ui.inlineAction("Hear Osu", AppIcon.VOLUME, onReplayOsu))
        })
        shell.setProgressContent(ui.stepProgress(requireNotNull(presentation.stepIndex), 3, "Lesson step"))
        shell.setActions(
            secondary = if (presentation.previousEnabled) ActivityShellAction("Previous", onClick = onPrevious) else null,
            primary = ActivityShellAction(presentation.nextLabel, onClick = onNext),
        )
    }

    private fun renderComplete() {
        shell.setHeading("Lesson complete", "You learned how Osu is used in this app.")
        shell.setRunnerContent(ui.card(
            title = "What comes next",
            paragraphs = listOf(
                "Next, practise responding after the app asks “Ready?”.",
                "Completing this lesson records learning progress, not pronunciation or voice verification.",
            ),
        ))
        shell.setProgressContent(null)
        shell.setActions(
            secondary = ActivityShellAction("Review lesson", onClick = onRestart),
            primary = ActivityShellAction("Practise responding  →", onClick = onContinue),
        )
    }
}

@SuppressLint("ViewConstructor")
class ReadyOsuView(
    context: Context,
    onExit: () -> Unit,
    private val pathPosition: String,
    private val onStart: () -> Unit,
    private val onReplayModel: () -> Unit,
    private val onTryResponding: () -> Unit,
    private val onCancelPrompt: () -> Unit,
    private val onStopListening: () -> Unit,
    private val onTryAgain: () -> Unit,
    private val onContinueWithoutVoice: () -> Unit,
    private val onFinish: () -> Unit,
    private val onPracticeAgain: () -> Unit,
    private val onContinue: () -> Unit,
) : FrameLayout(context) {
    private val ui = TerminologyActivityUi(context)
    private val shell = ActivityShellView(context, onExit)
    val cameraPreview = PreviewView(context).apply {
        scaleType = PreviewView.ScaleType.FILL_CENTER
        contentDescription = "Front camera preview for the hands-free selfie"
    }
    private var selfieBitmap: Bitmap? = null
    private var liveCameraCard: LinearLayout? = null
    var presentation = ReadyOsuPresentation()
        private set

    init {
        addView(shell, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        render(presentation)
    }

    fun render(next: ReadyOsuPresentation) {
        val previousPhase = presentation.state.phase
        presentation = next
        shell.setHeader(GROUP_TITLE, pathPosition)
        shell.setContext("KARATE BASICS", "PRACTICE")
        when (next.state.phase) {
            ReadyOsuPhase.READY -> renderReady()
            ReadyOsuPhase.MODEL -> renderModel()
            ReadyOsuPhase.PREPARING_CAMERA -> renderCameraStage(
                subtitle = "Starting the front camera…",
                status = "Preparing camera",
                body = "Keep the phone steady and place your face in the preview.",
            )
            ReadyOsuPhase.PROMPTING -> renderPrompting()
            ReadyOsuPhase.LISTENING -> renderListening()
            ReadyOsuPhase.CHECKING -> renderChecking()
            ReadyOsuPhase.CAPTURING -> renderCameraStage(
                subtitle = "Osu recognized — taking your photo…",
                status = "Taking selfie",
                body = "No tap is needed. Hold the phone steady for a moment.",
            )
            ReadyOsuPhase.RESULT -> renderResult()
            ReadyOsuPhase.ERROR -> renderError()
            ReadyOsuPhase.COMPLETE -> renderComplete()
        }
        if (previousPhase != next.state.phase && next.state.phase in ANNOUNCED_READY_OSU_PHASES) {
            announceForAccessibility(readyOsuStatus(next))
        }
    }

    private fun renderReady() {
        shell.setHeading("Ready? — Osu", "Practise responding when the app asks if you are ready.")
        shell.setRunnerContent(ui.card(
            title = "How the practice works",
            paragraphs = listOf(
                "First, hear the complete exchange. Then the app says “Ready?” and waits for your response.",
                "When you start the hands-free attempt, the front camera and microphone turn on. Saying “Osu” takes a selfie without another tap.",
                "The photo appears only on the result page and is discarded when you retry or leave. Android speech recognition may use an online service.",
                "A recognition match triggers the camera. It does not assess pronunciation or karate skill.",
            ),
            footer = "Front camera + microphone start only after your action",
        ))
        shell.setProgressContent(null)
        shell.setActions(null, ActivityShellAction("Start practice  →", onClick = onStart))
    }

    private fun renderModel() {
        shell.setHeading("Ready? — Osu", "Hear the exchange before trying it yourself.")
        shell.setRunnerContent(ui.exchangeCard(
            status = "Example",
            prompt = "Ready?",
            response = "Osu",
        ).apply { addView(ui.inlineAction("Play example", AppIcon.VOLUME, onReplayModel)) })
        shell.setProgressContent(ui.stepProgress(0, 2, "Practice step"))
        shell.setActions(null, ActivityShellAction("Start hands-free selfie  →", onClick = onTryResponding))
    }

    private fun renderPrompting() {
        renderCameraStage(
            subtitle = "Listen for the question.",
            status = "Playing prompt",
            body = "Ready? Your turn comes next.",
            onStop = onCancelPrompt,
        )
    }

    private fun renderListening() {
        renderCameraStage(
            subtitle = "Your turn — say “Osu”.",
            status = "Listening…",
            body = "Say Osu now. A recognized response takes the selfie automatically.",
            onStop = onStopListening,
        )
    }

    private fun renderChecking() {
        renderCameraStage(
            subtitle = "Checking what the app heard…",
            status = "Checking response",
            body = "The camera will trigger only if the intended Osu response is recognized.",
            onStop = onContinueWithoutVoice,
        )
    }

    private fun renderResult() {
        val verified = presentation.state.voiceVerified
        val captured = presentation.state.selfieCaptured && selfieBitmap != null
        shell.setHeading("Your hands-free result", when {
            captured -> "You said Osu, and the app took the selfie."
            verified -> "The app recognized Osu, but no selfie was available."
            else -> "The app did not confirm Osu, so no selfie was taken."
        })
        shell.setRunnerContent(if (captured) {
            ui.selfieResultCard(requireNotNull(selfieBitmap))
        } else {
            ui.card(
                title = if (verified) "Voice response confirmed" else "No hands-free photo",
                paragraphs = listOf(
                    if (verified) "The intended response was recognized." else "You can try again or finish without voice verification.",
                    "This result is not a pronunciation score or a karate-skill assessment.",
                ),
            )
        })
        shell.setProgressContent(ui.stepProgress(1, 2, "Practice step"))
        shell.setActions(
            secondary = ActivityShellAction("Try again", onClick = onTryAgain),
            primary = ActivityShellAction("Finish practice  →", onClick = onFinish),
        )
    }

    private fun renderError() {
        val message = if (presentation.state.interrupted) {
            "The response was interrupted. Restart the response when you are ready."
        } else {
            readyOsuErrorCopy(presentation)
        }
        shell.setHeading("Selfie practice paused", message)
        shell.setRunnerContent(ui.card(
            title = "No selfie was saved",
            paragraphs = listOf(
                "You can try the front camera and microphone again, or continue without a photo.",
                "Any recognized voice response remains separate from whether the camera captured an image.",
            ),
        ))
        shell.setProgressContent(null)
        shell.setActions(
            secondary = ActivityShellAction("Continue without photo", onClick = onContinueWithoutVoice),
            primary = ActivityShellAction("Try selfie again  →", onClick = onTryAgain),
        )
    }

    private fun renderComplete() {
        val verified = presentation.state.voiceVerified
        shell.setHeading("Practice complete", "You practised the Ready? — Osu exchange.")
        shell.setRunnerContent(ui.card(
            title = if (verified) "Voice response confirmed" else "Completed without voice confirmation",
            paragraphs = listOf(
                if (verified) "The app recognized the intended Osu response." else "No voice-verification claim was recorded.",
                if (presentation.state.selfieCaptured) "A selfie was captured for the result page." else "No successful selfie capture was recorded.",
                "This activity does not assess pronunciation or karate skill.",
            ),
        ))
        shell.setProgressContent(null)
        shell.setActions(
            secondary = ActivityShellAction("Practice again", onClick = onPracticeAgain),
            primary = ActivityShellAction("Practise stopping  →", onClick = onContinue),
        )
    }

    fun setSelfie(bitmap: Bitmap) {
        selfieBitmap = bitmap
    }

    fun clearSelfie() {
        selfieBitmap = null
    }

    private fun renderCameraStage(
        subtitle: String,
        status: String,
        body: String,
        onStop: () -> Unit = onContinueWithoutVoice,
    ) {
        shell.setHeading("Ready? — Osu", subtitle)
        val card = liveCameraCard ?: ui.cameraCard(status, body, cameraPreview).also {
            liveCameraCard = it
        }
        (card.getChildAt(0) as TextView).text = status
        (card.getChildAt(1) as TextView).text = body
        // Keep the live CameraX surface attached between prompt, recognition and capture.
        if (card.parent == null) shell.setRunnerContent(card)
        shell.setProgressContent(ui.stepProgress(1, 2, "Practice step"))
        shell.setActions(null, ActivityShellAction("Stop selfie practice", onClick = onStop))
    }
}

@SuppressLint("ViewConstructor")
class StopCountView(
    context: Context,
    onExit: () -> Unit,
    private val pathPosition: String,
    private val onStartVoice: () -> Unit,
    private val onStartButtonOnly: () -> Unit,
    private val onStopSession: () -> Unit,
    private val onTryVoiceAgain: () -> Unit,
    private val onFinish: () -> Unit,
    private val onPracticeAgain: () -> Unit,
    private val onContinue: () -> Unit,
) : FrameLayout(context) {
    private val ui = TerminologyActivityUi(context)
    private val shell = ActivityShellView(context, onExit)
    var presentation = StopCountPresentation()
        private set

    init {
        addView(shell, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        render(presentation)
    }

    fun render(next: StopCountPresentation) {
        val previousPhase = presentation.state.phase
        presentation = next
        shell.setHeader(GROUP_TITLE, pathPosition)
        shell.setContext("KARATE BASICS", "PRACTICE")
        when (next.state.phase) {
            StopCountPhase.READY -> renderReady()
            StopCountPhase.COUNTING -> renderCounting()
            StopCountPhase.COUNT_FINISHED -> renderCountFinished()
            StopCountPhase.STOPPED -> renderStopped()
            StopCountPhase.ERROR -> renderError()
            StopCountPhase.COMPLETE -> renderComplete()
        }
        if (previousPhase != next.state.phase) {
            announceForAccessibility(stopCountStatus(next))
        }
    }

    private fun renderReady() {
        shell.setHeading("Stop the Session", "Stop a Japanese count before it reaches ten.")
        shell.setRunnerContent(ui.card(
            title = "Your safety command",
            paragraphs = listOf(
                "The app will count from one to ten in Japanese. Say “Stop” at any time to interrupt it.",
                "A Stop button always remains available and works even when voice recognition does not.",
                "This is a camera-free simulation. It does not start a training or recording session.",
            ),
            footer = "Microphone optional  ·  Camera not used",
        ))
        shell.setProgressContent(null)
        shell.setActions(
            secondary = ActivityShellAction("Use button only", onClick = onStartButtonOnly),
            primary = ActivityShellAction("Start voice stop practice  →", onClick = onStartVoice),
        )
    }

    private fun renderCounting() {
        val index = presentation.state.currentNumberIndex ?: 0
        val item = JapaneseCountLesson.items[index]
        val voiceUnavailable = presentation.state.voiceError != null
        shell.setHeading(
            "Stop the Session",
            if (presentation.state.voiceEnabled) "Say Stop or tap Stop session." else "Tap Stop session before the count reaches ten.",
        )
        shell.setRunnerContent(ui.countingCard(
            number = item.number,
            kanji = item.displayKanji,
            spoken = item.japanese,
            status = when {
                voiceUnavailable -> "Voice is unavailable. The Stop button still works."
                presentation.state.voiceEnabled -> "Listening for Stop"
                else -> "Button-only practice"
            },
            listening = presentation.state.voiceEnabled,
        ))
        shell.setProgressContent(ui.stepProgress(index, 10, "Japanese count"))
        shell.setActions(null, ActivityShellAction("Stop session", onClick = onStopSession))
    }

    private fun renderCountFinished() {
        shell.setHeading("The count reached ten", "Start again and stop it before ten.")
        shell.setRunnerContent(ui.card(
            title = "Nothing was marked wrong",
            paragraphs = listOf(
                "This practice finishes when you interrupt the count.",
                "Try voice again, or use the reliable Stop button.",
            ),
        ))
        shell.setProgressContent(ui.stepProgress(9, 10, "Japanese count"))
        shell.setActions(
            secondary = ActivityShellAction("Use button only", onClick = onStartButtonOnly),
            primary = ActivityShellAction("Try voice again  →", onClick = onTryVoiceAgain),
        )
    }

    private fun renderStopped() {
        val voice = presentation.state.stopMethod == StopCountMethod.VOICE
        shell.setHeading("Session stopped", if (voice) "The app recognized your Stop command." else "You used the Stop button.")
        shell.setRunnerContent(ui.card(
            title = if (voice) "Voice command confirmed" else "Manual Stop practised",
            paragraphs = listOf(
                "The count and microphone stopped immediately.",
                if (voice) "This confirms the command was recognized; it is not a pronunciation score."
                else "Manual completion does not claim voice verification.",
            ),
        ))
        shell.setProgressContent(null)
        shell.setActions(
            secondary = ActivityShellAction(if (voice) "Try again" else "Try voice", onClick = onTryVoiceAgain),
            primary = ActivityShellAction("Finish practice  →", onClick = onFinish),
        )
    }

    private fun renderError() {
        val message = if (presentation.state.interrupted) {
            "The simulated session was interrupted and safely stopped."
        } else {
            voiceErrorCopy(presentation.state.voiceError)
        }
        shell.setHeading("Voice Stop unavailable", message)
        shell.setRunnerContent(ui.card(
            title = "The Stop button still works",
            paragraphs = listOf(
                "You can practise with the button without granting microphone access.",
                "No camera or recording is used.",
            ),
        ))
        shell.setProgressContent(null)
        shell.setActions(
            secondary = ActivityShellAction("Practice with button", onClick = onStartButtonOnly),
            primary = ActivityShellAction("Try microphone again  →", onClick = onTryVoiceAgain),
        )
    }

    private fun renderComplete() {
        val voice = presentation.state.stopMethod == StopCountMethod.VOICE
        shell.setHeading("Practice complete", "You stopped an active Japanese count.")
        shell.setRunnerContent(ui.card(
            title = if (voice) "Voice Stop confirmed" else "Stop button practised",
            paragraphs = listOf(
                "The Stop button remains the reliable fallback in later hands-free sessions.",
                if (voice) "The app recorded command recognition separately from activity completion."
                else "The activity is complete without making a voice-verification claim.",
            ),
        ))
        shell.setProgressContent(null)
        shell.setActions(
            secondary = ActivityShellAction("Practice again", onClick = onPracticeAgain),
            primary = ActivityShellAction("Continue to counting  →", onClick = onContinue),
        )
    }
}

private class TerminologyActivityUi(private val context: Context) {
    private val red = ContextCompat.getColor(context, R.color.app_accent)
    private val ink = ContextCompat.getColor(context, R.color.app_text_primary)
    private val muted = ContextCompat.getColor(context, R.color.app_text_secondary)
    private val border = ContextCompat.getColor(context, R.color.app_border)
    private val warmSurface = ContextCompat.getColor(context, R.color.home_card_surface)
    private val paleRed = ContextCompat.getColor(context, R.color.progress_pale_fill)

    fun card(title: String, paragraphs: List<String>, footer: String? = null) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(18.dp(), 18.dp(), 18.dp(), 18.dp())
        background = GradientDrawable().apply {
            setColor(warmSurface)
            cornerRadius = 24.dp().toFloat()
            setStroke(1.dp(), border)
        }
        elevation = 0f
        addView(label(title, 19f, Typeface.BOLD).apply { setTextColor(red) })
        paragraphs.forEach { paragraph ->
            addView(label(paragraph, 15f).apply { setPadding(0, 9.dp(), 0, 0) })
        }
        footer?.let {
            addView(label(it, 13f, Typeface.BOLD, Gravity.CENTER).apply {
                setTextColor(muted)
                setPadding(0, 16.dp(), 0, 0)
            })
        }
    }

    fun exchangeCard(status: String, prompt: String, response: String) = card(
        title = status,
        paragraphs = emptyList(),
    ).apply {
        addView(label(prompt, 28f, Typeface.BOLD, Gravity.CENTER).apply { setPadding(0, 22.dp(), 0, 6.dp()) })
        addView(label(response, 22f, Typeface.BOLD, Gravity.CENTER).apply {
            setTextColor(red)
            setPadding(0, 6.dp(), 0, 12.dp())
        })
    }

    fun voiceStatusCard(title: String, body: String, active: Boolean) = card(title, listOf(body)).apply {
        addView(FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (active) red else paleRed)
            }
            addView(AppIconView(context, AppIcon.MICROPHONE, 36).apply {
                setIconColor(if (active) Color.WHITE else red)
            }, FrameLayout.LayoutParams(36.dp(), 36.dp(), Gravity.CENTER))
        }, LinearLayout.LayoutParams(72.dp(), 72.dp()).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = 18.dp()
        })
    }

    fun cameraCard(title: String, body: String, preview: View) = card(title, listOf(body)).apply {
        // Runner stages reuse one live preview; release its previous card first.
        (preview.parent as? ViewGroup)?.removeView(preview)
        addView(preview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 280.dp()).apply {
            topMargin = 16.dp()
        })
    }

    fun selfieResultCard(bitmap: Bitmap) = card(
        title = "Selfie captured",
        paragraphs = emptyList(),
    ).apply {
        addView(ImageView(context).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.CENTER_CROP
            adjustViewBounds = false
            contentDescription = "Selfie captured after the Osu response"
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 320.dp()).apply {
            topMargin = 16.dp()
        })
        addView(label(
            "The hands-free action worked: the recognized Osu response triggered this photo.",
            15f,
        ).apply { setPadding(0, 12.dp(), 0, 0) })
        addView(label(
            "The photo is kept only for this result and is not a pronunciation or skill assessment.",
            15f,
        ).apply { setPadding(0, 9.dp(), 0, 0) })
    }

    fun countingCard(number: String, kanji: String, spoken: String, status: String, listening: Boolean) = card(
        title = status,
        paragraphs = emptyList(),
    ).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        addView(label(number, 22f, Typeface.BOLD, Gravity.CENTER).apply { setPadding(0, 18.dp(), 0, 0) })
        addView(label(kanji, 76f, Typeface.BOLD, Gravity.CENTER).apply { setTextColor(red) })
        addView(label(spoken, 21f, Typeface.BOLD, Gravity.CENTER))
        addView(label(if (listening) "Microphone listening for Stop" else "Microphone off", 13f, Typeface.BOLD, Gravity.CENTER).apply {
            setTextColor(muted)
            setPadding(0, 16.dp(), 0, 4.dp())
        })
    }

    fun inlineAction(text: String, icon: AppIcon, onClick: () -> Unit) = Button(context).apply {
        this.text = text
        textSize = 15f
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        setTextColor(red)
        gravity = Gravity.CENTER
        minimumHeight = 48.dp()
        contentDescription = text
        setPadding(12.dp(), 8.dp(), 12.dp(), 8.dp())
        setCompoundDrawablesRelativeWithIntrinsicBounds(icon.drawableRes, 0, 0, 0)
        compoundDrawableTintList = ColorStateList.valueOf(red)
        compoundDrawablePadding = 8.dp()
        background = GradientDrawable().apply {
            setColor(paleRed)
            cornerRadius = 14.dp().toFloat()
        }
        setOnClickListener { onClick() }
    }

    fun stepProgress(index: Int, total: Int, labelPrefix: String) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        contentDescription = "$labelPrefix ${index + 1} of $total"
        repeat(total) { itemIndex ->
            addView(View(context).apply {
                background = GradientDrawable().apply {
                    cornerRadius = 5.dp().toFloat()
                    setColor(if (itemIndex <= index) red else paleRed)
                }
            }, LinearLayout.LayoutParams(0, 8.dp(), 1f).apply {
                if (itemIndex > 0) marginStart = 5.dp()
            })
        }
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

    private fun Int.dp() = (this * context.resources.displayMetrics.density).toInt()
}

private fun voiceErrorCopy(error: SpeechRecognitionError?): String = when (error) {
    SpeechRecognitionError.MICROPHONE_PERMISSION_DENIED -> "Microphone access was not allowed."
    SpeechRecognitionError.RECOGNITION_SERVICE_UNAVAILABLE -> "Speech recognition is unavailable on this device."
    SpeechRecognitionError.LANGUAGE_NOT_SUPPORTED,
    SpeechRecognitionError.LANGUAGE_UNAVAILABLE,
    -> "The required speech-recognition language is unavailable."
    SpeechRecognitionError.NETWORK -> "Speech recognition could not reach its service."
    SpeechRecognitionError.TIMEOUT,
    SpeechRecognitionError.NO_SPEECH_DETECTED,
    SpeechRecognitionError.EMPTY_TRANSCRIPTION,
    -> "The app did not receive a usable voice response."
    SpeechRecognitionError.BUSY -> "Speech recognition is busy. Wait a moment and try again."
    else -> "Speech recognition could not start."
}

private fun readyOsuErrorCopy(presentation: ReadyOsuPresentation): String {
    val cameraError = presentation.state.cameraError
    val voiceError = presentation.state.error
    return when {
        cameraError == ReadyOsuCameraError.CAMERA_PERMISSION_DENIED &&
            voiceError == SpeechRecognitionError.MICROPHONE_PERMISSION_DENIED ->
            "Front-camera and microphone access were not allowed."
        cameraError == ReadyOsuCameraError.CAMERA_PERMISSION_DENIED ->
            "Front-camera access was not allowed."
        cameraError == ReadyOsuCameraError.FRONT_CAMERA_UNAVAILABLE ->
            "A front camera is not available for this activity."
        cameraError == ReadyOsuCameraError.CAPTURE_FAILED ->
            "The response was processed, but the selfie could not be captured."
        else -> voiceErrorCopy(voiceError)
    }
}

private fun readyOsuStatus(presentation: ReadyOsuPresentation): String = when (presentation.state.phase) {
    ReadyOsuPhase.PREPARING_CAMERA -> "Starting front camera"
    ReadyOsuPhase.PROMPTING -> "Playing Ready prompt"
    ReadyOsuPhase.LISTENING -> "Listening. Say Osu"
    ReadyOsuPhase.CHECKING -> "Checking response"
    ReadyOsuPhase.CAPTURING -> "Osu recognized. Taking selfie"
    ReadyOsuPhase.RESULT -> if (presentation.state.selfieCaptured) "Selfie captured" else "No selfie captured"
    ReadyOsuPhase.ERROR -> "Selfie practice paused"
    ReadyOsuPhase.COMPLETE -> "Practice complete"
    else -> ""
}

private fun stopCountStatus(presentation: StopCountPresentation): String = when (presentation.state.phase) {
    StopCountPhase.COUNTING -> "Japanese count ${presentation.currentNumber}. ${if (presentation.state.voiceEnabled) "Listening for Stop" else "Use the Stop button"}"
    StopCountPhase.COUNT_FINISHED -> "The count reached ten"
    StopCountPhase.STOPPED -> "Session stopped"
    StopCountPhase.ERROR -> "Voice Stop unavailable"
    StopCountPhase.COMPLETE -> "Stop practice complete"
    else -> ""
}

private val ANNOUNCED_READY_OSU_PHASES = setOf(
    ReadyOsuPhase.PROMPTING,
    ReadyOsuPhase.PREPARING_CAMERA,
    ReadyOsuPhase.LISTENING,
    ReadyOsuPhase.CHECKING,
    ReadyOsuPhase.CAPTURING,
    ReadyOsuPhase.RESULT,
    ReadyOsuPhase.ERROR,
    ReadyOsuPhase.COMPLETE,
)

private const val GROUP_TITLE = "Voice Interaction"
