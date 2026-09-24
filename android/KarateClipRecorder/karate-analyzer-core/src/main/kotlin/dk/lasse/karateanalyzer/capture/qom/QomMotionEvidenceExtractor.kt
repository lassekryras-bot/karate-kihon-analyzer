package dk.lasse.karateanalyzer.capture.qom

import dk.lasse.karateanalyzer.core.Point3
import dk.lasse.karateanalyzer.core.PoseFrame
import dk.lasse.karateanalyzer.core.PoseLandmarkId
import dk.lasse.karateanalyzer.motion.CausalCoordinateMotionFilter
import kotlin.math.sqrt

/**
 * Causal Quantity-of-Motion evidence extractor.
 *
 * Implements the normative signal pipeline:
 * MediaPipe world landmarks -> subtract hip midpoint -> effective points ->
 * 3-frame coordinate-wise median -> 3-frame trailing mean -> point velocity ->
 * confidence-weighted block QoM -> equal block average -> trailing 150 ms trapezoidal area.
 */
class QomMotionEvidenceExtractor(
    val config: QomDetectorConfig = QomDetectorConfig(),
    val profile: MotionBodyProfile = MotionBodyProfile.PUNCH,
) {
    private var previousTimestampMs: Long? = null

    private val pointFilters = mutableMapOf<String, CausalCoordinateMotionFilter>()

    // QoM time series for trailing trapezoidal area: list of Pair(timestampSec, qomValue)
    private data class QomSample(val timestampSec: Double, val qom: Double)
    private val qomHistory = mutableListOf<QomSample>()

    /**
     * Resets internal filter history and rolling accumulator.
     */
    fun reset() {
        previousTimestampMs = null
        pointFilters.clear()
        qomHistory.clear()
    }

    /**
     * Processes a single [PoseFrame] and returns the computed [QomFrameEvidence].
     *
     * @throws IllegalArgumentException if timestamps are non-increasing.
     */
    fun extract(frame: PoseFrame): QomFrameEvidence {
        val currentMs = frame.timestampMs
        val prevMs = previousTimestampMs

        if (prevMs != null && currentMs <= prevMs) {
            throw IllegalArgumentException(
                "Timestamps must be strictly increasing: previous=$prevMs, current=$currentMs",
            )
        }

        // Handle timestamp gap reset
        if (prevMs != null && (currentMs - prevMs) > config.maximumFrameGapMs) {
            reset()
        }
        previousTimestampMs = currentMs

        val currentSec = currentMs / 1000.0

        // 1. Hip midpoint calculation: H_i = (LEFT_HIP + RIGHT_HIP) / 2
        val leftHipSample = frame.landmarks[PoseLandmarkId.LEFT_HIP]
        val rightHipSample = frame.landmarks[PoseLandmarkId.RIGHT_HIP]
        val leftHipPos = leftHipSample?.worldPosition ?: leftHipSample?.position
        val rightHipPos = rightHipSample?.worldPosition ?: rightHipSample?.position

        if (leftHipPos == null || rightHipPos == null ||
            !leftHipPos.x.isFinite() || !leftHipPos.y.isFinite() || !leftHipPos.z.isFinite() ||
            !rightHipPos.x.isFinite() || !rightHipPos.y.isFinite() || !rightHipPos.z.isFinite()
        ) {
            return QomFrameEvidence(
                timestampMs = currentMs,
                profile = profile,
                blockQom = emptyMap(),
                aggregateQom = null,
                rollingArea = 0.0,
                availableBlockCount = 0,
                totalActiveBlockCount = profile.activeBlocks.size,
                isAvailable = false,
                reason = "hip_midpoint_unavailable",
            )
        }

        val hipMidpoint = Point3(
            (leftHipPos.x + rightHipPos.x) * 0.5f,
            (leftHipPos.y + rightHipPos.y) * 0.5f,
            (leftHipPos.z + rightHipPos.z) * 0.5f,
        )

        // 2. Extract effective points and subtract hip midpoint
        val effectivePoints = extractEffectivePoints(frame, hipMidpoint)

        // 3. Apply causal 3-sample median then 3-sample mean filtering
        val currentSmoothed = mutableMapOf<String, Point3>()
        val currentConfidences = mutableMapOf<String, Double>()
        val filterOutputs = mutableMapOf<String, CausalCoordinateMotionFilter.Output>()

        for ((ptId, sample) in effectivePoints) {
            val filter = pointFilters.getOrPut(ptId) {
                CausalCoordinateMotionFilter(
                    coordinateCount = 3,
                    medianSamples = config.positionMedianSamples,
                    meanSamples = config.positionMeanSamples,
                )
            }
            val output = filter.accept(
                timestampUs = currentMs * 1000L,
                coordinates = listOf(sample.position.x.toDouble(), sample.position.y.toDouble(), sample.position.z.toDouble()),
                confidence = sample.confidence,
            )
            filterOutputs[ptId] = output
            currentSmoothed[ptId] = Point3(
                output.coordinates[0].toFloat(),
                output.coordinates[1].toFloat(),
                output.coordinates[2].toFloat(),
            )
            currentConfidences[ptId] = output.confidence
        }

        // 4. Point velocities and weights
        if (prevMs == null) {
            // First frame: establish history, velocity is not yet defined
            return QomFrameEvidence(
                timestampMs = currentMs,
                profile = profile,
                blockQom = emptyMap(),
                aggregateQom = null,
                rollingArea = 0.0,
                availableBlockCount = 0,
                totalActiveBlockCount = profile.activeBlocks.size,
                isAvailable = false,
                reason = "initial_filter_startup",
                pointConfidences = currentConfidences,
            )
        }

        val dt = currentSec - (prevMs / 1000.0)
        if (dt <= 0.0) {
            return QomFrameEvidence(
                timestampMs = currentMs,
                profile = profile,
                blockQom = emptyMap(),
                aggregateQom = null,
                rollingArea = 0.0,
                availableBlockCount = 0,
                totalActiveBlockCount = profile.activeBlocks.size,
                isAvailable = false,
                reason = "non_positive_time_delta",
                pointConfidences = currentConfidences,
            )
        }

        val pointSpeeds = mutableMapOf<String, Double>()
        val pointWeights = mutableMapOf<String, Double>()

        for ((ptId, currPos) in currentSmoothed) {
            val output = filterOutputs[ptId] ?: continue
            val previous = output.previousCoordinates ?: continue
            val prevPos = Point3(previous[0].toFloat(), previous[1].toFloat(), previous[2].toFloat())
            val dx = (currPos.x - prevPos.x).toDouble()
            val dy = (currPos.y - prevPos.y).toDouble()
            val dz = (currPos.z - prevPos.z).toDouble()
            val speed = sqrt(dx * dx + dy * dy + dz * dz) / dt
            val weight = minOf(output.confidence, output.previousConfidence ?: 0.0)

            pointSpeeds[ptId] = speed
            pointWeights[ptId] = weight
        }

        // 5. Block Quantity of Motion: confidence-weighted mean
        val blockDefinitions = getBlockDefinitions(profile)
        val blockQom = mutableMapOf<MotionBlockId, Double>()

        for ((blockId, pointIds) in blockDefinitions) {
            var sumWeightSpeed = 0.0
            var sumWeight = 0.0
            for (ptId in pointIds) {
                val s = pointSpeeds[ptId]
                val w = pointWeights[ptId]
                if (s != null && w != null && w > 0.0) {
                    sumWeightSpeed += w * s
                    sumWeight += w
                }
            }
            if (sumWeight > 1e-9) {
                blockQom[blockId] = sumWeightSpeed / sumWeight
            }
        }

        // 6. Equal block aggregation
        val availableActiveBlocks = blockQom.filterKeys { it in profile.activeBlocks }
        val aggregateQom = if (availableActiveBlocks.size >= config.minimumValidBlocks) {
            availableActiveBlocks.values.average()
        } else {
            null
        }

        // 7. Rolling 150 ms trapezoidal area
        val rollingArea = if (aggregateQom != null) {
            qomHistory.add(QomSample(currentSec, aggregateQom))
            computeRollingArea(currentSec, config.rollingAreaDurationMs / 1000.0)
        } else {
            0.0
        }

        return QomFrameEvidence(
            timestampMs = currentMs,
            profile = profile,
            blockQom = blockQom,
            aggregateQom = aggregateQom,
            rollingArea = rollingArea,
            availableBlockCount = availableActiveBlocks.size,
            totalActiveBlockCount = profile.activeBlocks.size,
            isAvailable = (aggregateQom != null),
            pointConfidences = currentConfidences,
        )
    }

    private fun extractEffectivePoints(
        frame: PoseFrame,
        hipMidpoint: Point3,
    ): Map<String, EffectivePointSample> {
        val result = mutableMapOf<String, EffectivePointSample>()

        fun addSingle(pointId: String, landmarkId: PoseLandmarkId) {
            val sample = frame.landmarks[landmarkId]
            val wp = sample?.worldPosition ?: sample?.position
            if (wp != null && wp.x.isFinite() && wp.y.isFinite() && wp.z.isFinite()) {
                val relPos = Point3(wp.x - hipMidpoint.x, wp.y - hipMidpoint.y, wp.z - hipMidpoint.z)
                result[pointId] = EffectivePointSample(
                    pointId = pointId,
                    position = relPos,
                    confidence = ExtremityPointComposer.landmarkConfidence(sample),
                )
            }
        }

        fun addComposite(pointId: String, constituentIds: List<PoseLandmarkId>) {
            val comp = ExtremityPointComposer.compositePoint(frame, constituentIds)
            if (comp != null) {
                val (center, conf) = comp
                val relPos = Point3(center.x - hipMidpoint.x, center.y - hipMidpoint.y, center.z - hipMidpoint.z)
                result[pointId] = EffectivePointSample(
                    pointId = pointId,
                    position = relPos,
                    confidence = conf,
                )
            }
        }

        // Torso points participate in both PUNCH and KICK
        addSingle("LEFT_SHOULDER", PoseLandmarkId.LEFT_SHOULDER)
        addSingle("RIGHT_SHOULDER", PoseLandmarkId.RIGHT_SHOULDER)
        addSingle("LEFT_HIP", PoseLandmarkId.LEFT_HIP)
        addSingle("RIGHT_HIP", PoseLandmarkId.RIGHT_HIP)

        when (profile) {
            MotionBodyProfile.PUNCH -> {
                addSingle("LEFT_ELBOW", PoseLandmarkId.LEFT_ELBOW)
                addSingle("RIGHT_ELBOW", PoseLandmarkId.RIGHT_ELBOW)
                addComposite("LEFT_HAND", MotionBodyProfile.PUNCH_HAND_LANDMARKS_LEFT)
                addComposite("RIGHT_HAND", MotionBodyProfile.PUNCH_HAND_LANDMARKS_RIGHT)
            }
            MotionBodyProfile.KICK -> {
                addSingle("LEFT_KNEE", PoseLandmarkId.LEFT_KNEE)
                addSingle("RIGHT_KNEE", PoseLandmarkId.RIGHT_KNEE)
                addComposite("LEFT_FOOT", MotionBodyProfile.KICK_FOOT_LANDMARKS_LEFT)
                addComposite("RIGHT_FOOT", MotionBodyProfile.KICK_FOOT_LANDMARKS_RIGHT)
            }
        }

        return result
    }

    private fun getBlockDefinitions(profile: MotionBodyProfile): Map<MotionBlockId, List<String>> = when (profile) {
        MotionBodyProfile.PUNCH -> mapOf(
            MotionBlockId.LEFT_ARM to listOf("LEFT_ELBOW", "LEFT_HAND"),
            MotionBlockId.RIGHT_ARM to listOf("RIGHT_ELBOW", "RIGHT_HAND"),
            MotionBlockId.TORSO to listOf("LEFT_SHOULDER", "RIGHT_SHOULDER", "LEFT_HIP", "RIGHT_HIP"),
        )
        MotionBodyProfile.KICK -> mapOf(
            MotionBlockId.LEFT_LEG to listOf("LEFT_KNEE", "LEFT_FOOT"),
            MotionBlockId.RIGHT_LEG to listOf("RIGHT_KNEE", "RIGHT_FOOT"),
            MotionBlockId.TORSO to listOf("LEFT_SHOULDER", "RIGHT_SHOULDER", "LEFT_HIP", "RIGHT_HIP"),
        )
    }

    private fun computeRollingArea(currentSec: Double, windowDurationSec: Double): Double {
        val windowStartSec = currentSec - windowDurationSec

        // Prune entries older than windowStartSec (keeping at most one entry before windowStartSec for interpolation)
        while (qomHistory.size > 2 && qomHistory[1].timestampSec < windowStartSec) {
            qomHistory.removeAt(0)
        }

        if (qomHistory.size < 2) return 0.0

        var area = 0.0
        for (i in 0 until qomHistory.size - 1) {
            val s1 = qomHistory[i]
            val s2 = qomHistory[i + 1]

            if (s2.timestampSec <= windowStartSec) continue

            if (s1.timestampSec < windowStartSec) {
                // Linear interpolation at windowStartSec
                val dt = s2.timestampSec - s1.timestampSec
                val frac = if (dt > 1e-9) (windowStartSec - s1.timestampSec) / dt else 0.0
                val qInterp = s1.qom + frac * (s2.qom - s1.qom)
                val width = s2.timestampSec - windowStartSec
                area += 0.5 * (qInterp + s2.qom) * width
            } else {
                val width = s2.timestampSec - s1.timestampSec
                area += 0.5 * (s1.qom + s2.qom) * width
            }
        }

        return area
    }

}

