package dk.lasse.karateanalyzer.core

/** A normalized, analyzer-neutral 3D point. */
data class Point3(
    val x: Float,
    val y: Float,
    val z: Float,
) {
    operator fun plus(other: Point3) = Point3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Point3) = Point3(x - other.x, y - other.y, z - other.z)
    operator fun times(scale: Float) = Point3(x * scale, y * scale, z * scale)
}

enum class LandmarkSource {
    OBSERVED,
    PREDICTED,
    INTERPOLATED,
    MISSING,
}

data class LandmarkSample(
    val position: Point3?,
    val confidence: Float,
    val source: LandmarkSource,
)

enum class HandLandmarkId {
    WRIST,
    THUMB_CMC,
    THUMB_MCP,
    THUMB_IP,
    THUMB_TIP,
    INDEX_MCP,
    INDEX_PIP,
    INDEX_DIP,
    INDEX_TIP,
    MIDDLE_MCP,
    MIDDLE_PIP,
    MIDDLE_DIP,
    MIDDLE_TIP,
    RING_MCP,
    RING_PIP,
    RING_DIP,
    RING_TIP,
    LITTLE_MCP,
    LITTLE_PIP,
    LITTLE_DIP,
    LITTLE_TIP,
}

enum class Handedness {
    LEFT,
    RIGHT,
    UNKNOWN,
}

data class HandFrame(
    val timestampMs: Long,
    val handedness: Handedness,
    val landmarks: Map<HandLandmarkId, LandmarkSample>,
)

enum class VerificationStatus {
    NO_HAND,
    INSUFFICIENT_VISIBILITY,
    NOT_READY,
    IN_PROGRESS,
    HOLDING,
    PASSED,
}

enum class FeedbackCode {
    NONE,
    MOVE_INTO_GUIDE,
    MOVE_CLOSER,
    OPEN_FINGERS,
    BEND_FINGERTIPS_MORE,
    CLOSE_FINGERS_MORE,
    MOVE_THUMB_ACROSS,
    HOLD_STILL,
    GOOD,
}

data class StepAnalysisResult(
    val status: VerificationStatus,
    val score: Float,
    val holdProgress: Float,
    val feedbackCode: FeedbackCode,
    val criticalLandmarksVisible: Boolean,
)
