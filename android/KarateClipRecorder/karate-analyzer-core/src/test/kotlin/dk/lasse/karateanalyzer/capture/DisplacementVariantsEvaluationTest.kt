package dk.lasse.karateanalyzer.capture

import dk.lasse.karateanalyzer.core.Point3
import dk.lasse.karateanalyzer.core.PoseLandmarkId
import dk.lasse.karateanalyzer.observation.Evidence
import kotlin.test.*

class DisplacementVariantsEvaluationTest {

    private val top2Config = Top2KinematicsConfig(
        enabled = true,
        translationMoving = 0.70,
        translationQuiet = 0.45,
        angularMoving = 35.0,
        angularQuiet = 20.0,
    )

    private fun baseConfig(policy: SlowDisplacementPolicy) = GenericMotionSegmenterConfig(
        baselineDwellMs = 100L,
        movementStartDwellMs = 100L,
        settlingDwellMs = 100L,
        noMovementTimeoutMs = null,
        minimumCoverage = 0.70,
        maximumStableDisplacement = 0.05,
        endPoseRelationship = EndPoseRelationship.ANY_STABLE_POSE_AFTER_MOVEMENT,
        top2Kinematics = top2Config,
        slowDisplacementPolicy = policy,
        settlingLocalDisplacementLimit = 0.03,
    )

    private fun observation(
        timestampMs: Long,
        et: Double?,
        ea: Double?,
        displacement: Double? = 0.01,
        coverage: Double = 0.95,
        relativePoseXOffset: Double = 0.0,
    ): MotionObservation {
        val top2 = if (et != null || ea != null) {
            KinematicEvidenceTop2(
                translationEvidence = et,
                translationChannel1 = "RIGHT_WRIST",
                translationChannel2 = "RIGHT_ELBOW",
                translationChannelCount = 2,
                angularEvidence = ea,
                angularChannel1 = "RIGHT_ELBOW",
                angularChannel2 = "RIGHT_FOREARM_ORIENTATION",
                angularChannelCount = 2,
                referenceScale = 0.158,
                cameraNearArmSide = LateralSide.RIGHT,
            )
        } else null

        val kin = BodyKinematics(
            leftArm = ArmKinematics(),
            rightArm = ArmKinematics(),
            leftLeg = LegKinematics(),
            rightLeg = LegKinematics(),
            pelvis = PelvisKinematics(),
            positiveEvidence = KinematicPositiveMotionEvidence(),
            top2 = top2,
        )

        val relPose = RelativePose(
            timestampMs = timestampMs,
            points = mapOf(
                PoseLandmarkId.RIGHT_WRIST to Point3(0.5f + relativePoseXOffset.toFloat(), 0.5f, 0f),
                PoseLandmarkId.RIGHT_ELBOW to Point3(0.3f, 0.5f, 0f),
                PoseLandmarkId.RIGHT_SHOULDER to Point3(0.1f, 0.5f, 0f),
                PoseLandmarkId.LEFT_SHOULDER to Point3(-0.1f, 0.5f, 0f),
                PoseLandmarkId.LEFT_HIP to Point3(-0.1f, -0.5f, 0f),
                PoseLandmarkId.RIGHT_HIP to Point3(0.1f, -0.5f, 0f),
                PoseLandmarkId.NOSE to Point3(0f, 0.8f, 0f),
                PoseLandmarkId.LEFT_EAR to Point3(-0.1f, 0.8f, 0f),
                PoseLandmarkId.RIGHT_EAR to Point3(0.1f, 0.8f, 0f),
                PoseLandmarkId.LEFT_ELBOW to Point3(-0.3f, 0.5f, 0f),
                PoseLandmarkId.LEFT_WRIST to Point3(-0.5f, 0.5f, 0f),
                PoseLandmarkId.LEFT_KNEE to Point3(-0.1f, -0.8f, 0f),
                PoseLandmarkId.RIGHT_KNEE to Point3(0.1f, -0.8f, 0f),
                PoseLandmarkId.LEFT_ANKLE to Point3(-0.1f, -1.1f, 0f),
                PoseLandmarkId.RIGHT_ANKLE to Point3(0.1f, -1.1f, 0f),
            ),
            confidences = mapOf(
                PoseLandmarkId.RIGHT_WRIST to 0.95,
                PoseLandmarkId.RIGHT_ELBOW to 0.95,
                PoseLandmarkId.RIGHT_SHOULDER to 0.95,
                PoseLandmarkId.LEFT_SHOULDER to 0.95,
                PoseLandmarkId.LEFT_HIP to 0.95,
                PoseLandmarkId.RIGHT_HIP to 0.95,
                PoseLandmarkId.NOSE to 0.95,
                PoseLandmarkId.LEFT_EAR to 0.95,
                PoseLandmarkId.RIGHT_EAR to 0.95,
                PoseLandmarkId.LEFT_ELBOW to 0.95,
                PoseLandmarkId.LEFT_WRIST to 0.95,
                PoseLandmarkId.LEFT_KNEE to 0.95,
                PoseLandmarkId.RIGHT_KNEE to 0.95,
                PoseLandmarkId.LEFT_ANKLE to 0.95,
                PoseLandmarkId.RIGHT_ANKLE to 0.95,
            ),
            worldScale = 1.0,
            referenceScale = 0.158,
        )

        return MotionObservation(
            timestampMs = timestampMs,
            articulatedMotion = 0.05,
            imageSpaceMotion = 0.05,
            coverage = coverage,
            sameAsStartSimilarity = 0.95,
            accumulatedDisplacement = displacement,
            kinematics = kin,
            relativePose = relPose,
        )
    }

    private fun armedSegmenter(policy: SlowDisplacementPolicy): GenericMotionSegmenter {
        val seg = GenericMotionSegmenter(baseConfig(policy))
        for (t in 0L..100L step 20L) {
            seg.accept(observation(t, et = 0.20, ea = 10.0))
        }
        assertTrue(seg.baselineReady)
        seg.arm(100L)
        assertEquals(MotionSegmentState.ARMED, seg.state)
        return seg
    }

    @Test
    fun trailingWindowRequiresDisplacementBelowLimit() {
        val seg = armedSegmenter(SlowDisplacementPolicy.TRAILING_WINDOW)
        // Start movement
        for (t in 120L..220L step 20L) seg.accept(observation(t, et = 0.90, ea = 40.0))
        assertEquals(MotionSegmentState.MOVING, seg.state)

        // Kinematics drop to quiet, but displacement = 0.08 > 0.05
        val obs = observation(240L, et = 0.20, ea = 10.0, displacement = 0.08)
        seg.accept(obs)
        assertEquals(Evidence.FALSE, seg.latestQuietEvidence, "Displacement > 0.05 must veto quiet in TRAILING_WINDOW")
        assertEquals(MotionSegmentState.MOVING, seg.state, "Must stay MOVING")
    }

    @Test
    fun disabledPolicyOmitsDisplacementGate() {
        val seg = armedSegmenter(SlowDisplacementPolicy.DISABLED)
        // Start movement
        for (t in 120L..220L step 20L) seg.accept(observation(t, et = 0.90, ea = 40.0))
        assertEquals(MotionSegmentState.MOVING, seg.state)

        // Kinematics drop to quiet, displacement is high (0.08 > 0.05)
        seg.accept(observation(240L, et = 0.20, ea = 10.0, displacement = 0.08))
        assertEquals(Evidence.TRUE, seg.latestQuietEvidence, "DISABLED policy must ignore displacement gate")
        assertEquals(MotionSegmentState.SETTLING, seg.state)

        // Settle for 100 ms with high displacement
        for (t in 260L..340L step 20L) {
            seg.accept(observation(t, et = 0.20, ea = 10.0, displacement = 0.08))
        }
        assertEquals(MotionSegmentState.COMPLETE, seg.state, "Must complete after 100ms dwell under DISABLED")
    }

    @Test
    fun settlingLocalCapturesAnchorAndVetoesDrift() {
        val seg = armedSegmenter(SlowDisplacementPolicy.SETTLING_LOCAL)
        // Start movement
        for (t in 120L..220L step 20L) seg.accept(observation(t, et = 0.90, ea = 40.0, relativePoseXOffset = 0.0))
        assertEquals(MotionSegmentState.MOVING, seg.state)

        // Candidate quiet at t = 240, captures anchor pose with relativePoseXOffset = 0.0
        seg.accept(observation(240L, et = 0.20, ea = 10.0, relativePoseXOffset = 0.0))
        assertEquals(MotionSegmentState.SETTLING, seg.state)

        // Slight drift within limit (0.01 <= 0.03) at t = 260 and 280
        seg.accept(observation(260L, et = 0.20, ea = 10.0, relativePoseXOffset = 0.01))
        seg.accept(observation(280L, et = 0.20, ea = 10.0, relativePoseXOffset = 0.01))
        assertEquals(MotionSegmentState.SETTLING, seg.state)

        // Drift exceeds limit (> 0.03 limit) at t = 300 (relativePoseXOffset = 0.20)
        val driftingObs = observation(300L, et = 0.20, ea = 10.0, relativePoseXOffset = 0.20)
        seg.accept(driftingObs)
        val snap = seg.snapshot()
        assertEquals(MotionSegmentState.SETTLING, snap.state)
        assertEquals(0L, snap.completionDwell.accumulatedDurationMs, "Drift veto must reset settling dwell")

        // Settle stably without drift for 100 ms (t = 320 .. 420)
        for (t in 320L..420L step 20L) {
            seg.accept(observation(t, et = 0.20, ea = 10.0, relativePoseXOffset = 0.01))
        }
        assertEquals(MotionSegmentState.COMPLETE, seg.state, "Must complete after full 100ms dwell within drift limit")
    }

    @Test
    fun midBandPausesSettlingDwellWithoutResettingOrAdvancing() {
        val seg = armedSegmenter(SlowDisplacementPolicy.DISABLED)
        // Start movement
        for (t in 120L..220L step 20L) seg.accept(observation(t, et = 0.90, ea = 40.0))
        assertEquals(MotionSegmentState.MOVING, seg.state)

        // t = 240: First QUIET candidate -> transitions to SETTLING
        seg.accept(observation(240L, et = 0.20, ea = 10.0))
        assertEquals(MotionSegmentState.SETTLING, seg.state)

        // t = 260: QUIET -> dwell advances to 20ms
        seg.accept(observation(260L, et = 0.20, ea = 10.0))
        val dwellAt260 = seg.snapshot().completionDwell.accumulatedDurationMs
        assertEquals(20L, dwellAt260)

        // t = 280, 300, 320: MID band (et = 0.55, ea = 10.0) -> dwell must PAUSE
        for (t in listOf(280L, 300L, 320L)) {
            seg.accept(observation(t, et = 0.55, ea = 10.0))
            val snap = seg.snapshot()
            assertEquals(MotionSegmentState.SETTLING, snap.state, "Must remain in SETTLING during MID band")
            assertEquals(20L, snap.completionDwell.accumulatedDurationMs, "Dwell must PAUSE in MID band (neither advance nor reset)")
        }

        // t = 340, 360, 380, 400, 420: QUIET returns -> dwell resumes from 20ms and advances to 100ms
        seg.accept(observation(340L, et = 0.20, ea = 10.0)) // first quiet frame after pause
        seg.accept(observation(360L, et = 0.20, ea = 10.0)) // +20ms -> 40ms
        seg.accept(observation(380L, et = 0.20, ea = 10.0)) // +20ms -> 60ms
        seg.accept(observation(400L, et = 0.20, ea = 10.0)) // +20ms -> 80ms
        seg.accept(observation(420L, et = 0.20, ea = 10.0)) // +20ms -> 100ms -> COMPLETE!

        assertEquals(MotionSegmentState.COMPLETE, seg.state, "Must transition to COMPLETE after accumulated 100ms QUIET dwell")
    }

    @Test
    fun movingStateAbortsSettlingAndResumesMoving() {
        val seg = armedSegmenter(SlowDisplacementPolicy.DISABLED)
        // Start movement
        for (t in 120L..220L step 20L) seg.accept(observation(t, et = 0.90, ea = 40.0))
        assertEquals(MotionSegmentState.MOVING, seg.state)

        // t = 240, 260: QUIET -> enters SETTLING, accumulates 20ms
        seg.accept(observation(240L, et = 0.20, ea = 10.0))
        seg.accept(observation(260L, et = 0.20, ea = 10.0))
        assertEquals(MotionSegmentState.SETTLING, seg.state)

        // t = 280: Channel crosses to MOVING (et = 0.85) -> ABORTS settling
        seg.accept(observation(280L, et = 0.85, ea = 10.0))
        val snap = seg.snapshot()
        assertEquals(MotionSegmentState.MOVING, snap.state, "MOVING must abort settling and return to MOVING")
        assertEquals(0L, snap.completionDwell.accumulatedDurationMs, "Settling dwell must be reset")
        assertEquals("movement_resumed", snap.transitions.last().trigger)
    }

    @Test
    fun missingCoverageResetsSettlingDwell() {
        val seg = armedSegmenter(SlowDisplacementPolicy.DISABLED)
        // Start movement
        for (t in 120L..220L step 20L) seg.accept(observation(t, et = 0.90, ea = 40.0))
        assertEquals(MotionSegmentState.MOVING, seg.state)

        // t = 240, 260: SETTLING
        seg.accept(observation(240L, et = 0.20, ea = 10.0))
        seg.accept(observation(260L, et = 0.20, ea = 10.0))
        assertEquals(MotionSegmentState.SETTLING, seg.state)

        // t = 280: Coverage deficit (0.40 < 0.70)
        seg.accept(observation(280L, et = 0.20, ea = 10.0, coverage = 0.40))
        val snap = seg.snapshot()
        assertEquals(0L, snap.completionDwell.accumulatedDurationMs, "Coverage deficit must reset settling dwell")
    }
}
