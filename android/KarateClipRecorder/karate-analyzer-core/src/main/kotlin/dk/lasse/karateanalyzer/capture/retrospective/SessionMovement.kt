package dk.lasse.karateanalyzer.capture.retrospective

/**
 * Lifecycle status of per-movement analysis.
 *
 * Movements are emitted in [DETECTED] state immediately after retrospective segmentation,
 * allowing the user interface to show the detected movement list before full biomechanical
 * analysis finishes.
 */
enum class MovementAnalysisStatus {
    DETECTED,
    ANALYZING,
    COMPLETED,
    FAILED,
}

/**
 * Represents a single activity cue (spoken count, beep, or instruction) on the session timeline.
 * Cues are contextual hints only and do not directly define movement boundaries.
 */
data class CueEvent(
    val id: String,
    val cueName: String,
    val monotonicTimestampMs: Long,
    val videoTimestampMs: Long? = null,
)

/**
 * An independent timeline of activity cues emitted during the continuous recording.
 */
data class CueTimeline(
    val sessionRecordingId: String,
    val cues: List<CueEvent> = emptyList(),
)

/**
 * Logical movement interval over the continuous master recording.
 *
 * Refers to the master video plus time bounds, completely avoiding physical MP4 cutting
 * unless explicitly requested for export/sharing.
 */
data class SessionMovement(
    val movementNumber: Int,
    val sourceRecordingPath: String,
    val logicalStartTimestampMs: Long,
    val logicalEndTimestampMs: Long,
    val durationMs: Long = logicalEndTimestampMs - logicalStartTimestampMs,
    val retainedStartTimestampMs: Long,
    val retainedEndTimestampMs: Long,
    val preferredSnapshotTimestampMs: Long? = null,
    val associatedCue: CueEvent? = null,
    val segmentationConfidence: Double = 1.0,
    val analysisStatus: MovementAnalysisStatus = MovementAnalysisStatus.DETECTED,
)

