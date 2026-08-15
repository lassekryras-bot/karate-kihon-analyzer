package dk.lasse.karatecliprecorder.learning

import dk.lasse.karateanalyzer.core.PoseFrame
import dk.lasse.karateanalyzer.core.PoseLandmarkId
import kotlin.math.abs
import kotlin.math.sqrt

enum class CameraView(val displayName: String, val instruction: String) {
    SIDE_LEFT("Side - left", "Turn your left side toward the camera."),
    FORTY_FIVE_LEFT("45 degrees - left", "Turn halfway left, about 45 degrees."),
    FRONT("Front", "Face the camera directly."),
    FORTY_FIVE_RIGHT("45 degrees - right", "Turn halfway right, about 45 degrees."),
    SIDE_RIGHT("Side - right", "Turn your right side toward the camera."),
}

enum class CameraSetupStage { SELECT_VIEW, POSITIONING, ADJUST_CAMERA, CAPTURED, FAILED, CANCELLED }

data class CameraSetupState(
    val stage: CameraSetupStage = CameraSetupStage.SELECT_VIEW,
    val selectedView: CameraView? = null,
    val message: String = "Choose the camera view you want to set up.",
    val holdProgress: Float = 0f,
    val captureReady: Boolean = false,
)

data class CameraSetupDecision(
    val state: CameraSetupState,
    val shouldCapture: Boolean = false,
    val captureFrame: PoseFrame? = null,
)

data class CameraSetupPunchGuide(val label: String, val y: Float, val leftX: Float, val rightX: Float)

object CameraSetupPunchGuides {
    fun calculate(frame: PoseFrame): List<CameraSetupPunchGuide> {
        fun point(id: PoseLandmarkId) = frame.landmarks[id]?.position
        val nose = point(PoseLandmarkId.NOSE) ?: return emptyList()
        val leftShoulder = point(PoseLandmarkId.LEFT_SHOULDER) ?: return emptyList()
        val rightShoulder = point(PoseLandmarkId.RIGHT_SHOULDER) ?: return emptyList()
        val leftHip = point(PoseLandmarkId.LEFT_HIP) ?: return emptyList()
        val rightHip = point(PoseLandmarkId.RIGHT_HIP) ?: return emptyList()
        val shoulderY = (leftShoulder.y + rightShoulder.y) * 0.5f
        val hipY = (leftHip.y + rightHip.y) * 0.5f
        val torsoHeight = (hipY - shoulderY).coerceAtLeast(0.01f)
        val centerX = (leftShoulder.x + rightShoulder.x + leftHip.x + rightHip.x) * 0.25f
        val bodyWidth = maxOf(abs(leftShoulder.x - rightShoulder.x), abs(leftHip.x - rightHip.x), 0.12f)
        val halfLineWidth = bodyWidth * 0.85f
        val leftX = (centerX - halfLineWidth).coerceIn(0.02f, 0.98f)
        val rightX = (centerX + halfLineWidth).coerceIn(0.02f, 0.98f)
        return listOf(
            CameraSetupPunchGuide("JODAN", nose.y + (shoulderY - nose.y) * 0.50f, leftX, rightX),
            CameraSetupPunchGuide("CHUDAN", shoulderY + torsoHeight * 0.42f, leftX, rightX),
            CameraSetupPunchGuide("GEDAN", shoulderY + torsoHeight * 0.78f, leftX, rightX),
        )
    }
}

class CameraSetupSessionCoordinator(
    private val requiredHoldMs: Long = 1_200L,
    private val movementResponseMs: Long = 2_500L,
) {
    private var state = CameraSetupState()
    private var readySinceMs: Long? = null
    private var captureRequested = false
    private var generation = 0L
    private var guidanceBlockedUntilMs: Long? = null

    @Synchronized fun currentState() = state
    @Synchronized fun currentGenerationToken() = generation

    @Synchronized fun start(): CameraSetupState {
        generation++
        readySinceMs = null
        captureRequested = false
        guidanceBlockedUntilMs = null
        state = CameraSetupState()
        return state
    }

    @Synchronized fun selectView(view: CameraView): CameraSetupState {
        readySinceMs = null
        captureRequested = false
        guidanceBlockedUntilMs = null
        state = CameraSetupState(
            stage = CameraSetupStage.POSITIONING,
            selectedView = view,
            message = "${view.instruction} Step back until your whole body is visible.",
        )
        return state
    }

    @Synchronized fun process(frame: PoseFrame): CameraSetupDecision {
        val view = state.selectedView
        if (state.stage != CameraSetupStage.POSITIONING || view == null || captureRequested) {
            return CameraSetupDecision(state)
        }
        val startingResponseWindow = guidanceBlockedUntilMs == null
        val blockedUntil = guidanceBlockedUntilMs ?: (frame.timestampMs + movementResponseMs).also {
            // The view-selection instruction has just been spoken. Give the user time to act
            // before replacing it with the first measured correction.
            guidanceBlockedUntilMs = it
        }
        if (startingResponseWindow && movementResponseMs > 0L) return CameraSetupDecision(state)
        if (frame.timestampMs < blockedUntil) return CameraSetupDecision(state)

        val guidance = evaluate(frame, view)
        if (!guidance.ready) {
            readySinceMs = null
            if (guidance.message != state.message) {
                state = state.copy(message = guidance.message, holdProgress = 0f, captureReady = false)
                guidanceBlockedUntilMs = frame.timestampMs + movementResponseMs
            }
            return CameraSetupDecision(state)
        }
        val started = if (guidance.ready) readySinceMs ?: frame.timestampMs.also { readySinceMs = it } else null
        val heldMs = started?.let { (frame.timestampMs - it).coerceAtLeast(0L) } ?: 0L
        val progress = if (guidance.ready) (heldMs.toFloat() / requiredHoldMs).coerceIn(0f, 1f) else 0f
        val ready = guidance.ready && heldMs >= requiredHoldMs
        state = state.copy(
            message = if (ready) "Perfect. Taking the picture." else if (guidance.ready) "Good. Hold still." else guidance.message,
            holdProgress = progress,
            captureReady = ready,
        )
        if (ready) captureRequested = true
        return CameraSetupDecision(state, shouldCapture = ready, captureFrame = frame.takeIf { ready })
    }

    @Synchronized fun captureSaved(): CameraSetupState {
        state = state.copy(
            stage = CameraSetupStage.CAPTURED,
            message = "Camera position saved for ${state.selectedView?.displayName ?: "this view"}.",
            holdProgress = 1f,
            captureReady = false,
        )
        return state
    }

    @Synchronized fun restartAfterCameraAdjustment(): CameraSetupState {
        val view = state.selectedView ?: return start()
        return selectView(view)
    }

    @Synchronized fun fail(message: String): CameraSetupState {
        state = state.copy(stage = CameraSetupStage.FAILED, message = message, captureReady = false)
        return state
    }

    @Synchronized fun cancel(): CameraSetupState {
        generation++
        state = state.copy(stage = CameraSetupStage.CANCELLED, message = "Camera setup cancelled.")
        return state
    }

    private fun evaluate(frame: PoseFrame, view: CameraView): Guidance {
        fun sample(id: PoseLandmarkId) = frame.landmarks[id]?.takeIf { it.isObserved(MIN_CONFIDENCE) }
        fun point(id: PoseLandmarkId) = sample(id)?.position
        fun stancePoint(id: PoseLandmarkId) = sample(id)?.let { it.worldPosition ?: it.position }

        // Correction priority: tracking -> distance/safe margins -> horizontal center -> stance -> angle.
        // Only one correction is exposed at a time, so the spoken coaching stays actionable.
        val nose = point(PoseLandmarkId.NOSE) ?: return Guidance("Step into the camera view.")
        val leftShoulder = point(PoseLandmarkId.LEFT_SHOULDER) ?: return Guidance("Keep both shoulders visible.")
        val rightShoulder = point(PoseLandmarkId.RIGHT_SHOULDER) ?: return Guidance("Keep both shoulders visible.")
        val leftHip = point(PoseLandmarkId.LEFT_HIP) ?: return Guidance("Keep your whole body visible.")
        val rightHip = point(PoseLandmarkId.RIGHT_HIP) ?: return Guidance("Keep your whole body visible.")
        val leftAnkle = point(PoseLandmarkId.LEFT_ANKLE) ?: return Guidance("Step farther back so both feet are visible.")
        val rightAnkle = point(PoseLandmarkId.RIGHT_ANKLE) ?: return Guidance("Step farther back so both feet are visible.")
        val leftHeel = point(PoseLandmarkId.LEFT_HEEL) ?: return Guidance("Keep both heels visible.")
        val rightHeel = point(PoseLandmarkId.RIGHT_HEEL) ?: return Guidance("Keep both heels visible.")
        val leftToe = point(PoseLandmarkId.LEFT_FOOT_INDEX) ?: return Guidance("Keep both feet fully visible.")
        val rightToe = point(PoseLandmarkId.RIGHT_FOOT_INDEX) ?: return Guidance("Keep both feet fully visible.")

        val shoulderY = (leftShoulder.y + rightShoulder.y) * 0.5f
        val ankleY = (leftAnkle.y + rightAnkle.y) * 0.5f
        val noseToAnkleHeight = ankleY - nose.y
        if (noseToAnkleHeight <= 0f) return Guidance("Stand upright where your whole body is visible.")

        // The pose model locates the nose rather than the crown. Estimate the top of the head
        // from the nose-to-shoulder distance, and add a small foot allowance below the ankles.
        val estimatedTop = nose.y - (shoulderY - nose.y).coerceAtLeast(0f) * HEAD_ABOVE_NOSE_RATIO
        val estimatedBottom = ankleY + noseToAnkleHeight * FOOT_BELOW_ANKLE_RATIO
        val estimatedHeight = estimatedBottom - estimatedTop

        // Apparent body height is the useful distance proxy for a fixed phone camera. This makes
        // the rule work for short and tall users without knowing their real-world height.
        if (estimatedHeight > MAX_FRAME_FILL) {
            return Guidance("Move farther back so your whole body fits with space above and below.")
        }
        if (estimatedHeight < MIN_FRAME_FILL) return Guidance("Move closer to the camera.")
        if (estimatedTop < SAFE_EDGE_MARGIN || estimatedBottom > 1f - SAFE_EDGE_MARGIN) {
            // Body size is already correct, so more distance would make the user too small. Pause
            // while the solo user walks to the phone, changes its tilt, and explicitly restarts.
            val tiltDirection = if (estimatedTop < SAFE_EDGE_MARGIN) "up" else "down"
            state = state.copy(
                stage = CameraSetupStage.ADJUST_CAMERA,
                message = "Camera tilt needs adjustment. Tilt the camera $tiltDirection, then tap Camera adjusted to restart.",
                holdProgress = 0f,
                captureReady = false,
            )
            return Guidance(state.message)
        }

        val horizontalPoints = listOf(leftShoulder, rightShoulder, leftHip, rightHip, leftAnkle, rightAnkle)
        val bodyCenterX = (horizontalPoints.minOf { it.x } + horizontalPoints.maxOf { it.x }) * 0.5f
        val centeringTolerance = (estimatedHeight * CENTER_TOLERANCE_BODY_RATIO)
            .coerceIn(MIN_CENTER_TOLERANCE, MAX_CENTER_TOLERANCE)
        // The back camera faces the user. Image-left is therefore the user's right side, so the
        // movement command must be mirrored to stay anatomical from the user's perspective.
        if (bodyCenterX < 0.5f - centeringTolerance) return Guidance("Move to your left.")
        if (bodyCenterX > 0.5f + centeringTolerance) return Guidance("Move to your right.")

        val stanceLeftShoulder = stancePoint(PoseLandmarkId.LEFT_SHOULDER) ?: leftShoulder
        val stanceRightShoulder = stancePoint(PoseLandmarkId.RIGHT_SHOULDER) ?: rightShoulder
        val stanceLeftAnkle = stancePoint(PoseLandmarkId.LEFT_ANKLE) ?: leftAnkle
        val stanceRightAnkle = stancePoint(PoseLandmarkId.RIGHT_ANKLE) ?: rightAnkle
        val stanceLeftHeel = stancePoint(PoseLandmarkId.LEFT_HEEL) ?: leftHeel
        val stanceRightHeel = stancePoint(PoseLandmarkId.RIGHT_HEEL) ?: rightHeel
        val stanceLeftToe = stancePoint(PoseLandmarkId.LEFT_FOOT_INDEX) ?: leftToe
        val stanceRightToe = stancePoint(PoseLandmarkId.RIGHT_FOOT_INDEX) ?: rightToe
        val shoulderSpacing = horizontalDistance(stanceLeftShoulder, stanceRightShoulder).coerceAtLeast(0.001f)
        val footSpacingRatio = horizontalDistance(stanceLeftAnkle, stanceRightAnkle) / shoulderSpacing
        if (footSpacingRatio < MIN_FOOT_TO_SHOULDER_SPACING) {
            return Guidance("Move your feet farther apart until they are shoulder-width.")
        }
        if (footSpacingRatio > MAX_FOOT_TO_SHOULDER_SPACING) {
            return Guidance("Move your feet closer together until they are shoulder-width.")
        }
        if (!feetAreParallel(stanceLeftHeel, stanceLeftToe, stanceRightHeel, stanceRightToe)) {
            return Guidance("Make your feet parallel and point them in the same direction.")
        }

        // MediaPipe z decreases toward the camera. Shoulder depth gives a stable turn estimate.
        val shoulderWidth = abs(leftShoulder.x - rightShoulder.x).coerceAtLeast(0.025f)
        val signedTurn = ((rightShoulder.z - leftShoulder.z) / shoulderWidth).coerceIn(-2f, 2f)
        val angleMessage = angleGuidance(view, signedTurn)
        return if (angleMessage == null) Guidance("Good. Hold still.", ready = true) else Guidance(angleMessage)
    }

    private fun angleGuidance(view: CameraView, turn: Float): String? {
        val target = when (view) {
            CameraView.SIDE_LEFT -> 1.15f
            CameraView.FORTY_FIVE_LEFT -> 0.52f
            CameraView.FRONT -> 0f
            CameraView.FORTY_FIVE_RIGHT -> -0.52f
            CameraView.SIDE_RIGHT -> -1.15f
        }
        val tolerance = if (view == CameraView.FRONT) 0.35f else 0.42f
        if (abs(turn - target) <= tolerance) return null
        if (view == CameraView.FRONT) return "Turn back toward the front and face the camera."
        val selectedSide = if (target > 0f) "left" else "right"
        val needsMoreTurn = if (target > 0f) turn < target else turn > target
        return if (needsMoreTurn) {
            "Turn more so your $selectedSide side comes toward the camera."
        } else {
            "Turn slightly back toward the front."
        }
    }

    private data class Guidance(val message: String, val ready: Boolean = false)

    private fun horizontalDistance(first: dk.lasse.karateanalyzer.core.Point3, second: dk.lasse.karateanalyzer.core.Point3): Float {
        val dx = first.x - second.x
        val dz = first.z - second.z
        return sqrt(dx * dx + dz * dz)
    }

    private fun feetAreParallel(
        leftHeel: dk.lasse.karateanalyzer.core.Point3,
        leftToe: dk.lasse.karateanalyzer.core.Point3,
        rightHeel: dk.lasse.karateanalyzer.core.Point3,
        rightToe: dk.lasse.karateanalyzer.core.Point3,
    ): Boolean {
        val leftX = leftToe.x - leftHeel.x
        val leftZ = leftToe.z - leftHeel.z
        val rightX = rightToe.x - rightHeel.x
        val rightZ = rightToe.z - rightHeel.z
        val leftLength = sqrt(leftX * leftX + leftZ * leftZ)
        val rightLength = sqrt(rightX * rightX + rightZ * rightZ)
        if (leftLength < MIN_FOOT_VECTOR_LENGTH || rightLength < MIN_FOOT_VECTOR_LENGTH) return false
        val cosine = (leftX * rightX + leftZ * rightZ) / (leftLength * rightLength)
        return cosine >= MIN_PARALLEL_COSINE
    }

    companion object {
        private const val MIN_CONFIDENCE = 0.50f
        private const val HEAD_ABOVE_NOSE_RATIO = 0.55f
        private const val FOOT_BELOW_ANKLE_RATIO = 0.015f
        private const val MIN_FRAME_FILL = 0.62f
        private const val MAX_FRAME_FILL = 0.90f
        private const val SAFE_EDGE_MARGIN = 0.015f
        private const val CENTER_TOLERANCE_BODY_RATIO = 0.07f
        private const val MIN_CENTER_TOLERANCE = 0.035f
        private const val MAX_CENTER_TOLERANCE = 0.065f
        private const val MIN_FOOT_TO_SHOULDER_SPACING = 0.65f
        private const val MAX_FOOT_TO_SHOULDER_SPACING = 1.35f
        private const val MIN_FOOT_VECTOR_LENGTH = 0.01f
        private const val MIN_PARALLEL_COSINE = 0.82f
    }
}
