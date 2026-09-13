package dk.lasse.karateanalyzer.capture

import dk.lasse.karateanalyzer.core.Point3
import dk.lasse.karateanalyzer.core.PoseLandmarkId
import dk.lasse.karateanalyzer.core.PoseLandmarkId.*
import dk.lasse.karateanalyzer.observation.Evidence
import kotlin.math.*
import kotlin.test.*

class FilteredKinematicsContractTest {
    private fun pose(t: Long, translation: Float = 0f): RelativePose {
        val p = mutableMapOf<PoseLandmarkId, Point3>()
        for ((side, sign) in listOf("LEFT" to -1f, "RIGHT" to 1f)) {
            p[PoseLandmarkId.valueOf("${side}_HIP")] = Point3(.2f * sign, 0f, 0f)
            p[PoseLandmarkId.valueOf("${side}_KNEE")] = Point3(.2f * sign, .5f, .1f)
            p[PoseLandmarkId.valueOf("${side}_ANKLE")] = Point3(.2f * sign, 1f, 0f)
            // Elbow interior angle is exactly 140 degrees, unaffected by translation.
            p[PoseLandmarkId.valueOf("${side}_SHOULDER")] = Point3(.25f * sign + translation, -1f, 0f)
            p[PoseLandmarkId.valueOf("${side}_ELBOW")] = Point3(.25f * sign + translation, -.6f, 0f)
            p[PoseLandmarkId.valueOf("${side}_WRIST")] = Point3(.25f * sign + translation + (.4 * sin(Math.toRadians(140.0))).toFloat(), -.6f - (.4 * cos(Math.toRadians(140.0))).toFloat(), 0f)
        }
        return RelativePose(t, p, p.mapValues { .95 }, 1.0)
    }

    private fun quiet(t: Long, kin: BodyKinematics? = null) = MotionObservation(t, .02, .02, .95,
        sameAsStartSimilarity = 1.0, accumulatedDisplacement = .01, kinematics = kin)
    private fun segmenter() = GenericMotionSegmenter(GenericMotionSegmenterConfig(
        baselineDwellMs = 100, movementStartDwellMs = 100, settlingDwellMs = 100,
        startMotionThreshold = .5, quietMotionThreshold = .5, noMovementTimeoutMs = null,
        endPoseRelationship = EndPoseRelationship.ANY_STABLE_POSE_AFTER_MOVEMENT,
        requiredRegionsForTerminalStillness = setOf(AnatomicalRegion.TORSO, AnatomicalRegion.RIGHT_ARM),
        enableKinematicsPositiveEvidence = true,
    ))

    @Test fun readinessRequiresQuietAndOptionalLegMotionVetoesIt() {
        val seg = segmenter()
        assertFailsWith<IllegalArgumentException> { seg.arm(0) }
        val k = BodyKinematics(ArmKinematics(), ArmKinematics(), LegKinematics(), LegKinematics(), PelvisKinematics(),
            KinematicPositiveMotionEvidence(motionEvidence = Evidence.TRUE, leftLeg = Evidence.TRUE))
        for (t in 0L..200L step 20) assertFalse(seg.accept(quiet(t, k)).baselineReady)
        assertTrue(seg.accept(quiet(220)).kinematicsAlteredDecision.not())
        assertTrue(seg.accept(quiet(320)).baselineReady)
    }

    @Test fun missingAndDegenerateChannelsStayUnknownButAvailableEndpointCanTrigger() {
        for (moving in listOf(false, true)) {
            val extractor = KinematicChainExtractor()
            var previous: RelativePose? = null
            var result: BodyKinematics? = null
            for (t in 0L..100L step 20) {
                val original = pose(t, if (moving) t / 1000f else 0f)
                val p = original.copy(points = original.points - LEFT_ELBOW)
                result = extractor.extract(previous, p, 20)
                previous = p
            }
            assertEquals(if (moving) Evidence.TRUE else Evidence.UNKNOWN, result!!.leftArm.motionEvidence)
            assertNull(result.leftArm.elbowAngularVelocityDegPerSec)
        }
        val extractor = KinematicChainExtractor()
        var result: BodyKinematics? = null
        for (t in 0L..100L step 20) {
            val p = pose(t)
            result = extractor.extract(null, p.copy(points = p.points + (LEFT_ELBOW to p.points.getValue(LEFT_WRIST))), 20)
        }
        assertEquals(Evidence.UNKNOWN, result!!.leftArm.motionEvidence)
    }

    @Test fun invalidConfidenceAndStaleWindowAreUnavailable() {
        for (confidence in listOf(Double.NaN, .49)) {
            val smoother = CausalKinematicSmoother()
            for (t in 0L..100L step 20) {
                val p = pose(t)
                smoother.accept(p.copy(confidences = p.confidences + (LEFT_WRIST to confidence)))
            }
            assertNull(smoother.getUsableWindow(100, listOf(LEFT_WRIST)))
        }
        val smoother = CausalKinematicSmoother()
        for (t in 0L..100L step 20) smoother.accept(pose(t))
        assertNull(smoother.getUsableWindow(110, listOf(LEFT_WRIST)))
        assertFailsWith<IllegalArgumentException> { smoother.accept(pose(100)) }
        smoother.preserveConfirmedWindow(0, 100)
        smoother.accept(pose(120))
        assertNotNull(smoother.getUsableWindow(120, listOf(LEFT_WRIST)))
        smoother.reset()
        smoother.accept(pose(140))
        assertNull(smoother.getUsableWindow(140, listOf(LEFT_WRIST)))
    }

    @Test fun locked140DegreeTranslationHasMeasuredCausalLatencyAndRecoveredBoundaryAtThreeRates() {
        for (speed in listOf(.4, 1.0)) for (fps in listOf(60, 49, 30)) {
            val ext = KinematicChainExtractor()
            val seg = segmenter()
            var previous: RelativePose? = null
            var firstTrigger: Long? = null
            var boundary: Long? = null
            var onset = 0L
            for (i in 0..40) {
                val t = (i * 1000.0 / fps).roundToLong()
                if (i == 12) onset = t
                val p = pose(t, if (i >= 12) speed.toFloat() * (t - onset) / 1000f else 0f)
                val k = ext.extract(previous, p, if (previous == null) 0 else t - previous.timestampMs)
                previous = p
                val snap = seg.accept(quiet(t, k)) // whole-body channels intentionally below threshold
                if (snap.baselineReady && snap.state == MotionSegmentState.BASELINE) seg.arm(t)
                if (i >= 12 && k.leftArm.motionEvidence == Evidence.TRUE && firstTrigger == null) firstTrigger = t
                if (snap.state == MotionSegmentState.MOVING && boundary == null) boundary = snap.transitions.first { it.newState == MotionSegmentState.MOVING }.estimatedBoundaryTimestampMs
                if (i >= 20) {
                    assertEquals(140.0, k.leftArm.elbowAngleDegrees!!, .001)
                    assertEquals(speed, k.leftArm.wristTorsoRelativeSpeed!!, .001)
                    assertTrue(k.leftArm.elbowAngularVelocityDegPerSec!! < .01)
                }
            }
            val latency = assertNotNull(firstTrigger) - onset
            val error = assertNotNull(boundary) - onset
            println("TASK5H_LATENCY speed=$speed fps=$fps threshold_ms=$latency boundary_error_ms=$error")
            // Characterization is not release acceptance: the separate report retains the <80 ms gate.
            // A near-threshold ramp can need almost the full causal window to cross.
            if (speed == 1.0) assertTrue(latency < 80, "Fast translation crossing target")
            assertTrue(latency <= 100, "Slow translation must cross within a full window at $fps fps")
            assertTrue(abs(error) <= 25, "Boundary error $error ms at $fps fps")
        }
    }

    @Test fun rearmRecordsNewSegmentBoundariesAnd120msQuietGapCompletes() {
        val seg = segmenter()
        seg.accept(quiet(0)); seg.accept(quiet(100)); seg.arm(100)
        for (start in listOf(120L, 440L)) {
            seg.accept(quiet(start).copy(articulatedMotion = 1.0))
            seg.accept(quiet(start + 100).copy(articulatedMotion = 1.0))
            seg.accept(quiet(start + 120))
            val complete = seg.accept(quiet(start + 220))
            assertEquals(MotionSegmentState.COMPLETE, complete.state)
            assertEquals(start, complete.transitions.first { it.newState == MotionSegmentState.MOVING }.estimatedBoundaryTimestampMs)
            val rearmed = seg.rearmAfterCompletion(start + 220)
            assertEquals(1, rearmed.transitions.size)
            assertEquals(MotionSegmentState.COMPLETE, rearmed.transitions.single().previousState)
        }
    }

    @Test fun causalFourWayAblationSeparatesAngularAndTranslationalEvidence() {
        for (movement in listOf("translation", "rotation")) {
            val ext = KinematicChainExtractor()
            val consumers = List(4) { segmenter() }
            var previous: RelativePose? = null
            for (t in 0L..800L step 20) {
                var p = pose(t, if (movement == "translation") .4f * maxOf(0, t - 300) / 1000f else 0f)
                if (movement == "rotation") {
                    // Short forearm isolates angular activity below endpoint/radial thresholds.
                    val theta = Math.toRadians(40.0 * maxOf(0, t - 300) / 1000.0)
                    p = p.copy(points = p.points + (LEFT_WRIST to (p.points.getValue(LEFT_ELBOW) +
                        Point3((.1 * sin(theta)).toFloat(), (.1 * cos(theta)).toFloat(), 0f))))
                }
                val k = ext.extract(previous, p, 20)
                previous = p
                for ((index, seg) in consumers.withIndex()) {
                    val channels = k.positiveEvidence.channels.filter { c ->
                        val translational = c.name.endsWith("SPEED") || c.name.endsWith("RADIAL")
                        c.usedInGating && when (index) { 0 -> false; 1 -> !translational; 2 -> translational; else -> true }
                    }
                    val active = channels.filter { it.evidence == Evidence.TRUE }
                    val aggregate = when { active.isNotEmpty() -> Evidence.TRUE
                        channels.isNotEmpty() && channels.all { it.evidence == Evidence.FALSE } -> Evidence.FALSE
                        else -> Evidence.UNKNOWN }
                    val evidence = k.positiveEvidence.copy(motionEvidence = aggregate, channels = channels,
                        triggeringChannels = active.map { it.name })
                    val snap = seg.accept(quiet(t, k.copy(positiveEvidence = evidence)))
                    if (snap.baselineReady && snap.state == MotionSegmentState.BASELINE) seg.arm(t)
                }
            }
            val detected = consumers.map { it.snapshot().state == MotionSegmentState.MOVING }
            println("TASK5H_ABLATION movement=$movement existing_angular_translation_both=$detected")
            assertEquals(if (movement == "translation") listOf(false, false, true, true) else listOf(false, true, false, true), detected)
        }
    }

    @Test fun slowExtensionRetractionChamberAndLegTravelRemainPositive() {
        for (landmark in listOf(LEFT_WRIST, RIGHT_WRIST, LEFT_KNEE, RIGHT_KNEE, LEFT_ANKLE, RIGHT_ANKLE)) {
            for (direction in listOf(-1, 1)) {
                val ext = KinematicChainExtractor()
                var previous: RelativePose? = null
                var result: BodyKinematics? = null
                for (t in 0L..300L step 20) {
                    val p = pose(t)
                    val changed = p.copy(points = p.points + (landmark to
                        (p.points.getValue(landmark) + Point3(direction * .4f * t / 1000f, 0f, 0f))))
                    result = ext.extract(previous, changed, 20)
                    previous = changed
                }
                val signal = when (landmark) {
                    LEFT_WRIST -> "LEFT_ARM_WRIST_SPEED"; RIGHT_WRIST -> "RIGHT_ARM_WRIST_SPEED"
                    LEFT_KNEE -> "LEFT_LEG_KNEE_SPEED"; RIGHT_KNEE -> "RIGHT_LEG_KNEE_SPEED"
                    LEFT_ANKLE -> "LEFT_LEG_ANKLE_SPEED"; else -> "RIGHT_LEG_ANKLE_SPEED"
                }
                assertEquals(Evidence.TRUE, result!!.positiveEvidence.channels.single { it.name == signal }.evidence)
            }
        }
    }
}
