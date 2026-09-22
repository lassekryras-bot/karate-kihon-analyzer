package dk.lasse.karateanalyzer.geometry

import dk.lasse.karateanalyzer.core.PoseFrame
import dk.lasse.karateanalyzer.core.PoseLandmarkId
import dk.lasse.karateanalyzer.core.PoseLandmarkSample

/**
 * Versioned recipe names for built-in landmark anchors.
 */
object LandmarkAnchorDefinitions {
    const val SHOULDER_CENTER = "SHOULDER_CENTER_V1"
    const val HIP_CENTER = "HIP_CENTER_V1"
    const val TORSO_CENTER = "TORSO_CENTER_V1"
    const val HEAD_EAR_MIDPOINT = "HEAD_EAR_MIDPOINT_V1"
    const val HEAD_EYES_EARS_COMPOSITE = "HEAD_EYES_EARS_COMPOSITE_V1"
}

/**
 * Observed spatial anchor from one source sample.
 */
data class AnchorObservation(
    val anchorName: String,
    val position: SourceNormalizedPoint,
    val timestampUs: Long,
    val contributingLandmarkIds: Set<PoseLandmarkId>,
    val confidence: Float,
)

/**
 * Spatial anchors extracted within a single frame.
 * Strict spatial-first rule: Torso observations require bilateral shoulders
 * and bilateral hips within the exact same sample.
 */
data class TorsoAnchorsSample(
    val timestampUs: Long,
    val shoulderCenter: SourceNormalizedPoint?,
    val hipCenter: SourceNormalizedPoint?,
    val torsoCenter: SourceNormalizedPoint?,
    val isTorsoValid: Boolean,
    val failureReason: String? = null,
)

/**
 * Pure utility to construct versioned spatial anchors and explicit temporal aggregates.
 */
object LandmarkAnchors {

    fun isLandmarkValid(sample: PoseLandmarkSample?, confidenceThreshold: Float = 0.55f): Boolean {
        if (sample == null || sample.position == null) return false
        val p = sample.position
        if (!p.x.isFinite() || !p.y.isFinite()) return false
        return sample.confidence >= confidenceThreshold
    }

    /**
     * Extracts torso anchors from a single frame enforcing the strict spatial-first rule.
     */
    fun extractTorsoAnchors(
        frame: PoseFrame,
        confidenceThreshold: Float = 0.55f,
    ): TorsoAnchorsSample {
        val ts = frame.timestampMs * 1000L
        val ls = frame.landmarks[PoseLandmarkId.LEFT_SHOULDER]
        val rs = frame.landmarks[PoseLandmarkId.RIGHT_SHOULDER]
        val lh = frame.landmarks[PoseLandmarkId.LEFT_HIP]
        val rh = frame.landmarks[PoseLandmarkId.RIGHT_HIP]

        val hasLs = isLandmarkValid(ls, confidenceThreshold)
        val hasRs = isLandmarkValid(rs, confidenceThreshold)
        val hasLh = isLandmarkValid(lh, confidenceThreshold)
        val hasRh = isLandmarkValid(rh, confidenceThreshold)

        val shoulderCenter = if (hasLs && hasRs) {
            SourceNormalizedPoint(
                (ls!!.position!!.x + rs!!.position!!.x) * 0.5f,
                (ls.position!!.y + rs.position!!.y) * 0.5f,
            )
        } else null

        val hipCenter = if (hasLh && hasRh) {
            SourceNormalizedPoint(
                (lh!!.position!!.x + rh!!.position!!.x) * 0.5f,
                (lh.position!!.y + rh.position!!.y) * 0.5f,
            )
        } else null

        val torsoCenter = if (shoulderCenter != null && hipCenter != null) {
            SourceNormalizedPoint(
                (shoulderCenter.x + hipCenter.x) * 0.5f,
                (shoulderCenter.y + hipCenter.y) * 0.5f,
            )
        } else null

        val isValid = shoulderCenter != null && hipCenter != null
        val failureReason = when {
            !hasLs || !hasRs -> "missing_shoulder_landmarks"
            !hasLh || !hasRh -> "missing_hip_landmarks"
            else -> null
        }

        return TorsoAnchorsSample(
            timestampUs = ts,
            shoulderCenter = shoulderCenter,
            hipCenter = hipCenter,
            torsoCenter = torsoCenter,
            isTorsoValid = isValid,
            failureReason = failureReason,
        )
    }

    /**
     * Extracts head anchor using the EAR_MIDPOINT_V1 definition.
     */
    fun extractHeadEarMidpointV1(
        frame: PoseFrame,
        confidenceThreshold: Float = 0.55f,
    ): SourceNormalizedPoint? {
        val le = frame.landmarks[PoseLandmarkId.LEFT_EAR]
        val re = frame.landmarks[PoseLandmarkId.RIGHT_EAR]
        return if (isLandmarkValid(le, confidenceThreshold) && isLandmarkValid(re, confidenceThreshold)) {
            SourceNormalizedPoint(
                (le!!.position!!.x + re!!.position!!.x) * 0.5f,
                (le.position!!.y + re.position!!.y) * 0.5f,
            )
        } else null
    }

    /**
     * Extracts head anchor using the EYES_EARS_COMPOSITE_V1 definition.
     */
    fun extractHeadEyesEarsCompositeV1(
        frame: PoseFrame,
        confidenceThreshold: Float = 0.55f,
    ): SourceNormalizedPoint? {
        val le = frame.landmarks[PoseLandmarkId.LEFT_EAR]
        val re = frame.landmarks[PoseLandmarkId.RIGHT_EAR]
        val ley = frame.landmarks[PoseLandmarkId.LEFT_EYE]
        val rey = frame.landmarks[PoseLandmarkId.RIGHT_EYE]
        return if (isLandmarkValid(le, confidenceThreshold) && isLandmarkValid(re, confidenceThreshold) &&
            isLandmarkValid(ley, confidenceThreshold) && isLandmarkValid(rey, confidenceThreshold)
        ) {
            SourceNormalizedPoint(
                (le!!.position!!.x + re!!.position!!.x + ley!!.position!!.x + rey!!.position!!.x) * 0.25f,
                (le.position!!.y + re.position!!.y + ley.position!!.y + rey.position!!.y) * 0.25f,
            )
        } else null
    }

    /**
     * Component-wise median aggregation over multiple spatial points.
     */
    fun aggregateTemporalMedian(points: List<SourceNormalizedPoint>): SourceNormalizedPoint? {
        if (points.isEmpty()) return null
        val medX = median(points.map { it.x })
        val medY = median(points.map { it.y })
        return SourceNormalizedPoint(medX, medY)
    }

    private fun median(values: List<Float>): Float {
        require(values.isNotEmpty()) { "Cannot compute median of empty list" }
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) {
            sorted[n / 2]
        } else {
            (sorted[n / 2 - 1] + sorted[n / 2]) * 0.5f
        }
    }
}

