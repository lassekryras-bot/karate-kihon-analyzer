package dk.lasse.karateanalyzer.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TemporalStepVerifierTest {
    @Test fun successAcceptsOnceAndStaysAccepted() {
        val verifier = TemporalStepVerifier()
        val results = matchingSequence(verifier, listOf(0, 150, 300, 450, 600, 700))
        assertTrue(results[1].progress in 0f..1f)
        assertTrue(results[2].progress > results[1].progress)
        assertFalse(results[3].accepted)
        assertTrue(results[4].accepted)
        assertTrue(results[4].newlyAccepted)
        assertTrue(results[5].accepted)
        assertFalse(results[5].newlyAccepted)
        verifier.reset()
        assertFalse(verifier.update(frame(800), instant()).accepted)
    }

    @Test fun oneFrameHighScoreAndDuplicateTimestampsDoNotAccept() {
        val verifier = TemporalStepVerifier()
        val first = verifier.update(frame(0), instant(score = 1f, quality = 1f))
        val duplicate = verifier.update(frame(0), instant(score = 1f, quality = 1f))
        assertFalse(first.accepted)
        assertFalse(duplicate.accepted)
        assertEquals(0L, duplicate.reliableMatchingMs)
    }

    @Test fun qualityAndProvenanceRules() {
        assertTrue(matchingSequence(TemporalStepVerifier(), listOf(0, 200, 400, 600), LandmarkSource.OBSERVED).last().accepted)
        assertTrue(matchingSequence(TemporalStepVerifier(), listOf(0, 200, 400, 600), LandmarkSource.INTERPOLATED).last().accepted)
        assertFalse(matchingSequence(TemporalStepVerifier(), listOf(0, 200, 400, 600, 800), LandmarkSource.PREDICTED).last().accepted)
        val lowQuality = TemporalStepVerifier().update(frame(600), instant(quality = 0.5f))
        assertFalse(lowQuality.accepted)
        val mixed = TemporalStepVerifier()
        matchingSequence(mixed, listOf(0, 200, 400), LandmarkSource.OBSERVED)
        val notAccepted = mixed.update(frame(600, source = LandmarkSource.PREDICTED), instant())
        assertFalse(notAccepted.accepted)
    }

    @Test fun mixedPredictedRequiresReliableRatio() {
        val verifier = TemporalStepVerifier()
        verifier.update(frame(0, source = LandmarkSource.OBSERVED), instant())
        verifier.update(frame(200, source = LandmarkSource.OBSERVED), instant())
        verifier.update(frame(400, source = LandmarkSource.OBSERVED), instant())
        verifier.update(frame(600, source = LandmarkSource.PREDICTED), instant())
        val accepted = verifier.update(frame(800, source = LandmarkSource.OBSERVED), instant())
        assertTrue(accepted.accepted)
    }

    @Test fun missingDataGraceDecayAndLongGapReset() {
        val verifier = TemporalStepVerifier()
        verifier.update(frame(0), instant())
        verifier.update(frame(400), instant()) // long gap resets, no progress
        assertEquals(0f, verifier.update(frame(400), instant()).progress)
        val v2 = TemporalStepVerifier()
        v2.update(frame(0), instant()); val built = v2.update(frame(100), instant())
        val paused = v2.update(frame(200), instant(status = InstantVerificationStatus.INSUFFICIENT_DATA, feedback = FeedbackCode.INSUFFICIENT_VISIBILITY))
        assertEquals(built.progress, paused.progress)
        val decayed = v2.update(frame(450), instant(status = InstantVerificationStatus.INSUFFICIENT_DATA, feedback = FeedbackCode.INSUFFICIENT_VISIBILITY))
        assertTrue(decayed.progress < paused.progress)
    }

    @Test fun partialAndIncorrectDecayWithoutAccepting() {
        val verifier = TemporalStepVerifier()
        verifier.update(frame(0), instant()); val built = verifier.update(frame(100), instant())
        val partial = verifier.update(frame(200), instant(status = InstantVerificationStatus.PARTIAL_MATCH, feedback = FeedbackCode.HOLD_STILL))
        assertEquals(built.progress, partial.progress)
        val extended = verifier.update(frame(400), instant(status = InstantVerificationStatus.PARTIAL_MATCH, feedback = FeedbackCode.HOLD_STILL))
        assertFalse(extended.accepted)
        val wrong = verifier.update(frame(500), instant(status = InstantVerificationStatus.NOT_MATCHING, feedback = FeedbackCode.OPEN_FINGERS))
        assertTrue(wrong.progress >= 0f)
        val resumed = verifier.update(frame(600), instant())
        assertTrue(resumed.progress >= wrong.progress)
    }

    @Test fun stateIsolationAndBackwardsTimestamps() {
        val verifier = TemporalStepVerifier()
        verifier.update(frame(0), instant()); val built = verifier.update(frame(100), instant())
        assertTrue(built.progress > 0f)
        assertEquals(0f, verifier.update(frame(200), instant(step = HandLessonStep.CLOSE_FINGERS)).progress)
        verifier.update(frame(300, handedness = Handedness.RIGHT), instant(step = HandLessonStep.CLOSE_FINGERS))
        assertEquals(0f, verifier.update(frame(400, handedness = Handedness.LEFT), instant(step = HandLessonStep.CLOSE_FINGERS)).progress)
        verifier.resetForStep(HandLessonStep.OPEN_PALM)
        assertEquals(0f, verifier.update(frame(500), instant()).progress)
        verifier.update(frame(600), instant())
        assertEquals(0f, verifier.update(frame(550), instant()).progress)
    }

    @Test fun generalFpsDeterministicAndClamped() {
        for (interval in listOf(67L, 33L, 16L)) {
            val times = generateSequence(0L) { it + interval }.takeWhile { it < 600 }.toList() + 600L
            assertTrue(matchingSequence(TemporalStepVerifier(), times).last().accepted)
        }
        val a = matchingSequence(TemporalStepVerifier(), listOf(0, 100, 200))
        val b = matchingSequence(TemporalStepVerifier(), listOf(0, 100, 200))
        assertEquals(a, b)
        a.forEach { assertTrue(it.progress.isFinite() && it.progress in 0f..1f) }
    }

    @Test fun nonGoodMatchingDoesNotAccumulate() {
        val result = TemporalStepVerifier().update(frame(600), instant(feedback = FeedbackCode.HOLD_STILL))
        assertEquals(0L, result.accumulatedMatchingMs)
    }

    private fun matchingSequence(verifier: TemporalStepVerifier, times: List<Long>, source: LandmarkSource = LandmarkSource.OBSERVED): List<TemporalStepResult> =
        times.map { verifier.update(frame(it, source = source), instant()) }

    private fun instant(step: HandLessonStep = HandLessonStep.OPEN_PALM, status: InstantVerificationStatus = InstantVerificationStatus.MATCHING, score: Float = 1f, quality: Float = 1f, feedback: FeedbackCode = FeedbackCode.GOOD) =
        InstantStepResult(step, status, score, quality, feedback, criticalLandmarksVisible = true)

    private fun frame(timestamp: Long, handedness: Handedness = Handedness.RIGHT, source: LandmarkSource = LandmarkSource.OBSERVED): TrackedHandFrame =
        TrackedHandFrame(timestamp, handedness, HandLandmarkId.entries.associateWith { LandmarkSample(Point3(0f, 0f, 0f), 1f, source) })
}
