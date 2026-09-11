package dk.lasse.karateanalyzer.capture

import dk.lasse.karateanalyzer.core.LandmarkSource
import dk.lasse.karateanalyzer.core.Point3
import dk.lasse.karateanalyzer.core.PoseFrame
import dk.lasse.karateanalyzer.core.PoseLandmarkId
import dk.lasse.karateanalyzer.core.PoseLandmarkSample
import dk.lasse.karateanalyzer.observation.NonIncreasingTimestampException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PoseMotionObservationExtractorTest {
    @Test fun stillPoseProducesLowSeparatelyObservableMotionChannels() {
        val extractor = extractor()
        extractor.accept(pose(0))
        val result = extractor.accept(pose(100))
        assertEquals(0.0, assertNotNull(result.articulatedMotion), 0.0001)
        assertEquals(0.0, assertNotNull(result.imageSpaceMotion), 0.0001)
        assertEquals(MotionChannelStatus.VALID, result.diagnostics?.articulatedStatus)
        assertEquals(MotionChannelStatus.VALID, result.diagnostics?.imageSpaceStatus)
    }

    @Test fun oneArmCreatesStrongArticulatedMotionWhileOtherRegionsRemainQuiet() {
        val extractor = extractor()
        extractor.accept(pose(0))
        val result = extractor.accept(pose(100, worldChanges = mapOf(PoseLandmarkId.LEFT_WRIST to Point3(0.3f, 0f, 0f))))
        assertTrue(assertNotNull(result.articulatedMotion) > 0.5)
        val regions = assertNotNull(result.diagnostics).regions
        assertTrue(assertNotNull(regions[AnatomicalRegion.LEFT_ARM]?.robustMotion) > 1.0)
        assertEquals(0.0, assertNotNull(regions[AnatomicalRegion.RIGHT_ARM]?.robustMotion), 0.0001)
    }

    @Test fun wholeBodyImageTranslationDoesNotBecomeArticulatedMotion() {
        val extractor = extractor()
        extractor.accept(pose(0))
        val translated = extractor.accept(pose(100, imageTranslationX = 0.05f))
        assertEquals(0.0, assertNotNull(translated.articulatedMotion), 0.0001)
        assertTrue(assertNotNull(translated.imageSpaceMotion) > 1.0)
        assertTrue(assertNotNull(translated.diagnostics?.imageCenterTranslation) > 1.0)
        assertEquals(0.0, assertNotNull(translated.diagnostics?.imageScaleChange), 0.0001)
    }

    @Test fun centeredTowardCameraScaleChangeProducesImageSpaceMotion() {
        val extractor = extractor()
        extractor.accept(pose(0))
        val closer = extractor.accept(pose(100, imageScale = 1.2f))
        assertEquals(0.0, assertNotNull(closer.diagnostics?.imageCenterTranslation), 0.0001)
        assertTrue(assertNotNull(closer.diagnostics?.imageScaleChange) > 1.0)
        assertTrue(assertNotNull(closer.imageSpaceMotion) > 1.0)
    }

    @Test fun confidenceAtEitherEndpointReducesContribution() {
        val high = extractor().run {
            accept(pose(0))
            accept(pose(100, worldChanges = mapOf(PoseLandmarkId.LEFT_WRIST to Point3(0.3f, 0f, 0f))))
        }
        val lower = extractor(PoseMotionExtractorConfig(minimumLandmarkConfidence = 0.1)).run {
            accept(pose(0, confidences = mapOf(PoseLandmarkId.LEFT_WRIST to 0.2f)))
            accept(pose(100, worldChanges = mapOf(PoseLandmarkId.LEFT_WRIST to Point3(0.3f, 0f, 0f))))
        }
        assertTrue(assertNotNull(lower.articulatedMotion) < assertNotNull(high.articulatedMotion))
    }

    @Test fun missingLandmarkLowersCoverageInsteadOfBecomingQuietEvidence() {
        val extractor = extractor()
        extractor.accept(pose(0))
        val result = extractor.accept(pose(100, missing = setOf(PoseLandmarkId.LEFT_WRIST)))
        assertTrue(result.coverage < 0.7)
        val leftArm = assertNotNull(result.diagnostics).regions.getValue(AnatomicalRegion.LEFT_ARM)
        assertEquals(2, leftArm.usableLandmarkCount)
        assertTrue(leftArm.coverage < result.diagnostics!!.regions.getValue(AnatomicalRegion.RIGHT_ARM).coverage)
        assertEquals(MotionChannelStatus.INSUFFICIENT_COVERAGE, result.diagnostics!!.articulatedStatus)
        assertEquals(result.coverage, result.diagnostics!!.minimumRegionCoverage)
        assertTrue(result.diagnostics!!.regionBalancedCoverage > result.diagnostics!!.minimumRegionCoverage)
    }

    @Test fun oneWorldDepthSpikeIsClampedAndReported() {
        val extractor = extractor(PoseMotionExtractorConfig(maximumNormalizedSpeed = 1.0))
        extractor.accept(pose(0))
        val result = extractor.accept(pose(100, worldChanges = mapOf(PoseLandmarkId.LEFT_WRIST to Point3(0f, 0f, 100f))))
        val arm = assertNotNull(result.diagnostics).regions.getValue(AnatomicalRegion.LEFT_ARM)
        assertTrue(assertNotNull(arm.rawMotion) > 100.0)
        assertTrue(assertNotNull(arm.robustMotion) <= 1.0)
        assertEquals(1, arm.clampedLandmarkCount)
        assertTrue(assertNotNull(result.articulatedMotion) < 1.0)
    }

    @Test fun irregularTimestampsProduceEquivalentVelocity() {
        fun velocity(duration: Long, displacement: Float): Double {
            val extractor = extractor()
            extractor.accept(pose(0))
            return assertNotNull(extractor.accept(
                pose(duration, worldChanges = mapOf(PoseLandmarkId.LEFT_WRIST to Point3(displacement, 0f, 0f))),
            ).articulatedMotion)
        }
        assertEquals(velocity(100, 0.1f), velocity(250, 0.25f), 0.0001)
    }

    @Test fun largeTimestampGapIsUnknownRatherThanFakeQuiet() {
        val extractor = extractor(PoseMotionExtractorConfig(maximumFrameGapMs = 200))
        extractor.accept(pose(0))
        val result = extractor.accept(pose(500))
        assertNull(result.articulatedMotion)
        assertNull(result.imageSpaceMotion)
        assertEquals(MotionChannelStatus.TIMESTAMP_GAP, result.diagnostics?.articulatedStatus)
    }

    @Test fun regionBalancingPreventsDenseTorsoFromOutvotingMovingArm() {
        val extractor = extractor()
        extractor.accept(pose(0))
        val result = extractor.accept(pose(100, worldChanges = mapOf(PoseLandmarkId.LEFT_WRIST to Point3(0.3f, 0f, 0f))))
        val leftArm = assertNotNull(result.diagnostics).regions.getValue(AnatomicalRegion.LEFT_ARM)
        assertTrue(assertNotNull(result.articulatedMotion) >= assertNotNull(leftArm.robustMotion) / 3.0)
    }

    @Test fun baselineUsesMultipleSamplesAndSamePoseComparisonIgnoresTranslation() {
        val extractor = extractor(PoseMotionExtractorConfig(baselineRequiredSamples = 3))
        val first = extractor.accept(pose(0))
        val second = extractor.accept(pose(100))
        val third = extractor.accept(pose(200))
        assertEquals(1, first.diagnostics?.baselineSampleCount)
        assertEquals(2, second.diagnostics?.baselineSampleCount)
        assertTrue(third.diagnostics?.baselineReady == true)
        val shifted = extractor.accept(pose(300, imageTranslationX = 0.04f))
        assertTrue(assertNotNull(shifted.sameAsStartSimilarity) > 0.99)
        assertTrue(assertNotNull(shifted.imageSpaceMotion) > 0.0)
    }

    @Test fun swappedBodySidesMatchMirroredReferenceButNotSameReference() {
        val extractor = extractor(PoseMotionExtractorConfig(baselineRequiredSamples = 3))
        listOf(0L, 100L, 200L).forEach { extractor.accept(asymmetricPose(it)) }
        val result = extractor.accept(mirroredAsymmetricPose(300))
        assertTrue(assertNotNull(result.mirroredStartSimilarity) > 0.99)
        assertTrue(assertNotNull(result.sameAsStartSimilarity) < 0.9)
    }

    @Test fun nonMirroredAsymmetricPoseDoesNotFalselyMatchMirroredReference() {
        val extractor = extractor(PoseMotionExtractorConfig(baselineRequiredSamples = 3))
        listOf(0L, 100L, 200L).forEach { extractor.accept(asymmetricPose(it)) }
        val result = extractor.accept(asymmetricPose(300))
        assertTrue(assertNotNull(result.sameAsStartSimilarity) > 0.99)
        assertTrue(assertNotNull(result.mirroredStartSimilarity) < 0.9)
    }

    @Test fun slowDriftAccumulatesDespiteLowInstantaneousMotion() {
        val extractor = extractor(PoseMotionExtractorConfig(slowDisplacementWindowMs = 500))
        extractor.accept(pose(0))
        var result = extractor.accept(pose(100, worldChanges = mapOf(PoseLandmarkId.LEFT_WRIST to Point3(0.01f, 0f, 0f))))
        for (index in 2..5) {
            result = extractor.accept(pose(index * 100L, worldChanges = mapOf(PoseLandmarkId.LEFT_WRIST to Point3(index * 0.01f, 0f, 0f))))
        }
        assertTrue(assertNotNull(result.articulatedMotion) < 0.1)
        assertTrue(assertNotNull(result.accumulatedDisplacement) > 0.01)
    }

    @Test fun resetAndVersionedFixtureReplayAreDeterministic() {
        val fixture = PoseReplayFixture(frames = listOf(pose(0), pose(100), pose(200, imageTranslationX = 0.02f)))
        val extractor = extractor(PoseMotionExtractorConfig(baselineRequiredSamples = 3))
        val first = extractor.replay(fixture)
        val second = extractor.replay(fixture)
        assertEquals(first, second)
        assertEquals(PoseReplayFixture.SCHEMA_VERSION, fixture.schemaVersion)
        assertFailsWith<NonIncreasingTimestampException> {
            extractor.reset()
            extractor.accept(pose(100))
            extractor.accept(pose(100))
        }
    }

    @Test fun poseSequenceFlowsThroughExtractorAndSegmenter() {
        val extractor = extractor(PoseMotionExtractorConfig(baselineRequiredSamples = 3))
        val segmenter = GenericMotionSegmenter(GenericMotionSegmenterConfig(
            baselineDwellMs = 100,
            movementStartDwellMs = 100,
            settlingDwellMs = 100,
            startMotionThreshold = 0.2,
            quietMotionThreshold = 0.1,
            minimumCoverage = 0.7,
            terminalPoseSimilarity = 0.8,
            maximumStableDisplacement = 0.1,
        ))
        listOf(0L, 100L, 200L).forEach { segmenter.accept(extractor.accept(pose(it))) }
        segmenter.arm(210)
        segmenter.accept(extractor.accept(pose(300, worldChanges = mapOf(PoseLandmarkId.LEFT_WRIST to Point3(0.3f, 0f, 0f)))))
        segmenter.accept(extractor.accept(pose(400, worldChanges = mapOf(PoseLandmarkId.LEFT_WRIST to Point3(0.6f, 0f, 0f)))))
        segmenter.accept(extractor.accept(pose(500)))
        segmenter.accept(extractor.accept(pose(600)))
        val complete = segmenter.accept(extractor.accept(pose(700)))
        assertEquals(MotionSegmentState.COMPLETE, complete.state)
    }

    private fun extractor(config: PoseMotionExtractorConfig = PoseMotionExtractorConfig()) =
        PoseMotionObservationExtractor(config)

    private fun asymmetricPose(timestampMs: Long) = pose(
        timestampMs,
        worldChanges = mapOf(
            PoseLandmarkId.LEFT_WRIST to Point3(0.35f, -0.4f, 0f),
            PoseLandmarkId.RIGHT_WRIST to Point3(-0.15f, 0.2f, 0f),
        ),
    )

    private fun mirroredAsymmetricPose(timestampMs: Long): PoseFrame {
        val source = asymmetricPose(timestampMs)
        val pairs = listOf(
            PoseLandmarkId.LEFT_EAR to PoseLandmarkId.RIGHT_EAR,
            PoseLandmarkId.LEFT_SHOULDER to PoseLandmarkId.RIGHT_SHOULDER,
            PoseLandmarkId.LEFT_ELBOW to PoseLandmarkId.RIGHT_ELBOW,
            PoseLandmarkId.LEFT_WRIST to PoseLandmarkId.RIGHT_WRIST,
            PoseLandmarkId.LEFT_HIP to PoseLandmarkId.RIGHT_HIP,
            PoseLandmarkId.LEFT_KNEE to PoseLandmarkId.RIGHT_KNEE,
            PoseLandmarkId.LEFT_ANKLE to PoseLandmarkId.RIGHT_ANKLE,
        )
        val swapped = source.landmarks.toMutableMap()
        pairs.forEach { (left, right) ->
            swapped[left] = source.landmarks.getValue(right).reflectedLaterally()
            swapped[right] = source.landmarks.getValue(left).reflectedLaterally()
        }
        return source.copy(landmarks = swapped)
    }

    private fun PoseLandmarkSample.reflectedLaterally() = copy(
        position = position?.copy(x = 1f - position!!.x),
        worldPosition = worldPosition?.copy(x = -worldPosition!!.x),
    )

    private fun pose(
        timestampMs: Long,
        imageTranslationX: Float = 0f,
        imageScale: Float = 1f,
        worldChanges: Map<PoseLandmarkId, Point3> = emptyMap(),
        confidences: Map<PoseLandmarkId, Float> = emptyMap(),
        missing: Set<PoseLandmarkId> = emptySet(),
    ): PoseFrame {
        val world = baseWorld.toMutableMap()
        worldChanges.forEach { (id, delta) -> world[id] = world.getValue(id) + delta }
        return PoseFrame(timestampMs, testLandmarks.associateWith { id ->
            if (id in missing) PoseLandmarkSample(null)
            else {
                val point = world.getValue(id)
                val confidence = confidences[id] ?: 0.9f
                PoseLandmarkSample(
                    position = Point3(
                        0.5f + point.x * 0.1f * imageScale + imageTranslationX,
                        0.35f + (point.y + 0.5f) * 0.1f * imageScale,
                        point.z * 0.1f * imageScale,
                    ),
                    worldPosition = point,
                    visibility = confidence,
                    presence = confidence,
                    source = LandmarkSource.OBSERVED,
                )
            }
        })
    }
}

private val testLandmarks = listOf(
    PoseLandmarkId.NOSE, PoseLandmarkId.LEFT_EAR, PoseLandmarkId.RIGHT_EAR,
    PoseLandmarkId.LEFT_SHOULDER, PoseLandmarkId.RIGHT_SHOULDER,
    PoseLandmarkId.LEFT_ELBOW, PoseLandmarkId.RIGHT_ELBOW,
    PoseLandmarkId.LEFT_WRIST, PoseLandmarkId.RIGHT_WRIST,
    PoseLandmarkId.LEFT_HIP, PoseLandmarkId.RIGHT_HIP,
    PoseLandmarkId.LEFT_KNEE, PoseLandmarkId.RIGHT_KNEE,
    PoseLandmarkId.LEFT_ANKLE, PoseLandmarkId.RIGHT_ANKLE,
)

private val baseWorld = mapOf(
    PoseLandmarkId.NOSE to Point3(0f, -1.7f, 0f),
    PoseLandmarkId.LEFT_EAR to Point3(-0.12f, -1.65f, 0f),
    PoseLandmarkId.RIGHT_EAR to Point3(0.12f, -1.65f, 0f),
    PoseLandmarkId.LEFT_SHOULDER to Point3(-0.25f, -1f, 0f),
    PoseLandmarkId.RIGHT_SHOULDER to Point3(0.25f, -1f, 0f),
    PoseLandmarkId.LEFT_ELBOW to Point3(-0.45f, -0.8f, 0f),
    PoseLandmarkId.RIGHT_ELBOW to Point3(0.45f, -0.8f, 0f),
    PoseLandmarkId.LEFT_WRIST to Point3(-0.65f, -0.5f, 0f),
    PoseLandmarkId.RIGHT_WRIST to Point3(0.65f, -0.5f, 0f),
    PoseLandmarkId.LEFT_HIP to Point3(-0.2f, 0f, 0f),
    PoseLandmarkId.RIGHT_HIP to Point3(0.2f, 0f, 0f),
    PoseLandmarkId.LEFT_KNEE to Point3(-0.25f, 1f, 0f),
    PoseLandmarkId.RIGHT_KNEE to Point3(0.25f, 1f, 0f),
    PoseLandmarkId.LEFT_ANKLE to Point3(-0.25f, 2f, 0f),
    PoseLandmarkId.RIGHT_ANKLE to Point3(0.25f, 2f, 0f),
)
