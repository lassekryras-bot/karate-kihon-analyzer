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

class ContinuousSegmentationSafetyTest {
    private val genericConfig = GenericMotionSegmenterConfig(
        enableKinematicsPositiveEvidence = true,
        baselineDwellMs = 100,
        movementStartDwellMs = 100,
        settlingDwellMs = 100,
        noMovementTimeoutMs = null,
        startMotionThreshold = 0.50,
        quietMotionThreshold = 0.50,
        minimumCoverage = 0.70,
        maximumStableDisplacement = 0.05,
        endPoseRelationship = EndPoseRelationship.ANY_STABLE_POSE_AFTER_MOVEMENT,
    )

    private fun dummyKinematics(
        anyLimbMotion: Evidence = Evidence.FALSE,
        activeChannels: List<String> = emptyList(),
    ) = BodyKinematics(
        leftArm = ArmKinematics(),
        rightArm = ArmKinematics(),
        leftLeg = LegKinematics(),
        rightLeg = LegKinematics(),
        pelvis = PelvisKinematics(),
        positiveEvidence = PositiveMotionEvidence(
            leftArm = if (activeChannels.any { "LEFT_ARM" in it }) Evidence.TRUE else Evidence.FALSE,
            rightArm = if (activeChannels.any { "RIGHT_ARM" in it }) Evidence.TRUE else Evidence.FALSE,
            leftLeg = if (activeChannels.any { "LEFT_LEG" in it }) Evidence.TRUE else Evidence.FALSE,
            rightLeg = if (activeChannels.any { "RIGHT_LEG" in it }) Evidence.TRUE else Evidence.FALSE,
            pelvis = if (activeChannels.any { "PELVIS" in it }) Evidence.TRUE else Evidence.FALSE,
            anyLimbMotion = anyLimbMotion,
            triggeringChannels = activeChannels,
        ),
    )

    private fun observation(
        time: Long,
        motion: Double = 0.02,
        coverage: Double = 0.90,
        displacement: Double? = 0.02,
        sameAsStartSimilarity: Double? = null,
        mirroredStartSimilarity: Double? = null,
        kinematics: BodyKinematics? = null,
        diagnostics: MotionObservationDiagnostics? = null,
    ) = MotionObservation(
        timestampMs = time,
        articulatedMotion = motion,
        imageSpaceMotion = 0.02,
        coverage = coverage,
        sameAsStartSimilarity = sameAsStartSimilarity,
        mirroredStartSimilarity = mirroredStartSimilarity,
        accumulatedDisplacement = displacement,
        diagnostics = diagnostics,
        kinematics = kinematics,
    )

    @Test
    fun returnToStartCompletesUnderAnyStablePoseAfterMovement() {
        val segmenter = GenericMotionSegmenter(genericConfig)
        segmenter.accept(observation(0)); segmenter.accept(observation(100)); segmenter.arm(100)
        segmenter.accept(observation(125, motion = 1.0)); segmenter.accept(observation(225, motion = 1.0))
        assertEquals(MotionSegmentState.MOVING, segmenter.snapshot().state)

        // Terminal hold has 0.95 similarity to start (returning to initial stance)
        val obs1 = observation(250, motion = 0.02, sameAsStartSimilarity = 0.95)
        val obs2 = observation(350, motion = 0.02, sameAsStartSimilarity = 0.95)
        segmenter.accept(obs1)
        val snap = segmenter.accept(obs2)
        assertEquals(MotionSegmentState.COMPLETE, snap.state, "Returning to start stance must complete under ANY_STABLE_POSE_AFTER_MOVEMENT")
        assertEquals(250L, snap.transitions.last().estimatedBoundaryTimestampMs)
    }

    @Test
    fun differentStablePoseCompletesUnderAnyStablePoseAfterMovement() {
        val segmenter = GenericMotionSegmenter(genericConfig)
        segmenter.accept(observation(0)); segmenter.accept(observation(100)); segmenter.arm(100)
        segmenter.accept(observation(125, motion = 1.0)); segmenter.accept(observation(225, motion = 1.0))
        assertEquals(MotionSegmentState.MOVING, segmenter.snapshot().state)

        // Terminal hold has 0.50 similarity to start (distinct punch pose)
        val obs1 = observation(250, motion = 0.02, sameAsStartSimilarity = 0.50)
        val obs2 = observation(350, motion = 0.02, sameAsStartSimilarity = 0.50)
        segmenter.accept(obs1)
        val snap = segmenter.accept(obs2)
        assertEquals(MotionSegmentState.COMPLETE, snap.state, "Finishing in a different pose must complete under ANY_STABLE_POSE_AFTER_MOVEMENT")
    }

    @Test
    fun mirroredStablePoseCompletesWithoutConsultingSimilarity() {
        val segmenter = GenericMotionSegmenter(genericConfig)
        segmenter.accept(observation(0)); segmenter.accept(observation(100)); segmenter.arm(100)
        segmenter.accept(observation(125, motion = 1.0)); segmenter.accept(observation(225, motion = 1.0))
        assertEquals(MotionSegmentState.MOVING, segmenter.snapshot().state)

        // Missing/null similarities must be completely ignored
        val obs1 = observation(250, motion = 0.02, sameAsStartSimilarity = null, mirroredStartSimilarity = null)
        val obs2 = observation(350, motion = 0.02, sameAsStartSimilarity = null, mirroredStartSimilarity = null)
        segmenter.accept(obs1)
        val snap = segmenter.accept(obs2)
        assertEquals(MotionSegmentState.COMPLETE, snap.state, "Missing similarity must not block ANY_STABLE_POSE_AFTER_MOVEMENT")
    }

    @Test
    fun noMovementBeforeCompletionPreventsCompletion() {
        val segmenter = GenericMotionSegmenter(genericConfig)
        segmenter.accept(observation(0)); segmenter.accept(observation(100)); segmenter.arm(100)
        // Sustained stillness while in ARMED for 2 seconds
        for (t in 120L..2000L step 20L) {
            val snap = segmenter.accept(observation(t, motion = 0.02))
            assertEquals(MotionSegmentState.ARMED, snap.state, "Stillness in ARMED must never transition to MOVING or COMPLETE")
        }
    }

    @Test
    fun briefPlateauDoesNotCompleteAndResumesMoving() {
        val segmenter = GenericMotionSegmenter(genericConfig)
        segmenter.accept(observation(0)); segmenter.accept(observation(100)); segmenter.arm(100)
        segmenter.accept(observation(125, motion = 1.0)); segmenter.accept(observation(225, motion = 1.0))
        assertEquals(MotionSegmentState.MOVING, segmenter.snapshot().state)

        // 80 ms quiet pause (250 ms to 330 ms)
        segmenter.accept(observation(250, motion = 0.02))
        assertEquals(MotionSegmentState.SETTLING, segmenter.snapshot().state)
        segmenter.accept(observation(330, motion = 0.02))
        assertEquals(MotionSegmentState.SETTLING, segmenter.snapshot().state)

        // Movement resumes at 350 ms
        segmenter.accept(observation(350, motion = 1.0))
        val snap = segmenter.snapshot()
        assertEquals(MotionSegmentState.MOVING, snap.state, "Motion resumption before 100 ms must transition back to MOVING")
        assertTrue(snap.transitions.any { it.trigger == "movement_resumed" })
        assertTrue(snap.transitions.none { it.newState == MotionSegmentState.COMPLETE })
    }

    @Test
    fun slowDriftPreventsCompletion() {
        val segmenter = GenericMotionSegmenter(genericConfig)
        segmenter.accept(observation(0)); segmenter.accept(observation(100)); segmenter.arm(100)
        segmenter.accept(observation(125, motion = 1.0)); segmenter.accept(observation(225, motion = 1.0))

        // Motion is numerically low (0.02), but accumulated displacement is 0.08 (> 0.05 limit)
        val obs1 = observation(250, motion = 0.02, displacement = 0.08)
        val snap1 = segmenter.accept(obs1)
        assertEquals(Evidence.FALSE, snap1.latestQuietEvidence)

        val obs2 = observation(350, motion = 0.02, displacement = 0.08)
        val snap2 = segmenter.accept(obs2)
        assertEquals(Evidence.FALSE, snap2.latestQuietEvidence)
        assertFalse(snap2.state == MotionSegmentState.COMPLETE, "Slow drift must veto terminal stability")
    }

    @Test
    fun movingOptionalLimbVetoesCompletion() {
        val sideViewConfig = genericConfig.copy(
            requiredRegionsForTerminalStillness = setOf(AnatomicalRegion.TORSO, AnatomicalRegion.RIGHT_ARM)
        )
        val segmenter = GenericMotionSegmenter(sideViewConfig)
        segmenter.accept(observation(0)); segmenter.accept(observation(100)); segmenter.arm(100)
        segmenter.accept(observation(125, motion = 1.0)); segmenter.accept(observation(225, motion = 1.0))

        // Moving optional limb via kinematics positive evidence
        val movingKinematics = dummyKinematics(
            anyLimbMotion = Evidence.TRUE,
            activeChannels = listOf("LEFT_ARM_WRIST_SPEED"),
        )
        val obs = observation(250, motion = 0.02, kinematics = movingKinematics)
        val snap = segmenter.accept(obs)
        assertEquals(Evidence.FALSE, snap.latestQuietEvidence, "Moving optional limb must veto quiet evidence via positive-motion guardrail")
        assertEquals(MotionSegmentState.MOVING, snap.state)
    }

    @Test
    fun missingRequiredRegionBlocksCompletionAsUnknown() {
        val diag = MotionObservationDiagnostics(
            articulatedStatus = MotionChannelStatus.INSUFFICIENT_COVERAGE,
            imageSpaceStatus = MotionChannelStatus.VALID,
            elapsedMs = 25, bodyScaleWorld = 1.0, bodyScaleImage = 1.0,
            imageCenterTranslation = 0.01, imageScaleChange = 0.01,
            minimumRegionCoverage = 0.3, regionBalancedCoverage = 0.6,
            baselineSampleCount = 3, baselineReady = true,
            regions = mapOf(
                AnatomicalRegion.TORSO to RegionMotionDiagnostics(4, 4, 1.0, 0.02, 0.02, 0),
                AnatomicalRegion.LEFT_ARM to RegionMotionDiagnostics(3, 1, 0.3, 0.02, 0.02, 0),
                AnatomicalRegion.RIGHT_ARM to RegionMotionDiagnostics(3, 3, 1.0, 0.02, 0.02, 0),
            )
        )
        val segmenter = GenericMotionSegmenter(genericConfig)
        segmenter.accept(observation(0)); segmenter.accept(observation(100)); segmenter.arm(100)
        segmenter.accept(observation(125, motion = 1.0)); segmenter.accept(observation(225, motion = 1.0))

        val obs1 = observation(250, motion = 0.02, coverage = 0.3, diagnostics = diag)
        val obs2 = observation(350, motion = 0.02, coverage = 0.3, diagnostics = diag)
        segmenter.accept(obs1)
        val snap = segmenter.accept(obs2)
        assertEquals(Evidence.UNKNOWN, snap.latestQuietEvidence, "Missing required region must evaluate to UNKNOWN")
        assertFalse(snap.state == MotionSegmentState.COMPLETE, "UNKNOWN quiet evidence must never complete")
    }

    @Test
    fun rearmAfterCompletionEstablishesNewBaselineWithoutDeadTime() {
        val segmenter = GenericMotionSegmenter(genericConfig)
        segmenter.accept(observation(0)); segmenter.accept(observation(100)); segmenter.arm(100)
        segmenter.accept(observation(125, motion = 1.0)); segmenter.accept(observation(225, motion = 1.0))
        segmenter.accept(observation(250, motion = 0.02)); segmenter.accept(observation(350, motion = 0.02))
        assertEquals(MotionSegmentState.COMPLETE, segmenter.snapshot().state)

        // Rearm immediately at 350 ms
        val rearmedSnap = segmenter.rearmAfterCompletion(350)
        assertEquals(MotionSegmentState.ARMED, rearmedSnap.state)
        assertTrue(rearmedSnap.baselineReady)

        // Movement 2 starts 20 ms later (370 ms)
        segmenter.accept(observation(370, motion = 1.0))
        val movingSnap = segmenter.accept(observation(470, motion = 1.0))
        assertEquals(MotionSegmentState.MOVING, movingSnap.state, "Movement 2 must be detected immediately without redundant baseline dwell")
        assertEquals(370L, movingSnap.transitions.last().estimatedBoundaryTimestampMs)
    }

    @Test
    fun pauseOf80msResumesMovingAsOneSegment() {
        val segmenter = GenericMotionSegmenter(genericConfig)
        segmenter.accept(observation(0)); segmenter.accept(observation(100)); segmenter.arm(100)
        segmenter.accept(observation(125, motion = 1.0)); segmenter.accept(observation(225, motion = 1.0))
        assertEquals(MotionSegmentState.MOVING, segmenter.snapshot().state)

        // 80 ms quiet pause (250 ms to 330 ms)
        segmenter.accept(observation(250, motion = 0.02))
        assertEquals(MotionSegmentState.SETTLING, segmenter.snapshot().state)
        segmenter.accept(observation(330, motion = 0.02))
        assertEquals(MotionSegmentState.SETTLING, segmenter.snapshot().state)

        // Movement resumes at 350 ms (before 100 ms settling dwell fulfills)
        segmenter.accept(observation(350, motion = 1.0))
        val snap = segmenter.snapshot()
        assertEquals(MotionSegmentState.MOVING, snap.state, "An 80 ms pause must resume moving without completing")
        assertTrue(snap.transitions.any { it.trigger == "movement_resumed" })
        assertTrue(snap.transitions.none { it.newState == MotionSegmentState.COMPLETE })
    }

    @Test
    fun pauseOf120msCompletesAndRearmsAsTwoSegments() {
        val segmenter = GenericMotionSegmenter(genericConfig)
        // Movement 1
        segmenter.accept(observation(0)); segmenter.accept(observation(100)); segmenter.arm(100)
        segmenter.accept(observation(125, motion = 1.0)); segmenter.accept(observation(225, motion = 1.0))
        assertEquals(MotionSegmentState.MOVING, segmenter.snapshot().state)

        // 120 ms quiet pause: 250 ms to 370 ms
        segmenter.accept(observation(250, motion = 0.02))
        assertEquals(MotionSegmentState.SETTLING, segmenter.snapshot().state)
        // At 350 ms (100 ms settling dwell fulfilled), Movement 1 completes!
        val completeSnap1 = segmenter.accept(observation(350, motion = 0.02))
        assertEquals(MotionSegmentState.COMPLETE, completeSnap1.state, "A 120 ms pause (>100 ms dwell) must complete Movement 1 at 100 ms")
        assertEquals(250L, completeSnap1.transitions.last().estimatedBoundaryTimestampMs)

        // Rearm seamlessly from confirmed stable terminal pose
        val rearmedSnap = segmenter.rearmAfterCompletion(350)
        assertEquals(MotionSegmentState.ARMED, rearmedSnap.state)

        // Movement 2 begins at 370 ms (20 ms after rearm)
        segmenter.accept(observation(370, motion = 1.0))
        val movingSnap2 = segmenter.accept(observation(470, motion = 1.0))
        assertEquals(MotionSegmentState.MOVING, movingSnap2.state, "Movement 2 must be detected after rearming")
        assertEquals(370L, movingSnap2.transitions.last().estimatedBoundaryTimestampMs)

        // Movement 2 completes after its own 120 ms pause
        segmenter.accept(observation(500, motion = 0.02))
        val completeSnap2 = segmenter.accept(observation(600, motion = 0.02))
        assertEquals(MotionSegmentState.COMPLETE, completeSnap2.state, "Movement 2 must complete independently")
    }

    @Test
    fun twoOverlappingLimbActionsWithNo100msStabilityRemainOneSegment() {
        val segmenter = GenericMotionSegmenter(genericConfig)
        segmenter.accept(observation(0)); segmenter.accept(observation(100)); segmenter.arm(100)
        // Right arm punch starts
        val rightArmKinematics = dummyKinematics(
            anyLimbMotion = Evidence.TRUE,
            activeChannels = listOf("RIGHT_ARM_WRIST_SPEED")
        )
        segmenter.accept(observation(125, motion = 1.0, kinematics = rightArmKinematics))
        segmenter.accept(observation(225, motion = 1.0, kinematics = rightArmKinematics))
        assertEquals(MotionSegmentState.MOVING, segmenter.snapshot().state)

        // Right arm finishes at 250 ms, but left arm punch starts 40 ms later at 290 ms
        segmenter.accept(observation(250, motion = 0.02)) // Brief quiet candidate
        assertEquals(MotionSegmentState.SETTLING, segmenter.snapshot().state)

        // Left arm moves at 290 ms (< 100 ms settling dwell)
        val leftArmKinematics = dummyKinematics(
            anyLimbMotion = Evidence.TRUE,
            activeChannels = listOf("LEFT_ARM_WRIST_SPEED")
        )
        val snap = segmenter.accept(observation(290, motion = 0.8, kinematics = leftArmKinematics))
        assertEquals(MotionSegmentState.MOVING, snap.state, "Overlapping limb actions must resume MOVING")
        assertTrue(snap.transitions.none { it.newState == MotionSegmentState.COMPLETE })
    }

    @Test
    fun stableTorsoWithMovingArmStaysMoving() {
        val movingArmKinematics = dummyKinematics(
            anyLimbMotion = Evidence.TRUE,
            activeChannels = listOf("RIGHT_ARM_WRIST_SPEED", "RIGHT_ARM_ELBOW_ANGULAR_VEL"),
        )
        val segmenter = GenericMotionSegmenter(genericConfig)
        segmenter.accept(observation(0)); segmenter.accept(observation(100)); segmenter.arm(100)
        segmenter.accept(observation(125, motion = 1.0)); segmenter.accept(observation(225, motion = 1.0))

        // Torso motion is quiet (0.10), but arm is moving rapidly
        val snap = segmenter.accept(observation(250, motion = 0.10, kinematics = movingArmKinematics))
        assertEquals(Evidence.FALSE, snap.latestQuietEvidence)
        assertEquals(MotionSegmentState.MOVING, snap.state)
    }

    @Test
    fun stableArmsWithMovingLegStaysMoving() {
        val movingLegKinematics = dummyKinematics(
            anyLimbMotion = Evidence.TRUE,
            activeChannels = listOf("RIGHT_LEG_ANKLE_SPEED"),
        )
        val segmenter = GenericMotionSegmenter(genericConfig)
        segmenter.accept(observation(0)); segmenter.accept(observation(100)); segmenter.arm(100)
        segmenter.accept(observation(125, motion = 1.0)); segmenter.accept(observation(225, motion = 1.0))

        val snap = segmenter.accept(observation(250, motion = 0.10, kinematics = movingLegKinematics))
        assertEquals(Evidence.FALSE, snap.latestQuietEvidence)
        assertEquals(MotionSegmentState.MOVING, snap.state)
    }

    @Test
    fun repeatedMovementSequenceWithUnknownCountCompletesAndRearmsRepeatedly() {
        val frames = mutableListOf<PoseFrame>()
        var t = 0L
        // Initial baseline: 0 to 160 ms (still)
        for (i in 0..8) { frames += makeFrame(t, wristX = 0.2f); t += 20L }

        // 3 consecutive movements with 460 ms quiet holds between them
        // (460 ms allows 300 ms displacement window to settle + 100 ms settling dwell to confirm)
        for (rep in 1..3) {
            // Movement burst: 140 ms of motion
            val startX = if (rep % 2 == 1) 0.2f else 0.8f
            val endX = if (rep % 2 == 1) 0.8f else 0.2f
            for (step in 1..7) {
                t += 20L
                val x = startX + (endX - startX) * (step / 7.0f)
                frames += makeFrame(t, wristX = x)
            }
            // Stable hold: 460 ms of stillness (> 300 ms window + 100 ms settling dwell)
            for (step in 1..23) {
                t += 20L
                frames += makeFrame(t, wristX = endX)
            }
        }

        val fixture = PoseReplayFixture(
            sequenceId = "test-multi-repetition",
            frames = frames,
            expectedEndPoseRelationship = EndPoseRelationship.ANY_STABLE_POSE_AFTER_MOVEMENT,
        )

        val controller = ContinuousMotionController(ContinuousMotionControllerConfig())

        val result = controller.run(fixture)
        assertEquals(3, result.detectedMovementCount, "Controller must discover all 3 movements and rearm seamlessly")
    }

    private fun makeFrame(timestampMs: Long, wristX: Float): PoseFrame {
        val landmarks = mutableMapOf<PoseLandmarkId, PoseLandmarkSample>()
        fun add(id: PoseLandmarkId, x: Float, y: Float, z: Float = 0f, conf: Double = 0.95) {
            landmarks[id] = PoseLandmarkSample(
                position = Point3(x, y, z),
                worldPosition = Point3(x, y, z),
                visibility = conf.toFloat(),
                presence = conf.toFloat(),
                source = LandmarkSource.OBSERVED,
            )
        }
        // Torso
        add(PoseLandmarkId.LEFT_SHOULDER, -0.2f, 0.5f)
        add(PoseLandmarkId.RIGHT_SHOULDER, 0.2f, 0.5f)
        add(PoseLandmarkId.LEFT_HIP, -0.2f, -0.5f)
        add(PoseLandmarkId.RIGHT_HIP, 0.2f, -0.5f)

        // Arms
        add(PoseLandmarkId.RIGHT_ELBOW, (0.2f + wristX) / 2f, 0.4f)
        add(PoseLandmarkId.RIGHT_WRIST, wristX, 0.3f)
        add(PoseLandmarkId.LEFT_ELBOW, -0.3f, 0.4f)
        add(PoseLandmarkId.LEFT_WRIST, -0.4f, 0.3f)

        // Legs
        add(PoseLandmarkId.RIGHT_KNEE, 0.2f, -0.9f)
        add(PoseLandmarkId.RIGHT_ANKLE, 0.2f, -1.3f)
        add(PoseLandmarkId.LEFT_KNEE, -0.2f, -0.9f)
        add(PoseLandmarkId.LEFT_ANKLE, -0.2f, -1.3f)

        return PoseFrame(
            timestampMs = timestampMs,
            landmarks = landmarks,
        )
    }
}
