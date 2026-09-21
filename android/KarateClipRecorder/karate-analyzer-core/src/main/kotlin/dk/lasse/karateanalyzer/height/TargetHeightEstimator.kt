package dk.lasse.karateanalyzer.height

import dk.lasse.karateanalyzer.core.PoseFrame
import dk.lasse.karateanalyzer.core.PoseLandmarkId
import dk.lasse.karateanalyzer.core.PoseLandmarkSample
import dk.lasse.karateanalyzer.core.PunchHeightTargetType
import dk.lasse.karateanalyzer.geometry.SourceNormalizedPoint
import kotlin.math.abs

enum class TargetEstimateStatus {
    VALID,
    UNAVAILABLE,
    DEGRADED,
}

data class TargetHeightEstimate(
    val targetId: String,
    val targetType: PunchHeightTargetType,
    val point: SourceNormalizedPoint?,
    val status: TargetEstimateStatus,
    val contributingTimestampsUs: List<Long>,
    val usableCount: Int,
    val configId: String,
    val estimatorVersion: String = "1",
    val reason: String? = null,
    val disagreementMetric: Float? = null,
)

/**
 * Evaluates versioned compatibility target height formulas.
 * Target hypotheses are kept separate from observed body geometry.
 */
object TargetHeightEstimator {

    const val CHUDAN_RATIO_045_ID = "CHUDAN_TORSO_RATIO_045_V1"
    const val GEDAN_RATIO_080_ID = "GEDAN_TORSO_RATIO_080_V1"
    const val GEDAN_HIP_LEVEL_ID = "GEDAN_HIP_LEVEL_V1"
    const val JODAN_MOUTH_NOSE_110_ID = "JODAN_MOUTH_NOSE_110_V1"

    /**
     * Chūdan: C = S + 0.45 * (H - S)
     */
    fun estimateChudanTorsoRatio045(
        observed: ObservedBodyGeometry,
        config: BodyHeightModelConfig? = null,
    ): TargetHeightEstimate {
        val effectiveConfigId = config?.effectiveConfigId ?: observed.configId
        val s = observed.shoulderCenter
        val h = observed.hipCenter
        if (s == null || h == null || observed.torsoEvidence.usableCount == 0 ||
            observed.currentBodyUp == null || observed.currentTorsoLength == null ||
            observed.torsoEvidence.windowState == WindowState.UNAVAILABLE
        ) {
            return TargetHeightEstimate(
                targetId = CHUDAN_RATIO_045_ID,
                targetType = PunchHeightTargetType.CHUDAN,
                point = null,
                status = TargetEstimateStatus.UNAVAILABLE,
                contributingTimestampsUs = emptyList(),
                usableCount = 0,
                configId = effectiveConfigId,
                reason = if (observed.currentTorsoLength == null || observed.currentBodyUp == null || observed.torsoEvidence.windowState == WindowState.UNAVAILABLE) {
                    "torso_geometry_degenerate_or_unavailable"
                } else {
                    "torso_geometry_unavailable"
                },
            )
        }

        val pt = SourceNormalizedPoint(
            x = s.x + 0.45f * (h.x - s.x),
            y = s.y + 0.45f * (h.y - s.y),
        )

        return TargetHeightEstimate(
            targetId = CHUDAN_RATIO_045_ID,
            targetType = PunchHeightTargetType.CHUDAN,
            point = pt,
            status = TargetEstimateStatus.VALID,
            contributingTimestampsUs = observed.torsoEvidence.contributingTimestampsUs,
            usableCount = observed.torsoEvidence.usableCount,
            configId = effectiveConfigId,
        )
    }

    /**
     * Gedan lower abdomen: G = S + 0.80 * (H - S)
     */
    fun estimateGedanTorsoRatio080(
        observed: ObservedBodyGeometry,
        config: BodyHeightModelConfig? = null,
    ): TargetHeightEstimate {
        val effectiveConfigId = config?.effectiveConfigId ?: observed.configId
        val s = observed.shoulderCenter
        val h = observed.hipCenter
        if (s == null || h == null || observed.torsoEvidence.usableCount == 0 ||
            observed.currentBodyUp == null || observed.currentTorsoLength == null ||
            observed.torsoEvidence.windowState == WindowState.UNAVAILABLE
        ) {
            return TargetHeightEstimate(
                targetId = GEDAN_RATIO_080_ID,
                targetType = PunchHeightTargetType.GEDAN,
                point = null,
                status = TargetEstimateStatus.UNAVAILABLE,
                contributingTimestampsUs = emptyList(),
                usableCount = 0,
                configId = effectiveConfigId,
                reason = if (observed.currentTorsoLength == null || observed.currentBodyUp == null || observed.torsoEvidence.windowState == WindowState.UNAVAILABLE) {
                    "torso_geometry_degenerate_or_unavailable"
                } else {
                    "torso_geometry_unavailable"
                },
            )
        }

        val pt = SourceNormalizedPoint(
            x = s.x + 0.80f * (h.x - s.x),
            y = s.y + 0.80f * (h.y - s.y),
        )

        return TargetHeightEstimate(
            targetId = GEDAN_RATIO_080_ID,
            targetType = PunchHeightTargetType.GEDAN,
            point = pt,
            status = TargetEstimateStatus.VALID,
            contributingTimestampsUs = observed.torsoEvidence.contributingTimestampsUs,
            usableCount = observed.torsoEvidence.usableCount,
            configId = effectiveConfigId,
        )
    }

    /**
     * Gedan hip level: G = H
     */
    fun estimateGedanHipLevel(
        observed: ObservedBodyGeometry,
        config: BodyHeightModelConfig? = null,
    ): TargetHeightEstimate {
        val effectiveConfigId = config?.effectiveConfigId ?: observed.configId
        val h = observed.hipCenter
        if (h == null || observed.torsoEvidence.usableCount == 0 ||
            observed.torsoEvidence.windowState == WindowState.UNAVAILABLE
        ) {
            return TargetHeightEstimate(
                targetId = GEDAN_HIP_LEVEL_ID,
                targetType = PunchHeightTargetType.GEDAN,
                point = null,
                status = TargetEstimateStatus.UNAVAILABLE,
                contributingTimestampsUs = emptyList(),
                usableCount = 0,
                configId = effectiveConfigId,
                reason = "hip_center_unavailable",
            )
        }

        return TargetHeightEstimate(
            targetId = GEDAN_HIP_LEVEL_ID,
            targetType = PunchHeightTargetType.GEDAN,
            point = h,
            status = TargetEstimateStatus.VALID,
            contributingTimestampsUs = observed.torsoEvidence.contributingTimestampsUs,
            usableCount = observed.torsoEvidence.usableCount,
            configId = effectiveConfigId,
        )
    }

    /**
     * Jōdan: MouthMidpoint + 1.10 * (MouthMidpoint - Nose)
     * Requires shared validity of bilateral mouth and nose per contributing sample.
     */
    fun estimateJodanMouthNose110(
        selectedTimestampUs: Long,
        frames: List<PoseFrame>,
        radius: Int = 1,
        config: BodyHeightModelConfig = BodyHeightModelConfig(),
    ): TargetHeightEstimate {
        val effectiveConfigId = config.effectiveConfigId

        if (frames.isEmpty()) {
            return TargetHeightEstimate(
                targetId = JODAN_MOUTH_NOSE_110_ID,
                targetType = PunchHeightTargetType.JODAN,
                point = null,
                status = TargetEstimateStatus.UNAVAILABLE,
                contributingTimestampsUs = emptyList(),
                usableCount = 0,
                configId = effectiveConfigId,
                reason = "no_frames_provided",
            )
        }

        val selectedIdx = frames.indices.minByOrNull {
            abs(frames[it].timestampMs * 1000L - selectedTimestampUs)
        } ?: return TargetHeightEstimate(
            targetId = JODAN_MOUTH_NOSE_110_ID,
            targetType = PunchHeightTargetType.JODAN,
            point = null,
            status = TargetEstimateStatus.UNAVAILABLE,
            contributingTimestampsUs = emptyList(),
            usableCount = 0,
            configId = effectiveConfigId,
            reason = "selected_frame_not_found",
        )

        val minIdx = (selectedIdx - radius).coerceAtLeast(0)
        val maxIdx = (selectedIdx + radius).coerceAtMost(frames.size - 1)

        val selectedTs = frames[selectedIdx].timestampMs * 1000L
        val temporallyConnected = mutableSetOf(selectedIdx)

        // Backward continuity
        var prevTs = selectedTs
        for (i in (selectedIdx - 1) downTo minIdx) {
            val currTs = frames[i].timestampMs * 1000L
            if (prevTs - currTs > config.maxTimestampGapUs) break
            temporallyConnected.add(i)
            prevTs = currTs
        }

        // Forward continuity
        prevTs = selectedTs
        for (i in (selectedIdx + 1)..maxIdx) {
            val currTs = frames[i].timestampMs * 1000L
            if (currTs - prevTs > config.maxTimestampGapUs) break
            temporallyConnected.add(i)
            prevTs = currTs
        }

        data class SampleFace(
            val timestampUs: Long,
            val mouth: SourceNormalizedPoint,
            val nose: SourceNormalizedPoint,
        )

        val validFaceSamples = mutableListOf<SampleFace>()

        for (idx in minIdx..maxIdx) {
            if (idx !in temporallyConnected) continue
            val f = frames[idx]
            val ml = f.landmarks[PoseLandmarkId.MOUTH_LEFT]
            val mr = f.landmarks[PoseLandmarkId.MOUTH_RIGHT]
            val nose = f.landmarks[PoseLandmarkId.NOSE]

            if (isLandmarkValid(ml, config) && isLandmarkValid(mr, config) && isLandmarkValid(nose, config)) {
                val mouthMid = SourceNormalizedPoint(
                    (ml!!.position!!.x + mr!!.position!!.x) * 0.5f,
                    (ml.position!!.y + mr.position!!.y) * 0.5f,
                )
                val nosePt = SourceNormalizedPoint(nose!!.position!!.x, nose.position!!.y)
                validFaceSamples.add(SampleFace(f.timestampMs * 1000L, mouthMid, nosePt))
            }
        }

        if (validFaceSamples.isEmpty()) {
            return TargetHeightEstimate(
                targetId = JODAN_MOUTH_NOSE_110_ID,
                targetType = PunchHeightTargetType.JODAN,
                point = null,
                status = TargetEstimateStatus.UNAVAILABLE,
                contributingTimestampsUs = emptyList(),
                usableCount = 0,
                configId = effectiveConfigId,
                reason = "missing_face_landmarks",
            )
        }

        val aggMouthX = median(validFaceSamples.map { it.mouth.x })
        val aggMouthY = median(validFaceSamples.map { it.mouth.y })
        val aggNoseX = median(validFaceSamples.map { it.nose.x })
        val aggNoseY = median(validFaceSamples.map { it.nose.y })

        val mouth = SourceNormalizedPoint(aggMouthX, aggMouthY)
        val nose = SourceNormalizedPoint(aggNoseX, aggNoseY)

        val jodan = SourceNormalizedPoint(
            x = mouth.x + 1.10f * (mouth.x - nose.x),
            y = mouth.y + 1.10f * (mouth.y - nose.y),
        )

        return TargetHeightEstimate(
            targetId = JODAN_MOUTH_NOSE_110_ID,
            targetType = PunchHeightTargetType.JODAN,
            point = jodan,
            status = TargetEstimateStatus.VALID,
            contributingTimestampsUs = validFaceSamples.map { it.timestampUs },
            usableCount = validFaceSamples.size,
            configId = effectiveConfigId,
        )
    }

    private fun isLandmarkValid(sample: PoseLandmarkSample?, config: BodyHeightModelConfig): Boolean {
        if (sample == null || sample.position == null) return false
        val p = sample.position
        if (!p.x.isFinite() || !p.y.isFinite()) return false
        return sample.confidence >= config.landmarkValidityThreshold
    }

    private fun median(values: List<Float>): Float {
        require(values.isNotEmpty())
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) * 0.5f
    }
}

