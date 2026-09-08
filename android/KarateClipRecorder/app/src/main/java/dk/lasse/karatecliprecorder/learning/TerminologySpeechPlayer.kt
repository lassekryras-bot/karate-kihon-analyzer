package dk.lasse.karatecliprecorder.learning

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

data class TerminologyPrompt(
    val spokenText: String,
    val locale: Locale,
)

/** Small prompt player for terminology activities; it owns no activity state or recognition. */
class TerminologySpeechPlayer(context: Context) : AutoCloseable {
    private val requestIds = AtomicLong(0L)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var closed = false
    private var pendingRequest: (() -> Unit)? = null
    private var activeLastUtteranceId: String? = null
    private var activeOnComplete: (() -> Unit)? = null
    private var activeOnError: ((Throwable) -> Unit)? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (closed) return@TextToSpeech
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == activeLastUtteranceId) finishSuccess()
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId == activeLastUtteranceId) {
                            finishError(IllegalStateException("Speech playback failed."))
                        }
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        if (utteranceId == activeLastUtteranceId) {
                            finishError(IllegalStateException("Speech playback failed with code $errorCode."))
                        }
                    }
                })
                pendingRequest?.also { pendingRequest = null }?.invoke()
            } else {
                pendingRequest = null
                finishError(IllegalStateException("Text-to-speech is unavailable."))
            }
        }
    }

    fun play(
        prompts: List<TerminologyPrompt>,
        onComplete: () -> Unit = {},
        onError: (Throwable) -> Unit = {},
    ) {
        stop()
        if (prompts.isEmpty()) {
            onComplete()
            return
        }
        val requestId = requestIds.incrementAndGet()
        activeLastUtteranceId = utteranceId(requestId, prompts.lastIndex)
        activeOnComplete = onComplete
        activeOnError = onError
        val request = {
            val engine = tts
            if (engine == null || closed) {
                finishError(IllegalStateException("Text-to-speech is unavailable."))
            } else {
                prompts.forEachIndexed { index, prompt ->
                    engine.setLanguage(prompt.locale)
                    engine.speak(
                        prompt.spokenText,
                        if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                        Bundle(),
                        utteranceId(requestId, index),
                    )
                }
            }
        }
        if (ready) request() else pendingRequest = request
    }

    fun stop() {
        pendingRequest = null
        activeLastUtteranceId = null
        activeOnComplete = null
        activeOnError = null
        tts?.stop()
    }

    override fun close() {
        closed = true
        stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    private fun finishSuccess() {
        val callback = activeOnComplete
        activeLastUtteranceId = null
        activeOnComplete = null
        activeOnError = null
        callback?.let { onComplete -> mainHandler.post { onComplete() } }
    }

    private fun finishError(error: Throwable) {
        val callback = activeOnError
        activeLastUtteranceId = null
        activeOnComplete = null
        activeOnError = null
        callback?.let { onError -> mainHandler.post { onError(error) } }
    }

    private fun utteranceId(requestId: Long, index: Int) = "terminology-$requestId-$index"
}
