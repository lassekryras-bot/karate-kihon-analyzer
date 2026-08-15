package dk.lasse.karatecliprecorder.learning

import dk.lasse.karateanalyzer.core.BodyInitializationEvaluation
import dk.lasse.karateanalyzer.core.PoseFrame
import dk.lasse.karateanalyzer.core.PunchHeightAnalyzer
import dk.lasse.karateanalyzer.core.PunchHeightCaptureSnapshot
import dk.lasse.karateanalyzer.core.PunchHeightEvaluation
import dk.lasse.karateanalyzer.core.PunchHeightGuidanceState
import dk.lasse.karateanalyzer.core.PunchHeightTargetType
import dk.lasse.karateanalyzer.core.SetupEvaluation
import dk.lasse.karateanalyzer.core.SetupGuidance
import dk.lasse.karatecliprecorder.mediapipeposeadapter.LivePoseLandmarkerOutput

enum class PunchHeightSessionStage {
    IDLE,
    SESSION_SETUP,
    BODY_INITIALIZATION,
    JODAN_INSTRUCTION,
    JODAN_GUIDANCE,
    JODAN_HOLD,
    JODAN_CAPTURED,
    CHUDAN_INSTRUCTION,
    CHUDAN_GUIDANCE,
    CHUDAN_HOLD,
    CHUDAN_CAPTURED,
    GEDAN_INSTRUCTION,
    GEDAN_GUIDANCE,
    GEDAN_HOLD,
    GEDAN_CAPTURED,
    SESSION_REVIEW,
    SESSION_COMPLETE,
    CANCELLED,
    FAILED,
}

data class PunchHeightSessionState(
    val stage: PunchHeightSessionStage = PunchHeightSessionStage.IDLE,
    val generationToken: Long = 0L,
    val targetType: PunchHeightTargetType? = null,
    val setupEvaluation: SetupEvaluation? = null,
    val initializationEvaluation: BodyInitializationEvaluation? = null,
    val evaluation: PunchHeightEvaluation? = null,
    val poseFrame: PoseFrame? = null,
    val inputWidth: Int = 640,
    val inputHeight: Int = 480,
    val chinProjectionMultiplier: Float = PunchHeightAnalyzer.DEFAULT_CHIN_PROJECTION_MULTIPLIER,
    val capturedTargets: Set<PunchHeightTargetType> = emptySet(),
    val message: String = "",
    val errorMessage: String? = null,
) {
    val isActive: Boolean get() = stage !in setOf(
        PunchHeightSessionStage.IDLE,
        PunchHeightSessionStage.SESSION_COMPLETE,
        PunchHeightSessionStage.CANCELLED,
        PunchHeightSessionStage.FAILED,
    )
}

data class PunchHeightFrameDecision(
    val state: PunchHeightSessionState,
    val captureSnapshot: PunchHeightCaptureSnapshot? = null,
)

class PunchHeightSessionCoordinator(
    private val analyzer: PunchHeightAnalyzer = PunchHeightAnalyzer(),
) {
    private var state = PunchHeightSessionState()

    @Synchronized fun currentState(): PunchHeightSessionState = state
    @Synchronized fun currentGenerationToken(): Long = state.generationToken

    @Synchronized fun start(): PunchHeightSessionState {
        analyzer.reset()
        state = PunchHeightSessionState(
            stage = PunchHeightSessionStage.SESSION_SETUP,
            generationToken = state.generationToken + 1L,
            message = "Move into the frame and turn sideways.",
        )
        return state
    }

    @Synchronized fun process(output: LivePoseLandmarkerOutput): PunchHeightFrameDecision {
        if (output.generationToken != state.generationToken || !state.isActive) {
            return PunchHeightFrameDecision(state)
        }
        val base = state.copy(
            poseFrame = output.poseFrame,
            inputWidth = output.inputWidth,
            inputHeight = output.inputHeight,
            errorMessage = null,
        )
        state = when (state.stage) {
            PunchHeightSessionStage.SESSION_SETUP -> processSetup(base, output.poseFrame)
            PunchHeightSessionStage.BODY_INITIALIZATION -> processInitialization(base, output.poseFrame)
            PunchHeightSessionStage.JODAN_INSTRUCTION,
            PunchHeightSessionStage.JODAN_GUIDANCE,
            PunchHeightSessionStage.JODAN_HOLD -> processTarget(base, PunchHeightTargetType.JODAN, output.poseFrame)
            PunchHeightSessionStage.CHUDAN_INSTRUCTION,
            PunchHeightSessionStage.CHUDAN_GUIDANCE,
            PunchHeightSessionStage.CHUDAN_HOLD -> processTarget(base, PunchHeightTargetType.CHUDAN, output.poseFrame)
            PunchHeightSessionStage.GEDAN_INSTRUCTION,
            PunchHeightSessionStage.GEDAN_GUIDANCE,
            PunchHeightSessionStage.GEDAN_HOLD -> processTarget(base, PunchHeightTargetType.GEDAN, output.poseFrame)
            else -> base
        }
        val evaluation = state.evaluation
        val targetType = state.targetType
        val snapshot = if (evaluation?.captureReady == true && targetType != null) {
            analyzer.captureSnapshot(targetType, evaluation, state.chinProjectionMultiplier)
        } else null
        if (snapshot != null) {
            state = state.copy(
                stage = capturedStage(snapshot.targetType),
                generationToken = state.generationToken + 1L,
                message = "Correct",
            )
        }
        return PunchHeightFrameDecision(state, snapshot)
    }

    @Synchronized fun captureSaved(target: PunchHeightTargetType): PunchHeightSessionState {
        state = state.copy(capturedTargets = state.capturedTargets + target, message = "Correct")
        return state
    }

    @Synchronized fun advanceAfterCapture(): PunchHeightSessionState {
        val next = when (state.stage) {
            PunchHeightSessionStage.JODAN_CAPTURED -> PunchHeightTargetType.CHUDAN
            PunchHeightSessionStage.CHUDAN_CAPTURED -> PunchHeightTargetType.GEDAN
            PunchHeightSessionStage.GEDAN_CAPTURED -> null
            else -> return state
        }
        analyzer.resetTarget()
        state = if (next == null) {
            state.copy(
                stage = PunchHeightSessionStage.SESSION_REVIEW,
                generationToken = state.generationToken + 1L,
                targetType = null,
                evaluation = null,
                message = "Preparing your review…",
            )
        } else {
            state.copy(
                stage = instructionStage(next),
                generationToken = state.generationToken + 1L,
                targetType = next,
                evaluation = null,
                message = introduction(next),
            )
        }
        return state
    }

    @Synchronized fun setChinProjectionMultiplier(value: Float): PunchHeightSessionState {
        val adjusted = value.coerceIn(
            PunchHeightAnalyzer.MIN_CHIN_PROJECTION_MULTIPLIER,
            PunchHeightAnalyzer.MAX_CHIN_PROJECTION_MULTIPLIER,
        )
        if (adjusted == state.chinProjectionMultiplier) return state
        analyzer.resetTarget()
        state = state.copy(
            chinProjectionMultiplier = adjusted,
            generationToken = state.generationToken + 1L,
            evaluation = null,
            message = "Jodan chin projection ${"%.2f".format(adjusted)}",
        )
        return state
    }

    @Synchronized fun markReviewReady(): PunchHeightSessionState {
        state = state.copy(stage = PunchHeightSessionStage.SESSION_REVIEW, message = "Review your three punch heights.")
        return state
    }

    @Synchronized fun complete(): PunchHeightSessionState {
        state = state.copy(stage = PunchHeightSessionStage.SESSION_COMPLETE, message = "Practice complete.")
        return state
    }

    @Synchronized fun cancel(): PunchHeightSessionState {
        analyzer.reset()
        state = state.copy(
            stage = PunchHeightSessionStage.CANCELLED,
            generationToken = state.generationToken + 1L,
            targetType = null,
            evaluation = null,
            message = "Session cancelled.",
        )
        return state
    }

    @Synchronized fun fail(message: String): PunchHeightSessionState {
        state = state.copy(
            stage = PunchHeightSessionStage.FAILED,
            generationToken = state.generationToken + 1L,
            errorMessage = message,
            message = message,
        )
        return state
    }

    private fun processSetup(base: PunchHeightSessionState, frame: PoseFrame): PunchHeightSessionState {
        val setup = analyzer.processSetup(frame)
        return if (setup.usable) {
            analyzer.resetBodyInitialization()
            base.copy(
                stage = PunchHeightSessionStage.BODY_INITIALIZATION,
                generationToken = base.generationToken + 1L,
                setupEvaluation = setup,
                message = "Hold still while I learn your body position.",
            )
        } else base.copy(setupEvaluation = setup, message = setup.guidance.userMessage())
    }

    private fun processInitialization(base: PunchHeightSessionState, frame: PoseFrame): PunchHeightSessionState {
        val initialization = analyzer.processBodyInitialization(frame, base.chinProjectionMultiplier)
        return if (initialization.bodyReference != null) {
            analyzer.resetTarget()
            base.copy(
                stage = PunchHeightSessionStage.JODAN_INSTRUCTION,
                generationToken = base.generationToken + 1L,
                targetType = PunchHeightTargetType.JODAN,
                initializationEvaluation = initialization,
                message = introduction(PunchHeightTargetType.JODAN),
            )
        } else base.copy(
            initializationEvaluation = initialization,
            message = "Keep your sideways stance still.",
        )
    }

    private fun processTarget(
        base: PunchHeightSessionState,
        target: PunchHeightTargetType,
        frame: PoseFrame,
    ): PunchHeightSessionState {
        val evaluation = analyzer.evaluateTarget(target, frame, base.chinProjectionMultiplier)
            ?: return base.copy(targetType = target, message = "Tracking is unreliable. Hold your body in view.")
        val stage = if (evaluation.holdProgress > 0f) holdStage(target) else guidanceStage(target)
        return base.copy(
            stage = stage,
            targetType = target,
            evaluation = evaluation,
            message = evaluation.guidance.userMessage(),
        )
    }

    companion object {
        fun introduction(target: PunchHeightTargetType): String = when (target) {
            PunchHeightTargetType.JODAN -> "Jodan. Extend a straight punch to chin height."
            PunchHeightTargetType.CHUDAN -> "Chudan. Extend a straight punch to solar plexus height."
            PunchHeightTargetType.GEDAN -> "Gedan. Extend a straight punch to lower abdomen height."
        }

        private fun instructionStage(target: PunchHeightTargetType) = when (target) {
            PunchHeightTargetType.JODAN -> PunchHeightSessionStage.JODAN_INSTRUCTION
            PunchHeightTargetType.CHUDAN -> PunchHeightSessionStage.CHUDAN_INSTRUCTION
            PunchHeightTargetType.GEDAN -> PunchHeightSessionStage.GEDAN_INSTRUCTION
        }

        private fun guidanceStage(target: PunchHeightTargetType) = when (target) {
            PunchHeightTargetType.JODAN -> PunchHeightSessionStage.JODAN_GUIDANCE
            PunchHeightTargetType.CHUDAN -> PunchHeightSessionStage.CHUDAN_GUIDANCE
            PunchHeightTargetType.GEDAN -> PunchHeightSessionStage.GEDAN_GUIDANCE
        }

        private fun holdStage(target: PunchHeightTargetType) = when (target) {
            PunchHeightTargetType.JODAN -> PunchHeightSessionStage.JODAN_HOLD
            PunchHeightTargetType.CHUDAN -> PunchHeightSessionStage.CHUDAN_HOLD
            PunchHeightTargetType.GEDAN -> PunchHeightSessionStage.GEDAN_HOLD
        }

        private fun capturedStage(target: PunchHeightTargetType) = when (target) {
            PunchHeightTargetType.JODAN -> PunchHeightSessionStage.JODAN_CAPTURED
            PunchHeightTargetType.CHUDAN -> PunchHeightSessionStage.CHUDAN_CAPTURED
            PunchHeightTargetType.GEDAN -> PunchHeightSessionStage.GEDAN_CAPTURED
        }
    }
}

fun SetupGuidance.userMessage(): String = when (this) {
    SetupGuidance.NO_BODY -> "Step into view so I can see your head, shoulders, hips, elbows, and wrists."
    SetupGuidance.MOVE_FARTHER_BACK -> "Move farther from the camera."
    SetupGuidance.MOVE_CLOSER -> "Move closer to the camera."
    SetupGuidance.KEEP_HEAD_IN_FRAME -> "Keep your head inside the frame."
    SetupGuidance.KEEP_ARM_IN_FRAME -> "Keep both arms inside the frame."
    SetupGuidance.TURN_MORE_SIDEWAYS -> "Turn more sideways to the camera."
    SetupGuidance.HOLD_STILL -> "Good framing. Hold still."
    SetupGuidance.CAMERA_READY -> "Camera position is ready."
}

fun PunchHeightGuidanceState.userMessage(): String = when (this) {
    PunchHeightGuidanceState.WAITING_FOR_ARM -> "Extend one arm into a punch."
    PunchHeightGuidanceState.ARM_NOT_EXTENDED -> "Straighten your arm."
    PunchHeightGuidanceState.FIST_TOO_HIGH -> "Lower your fist."
    PunchHeightGuidanceState.FIST_TOO_LOW -> "Raise your fist."
    PunchHeightGuidanceState.CORRECT_BUT_MOVING -> "Hold still."
    PunchHeightGuidanceState.CORRECT_AND_HOLDING -> "Good. Keep holding."
    PunchHeightGuidanceState.TRACKING_UNRELIABLE -> "Tracking is unreliable. Keep your body and punching hand visible."
    PunchHeightGuidanceState.CAPTURE_READY -> "Correct"
}
