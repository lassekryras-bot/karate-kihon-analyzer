package dk.lasse.karatecliprecorder.training

import dk.lasse.karateanalyzer.capture.qom.QomDetectorConfig
import dk.lasse.karateanalyzer.capture.qom.QomFrameEvidence
import dk.lasse.karateanalyzer.core.PoseFrame

/** Version-scoped ownership while preserving the full-track context required by analyzers. */
object MovementFrameOwnership {
    fun frames(
        movementIndex: Int,
        movements: List<SessionMovement>,
        source: List<PoseFrame>,
        recordingEndUs: Long,
    ): List<PoseFrame> = source.filterNot { frame ->
        excludesEnd(movementIndex, movements, frame.timestampMs * 1_000L, recordingEndUs)
    }

    fun qom(
        movementIndex: Int,
        movements: List<SessionMovement>,
        source: List<QomFrameEvidence>,
        recordingEndUs: Long,
    ): List<QomFrameEvidence> = source.filterNot { evidence ->
        excludesEnd(movementIndex, movements, evidence.timestampMs * 1_000L, recordingEndUs)
    }

    fun ownsTimestamp(
        movementIndex: Int,
        movements: List<SessionMovement>,
        timestampUs: Long,
        recordingEndUs: Long,
    ): Boolean = !excludesEnd(movementIndex, movements, timestampUs, recordingEndUs)

    private fun excludesEnd(
        movementIndex: Int,
        movements: List<SessionMovement>,
        timestampUs: Long,
        recordingEndUs: Long,
    ): Boolean {
        val movement = movements.getOrNull(movementIndex) ?: return false
        if (timestampUs != movement.endUs) return false
        return when (movement.segmentationVersion) {
            QomDetectorConfig.SIDE_VIEW_ALTERNATING_PUNCH_V1 ->
                movements.getOrNull(movementIndex + 1)?.startUs == movement.endUs
            QomDetectorConfig.SIDE_VIEW_ALTERNATING_PUNCH_V2 -> movement.endUs != recordingEndUs
            else -> false
        }
    }
}
