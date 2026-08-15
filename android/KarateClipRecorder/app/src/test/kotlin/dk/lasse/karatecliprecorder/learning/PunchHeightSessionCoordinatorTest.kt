package dk.lasse.karatecliprecorder.learning

import dk.lasse.karateanalyzer.core.LandmarkSource
import dk.lasse.karateanalyzer.core.Point3
import dk.lasse.karateanalyzer.core.PoseFrame
import dk.lasse.karateanalyzer.core.PoseLandmarkId
import dk.lasse.karateanalyzer.core.PoseLandmarkSample
import dk.lasse.karateanalyzer.core.PunchHeightTargetType
import dk.lasse.karatecliprecorder.mediapipeposeadapter.LivePoseLandmarkerOutput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PunchHeightSessionCoordinatorTest {
    @Test fun staleGenerationCannotAdvanceTheSession() {
        val coordinator = PunchHeightSessionCoordinator()
        val started = coordinator.start()
        val stale = LivePoseLandmarkerOutput(
            poseFrame = pose(0L),
            inputWidth = 640,
            inputHeight = 480,
            inferenceLatencyMs = 8L,
            generationToken = started.generationToken - 1L,
        )

        val decision = coordinator.process(stale)

        assertEquals(PunchHeightSessionStage.SESSION_SETUP, decision.state.stage)
        assertEquals(null, decision.state.poseFrame)
    }

    @Test fun syntheticTraceCapturesOncePerTargetAndReachesReview() {
        val coordinator = PunchHeightSessionCoordinator()
        coordinator.start()
        var time = 0L
        while (coordinator.currentState().stage == PunchHeightSessionStage.SESSION_SETUP && time < 3_000L) {
            coordinator.process(output(coordinator, pose(time)))
            time += 100L
        }
        while (coordinator.currentState().stage == PunchHeightSessionStage.BODY_INITIALIZATION && time < 6_000L) {
            coordinator.process(output(coordinator, pose(time)))
            time += 100L
        }
        assertEquals(PunchHeightTargetType.JODAN, coordinator.currentState().targetType)

        val captured = mutableListOf<PunchHeightTargetType>()
        listOf(
            PunchHeightTargetType.JODAN to -0.244f,
            PunchHeightTargetType.CHUDAN to 0.45f,
            PunchHeightTargetType.GEDAN to 0.80f,
        ).forEach { (target, ratio) ->
            var snapshot = coordinator.process(output(coordinator, pose(time, ratio))).captureSnapshot
            var attempts = 0
            while (snapshot == null && attempts < 100) {
                time += 100L
                snapshot = coordinator.process(output(coordinator, pose(time, ratio))).captureSnapshot
                attempts++
            }
            val accepted = assertNotNull(snapshot, "No capture for $target; state=${coordinator.currentState()}")
            assertEquals(target, accepted.targetType)
            captured += accepted.targetType
            coordinator.captureSaved(target)
            coordinator.advanceAfterCapture()
            time += 900L
        }

        assertEquals(PunchHeightTargetType.entries.toList(), captured.toList())
        assertEquals(PunchHeightSessionStage.SESSION_REVIEW, coordinator.currentState().stage)
        assertTrue(coordinator.currentState().capturedTargets.containsAll(PunchHeightTargetType.entries))
    }

    private fun output(coordinator: PunchHeightSessionCoordinator, frame: PoseFrame) = LivePoseLandmarkerOutput(
        poseFrame = frame,
        inputWidth = 640,
        inputHeight = 480,
        inferenceLatencyMs = 8L,
        generationToken = coordinator.currentGenerationToken(),
    )

    private fun pose(timestampMs: Long, targetRatio: Float? = null): PoseFrame {
        val shoulderY = 0.38f
        val hipY = 0.68f
        val wristY = targetRatio?.let { shoulderY + (hipY - shoulderY) * it } ?: 0.62f
        val wristX = 0.14f
        val elbow = Point3(0.32f, (shoulderY + wristY) / 2f, -0.1f)
        val landmarks = mutableMapOf<PoseLandmarkId, PoseLandmarkSample>()
        fun put(id: PoseLandmarkId, x: Float, y: Float, z: Float, confidence: Float) {
            landmarks[id] = PoseLandmarkSample(
                position = Point3(x, y, z),
                worldPosition = Point3(x, y, z),
                visibility = confidence,
                presence = confidence,
                source = LandmarkSource.OBSERVED,
            )
        }
        put(PoseLandmarkId.LEFT_SHOULDER, 0.49f, shoulderY, -0.1f, 0.95f)
        put(PoseLandmarkId.RIGHT_SHOULDER, 0.51f, shoulderY, 0.08f, 0.75f)
        put(PoseLandmarkId.LEFT_HIP, 0.49f, hipY, -0.1f, 0.95f)
        put(PoseLandmarkId.RIGHT_HIP, 0.51f, hipY, 0.08f, 0.75f)
        put(PoseLandmarkId.LEFT_ELBOW, elbow.x, elbow.y, -0.1f, 0.95f)
        put(PoseLandmarkId.LEFT_WRIST, wristX, wristY, -0.1f, 0.95f)
        put(PoseLandmarkId.LEFT_INDEX, wristX - 0.02f, wristY, -0.1f, 0.95f)
        put(PoseLandmarkId.LEFT_PINKY, wristX - 0.01f, wristY, -0.1f, 0.95f)
        put(PoseLandmarkId.RIGHT_ELBOW, 0.55f, 0.50f, 0.08f, 0.75f)
        put(PoseLandmarkId.RIGHT_WRIST, 0.55f, 0.62f, 0.08f, 0.75f)
        put(PoseLandmarkId.RIGHT_INDEX, 0.55f, 0.64f, 0.08f, 0.75f)
        put(PoseLandmarkId.RIGHT_PINKY, 0.56f, 0.64f, 0.08f, 0.75f)
        put(PoseLandmarkId.NOSE, 0.49f, 0.20f, -0.1f, 0.95f)
        put(PoseLandmarkId.MOUTH_LEFT, 0.49f, 0.25f, -0.1f, 0.95f)
        put(PoseLandmarkId.LEFT_EYE, 0.49f, 0.17f, -0.1f, 0.95f)
        put(PoseLandmarkId.LEFT_EAR, 0.51f, 0.20f, -0.1f, 0.95f)
        put(PoseLandmarkId.RIGHT_EYE, 0.51f, 0.17f, 0.08f, 0.70f)
        put(PoseLandmarkId.RIGHT_EAR, 0.52f, 0.20f, 0.08f, 0.70f)
        return PoseFrame(timestampMs, landmarks)
    }
}
