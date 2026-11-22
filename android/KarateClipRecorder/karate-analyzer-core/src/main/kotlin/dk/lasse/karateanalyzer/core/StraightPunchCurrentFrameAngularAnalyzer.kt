package dk.lasse.karateanalyzer.core

import dk.lasse.karateanalyzer.capture.LateralSide
import dk.lasse.karateanalyzer.capture.qom.ExtremityPointComposer
import dk.lasse.karateanalyzer.capture.qom.MotionBodyProfile
import dk.lasse.karateanalyzer.geometry.CanonicalGeometryDescriptor
import dk.lasse.karateanalyzer.geometry.SourceNormalizedPoint
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

enum class CurrentFrameAngularState { RESOLVED, AMBIGUOUS, UNAVAILABLE }
enum class CurrentFrameAngularTargetState { VALID, UNREACHABLE, UNAVAILABLE }
enum class CurrentFrameTargetSelectionMode { OPEN, DECLARED }

data class CompositeFistReachEvidence(
    val estimatorVersion: String,
    val state: CurrentFrameAngularTargetState,
    val radiusAspectCorrect: Double? = null,
    val upperArmP80AspectCorrect: Double? = null,
    val distalCompositeFistP80AspectCorrect: Double? = null,
    val supportedTimestampsUs: List<Long> = emptyList(),
    val intervalStartUs: Long,
    val intervalEndUs: Long,
    val minimumConfidence: Double,
    val minimumObservedConfidence: Double? = null,
    val reason: String,
)

data class CurrentFrameAngularTargetEvaluation(
    val target: PunchHeightTargetType,
    val state: CurrentFrameAngularTargetState,
    val targetPoint: SourceNormalizedPoint? = null,
    val idealEndpoint: SourceNormalizedPoint? = null,
    val idealAngleDeg: Double? = null,
    val signedErrorDeg: Double? = null,
    val reason: String,
)

data class StraightPunchCurrentFrameAngularResult(
    val identity: HorizontalTargetIdentity,
    val state: CurrentFrameAngularState,
    val selectionMode: CurrentFrameTargetSelectionMode,
    val declaredTargetRaw: String?,
    val selectedTarget: PunchHeightTargetType?,
    val selectionReason: String,
    val comparisonComplete: Boolean,
    val side: LateralSide,
    val facing: ImageFacingDirection,
    val facingSource: String,
    val shoulder: SourceNormalizedPoint?,
    val fist: SourceNormalizedPoint?,
    val fistEstimatorVersion: String,
    val actualAngleDeg: Double?,
    val signedErrorDeg: Double?,
    val reach: CompositeFistReachEvidence,
    val targets: Map<PunchHeightTargetType, CurrentFrameAngularTargetEvaluation>,
)

data class StraightPunchCurrentFrameAngularInput(
    val movementStartUs: Long,
    val frame: PoseFrame,
    val frameIndex: Long,
    val horizontal: StraightPunchHorizontalTargetResult,
    val frames: List<PoseFrame>,
    val canonicalGeometry: CanonicalGeometryDescriptor,
    val declaredTargetId: String? = null,
    val minimumConfidence: Float = 0.55f,
)

/** Stable anatomical reach in aspect-correct image space using the same composite fist as impact analysis. */
object CompositeFistArmReachEstimator {
    const val VERSION = "composite_fist_segment_p80_v1"
    const val MINIMUM_SAMPLES = 3

    fun estimate(
        frames: List<PoseFrame>,
        startUs: Long,
        impactUs: Long,
        side: LateralSide,
        aspectRatio: Float,
        minimumConfidence: Float,
    ): CompositeFistReachEvidence {
        fun unavailable(reason: String, timestamps: List<Long> = emptyList(), observed: Double? = null) =
            CompositeFistReachEvidence(VERSION, CurrentFrameAngularTargetState.UNAVAILABLE,
                supportedTimestampsUs = timestamps, intervalStartUs = startUs, intervalEndUs = impactUs,
                minimumConfidence = minimumConfidence.toDouble(), minimumObservedConfidence = observed, reason = reason)
        if (startUs < 0L || impactUs < startUs || !aspectRatio.isFinite() || aspectRatio <= 0f) {
            return unavailable("invalid_reach_interval_or_geometry")
        }
        val shoulderId = if (side == LateralSide.LEFT) PoseLandmarkId.LEFT_SHOULDER else PoseLandmarkId.RIGHT_SHOULDER
        val elbowId = if (side == LateralSide.LEFT) PoseLandmarkId.LEFT_ELBOW else PoseLandmarkId.RIGHT_ELBOW
        val fistIds = if (side == LateralSide.LEFT) MotionBodyProfile.PUNCH_HAND_LANDMARKS_LEFT else MotionBodyProfile.PUNCH_HAND_LANDMARKS_RIGHT
        data class Sample(val timestampUs: Long, val upper: Double, val distal: Double, val confidence: Double)
        val samples = frames.mapNotNull { frame ->
            val timestampUs = frame.timestampMs * 1000L
            if (timestampUs !in startUs..impactUs) return@mapNotNull null
            val shoulderSample = frame.landmarks[shoulderId]?.takeIf { it.isObserved(minimumConfidence) } ?: return@mapNotNull null
            val elbowSample = frame.landmarks[elbowId]?.takeIf { it.isObserved(minimumConfidence) } ?: return@mapNotNull null
            if (shoulderSample.source != LandmarkSource.OBSERVED || elbowSample.source != LandmarkSource.OBSERVED) return@mapNotNull null
            val shoulder = shoulderSample.position ?: return@mapNotNull null
            val elbow = elbowSample.position ?: return@mapNotNull null
            val fist = ExtremityPointComposer.compositeSourcePoint(frame, fistIds) ?: return@mapNotNull null
            if (fist.confidence < minimumConfidence || fist.contributingLandmarks.any { frame.landmarks[it]?.source != LandmarkSource.OBSERVED }) return@mapNotNull null
            val upper = sqrt(((elbow.x - shoulder.x) * aspectRatio).toDouble().let { it * it } +
                (elbow.y - shoulder.y).toDouble().let { it * it })
            val distal = sqrt(((fist.point.x - elbow.x) * aspectRatio).toDouble().let { it * it } +
                (fist.point.y - elbow.y).toDouble().let { it * it })
            if (!upper.isFinite() || !distal.isFinite() || upper <= 0.01 || distal <= 0.01) return@mapNotNull null
            val confidence = minOf(
                (shoulderSample.visibility * shoulderSample.presence).toDouble(),
                (elbowSample.visibility * elbowSample.presence).toDouble(),
                fist.confidence,
            )
            Sample(timestampUs, upper, distal, confidence)
        }
        val timestamps = samples.map { it.timestampUs }
        val observed = samples.minOfOrNull { it.confidence }
        if (samples.size < MINIMUM_SAMPLES) return unavailable("insufficient_supported_reach_samples", timestamps, observed)
        fun p80(values: List<Double>): Double = values.sorted()[((values.size - 1) * 0.80).toInt()]
        val upper = p80(samples.map { it.upper })
        val distal = p80(samples.map { it.distal })
        val radius = upper + distal
        if (!radius.isFinite() || radius <= 0.05) return unavailable("degenerate_composite_fist_reach", timestamps, observed)
        return CompositeFistReachEvidence(
            estimatorVersion = VERSION,
            state = CurrentFrameAngularTargetState.VALID,
            radiusAspectCorrect = radius,
            upperArmP80AspectCorrect = upper,
            distalCompositeFistP80AspectCorrect = distal,
            supportedTimestampsUs = timestamps,
            intervalStartUs = startUs,
            intervalEndUs = impactUs,
            minimumConfidence = minimumConfidence.toDouble(),
            minimumObservedConfidence = observed,
            reason = "supported_composite_fist_segment_percentile",
        )
    }
}

/** Exact stable-frame shoulder-origin angular comparison against current horizontal target guides. */
object StraightPunchCurrentFrameAngularAnalyzer {
    const val VERSION = "straight_punch_current_frame_angular_v1"
    const val COORDINATE_SPACE = "normalized_upright_unmirrored_image_y_down_aspect_correct_angles"

    fun analyze(input: StraightPunchCurrentFrameAngularInput): StraightPunchCurrentFrameAngularResult {
        val horizontal = input.horizontal
        val identity = horizontal.identity
        val selectionMode = if (input.declaredTargetId == null) CurrentFrameTargetSelectionMode.OPEN else CurrentFrameTargetSelectionMode.DECLARED
        val geometry = input.canonicalGeometry
        val reach = CompositeFistArmReachEstimator.estimate(input.frames, input.movementStartUs, identity.timestampUs,
            identity.side, geometry.canonicalWidth.toFloat() / geometry.canonicalHeight.toFloat(), input.minimumConfidence)
        fun unavailable(reason: String, shoulder: SourceNormalizedPoint? = null, fist: SourceNormalizedPoint? = null,
                        actualAngle: Double? = null, targets: Map<PunchHeightTargetType, CurrentFrameAngularTargetEvaluation> = emptyMap(),
                        selected: PunchHeightTargetType? = null) = StraightPunchCurrentFrameAngularResult(
            identity, CurrentFrameAngularState.UNAVAILABLE, selectionMode, input.declaredTargetId, selected, reason,
            comparisonComplete = false, identity.side, horizontal.facing, horizontal.facingSource, shoulder, fist,
            horizontal.fistEstimatorVersion, actualAngle, null, reach, targets)

        if (!geometry.isAvailable || identity.recordingId != geometry.recordingId || identity.landmarkTrackId != geometry.landmarkTrackId ||
            identity.frameGeometryId != geometry.geometryId || identity.frameGeometryContractVersion != geometry.contractVersion ||
            identity.canonicalWidth != geometry.canonicalWidth || identity.canonicalHeight != geometry.canonicalHeight ||
            identity.frameIndex != input.frameIndex || input.frame.timestampMs * 1000L != identity.timestampUs
        ) return unavailable("horizontal_exact_frame_identity_mismatch")
        val exactMatches = input.frames.withIndex().filter { it.value.timestampMs * 1000L == identity.timestampUs }
        if (exactMatches.size != 1 || exactMatches.single().index.toLong() != identity.frameIndex ||
            exactMatches.single().value != input.frame
        ) return unavailable("horizontal_exact_frame_identity_mismatch")

        val shoulderId = if (identity.side == LateralSide.LEFT) PoseLandmarkId.LEFT_SHOULDER else PoseLandmarkId.RIGHT_SHOULDER
        val shoulderSample = input.frame.landmarks[shoulderId]?.takeIf { it.isObserved(input.minimumConfidence) }
            ?: return unavailable("striking_shoulder_unavailable")
        if (shoulderSample.source != LandmarkSource.OBSERVED) return unavailable("striking_shoulder_not_observed")
        val shoulderPosition = shoulderSample.position ?: return unavailable("striking_shoulder_unavailable")
        val shoulder = SourceNormalizedPoint(shoulderPosition.x, shoulderPosition.y)
        val fistIds = if (identity.side == LateralSide.LEFT) MotionBodyProfile.PUNCH_HAND_LANDMARKS_LEFT else MotionBodyProfile.PUNCH_HAND_LANDMARKS_RIGHT
        val fistComposite = ExtremityPointComposer.compositeSourcePoint(input.frame, fistIds)
            ?: return unavailable("composite_fist_unavailable", shoulder)
        if (fistComposite.confidence < input.minimumConfidence ||
            fistComposite.contributingLandmarks.any { input.frame.landmarks[it]?.source != LandmarkSource.OBSERVED } ||
            abs(fistComposite.point.x - horizontal.fist.x) > 1e-6f || abs(fistComposite.point.y - horizontal.fist.y) > 1e-6f
        ) return unavailable("composite_fist_identity_or_confidence_mismatch", shoulder)
        val fist = fistComposite.point
        if (horizontal.facing == ImageFacingDirection.UNKNOWN) return unavailable("facing_unavailable", shoulder, fist)
        val radius = reach.radiusAspectCorrect ?: return unavailable(reach.reason, shoulder, fist)
        val aspect = geometry.canonicalWidth.toDouble() / geometry.canonicalHeight.toDouble()
        val forwardSign = if (horizontal.facing == ImageFacingDirection.IMAGE_LEFT) -1.0 else 1.0
        val actualForward = (fist.x - shoulder.x) * aspect * forwardSign
        val actualUp = shoulder.y.toDouble() - fist.y.toDouble()
        val actualLength = sqrt(actualForward * actualForward + actualUp * actualUp)
        if (!actualLength.isFinite() || actualLength <= 1e-6) return unavailable("zero_actual_shoulder_fist_ray", shoulder, fist)
        if (actualForward <= 0.0) return unavailable("actual_fist_behind_resolved_facing", shoulder, fist)
        val actualAngle = Math.toDegrees(atan2(actualUp, actualForward))

        val targets = PunchHeightTargetType.entries.associateWith { target ->
            val guide = horizontal.guides[target]
            val point = guide?.targetPoint
            when {
                guide == null || guide.state != HorizontalGuideState.AVAILABLE || point == null -> CurrentFrameAngularTargetEvaluation(
                    target, CurrentFrameAngularTargetState.UNAVAILABLE, reason = guide?.reason ?: "horizontal_guide_unavailable")
                else -> {
                    val height = shoulder.y.toDouble() - point.y.toDouble()
                    val forwardSquared = radius * radius - height * height
                    if (forwardSquared < 0.0) CurrentFrameAngularTargetEvaluation(
                        target, CurrentFrameAngularTargetState.UNREACHABLE, targetPoint = point, reason = "target_outside_composite_fist_reach_circle")
                    else {
                        val idealForward = sqrt(forwardSquared)
                        val idealAngle = Math.toDegrees(atan2(height, idealForward))
                        val endpoint = SourceNormalizedPoint(
                            (shoulder.x + (forwardSign * idealForward / aspect)).toFloat(),
                            (shoulder.y - height).toFloat(),
                        )
                        CurrentFrameAngularTargetEvaluation(target, CurrentFrameAngularTargetState.VALID, point, endpoint,
                            idealAngle, actualAngle - idealAngle, "current_horizontal_guide_reachable")
                    }
                }
            }
        }
        val declared = input.declaredTargetId?.let { raw -> PunchHeightTargetType.entries.singleOrNull { it.name == raw } }
        if (selectionMode == CurrentFrameTargetSelectionMode.DECLARED && declared == null) {
            return unavailable("invalid_declared_target:${input.declaredTargetId}", shoulder, fist, actualAngle, targets)
        }
        if (declared != null) {
            val selected = targets.getValue(declared)
            if (selected.state != CurrentFrameAngularTargetState.VALID || selected.signedErrorDeg == null) {
                return unavailable("declared_target_${selected.state.name.lowercase()}:${selected.reason}", shoulder, fist, actualAngle, targets, declared)
            }
            return StraightPunchCurrentFrameAngularResult(identity, CurrentFrameAngularState.RESOLVED, selectionMode,
                input.declaredTargetId, declared, "declared_target_only:${declared.name}", comparisonComplete = true,
                identity.side, horizontal.facing, horizontal.facingSource, shoulder, fist, horizontal.fistEstimatorVersion,
                actualAngle, selected.signedErrorDeg, reach, targets)
        }

        val valid = targets.values.filter { it.state == CurrentFrameAngularTargetState.VALID && it.signedErrorDeg != null }
        if (valid.isEmpty()) return unavailable("no_reachable_current_frame_targets", shoulder, fist, actualAngle, targets)
        val minimum = valid.minOf { abs(it.signedErrorDeg!!) }
        val tied = valid.filter { abs(it.signedErrorDeg!!) == minimum }
        val complete = valid.size == PunchHeightTargetType.entries.size
        val compared = valid.joinToString(",") { it.target.name }
        val excluded = targets.values.filter { it.state != CurrentFrameAngularTargetState.VALID }
            .joinToString(",") { "${it.target.name}:${it.state.name}" }
        if (tied.size != 1) return StraightPunchCurrentFrameAngularResult(identity, CurrentFrameAngularState.AMBIGUOUS,
            selectionMode, null, null, "exact_absolute_angle_tie;compared=$compared;excluded=$excluded", complete,
            identity.side, horizontal.facing, horizontal.facingSource, shoulder, fist, horizontal.fistEstimatorVersion,
            actualAngle, null, reach, targets)
        val selected = tied.single()
        return StraightPunchCurrentFrameAngularResult(identity, CurrentFrameAngularState.RESOLVED, selectionMode, null,
            selected.target, "minimum_absolute_angle_error;compared=$compared;excluded=$excluded", complete,
            identity.side, horizontal.facing, horizontal.facingSource, shoulder, fist, horizontal.fistEstimatorVersion,
            actualAngle, selected.signedErrorDeg, reach, targets)
    }
}
