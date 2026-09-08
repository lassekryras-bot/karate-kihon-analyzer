package dk.lasse.karatecliprecorder.learningactivity

import dk.lasse.karatecliprecorder.learning.ShortVoiceCommand
import dk.lasse.karatecliprecorder.learning.ShortVoiceCommandMatcher
import dk.lasse.karatecliprecorder.learning.SpeechRecognitionError

enum class OsuMeaningUseStage { READY, MEANING, DOJO_USE, APP_USE, COMPLETE }

data class OsuMeaningUsePresentation(
    val stage: OsuMeaningUseStage = OsuMeaningUseStage.READY,
) {
    val shellState: ActivityShellState = when (stage) {
        OsuMeaningUseStage.READY -> ActivityShellState.READY
        OsuMeaningUseStage.COMPLETE -> ActivityShellState.COMPLETE
        else -> ActivityShellState.ACTIVE
    }
    val stepIndex: Int? = when (stage) {
        OsuMeaningUseStage.MEANING -> 0
        OsuMeaningUseStage.DOJO_USE -> 1
        OsuMeaningUseStage.APP_USE -> 2
        else -> null
    }
    val previousEnabled: Boolean = (stepIndex ?: 0) > 0
    val nextLabel: String = if (stage == OsuMeaningUseStage.APP_USE) "Finish lesson  →" else "Continue  →"
    val cameraRequired = false
    val microphoneRequired = false
}

class OsuMeaningUseController(
    private val onChanged: (OsuMeaningUsePresentation) -> Unit,
) {
    var presentation = OsuMeaningUsePresentation()
        private set

    fun start() = update(OsuMeaningUsePresentation(OsuMeaningUseStage.MEANING))

    fun next() = update(when (presentation.stage) {
        OsuMeaningUseStage.MEANING -> OsuMeaningUsePresentation(OsuMeaningUseStage.DOJO_USE)
        OsuMeaningUseStage.DOJO_USE -> OsuMeaningUsePresentation(OsuMeaningUseStage.APP_USE)
        OsuMeaningUseStage.APP_USE -> OsuMeaningUsePresentation(OsuMeaningUseStage.COMPLETE)
        else -> presentation
    })

    fun previous() = update(when (presentation.stage) {
        OsuMeaningUseStage.DOJO_USE -> OsuMeaningUsePresentation(OsuMeaningUseStage.MEANING)
        OsuMeaningUseStage.APP_USE -> OsuMeaningUsePresentation(OsuMeaningUseStage.DOJO_USE)
        else -> presentation
    })

    fun restart() = start()

    fun reset() = update(OsuMeaningUsePresentation())

    private fun update(next: OsuMeaningUsePresentation) {
        presentation = next
        onChanged(next)
    }
}

enum class ReadyOsuPhase {
    READY,
    MODEL,
    PROMPTING,
    LISTENING,
    CHECKING,
    FEEDBACK_CONFIRMED,
    FEEDBACK_UNCONFIRMED,
    ERROR,
    COMPLETE,
}

data class ReadyOsuState(
    val phase: ReadyOsuPhase = ReadyOsuPhase.READY,
    val attempts: Int = 0,
    val voiceVerified: Boolean = false,
    val error: SpeechRecognitionError? = null,
    val interrupted: Boolean = false,
)

data class ReadyOsuPresentation(val state: ReadyOsuState = ReadyOsuState()) {
    val shellState: ActivityShellState = when (state.phase) {
        ReadyOsuPhase.READY -> ActivityShellState.READY
        ReadyOsuPhase.ERROR -> ActivityShellState.ERROR
        ReadyOsuPhase.COMPLETE -> ActivityShellState.COMPLETE
        else -> ActivityShellState.ACTIVE
    }
    val microphoneActive = state.phase == ReadyOsuPhase.LISTENING
    val promptPlaying = state.phase == ReadyOsuPhase.PROMPTING
    val checking = state.phase == ReadyOsuPhase.CHECKING
    val canFinish = state.phase in setOf(
        ReadyOsuPhase.FEEDBACK_CONFIRMED,
        ReadyOsuPhase.FEEDBACK_UNCONFIRMED,
    )
    val cameraRequired = false
}

class ReadyOsuController(private val onChanged: (ReadyOsuPresentation) -> Unit) {
    var state = ReadyOsuState()
        private set

    fun start() = update(state.copy(phase = ReadyOsuPhase.MODEL, error = null, interrupted = false))

    fun beginPrompt() = update(state.copy(phase = ReadyOsuPhase.PROMPTING, error = null, interrupted = false))

    fun beginListening() = update(state.copy(phase = ReadyOsuPhase.LISTENING))

    fun beginChecking() = update(state.copy(phase = ReadyOsuPhase.CHECKING))

    fun handleTranscripts(transcripts: List<String>) {
        val confirmed = ShortVoiceCommandMatcher.matches(ShortVoiceCommand.OSU, transcripts)
        update(state.copy(
            phase = if (confirmed) ReadyOsuPhase.FEEDBACK_CONFIRMED else ReadyOsuPhase.FEEDBACK_UNCONFIRMED,
            attempts = state.attempts + 1,
            voiceVerified = state.voiceVerified || confirmed,
            error = null,
        ))
    }

    fun stopListening() = update(state.copy(
        phase = ReadyOsuPhase.FEEDBACK_UNCONFIRMED,
        attempts = state.attempts + 1,
        error = null,
    ))

    fun fail(error: SpeechRecognitionError, interrupted: Boolean = false) = update(state.copy(
        phase = ReadyOsuPhase.ERROR,
        error = error,
        interrupted = interrupted,
    ))

    fun continueWithoutVerification() = update(state.copy(
        phase = ReadyOsuPhase.FEEDBACK_UNCONFIRMED,
        error = null,
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
        error = null,
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
