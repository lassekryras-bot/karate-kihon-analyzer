package dk.lasse.karateanalyzer.capture.qom

import dk.lasse.karateanalyzer.core.Point3
import dk.lasse.karateanalyzer.core.PoseFrame
import dk.lasse.karateanalyzer.core.PoseLandmarkId
import dk.lasse.karateanalyzer.core.PoseLandmarkSample
import dk.lasse.karateanalyzer.geometry.SourceNormalizedPoint

/**
 * Identifiers for physical body blocks participating in Quantity of Motion aggregation.
 */
enum class MotionBlockId {
    LEFT_ARM,
    RIGHT_ARM,
    TORSO,
    LEFT_LEG,
    RIGHT_LEG,
}

/**
 * Activity-selected body profile defining active blocks and constituent effective points.
 */
enum class MotionBodyProfile(
    val activeBlocks: Set<MotionBlockId>,
) {
    /**
     * Punches and upper-body strikes:
     * - Left arm = left elbow + composite left hand
     * - Right arm = right elbow + composite right hand
     * - Torso = both shoulders + both hips
     * Legs and head/face are strictly excluded.
     */
    PUNCH(
        activeBlocks = setOf(MotionBlockId.LEFT_ARM, MotionBlockId.RIGHT_ARM, MotionBlockId.TORSO),
    ),

    /**
     * Kicks and lower-body strikes:
     * - Left leg = left knee + composite left foot
     * - Right leg = right knee + composite right foot
     * - Torso = both shoulders + both hips
     * Arms and head/face are strictly excluded.
     */
    KICK(
        activeBlocks = setOf(MotionBlockId.LEFT_LEG, MotionBlockId.RIGHT_LEG, MotionBlockId.TORSO),
    );

    companion object {
        val PUNCH_HAND_LANDMARKS_LEFT = listOf(
            PoseLandmarkId.LEFT_WRIST,
            PoseLandmarkId.LEFT_THUMB,
            PoseLandmarkId.LEFT_INDEX,
            PoseLandmarkId.LEFT_PINKY,
        )

        val PUNCH_HAND_LANDMARKS_RIGHT = listOf(
            PoseLandmarkId.RIGHT_WRIST,
            PoseLandmarkId.RIGHT_THUMB,
            PoseLandmarkId.RIGHT_INDEX,
            PoseLandmarkId.RIGHT_PINKY,
        )

        val KICK_FOOT_LANDMARKS_LEFT = listOf(
            PoseLandmarkId.LEFT_ANKLE,
            PoseLandmarkId.LEFT_HEEL,
            PoseLandmarkId.LEFT_FOOT_INDEX,
        )

        val KICK_FOOT_LANDMARKS_RIGHT = listOf(
            PoseLandmarkId.RIGHT_ANKLE,
            PoseLandmarkId.RIGHT_HEEL,
            PoseLandmarkId.RIGHT_FOOT_INDEX,
        )
    }
}

/**
 * Represents a single effective point extracted and hip-centered for motion detection.
 */
data class EffectivePointSample(
    val pointId: String,
    val position: Point3,
    val confidence: Double,
)

/**
 * Computes composite extremity points (hands and feet) and hip-relative positions for a frame.
 */
object ExtremityPointComposer {

    data class SourceCompositePoint(
        val point: SourceNormalizedPoint,
        val confidence: Double,
        val contributingLandmarks: Set<PoseLandmarkId>,
    )

    fun landmarkConfidence(sample: PoseLandmarkSample?): Double {
        if (sample == null) return 0.0
        val p = sample.worldPosition ?: sample.position ?: return 0.0
        if (!p.x.isFinite() || !p.y.isFinite() || !p.z.isFinite()) return 0.0
        val vis = (sample.visibility ?: 0f).coerceIn(0f, 1f)
        val pres = (sample.presence ?: 0f).coerceIn(0f, 1f)
        return (vis * pres).toDouble()
    }

    /**
     * Calculates the confidence-weighted mean center of constituent landmarks.
     * If all confidences are zero but finite points exist, falls back to arithmetic mean with 0.0 confidence.
     * Returns null if no finite positions are available.
     */
    fun compositePoint(
        frame: PoseFrame,
        constituentIds: List<PoseLandmarkId>,
    ): Pair<Point3, Double>? {
        val validSamples = constituentIds.mapNotNull { id ->
            val sample = frame.landmarks[id]
            val wp = sample?.worldPosition ?: sample?.position
            if (wp != null && wp.x.isFinite() && wp.y.isFinite() && wp.z.isFinite()) {
                Pair(wp, landmarkConfidence(sample))
            } else {
                null
            }
        }
        if (validSamples.isEmpty()) return null

        val sumConf = validSamples.sumOf { it.second }
        val center = if (sumConf > 1e-9) {
            var sx = 0.0
            var sy = 0.0
            var sz = 0.0
            for ((pt, conf) in validSamples) {
                sx += pt.x * conf
                sy += pt.y * conf
                sz += pt.z * conf
            }
            Point3((sx / sumConf).toFloat(), (sy / sumConf).toFloat(), (sz / sumConf).toFloat())
        } else {
            var sx = 0.0
            var sy = 0.0
            var sz = 0.0
            for ((pt, _) in validSamples) {
                sx += pt.x
                sy += pt.y
                sz += pt.z
            }
            val n = validSamples.size.toDouble()
            Point3((sx / n).toFloat(), (sy / n).toFloat(), (sz / n).toFloat())
        }

        // Composite confidence is the arithmetic mean across all constituent landmarks (missing constituents count as 0.0)
        val totalConstituents = constituentIds.size.toDouble()
        val avgConfidence = constituentIds.sumOf { id -> landmarkConfidence(frame.landmarks[id]) } / totalConstituents

        return Pair(center, avgConfidence)
    }

    /**
     * Confidence-weighted composite in canonical source-image coordinates.
     * Unlike [compositePoint], this intentionally never substitutes world coordinates.
     */
    fun compositeSourcePoint(
        frame: PoseFrame,
        constituentIds: List<PoseLandmarkId>,
    ): SourceCompositePoint? {
        val validSamples = constituentIds.mapNotNull { id ->
            val sample = frame.landmarks[id]
            val point = sample?.position
            if (point != null && point.x.isFinite() && point.y.isFinite()) {
                Triple(id, point, landmarkConfidence(sample))
            } else {
                null
            }
        }
        if (validSamples.isEmpty()) return null

        val sumConfidence = validSamples.sumOf { it.third }
        val x: Double
        val y: Double
        if (sumConfidence > 1e-9) {
            x = validSamples.sumOf { it.second.x * it.third } / sumConfidence
            y = validSamples.sumOf { it.second.y * it.third } / sumConfidence
        } else {
            x = validSamples.map { it.second.x.toDouble() }.average()
            y = validSamples.map { it.second.y.toDouble() }.average()
        }
        val averageConfidence = constituentIds.sumOf { id -> landmarkConfidence(frame.landmarks[id]) } /
            constituentIds.size.toDouble()
        return SourceCompositePoint(
            point = SourceNormalizedPoint(x.toFloat(), y.toFloat()),
            confidence = averageConfidence,
            contributingLandmarks = validSamples.map { it.first }.toSet(),
        )
    }
}

