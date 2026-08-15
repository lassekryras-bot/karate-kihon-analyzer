package dk.lasse.karatecliprecorder.learning

enum class CountStatus {
    CORRECT,
    INCORRECT,
    MISSING,
}

enum class CountNormalizationRule {
    ARABIC_DIGIT,
    KANJI,
    HIRAGANA,
    KATAKANA,
    ROMAJI,
}

data class CountResult(
    val expectedPosition: Int,
    val expectedNumber: String,
    val recognizedText: String?,
    val normalizedNumber: String?,
    val normalizationRule: CountNormalizationRule?,
    val status: CountStatus,
)

data class CountExerciseResult(
    val expectedSequence: List<String> = JapaneseCountSequence.expected,
    val normalizedSequence: List<String>,
    val countResults: List<CountResult>,
    val sequenceScore: Float,
    val successful: Boolean,
)

data class CountTokenMatch(
    val recognizedText: String,
    val preparedText: String,
    val normalizedNumber: String?,
    val normalizationRule: CountNormalizationRule?,
)

data class CountNormalizationCandidate(
    val rawTranscript: String,
    val recognitionLanguage: String,
    val tokens: List<CountTokenMatch>,
    val exerciseResult: CountExerciseResult,
    val recognitionConfidence: Float? = null,
) {
    val normalizedSequence: List<String>
        get() = exerciseResult.normalizedSequence

    val sequenceScore: Float
        get() = exerciseResult.sequenceScore

    val successful: Boolean
        get() = exerciseResult.successful
}

data class CountRecognitionAlternative(
    val transcript: String,
    val confidence: Float? = null,
)

data class CountRecognitionFailure(
    val error: CountRecognitionError,
    val technicalMessage: String? = null,
)

enum class CountRecognitionError {
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

enum class CountTrainingPhase {
    IDLE,
    READY,
    LISTENING,
    FINALIZING,
    RESULT,
    ERROR,
}

data class CountTrainingSession(
    val level: Int = 2,
    val phase: CountTrainingPhase = CountTrainingPhase.IDLE,
    val expectedSequence: List<String> = JapaneseCountSequence.expected,
    val recognitionLanguage: String = JapaneseCountSequence.PRIMARY_LANGUAGE,
    val partialTranscripts: List<String> = emptyList(),
    val finalTranscriptAlternatives: List<String> = emptyList(),
    val selectedTranscript: String? = null,
    val normalizationTokens: List<CountTokenMatch> = emptyList(),
    val normalizedSequence: List<String> = emptyList(),
    val countResults: List<CountResult> = emptyList(),
    val sequenceScore: Float = 0f,
    val successful: Boolean = false,
    val error: CountRecognitionError? = null,
    val technicalError: String? = null,
)
