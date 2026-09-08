package dk.lasse.karatecliprecorder.learning

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

data class SpeechRecognitionAlternative(
    val transcript: String,
    val confidence: Float? = null,
)

data class SpeechRecognitionFailure(
    val error: SpeechRecognitionError,
    val technicalMessage: String? = null,
)

enum class SpeechRecognitionError {
    MICROPHONE_PERMISSION_DENIED,
    NO_SPEECH_DETECTED,
    RECOGNITION_SERVICE_UNAVAILABLE,
    LANGUAGE_NOT_SUPPORTED,
    LANGUAGE_UNAVAILABLE,
    NETWORK,
    EMPTY_TRANSCRIPTION,
    TIMEOUT,
    BUSY,
    CLIENT,
    SERVER,
    UNKNOWN,
}

data class LiveSpeechRecognitionConfig(
    val languageTag: String,
    val minimumSessionLengthMs: Long,
    val possiblyCompleteSilenceMs: Long,
    val completeSilenceMs: Long,
    val segmentedSession: Boolean = false,
    val maxResults: Int = 5,
)

/** Android speech-recognition capability with no lesson, command, or counting assumptions. */
class LiveSpeechRecognizer(context: Context) {
    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    private var active = false

    fun start(
        config: LiveSpeechRecognitionConfig,
        onPartialResults: (List<String>) -> Unit,
        onSegmentResults: (List<SpeechRecognitionAlternative>) -> Unit = {},
        onFinalResults: (List<SpeechRecognitionAlternative>) -> Unit,
        onRecognitionEnded: () -> Unit = {},
        onError: (SpeechRecognitionFailure) -> Unit,
    ) {
        cancel()
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            onError(SpeechRecognitionFailure(
                SpeechRecognitionError.RECOGNITION_SERVICE_UNAVAILABLE,
                "No Android speech recognition service is available.",
            ))
            return
        }

        val speechRecognizer = try {
            SpeechRecognizer.createSpeechRecognizer(appContext)
        } catch (error: Throwable) {
            onError(SpeechRecognitionFailure(
                SpeechRecognitionError.RECOGNITION_SERVICE_UNAVAILABLE,
                "Unable to create Android speech recognition: ${error.message}",
            ))
            return
        }
        recognizer = speechRecognizer
        active = true
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit

            override fun onError(error: Int) {
                if (!active) return
                finish()
                onError(SpeechRecognitionFailure(
                    error.toSpeechRecognitionError(),
                    "Android live speech recognition failed with error code $error.",
                ))
            }

            override fun onResults(results: Bundle?) {
                if (!active) return
                val alternatives = results.toAlternatives()
                finish()
                if (alternatives.isEmpty()) {
                    onError(SpeechRecognitionFailure(
                        SpeechRecognitionError.EMPTY_TRANSCRIPTION,
                        "The recognizer returned no final alternatives.",
                    ))
                } else {
                    onFinalResults(alternatives)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (!active) return
                onPartialResults(
                    partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        .orEmpty()
                        .filter(String::isNotBlank),
                )
            }

            override fun onSegmentResults(segmentResults: Bundle) {
                if (!active) return
                segmentResults.toAlternatives()
                    .takeIf(List<SpeechRecognitionAlternative>::isNotEmpty)
                    ?.let(onSegmentResults)
            }

            override fun onEndOfSegmentedSession() {
                if (!active) return
                finish()
                onRecognitionEnded()
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        try {
            speechRecognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, config.languageTag)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, config.maxResults)
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                    config.minimumSessionLengthMs,
                )
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    config.possiblyCompleteSilenceMs,
                )
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                    config.completeSilenceMs,
                )
                if (config.segmentedSession && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    putExtra(
                        RecognizerIntent.EXTRA_SEGMENTED_SESSION,
                        RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                    )
                }
            })
        } catch (error: Throwable) {
            finish()
            onError(SpeechRecognitionFailure(
                if (error is SecurityException) {
                    SpeechRecognitionError.MICROPHONE_PERMISSION_DENIED
                } else {
                    SpeechRecognitionError.CLIENT
                },
                "Unable to start live recognition: ${error.message}",
            ))
        }
    }

    fun stopListening() {
        if (active) runCatching { recognizer?.stopListening() }
    }

    fun cancel() {
        active = false
        recognizer?.let { current ->
            runCatching { current.cancel() }
            runCatching { current.destroy() }
        }
        recognizer = null
    }

    fun release() = cancel()

    private fun finish() {
        active = false
        recognizer?.let { current -> runCatching { current.destroy() } }
        recognizer = null
    }

    private fun Bundle?.toAlternatives(): List<SpeechRecognitionAlternative> {
        val transcripts = this
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            .orEmpty()
        val confidence = this?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
        return transcripts.mapIndexedNotNull { index, transcript ->
            transcript.takeIf(String::isNotBlank)?.let { raw ->
                SpeechRecognitionAlternative(
                    transcript = raw,
                    confidence = confidence?.getOrNull(index)?.takeIf { it.isFinite() && it in 0f..1f },
                )
            }
        }
    }

    private fun Int.toSpeechRecognitionError(): SpeechRecognitionError = when (this) {
        SpeechRecognizer.ERROR_AUDIO,
        SpeechRecognizer.ERROR_CLIENT,
        -> SpeechRecognitionError.CLIENT
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechRecognitionError.MICROPHONE_PERMISSION_DENIED
        SpeechRecognizer.ERROR_NETWORK -> SpeechRecognitionError.NETWORK
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> SpeechRecognitionError.TIMEOUT
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        -> SpeechRecognitionError.NO_SPEECH_DETECTED
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> SpeechRecognitionError.BUSY
        SpeechRecognizer.ERROR_SERVER,
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS,
        -> SpeechRecognitionError.SERVER
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> SpeechRecognitionError.LANGUAGE_NOT_SUPPORTED
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> SpeechRecognitionError.LANGUAGE_UNAVAILABLE
        else -> SpeechRecognitionError.UNKNOWN
    }
}
