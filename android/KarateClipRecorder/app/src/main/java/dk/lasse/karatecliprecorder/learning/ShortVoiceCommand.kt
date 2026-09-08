package dk.lasse.karatecliprecorder.learning

import java.text.Normalizer
import java.util.Locale

enum class ShortVoiceCommand {
    OSU,
    STOP,
}

object ShortVoiceCommandMatcher {
    private val osuVariants = setOf("osu", "oss", "おす", "おっす", "オス", "オッス", "押忍")

    fun matches(command: ShortVoiceCommand, transcripts: List<String>): Boolean = transcripts.any { transcript ->
        val prepared = prepare(transcript)
        when (command) {
            ShortVoiceCommand.OSU -> prepared in osuVariants || prepared.split(' ').any(osuVariants::contains)
            ShortVoiceCommand.STOP -> prepared.split(' ').any { it == "stop" }
        }
    }

    private fun prepare(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
}

object ShortVoiceRecognitionConfig {
    val OSU = LiveSpeechRecognitionConfig(
        languageTag = JapaneseCountSequence.PRIMARY_LANGUAGE,
        minimumSessionLengthMs = 2_000L,
        possiblyCompleteSilenceMs = 1_000L,
        completeSilenceMs = 1_500L,
    )

    val STOP = LiveSpeechRecognitionConfig(
        languageTag = "en-US",
        minimumSessionLengthMs = 8_000L,
        possiblyCompleteSilenceMs = 1_000L,
        completeSilenceMs = 1_500L,
    )
}
