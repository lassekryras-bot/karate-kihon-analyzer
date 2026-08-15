package dk.lasse.karatecliprecorder.learning

import dk.lasse.karateanalyzer.core.LandmarkSource
import dk.lasse.karateanalyzer.core.Point3
import dk.lasse.karateanalyzer.core.PoseFrame
import dk.lasse.karateanalyzer.core.PoseLandmarkId
import dk.lasse.karateanalyzer.core.PoseLandmarkSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CameraSetupSessionCoordinatorTest {
    @Test fun beginsWithOnlyViewSelection() {
        val state = CameraSetupSessionCoordinator().start()
        assertEquals(CameraSetupStage.SELECT_VIEW, state.stage)
        assertEquals(null, state.selectedView)
    }

    @Test fun stableCorrectFrontViewRequestsOnePicture() {
        val coordinator = CameraSetupSessionCoordinator(requiredHoldMs = 1_000L, movementResponseMs = 0L)
        coordinator.start()
        coordinator.selectView(CameraView.FRONT)

        assertFalse(coordinator.process(frame(0L, shoulderDepth = 0f)).shouldCapture)
        assertFalse(coordinator.process(frame(500L, shoulderDepth = 0f)).shouldCapture)
        assertTrue(coordinator.process(frame(1_000L, shoulderDepth = 0f)).shouldCapture)
        assertFalse(coordinator.process(frame(1_100L, shoulderDepth = 0f)).shouldCapture)
    }

    @Test fun wrongAngleGivesTurnInstructionAndResetsHold() {
        val coordinator = CameraSetupSessionCoordinator(requiredHoldMs = 1_000L, movementResponseMs = 0L)
        coordinator.start()
        coordinator.selectView(CameraView.SIDE_LEFT)

        val decision = coordinator.process(frame(0L, shoulderDepth = 0f))
        assertEquals(0f, decision.state.holdProgress)
        assertTrue(decision.state.message.contains("left", ignoreCase = true))
    }

    @Test fun distanceUsesApparentBodyHeight() {
        val coordinator = CameraSetupSessionCoordinator(movementResponseMs = 0L)
        coordinator.start()
        coordinator.selectView(CameraView.FRONT)

        val tooFar = coordinator.process(frame(0L, shoulderDepth = 0f, scale = 0.70f))

        assertTrue(tooFar.state.message.contains("closer", ignoreCase = true))
        assertEquals(0f, tooFar.state.holdProgress)
    }

    @Test fun horizontalMarginsMustBeBalancedRelativeToBodyHeight() {
        val coordinator = CameraSetupSessionCoordinator(movementResponseMs = 0L)
        coordinator.start()
        coordinator.selectView(CameraView.FRONT)

        val leftOfCenter = coordinator.process(frame(0L, shoulderDepth = 0f, centerX = 0.35f))

        assertTrue(leftOfCenter.state.message.contains("your left", ignoreCase = true))
    }

    @Test fun horizontalDirectionIsAlsoMirroredForUserOnImageRight() {
        val coordinator = CameraSetupSessionCoordinator(movementResponseMs = 0L)
        coordinator.start()
        coordinator.selectView(CameraView.FRONT)

        val rightOfCenter = coordinator.process(frame(0L, shoulderDepth = 0f, centerX = 0.65f))

        assertTrue(rightOfCenter.state.message.contains("your right", ignoreCase = true))
    }

    @Test fun unsafeTopOrBottomMarginPausesForCameraTiltAndCanRestart() {
        val coordinator = CameraSetupSessionCoordinator(movementResponseMs = 0L)
        coordinator.start()
        coordinator.selectView(CameraView.FRONT)

        val tooHigh = coordinator.process(frame(0L, shoulderDepth = 0f, centerY = 0.42f))

        assertEquals(CameraSetupStage.ADJUST_CAMERA, tooHigh.state.stage)
        assertTrue(tooHigh.state.message.contains("tilt", ignoreCase = true))

        val restarted = coordinator.restartAfterCameraAdjustment()
        assertEquals(CameraSetupStage.POSITIONING, restarted.stage)
        assertEquals(CameraView.FRONT, restarted.selectedView)
        assertEquals(0f, restarted.holdProgress)
    }

    @Test fun waitsAfterSpokenCommandBeforeChoosingNextInstruction() {
        val coordinator = CameraSetupSessionCoordinator(movementResponseMs = 2_500L)
        coordinator.start()
        val selection = coordinator.selectView(CameraView.FRONT)

        val immediately = coordinator.process(frame(1_000L, shoulderDepth = 0f, scale = 0.70f))
        val stillWaiting = coordinator.process(frame(3_499L, shoulderDepth = 0f, scale = 0.70f))
        val nextInstruction = coordinator.process(frame(3_500L, shoulderDepth = 0f, scale = 0.70f))

        assertEquals(selection.message, immediately.state.message)
        assertEquals(selection.message, stillWaiting.state.message)
        assertTrue(nextInstruction.state.message.contains("closer", ignoreCase = true))
    }

    @Test fun feetMustUseShoulderWidthSpacing() {
        val coordinator = CameraSetupSessionCoordinator(movementResponseMs = 0L)
        coordinator.start()
        coordinator.selectView(CameraView.FRONT)

        val narrow = coordinator.process(frame(0L, shoulderDepth = 0f, footSpacingScale = 0.50f))

        assertTrue(narrow.state.message.contains("farther apart", ignoreCase = true))
    }

    @Test fun feetMustBeParallel() {
        val coordinator = CameraSetupSessionCoordinator(movementResponseMs = 0L)
        coordinator.start()
        coordinator.selectView(CameraView.FRONT)

        val turnedFoot = coordinator.process(frame(0L, shoulderDepth = 0f, rightFootReversed = true))

        assertTrue(turnedFoot.state.message.contains("parallel", ignoreCase = true))
    }

    @Test fun punchGuidesAreOrderedFromJodanToGedan() {
        val guides = CameraSetupPunchGuides.calculate(frame(0L, shoulderDepth = 0f))

        assertEquals(listOf("JODAN", "CHUDAN", "GEDAN"), guides.map { it.label })
        assertTrue(guides.zipWithNext().all { (higher, lower) -> higher.y < lower.y })
    }

    private fun frame(
        timestampMs: Long,
        shoulderDepth: Float,
        centerX: Float = 0.50f,
        centerY: Float = 0.50f,
        scale: Float = 1f,
        footSpacingScale: Float = 1f,
        rightFootReversed: Boolean = false,
    ): PoseFrame {
        fun x(value: Float) = centerX + (value - 0.50f) * scale
        fun y(value: Float) = centerY + (value - 0.50f) * scale
        val points = mapOf(
            PoseLandmarkId.NOSE to Point3(x(0.50f), y(0.14f), 0f),
            PoseLandmarkId.LEFT_SHOULDER to Point3(x(0.42f), y(0.30f), -shoulderDepth / 2f),
            PoseLandmarkId.RIGHT_SHOULDER to Point3(x(0.58f), y(0.30f), shoulderDepth / 2f),
            PoseLandmarkId.LEFT_HIP to Point3(x(0.45f), y(0.58f), 0f),
            PoseLandmarkId.RIGHT_HIP to Point3(x(0.55f), y(0.58f), 0f),
            PoseLandmarkId.LEFT_ANKLE to Point3(x(0.50f - 0.08f * footSpacingScale), y(0.88f), 0f),
            PoseLandmarkId.RIGHT_ANKLE to Point3(x(0.50f + 0.08f * footSpacingScale), y(0.88f), 0f),
            PoseLandmarkId.LEFT_HEEL to Point3(x(0.50f - 0.08f * footSpacingScale - 0.02f), y(0.89f), 0f),
            PoseLandmarkId.RIGHT_HEEL to Point3(x(0.50f + 0.08f * footSpacingScale - 0.02f), y(0.89f), 0f),
            PoseLandmarkId.LEFT_FOOT_INDEX to Point3(x(0.50f - 0.08f * footSpacingScale + 0.02f), y(0.90f), 0f),
            PoseLandmarkId.RIGHT_FOOT_INDEX to Point3(
                x(0.50f + 0.08f * footSpacingScale + if (rightFootReversed) -0.04f else 0.02f),
                y(0.90f),
                0f,
            ),
        )
        return PoseFrame(timestampMs, PoseLandmarkId.entries.associateWith { id ->
            points[id]?.let { PoseLandmarkSample(it, visibility = 1f, presence = 1f, source = LandmarkSource.OBSERVED) }
                ?: PoseLandmarkSample(null)
        })
    }
}
