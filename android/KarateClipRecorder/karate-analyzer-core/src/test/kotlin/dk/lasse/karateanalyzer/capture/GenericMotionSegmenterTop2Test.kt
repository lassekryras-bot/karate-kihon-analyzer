package dk.lasse.karateanalyzer.capture

import dk.lasse.karateanalyzer.observation.Evidence
import kotlin.test.*

class GenericMotionSegmenterTop2Test {

    private val top2Config = Top2KinematicsConfig(
        enabled = true,
        translationMoving = 0.70,
        translationQuiet = 0.45,
        angularMoving = 35.0,
        angularQuiet = 20.0,
    )

    private val segmenterConfig = GenericMotionSegmenterConfig(
        baselineDwellMs = 100L,
        movementStartDwellMs = 100L,
        settlingDwellMs = 100L,
        noMovementTimeoutMs = null,
        minimumCoverage = 0.70,
        maximumStableDisplacement = 0.05,
        endPoseRelationship = EndPoseRelationship.ANY_STABLE_POSE_AFTER_MOVEMENT,
        top2Kinematics = top2Config,
    )

    private fun observation(
        timestampMs: Long,
        et: Double?,
        ea: Double?,
        displacement: Double? = 0.01,
        coverage: Double = 0.95,
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

        return MotionObservation(
            timestampMs = timestampMs,
            articulatedMotion = 0.05,
            imageSpaceMotion = 0.05,
            coverage = coverage,
            sameAsStartSimilarity = 0.95,
            accumulatedDisplacement = displacement,
            kinematics = kin,
        )
    }

    private fun quietObs(t: Long) = observation(t, et = 0.20, ea = 10.0)

    private fun armedSegmenter(): GenericMotionSegmenter {
        val seg = GenericMotionSegmenter(segmenterConfig)
        for (t in 0L..100L step 20L) {
            seg.accept(quietObs(t))
        }
        assertTrue(seg.baselineReady, "Baseline must be ready after 100ms quiet")
        seg.arm(100L)
        assertEquals(MotionSegmentState.ARMED, seg.state)
        return seg
    }

    @Test
    fun translationMovingAbove070TriggersMovement() {
        val seg = armedSegmenter()
        // et = 0.80 >= 0.70, ea = 10.0 (quiet)
        val movingObs = observation(120L, et = 0.80, ea = 10.0)
        seg.accept(movingObs)
        assertEquals(Evidence.TRUE, seg.latestMotionEvidence)
        assertEquals(KinematicDecisionState.MOVING, seg.latestTop2TranslationDecision)
        assertEquals(KinematicDecisionState.QUIET, seg.latestTop2AngularDecision)

        // Dwell 100ms
        for (t in 140L..220L step 20L) {
            seg.accept(observation(t, et = 0.80, ea = 10.0))
        }
        assertEquals(MotionSegmentState.MOVING, seg.state)
    }

    @Test
    fun angularMovingAbove35TriggersMovement() {
        val seg = armedSegmenter()
        // et = 0.30 (quiet), ea = 45.0 >= 35.0 (moving)
        for (t in 120L..220L step 20L) {
            seg.accept(observation(t, et = 0.30, ea = 45.0))
        }
        assertEquals(MotionSegmentState.MOVING, seg.state)
        assertEquals(KinematicDecisionState.QUIET, seg.latestTop2TranslationDecision)
        assertEquals(KinematicDecisionState.MOVING, seg.latestTop2AngularDecision)
    }

    @Test
    fun hysteresisMidBandDoesNotTriggerMovementAndDoesNotConfirmQuiet() {
        val seg = armedSegmenter()
        // et = 0.55 (MID: 0.45 < 0.55 < 0.70), ea = 28.0 (MID: 20.0 < 28.0 < 35.0)
        for (t in 120L..300L step 20L) {
            seg.accept(observation(t, et = 0.55, ea = 28.0))
            assertEquals(Evidence.FALSE, seg.latestMotionEvidence, "MID band must not be positive movement")
            assertEquals(Evidence.FALSE, seg.latestQuietEvidence, "MID band must not be confirmed quiet")
            assertEquals(MotionSegmentState.ARMED, seg.state, "Segmenter must remain ARMED in MID band")
        }
    }

    @Test
    fun movingChannelVetoesQuiet() {
        val seg = armedSegmenter()
        // Start movement
        for (t in 120L..220L step 20L) {
            seg.accept(observation(t, et = 0.90, ea = 50.0))
        }
        assertEquals(MotionSegmentState.MOVING, seg.state)

        // One channel drops to quiet (ea = 15.0), but translation is still moving (et = 0.75)
        seg.accept(observation(240L, et = 0.75, ea = 15.0))
        assertEquals(Evidence.FALSE, seg.latestQuietEvidence, "Moving translation channel must veto quiet")
        assertEquals(MotionSegmentState.MOVING, seg.state, "Must stay in MOVING when one channel is moving")
    }

    @Test
    fun bothChannelsQuietConfirmsSettlingAndCompletion() {
        val seg = armedSegmenter()
        // Start movement
        for (t in 120L..220L step 20L) {
            seg.accept(observation(t, et = 0.90, ea = 50.0))
        }
        assertEquals(MotionSegmentState.MOVING, seg.state)

        // Both channels cross to quiet at t = 240
        seg.accept(observation(240L, et = 0.30, ea = 15.0))
        assertEquals(Evidence.TRUE, seg.latestQuietEvidence)
        assertEquals(MotionSegmentState.SETTLING, seg.state, "Must transition to SETTLING on first quiet candidate")

        // Settle for 100ms
        for (t in 260L..340L step 20L) {
            seg.accept(observation(t, et = 0.30, ea = 15.0))
        }
        assertEquals(MotionSegmentState.COMPLETE, seg.state, "Must transition to COMPLETE after settling dwell")

        val completeTr = seg.snapshot().transitions.last()
        assertEquals(340L, completeTr.decisionTimestampMs, "Decision at 340ms")
        assertEquals(240L, completeTr.estimatedBoundaryTimestampMs, "Backdated terminal boundary at first quiet candidate (240ms)")
    }

    @Test
    fun backdatedStartBoundaryMatchesFirstThresholdCrossing() {
        val seg = armedSegmenter()
        // Still quiet at t = 120
        seg.accept(observation(120L, et = 0.30, ea = 15.0))
        assertEquals(MotionSegmentState.ARMED, seg.state)

        // First threshold crossing at t = 140 (Candidate Start!)
        seg.accept(observation(140L, et = 0.85, ea = 40.0))
        assertEquals(MotionSegmentState.ARMED, seg.state)

        // Continue moving to fulfill 100ms dwell (t = 160, 180, 200, 220, 240)
        for (t in 160L..240L step 20L) {
            seg.accept(observation(t, et = 0.85, ea = 40.0))
        }
        assertEquals(MotionSegmentState.MOVING, seg.state)
        val movingTr = seg.snapshot().transitions.first { it.newState == MotionSegmentState.MOVING }
        assertEquals(240L, movingTr.decisionTimestampMs, "Confirmed start at dwell fulfillment (240ms)")
        assertEquals(140L, movingTr.estimatedBoundaryTimestampMs, "Backdated start boundary at first crossing (140ms)")
    }

    @Test
    fun missingEvidenceYieldsUnknownNeverFalseQuiet() {
        val seg = GenericMotionSegmenter(segmenterConfig)
        // Feed frames with null kinematics
        for (t in 0L..200L step 20L) {
            val obs = observation(t, et = null, ea = null)
            seg.accept(obs)
            assertEquals(Evidence.UNKNOWN, seg.latestQuietEvidence, "Missing kinematics must be UNKNOWN")
            assertFalse(seg.baselineReady, "Baseline must never become ready without valid evidence")
        }
    }

    @Test
    fun displacementExceedingThresholdPreventsStillnessEvenIfKinematicsQuiet() {
        val seg = armedSegmenter()
        // Move
        for (t in 120L..220L step 20L) {
            seg.accept(observation(t, et = 0.85, ea = 40.0))
        }
        assertEquals(MotionSegmentState.MOVING, seg.state)

        // Kinematics are quiet, but displacement = 0.08 > 0.05 limit
        val slowDrift = observation(240L, et = 0.20, ea = 10.0, displacement = 0.08)
        seg.accept(slowDrift)
        assertEquals(Evidence.FALSE, seg.latestQuietEvidence, "Displacement > 0.05 must veto quiet")
        assertEquals(MotionSegmentState.MOVING, seg.state, "Must remain in MOVING")
    }

    @Test
    fun seamlessRearmAllowsSubsequentMovementDetection() {
        val seg = armedSegmenter()
        // Repetition 1
        for (t in 120L..220L step 20L) seg.accept(observation(t, et = 0.85, ea = 40.0))
        for (t in 240L..340L step 20L) seg.accept(observation(t, et = 0.20, ea = 10.0))
        assertEquals(MotionSegmentState.COMPLETE, seg.state)

        // Seamless rearm at terminal hold
        seg.rearmAfterCompletion(340L)
        assertEquals(MotionSegmentState.ARMED, seg.state)
        assertEquals(1, seg.snapshot().transitions.size)
        assertEquals("rearmed_from_stable_terminal_pose", seg.snapshot().transitions.first().trigger)

        // Repetition 2 start
        seg.accept(observation(360L, et = 0.85, ea = 40.0)) // First crossing
        for (t in 380L..460L step 20L) seg.accept(observation(t, et = 0.85, ea = 40.0))
        assertEquals(MotionSegmentState.MOVING, seg.state)
        val rep2Start = seg.snapshot().transitions.first { it.newState == MotionSegmentState.MOVING }
        assertEquals(360L, rep2Start.estimatedBoundaryTimestampMs)
        assertEquals(460L, rep2Start.decisionTimestampMs)
    }
}
