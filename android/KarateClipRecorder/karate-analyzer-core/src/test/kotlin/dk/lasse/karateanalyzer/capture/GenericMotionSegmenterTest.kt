package dk.lasse.karateanalyzer.capture

import dk.lasse.karateanalyzer.observation.Evidence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GenericMotionSegmenterTest {
    private val config = GenericMotionSegmenterConfig(
        baselineDwellMs = 100,
        movementStartDwellMs = 50,
        settlingDwellMs = 100,
        noMovementTimeoutMs = 200,
        startMotionThreshold = 0.5,
        quietMotionThreshold = 0.1,
        minimumCoverage = 0.7,
        terminalPoseSimilarity = 0.8,
        maximumStableDisplacement = 0.1,
    )

    @Test fun baselineCueMovementSettlingAndCompletionAreDeterministic() {
        val segmenter = armedSegmenter()
        assertEquals(MotionSegmentState.ARMED, segmenter.snapshot().state)
        segmenter.accept(moving(200))
        assertEquals(MotionSegmentState.MOVING, segmenter.accept(moving(250)).state)
        assertEquals(MotionSegmentState.SETTLING, segmenter.accept(quiet(300)).state)
        val complete = segmenter.accept(quiet(400))
        assertEquals(MotionSegmentState.COMPLETE, complete.state)
        val transition = complete.transitions.last()
        assertEquals(400, transition.decisionTimestampMs)
        assertEquals(300, transition.estimatedBoundaryTimestampMs)
    }

    @Test fun movementBeforeCueIsPreservedAsAnticipatoryMovement() {
        val segmenter = baselineSegmenter()
        segmenter.arm(atTimestampMs = 110, cueTimestampMs = 300)
        segmenter.accept(moving(150))
        val moving = segmenter.accept(moving(200))
        assertEquals(MotionSegmentState.MOVING, moving.state)
        assertEquals(150, moving.transitions.last().estimatedBoundaryTimestampMs)
    }

    @Test fun cueWithoutMovementTimesOutExplicitly() {
        val segmenter = baselineSegmenter()
        segmenter.arm(110, cueTimestampMs = 150)
        segmenter.accept(quiet(200))
        val failed = segmenter.accept(quiet(350))
        assertEquals(MotionSegmentState.FAILED, failed.state)
        assertEquals(MotionSegmentFailure.NO_MOVEMENT_TIMEOUT, failed.failure)
    }

    @Test fun eitherMotionChannelCanStartMovement() {
        for (observationAt in listOf(
            { time: Long -> moving(time, articulated = 0.6, image = 0.0) },
            { time: Long -> moving(time, articulated = 0.0, image = 0.6) },
        )) {
            val segmenter = armedSegmenter()
            segmenter.accept(observationAt(200))
            assertEquals(MotionSegmentState.MOVING, segmenter.accept(observationAt(250)).state)
        }
    }

    @Test fun completionRequiresBothChannelsQuiet() {
        val segmenter = movingSegmenter()
        assertEquals(MotionSegmentState.MOVING, segmenter.accept(moving(300, articulated = 0.0, image = 0.6)).state)
        assertEquals(MotionSegmentState.SETTLING, segmenter.accept(quiet(350)).state)
        assertEquals(MotionSegmentState.COMPLETE, segmenter.accept(quiet(450)).state)
    }

    @Test fun insufficientCoverageAndUnknownNeverCompleteDwell() {
        val segmenter = movingSegmenter()
        segmenter.accept(quiet(300))
        val unknown = segmenter.accept(quiet(350, coverage = 0.2))
        assertEquals(Evidence.UNKNOWN, unknown.latestQuietEvidence)
        assertEquals(MotionSegmentState.SETTLING, unknown.state)
        assertNotEquals(MotionSegmentState.COMPLETE, segmenter.accept(quiet(500, coverage = 0.2)).state)
    }

    @Test fun settlingReturnsToMovingWhenMovementResumes() {
        val segmenter = movingSegmenter()
        assertEquals(MotionSegmentState.SETTLING, segmenter.accept(quiet(300)).state)
        assertEquals(MotionSegmentState.MOVING, segmenter.accept(moving(350)).state)
    }

    @Test fun intermediatePlateauDoesNotCompleteWithoutTerminalPose() {
        val segmenter = movingSegmenter()
        segmenter.accept(quiet(300, similarity = 0.4))
        assertEquals(MotionSegmentState.SETTLING, segmenter.accept(quiet(450, similarity = 0.4)).state)
        segmenter.accept(moving(500))
        segmenter.accept(quiet(550))
        assertEquals(MotionSegmentState.COMPLETE, segmenter.accept(quiet(650)).state)
    }

    @Test fun accumulatedSlowChangePreventsFalseStillness() {
        val segmenter = movingSegmenter()
        val slow = quiet(300).copy(accumulatedDisplacement = 0.2)
        assertEquals(Evidence.FALSE, segmenter.accept(slow).latestQuietEvidence)
        assertEquals(MotionSegmentState.MOVING, segmenter.snapshot().state)
    }

    @Test fun activityDeadlineIsExplicitFailure() {
        val segmenter = baselineSegmenter()
        segmenter.arm(110, activityDeadlineMs = 350)
        segmenter.accept(moving(200))
        segmenter.accept(moving(250))
        val failed = segmenter.accept(moving(350))
        assertEquals(MotionSegmentFailure.ACTIVITY_DEADLINE_EXCEEDED, failed.failure)
        assertEquals(MotionSegmentState.FAILED, failed.state)
    }

    @Test fun identicalReplayProducesIdenticalHistory() {
        val observations = listOf(moving(200), moving(250), quiet(300), quiet(400))
        fun replay(): MotionSegmentSnapshot {
            val segmenter = armedSegmenter()
            observations.forEach(segmenter::accept)
            return segmenter.snapshot()
        }
        assertEquals(replay(), replay())
    }

    @Test fun freeRunningArmDoesNotRequireCue() {
        val segmenter = baselineSegmenter()
        segmenter.arm(110)
        segmenter.accept(moving(150))
        assertEquals(MotionSegmentState.MOVING, segmenter.accept(moving(200)).state)
    }

    private fun baselineSegmenter(): GenericMotionSegmenter = GenericMotionSegmenter(config).also {
        it.accept(quiet(0))
        it.accept(quiet(100))
        assertTrue(it.snapshot().baselineReady)
    }

    private fun armedSegmenter() = baselineSegmenter().also { it.arm(110, cueTimestampMs = 150) }

    private fun movingSegmenter() = armedSegmenter().also {
        it.accept(moving(200))
        it.accept(moving(250))
        assertEquals(MotionSegmentState.MOVING, it.snapshot().state)
    }

    private fun moving(time: Long, articulated: Double = 0.6, image: Double = 0.6) = MotionObservation(
        time, articulated, image, coverage = 0.9, sameAsStartSimilarity = 0.4, accumulatedDisplacement = 0.3,
    )

    private fun quiet(time: Long, coverage: Double = 0.9, similarity: Double = 0.9) = MotionObservation(
        time, 0.02, 0.02, coverage, sameAsStartSimilarity = similarity, mirroredStartSimilarity = similarity,
        accumulatedDisplacement = 0.02,
    )
}
