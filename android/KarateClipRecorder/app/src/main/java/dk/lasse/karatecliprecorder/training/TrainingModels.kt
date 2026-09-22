package dk.lasse.karatecliprecorder.training

import java.util.UUID
import dk.lasse.karateanalyzer.capture.qom.MotionBodyProfile
import dk.lasse.karatecliprecorder.sharedcapture.CaptureType

fun trainingId(): String = UUID.randomUUID().toString()

// Wall-clock fields are epoch milliseconds. All media-relative fields use integer microseconds.
enum class SessionState { PREPARING, RECORDING, FINALIZING, RECORDED, LANDMARKS_PROCESSING, LANDMARKS_READY, CANCELLED, SEGMENTING, MOVEMENTS_AVAILABLE, ANALYZING, COMPLETED, PARTIAL, FAILED }
enum class SourceState { PENDING, AVAILABLE, DELETING, DELETED, MISSING, FAILED }
enum class ProcessingState { PENDING, PROCESSING, COMPLETED, FAILED }
enum class MovementState { DETECTED, ANALYZING, COMPLETED, PARTIAL, FAILED }
enum class AnalysisState { COMPLETED, PARTIAL, ABSTAINED, FAILED }
enum class ResultState { VALID, PARTIAL, ABSTAINED, FAILED }
enum class ValueType { NUMERIC, CATEGORICAL }
enum class ObservedView { FRONT, LEFT_SIDE, RIGHT_SIDE, OTHER, UNKNOWN }
enum class BodySide { LEFT, RIGHT, BILATERAL, WHOLE_BODY, UNKNOWN }

enum class RunMode { INITIAL, REANALYSIS, LANDMARK_REPROCESS }
enum class RunState { PENDING, PROCESSING, COMPLETED, PARTIAL, FAILED }

data class ProcessingRun(
    val runId: String = trainingId(),
    val sessionId: String,
    val createdAtMs: Long = System.currentTimeMillis(),
    val completedAtMs: Long? = null,
    val mode: RunMode = RunMode.INITIAL,
    val sourceLandmarkTrackId: String? = null,
    val planKey: String = RecordingProcessingPlans.STRAIGHT_PUNCH_TARGET_ANALYSIS.key,
    val planVersion: Int = RecordingProcessingPlans.STRAIGHT_PUNCH_TARGET_ANALYSIS.version,
    val segmenterVersion: String? = null,
    val analyzerKey: String? = null,
    val analyzerVersion: String? = null,
    val state: RunState = RunState.PROCESSING,
    val isCurrent: Boolean = false,
    val error: String? = null,
    val landmarkDurationMs: Long? = null,
    val segmentationDurationMs: Long? = null,
    val analysisDurationMs: Long? = null,
)

data class TrainingUser(val userId: String = trainingId(), val createdAtMs: Long = System.currentTimeMillis())
enum class QueueState { QUEUED, PROCESSING, READY, FAILED, DELETING }
enum class ProcessingPhase { QUEUED, LANDMARKS, SEGMENTATION, ANALYSIS, READY, FAILED }
data class RecordingProcessing(val sessionId: String, val queuedAtMs: Long,
    val state: QueueState = QueueState.QUEUED, val promotedAtMs: Long? = null,
    val manual: Boolean = false, val error: String? = null,
    val phase: ProcessingPhase = ProcessingPhase.QUEUED,
    val planKey: String = RecordingProcessingPlans.STRAIGHT_PUNCH_TARGET_ANALYSIS.key,
    val planVersion: Int = RecordingProcessingPlans.STRAIGHT_PUNCH_TARGET_ANALYSIS.version,
    val landmarkDurationMs: Long? = null, val segmentationDurationMs: Long? = null,
    val sourceLandmarkTrackId: String? = null, val segmentationVersion: String? = null)

data class RecordingProcessingPlan(
    val key: String,
    val version: Int,
    val requiresLandmarks: Boolean,
    val requiresSegmentation: Boolean,
    val analyzers: List<String>,
    val movementProfile: MotionBodyProfile = MotionBodyProfile.PUNCH,
)

object RecordingProcessingPlans {
    val STRAIGHT_PUNCH_SEGMENTS = RecordingProcessingPlan(
        key = "straight_punch_segments",
        version = 1,
        requiresLandmarks = true,
        requiresSegmentation = true,
        analyzers = emptyList(),
        movementProfile = MotionBodyProfile.PUNCH,
    )

    val STRAIGHT_PUNCH_TARGET_ANALYSIS = RecordingProcessingPlan(
        key = "straight_punch_target_analysis",
        version = 1,
        requiresLandmarks = true,
        requiresSegmentation = true,
        analyzers = listOf("straight_punch_target"),
        movementProfile = MotionBodyProfile.PUNCH,
    )

    fun forSession(session: RecordingSession): RecordingProcessingPlan {
        val isKick = (session.expectedCategory?.contains("kick", ignoreCase = true) == true) ||
            (session.activityKey?.contains("kick", ignoreCase = true) == true)
        val profile = if (isKick) MotionBodyProfile.KICK else MotionBodyProfile.PUNCH

        return when (session.activityKey) {
            AssistedCaptureSetup.ACTIVITY_KEY -> STRAIGHT_PUNCH_TARGET_ANALYSIS.copy(movementProfile = profile)
            else -> RecordingProcessingPlan("legacy_punch_analysis", 1, true, true, listOf("android_punch_height"), movementProfile = profile)
        }
    }
}
data class RecordingSession(
    val sessionId: String = trainingId(), val userId: String, val startedAtMs: Long,
    val endedAtMs: Long? = null, val activityKey: String? = null, val guided: Boolean = false,
    val expectedRepetitions: Int? = null, val state: SessionState = SessionState.RECORDING,
    val reason: String? = null,
    val cadenceUs: Long? = null, val spokenCounting: Boolean? = null, val firstCueDelayUs: Long? = null,
    val expectedActivity: String? = null, val expectedCategory: String? = null,
    val interruptionReason: String? = null,
    val callerId: String? = null, val parentId: String? = null,
    val captureTrigger: String? = null, val cueMode: String? = null,
    val requestedView: String? = null, val completionPrompt: String? = null,
    val captureOutcome: String? = null,
    val audioCuePackageVersionId: String? = null,
)
data class MasterRecording(
    val recordingId: String = trainingId(), val sessionId: String, val filePath: String,
    val createdAtMs: Long, val durationUs: Long? = null, val frameRate: Double? = null,
    val width: Int? = null, val height: Int? = null, val device: String? = null,
    val camera: String? = null, val sourceState: SourceState = SourceState.PENDING,
    val captureType: CaptureType = CaptureType.VIDEO, val mimeType: String? = "video/mp4",
    val rotation: Int? = null,
    val canonicalGeometryJson: String? = null,
)
data class LandmarkTrack(
    val landmarkTrackId: String = trainingId(), val recordingId: String,
    val pipelineKey: String, val pipelineVersion: String, val configuration: String,
    val filePath: String, val createdAtMs: Long = System.currentTimeMillis(),
    val state: ProcessingState = ProcessingState.PENDING, val sourceState: SourceState = SourceState.PENDING,
    val quality: String? = null, val sha256: String? = null,
    val formatId: String? = null, val formatVersion: Int? = null,
    val canonicalGeometryJson: String? = null,
)
data class SessionMovement(
    val movementId: String = trainingId(), val sessionId: String,
    val startUs: Long, val endUs: Long, val playbackStartUs: Long, val playbackEndUs: Long,
    val state: MovementState = MovementState.DETECTED,
    val segmentationSource: String, val segmentationVersion: String,
    val segmentationTrackId: String? = null, val segmentationConfidence: Double? = null,
    val runId: String? = null,
    val analysisFrameUs: Long? = null,
)
data class ObservationContext(
    val movementId: String, val view: ObservedView = ObservedView.UNKNOWN,
    val nearerSide: BodySide = BodySide.UNKNOWN, val bodyToCameraDegrees: Double? = null,
    val confidence: Double? = null, val evidence: String = "orientation_not_estimated",
)
data class SessionEvent(
    val sessionEventId: String = trainingId(), val sessionId: String,
    val type: String, val timestampUs: Long, val data: String? = null,
    val timingSource: String = "recording_timeline",
)
data class MovementSessionEvent(val movementId: String, val sessionEventId: String, val sessionId: String)
data class Label(val labelId: String = trainingId(), val machineKey: String, val displayText: String, val category: String)
data class MovementLabel(val movementId: String, val labelId: String, val source: String = "activity_context")
data class MovementAnalysis(
    val analysisId: String = trainingId(), val movementId: String, val analyzerKey: String,
    val analyzerVersion: String, val landmarkTrackId: String, val state: AnalysisState,
    val createdAtMs: Long = System.currentTimeMillis(), val reason: String? = null,
    val geometryJson: String? = null,
)
data class MeasurementResult(
    val measurementResultId: String = trainingId(), val analysisId: String, val measurementKey: String,
    val calculationVersion: String, val valueType: ValueType = ValueType.NUMERIC,
    val numericValue: Double? = null, val categoricalValue: String? = null,
    val side: BodySide = BodySide.UNKNOWN, val role: String = "unspecified",
    val state: ResultState, val reason: String? = null, val confidence: Double? = null,
    val uncertainty: Double? = null, val occurrenceUs: Long? = null, val frameIndex: Long? = null,
)
data class UserBodyMeasurement(
    val bodyMeasurementId: String = trainingId(), val userId: String, val type: String,
    val value: Double, val canonicalUnit: String = "cm", val measuredAtMs: Long, val source: String,
)
data class UserCalibration(
    val calibrationId: String = trainingId(), val userId: String, val type: String,
    val measuredAtMs: Long, val version: String, val source: String, val state: String, val data: String,
)
data class AnalysisBodyMeasurement(val analysisId: String, val bodyMeasurementId: String)
data class AnalysisCalibration(val analysisId: String, val calibrationId: String)
data class SessionBodyMeasurement(val sessionId: String, val bodyMeasurementId: String)

data class MovementEvidence(
    val movement: SessionMovement, val recording: MasterRecording, val observation: ObservationContext?,
    val labels: List<Label>, val events: List<SessionEvent>, val analyses: List<MovementAnalysis>,
    val measurements: List<MeasurementResult>, val landmarkTracks: List<LandmarkTrack>,
)

/** Ordered explicitly by application approval, newest first; version strings are never sorted lexically. */
data class AnalyzerPolicy(val analyzerKey: String, val approvedVersions: List<String>)
data class HistoryQuery(
    val userId: String, val measurementKey: String, val analyzer: AnalyzerPolicy,
    val labelKeys: Set<String> = emptySet(), val side: BodySide? = null, val role: String? = null,
    val acceptedStates: Set<ResultState> = setOf(ResultState.VALID), val minimumConfidence: Double? = null,
    val limit: Int = 100,
)

data class MeasurementDefinition(
    val key: String, val meaning: String, val canonicalUnit: String, val calculationVersion: String,
    val bodyRole: String, val technique: String?, val requiredViews: Set<ObservedView>,
    val orientationRequirement: String, val requiredLandmarks: Set<String>, val phase: String,
)

/** Only measurements actually produced by the Android adapter are registered. Ratios stay ratios. */
object TrainingMeasurements {
    const val PUNCH_HEIGHT_ERROR_TORSO_RATIO = "PUNCH_HEIGHT_ERROR_TORSO_RATIO"
    const val PUNCH_ELBOW_ANGLE = "PUNCH_ELBOW_ANGLE"
    const val PUNCH_CLOSEST_TARGET = "PUNCH_CLOSEST_TARGET"
    const val PUNCH_TARGET_ANGLE_ERROR_DEG = "PUNCH_TARGET_ANGLE_ERROR_DEG"
    const val PUNCH_JODAN_TARGET_ANGLE_ERROR_DEG = "PUNCH_JODAN_TARGET_ANGLE_ERROR_DEG"
    const val PUNCH_CHUDAN_TARGET_ANGLE_ERROR_DEG = "PUNCH_CHUDAN_TARGET_ANGLE_ERROR_DEG"
    const val PUNCH_GEDAN_TARGET_ANGLE_ERROR_DEG = "PUNCH_GEDAN_TARGET_ANGLE_ERROR_DEG"
    const val PUNCH_TARGET_CLASSIFICATION_MARGIN_DEG = "PUNCH_TARGET_CLASSIFICATION_MARGIN_DEG"

    val definitions = listOf(
        MeasurementDefinition(PUNCH_HEIGHT_ERROR_TORSO_RATIO, "Signed fist height error relative to target",
            "torso_ratio", "1", "strike", "straight_punch", setOf(ObservedView.LEFT_SIDE, ObservedView.RIGHT_SIDE),
            "Side-view setup accepted by PunchHeightAnalyzer", setOf("SHOULDER", "HIP", "WRIST", "ELBOW", "NOSE", "MOUTH"), "most_extended_observed_sample"),
        MeasurementDefinition(PUNCH_ELBOW_ANGLE, "Elbow angle in the camera image", "degree", "1", "strike",
            "straight_punch", setOf(ObservedView.LEFT_SIDE, ObservedView.RIGHT_SIDE),
            "Side-view setup accepted by PunchHeightAnalyzer", setOf("SHOULDER", "ELBOW", "WRIST"), "most_extended_observed_sample"),
        MeasurementDefinition(PUNCH_CLOSEST_TARGET, "Closest observed target height category", "categorical", "1",
            "strike", "straight_punch", setOf(ObservedView.LEFT_SIDE, ObservedView.RIGHT_SIDE),
            "Side-view setup accepted by StraightPunchTargetCalculator", setOf("SHOULDER", "ELBOW", "WRIST", "HIP"), "canonical_terminal_frame"),
        MeasurementDefinition("PUNCH_TARGET_ANGLE_ERROR_DEG", "Signed angular error to closest target in degrees", "degree", "1",
            "strike", "straight_punch", setOf(ObservedView.LEFT_SIDE, ObservedView.RIGHT_SIDE),
            "Side-view setup accepted by StraightPunchTargetCalculator", setOf("SHOULDER", "ELBOW", "WRIST", "HIP"), "canonical_terminal_frame"),
        MeasurementDefinition("PUNCH_JODAN_TARGET_ANGLE_ERROR_DEG", "Signed angular error to Jodan target in degrees", "degree", "1",
            "strike", "straight_punch", setOf(ObservedView.LEFT_SIDE, ObservedView.RIGHT_SIDE),
            "Side-view setup accepted by StraightPunchTargetCalculator", setOf("SHOULDER", "ELBOW", "WRIST", "HIP", "NOSE", "MOUTH"), "canonical_terminal_frame"),
        MeasurementDefinition("PUNCH_CHUDAN_TARGET_ANGLE_ERROR_DEG", "Signed angular error to Chudan target in degrees", "degree", "1",
            "strike", "straight_punch", setOf(ObservedView.LEFT_SIDE, ObservedView.RIGHT_SIDE),
            "Side-view setup accepted by StraightPunchTargetCalculator", setOf("SHOULDER", "ELBOW", "WRIST", "HIP"), "canonical_terminal_frame"),
        MeasurementDefinition("PUNCH_GEDAN_TARGET_ANGLE_ERROR_DEG", "Signed angular error to Gedan target in degrees", "degree", "1",
            "strike", "straight_punch", setOf(ObservedView.LEFT_SIDE, ObservedView.RIGHT_SIDE),
            "Side-view setup accepted by StraightPunchTargetCalculator", setOf("SHOULDER", "ELBOW", "WRIST", "HIP"), "canonical_terminal_frame"),
        MeasurementDefinition("PUNCH_TARGET_CLASSIFICATION_MARGIN_DEG", "Angular margin between closest and second-best target in degrees", "degree", "1",
            "strike", "straight_punch", setOf(ObservedView.LEFT_SIDE, ObservedView.RIGHT_SIDE),
            "Side-view setup accepted by StraightPunchTargetCalculator", setOf("SHOULDER", "ELBOW", "WRIST", "HIP"), "canonical_terminal_frame"),
    ).associateBy { it.key }
}
