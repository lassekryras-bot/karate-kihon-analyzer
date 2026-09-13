package dk.lasse.karateanalyzer.capture

import dk.lasse.karateanalyzer.core.LandmarkSource
import dk.lasse.karateanalyzer.core.Point3
import dk.lasse.karateanalyzer.core.PoseFrame
import dk.lasse.karateanalyzer.core.PoseLandmarkId
import dk.lasse.karateanalyzer.core.PoseLandmarkSample
import dk.lasse.karateanalyzer.observation.Evidence
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LimbKinematicsSafetyTest {

    private val extractorConfig = PoseMotionExtractorConfig(
        minimumLandmarkConfidence = 0.50,
        baselineRequiredSamples = 3,
        baselineMaximumArticulatedMotion = 0.30,
        kinematics = KinematicsConfig(
            enabled = true,
            windowDurationMs = 100L,
            minimumSamples = 3,
            minimumWindowSpanRatio = 0.60,
            minimumLandmarkConfidence = 0.50,
            endpointSpeedThreshold = 0.35,
            radialSpeedThreshold = 0.25,
            jointAngularVelocityThreshold = 35.0,
            segmentOrientationRateThreshold = 35.0,
            pelvisYawRateThreshold = 18.0,
            pelvisRotationRateThreshold = 30.0,
        ),
    )

    private val segmenterConfig = GenericMotionSegmenterConfig(
        baselineDwellMs = 100L,
        movementStartDwellMs = 80L,
        settlingDwellMs = 100L,
        startMotionThreshold = 0.50,
        quietMotionThreshold = 0.50,
        minimumCoverage = 0.70,
        maximumStableDisplacement = 0.05,
        enableKinematicsPositiveEvidence = true,
    )

    private fun baseHumanoidPoints(): MutableMap<PoseLandmarkId, Point3> {
        val points = mutableMapOf(PoseLandmarkId.NOSE to Point3(0f, -1.7f, 0f))
        for ((side, sign) in listOf("LEFT" to -1f, "RIGHT" to 1f)) {
            for ((joint, point) in mapOf(
                "EAR" to Point3(0.12f, -1.65f, 0f),
                "SHOULDER" to Point3(0.25f, -1.0f, 0f),
                "ELBOW" to Point3(0.40f, -0.6f, 0f),
                "WRIST" to Point3(0.45f, -0.2f, 0f),
                "HIP" to Point3(0.20f, 0.0f, 0f),
                "KNEE" to Point3(0.22f, 0.5f, 0f),
                "ANKLE" to Point3(0.22f, 1.0f, 0f),
            )) {
                points[PoseLandmarkId.valueOf("${side}_$joint")] = point.copy(x = point.x * sign)
            }
        }
        return points
    }

    private fun makeFrame(
        timestampMs: Long,
        points: Map<PoseLandmarkId, Point3>,
        confidence: Float = 0.95f,
        confidenceOverrides: Map<PoseLandmarkId, Float> = emptyMap(),
    ): PoseFrame {
        val landmarks = points.mapValues { (id, p) ->
            val conf = confidenceOverrides[id] ?: confidence
            PoseLandmarkSample(
                position = Point3(0.5f + p.x * 0.1f, 0.5f + p.y * 0.1f, p.z * 0.1f),
                worldPosition = p,
                visibility = conf,
                presence = conf,
                source = LandmarkSource.OBSERVED,
            )
        }
        return PoseFrame(timestampMs, landmarks)
    }

    private fun feedMotion(
        extractor: PoseMotionObservationExtractor,
        startMs: Long,
        endMs: Long,
        stepMs: Long = 33L,
        modifyPose: (Float, MutableMap<PoseLandmarkId, Point3>) -> Unit,
    ): MotionObservation {
        var lastObs: MotionObservation? = null
        var t = startMs
        while (t <= endMs) {
            val progress = if (endMs == startMs) 0f else (t - startMs).toFloat() / (endMs - startMs).toFloat()
            val points = baseHumanoidPoints()
            modifyPose(progress, points)
            lastObs = extractor.accept(makeFrame(t, points))
            t += stepMs
        }
        return checkNotNull(lastObs)
    }

    // --- Core Biomechanical & Kinematic Tests (Tests 1-15) ---

    @Test
    fun test1_isolatedWristExtension() {
        val extractor = PoseMotionObservationExtractor(extractorConfig)
        val obs = feedMotion(extractor, 0L, 100L, 33L) { progress, points ->
            // Extend left wrist outward rapidly by 0.5 torso lengths over 100 ms (5.0 torso/s)
            points[PoseLandmarkId.LEFT_WRIST] = points[PoseLandmarkId.LEFT_WRIST]!! + Point3(-0.5f * progress, 0f, 0.3f * progress)
        }

        val kin = obs.kinematics
        assertNotNull(kin)
        assertTrue(kin.leftArm.wristTorsoRelativeSpeed!! >= 0.35, "Wrist speed should exceed threshold")
        assertTrue(kin.leftArm.wristRadialVelocity!! > 0.0, "Radial velocity should be positive (extending)")
        assertEquals(Evidence.TRUE, kin.positiveEvidence.leftArm)
        assertEquals(Evidence.TRUE, kin.positiveEvidence.anyLimbMotion)
        assertTrue(kin.positiveEvidence.triggeringChannels.contains("LEFT_ARM_WRIST_SPEED"))
    }

    @Test
    fun test2_isolatedElbowFlexionExtension() {
        val extractor = PoseMotionObservationExtractor(extractorConfig)
        val obs = feedMotion(extractor, 0L, 100L, 33L) { progress, points ->
            points[PoseLandmarkId.LEFT_SHOULDER] = Point3(-0.25f, -1.0f, 0f)
            points[PoseLandmarkId.LEFT_ELBOW] = Point3(-0.25f, -0.6f, 0f)
            val angle = Math.toRadians(160.0 - 70.0 * progress) // Flex elbow 70 deg forward over 100 ms (700 deg/s)
            val forearmLen = 0.4f
            points[PoseLandmarkId.LEFT_WRIST] = Point3(
                -0.25f,
                (-0.6f + forearmLen * cos(angle)).toFloat(),
                (forearmLen * sin(angle)).toFloat(),
            )
        }

        val kin = obs.kinematics
        assertNotNull(kin)
        assertTrue(kin.leftArm.elbowAngularVelocityDegPerSec!! >= 35.0, "Elbow angular velocity should exceed threshold")
        assertEquals(Evidence.TRUE, kin.positiveEvidence.leftArm)
        assertTrue(kin.positiveEvidence.triggeringChannels.contains("LEFT_ARM_ELBOW_VELOCITY"))
    }

    @Test
    fun test3_isolatedShoulderDrivenArmRotation() {
        val extractor = PoseMotionObservationExtractor(extractorConfig)
        val obs = feedMotion(extractor, 0L, 100L, 33L) { progress, points ->
            // Rotate straight arm 45 degrees outward around shoulder over 100 ms (450 deg/s)
            val angle = Math.toRadians(45.0 * progress)
            val armLen1 = 0.4f
            val armLen2 = 0.8f
            val shoulder = Point3(-0.25f, -1.0f, 0f)
            points[PoseLandmarkId.LEFT_SHOULDER] = shoulder
            points[PoseLandmarkId.LEFT_ELBOW] = Point3(
                (shoulder.x - armLen1 * sin(angle)).toFloat(),
                (shoulder.y + armLen1 * cos(angle)).toFloat(),
                0f,
            )
            points[PoseLandmarkId.LEFT_WRIST] = Point3(
                (shoulder.x - armLen2 * sin(angle)).toFloat(),
                (shoulder.y + armLen2 * cos(angle)).toFloat(),
                0f,
            )
        }

        val kin = obs.kinematics
        assertNotNull(kin)
        assertTrue(kin.leftArm.upperArmOrientationChangeDegPerSec!! >= 35.0)
        assertEquals(Evidence.TRUE, kin.positiveEvidence.leftArm)
    }

    @Test
    fun test4_isolatedAnkleMovement() {
        val extractor = PoseMotionObservationExtractor(extractorConfig)
        val obs = feedMotion(extractor, 0L, 100L, 33L) { progress, points ->
            // Move left ankle rapidly forward/outward
            points[PoseLandmarkId.LEFT_ANKLE] = points[PoseLandmarkId.LEFT_ANKLE]!! + Point3(0f, -0.2f * progress, 0.4f * progress)
        }

        val kin = obs.kinematics
        assertNotNull(kin)
        assertTrue(kin.leftLeg.ankleTorsoRelativeSpeed!! >= 0.35)
        assertEquals(Evidence.TRUE, kin.positiveEvidence.leftLeg)
        assertTrue(kin.positiveEvidence.triggeringChannels.contains("LEFT_LEG_ANKLE_SPEED"))
    }

    @Test
    fun test5_kneeChamberWithLittleAnkleRadialExtension() {
        val extractor = PoseMotionObservationExtractor(extractorConfig)
        val obs = feedMotion(extractor, 0L, 100L, 33L) { progress, points ->
            // Chamber: bend knee upward, flexing knee from 180 to 90 degrees
            points[PoseLandmarkId.RIGHT_KNEE] = Point3(0.22f, 0.5f - 0.4f * progress, 0.35f * progress)
            points[PoseLandmarkId.RIGHT_ANKLE] = Point3(0.22f, 1.0f - 0.55f * progress, 0.1f * progress)
        }

        val kin = obs.kinematics
        assertNotNull(kin)
        assertTrue(kin.rightLeg.kneeAngularVelocityDegPerSec!! >= 35.0)
        assertEquals(Evidence.TRUE, kin.positiveEvidence.rightLeg)
        assertTrue(kin.positiveEvidence.triggeringChannels.contains("RIGHT_LEG_KNEE_VELOCITY"))
    }

    @Test
    fun test6_kneeStrikeLikeMotionTowardTorso() {
        val extractor = PoseMotionObservationExtractor(extractorConfig)
        val obs = feedMotion(extractor, 0L, 100L, 33L) { progress, points ->
            // Knee drives rapidly upward toward torso
            points[PoseLandmarkId.LEFT_KNEE] = Point3(-0.22f, 0.5f - 0.7f * progress, 0.3f * progress)
        }

        val kin = obs.kinematics
        assertNotNull(kin)
        assertTrue(kin.leftLeg.kneeTorsoRelativeSpeed!! >= 0.35)
        assertTrue(kin.leftLeg.kneeRadialVelocity!! < 0.0, "Radial velocity toward torso should be negative")
        assertEquals(Evidence.TRUE, kin.positiveEvidence.leftLeg)
    }

    @Test
    fun test7_legExtensionAfterChamber() {
        val extractor = PoseMotionObservationExtractor(extractorConfig)
        val obs = feedMotion(extractor, 0L, 100L, 33L) { progress, points ->
            points[PoseLandmarkId.LEFT_KNEE] = Point3(-0.22f, 0.1f, 0.35f)
            points[PoseLandmarkId.LEFT_ANKLE] = Point3(-0.22f, 0.45f + 0.3f * progress, 0.1f + 0.6f * progress)
        }

        val kin = obs.kinematics
        assertNotNull(kin)
        assertTrue(kin.leftLeg.kneeAngularVelocityDegPerSec!! >= 35.0)
        assertEquals(Evidence.TRUE, kin.positiveEvidence.leftLeg)
    }

    @Test
    fun test8_pelvisRotationWithStationaryLimbs() {
        val extractor = PoseMotionObservationExtractor(extractorConfig)
        val obs = feedMotion(extractor, 0L, 100L, 33L) { progress, points ->
            // Rotate pelvis in transverse plane (yaw) by 20 degrees over 100 ms (200 deg/s >= 18 deg/s)
            val angle = Math.toRadians(20.0 * progress)
            val hipWidth = 0.20f
            points[PoseLandmarkId.LEFT_HIP] = Point3((-hipWidth * cos(angle)).toFloat(), 0f, (-hipWidth * sin(angle)).toFloat())
            points[PoseLandmarkId.RIGHT_HIP] = Point3((hipWidth * cos(angle)).toFloat(), 0f, (hipWidth * sin(angle)).toFloat())
        }

        val kin = obs.kinematics
        assertNotNull(kin)
        assertTrue(kin.pelvis.yawRotationRateDegPerSec!! >= 18.0)
        assertEquals(Evidence.TRUE, kin.positiveEvidence.pelvis)
        assertTrue(kin.positiveEvidence.triggeringChannels.contains("PELVIS_ROTATION_YAW"))
    }

    @Test
    fun test9_cameraTranslationWithLittleAnatomicalChange() {
        val extractor = PoseMotionObservationExtractor(extractorConfig)
        val translation = Point3(0.5f, -0.3f, 0.2f)
        val obs = feedMotion(extractor, 0L, 100L, 33L) { progress, points ->
            for (key in points.keys.toList()) {
                points[key] = points[key]!! + translation * progress
            }
        }

        val kin = obs.kinematics
        assertNotNull(kin)
        // Because RelativePose normalizes relative to hipCenter, torso-relative speeds must be near zero
        assertTrue(kin.leftArm.wristTorsoRelativeSpeed!! < 0.10)
        assertTrue(kin.rightArm.wristTorsoRelativeSpeed!! < 0.10)
        assertTrue(kin.leftLeg.ankleTorsoRelativeSpeed!! < 0.10)
        assertEquals(Evidence.FALSE, kin.positiveEvidence.anyLimbMotion)
    }

    @Test
    fun test10_staticPoseWithLandmarkJitter() {
        val extractor = PoseMotionObservationExtractor(extractorConfig)
        val obs = feedMotion(extractor, 0L, 100L, 33L) { progress, points ->
            // Tiny jitter (2mm)
            val sign = if (progress < 0.5f) 1f else -1f
            points[PoseLandmarkId.LEFT_WRIST] = points[PoseLandmarkId.LEFT_WRIST]!! + Point3(0.002f * sign, -0.001f * sign, 0.002f * sign)
            points[PoseLandmarkId.RIGHT_ANKLE] = points[PoseLandmarkId.RIGHT_ANKLE]!! + Point3(-0.002f * sign, 0.002f * sign, 0.0f)
        }

        val kin = obs.kinematics
        assertNotNull(kin)
        assertEquals(Evidence.FALSE, kin.positiveEvidence.anyLimbMotion)
        assertTrue(kin.positiveEvidence.triggeringChannels.isEmpty())
    }

    @Test
    fun test11_lowConfidenceWristSpike() {
        val extractor = PoseMotionObservationExtractor(extractorConfig)
        val p1 = baseHumanoidPoints()
        extractor.accept(makeFrame(0L, p1))
        extractor.accept(makeFrame(33L, p1))
        extractor.accept(makeFrame(66L, p1))

        val p2 = baseHumanoidPoints()
        // Massive wrist displacement but low confidence (0.20 < 0.50)
        p2[PoseLandmarkId.LEFT_WRIST] = p1[PoseLandmarkId.LEFT_WRIST]!! + Point3(2.0f, 2.0f, 2.0f)
        val obs = extractor.accept(makeFrame(100L, p2, confidenceOverrides = mapOf(PoseLandmarkId.LEFT_WRIST to 0.20f)))

        val kin = obs.kinematics
        assertNotNull(kin)
        assertNull(kin.leftArm.wristTorsoRelativeSpeed, "Low confidence landmark speed must be null")
        assertFalse(kin.positiveEvidence.triggeringChannels.contains("LEFT_ARM_WRIST_SPEED"))
        assertEquals(Evidence.UNKNOWN, kin.leftArm.motionEvidence)
    }

    @Test
    fun test12_lowConfidenceKneeSpike() {
        val extractor = PoseMotionObservationExtractor(extractorConfig)
        val p1 = baseHumanoidPoints()
        extractor.accept(makeFrame(0L, p1))
        extractor.accept(makeFrame(33L, p1))
        extractor.accept(makeFrame(66L, p1))

        val p2 = baseHumanoidPoints()
        // Knee displacement with low confidence (0.15 < 0.50)
        p2[PoseLandmarkId.LEFT_KNEE] = p1[PoseLandmarkId.LEFT_KNEE]!! + Point3(1.5f, 1.5f, 1.5f)
        val obs = extractor.accept(makeFrame(100L, p2, confidenceOverrides = mapOf(PoseLandmarkId.LEFT_KNEE to 0.15f)))

        val kin = obs.kinematics
        assertNotNull(kin)
        assertNull(kin.leftLeg.kneeTorsoRelativeSpeed, "Low confidence knee speed must be null")
        assertEquals(Evidence.UNKNOWN, kin.leftLeg.motionEvidence)
    }

    @Test
    fun test13_slowSustainedLimbMovement() {
        val segmenter = GenericMotionSegmenter(segmenterConfig)
        listOf(0L, 25L, 50L, 75L, 100L).forEach { t ->
            segmenter.accept(MotionObservation(t, articulatedMotion = 0.1, imageSpaceMotion = 0.1, coverage = 0.9, accumulatedDisplacement = 0.01))
        }
        segmenter.arm(100L)

        // Slow sustained motion: speeds below threshold, but displacement accumulates
        var snap = segmenter.accept(MotionObservation(150L, articulatedMotion = 0.2, imageSpaceMotion = 0.2, coverage = 0.9, accumulatedDisplacement = 0.02))
        assertEquals(MotionSegmentState.ARMED, snap.state)

        // Once displacement exceeds 0.05, quietEvidence is FALSE
        snap = segmenter.accept(MotionObservation(300L, articulatedMotion = 0.2, imageSpaceMotion = 0.2, coverage = 0.9, accumulatedDisplacement = 0.08))
        assertEquals(Evidence.FALSE, snap.latestQuietEvidence)
    }

    @Test
    fun test14_shortSharpMovementFollowedByQuiet() {
        val segmenter = GenericMotionSegmenter(segmenterConfig)
        listOf(0L, 25L, 50L, 75L, 100L).forEach { t ->
            segmenter.accept(MotionObservation(t, articulatedMotion = 0.1, imageSpaceMotion = 0.1, coverage = 0.9, accumulatedDisplacement = 0.01))
        }
        segmenter.arm(100L)

        // Fast strike triggers MOVING
        segmenter.accept(MotionObservation(125L, articulatedMotion = 1.2, imageSpaceMotion = 0.5, coverage = 0.9, accumulatedDisplacement = 0.02))
        segmenter.accept(MotionObservation(225L, articulatedMotion = 1.0, imageSpaceMotion = 0.4, coverage = 0.9, accumulatedDisplacement = 0.04))
        assertEquals(MotionSegmentState.MOVING, segmenter.snapshot().state)

        // Holds quiet stillness: quietEvidence TRUE triggers SETTLING
        segmenter.accept(MotionObservation(250L, articulatedMotion = 0.1, imageSpaceMotion = 0.1, coverage = 0.9, accumulatedDisplacement = 0.03, sameAsStartSimilarity = 0.9))
        assertEquals(MotionSegmentState.SETTLING, segmenter.snapshot().state)

        // 100ms dwell completed -> COMPLETE
        segmenter.accept(MotionObservation(350L, articulatedMotion = 0.1, imageSpaceMotion = 0.1, coverage = 0.9, accumulatedDisplacement = 0.02, sameAsStartSimilarity = 0.9))
        assertEquals(MotionSegmentState.COMPLETE, segmenter.snapshot().state)
    }

    @Test
    fun test15_twoOverlappingLimbMovementsWithNoStableGap() {
        val segmenter = GenericMotionSegmenter(segmenterConfig)
        listOf(0L, 25L, 50L, 75L, 100L).forEach { t ->
            segmenter.accept(MotionObservation(t, articulatedMotion = 0.1, imageSpaceMotion = 0.1, coverage = 0.9, accumulatedDisplacement = 0.01))
        }
        segmenter.arm(100L)

        segmenter.accept(MotionObservation(150L, articulatedMotion = 1.0, imageSpaceMotion = 0.4, coverage = 0.9, accumulatedDisplacement = 0.03))
        segmenter.accept(MotionObservation(250L, articulatedMotion = 0.9, imageSpaceMotion = 0.4, coverage = 0.9, accumulatedDisplacement = 0.04))
        assertEquals(MotionSegmentState.MOVING, segmenter.snapshot().state)

        // Handoff: whole-body motion stays high
        segmenter.accept(MotionObservation(300L, articulatedMotion = 0.7, imageSpaceMotion = 0.3, coverage = 0.9, accumulatedDisplacement = 0.04))
        segmenter.accept(MotionObservation(350L, articulatedMotion = 1.1, imageSpaceMotion = 0.4, coverage = 0.9, accumulatedDisplacement = 0.05))

        assertEquals(MotionSegmentState.MOVING, segmenter.snapshot().state)
        assertFalse(segmenter.snapshot().transitions.any { it.newState == MotionSegmentState.SETTLING })
    }

    // --- Phase 7: Baseline Readiness & Dwell Protection ---

    @Test
    fun phase7_baselineReadinessAndDwellProtection_shortBurstNeverTriggersMoving() {
        val segmenter = GenericMotionSegmenter(segmenterConfig.copy(movementStartDwellMs = 80L))
        // Quiet baseline
        listOf(0L, 25L, 50L, 75L, 100L).forEach { t ->
            segmenter.accept(MotionObservation(t, articulatedMotion = 0.05, imageSpaceMotion = 0.05, coverage = 0.9, accumulatedDisplacement = 0.01))
        }
        assertTrue(segmenter.snapshot().baselineReady)
        segmenter.arm(100L)
        assertEquals(MotionSegmentState.ARMED, segmenter.snapshot().state)

        // Single burst lasting 60 ms (< 80 ms dwell threshold)
        segmenter.accept(MotionObservation(130L, articulatedMotion = 1.5, imageSpaceMotion = 0.8, coverage = 0.9, accumulatedDisplacement = 0.02))
        segmenter.accept(MotionObservation(160L, articulatedMotion = 1.5, imageSpaceMotion = 0.8, coverage = 0.9, accumulatedDisplacement = 0.03))
        // Motion subsides before reaching 80 ms dwell
        segmenter.accept(MotionObservation(170L, articulatedMotion = 0.05, imageSpaceMotion = 0.05, coverage = 0.9, accumulatedDisplacement = 0.03))
        segmenter.accept(MotionObservation(200L, articulatedMotion = 0.05, imageSpaceMotion = 0.05, coverage = 0.9, accumulatedDisplacement = 0.03))

        // State must remain ARMED without any false start transition
        assertEquals(MotionSegmentState.ARMED, segmenter.snapshot().state)
        assertFalse(segmenter.snapshot().transitions.any { it.newState == MotionSegmentState.MOVING })
    }

    @Test
    fun phase7_baselineFormationQuietStance() {
        val segmenter = GenericMotionSegmenter(segmenterConfig.copy(baselineDwellMs = 100L))
        listOf(0L, 33L, 66L).forEach { t ->
            val snap = segmenter.accept(MotionObservation(t, articulatedMotion = 0.02, imageSpaceMotion = 0.02, coverage = 0.9, accumulatedDisplacement = 0.01))
            assertFalse(snap.baselineReady, "Baseline should not be ready before dwell satisfied")
        }
        val snapReady = segmenter.accept(MotionObservation(100L, articulatedMotion = 0.02, imageSpaceMotion = 0.02, coverage = 0.9, accumulatedDisplacement = 0.01))
        assertTrue(snapReady.baselineReady, "Baseline should become ready after 100 ms quiet dwell")
    }

    // --- Phase 8: Strict Causality, Measured Threshold Latency, and Boundary Recovery ---

    @Test
    fun phase8_strictCausality() {
        val extractorA = PoseMotionObservationExtractor(extractorConfig)
        val extractorB = PoseMotionObservationExtractor(extractorConfig)

        // Extractor A processes frames up to 100 ms
        val p1 = baseHumanoidPoints()
        listOf(0L, 33L, 66L, 100L).forEach { t ->
            val frame = makeFrame(t, p1)
            extractorA.accept(frame)
            extractorB.accept(frame)
        }

        // Extractor A accepts moving frame at 133 ms
        val pMove1 = baseHumanoidPoints()
        pMove1[PoseLandmarkId.LEFT_WRIST] = p1[PoseLandmarkId.LEFT_WRIST]!! + Point3(-0.2f, 0f, 0.1f)
        val obsA = extractorA.accept(makeFrame(133L, pMove1))

        // Extractor B accepts moving frame at 133 ms, and then future frames at 166 ms and 200 ms
        val obsB = extractorB.accept(makeFrame(133L, pMove1))
        val pMove2 = baseHumanoidPoints()
        pMove2[PoseLandmarkId.LEFT_WRIST] = p1[PoseLandmarkId.LEFT_WRIST]!! + Point3(-0.4f, 0f, 0.2f)
        extractorB.accept(makeFrame(166L, pMove2))
        extractorB.accept(makeFrame(200L, pMove2))

        // The observation at 133 ms in Extractor A and Extractor B must be strictly identical
        assertEquals(obsA.articulatedMotion, obsB.articulatedMotion)
        assertEquals(obsA.kinematics?.leftArm?.wristTorsoRelativeSpeed, obsB.kinematics?.leftArm?.wristTorsoRelativeSpeed)
        assertEquals(obsA.kinematics?.positiveEvidence?.motionEvidence, obsB.kinematics?.positiveEvidence?.motionEvidence)
    }

    @Test
    fun phase8_measuredThresholdLatencyAndBoundaryRecovery() {
        val extractor = PoseMotionObservationExtractor(extractorConfig)
        val segmenter = GenericMotionSegmenter(segmenterConfig.copy(movementStartDwellMs = 80L))

        extractor.accept(makeFrame(-33L, baseHumanoidPoints()))
        // Establish quiet baseline
        listOf(0L, 20L, 40L, 60L, 80L, 100L).forEach { t ->
            val obs = extractor.accept(makeFrame(t, baseHumanoidPoints()))
            segmenter.accept(obs)
        }
        segmenter.arm(100L)

        // True physical onset begins at t = 100 ms
        val trueOnsetMs = 100L
        var movingTransitionTimestamp: Long? = null
        var estimatedBoundaryMs: Long? = null

        var t = 120L
        while (t <= 350L) {
            val progress = (t - trueOnsetMs).toFloat() / 200f
            val p = baseHumanoidPoints()
            p[PoseLandmarkId.LEFT_WRIST] = p[PoseLandmarkId.LEFT_WRIST]!! + Point3(-0.5f * progress, 0f, 0.3f * progress)
            val obs = extractor.accept(makeFrame(t, p))
            val snap = segmenter.accept(obs)
            if (snap.state == MotionSegmentState.MOVING && movingTransitionTimestamp == null) {
                movingTransitionTimestamp = t
                val trans = snap.transitions.first { it.newState == MotionSegmentState.MOVING }
                estimatedBoundaryMs = trans.estimatedBoundaryTimestampMs
            }
            t += 20L
        }

        assertNotNull(movingTransitionTimestamp, "Segmenter should transition to MOVING")
        val latency = movingTransitionTimestamp - trueOnsetMs
        // Causal latency to threshold crossing + 80 ms dwell must be within acceptable bound (< 150 ms)
        assertTrue(latency < 150L, "Threshold latency ($latency ms) should be bounded")
        // Estimated boundary timestamp must recover true onset within +-25 ms
        assertNotNull(estimatedBoundaryMs, "Estimated boundary timestamp should be set")
        val boundaryError = abs(estimatedBoundaryMs - trueOnsetMs)
        assertTrue(boundaryError <= 25L, "Boundary recovery error ($boundaryError ms) must be within +-25 ms")
    }

    // --- Phase 9: Slow-Motion Protection ---

    @Test
    fun phase9_slowMotionProtection_lockedJointTranslationAt040TorsoPerSec() {
        val extractor = PoseMotionObservationExtractor(extractorConfig)
        // Wrist translates outward at 0.40 torso/s while maintaining locked elbow angle (140.0 deg)
        val obs = feedMotion(extractor, 0L, 100L, 33L) { progress, points ->
            val translationX = -0.04f * progress
            points[PoseLandmarkId.LEFT_SHOULDER] = Point3(-0.25f + translationX, -1.0f, 0f)
            points[PoseLandmarkId.LEFT_ELBOW] = Point3(-0.40f + translationX, -0.6f, 0f)
            val angleRad = Math.toRadians(140.0)
            val forearmLen = 0.4f
            points[PoseLandmarkId.LEFT_WRIST] = Point3(
                (-0.40f + translationX - forearmLen * sin(angleRad)).toFloat(),
                (-0.6f + forearmLen * cos(angleRad)).toFloat(),
                0f,
            )
        }

        val kin = obs.kinematics
        assertNotNull(kin)
        // Elbow angular velocity should be near zero (angle is strictly locked)
        val elbowVel = kin.leftArm.elbowAngularVelocityDegPerSec ?: 0.0
        assertTrue(elbowVel < 35.0, "Elbow angular velocity must remain below threshold ($elbowVel)")
        // Wrist speed must cross 0.35 threshold (speed ~ 0.40 torso/s)
        val wristSpeed = kin.leftArm.wristTorsoRelativeSpeed
        assertNotNull(wristSpeed)
        assertTrue(wristSpeed >= 0.35, "Wrist speed ($wristSpeed) should cross 0.35 torso/s threshold")
        // Positive evidence must be TRUE, triggered by wrist speed
        assertEquals(Evidence.TRUE, kin.positiveEvidence.leftArm)
        assertTrue(
            kin.positiveEvidence.triggeringChannels.contains("LEFT_ARM_WRIST_SPEED") ||
                kin.positiveEvidence.triggeringChannels.contains("LEFT_ARM_RADIAL_VELOCITY"),
        )
    }

    @Test
    fun phase9_slowMotionProtection_slowJointRotationAt40DegPerSec() {
        val extractor = PoseMotionObservationExtractor(extractorConfig)
        val obs = feedMotion(extractor, 0L, 100L, 33L) { progress, points ->
            points[PoseLandmarkId.LEFT_SHOULDER] = Point3(-0.25f, -1.0f, 0f)
            points[PoseLandmarkId.LEFT_ELBOW] = Point3(-0.25f, -0.6f, 0f)
            val angle = Math.toRadians(160.0 - 4.0 * progress) // 40 deg/s rotation
            val forearmLen = 0.4f
            points[PoseLandmarkId.LEFT_WRIST] = Point3(
                -0.25f,
                (-0.6f + forearmLen * cos(angle)).toFloat(),
                (forearmLen * sin(angle)).toFloat(),
            )
        }

        val kin = obs.kinematics
        assertNotNull(kin)
        assertTrue(kin.leftArm.elbowAngularVelocityDegPerSec!! >= 35.0)
        assertEquals(Evidence.TRUE, kin.positiveEvidence.leftArm)
        assertTrue(kin.positiveEvidence.triggeringChannels.contains("LEFT_ARM_ELBOW_VELOCITY"))
    }

    @Test
    fun phase9_slowMotionProtection_slowPelvisRotationAt22DegPerSec() {
        val extractor = PoseMotionObservationExtractor(extractorConfig)
        val obs = feedMotion(extractor, 0L, 100L, 33L) { progress, points ->
            // Rotate hips in transverse plane at 22 deg/s (2.2 deg over 100 ms)
            val angle = Math.toRadians(2.2 * progress)
            val hipWidth = 0.20f
            points[PoseLandmarkId.LEFT_HIP] = Point3((-hipWidth * cos(angle)).toFloat(), 0f, (-hipWidth * sin(angle)).toFloat())
            points[PoseLandmarkId.RIGHT_HIP] = Point3((hipWidth * cos(angle)).toFloat(), 0f, (hipWidth * sin(angle)).toFloat())
        }

        val kin = obs.kinematics
        assertNotNull(kin)
        assertTrue(kin.pelvis.yawRotationRateDegPerSec!! >= 18.0)
        assertEquals(Evidence.TRUE, kin.positiveEvidence.pelvis)
        assertTrue(kin.positiveEvidence.triggeringChannels.contains("PELVIS_ROTATION_YAW"))
    }

    // --- Phase 10: Adversarial Noise Tests ---

    @Test
    fun phase10_adversarialHighFrequencyAlternatingJitter() {
        val extractor = PoseMotionObservationExtractor(extractorConfig)
        val obs = feedMotion(extractor, 0L, 200L, 33L) { progress, points ->
            // Alternating +3mm / -3mm noise on wrist
            val step = ((progress * 6f).toInt() % 2) * 2 - 1
            points[PoseLandmarkId.LEFT_WRIST] = points[PoseLandmarkId.LEFT_WRIST]!! + Point3(0.003f * step, 0f, 0f)
        }

        val kin = obs.kinematics
        assertNotNull(kin)
        // High-frequency alternating noise is smoothed out
        assertTrue(kin.leftArm.wristTorsoRelativeSpeed!! < 0.20, "Alternating jitter speed should be smoothed out")
        assertEquals(Evidence.FALSE, kin.positiveEvidence.anyLimbMotion)
    }

    @Test
    fun phase10_adversarialIsolatedHighConfidenceSpikeDwellProtection() {
        val segmenter = GenericMotionSegmenter(segmenterConfig.copy(movementStartDwellMs = 80L))
        listOf(0L, 25L, 50L, 75L, 100L).forEach { t ->
            segmenter.accept(MotionObservation(t, articulatedMotion = 0.05, imageSpaceMotion = 0.05, coverage = 0.9, accumulatedDisplacement = 0.01))
        }
        segmenter.arm(100L)

        // Single isolated spike frame at t = 125 ms
        segmenter.accept(MotionObservation(125L, articulatedMotion = 2.0, imageSpaceMotion = 1.0, coverage = 0.9, accumulatedDisplacement = 0.02))
        // Frame at 150 ms immediately returns to quiet
        segmenter.accept(MotionObservation(150L, articulatedMotion = 0.05, imageSpaceMotion = 0.05, coverage = 0.9, accumulatedDisplacement = 0.02))

        assertEquals(MotionSegmentState.ARMED, segmenter.snapshot().state)
    }

    @Test
    fun phase10_adversarialLandmarkDropoutTernaryUnknown() {
        val extractor = PoseMotionObservationExtractor(extractorConfig)
        val p1 = baseHumanoidPoints()
        listOf(0L, 33L, 66L).forEach { t -> extractor.accept(makeFrame(t, p1)) }

        // At 100 ms, left wrist is missing/dropped from the frame
        val pMissing = baseHumanoidPoints()
        pMissing.remove(PoseLandmarkId.LEFT_WRIST)
        val obs = extractor.accept(makeFrame(100L, pMissing))

        val kin = obs.kinematics
        assertNotNull(kin)
        assertNull(kin.leftArm.wristTorsoRelativeSpeed)
        assertEquals(Evidence.UNKNOWN, kin.leftArm.motionEvidence)
    }

    @Test
    fun phase10_adversarialSeamlessRearmZeroDeadTimePreservation() {
        val extractor = PoseMotionObservationExtractor(extractorConfig)
        val segmenter = GenericMotionSegmenter(
            segmenterConfig.copy(
                movementStartDwellMs = 80L,
                settlingDwellMs = 100L,
                endPoseRelationship = EndPoseRelationship.ANY_STABLE_POSE_AFTER_MOVEMENT,
            ),
        )

        extractor.accept(makeFrame(-33L, baseHumanoidPoints()))
        // Baseline
        listOf(0L, 33L, 66L, 100L).forEach { t ->
            segmenter.accept(extractor.accept(makeFrame(t, baseHumanoidPoints())))
        }
        segmenter.arm(100L)

        // Move 1: 133 ms to 266 ms (133 ms duration > 80 ms dwell)
        var t = 133L
        while (t <= 266L) {
            val progress = (t - 133f) / 133f
            val p = baseHumanoidPoints()
            p[PoseLandmarkId.LEFT_WRIST] = p[PoseLandmarkId.LEFT_WRIST]!! + Point3(-0.5f * progress, 0f, 0.3f * progress)
            segmenter.accept(extractor.accept(makeFrame(t, p)))
            t += 33L
        }
        assertEquals(MotionSegmentState.MOVING, segmenter.snapshot().state)

        // Terminal quiet: 300 ms to 1050 ms (clears 600 ms displacement window by 866 ms, 100 ms settling dwell fulfilled by 966 ms)
        val terminalPose = baseHumanoidPoints()
        terminalPose[PoseLandmarkId.LEFT_WRIST] = terminalPose[PoseLandmarkId.LEFT_WRIST]!! + Point3(-0.5f, 0f, 0.3f)
        while (t <= 1050L) {
            segmenter.accept(extractor.accept(makeFrame(t, terminalPose)))
            t += 33L
        }
        assertEquals(MotionSegmentState.COMPLETE, segmenter.snapshot().state)

        // Seamless rearm preserving confirmed terminal stable window
        extractor.rebaselineFromConfirmedStableWindow(900L, 1050L)
        segmenter.rearmAfterCompletion(1050L)
        assertEquals(MotionSegmentState.ARMED, segmenter.snapshot().state)

        // Immediately start Move 2 at t = 1083 ms
        val pMove2 = baseHumanoidPoints()
        pMove2[PoseLandmarkId.RIGHT_WRIST] = pMove2[PoseLandmarkId.RIGHT_WRIST]!! + Point3(0.2f, 0f, 0.1f)
        val obs2 = extractor.accept(makeFrame(1083L, pMove2))

        // Smoothed kinematics should be immediately evaluable (not null) because 900..1050 ms window was preserved
        assertNotNull(obs2.kinematics)
        assertNotNull(obs2.kinematics?.rightArm?.wristTorsoRelativeSpeed, "Right wrist speed must be evaluable without dead time")
    }

    @Test
    fun phase10_adversarialFullResetBufferWipe() {
        val extractor = PoseMotionObservationExtractor(extractorConfig)
        listOf(0L, 33L, 66L, 100L).forEach { t ->
            extractor.accept(makeFrame(t, baseHumanoidPoints()))
        }

        // Full reset
        extractor.reset()

        // First frame after full reset has only 1 sample, so window is insufficient
        val obs = extractor.accept(makeFrame(200L, baseHumanoidPoints()))
        assertNotNull(obs.kinematics)
        assertNull(obs.kinematics?.leftArm?.wristTorsoRelativeSpeed, "Buffer must be wiped after full reset")
    }

    // --- Phase 11: Frame-Rate Invariance ---

    @Test
    fun phase11_frameRateInvariance_60fps_49fps_30fps() {
        val rates = listOf(
            "60fps" to 16L,
            "49fps" to 20L,
            "30fps" to 33L,
        )

        for ((label, stepMs) in rates) {
            val extractor = PoseMotionObservationExtractor(extractorConfig)
            val obs = feedMotion(extractor, 0L, 100L, stepMs) { progress, points ->
                // Extend left wrist at 1.0 torso/s
                points[PoseLandmarkId.LEFT_WRIST] = points[PoseLandmarkId.LEFT_WRIST]!! + Point3(-0.1f * progress, 0f, 0f)
            }
            val speed = obs.kinematics?.leftArm?.wristTorsoRelativeSpeed
            assertNotNull(speed, "Wrist speed must be non-null for $label")
            assertTrue(speed in 0.80..1.20, "Expected speed ~1.0 for $label but was $speed")
            assertEquals(Evidence.TRUE, obs.kinematics?.positiveEvidence?.leftArm, "Positive evidence must be TRUE for $label")
        }
    }

    // --- Phase 14: Compound Movements ---

    @Test
    fun phase14_compoundMovements_80msQuietGapRemainsSingleSegment() {
        val segmenter = GenericMotionSegmenter(segmenterConfig.copy(settlingDwellMs = 100L))
        listOf(0L, 25L, 50L, 75L, 100L).forEach { t ->
            segmenter.accept(MotionObservation(t, articulatedMotion = 0.05, imageSpaceMotion = 0.05, coverage = 0.9, accumulatedDisplacement = 0.01))
        }
        segmenter.arm(100L)

        // Strike 1 (sustained for 90 ms > 80 ms dwell)
        segmenter.accept(MotionObservation(130L, articulatedMotion = 1.0, imageSpaceMotion = 0.5, coverage = 0.9, accumulatedDisplacement = 0.03))
        segmenter.accept(MotionObservation(170L, articulatedMotion = 0.9, imageSpaceMotion = 0.4, coverage = 0.9, accumulatedDisplacement = 0.04))
        segmenter.accept(MotionObservation(220L, articulatedMotion = 0.9, imageSpaceMotion = 0.4, coverage = 0.9, accumulatedDisplacement = 0.04))
        assertEquals(MotionSegmentState.MOVING, segmenter.snapshot().state)

        // 80 ms quiet gap (sub-dwell: 100 ms required for completion)
        segmenter.accept(MotionObservation(240L, articulatedMotion = 0.05, imageSpaceMotion = 0.05, coverage = 0.9, accumulatedDisplacement = 0.04))
        segmenter.accept(MotionObservation(280L, articulatedMotion = 0.05, imageSpaceMotion = 0.05, coverage = 0.9, accumulatedDisplacement = 0.04))
        segmenter.accept(MotionObservation(320L, articulatedMotion = 0.05, imageSpaceMotion = 0.05, coverage = 0.9, accumulatedDisplacement = 0.04))
        // Still in SETTLING or MOVING, NOT complete
        assertTrue(segmenter.snapshot().state == MotionSegmentState.SETTLING || segmenter.snapshot().state == MotionSegmentState.MOVING)

        // Strike 2 begins before 100 ms settling dwell finishes
        segmenter.accept(MotionObservation(340L, articulatedMotion = 1.2, imageSpaceMotion = 0.6, coverage = 0.9, accumulatedDisplacement = 0.05))
        assertEquals(MotionSegmentState.MOVING, segmenter.snapshot().state)
        assertFalse(segmenter.snapshot().transitions.any { it.newState == MotionSegmentState.COMPLETE })
    }

    @Test
    fun phase14_compoundMovements_150msQuietGapProducesTwoSegments() {
        val segmenter = GenericMotionSegmenter(segmenterConfig.copy(settlingDwellMs = 100L, endPoseRelationship = EndPoseRelationship.ANY_STABLE_POSE_AFTER_MOVEMENT))
        listOf(0L, 25L, 50L, 75L, 100L).forEach { t ->
            segmenter.accept(MotionObservation(t, articulatedMotion = 0.05, imageSpaceMotion = 0.05, coverage = 0.9, accumulatedDisplacement = 0.01))
        }
        segmenter.arm(100L)

        // Strike 1 (sustained for 90 ms > 80 ms dwell)
        segmenter.accept(MotionObservation(130L, articulatedMotion = 1.0, imageSpaceMotion = 0.5, coverage = 0.9, accumulatedDisplacement = 0.03))
        segmenter.accept(MotionObservation(170L, articulatedMotion = 0.9, imageSpaceMotion = 0.4, coverage = 0.9, accumulatedDisplacement = 0.04))
        segmenter.accept(MotionObservation(220L, articulatedMotion = 0.9, imageSpaceMotion = 0.4, coverage = 0.9, accumulatedDisplacement = 0.04))
        assertEquals(MotionSegmentState.MOVING, segmenter.snapshot().state)

        // 150 ms quiet gap (> 100 ms settling dwell)
        segmenter.accept(MotionObservation(240L, articulatedMotion = 0.05, imageSpaceMotion = 0.05, coverage = 0.9, accumulatedDisplacement = 0.04))
        segmenter.accept(MotionObservation(290L, articulatedMotion = 0.05, imageSpaceMotion = 0.05, coverage = 0.9, accumulatedDisplacement = 0.04))
        val snapComplete = segmenter.accept(MotionObservation(350L, articulatedMotion = 0.05, imageSpaceMotion = 0.05, coverage = 0.9, accumulatedDisplacement = 0.04))
        assertEquals(MotionSegmentState.COMPLETE, snapComplete.state)

        // Rearm for Strike 2
        segmenter.rearmAfterCompletion(350L)
        assertEquals(MotionSegmentState.ARMED, segmenter.snapshot().state)

        // Strike 2 (sustained for 90 ms > 80 ms dwell)
        segmenter.accept(MotionObservation(380L, articulatedMotion = 1.1, imageSpaceMotion = 0.5, coverage = 0.9, accumulatedDisplacement = 0.02))
        segmenter.accept(MotionObservation(420L, articulatedMotion = 1.0, imageSpaceMotion = 0.4, coverage = 0.9, accumulatedDisplacement = 0.04))
        segmenter.accept(MotionObservation(470L, articulatedMotion = 1.0, imageSpaceMotion = 0.4, coverage = 0.9, accumulatedDisplacement = 0.04))
        assertEquals(MotionSegmentState.MOVING, segmenter.snapshot().state)
    }

    // --- Phase 16: Causal Ablation Test ---

    @Test
    fun phase16_causalAblation_kinematicAugmentationBenefits() {
        // Subtle isolated wrist movement where whole-body articulated motion is only 0.20 (below 0.50 threshold)
        val kinActive = KinematicPositiveMotionEvidence(
            motionEvidence = Evidence.TRUE,
            leftArm = Evidence.TRUE,
            triggeringChannels = listOf("LEFT_ARM_WRIST_SPEED"),
        )
        val obsWithoutKin = MotionObservation(150L, articulatedMotion = 0.20, imageSpaceMotion = 0.15, coverage = 0.9, accumulatedDisplacement = 0.02)
        val obsWithKin = MotionObservation(150L, articulatedMotion = 0.20, imageSpaceMotion = 0.15, coverage = 0.9, accumulatedDisplacement = 0.02, kinematics = BodyKinematics(
            leftArm = ArmKinematics(wristTorsoRelativeSpeed = 0.50),
            rightArm = ArmKinematics(),
            leftLeg = LegKinematics(),
            rightLeg = LegKinematics(),
            pelvis = PelvisKinematics(),
            positiveEvidence = kinActive,
        ))

        // Baseline setup
        val segNoKin = GenericMotionSegmenter(segmenterConfig.copy(enableKinematicsPositiveEvidence = false))
        val segWithKin = GenericMotionSegmenter(segmenterConfig.copy(enableKinematicsPositiveEvidence = true))
        listOf(0L, 25L, 50L, 75L, 100L).forEach { t ->
            val quietObs = MotionObservation(t, articulatedMotion = 0.05, imageSpaceMotion = 0.05, coverage = 0.9, accumulatedDisplacement = 0.01)
            segNoKin.accept(quietObs)
            segWithKin.accept(quietObs)
        }
        segNoKin.arm(100L)
        segWithKin.arm(100L)

        // Feed isolated movement observations across 80 ms dwell (150 ms to 240 ms = 90 ms)
        segNoKin.accept(obsWithoutKin)
        segWithKin.accept(obsWithKin)
        segNoKin.accept(obsWithoutKin.copy(timestampMs = 190L))
        segWithKin.accept(obsWithKin.copy(timestampMs = 190L))
        segNoKin.accept(obsWithoutKin.copy(timestampMs = 240L))
        val snapWithKin = segWithKin.accept(obsWithKin.copy(timestampMs = 240L))

        // Segmenter WITHOUT kinematics fails to detect subtle motion (stays ARMED)
        assertEquals(MotionSegmentState.ARMED, segNoKin.snapshot().state)
        // Segmenter WITH kinematics successfully transitions to MOVING (dwell 80 ms satisfied)
        assertEquals(MotionSegmentState.MOVING, snapWithKin.state)
        assertTrue(snapWithKin.transitions.any { it.newState == MotionSegmentState.MOVING })
    }
}
