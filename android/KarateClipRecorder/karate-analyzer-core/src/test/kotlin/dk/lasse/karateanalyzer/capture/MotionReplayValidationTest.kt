package dk.lasse.karateanalyzer.capture

import dk.lasse.karateanalyzer.core.LandmarkSource
import dk.lasse.karateanalyzer.core.Point3
import dk.lasse.karateanalyzer.core.PoseFrame
import dk.lasse.karateanalyzer.core.PoseLandmarkId
import dk.lasse.karateanalyzer.core.PoseLandmarkSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MotionReplayValidationTest {
    private val parameters = MotionReplayParameterSet(
        name = "conservative-v1",
        extractor = PoseMotionExtractorConfig(baselineRequiredSamples = 3),
        segmenter = GenericMotionSegmenterConfig(
            baselineDwellMs = 100,
            movementStartDwellMs = 100,
            settlingDwellMs = 100,
            noMovementTimeoutMs = 300,
            startMotionThreshold = 0.2,
            quietMotionThreshold = 0.1,
            minimumCoverage = 0.7,
            terminalPoseSimilarity = 0.8,
            maximumStableDisplacement = 0.1,
        ),
    )

    @Test fun persistedFixtureRoundTripPreservesReplayEvidenceAndLabels() {
        val fixture = multiBurstFixture().copy(
            notes = listOf("synthetic", "manual labels"),
            labels = multiBurstFixture().labels?.copy(
                terminalStableInterval = ReplayInterval(900, 1_000),
                intermediateStablePlateaus = listOf(ReplayInterval(500, 600)),
                trackingLossIntervals = listOf(ReplayInterval(650, 675)),
                ambiguousIntervals = listOf(ReplayInterval(290, 310)),
                anticipatoryMovement = true,
            ),
        )
        val encoded = PoseReplayJson.encode(fixture)
        assertEquals(fixture, PoseReplayJson.decode(encoded))
        assertEquals(encoded, PoseReplayJson.encode(PoseReplayJson.decode(encoded)))
    }

    @Test fun multiBurstReplayDoesNotCompleteAtIntermediatePlateau() {
        val result = MotionReplayRunner().run(multiBurstFixture(), parameters)
        assertEquals(MotionSegmentState.COMPLETE, result.finalState)
        val completion = result.transitions.first { it.newState == MotionSegmentState.COMPLETE }
        assertEquals(900, completion.estimatedBoundaryTimestampMs)
        assertTrue(completion.estimatedBoundaryTimestampMs!! > 600)
        assertTrue(CaptureSafetyFailure.PREMATURE_COMPLETION !in result.score.failures)
    }

    @Test fun replayTraceAndScoreAreDeterministic() {
        val runner = MotionReplayRunner()
        val first = runner.run(multiBurstFixture(), parameters)
        val second = runner.run(multiBurstFixture(), parameters)
        assertEquals(first, second)
        assertEquals(MotionReplayTraceJson.encode(first), MotionReplayTraceJson.encode(second))
    }

    @Test fun scoringMakesPrematureCompletionMuchWorseThanExtraPostRoll() {
        val fixture = multiBurstFixture()
        val early = listOf(
            transition(MotionSegmentState.ARMED, MotionSegmentState.MOVING, 350, 300),
            transition(MotionSegmentState.SETTLING, MotionSegmentState.COMPLETE, 650, 500),
        )
        val late = listOf(
            transition(MotionSegmentState.ARMED, MotionSegmentState.MOVING, 350, 300),
            transition(MotionSegmentState.SETTLING, MotionSegmentState.COMPLETE, 1_100, 1_000),
        )
        val final = GenericMotionSegmenter(parameters.segmenter).snapshot()
        val earlyScore = CaptureSafetyScorer.score(fixture, true, early, final)
        val lateScore = CaptureSafetyScorer.score(fixture, true, late, final)
        assertTrue(earlyScore.totalPenalty > lateScore.totalPenalty * 10)
        assertTrue(CaptureSafetyFailure.PREMATURE_COMPLETION in earlyScore.failures)
    }

    @Test fun parameterSweepUsesIndependentStateAndRanksUnsafeCoveragePolicyWorse() {
        val trackingLoss = multiBurstFixture().copy(
            sequenceId = "terminal-tracking-loss",
            frames = multiBurstFixture().frames.map { frame ->
                if (frame.timestampMs in 900L..1_000L) frame.withMissing(PoseLandmarkId.LEFT_WRIST) else frame
            },
            labels = multiBurstFixture().labels?.copy(trackingLossIntervals = listOf(ReplayInterval(900, 1_000))),
        )
        val relaxed = parameters.copy(
            name = "relaxed-coverage",
            segmenter = parameters.segmenter.copy(minimumCoverage = 0.5),
        )
        val sweep = MotionReplayRunner().sweep(listOf(trackingLoss), listOf(parameters, relaxed))
        assertEquals(listOf("conservative-v1", "relaxed-coverage"), sweep.map { it.parameterSet.name })
        assertTrue(sweep[1].totalPenalty >= sweep[0].totalPenalty)
        assertTrue(CaptureSafetyFailure.COMPLETED_DURING_TRACKING_LOSS in sweep[1].fixtures.single().score.failures)
    }

    @Test fun noMovementFixtureProducesNoMovementFailureWithoutFalsePositive() {
        val frames = (0L..700L step 100).map(::pose)
        val fixture = PoseReplayFixture(
            sequenceId = "cue-no-movement",
            frames = frames,
            armTimestampMs = 200,
            cueTimestampMs = 300,
            labels = PoseSequenceLabels(),
        )
        val result = MotionReplayRunner().run(fixture, parameters)
        assertEquals(MotionSegmentFailure.NO_MOVEMENT_TIMEOUT, result.failure)
        assertTrue(CaptureSafetyFailure.FALSE_POSITIVE !in result.score.failures)
    }

    private fun multiBurstFixture(): PoseReplayFixture {
        val offsets = mapOf(300L to 0.3f, 400L to 0.3f, 500L to 0.3f, 600L to 0.3f, 700L to 0.6f)
        val frames = (0L..1_000L step 100).map { timestamp ->
            pose(timestamp, wristOffset = offsets[timestamp] ?: 0f)
        }
        return PoseReplayFixture(
            sequenceId = "synthetic-multi-burst",
            frames = frames,
            armTimestampMs = 200,
            cueTimestampMs = 300,
            expectedEndPoseRelationship = EndPoseRelationship.SAME_AS_START,
            labels = PoseSequenceLabels(
                movementStartTimestampMs = 300,
                movementEndTimestampMs = 800,
                terminalStableInterval = ReplayInterval(900, 1_000),
                intermediateStablePlateaus = listOf(ReplayInterval(500, 600)),
            ),
            notes = listOf("committed synthetic fixture; no private media"),
        )
    }

    private fun pose(timestampMs: Long, wristOffset: Float = 0f): PoseFrame = PoseFrame(
        timestampMs,
        replayLandmarks.mapValues { (id, world) ->
            val adjusted = if (id == PoseLandmarkId.LEFT_WRIST) world + Point3(wristOffset, 0f, 0f) else world
            PoseLandmarkSample(
                position = Point3(0.5f + adjusted.x * 0.1f, 0.35f + (adjusted.y + 0.5f) * 0.1f, adjusted.z * 0.1f),
                worldPosition = adjusted,
                visibility = 0.9f,
                presence = 0.9f,
                source = LandmarkSource.OBSERVED,
            )
        },
    )

    private fun PoseFrame.withMissing(id: PoseLandmarkId) = copy(
        landmarks = landmarks + (id to PoseLandmarkSample(null)),
    )

    private fun transition(
        from: MotionSegmentState,
        to: MotionSegmentState,
        decision: Long,
        boundary: Long,
    ) = dk.lasse.karateanalyzer.observation.TransitionRecord(from, to, decision, boundary, "test")
}

private val replayLandmarks = mapOf(
    PoseLandmarkId.NOSE to Point3(0f, -1.7f, 0f),
    PoseLandmarkId.LEFT_EAR to Point3(-0.12f, -1.65f, 0f), PoseLandmarkId.RIGHT_EAR to Point3(0.12f, -1.65f, 0f),
    PoseLandmarkId.LEFT_SHOULDER to Point3(-0.25f, -1f, 0f), PoseLandmarkId.RIGHT_SHOULDER to Point3(0.25f, -1f, 0f),
    PoseLandmarkId.LEFT_ELBOW to Point3(-0.45f, -0.8f, 0f), PoseLandmarkId.RIGHT_ELBOW to Point3(0.45f, -0.8f, 0f),
    PoseLandmarkId.LEFT_WRIST to Point3(-0.65f, -0.5f, 0f), PoseLandmarkId.RIGHT_WRIST to Point3(0.65f, -0.5f, 0f),
    PoseLandmarkId.LEFT_HIP to Point3(-0.2f, 0f, 0f), PoseLandmarkId.RIGHT_HIP to Point3(0.2f, 0f, 0f),
    PoseLandmarkId.LEFT_KNEE to Point3(-0.25f, 1f, 0f), PoseLandmarkId.RIGHT_KNEE to Point3(0.25f, 1f, 0f),
    PoseLandmarkId.LEFT_ANKLE to Point3(-0.25f, 2f, 0f), PoseLandmarkId.RIGHT_ANKLE to Point3(0.25f, 2f, 0f),
)
