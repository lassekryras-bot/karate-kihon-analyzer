package dk.lasse.karateanalyzer.motion

import dk.lasse.karateanalyzer.core.PoseFrame
import dk.lasse.karateanalyzer.core.PoseLandmarkId
import dk.lasse.karateanalyzer.geometry.CanonicalGeometryDescriptor

enum class LimbId(val proximal: PoseLandmarkId, val joint: PoseLandmarkId, val distal: PoseLandmarkId) {
    LEFT_ARM(PoseLandmarkId.LEFT_SHOULDER, PoseLandmarkId.LEFT_ELBOW, PoseLandmarkId.LEFT_WRIST),
    RIGHT_ARM(PoseLandmarkId.RIGHT_SHOULDER, PoseLandmarkId.RIGHT_ELBOW, PoseLandmarkId.RIGHT_WRIST),
    LEFT_LEG(PoseLandmarkId.LEFT_HIP, PoseLandmarkId.LEFT_KNEE, PoseLandmarkId.LEFT_ANKLE),
    RIGHT_LEG(PoseLandmarkId.RIGHT_HIP, PoseLandmarkId.RIGHT_KNEE, PoseLandmarkId.RIGHT_ANKLE);
    val sourceLandmarks: List<PoseLandmarkId> get() = listOf(proximal, joint, distal)
}

enum class LimbMotionPattern { STABLE, MOSTLY_OPENING, MOSTLY_CLOSING, OPEN_CLOSE_REVERSAL, CLOSE_OPEN_REVERSAL, MULTI_DIRECTIONAL, INSUFFICIENT_EVIDENCE }
enum class LimbAbstentionReason { FRAME_GEOMETRY_UNAVAILABLE, TRACK_IDENTITY_MISMATCH, INVALID_TIMESTAMPS, INVALID_BOUNDS, JOINT_GEOMETRY_UNAVAILABLE, INSUFFICIENT_VALID_SAMPLES, TRACKING_QUALITY_INSUFFICIENT, INVALID_STABLE_REFERENCE }
enum class LimbQualityFlag { GAPS, NON_OBSERVED_SAMPLES, BOUNDARY_REFERENCE, UNCONFIRMED_ONSET, UNCONFIRMED_SETTLING }

data class LimbEvidenceWindow(val startTimestampUs: Long, val endTimestampUs: Long)

/** All parameters are retained in the result. Defaults are experimental, not calibrated thresholds.
 * The angular band is excursion amplitude, deliberately NOT degrees per frame.
 * Identity filtering avoids frame-count-dependent delay by default; optional smoothing reuses QoM's filter.
 */
data class FourLimbMotionConfig(
    val version: String = "four-limb-config-v1-provisional",
    val angularDeadbandDeg: Double = 3.6,
    val minimumConfidence: Float = 0.55f,
    val minimumCoverage: Double = 0.60,
    val maximumGapUs: Long = 100_000L,
    val onsetConfirmationUs: Long = 60_000L,
    val quietConfirmationUs: Long = 100_000L,
    val medianSamples: Int = 1,
    val meanSamples: Int = 1,
    val dominantTravelRatio: Double = 1.25,
) {
    init {
        require(version.isNotBlank())
        require(angularDeadbandDeg.isFinite() && angularDeadbandDeg > 0.0)
        require(minimumConfidence.isFinite() && minimumConfidence in 0f..1f)
        require(minimumCoverage.isFinite() && minimumCoverage in 0.0..1.0)
        require(maximumGapUs > 0 && onsetConfirmationUs > 0 && quietConfirmationUs > 0)
        require(medianSamples > 0 && meanSamples > 0)
        require(dominantTravelRatio.isFinite() && dominantTravelRatio > 1.0)
    }
}

/** Frames must already be upright/unmirrored source-normalized samples from the declared track.
 * Logical bounds belong to QoM. Optional reference windows are independent evidence bounds.
 */
data class FourLimbMotionInput(
    val movementId: String,
    val landmarkTrackId: String,
    val logicalStartTimestampUs: Long,
    val logicalEndTimestampUs: Long,
    val frames: List<PoseFrame>,
    val canonicalGeometry: CanonicalGeometryDescriptor,
    val stablePreWindow: LimbEvidenceWindow? = null,
    val stablePostWindow: LimbEvidenceWindow? = null,
    val segmenterVersion: String? = null,
)

/** Null angle/edge is unavailable evidence, never zero motion. No interpolation is performed. */
data class LimbMotionSample(
    val timestampUs: Long,
    val rawAngleDeg: Double?,
    val angleDeg: Double?,
    val confidence: Double?,
    val blockId: Int?,
    val meaningfulDeltaDeg: Double? = null,
    val angularSpeedDegPerSec: Double? = null,
)

data class LimbReversal(
    val timestampUs: Long,
    val angleDeg: Double,
    val beforeDirection: Int,
    val afterDirection: Int,
    /** Smaller of the two supported excursions. */
    val magnitudeDeg: Double,
)

data class LimbMotionMetrics(
    val referenceStartAngleDeg: Double,
    val referenceEndAngleDeg: Double,
    val minimumAngleDeg: Double,
    val maximumAngleDeg: Double,
    val netAngleChangeDeg: Double,
    val meaningfulAngularTravelDeg: Double,
    val positiveAngularTravelDeg: Double,
    val negativeAngularTravelDeg: Double,
    val maxExcursionDeg: Double,
    val excursionDirection: Int,
    val maxExcursionTimestampUs: Long,
    val movingDurationUs: Long,
    val stableDurationUs: Long,
    val unknownDurationUs: Long,
    val firstMeaningfulMotionTimestampUs: Long?,
    val lastMeaningfulMotionTimestampUs: Long?,
    val motionStartTimestampUs: Long?,
    val motionEndTimestampUs: Long?,
    val settlingConfirmedAtTimestampUs: Long?,
    val peakAngularSpeedDegPerSec: Double?,
    val peakAngularSpeedTimestampUs: Long?,
    val reversals: List<LimbReversal>,
) {
    val largestReversalDeg: Double? get() = reversals.maxOfOrNull { it.magnitudeDeg }
}

data class LimbMotionProfile(
    val limbId: LimbId,
    val landmarkTrackId: String,
    val metrics: LimbMotionMetrics?,
    val motionPattern: LimbMotionPattern,
    val validSampleCoverage: Double,
    val minimumObservedConfidence: Double?,
    val qualityFlags: Set<LimbQualityFlag>,
    val abstentionReason: LimbAbstentionReason?,
    /** Authoritative data for plots and downstream consumers, including gaps. */
    val samples: List<LimbMotionSample>,
)

data class LimbActivityRanking(
    /** Shares only among measurable limbs; null when no meaningful travel exists. */
    val activityShares: Map<LimbId, Double>?,
    val activeCandidates: List<LimbId>,
    val stableLimbs: Set<LimbId>,
    /** Only provided with four measurable profiles and a separated largest travel. */
    val primaryActiveLimb: LimbId?,
    val completeCoverage: Boolean,
)

data class FourLimbMotionResult(
    val movementId: String,
    val landmarkTrackId: String,
    val logicalStartTimestampUs: Long,
    val logicalEndTimestampUs: Long,
    val canonicalGeometry: CanonicalGeometryDescriptor,
    val segmenterVersion: String?,
    val config: FourLimbMotionConfig,
    val analyzerVersion: String,
    val angularPolicyVersion: String,
    val stablePreWindow: LimbEvidenceWindow?,
    val stablePostWindow: LimbEvidenceWindow?,
    val profiles: Map<LimbId, LimbMotionProfile>,
    val activityRanking: LimbActivityRanking,
) {
    val leftArm get() = profiles.getValue(LimbId.LEFT_ARM)
    val rightArm get() = profiles.getValue(LimbId.RIGHT_ARM)
    val leftLeg get() = profiles.getValue(LimbId.LEFT_LEG)
    val rightLeg get() = profiles.getValue(LimbId.RIGHT_LEG)
}
