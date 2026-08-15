package dk.lasse.karatecliprecorder.learning

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/** Normalizes Japanese live-recognition transcripts for the count-to-ten exercise. */
object CountTranscriptNormalizer {
    private data class Alias(
        val text: String,
        val number: String,
        val rule: CountNormalizationRule,
    )

    private val aliases: List<Alias> = buildList {
        fun add(number: String, rule: CountNormalizationRule, vararg values: String) {
            values.forEach { value -> add(Alias(value, number, rule)) }
        }

        add("1", CountNormalizationRule.ARABIC_DIGIT, "1")
        add("2", CountNormalizationRule.ARABIC_DIGIT, "2")
        add("3", CountNormalizationRule.ARABIC_DIGIT, "3")
        add("4", CountNormalizationRule.ARABIC_DIGIT, "4")
        add("5", CountNormalizationRule.ARABIC_DIGIT, "5")
        add("6", CountNormalizationRule.ARABIC_DIGIT, "6")
        add("7", CountNormalizationRule.ARABIC_DIGIT, "7")
        add("8", CountNormalizationRule.ARABIC_DIGIT, "8")
        add("9", CountNormalizationRule.ARABIC_DIGIT, "9")
        add("10", CountNormalizationRule.ARABIC_DIGIT, "10")

        add("1", CountNormalizationRule.KANJI, "一")
        add("2", CountNormalizationRule.KANJI, "二")
        add("3", CountNormalizationRule.KANJI, "三")
        add("4", CountNormalizationRule.KANJI, "四")
        add("5", CountNormalizationRule.KANJI, "五")
        add("6", CountNormalizationRule.KANJI, "六")
        add("7", CountNormalizationRule.KANJI, "七")
        add("8", CountNormalizationRule.KANJI, "八")
        add("9", CountNormalizationRule.KANJI, "九")
        add("10", CountNormalizationRule.KANJI, "十")

        add("1", CountNormalizationRule.HIRAGANA, "いち")
        add("2", CountNormalizationRule.HIRAGANA, "に")
        add("3", CountNormalizationRule.HIRAGANA, "さん")
        add("4", CountNormalizationRule.HIRAGANA, "し", "よん")
        add("5", CountNormalizationRule.HIRAGANA, "ご")
        add("6", CountNormalizationRule.HIRAGANA, "ろく")
        add("7", CountNormalizationRule.HIRAGANA, "しち", "なな")
        add("8", CountNormalizationRule.HIRAGANA, "はち")
        add("9", CountNormalizationRule.HIRAGANA, "きゅう", "く")
        add("10", CountNormalizationRule.HIRAGANA, "じゅう")

        add("1", CountNormalizationRule.KATAKANA, "イチ")
        add("2", CountNormalizationRule.KATAKANA, "ニ")
        add("3", CountNormalizationRule.KATAKANA, "サン")
        add("4", CountNormalizationRule.KATAKANA, "シ", "ヨン")
        add("5", CountNormalizationRule.KATAKANA, "ゴ")
        add("6", CountNormalizationRule.KATAKANA, "ロク")
        add("7", CountNormalizationRule.KATAKANA, "シチ", "ナナ")
        add("8", CountNormalizationRule.KATAKANA, "ハチ")
        add("9", CountNormalizationRule.KATAKANA, "キュウ", "ク")
        add("10", CountNormalizationRule.KATAKANA, "ジュウ")

        add("1", CountNormalizationRule.ROMAJI, "ichi", "ich")
        add("2", CountNormalizationRule.ROMAJI, "ni")
        add("3", CountNormalizationRule.ROMAJI, "san")
        add("4", CountNormalizationRule.ROMAJI, "shi", "yon")
        add("5", CountNormalizationRule.ROMAJI, "go")
        add("6", CountNormalizationRule.ROMAJI, "roku", "rok")
        add("7", CountNormalizationRule.ROMAJI, "shichi", "shich", "nana")
        add("8", CountNormalizationRule.ROMAJI, "hachi", "hach")
        add("9", CountNormalizationRule.ROMAJI, "kyuu", "kyu", "ku")
        add("10", CountNormalizationRule.ROMAJI, "juu", "ju")
    }.sortedByDescending { alias -> alias.text.length }

    fun normalizeJapaneseTranscript(
        raw: String,
        recognitionLanguage: String = JapaneseCountSequence.PRIMARY_LANGUAGE,
        recognitionConfidence: Float? = null,
    ): CountNormalizationCandidate {
        val prepared = prepare(raw)
        val tokens = mutableListOf<CountTokenMatch>()
        var index = 0
        while (index < prepared.length) {
            val match = aliases.firstOrNull { alias -> prepared.startsWith(alias.text, index) }
            if (match != null) {
                tokens += CountTokenMatch(
                    recognizedText = match.text,
                    preparedText = match.text,
                    normalizedNumber = match.number,
                    normalizationRule = match.rule,
                )
                index += match.text.length
            } else {
                val start = index
                index += 1
                while (index < prepared.length && aliases.none { alias -> prepared.startsWith(alias.text, index) }) {
                    index += 1
                }
                val unknown = prepared.substring(start, index)
                tokens += CountTokenMatch(
                    recognizedText = unknown,
                    preparedText = unknown,
                    normalizedNumber = null,
                    normalizationRule = null,
                )
            }
        }

        return CountNormalizationCandidate(
            rawTranscript = raw,
            recognitionLanguage = recognitionLanguage,
            tokens = tokens,
            exerciseResult = scoreTokens(tokens),
            recognitionConfidence = recognitionConfidence?.takeIf { it.isFinite() && it in 0f..1f },
        )
    }

    fun selectStrongestAlternative(
        alternatives: List<CountRecognitionAlternative>,
    ): CountNormalizationCandidate? = alternatives
        .mapIndexed { index, alternative ->
            IndexedCandidate(
                index = index,
                candidate = normalizeJapaneseTranscript(
                    raw = alternative.transcript,
                    recognitionConfidence = alternative.confidence,
                ),
            )
        }
        .filter { indexed -> indexed.candidate.rawTranscript.isNotBlank() }
        .maxWithOrNull(
            compareBy<IndexedCandidate> { it.candidate.successful }
                .thenBy { it.candidate.sequenceScore }
                .thenBy { candidate -> candidate.candidate.exerciseResult.countResults.count { it.status == CountStatus.CORRECT } }
                .thenBy { it.candidate.recognitionConfidence ?: -1f }
                .thenBy { -it.index },
        )
        ?.candidate

    /** True after a partial transcript contains ten recognizable count terms in any order. */
    fun hasRecognizedCountLimit(raw: String, limit: Int = JapaneseCountSequence.expected.size): Boolean =
        normalizeJapaneseTranscript(raw).normalizedSequence.size >= limit

    fun scoreNormalizedSequence(normalizedNumbers: List<String>): Float =
        JapaneseCountSequence.expected.indices.count { index ->
            normalizedNumbers.getOrNull(index) == JapaneseCountSequence.expected[index]
        }.toFloat() / JapaneseCountSequence.expected.size

    private fun scoreTokens(tokens: List<CountTokenMatch>): CountExerciseResult {
        val expected = JapaneseCountSequence.expected
        val aligned = alignTokensToExpected(tokens, expected)
        val results = expected.mapIndexed { index, expectedNumber ->
            val token = aligned[index]
            CountResult(
                expectedPosition = index + 1,
                expectedNumber = expectedNumber,
                recognizedText = token?.recognizedText,
                normalizedNumber = token?.normalizedNumber,
                normalizationRule = token?.normalizationRule,
                status = when {
                    token == null -> CountStatus.MISSING
                    token.normalizedNumber == expectedNumber -> CountStatus.CORRECT
                    else -> CountStatus.INCORRECT
                },
            )
        }
        val normalized = tokens.mapNotNull(CountTokenMatch::normalizedNumber)
        return CountExerciseResult(
            normalizedSequence = normalized,
            countResults = results,
            sequenceScore = results.count { it.status == CountStatus.CORRECT }.toFloat() / expected.size,
            successful = tokens.size == expected.size &&
                tokens.all { it.normalizedNumber != null } &&
                normalized == expected,
        )
    }

    private fun alignTokensToExpected(
        tokens: List<CountTokenMatch>,
        expected: List<String>,
    ): List<CountTokenMatch?> {
        val costs = Array(expected.size + 1) { IntArray(tokens.size + 1) }
        val displacement = Array(expected.size + 1) { IntArray(tokens.size + 1) }
        val steps = Array(expected.size + 1) { arrayOfNulls<AlignmentStep>(tokens.size + 1) }
        for (i in 1..expected.size) {
            costs[i][0] = i
            steps[i][0] = AlignmentStep.MISSING
        }
        for (j in 1..tokens.size) {
            costs[0][j] = j
            steps[0][j] = AlignmentStep.EXTRA
        }
        for (i in 1..expected.size) {
            for (j in 1..tokens.size) {
                var bestCost = costs[i - 1][j - 1] + if (tokens[j - 1].normalizedNumber == expected[i - 1]) 0 else 1
                var bestDisplacement = displacement[i - 1][j - 1] + abs(i - j)
                var bestStep = AlignmentStep.DIAGONAL
                val missingCost = costs[i - 1][j] + 1
                if (missingCost < bestCost || missingCost == bestCost && displacement[i - 1][j] < bestDisplacement) {
                    bestCost = missingCost
                    bestDisplacement = displacement[i - 1][j]
                    bestStep = AlignmentStep.MISSING
                }
                val extraCost = costs[i][j - 1] + 1
                if (extraCost < bestCost || extraCost == bestCost && displacement[i][j - 1] < bestDisplacement) {
                    bestCost = extraCost
                    bestDisplacement = displacement[i][j - 1]
                    bestStep = AlignmentStep.EXTRA
                }
                costs[i][j] = bestCost
                displacement[i][j] = bestDisplacement
                steps[i][j] = bestStep
            }
        }
        val aligned = MutableList<CountTokenMatch?>(expected.size) { null }
        var i = expected.size
        var j = tokens.size
        while (i > 0 || j > 0) {
            when (steps[i][j]) {
                AlignmentStep.DIAGONAL -> {
                    aligned[i - 1] = tokens[j - 1]
                    i -= 1
                    j -= 1
                }
                AlignmentStep.MISSING -> i -= 1
                AlignmentStep.EXTRA -> j -= 1
                null -> break
            }
        }
        return aligned
    }

    private fun prepare(raw: String): String = Normalizer
        .normalize(raw, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)

    private data class IndexedCandidate(val index: Int, val candidate: CountNormalizationCandidate)

    private enum class AlignmentStep { DIAGONAL, MISSING, EXTRA }

}
