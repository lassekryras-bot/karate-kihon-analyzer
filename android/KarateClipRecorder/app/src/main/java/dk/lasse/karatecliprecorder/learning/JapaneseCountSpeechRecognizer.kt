package dk.lasse.karatecliprecorder.learning

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** One Japanese live-recognition session. No microphone audio is stored or replayed. */
class JapaneseCountLiveRecognizer(context: Context) {
    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    private var active = false

    fun start(
        onPartialResults: (List<String>) -> Unit,
        onSegmentResults: (List<CountRecognitionAlternative>) -> Unit,
        onFinalResults: (List<CountRecognitionAlternative>) -> Unit,
        onRecognitionEnded: () -> Unit,
        onError: (CountRecognitionFailure) -> Unit,
    ) {
        cancel()
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            onError(
                CountRecognitionFailure(
                    CountRecognitionError.RECOGNITION_SERVICE_UNAVAILABLE,
                    "No Android speech recognition service is available.",
                ),
            )
            return
        }

        val speechRecognizer = try {
            SpeechRecognizer.createSpeechRecognizer(appContext)
        } catch (error: Throwable) {
            onError(
                CountRecognitionFailure(
                    CountRecognitionError.RECOGNITION_SERVICE_UNAVAILABLE,
                    "Unable to create Android speech recognition: ${error.message}",
                ),
            )
            return
        }
        recognizer = speechRecognizer
        active = true
        speechRecognizer.setRecognitionListener(
            object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit

                override fun onError(error: Int) {
                    if (!active) return
                    finish()
                    onError(
                        CountRecognitionFailure(
                            error = error.toCountRecognitionError(),
                            technicalMessage = "Android live speech recognition failed with error code $error.",
                        ),
                    )
                }

                override fun onResults(results: Bundle?) {
                    if (!active) return
                    val alternatives = results.toAlternatives()
                    finish()
                    if (alternatives.isEmpty()) {
                        onError(
                            CountRecognitionFailure(
                                CountRecognitionError.EMPTY_TRANSCRIPTION,
                                "The Japanese recognizer returned no final alternatives.",
                            ),
                        )
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
                        .takeIf(List<CountRecognitionAlternative>::isNotEmpty)
                        ?.let(onSegmentResults)
                }

                override fun onEndOfSegmentedSession() {
                    if (!active) return
                    finish()
                    onRecognitionEnded()
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            },
        )

        try {
            speechRecognizer.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, JapaneseCountSequence.PRIMARY_LANGUAGE)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_RESULTS)
                    putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                        MINIMUM_SESSION_LENGTH_MS,
                    )
                    putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                        POSSIBLY_COMPLETE_SILENCE_MS,
                    )
                    putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                        COMPLETE_SILENCE_MS,
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        putExtra(
                            RecognizerIntent.EXTRA_SEGMENTED_SESSION,
                            RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                        )
                    }
                },
            )
        } catch (error: Throwable) {
            finish()
            onError(
                CountRecognitionFailure(
                    error = if (error is SecurityException) {
                        CountRecognitionError.MICROPHONE_PERMISSION_DENIED
                    } else {
                        CountRecognitionError.CLIENT
                    },
                    technicalMessage = "Unable to start Japanese live recognition: ${error.message}",
                ),
            )
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

    private fun Bundle?.toAlternatives(): List<CountRecognitionAlternative> {
        val transcripts = this
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            .orEmpty()
        val confidence = this?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
        return transcripts.mapIndexedNotNull { index, transcript ->
            transcript.takeIf(String::isNotBlank)?.let { raw ->
                CountRecognitionAlternative(
                    transcript = raw,
                    confidence = confidence?.getOrNull(index)?.takeIf { it.isFinite() && it in 0f..1f },
                )
            }
        }
    }

    private fun Int.toCountRecognitionError(): CountRecognitionError = when (this) {
        SpeechRecognizer.ERROR_AUDIO -> CountRecognitionError.CLIENT
        SpeechRecognizer.ERROR_CLIENT -> CountRecognitionError.CLIENT
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> CountRecognitionError.MICROPHONE_PERMISSION_DENIED
        SpeechRecognizer.ERROR_NETWORK -> CountRecognitionError.NETWORK
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> CountRecognitionError.TIMEOUT
        SpeechRecognizer.ERROR_NO_MATCH -> CountRecognitionError.NO_SPEECH_DETECTED
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> CountRecognitionError.BUSY
        SpeechRecognizer.ERROR_SERVER,
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS,
        -> CountRecognitionError.SERVER
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> CountRecognitionError.NO_SPEECH_DETECTED
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> CountRecognitionError.LANGUAGE_NOT_SUPPORTED
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> CountRecognitionError.LANGUAGE_UNAVAILABLE
        else -> CountRecognitionError.UNKNOWN
    }

    private companion object {
        const val MAX_RESULTS = 5
        const val MINIMUM_SESSION_LENGTH_MS = 15_000L
        const val POSSIBLY_COMPLETE_SILENCE_MS = 10_000L
        const val COMPLETE_SILENCE_MS = 12_000L
    }
}
