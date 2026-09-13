package dk.lasse.karateanalyzer.capture

import dk.lasse.karateanalyzer.core.LandmarkSource
import dk.lasse.karateanalyzer.core.Point3
import dk.lasse.karateanalyzer.core.PoseFrame
import dk.lasse.karateanalyzer.core.PoseLandmarkId
import dk.lasse.karateanalyzer.core.PoseLandmarkSample
import dk.lasse.karateanalyzer.observation.Evidence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** General evidence checks for experimental values; no video-specific expected frames. */
class MotionCalibrationSafetyTest {
    private val config = GenericMotionSegmenterConfig(
        baselineDwellMs = 100, movementStartDwellMs = 100, settlingDwellMs = 100,
        startMotionThreshold = .5, quietMotionThreshold = .5,
        minimumCoverage = .7, maximumStableDisplacement = .05,
        endPoseRelationship = EndPoseRelationship.DIFFERENT_STABLE_POSE,
        enableKinematicsPositiveEvidence = false,
    )

    private fun observation(time: Long, motion: Double = .3, coverage: Double = .9, displacement: Double? = .03) =
        MotionObservation(time, motion, .04, coverage, .5, .9, displacement)

    @Test fun sustainedNoisyHoldEstablishesBaselineButCoverageLossResetsIt() {
        for (coverage in listOf(.9, .6)) {
            val segmenter = GenericMotionSegmenter(config)
            listOf(0L, 25L, 50L, 75L, 100L).forEach { segmenter.accept(observation(it, coverage = coverage)) }
            assertEquals(coverage >= .7, segmenter.snapshot().baselineReady)
            if (coverage < .7) assertEquals(Evidence.UNKNOWN, segmenter.snapshot().latestQuietEvidence)
        }
    }

    @Test fun briefQuietInsideMovementCannotCompleteButSustainedTerminalEvidenceCan() {
        val segmenter = GenericMotionSegmenter(config)
        segmenter.accept(observation(0)); segmenter.accept(observation(100)); segmenter.arm(100)
        segmenter.accept(observation(125, motion = 1.0)); segmenter.accept(observation(225, motion = 1.0))
        segmenter.accept(observation(250)); segmenter.accept(observation(300))
        assertEquals(MotionSegmentState.SETTLING, segmenter.snapshot().state)
        segmenter.accept(observation(325, motion = 1.0))
        assertEquals(MotionSegmentState.MOVING, segmenter.snapshot().state)
        segmenter.accept(observation(350)); segmenter.accept(observation(450))
        assertEquals(MotionSegmentState.COMPLETE, segmenter.snapshot().state)
        assertEquals(350L, segmenter.snapshot().transitions.last().estimatedBoundaryTimestampMs)
    }

    @Test fun slowDisplacementAndMissingDisplacementNeverProveStillness() {
        for (displacement in listOf(.06, null)) {
            val segmenter = GenericMotionSegmenter(config)
            segmenter.accept(observation(0, displacement = displacement))
            segmenter.accept(observation(100, displacement = displacement))
            assertFalse(segmenter.snapshot().baselineReady)
            assertEquals(if (displacement == null) Evidence.UNKNOWN else Evidence.FALSE, segmenter.snapshot().latestQuietEvidence)
        }
    }

    @Test fun farSideLossCanSupportPositiveMovementButCannotProveTerminalStillness() {
        val segmenter = GenericMotionSegmenter(config)
        segmenter.accept(observation(0)); segmenter.accept(observation(100)); segmenter.arm(100)
        segmenter.accept(observation(125, motion = 1.0, coverage = .6))
        segmenter.accept(observation(225, motion = 1.0, coverage = .6))
        assertEquals(MotionSegmentState.MOVING, segmenter.snapshot().state)
        segmenter.accept(observation(250, coverage = .6)); segmenter.accept(observation(450, coverage = .6))
        assertEquals(Evidence.UNKNOWN, segmenter.snapshot().latestQuietEvidence)
        assertTrue(segmenter.snapshot().transitions.none { it.newState == MotionSegmentState.COMPLETE })
    }

    @Test fun shorteningDisplacementMemoryNeedsAPairedLimitToRetainSlowDriftProtection() {
        for ((window, limit) in listOf(600L to .1, 300L to .1, 300L to .05)) {
            val result = MotionReplayRunner().run(slowDriftFixture(), MotionReplayParameterSet(
                "synthetic-window-comparison",
                PoseMotionExtractorConfig(baselineRequiredSamples = 3, baselineMaximumArticulatedMotion = .3,
                    slowDisplacementWindowMs = window),
                config.copy(maximumStableDisplacement = limit),
            ))
            val boundary = result.transitions.first { it.newState == MotionSegmentState.COMPLETE }.estimatedBoundaryTimestampMs!!
            // The unpaired short window is a measured unsafe control, not a recommended config.
            if (window == 300L && limit == .1) assertTrue(boundary < 2_000)
            else assertTrue(boundary >= 2_000, "Continuous drift must finish before terminal boundary")
        }
    }

    @Test fun extractorReferenceAcceptsBoundedNoiseButNotAnUncertainLimb() {
        val base = slowDriftFixture().frames.first()
        for (uncertain in listOf(false, true)) {
            val extractor = PoseMotionObservationExtractor(PoseMotionExtractorConfig(
                baselineRequiredSamples = 3, baselineMaximumArticulatedMotion = .3,
            ))
            val observations = (0..10).map { index ->
                val frame = base.copy(timestampMs = index * 50L, landmarks = base.landmarks.mapValues { (id, sample) ->
                    val offset = if (id.name.endsWith("HIP") || id.name.endsWith("SHOULDER")) 0f else
                        if (index % 2 == 0) .008f else -.008f
                    sample.copy(worldPosition = sample.worldPosition!! + Point3(offset, 0f, 0f),
                        position = sample.position!! + Point3(offset * .1f, 0f, 0f),
                        visibility = if (uncertain && id == PoseLandmarkId.LEFT_WRIST) .3f else .95f)
                })
                extractor.accept(frame)
            }
            assertEquals(!uncertain, observations.last().diagnostics!!.baselineReady)
            assertTrue(observations.last().articulatedMotion!! > .12)
            assertTrue(observations.last().articulatedMotion!! < .3)
        }
    }

    private fun fullRegions(
        overrides: Map<AnatomicalRegion, RegionMotionDiagnostics> = emptyMap()
    ): Map<AnatomicalRegion, RegionMotionDiagnostics> =
        AnatomicalRegion.entries.associateWith { RegionMotionDiagnostics(3, 3, 1.0, 0.02, 0.02, 0) } + overrides

    @Test fun oneArmMovingWhileOtherStopsPreventsCompletion() {
        val segmenter = GenericMotionSegmenter(config)
        segmenter.accept(observation(0)); segmenter.accept(observation(100)); segmenter.arm(100)
        segmenter.accept(observation(125, motion = 1.0)); segmenter.accept(observation(225, motion = 1.0))
        assertEquals(MotionSegmentState.MOVING, segmenter.snapshot().state)
        val diag = MotionObservationDiagnostics(
            articulatedStatus = MotionChannelStatus.VALID, imageSpaceStatus = MotionChannelStatus.VALID,
            elapsedMs = 25, bodyScaleWorld = 1.0, bodyScaleImage = 1.0, imageCenterTranslation = 0.01,
            imageScaleChange = 0.01, minimumRegionCoverage = 0.9, regionBalancedCoverage = 0.9,
            baselineSampleCount = 3, baselineReady = true,
            regions = fullRegions(mapOf(
                AnatomicalRegion.RIGHT_ARM to RegionMotionDiagnostics(3, 3, 1.0, 1.2, 1.2, 0),
            ))
        )
        val obs = MotionObservation(250, articulatedMotion = 0.8, imageSpaceMotion = 0.02, coverage = 0.9, sameAsStartSimilarity = 0.5, accumulatedDisplacement = 0.02, diagnostics = diag)
        val snap = segmenter.accept(obs)
        assertEquals(Evidence.FALSE, snap.latestQuietEvidence)
        assertEquals(MotionSegmentState.MOVING, snap.state)
    }

    @Test fun torsoStoppedWhileLegContinuesMovingPreventsCompletion() {
        val segmenter = GenericMotionSegmenter(config)
        segmenter.accept(observation(0)); segmenter.accept(observation(100)); segmenter.arm(100)
        segmenter.accept(observation(125, motion = 1.0)); segmenter.accept(observation(225, motion = 1.0))
        val diag = MotionObservationDiagnostics(
            articulatedStatus = MotionChannelStatus.VALID, imageSpaceStatus = MotionChannelStatus.VALID,
            elapsedMs = 25, bodyScaleWorld = 1.0, bodyScaleImage = 1.0, imageCenterTranslation = 0.01,
            imageScaleChange = 0.01, minimumRegionCoverage = 0.9, regionBalancedCoverage = 0.9,
            baselineSampleCount = 3, baselineReady = true,
            regions = fullRegions(mapOf(
                AnatomicalRegion.LEFT_LEG to RegionMotionDiagnostics(3, 3, 1.0, 1.5, 1.5, 0),
            ))
        )
        val obs = MotionObservation(250, articulatedMotion = 0.9, imageSpaceMotion = 0.02, coverage = 0.9, sameAsStartSimilarity = 0.5, accumulatedDisplacement = 0.02, diagnostics = diag)
        assertEquals(Evidence.FALSE, segmenter.accept(obs).latestQuietEvidence)
    }

    @Test fun requiredRegionStableWhileOptionalRegionMovingPreventsCompletion() {
        val sideViewConfig = config.copy(
            requiredRegionsForTerminalStillness = setOf(AnatomicalRegion.TORSO, AnatomicalRegion.RIGHT_ARM)
        )
        val segmenter = GenericMotionSegmenter(sideViewConfig)
        segmenter.accept(observation(0)); segmenter.accept(observation(100)); segmenter.arm(100)
        segmenter.accept(observation(125, motion = 1.0)); segmenter.accept(observation(225, motion = 1.0))
        val diag = MotionObservationDiagnostics(
            articulatedStatus = MotionChannelStatus.VALID, imageSpaceStatus = MotionChannelStatus.VALID,
            elapsedMs = 25, bodyScaleWorld = 1.0, bodyScaleImage = 1.0, imageCenterTranslation = 0.01,
            imageScaleChange = 0.01, minimumRegionCoverage = 0.9, regionBalancedCoverage = 0.9,
            baselineSampleCount = 3, baselineReady = true,
            regions = mapOf(
                AnatomicalRegion.TORSO to RegionMotionDiagnostics(4, 4, 1.0, 0.02, 0.02, 0),
                AnatomicalRegion.RIGHT_ARM to RegionMotionDiagnostics(3, 3, 1.0, 0.02, 0.02, 0),
                AnatomicalRegion.LEFT_ARM to RegionMotionDiagnostics(3, 3, 1.0, 1.2, 1.2, 0),
            )
        )
        val obs = MotionObservation(250, articulatedMotion = 0.7, imageSpaceMotion = 0.02, coverage = 0.9, sameAsStartSimilarity = 0.5, accumulatedDisplacement = 0.02, diagnostics = diag)
        val snap = segmenter.accept(obs)
        assertEquals(Evidence.FALSE, snap.latestQuietEvidence, "Moving optional limb must force quiet evidence to FALSE")
        assertEquals(MotionSegmentState.MOVING, snap.state)
    }

    @Test fun optionalRegionMissingEntirelyAllowsCompletionWhenRequiredRegionsAreStable() {
        val sideViewConfig = config.copy(
            requiredRegionsForTerminalStillness = setOf(AnatomicalRegion.TORSO, AnatomicalRegion.RIGHT_ARM)
        )
        val segmenter = GenericMotionSegmenter(sideViewConfig)
        segmenter.accept(observation(0)); segmenter.accept(observation(100)); segmenter.arm(100)
        segmenter.accept(observation(125, motion = 1.0)); segmenter.accept(observation(225, motion = 1.0))
        val diag = MotionObservationDiagnostics(
            articulatedStatus = MotionChannelStatus.VALID, imageSpaceStatus = MotionChannelStatus.VALID,
            elapsedMs = 25, bodyScaleWorld = 1.0, bodyScaleImage = 1.0, imageCenterTranslation = 0.01,
            imageScaleChange = 0.01, minimumRegionCoverage = 0.0, regionBalancedCoverage = 0.7,
            baselineSampleCount = 3, baselineReady = true,
            regions = mapOf(
                AnatomicalRegion.TORSO to RegionMotionDiagnostics(4, 4, 1.0, 0.02, 0.02, 0),
                AnatomicalRegion.RIGHT_ARM to RegionMotionDiagnostics(3, 3, 1.0, 0.02, 0.02, 0),
                AnatomicalRegion.LEFT_ARM to RegionMotionDiagnostics(3, 0, 0.0, null, null, 0),
            )
        )
        val obs = MotionObservation(250, articulatedMotion = 0.02, imageSpaceMotion = 0.02, coverage = 0.0, sameAsStartSimilarity = 0.5, accumulatedDisplacement = 0.02, diagnostics = diag)
        assertEquals(Evidence.TRUE, segmenter.accept(obs).latestQuietEvidence, "Unobserved optional limb must not block completion when required regions are quiet")
        val obs2 = obs.copy(timestampMs = 350)
        assertEquals(MotionSegmentState.COMPLETE, segmenter.accept(obs2).state)
    }

    @Test fun lowConfidenceRequiredLimbNeverBecomesQuietEvenIfMotionIsNumericallyLow() {
        val segmenter = GenericMotionSegmenter(config)
        segmenter.accept(observation(0)); segmenter.accept(observation(100)); segmenter.arm(100)
        segmenter.accept(observation(125, motion = 1.0)); segmenter.accept(observation(225, motion = 1.0))
        val diag = MotionObservationDiagnostics(
            articulatedStatus = MotionChannelStatus.INSUFFICIENT_COVERAGE, imageSpaceStatus = MotionChannelStatus.VALID,
            elapsedMs = 25, bodyScaleWorld = 1.0, bodyScaleImage = 1.0, imageCenterTranslation = 0.01,
            imageScaleChange = 0.01, minimumRegionCoverage = 0.3, regionBalancedCoverage = 0.6,
            baselineSampleCount = 3, baselineReady = true,
            regions = mapOf(
                AnatomicalRegion.TORSO to RegionMotionDiagnostics(4, 4, 1.0, 0.02, 0.02, 0),
                AnatomicalRegion.LEFT_ARM to RegionMotionDiagnostics(3, 1, 0.3, 0.02, 0.02, 0),
                AnatomicalRegion.RIGHT_ARM to RegionMotionDiagnostics(3, 3, 1.0, 0.02, 0.02, 0),
            )
        )
        val obs = MotionObservation(250, articulatedMotion = 0.02, imageSpaceMotion = 0.02, coverage = 0.3, sameAsStartSimilarity = 0.5, accumulatedDisplacement = 0.02, diagnostics = diag)
        assertEquals(Evidence.UNKNOWN, segmenter.accept(obs).latestQuietEvidence, "Low confidence required limb must remain UNKNOWN")
    }

    @Test fun differentStablePoseRejectsReturnToInitialPose() {
        val segmenter = GenericMotionSegmenter(config)
        segmenter.accept(observation(0)); segmenter.accept(observation(100)); segmenter.arm(100)
        segmenter.accept(observation(125, motion = 1.0)); segmenter.accept(observation(225, motion = 1.0))
        val obs1 = MotionObservation(250, 0.02, 0.02, 0.9, sameAsStartSimilarity = 0.92, accumulatedDisplacement = 0.02)
        val obs2 = MotionObservation(350, 0.02, 0.02, 0.9, sameAsStartSimilarity = 0.92, accumulatedDisplacement = 0.02)
        segmenter.accept(obs1)
        val snap = segmenter.accept(obs2)
        assertEquals(MotionSegmentState.SETTLING, snap.state, "Accidental return to start must not complete under DIFFERENT_STABLE_POSE")
        assertTrue(snap.transitions.none { it.newState == MotionSegmentState.COMPLETE })
    }

    @Test fun differentStablePoseCompletesOnDistinctStablePose() {
        val segmenter = GenericMotionSegmenter(config)
        segmenter.accept(observation(0)); segmenter.accept(observation(100)); segmenter.arm(100)
        segmenter.accept(observation(125, motion = 1.0)); segmenter.accept(observation(225, motion = 1.0))
        val obs1 = MotionObservation(250, 0.02, 0.02, 0.9, sameAsStartSimilarity = 0.50, accumulatedDisplacement = 0.02)
        val obs2 = MotionObservation(350, 0.02, 0.02, 0.9, sameAsStartSimilarity = 0.50, accumulatedDisplacement = 0.02)
        segmenter.accept(obs1)
        val snap = segmenter.accept(obs2)
        assertEquals(MotionSegmentState.COMPLETE, snap.state)
    }

    @Test fun mirroredPoseDistinguishesExactFromApproximateHold() {
        val mirrorConfig = config.copy(endPoseRelationship = EndPoseRelationship.MIRRORED_START)
        for ((sim, shouldComplete) in listOf(0.95 to true, 0.70 to false)) {
            val segmenter = GenericMotionSegmenter(mirrorConfig)
            segmenter.accept(observation(0)); segmenter.accept(observation(100)); segmenter.arm(100)
            segmenter.accept(observation(125, motion = 1.0)); segmenter.accept(observation(225, motion = 1.0))
            segmenter.accept(MotionObservation(250, 0.02, 0.02, 0.9, mirroredStartSimilarity = sim, accumulatedDisplacement = 0.02))
            val snap = segmenter.accept(MotionObservation(350, 0.02, 0.02, 0.9, mirroredStartSimilarity = sim, accumulatedDisplacement = 0.02))
            assertEquals(shouldComplete, snap.state == MotionSegmentState.COMPLETE, "Mirrored similarity $sim completion check")
        }
    }

    @Test fun temporaryCoverageRecoveryDuringOngoingMovementStaysMoving() {
        val segmenter = GenericMotionSegmenter(config)
        segmenter.accept(observation(0)); segmenter.accept(observation(100)); segmenter.arm(100)
        segmenter.accept(observation(125, motion = 1.0, coverage = 0.5))
        segmenter.accept(observation(225, motion = 1.0, coverage = 0.5))
        assertEquals(MotionSegmentState.MOVING, segmenter.snapshot().state)
        val snap = segmenter.accept(observation(250, motion = 1.5, coverage = 0.95))
        assertEquals(MotionSegmentState.MOVING, snap.state)
        assertEquals(Evidence.TRUE, snap.latestMotionEvidence)
        assertEquals(Evidence.FALSE, snap.latestQuietEvidence)
    }

    private fun slowDriftFixture(): PoseReplayFixture {
        val points = mutableMapOf(PoseLandmarkId.NOSE to Point3(0f, -1.7f, 0f))
        for ((side, sign) in listOf("LEFT" to -1f, "RIGHT" to 1f)) {
            for ((joint, point) in mapOf(
                "EAR" to Point3(.12f, -1.65f, 0f), "SHOULDER" to Point3(.25f, -1f, 0f),
                "ELBOW" to Point3(.45f, -.8f, 0f), "WRIST" to Point3(.65f, -.5f, 0f),
                "HIP" to Point3(.2f, 0f, 0f), "KNEE" to Point3(.25f, 1f, 0f), "ANKLE" to Point3(.25f, 2f, 0f),
            )) points[PoseLandmarkId.valueOf("${side}_$joint")] = point.copy(x = point.x * sign)
        }
        val frames = (0L..3_000L step 50).map { time ->
            val offset = if (time < 400) 0f else
                minOf(1f, (time - 350) / 250f) + maxOf(0L, minOf(time, 2_000) - 600) / 1_000f * .3f
            PoseFrame(time, points.mapValues { (id, base) ->
                val torso = id.name.endsWith("HIP") || id.name.endsWith("SHOULDER")
                val p = base.copy(x = base.x + if (torso) 0f else offset)
                PoseLandmarkSample(Point3(.5f + p.x * .1f, .35f + (p.y + .5f) * .1f, p.z * .1f),
                    p, .95f, .95f, LandmarkSource.OBSERVED)
            })
        }
        return PoseReplayFixture(frames = frames, armTimestampMs = 0,
            expectedEndPoseRelationship = EndPoseRelationship.DIFFERENT_STABLE_POSE)
    }
}
