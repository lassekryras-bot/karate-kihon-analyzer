package dk.lasse.karatecliprecorder.learning

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

class PunchHeightCoachGate(private val repeatCooldownMs: Long = 2_500L) {
    private var lastPrompt: String? = null
    private var lastSpokenAtMs = Long.MIN_VALUE

    fun shouldSpeak(prompt: String, nowMs: Long, force: Boolean = false): Boolean {
        if (prompt.isBlank()) return false
        val samePromptWithinCooldown = prompt == lastPrompt && nowMs - lastSpokenAtMs < repeatCooldownMs
        if (!force && samePromptWithinCooldown) return false
        lastPrompt = prompt
        lastSpokenAtMs = nowMs
        return true
    }

    fun reset() {
        lastPrompt = null
        lastSpokenAtMs = Long.MIN_VALUE
    }
}

class PunchHeightVoiceCoach(
    context: Context,
    private val gate: PunchHeightCoachGate = PunchHeightCoachGate(),
) : AutoCloseable {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var closed = false

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            val engine = tts ?: return@TextToSpeech
            if (closed || status != TextToSpeech.SUCCESS) return@TextToSpeech
            val languageResult = engine.setLanguage(Locale.ENGLISH)
            ready = languageResult != TextToSpeech.LANG_MISSING_DATA &&
                languageResult != TextToSpeech.LANG_NOT_SUPPORTED
            if (ready) {
                engine.setSpeechRate(0.90f)
                engine.setPitch(1.0f)
                selectEnglishVoice(engine)
            }
        }
    }

    fun speak(prompt: String, nowMs: Long, force: Boolean = false): Boolean {
        if (!ready || closed || !gate.shouldSpeak(prompt, nowMs, force)) return false
        tts?.speak(prompt, TextToSpeech.QUEUE_FLUSH, null, "punch-height-coach")
        return true
    }

    fun reset() {
        gate.reset()
        tts?.stop()
    }

    override fun close() {
        closed = true
        ready = false
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    private fun selectEnglishVoice(engine: TextToSpeech) {
        val candidate: Voice? = engine.voices
            ?.filter { it.locale.language == Locale.ENGLISH.language && !it.isNetworkConnectionRequired }
            ?.maxByOrNull { it.quality }
        if (candidate != null) engine.voice = candidate
    }
}
