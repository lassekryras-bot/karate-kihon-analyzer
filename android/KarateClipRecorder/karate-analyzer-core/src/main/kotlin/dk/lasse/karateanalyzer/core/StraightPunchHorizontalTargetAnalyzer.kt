package dk.lasse.karateanalyzer.core

import dk.lasse.karateanalyzer.capture.LateralSide
import dk.lasse.karateanalyzer.capture.qom.ExtremityPointComposer
import dk.lasse.karateanalyzer.capture.qom.MotionBodyProfile
import dk.lasse.karateanalyzer.geometry.CanonicalGeometryDescriptor
import dk.lasse.karateanalyzer.geometry.SourceNormalizedPoint
import kotlin.math.abs

enum class HorizontalGuideState { AVAILABLE, UNAVAILABLE }
enum class HorizontalClosestState { RESOLVED, AMBIGUOUS, UNAVAILABLE }
enum class ImageFacingDirection { IMAGE_LEFT, IMAGE_RIGHT, UNKNOWN }

data class HorizontalTargetGuide(
    val target: PunchHeightTargetType,
    val state: HorizontalGuideState,
    val targetPoint: SourceNormalizedPoint? = null,
    val signedErrorNormalized: Double? = null,
    val signedErrorCanonicalPx: Double? = null,
    val estimatorVersion: String,
    val reason: String,
)

data class HorizontalTargetIdentity(
    val recordingId: String,
    val landmarkTrackId: String,
    val frameGeometryId: String,
    val frameGeometryContractVersion: String,
    val canonicalWidth: Int,
    val canonicalHeight: Int,
    val timestampUs: Long,
    val frameIndex: Long,
    val side: LateralSide,
)

data class StraightPunchHorizontalTargetResult(
    val identity: HorizontalTargetIdentity,
    val fist: SourceNormalizedPoint,
    val fistEstimatorVersion: String,
    val shoulderCenter: SourceNormalizedPoint?,
    val hipCenter: SourceNormalizedPoint?,
    val guides: Map<PunchHeightTargetType, HorizontalTargetGuide>,
    val closestState: HorizontalClosestState,
    val closestTarget: PunchHeightTargetType?,
    val closestReason: String,
    val facing: ImageFacingDirection,
    val facingSource: String,
)

data class StraightPunchHorizontalTargetInput(
    val frame: PoseFrame,
    val frameIndex: Long,
    val timestampUs: Long,
    val recordingId: String,
    val landmarkTrackId: String,
    val canonicalGeometry: CanonicalGeometryDescriptor,
    val side: LateralSide,
    val expectedFist: SourceNormalizedPoint,
    val minimumConfidence: Float = 0.55f,
    val facing: ImageFacingDirection = ImageFacingDirection.UNKNOWN,
    val facingSource: String = "unresolved",
)

/** Exact-frame horizontal target-height math for completed straight-punch impact evidence. */
object StraightPunchHorizontalTargetAnalyzer {
    const val VERSION = "straight_punch_horizontal_target_v1"
    const val COORDINATE_SPACE = "normalized_upright_unmirrored_image_y_down"
    const val FIST_ESTIMATOR = "impact_composite_hand_source_v1"
    const val JODAN_ESTIMATOR = "side_view_chin_fresh_face_v1"
    const val CHUDAN_ESTIMATOR = "bilateral_shoulder_hip_interpolation_0_45_v1"
    const val GEDAN_ESTIMATOR = "bilateral_hip_midpoint_groin_v1"

    fun analyze(input: StraightPunchHorizontalTargetInput): StraightPunchHorizontalTargetResult? {
        val geometry = input.canonicalGeometry
        if (!geometry.isAvailable || geometry.recordingId != input.recordingId ||
            geometry.landmarkTrackId != input.landmarkTrackId || geometry.canonicalHeight <= 0 ||
            input.frame.timestampMs * 1000L != input.timestampUs
        ) return null

        val fistIds = when (input.side) {
            LateralSide.LEFT -> MotionBodyProfile.PUNCH_HAND_LANDMARKS_LEFT
            LateralSide.RIGHT -> MotionBodyProfile.PUNCH_HAND_LANDMARKS_RIGHT
        }
        val fistComposite = ExtremityPointComposer.compositeSourcePoint(input.frame, fistIds) ?: return null
        if (fistComposite.confidence < input.minimumConfidence) return null
        if (fistComposite.contributingLandmarks.any { input.frame.landmarks[it]?.source != LandmarkSource.OBSERVED }) return null
        if (!samePoint(fistComposite.point, input.expectedFist)) return null

        val shoulder = midpoint(input.frame, PoseLandmarkId.LEFT_SHOULDER, PoseLandmarkId.RIGHT_SHOULDER, input.minimumConfidence)
        val hip = midpoint(input.frame, PoseLandmarkId.LEFT_HIP, PoseLandmarkId.RIGHT_HIP, input.minimumConfidence)
        val visibleSide = visibleSide(input.frame, input.minimumConfidence)
        val chin = SideViewChinEstimator.freshFaceObservation(
            input.frame,
            visibleSide,
            PunchHeightAnalyzer.DEFAULT_CHIN_PROJECTION_MULTIPLIER,
            minimumConfidence = 0.70f,
        )?.point?.let { SourceNormalizedPoint(it.x, it.y) }

        val targets = linkedMapOf<PunchHeightTargetType, Pair<SourceNormalizedPoint?, Pair<String, String>>>()
        targets[PunchHeightTargetType.JODAN] = chin to (JODAN_ESTIMATOR to if (chin == null) "fresh_face_chin_unavailable" else "exact_frame_fresh_face_chin")
        val chudan = if (shoulder != null && hip != null) SourceNormalizedPoint(
            shoulder.x + 0.45f * (hip.x - shoulder.x),
            shoulder.y + 0.45f * (hip.y - shoulder.y),
        ) else null
        targets[PunchHeightTargetType.CHUDAN] = chudan to (CHUDAN_ESTIMATOR to if (chudan == null) "bilateral_shoulder_or_hip_unavailable" else "shoulder_plus_0_45_hip_minus_shoulder")
        targets[PunchHeightTargetType.GEDAN] = hip to (GEDAN_ESTIMATOR to if (hip == null) "bilateral_hip_unavailable" else "bilateral_hip_midpoint")

        val guides = targets.mapValues { (target, definition) ->
            val point = definition.first
            val estimator = definition.second.first
            val reason = definition.second.second
            if (point == null) HorizontalTargetGuide(target, HorizontalGuideState.UNAVAILABLE, estimatorVersion = estimator, reason = reason)
            else {
                val error = (point.y - fistComposite.point.y).toDouble()
                HorizontalTargetGuide(
                    target = target,
                    state = HorizontalGuideState.AVAILABLE,
                    targetPoint = point,
                    signedErrorNormalized = error,
                    signedErrorCanonicalPx = error * geometry.canonicalHeight.toDouble(),
                    estimatorVersion = estimator,
                    reason = reason,
                )
            }
        }
        val available = guides.values.filter { it.state == HorizontalGuideState.AVAILABLE }
        val min = available.minOfOrNull { abs(it.signedErrorNormalized!!) }
        val tied = min?.let { best -> available.filter { abs(abs(it.signedErrorNormalized!!) - best) <= 1e-12 } }.orEmpty()
        val closestState = when {
            available.isEmpty() -> HorizontalClosestState.UNAVAILABLE
            tied.size != 1 -> HorizontalClosestState.AMBIGUOUS
            else -> HorizontalClosestState.RESOLVED
        }
        val compared = available.joinToString(",") { it.target.name }
        val closestReason = when (closestState) {
            HorizontalClosestState.UNAVAILABLE -> "no_horizontal_guides_available"
            HorizontalClosestState.AMBIGUOUS -> "exact_absolute_y_tie;compared=$compared"
            HorizontalClosestState.RESOLVED -> "minimum_absolute_y_error;compared=$compared"
        }
        return StraightPunchHorizontalTargetResult(
            identity = HorizontalTargetIdentity(input.recordingId, input.landmarkTrackId, geometry.geometryId,
                geometry.contractVersion, geometry.canonicalWidth, geometry.canonicalHeight,
                input.timestampUs, input.frameIndex, input.side),
            fist = fistComposite.point,
            fistEstimatorVersion = FIST_ESTIMATOR,
            shoulderCenter = shoulder,
            hipCenter = hip,
            guides = guides,
            closestState = closestState,
            closestTarget = tied.singleOrNull()?.target,
            closestReason = closestReason,
            facing = input.facing,
            facingSource = input.facingSource,
        )
    }

    private fun midpoint(frame: PoseFrame, a: PoseLandmarkId, b: PoseLandmarkId, threshold: Float): SourceNormalizedPoint? {
        val pa = frame.landmarks[a]?.takeIf { it.isObserved(threshold) }?.position ?: return null
        val pb = frame.landmarks[b]?.takeIf { it.isObserved(threshold) }?.position ?: return null
        return SourceNormalizedPoint((pa.x + pb.x) * 0.5f, (pa.y + pb.y) * 0.5f)
    }

    private fun visibleSide(frame: PoseFrame, threshold: Float): VisibleSide {
        val left = frame.landmarks[PoseLandmarkId.LEFT_SHOULDER]?.takeIf { it.isObserved(threshold) }?.position
        val right = frame.landmarks[PoseLandmarkId.RIGHT_SHOULDER]?.takeIf { it.isObserved(threshold) }?.position
        if (left != null && right != null && left.z != right.z) return if (left.z < right.z) VisibleSide.LEFT else VisibleSide.RIGHT
        val leftFace = SideViewChinEstimator.freshFaceObservation(frame, VisibleSide.LEFT,
            PunchHeightAnalyzer.DEFAULT_CHIN_PROJECTION_MULTIPLIER, minimumConfidence = 0.70f)
        val rightFace = SideViewChinEstimator.freshFaceObservation(frame, VisibleSide.RIGHT,
            PunchHeightAnalyzer.DEFAULT_CHIN_PROJECTION_MULTIPLIER, minimumConfidence = 0.70f)
        return when {
            leftFace != null && rightFace == null -> VisibleSide.LEFT
            rightFace != null && leftFace == null -> VisibleSide.RIGHT
            else -> VisibleSide.UNKNOWN
        }
    }

    private fun samePoint(a: SourceNormalizedPoint, b: SourceNormalizedPoint): Boolean =
        abs(a.x - b.x) <= 1e-6f && abs(a.y - b.y) <= 1e-6f
}
