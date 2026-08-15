package dk.lasse.karateanalyzer.core

enum class PoseLandmarkId {
    NOSE,
    LEFT_EYE_INNER, LEFT_EYE, LEFT_EYE_OUTER,
    RIGHT_EYE_INNER, RIGHT_EYE, RIGHT_EYE_OUTER,
    LEFT_EAR, RIGHT_EAR,
    MOUTH_LEFT, MOUTH_RIGHT,
    LEFT_SHOULDER, RIGHT_SHOULDER,
    LEFT_ELBOW, RIGHT_ELBOW,
    LEFT_WRIST, RIGHT_WRIST,
    LEFT_PINKY, RIGHT_PINKY,
    LEFT_INDEX, RIGHT_INDEX,
    LEFT_THUMB, RIGHT_THUMB,
    LEFT_HIP, RIGHT_HIP,
    LEFT_KNEE, RIGHT_KNEE,
    LEFT_ANKLE, RIGHT_ANKLE,
    LEFT_HEEL, RIGHT_HEEL,
    LEFT_FOOT_INDEX, RIGHT_FOOT_INDEX,
}

data class PoseLandmarkSample(
    val position: Point3?,
    val worldPosition: Point3? = null,
    val visibility: Float = 0f,
    val presence: Float = 0f,
    val source: LandmarkSource = LandmarkSource.MISSING,
) {
    val confidence: Float get() = minOf(visibility, presence).coerceIn(0f, 1f)
    fun isObserved(minimumConfidence: Float = 0.55f): Boolean =
        position != null && source == LandmarkSource.OBSERVED && confidence >= minimumConfidence
}

data class PoseFrame(
    val timestampMs: Long,
    val landmarks: Map<PoseLandmarkId, PoseLandmarkSample>,
)

data class TrackedPoseFrame(
    val timestampMs: Long,
    val landmarks: Map<PoseLandmarkId, PoseLandmarkSample>,
)

enum class VisibleSide { LEFT, RIGHT, UNKNOWN }
enum class PunchHeightTargetType { JODAN, CHUDAN, GEDAN }
enum class ActiveArm { LEFT, RIGHT, NONE }

data class BodyReference(
    val visibleSide: VisibleSide,
    val shoulderPoint: Point3,
    val hipPoint: Point3,
    val torsoAxis: Point3,
    val torsoLength: Float,
    val confidence: Float,
)

enum class ChinTrackingSource {
    FRESH_FACE,
    NOSE_RELATIVE,
    EAR_RELATIVE,
    EYE_RELATIVE,
    SHOULDER_RELATIVE,
    PREDICTED,
    LOST,
}

data class ChinEstimate(
    val rawPoint: Point3?,
    val smoothedPoint: Point3?,
    val torsoScalar: Float?,
    val source: ChinTrackingSource,
    val confidence: Float,
    val ageMs: Long,
    val captureEligible: Boolean,
)

data class PunchHeightTarget(
    val type: PunchHeightTargetType,
    val targetPoint: Point3,
    val torsoScalar: Float,
    val tolerance: Float,
    val confidence: Float,
    val sourceLandmarks: Set<PoseLandmarkId>,
    val calculationStrategy: String,
    val explanation: String,
    val captureEligible: Boolean,
    val chinEstimate: ChinEstimate? = null,
)

interface PunchHeightTargetModel {
    fun evaluate(
        frame: TrackedPoseFrame,
        bodyReference: BodyReference,
        chinProjectionMultiplier: Float,
    ): PunchHeightTarget?
}

enum class PunchHeightGuidanceState {
    WAITING_FOR_ARM,
    ARM_NOT_EXTENDED,
    FIST_TOO_HIGH,
    FIST_TOO_LOW,
    CORRECT_BUT_MOVING,
    CORRECT_AND_HOLDING,
    TRACKING_UNRELIABLE,
    CAPTURE_READY,
}

enum class SetupGuidance {
    NO_BODY,
    MOVE_FARTHER_BACK,
    MOVE_CLOSER,
    KEEP_HEAD_IN_FRAME,
    KEEP_ARM_IN_FRAME,
    TURN_MORE_SIDEWAYS,
    HOLD_STILL,
    CAMERA_READY,
}

data class SetupEvaluation(
    val guidance: SetupGuidance,
    val progress: Float,
    val usable: Boolean,
    val torsoLength: Float? = null,
)

data class BodyInitializationEvaluation(
    val progress: Float,
    val bodyReference: BodyReference?,
)

data class PunchHeightEvaluation(
    val timestampMs: Long,
    val target: PunchHeightTarget?,
    val guidance: PunchHeightGuidanceState,
    val activeArm: ActiveArm,
    val fistCenter: Point3?,
    val elbowPoint: Point3?,
    val shoulderPoint: Point3?,
    val wristPoint: Point3?,
    val elbowAngleDegrees: Float?,
    val signedHeightErrorTorsoRatio: Float?,
    val holdProgress: Float,
    val stableHoldMs: Long,
    val captureReady: Boolean,
    val bodyReference: BodyReference,
)

data class PunchHeightCaptureSnapshot(
    val targetType: PunchHeightTargetType,
    val timestampMs: Long,
    val target: PunchHeightTarget,
    val bodyReference: BodyReference,
    val activeArm: ActiveArm,
    val fistCenter: Point3,
    val shoulderPoint: Point3,
    val elbowPoint: Point3,
    val wristPoint: Point3,
    val elbowAngleDegrees: Float,
    val signedHeightErrorTorsoRatio: Float,
    val stableHoldMs: Long,
    val chinProjectionMultiplier: Float,
)
