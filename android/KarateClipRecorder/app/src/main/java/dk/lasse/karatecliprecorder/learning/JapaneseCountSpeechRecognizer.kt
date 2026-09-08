package dk.lasse.karatecliprecorder.learning

import android.content.Context

/** Counting-specific adapter over the generic live speech-recognition capability. */
class JapaneseCountLiveRecognizer(context: Context) {
    private val recognizer = LiveSpeechRecognizer(context)

    fun start(
        onPartialResults: (List<String>) -> Unit,
        onSegmentResults: (List<CountRecognitionAlternative>) -> Unit,
        onFinalResults: (List<CountRecognitionAlternative>) -> Unit,
        onRecognitionEnded: () -> Unit,
        onError: (CountRecognitionFailure) -> Unit,
    ) {
        recognizer.start(
            config = COUNTING_CONFIG,
            onPartialResults = onPartialResults,
            onSegmentResults = { alternatives -> onSegmentResults(alternatives.map { it.toCountAlternative() }) },
            onFinalResults = { alternatives -> onFinalResults(alternatives.map { it.toCountAlternative() }) },
            onRecognitionEnded = onRecognitionEnded,
            onError = { failure -> onError(failure.toCountFailure()) },
        )
    }

    fun stopListening() = recognizer.stopListening()

    fun cancel() = recognizer.cancel()

    fun release() = recognizer.release()

    private fun SpeechRecognitionAlternative.toCountAlternative() = CountRecognitionAlternative(
        transcript = transcript,
        confidence = confidence,
    )

    private fun SpeechRecognitionFailure.toCountFailure() = CountRecognitionFailure(
        error = CountRecognitionError.valueOf(error.name),
        technicalMessage = technicalMessage,
    )

    private companion object {
        val COUNTING_CONFIG = LiveSpeechRecognitionConfig(
            languageTag = JapaneseCountSequence.PRIMARY_LANGUAGE,
            minimumSessionLengthMs = 15_000L,
            possiblyCompleteSilenceMs = 10_000L,
            completeSilenceMs = 12_000L,
            segmentedSession = true,
        )
    }
}
