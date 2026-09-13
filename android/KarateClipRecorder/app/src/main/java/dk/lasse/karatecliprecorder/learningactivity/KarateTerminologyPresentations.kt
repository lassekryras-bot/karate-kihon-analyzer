package dk.lasse.karatecliprecorder.learningactivity

import androidx.annotation.StringRes
import dk.lasse.karatecliprecorder.R
import dk.lasse.karatecliprecorder.learning.ShortVoiceCommand
import dk.lasse.karatecliprecorder.learning.ShortVoiceCommandMatcher
import dk.lasse.karatecliprecorder.learning.SpeechRecognitionError

enum class OsuMeaningUseStage { READY, HEARD_YOU, READY_RESPONSE, KEEP_TRYING, COMPLETE }

enum class OsuIllustration {
    SENSEI_SPEAKING,
    HEARD_YOU,
    PIZZA_DISTRACTION,
    READY_GUARD,
    SLEEP_DISTRACTION,
    ONE_MORE,
    HOME_DISTRACTION,
}

data class OsuMeaningQuestion(
    val stage: OsuMeaningUseStage,
    @StringRes val senseiSpeechRes: Int,
    @StringRes val questionRes: Int,
    val correctIllustration: OsuIllustration,
    @StringRes val correctLabelRes: Int,
    val distractionIllustration: OsuIllustration,
    @StringRes val distractionLabelRes: Int,
    @StringRes val correctFeedbackRes: Int,
    @StringRes val wrongFeedbackRes: Int,
)

object OsuMeaningLesson {
    val questions = listOf(
        OsuMeaningQuestion(
            stage = OsuMeaningUseStage.HEARD_YOU,
            senseiSpeechRes = R.string.osu_heard_sensei_speech,
            questionRes = R.string.osu_heard_question,
            correctIllustration = OsuIllustration.HEARD_YOU,
            correctLabelRes = R.string.osu_heard_answer,
            distractionIllustration = OsuIllustration.PIZZA_DISTRACTION,
            distractionLabelRes = R.string.osu_pizza_answer,
            correctFeedbackRes = R.string.osu_heard_correct_feedback,
            wrongFeedbackRes = R.string.osu_heard_wrong_feedback,
        ),
        OsuMeaningQuestion(
            stage = OsuMeaningUseStage.READY_RESPONSE,
            senseiSpeechRes = R.string.osu_ready_sensei_speech,
            questionRes = R.string.osu_ready_question,
            correctIllustration = OsuIllustration.READY_GUARD,
            correctLabelRes = R.string.osu_ready_answer,
            distractionIllustration = OsuIllustration.SLEEP_DISTRACTION,
            distractionLabelRes = R.string.osu_sleep_answer,
            correctFeedbackRes = R.string.osu_ready_correct_feedback,
            wrongFeedbackRes = R.string.osu_ready_wrong_feedback,
        ),
        OsuMeaningQuestion(
            stage = OsuMeaningUseStage.KEEP_TRYING,
            senseiSpeechRes = R.string.osu_trying_sensei_speech,
            questionRes = R.string.osu_trying_question,
            correctIllustration = OsuIllustration.ONE_MORE,
            correctLabelRes = R.string.osu_trying_answer,
            distractionIllustration = OsuIllustration.HOME_DISTRACTION,
            distractionLabelRes = R.string.osu_home_answer,
            correctFeedbackRes = R.string.osu_trying_correct_feedback,
            wrongFeedbackRes = R.string.osu_trying_wrong_feedback,
        ),
    )

    fun questionFor(stage: OsuMeaningUseStage): OsuMeaningQuestion? =
        questions.firstOrNull { it.stage == stage }
}

data class OsuMeaningUsePresentation(
    val stage: OsuMeaningUseStage = OsuMeaningUseStage.READY,
    val selections: Map<OsuMeaningUseStage, OsuIllustration> = emptyMap(),
) {
    val shellState: ActivityShellState = when (stage) {
        OsuMeaningUseStage.READY -> ActivityShellState.READY
        OsuMeaningUseStage.COMPLETE -> ActivityShellState.COMPLETE
        else -> ActivityShellState.ACTIVE
    }
    val question: OsuMeaningQuestion? = OsuMeaningLesson.questionFor(stage)
    val stepIndex: Int? = OsuMeaningLesson.questions.indexOfFirst { it.stage == stage }
        .takeIf { it >= 0 }
    val selectedIllustration: OsuIllustration? = selections[stage]
    val answerIsCorrect: Boolean = question != null && question.correctIllustration == selectedIllustration
    val hasSelection: Boolean = selectedIllustration != null
    val previousEnabled: Boolean = (stepIndex ?: 0) > 0
    val nextEnabled: Boolean = answerIsCorrect
    val isLastQuestion: Boolean = stepIndex == OsuMeaningLesson.questions.lastIndex
    val cameraRequired = false
    val microphoneRequired = false
}

class OsuMeaningUseController(
    private val onChanged: (OsuMeaningUsePresentation) -> Unit,
) {
    var presentation = OsuMeaningUsePresentation()
        private set

    fun start() = update(OsuMeaningUsePresentation(OsuMeaningLesson.questions.first().stage))

    fun selectAnswer(illustration: OsuIllustration) {
        val question = presentation.question ?: return
        if (illustration !in setOf(question.correctIllustration, question.distractionIllustration)) return
        update(presentation.copy(
            selections = presentation.selections + (presentation.stage to illustration),
        ))
    }

    fun next() {
        if (!presentation.nextEnabled) return
        val currentIndex = requireNotNull(presentation.stepIndex)
        val nextStage = OsuMeaningLesson.questions.getOrNull(currentIndex + 1)?.stage
            ?: OsuMeaningUseStage.COMPLETE
        update(presentation.copy(stage = nextStage))
    }

    fun previous() {
        val previousStage = presentation.stepIndex
            ?.minus(1)
            ?.let(OsuMeaningLesson.questions::getOrNull)
            ?.stage
            ?: return
        update(presentation.copy(stage = previousStage))
    }

    fun restart() = update(OsuMeaningUsePresentation(OsuMeaningLesson.questions.first().stage))

    fun reset() = update(OsuMeaningUsePresentation())

    private fun update(next: OsuMeaningUsePresentation) {
        presentation = next
        onChanged(next)
    }
}

enum class ReadyOsuPhase {
    READY,
    MODEL,
    PREPARING_CAMERA,
    PROMPTING,
    LISTENING,
    CHECKING,
    CAPTURING,
    RESULT,
    ERROR,
    COMPLETE,
}

enum class ReadyOsuCameraError {
    CAMERA_PERMISSION_DENIED,
    FRONT_CAMERA_UNAVAILABLE,
    CAPTURE_FAILED,
}

data class ReadyOsuState(
    val phase: ReadyOsuPhase = ReadyOsuPhase.READY,
    val attempts: Int = 0,
    val voiceVerified: Boolean = false,
    val error: SpeechRecognitionError? = null,
    val cameraError: ReadyOsuCameraError? = null,
    val selfieCaptured: Boolean = false,
    val interrupted: Boolean = false,
)

data class ReadyOsuPresentation(val state: ReadyOsuState = ReadyOsuState()) {
    val shellState: ActivityShellState = when (state.phase) {
        ReadyOsuPhase.READY -> ActivityShellState.READY
        ReadyOsuPhase.RESULT -> ActivityShellState.RESULT
        ReadyOsuPhase.ERROR -> ActivityShellState.ERROR
        ReadyOsuPhase.COMPLETE -> ActivityShellState.COMPLETE
        else -> ActivityShellState.ACTIVE
    }
    val microphoneActive = state.phase == ReadyOsuPhase.LISTENING
    val promptPlaying = state.phase == ReadyOsuPhase.PROMPTING
    val checking = state.phase == ReadyOsuPhase.CHECKING
    val cameraActive = state.phase in setOf(
        ReadyOsuPhase.PREPARING_CAMERA,
        ReadyOsuPhase.PROMPTING,
        ReadyOsuPhase.LISTENING,
        ReadyOsuPhase.CHECKING,
        ReadyOsuPhase.CAPTURING,
    )
    val canFinish = state.phase == ReadyOsuPhase.RESULT
    val cameraRequired = true
}

class ReadyOsuController(private val onChanged: (ReadyOsuPresentation) -> Unit) {
    var state = ReadyOsuState()
        private set

    fun start() = update(state.copy(phase = ReadyOsuPhase.MODEL, error = null, interrupted = false))

    fun beginCameraPreparation() = update(ReadyOsuState(
        phase = ReadyOsuPhase.PREPARING_CAMERA,
        attempts = state.attempts,
    ))

    fun beginPrompt() = update(state.copy(phase = ReadyOsuPhase.PROMPTING, error = null, interrupted = false))

    fun beginListening() = update(state.copy(phase = ReadyOsuPhase.LISTENING))

    fun beginChecking() = update(state.copy(phase = ReadyOsuPhase.CHECKING))

    fun handleTranscripts(transcripts: List<String>) {
        val confirmed = ShortVoiceCommandMatcher.matches(ShortVoiceCommand.OSU, transcripts)
        update(state.copy(
            phase = if (confirmed) ReadyOsuPhase.CAPTURING else ReadyOsuPhase.RESULT,
            attempts = state.attempts + 1,
            voiceVerified = state.voiceVerified || confirmed,
            error = null,
            cameraError = null,
            selfieCaptured = false,
        ))
    }

    fun stopListening() = update(state.copy(
        phase = ReadyOsuPhase.RESULT,
        attempts = state.attempts + 1,
        error = null,
        cameraError = null,
        selfieCaptured = false,
    ))

    fun fail(error: SpeechRecognitionError, interrupted: Boolean = false) = update(state.copy(
        phase = ReadyOsuPhase.ERROR,
        error = error,
        cameraError = null,
        interrupted = interrupted,
    ))

    fun failCamera(error: ReadyOsuCameraError, interrupted: Boolean = false) = update(state.copy(
        phase = ReadyOsuPhase.ERROR,
        error = null,
        cameraError = error,
        interrupted = interrupted,
    ))

    fun permissionFailure(cameraAllowed: Boolean, microphoneAllowed: Boolean) = update(state.copy(
        phase = ReadyOsuPhase.ERROR,
        error = if (microphoneAllowed) null else SpeechRecognitionError.MICROPHONE_PERMISSION_DENIED,
        cameraError = if (cameraAllowed) null else ReadyOsuCameraError.CAMERA_PERMISSION_DENIED,
        interrupted = false,
    ))

    fun selfieCaptured() {
        if (state.phase == ReadyOsuPhase.CAPTURING) {
            update(state.copy(phase = ReadyOsuPhase.RESULT, selfieCaptured = true, cameraError = null))
        }
    }

    fun continueToResultWithoutSelfie() = update(state.copy(
        phase = ReadyOsuPhase.RESULT,
        error = null,
        cameraError = null,
        selfieCaptured = false,
        interrupted = false,
    ))

    fun finish() {
        if (ReadyOsuPresentation(state).canFinish) {
            update(state.copy(phase = ReadyOsuPhase.COMPLETE, error = null, interrupted = false))
        }
    }

    fun restart() = update(ReadyOsuState())

    fun returnToModel() = update(state.copy(
        phase = ReadyOsuPhase.MODEL,
        voiceVerified = false,
        error = null,
        cameraError = null,
        selfieCaptured = false,
        interrupted = false,
    ))

    private fun update(next: ReadyOsuState) {
        state = next
        onChanged(ReadyOsuPresentation(next))
    }
}

enum class StopCountPhase { READY, COUNTING, COUNT_FINISHED, STOPPED, ERROR, COMPLETE }

enum class StopCountMethod { VOICE, BUTTON }

data class StopCountState(
    val phase: StopCountPhase = StopCountPhase.READY,
    val currentNumberIndex: Int? = null,
    val voiceEnabled: Boolean = false,
    val stopMethod: StopCountMethod? = null,
    val voiceError: SpeechRecognitionError? = null,
    val interrupted: Boolean = false,
)

data class StopCountPresentation(val state: StopCountState = StopCountState()) {
    val shellState: ActivityShellState = when (state.phase) {
        StopCountPhase.READY -> ActivityShellState.READY
        StopCountPhase.ERROR -> ActivityShellState.ERROR
        StopCountPhase.COMPLETE -> ActivityShellState.COMPLETE
        else -> ActivityShellState.ACTIVE
    }
    val currentNumber = state.currentNumberIndex?.plus(1)
    val isCounting = state.phase == StopCountPhase.COUNTING
    val voiceVerified = state.stopMethod == StopCountMethod.VOICE
    val cameraRequired = false
}

class StopCountController(private val onChanged: (StopCountPresentation) -> Unit) {
    var state = StopCountState()
        private set

    fun start(voiceEnabled: Boolean) = update(StopCountState(
        phase = StopCountPhase.COUNTING,
        currentNumberIndex = 0,
        voiceEnabled = voiceEnabled,
    ))

    fun showNumber(index: Int) {
        if (state.phase == StopCountPhase.COUNTING && index in 0..9) {
            update(state.copy(currentNumberIndex = index))
        }
    }

    fun countFinished() {
        if (state.phase == StopCountPhase.COUNTING) {
            update(state.copy(phase = StopCountPhase.COUNT_FINISHED, currentNumberIndex = 9))
        }
    }

    fun stop(method: StopCountMethod) {
        if (state.phase == StopCountPhase.COUNTING) {
            update(state.copy(phase = StopCountPhase.STOPPED, stopMethod = method, voiceError = null))
        }
    }

    fun permissionError(error: SpeechRecognitionError) = update(StopCountState(
        phase = StopCountPhase.ERROR,
        voiceError = error,
    ))

    fun continueButtonOnlyAfterError() = start(voiceEnabled = false)

    fun voiceUnavailableDuringCount(error: SpeechRecognitionError) {
        if (state.phase == StopCountPhase.COUNTING) {
            update(state.copy(voiceEnabled = false, voiceError = error))
        }
    }

    fun interrupt() {
        if (state.phase == StopCountPhase.COUNTING) {
            update(StopCountState(
                phase = StopCountPhase.ERROR,
                voiceError = SpeechRecognitionError.CLIENT,
                interrupted = true,
            ))
        }
    }

    fun finish() {
        if (state.phase == StopCountPhase.STOPPED) {
            update(state.copy(phase = StopCountPhase.COMPLETE))
        }
    }

    fun restart() = update(StopCountState())

    private fun update(next: StopCountState) {
        state = next
        onChanged(StopCountPresentation(next))
    }
}
