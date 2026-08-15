package dk.lasse.karatecliprecorder.learning

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CountTranscriptNormalizerTest {
    @Test fun normalizesArabicKanjiHiraganaKatakanaAndRomaji() {
        val transcripts = listOf(
            "1 2 3 4 5 6 7 8 9 10",
            "一二三四五六七八九十",
            "いちにさんしごろくしちはちきゅうじゅう",
            "イチ、ニ、サン、シ、ゴ、ロク、シチ、ハチ、キュウ、ジュウ",
            "ichi ni san shi go roku shichi hachi kyu ju",
        )

        transcripts.forEach { transcript ->
            val candidate = CountTranscriptNormalizer.normalizeJapaneseTranscript(transcript)
            assertEquals(JapaneseCountSequence.expected, candidate.normalizedSequence, transcript)
            assertTrue(candidate.successful, transcript)
            assertTrue(candidate.exerciseResult.countResults.all { it.status == CountStatus.CORRECT }, transcript)
        }
    }

    @Test fun normalizesFullWidthDigitsAndJapanesePunctuation() {
        val candidate = CountTranscriptNormalizer.normalizeJapaneseTranscript(
            "１、２、３、４、５、６、７、８、９、１０。",
        )

        assertEquals(JapaneseCountSequence.expected, candidate.normalizedSequence)
        assertTrue(candidate.successful)
        assertTrue(candidate.tokens.all { it.normalizationRule == CountNormalizationRule.ARABIC_DIGIT })
    }

    @Test fun supportsCommonJapaneseCountingAlternatives() {
        val candidate = CountTranscriptNormalizer.normalizeJapaneseTranscript(
            "いち に さん よん ご ろく なな はち く じゅう",
        )

        assertEquals(JapaneseCountSequence.expected, candidate.normalizedSequence)
        assertTrue(candidate.successful)
    }

    @Test fun supportsShortKarateRomajiForms() {
        val candidate = CountTranscriptNormalizer.normalizeJapaneseTranscript(
            "ich ni san shi go rok shich hach kyu ju",
        )

        assertEquals(JapaneseCountSequence.expected, candidate.normalizedSequence)
        assertTrue(candidate.successful)
    }

    @Test fun longestMatchPreventsOverlappingJapaneseTermsFromSplitting() {
        val candidate = CountTranscriptNormalizer.normalizeJapaneseTranscript(
            "いちにさんしごろくしちはちきゅうじゅう",
        )

        assertEquals(listOf("し", "しち"), candidate.tokens.filter { it.normalizedNumber in setOf("4", "7") }.map { it.recognizedText })
        assertEquals(listOf("きゅう", "じゅう"), candidate.tokens.takeLast(2).map { it.recognizedText })
        assertTrue(candidate.successful)
    }

    @Test fun missingCountProducesMissingResultAndFailsExercise() {
        val candidate = CountTranscriptNormalizer.normalizeJapaneseTranscript(
            "一二三四五六八九十",
        )

        assertFalse(candidate.successful)
        assertEquals(CountStatus.MISSING, candidate.exerciseResult.countResults[6].status)
        assertEquals("7", candidate.exerciseResult.countResults[6].expectedNumber)
    }

    @Test fun unknownTextPreventsOtherwiseCompleteSequenceFromPassing() {
        val candidate = CountTranscriptNormalizer.normalizeJapaneseTranscript(
            "一二三四五六七八九十ありがとう",
        )

        assertEquals(JapaneseCountSequence.expected, candidate.normalizedSequence)
        assertTrue(candidate.tokens.any { it.normalizedNumber == null })
        assertFalse(candidate.successful)
    }

    @Test fun evaluatesEveryAlternativeAndSelectsTheStrongestMatch() {
        val selected = assertNotNull(
            CountTranscriptNormalizer.selectStrongestAlternative(
                listOf(
                    CountRecognitionAlternative("一二三四五六七八九", confidence = 0.99f),
                    CountRecognitionAlternative("いちにさんしごろくしちはちきゅうじゅう", confidence = 0.40f),
                    CountRecognitionAlternative("一二三四五六八七九十", confidence = 0.95f),
                ),
            ),
        )

        assertEquals("いちにさんしごろくしちはちきゅうじゅう", selected.rawTranscript)
        assertTrue(selected.successful)
    }

    @Test fun autoFinishTriggersAfterTenRecognizedCountsInCorrectOrWrongOrder() {
        assertTrue(CountTranscriptNormalizer.hasRecognizedCountLimit("一二三四五六七八九十"))
        assertTrue(CountTranscriptNormalizer.hasRecognizedCountLimit("一二三四五六八七九十"))
        assertFalse(CountTranscriptNormalizer.hasRecognizedCountLimit("一二三四五六七八九"))
    }

    @Test fun perNumberResultsKeepJapaneseTextAndRule() {
        val candidate = CountTranscriptNormalizer.normalizeJapaneseTranscript("一二三四五六七八九十")
        val six = candidate.exerciseResult.countResults[5]

        assertEquals(6, six.expectedPosition)
        assertEquals("6", six.expectedNumber)
        assertEquals("六", six.recognizedText)
        assertEquals("6", six.normalizedNumber)
        assertEquals(CountNormalizationRule.KANJI, six.normalizationRule)
        assertEquals(CountStatus.CORRECT, six.status)
    }
}
