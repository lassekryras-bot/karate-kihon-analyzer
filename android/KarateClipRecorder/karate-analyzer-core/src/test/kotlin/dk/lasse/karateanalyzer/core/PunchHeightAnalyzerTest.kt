package dk.lasse.karateanalyzer.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PunchHeightAnalyzerTest {
    @Test fun torsoTargetsAreBodyRelativeAndScaleInvariant() {
        val small = body(torsoLength = 0.25f)
        val large = body(torsoLength = 0.50f)

        val smallTarget = ChudanTargetModel().evaluate(emptyTracked(), small, 1.1f)
        val largeTarget = ChudanTargetModel().evaluate(emptyTracked(), large, 1.1f)

        assertEquals(0.45f, smallTarget.torsoScalar)
        assertEquals(0.45f, largeTarget.torsoScalar)
        assertEquals(0.1125f, smallTarget.targetPoint.y - small.shoulderPoint.y, 0.0001f)
        assertEquals(0.225f, largeTarget.targetPoint.y - large.shoulderPoint.y, 0.0001f)
        assertEquals(0.80f, GedanTargetModel().evaluate(emptyTracked(), small, 1.1f).torsoScalar)
    }

    @Test fun jodanProjectionMultiplierImmediatelyMovesTarget() {
        val estimator = SideViewChinEstimator()
        val model = JodanTargetModel(estimator)
        val body = body(0.30f)
        val frame = trackedFrame(0L)

        val first = assertNotNull(model.evaluate(frame, body, 1.0f))
        val second = assertNotNull(model.evaluate(frame.copy(timestampMs = 100L), body, 1.5f))

        assertTrue(second.targetPoint.y > first.targetPoint.y)
        assertEquals(ChinTrackingSource.FRESH_FACE, second.chinEstimate?.source)
    }

    @Test fun setupRequiresSideViewAndStableHold() {
        val analyzer = PunchHeightAnalyzer()
        val frontFacing = poseFrame(0L, shoulderHalfWidth = 0.10f)
        assertEquals(SetupGuidance.TURN_MORE_SIDEWAYS, analyzer.processSetup(frontFacing).guidance)

        var result = analyzer.processSetup(poseFrame(100L))
        for (time in 200L..2_600L step 100L) result = analyzer.processSetup(poseFrame(time))

        assertTrue(result.usable)
        assertEquals(SetupGuidance.CAMERA_READY, result.guidance)
    }

    @Test fun mirroredRightSidePoseLocksRightWithoutChangingBodyScale() {
        val analyzer = PunchHeightAnalyzer()
        for (time in 0L..1_300L step 100L) analyzer.processSetup(mirror(poseFrame(time)))
        var reference: BodyReference? = null
        for (time in 1_400L..3_000L step 100L) {
            reference = analyzer.processBodyInitialization(mirror(poseFrame(time)), 1.1f).bodyReference ?: reference
        }

        assertEquals(VisibleSide.RIGHT, assertNotNull(reference).visibleSide)
        assertEquals(0.30f, reference.torsoLength, 0.001f)
    }

    @Test fun stableExtendedChudanPoseCapturesOnceAfterHold() {
        val analyzer = initializedAnalyzer()
        var captured = 0
        var finalEvaluation: PunchHeightEvaluation? = null
        for (time in 3_100L..7_000L step 100L) {
            val evaluation = assertNotNull(analyzer.evaluateTarget(PunchHeightTargetType.CHUDAN, poseFrame(time, punchTargetRatio = 0.45f), 1.1f))
            if (evaluation.captureReady) captured++
            finalEvaluation = evaluation
        }

        assertEquals(1, captured, finalEvaluation.toString())
        assertEquals(PunchHeightGuidanceState.CORRECT_AND_HOLDING, finalEvaluation?.guidance)
        assertTrue((finalEvaluation?.stableHoldMs ?: 0L) >= 1_200L)
    }

    @Test fun predictionOnlyJodanNeverCaptures() {
        val analyzer = initializedAnalyzer()
        var captured = false
        for (time in 3_100L..5_000L step 100L) {
            val evaluation = assertNotNull(
                analyzer.evaluateTarget(
                    PunchHeightTargetType.JODAN,
                    poseFrame(time, punchTargetRatio = -0.65f, omitFace = true),
                    1.1f,
                ),
            )
            captured = captured || evaluation.captureReady
        }

        assertFalse(captured)
    }

    @Test fun aSingleUnreliableArmFrameResetsAnInProgressHold() {
        val analyzer = initializedAnalyzer()
        var evaluation: PunchHeightEvaluation? = null
        for (time in 3_100L..5_300L step 100L) {
            evaluation = analyzer.evaluateTarget(PunchHeightTargetType.CHUDAN, poseFrame(time, punchTargetRatio = 0.45f), 1.1f)
            if ((evaluation?.holdProgress ?: 0f) >= 0.4f) break
        }
        assertTrue((evaluation?.holdProgress ?: 0f) in 0.4f..<1f)

        val interrupted = assertNotNull(
            analyzer.evaluateTarget(PunchHeightTargetType.CHUDAN, poseFrame((evaluation?.timestampMs ?: 0L) + 100L, punchTargetRatio = 0.80f), 1.1f),
        )
        assertEquals(0f, interrupted.holdProgress)
        assertFalse(interrupted.captureReady)
    }

    @Test fun shoulderOnlyChinFallbackExpiresAndCannotCapture() {
        val estimator = SideViewChinEstimator()
        val body = body(0.30f)
        estimator.initialize(trackedFrame(0L), body, 1.1f)

        val expired = estimator.estimate(TrackedPoseFrame(600L, poseFrame(600L, omitFace = true).landmarks), body, 1.1f)

        assertEquals(ChinTrackingSource.LOST, expired.source)
        assertFalse(expired.captureEligible)
    }

    private fun initializedAnalyzer(): PunchHeightAnalyzer = PunchHeightAnalyzer().also { analyzer ->
        for (time in 0L..1_300L step 100L) analyzer.processSetup(poseFrame(time))
        var reference: BodyReference? = null
        for (time in 1_400L..3_000L step 100L) {
            reference = analyzer.processBodyInitialization(poseFrame(time), 1.1f).bodyReference ?: reference
        }
        assertNotNull(reference)
    }

    private fun body(torsoLength: Float) = BodyReference(
        visibleSide = VisibleSide.LEFT,
        shoulderPoint = Point3(0.5f, 0.3f, 0f),
        hipPoint = Point3(0.5f, 0.3f + torsoLength, 0f),
        torsoAxis = Point3(0f, 1f, 0f),
        torsoLength = torsoLength,
        confidence = 0.95f,
    )

    private fun emptyTracked() = TrackedPoseFrame(0L, emptyMap())

    private fun trackedFrame(timestampMs: Long) = TrackedPoseFrame(timestampMs, poseFrame(timestampMs).landmarks)

    private fun poseFrame(
        timestampMs: Long,
        shoulderHalfWidth: Float = 0.01f,
        punchTargetRatio: Float? = null,
        omitFace: Boolean = false,
    ): PoseFrame {
        val shoulderY = 0.38f
        val hipY = 0.68f
        val shoulderX = 0.50f
        val wristY = punchTargetRatio?.let { shoulderY + (hipY - shoulderY) * it } ?: 0.62f
        val wristX = 0.14f
        val elbow = Point3((shoulderX + wristX) / 2f, (shoulderY + wristY) / 2f, -0.10f)
        val values = mutableMapOf<PoseLandmarkId, PoseLandmarkSample>()
        fun put(id: PoseLandmarkId, x: Float, y: Float, z: Float, confidence: Float) {
            values[id] = PoseLandmarkSample(Point3(x, y, z), Point3(x, y, z), confidence, confidence, LandmarkSource.OBSERVED)
        }
        put(PoseLandmarkId.LEFT_SHOULDER, shoulderX - shoulderHalfWidth, shoulderY, -0.10f, 0.95f)
        put(PoseLandmarkId.RIGHT_SHOULDER, shoulderX + shoulderHalfWidth, shoulderY, 0.08f, 0.75f)
        put(PoseLandmarkId.LEFT_HIP, shoulderX - shoulderHalfWidth, hipY, -0.10f, 0.95f)
        put(PoseLandmarkId.RIGHT_HIP, shoulderX + shoulderHalfWidth, hipY, 0.08f, 0.75f)
        put(PoseLandmarkId.LEFT_ELBOW, elbow.x, elbow.y, -0.10f, 0.95f)
        put(PoseLandmarkId.LEFT_WRIST, wristX, wristY, -0.10f, 0.95f)
        put(PoseLandmarkId.LEFT_INDEX, wristX - 0.02f, wristY, -0.10f, 0.95f)
        put(PoseLandmarkId.LEFT_PINKY, wristX - 0.01f, wristY + 0.005f, -0.10f, 0.95f)
        put(PoseLandmarkId.RIGHT_ELBOW, 0.55f, 0.50f, 0.08f, 0.75f)
        put(PoseLandmarkId.RIGHT_WRIST, 0.55f, 0.62f, 0.08f, 0.75f)
        put(PoseLandmarkId.RIGHT_INDEX, 0.55f, 0.64f, 0.08f, 0.75f)
        put(PoseLandmarkId.RIGHT_PINKY, 0.56f, 0.64f, 0.08f, 0.75f)
        if (!omitFace) {
            put(PoseLandmarkId.NOSE, 0.49f, 0.20f, -0.10f, 0.95f)
            put(PoseLandmarkId.MOUTH_LEFT, 0.49f, 0.25f, -0.10f, 0.95f)
            put(PoseLandmarkId.LEFT_EYE, 0.49f, 0.17f, -0.10f, 0.95f)
            put(PoseLandmarkId.LEFT_EAR, 0.51f, 0.20f, -0.10f, 0.95f)
            put(PoseLandmarkId.RIGHT_EYE, 0.51f, 0.17f, 0.08f, 0.70f)
            put(PoseLandmarkId.RIGHT_EAR, 0.52f, 0.20f, 0.08f, 0.70f)
        }
        return PoseFrame(timestampMs, values)
    }

    private fun mirror(frame: PoseFrame): PoseFrame = frame.copy(
        landmarks = frame.landmarks.mapKeys { (id, _) ->
            when {
                id.name.startsWith("LEFT_") -> PoseLandmarkId.valueOf(id.name.replaceFirst("LEFT_", "RIGHT_"))
                id.name.startsWith("RIGHT_") -> PoseLandmarkId.valueOf(id.name.replaceFirst("RIGHT_", "LEFT_"))
                else -> id
            }
        }.mapValues { (_, sample) ->
            sample.copy(
                position = sample.position?.let { it.copy(x = 1f - it.x) },
                worldPosition = sample.worldPosition?.let { it.copy(x = -it.x) },
            )
        },
    )
}
