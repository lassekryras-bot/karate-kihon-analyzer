package dk.lasse.karateanalyzer.capture.qom

import dk.lasse.karateanalyzer.capture.LateralSide
import dk.lasse.karateanalyzer.core.LandmarkSource
import dk.lasse.karateanalyzer.core.Point3
import dk.lasse.karateanalyzer.core.PoseFrame
import dk.lasse.karateanalyzer.core.PoseLandmarkId
import dk.lasse.karateanalyzer.motion.CausalCoordinateMotionFilter
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/** Configuration for the explicit offline side-view alternating-punch segmentation option. */
data class SideViewAlternatingPunchConfig(
    val version: String = QomDetectorConfig.SIDE_VIEW_ALTERNATING_PUNCH_V2,
    val repairSide: LateralSide = LateralSide.LEFT,
    val minimumObservedConfidence: Double = 0.55,
    val minimumCompositeSupport: Double = 0.55,
    val positionMedianSamples: Int = 3,
    val positionMeanSamples: Int = 3,
    val maximumRepairAnchorSpanMs: Long = 200L,
    val upperArmMadMultiplier: Double = 3.0,
    val madConsistencyScale: Double = 1.4826,
    val rollingAreaDurationMs: Long = 150L,
    val maximumFrameGapMs: Long = 500L,
    val minimumReferenceSamples: Int = 3,
    val minimumRiseSamples: Int = 2,
    val minimumPeakQuietRanges: Double = 8.0,
) {
    init {
        require(version in SUPPORTED_VERSIONS)
        require(repairSide == LateralSide.LEFT) { "Only fixture-demonstrated left-arm repair is supported" }
        require(minimumObservedConfidence in 0.0..1.0)
        require(minimumCompositeSupport in 0.0..1.0)
        require(positionMedianSamples == 3 && positionMeanSamples == 3) {
            "Side-view detector versions support only the validated 3/3 position filter"
        }
        require(maximumRepairAnchorSpanMs > 0L)
        require(upperArmMadMultiplier > 0.0 && madConsistencyScale > 0.0)
        require(rollingAreaDurationMs > 0L && maximumFrameGapMs > 0L)
        require(minimumReferenceSamples >= 3 && minimumRiseSamples >= 1)
        require(minimumPeakQuietRanges > 0.0)
    }

    companion object {
        /** The versioned QoM config is the sole persisted option source for production and replay. */
        fun from(qomConfig: QomDetectorConfig): SideViewAlternatingPunchConfig {
            val expected = when (qomConfig.detectorVersion) {
                QomDetectorConfig.SIDE_VIEW_ALTERNATING_PUNCH_V1 -> QomDetectorConfig.sideViewAlternatingPunchV1()
                QomDetectorConfig.SIDE_VIEW_ALTERNATING_PUNCH_V2 -> QomDetectorConfig.sideViewAlternatingPunchV2()
                else -> error("Unsupported side-view alternating-punch version: ${qomConfig.detectorVersion}")
            }
            require(qomConfig == expected) {
                "${qomConfig.detectorVersion} has fixed versioned settings; caller overrides are unsupported"
            }
            return SideViewAlternatingPunchConfig(
                version = qomConfig.detectorVersion,
                repairSide = requireNotNull(qomConfig.sideViewRepairSide),
                minimumObservedConfidence = qomConfig.minimumObservedConfidence,
                minimumCompositeSupport = qomConfig.minimumCompositeSupport,
                positionMedianSamples = qomConfig.positionMedianSamples,
                positionMeanSamples = qomConfig.positionMeanSamples,
                rollingAreaDurationMs = qomConfig.rollingAreaDurationMs,
                maximumFrameGapMs = qomConfig.maximumFrameGapMs,
            )
        }

        private val SUPPORTED_VERSIONS = setOf(
            QomDetectorConfig.SIDE_VIEW_ALTERNATING_PUNCH_V1,
            QomDetectorConfig.SIDE_VIEW_ALTERNATING_PUNCH_V2,
        )
    }
}

enum class DerivedLandmarkProvenance {
    OBSERVED,
    INTERPOLATED,
    UNRESOLVED_MISSING,
    UNRESOLVED_REJECTED,
}

enum class DerivedLandmarkRepairRefusalReason {
    MISSING_LEFT_ANCHOR,
    MISSING_RIGHT_ANCHOR,
    MISSING_BOTH_ANCHORS,
    ANCHOR_SPAN_NON_POSITIVE,
    ANCHOR_SPAN_EXCEEDS_LIMIT,
}

data class DerivedLandmarkEvidence(
    val point: Point3?,
    val reliability: Double?,
    val support: Double?,
    val provenance: DerivedLandmarkProvenance,
    val leftAnchorTimestampMs: Long? = null,
    val rightAnchorTimestampMs: Long? = null,
    val repairAnchorSpanMs: Long? = null,
    val repairRefusalReason: DerivedLandmarkRepairRefusalReason? = null,
    val rejectedObservedPoint: Point3? = null,
)

data class SideViewDerivedFrame(
    val timestampMs: Long,
    val landmarks: Map<PoseLandmarkId, DerivedLandmarkEvidence>,
    val smoothedReferenceCenter: Point3?,
    val instantaneousArmQom: Double?,
    val rollingArea: Double?,
    val slope: Double?,
    val signedLeftArmXVelocity: Double? = null,
    val signedRightArmXVelocity: Double? = null,
)

data class SideViewPunchWindow(
    val startFrameIndex: Int,
    val endFrameIndex: Int,
    val startTimestampMs: Long,
    val endTimestampMs: Long,
    val endBoundaryKind: QomEndBoundaryKind = QomEndBoundaryKind.STOP_GATE,
)

data class SideViewAlternatingPunchThresholds(
    val quietAreaCutoff: Double,
    val steepSlope: Double,
    val flatSlope: Double,
    val quietAmplitudeRange: Double,
)

enum class SideViewAlternatingPunchState { COMPLETED, ABSTAINED }

data class SideViewAlternatingPunchResult(
    val detectorVersion: String,
    val state: SideViewAlternatingPunchState,
    val reason: String,
    val candidateCount: Int,
    val windows: List<SideViewPunchWindow>,
    val derivedFrames: List<SideViewDerivedFrame>,
    val qomTimeline: List<QomFrameEvidence>,
    val thresholds: SideViewAlternatingPunchThresholds?,
    val upperArmReferenceSampleCount: Int,
    val upperArmMedian: Double?,
    val upperArmMad: Double?,
    val unresolvedRejectedCount: Int,
) {
    /** V2 is half-open; only an actual recording-end sample remains owned by the terminal window. */
    fun owningWindowIndex(frameIndex: Int): Int? = windows.indexOfLast { window ->
        frameIndex >= window.startFrameIndex &&
            when (detectorVersion) {
                QomDetectorConfig.SIDE_VIEW_ALTERNATING_PUNCH_V1 -> frameIndex < window.endFrameIndex ||
                    (window === windows.lastOrNull() && frameIndex == window.endFrameIndex)
                else -> frameIndex < window.endFrameIndex ||
                    (frameIndex == derivedFrames.lastIndex && window.endFrameIndex == derivedFrames.lastIndex)
            }
    }.takeIf { it >= 0 }
}

/**
 * Batch detector matching the documented offline side-view signal while refusing unresolved
 * rejected observations. Raw [PoseFrame] values are never modified.
 */
class SideViewAlternatingPunchSegmenter(
    val config: SideViewAlternatingPunchConfig = SideViewAlternatingPunchConfig(),
) {
    private data class SupportedPoint(val point: Point3, val reliability: Double, val support: Double)
    private data class VelocityPoint(val signedX: Double, val reliability: Double) {
        val absoluteX: Double get() = abs(signedX)
    }
    private data class ArmSignal(
        val absoluteQom: Double?,
        val signedLeftX: Double?,
        val signedRightX: Double?,
    )
    private data class DetectedWindow(
        val start: Int,
        val end: Int,
        val endBoundaryKind: QomEndBoundaryKind,
    )
    private data class Detection(
        val candidateCount: Int,
        val windows: List<DetectedWindow>,
        val thresholds: SideViewAlternatingPunchThresholds?,
    )

    fun segment(inputFrames: List<PoseFrame>): SideViewAlternatingPunchResult {
        if (inputFrames.isEmpty()) return abstained("no_frames", emptyList())
        require(inputFrames.zipWithNext().all { (a, b) -> a.timestampMs < b.timestampMs }) {
            "Timestamps must be strictly increasing"
        }

        val frames = inputFrames
        val observed = frames.map { frame ->
            PoseLandmarkId.entries.associateWith { id -> supported(frame, id) }
        }
        val upperArmLengths = observed.map { map ->
            val shoulder = map[PoseLandmarkId.LEFT_SHOULDER]
            val elbow = map[PoseLandmarkId.LEFT_ELBOW]
            if (shoulder == null || elbow == null) null else distance(shoulder.point, elbow.point)
        }
        val reference = upperArmLengths.filterNotNull()
        if (reference.size < config.minimumReferenceSamples) {
            return abstained("left_upper_arm_reference_insufficient", frames)
        }
        val upperArmMedian = median(reference)
        val upperArmMad = median(reference.map { abs(it - upperArmMedian) })
        val tolerance = config.upperArmMadMultiplier * config.madConsistencyScale * upperArmMad
        val acceptedUpperArm = upperArmLengths.map { length ->
            length != null && abs(length - upperArmMedian) <= tolerance
        }

        val repairIds = setOf(
            PoseLandmarkId.LEFT_SHOULDER, PoseLandmarkId.LEFT_ELBOW, PoseLandmarkId.LEFT_WRIST,
            PoseLandmarkId.LEFT_THUMB, PoseLandmarkId.LEFT_INDEX, PoseLandmarkId.LEFT_PINKY,
        )
        val derived = frames.indices.map { index ->
            val values = mutableMapOf<PoseLandmarkId, DerivedLandmarkEvidence>()
            CENTROID_ROSTER.forEach { id ->
                val raw = observed[index].getValue(id)
                values[id] = when {
                    id !in repairIds -> raw?.toObservedEvidence() ?: unresolvedMissing()
                    acceptedUpperArm[index] && raw != null -> raw.toObservedEvidence()
                    else -> interpolate(index, id, frames, observed, acceptedUpperArm, raw)
                }
            }
            SideViewDerivedFrame(frames[index].timestampMs, values, null, null, null, null)
        }
        val rawCenters = derived.map { frame ->
            frame.landmarks.values.mapNotNull { it.point }.takeIf { it.isNotEmpty() }?.let(::meanPoint)
        }
        val centers = rawCenters.indices.map { index ->
            val current = rawCenters[index]
            val previous = when {
                index == 0 -> current
                config.version == QomDetectorConfig.SIDE_VIEW_ALTERNATING_PUNCH_V2 &&
                    frames[index].timestampMs - frames[index - 1].timestampMs > config.maximumFrameGapMs -> current
                else -> rawCenters[index - 1]
            }
            val next = when {
                index == rawCenters.lastIndex -> current
                config.version == QomDetectorConfig.SIDE_VIEW_ALTERNATING_PUNCH_V2 &&
                    frames[index + 1].timestampMs - frames[index].timestampMs > config.maximumFrameGapMs -> current
                else -> rawCenters[index + 1]
            }
            if (previous == null || current == null || next == null) null else Point3(
                (0.25 * previous.x + 0.50 * current.x + 0.25 * next.x).toFloat(),
                (0.25 * previous.y + 0.50 * current.y + 0.25 * next.y).toFloat(),
                (0.25 * previous.z + 0.50 * current.z + 0.25 * next.z).toFloat(),
            )
        }
        val armSignals = armQom(frames, derived, centers)
        val instantaneous = armSignals.map { it.absoluteQom }
        val areas = rollingAreas(frames, instantaneous)
        val slopes = slopes(frames, areas)
        val detection = detect(frames, areas, slopes)
        val completedFrames = derived.indices.map { index -> derived[index].copy(
            smoothedReferenceCenter = centers[index],
            instantaneousArmQom = instantaneous[index],
            rollingArea = areas[index],
            slope = slopes[index],
            signedLeftArmXVelocity = armSignals[index].signedLeftX,
            signedRightArmXVelocity = armSignals[index].signedRightX,
        ) }
        val qom = completedFrames.map { frame ->
            val available = frame.instantaneousArmQom != null && frame.rollingArea != null
            QomFrameEvidence(
                timestampMs = frame.timestampMs,
                profile = MotionBodyProfile.PUNCH,
                blockQom = emptyMap(),
                aggregateQom = frame.instantaneousArmQom,
                rollingArea = frame.rollingArea ?: 0.0,
                availableBlockCount = if (available) 1 else 0,
                totalActiveBlockCount = 2,
                isAvailable = available,
                reason = if (available) null else "side_view_punch_signal_unavailable",
            )
        }
        val windows = detection.windows.map { window ->
            SideViewPunchWindow(
                window.start,
                window.end,
                frames[window.start].timestampMs,
                frames[window.end].timestampMs,
                window.endBoundaryKind,
            )
        }
        val unresolvedRejected = completedFrames.sumOf { frame ->
            frame.landmarks.values.count { it.provenance == DerivedLandmarkProvenance.UNRESOLVED_REJECTED }
        }
        val state = if (detection.thresholds == null) SideViewAlternatingPunchState.ABSTAINED else SideViewAlternatingPunchState.COMPLETED
        val reason = if (detection.thresholds == null) "quiet_reference_degenerate_or_insufficient" else "completed"
        return SideViewAlternatingPunchResult(
            config.version, state, reason, detection.candidateCount, windows, completedFrames, qom, detection.thresholds,
            reference.size, upperArmMedian, upperArmMad, unresolvedRejected,
        )
    }

    private fun supported(frame: PoseFrame, id: PoseLandmarkId): SupportedPoint? {
        val sample = frame.landmarks[id] ?: return null
        val point = sample.worldPosition ?: return null
        if (sample.source != LandmarkSource.OBSERVED || !sample.visibility.isFinite() || !sample.presence.isFinite() ||
            !point.x.isFinite() || !point.y.isFinite() || !point.z.isFinite()
        ) return null
        val support = minOf(sample.visibility, sample.presence).coerceIn(0f, 1f).toDouble()
        if (support < config.minimumObservedConfidence) return null
        val reliability = sample.visibility.coerceIn(0f, 1f).toDouble() *
            sample.presence.coerceIn(0f, 1f).toDouble()
        return SupportedPoint(point, reliability, support)
    }

    private fun interpolate(
        index: Int,
        id: PoseLandmarkId,
        frames: List<PoseFrame>,
        observed: List<Map<PoseLandmarkId, SupportedPoint?>>,
        acceptedUpperArm: List<Boolean>,
        rejectedObserved: SupportedPoint?,
    ): DerivedLandmarkEvidence {
        val left = (index - 1 downTo 0).firstOrNull { acceptedUpperArm[it] && observed[it].getValue(id) != null }
        val right = (index + 1..frames.lastIndex).firstOrNull { acceptedUpperArm[it] && observed[it].getValue(id) != null }
        val unresolved = {
            val refusalReason = when {
                left == null && right == null -> DerivedLandmarkRepairRefusalReason.MISSING_BOTH_ANCHORS
                left == null -> DerivedLandmarkRepairRefusalReason.MISSING_LEFT_ANCHOR
                right == null -> DerivedLandmarkRepairRefusalReason.MISSING_RIGHT_ANCHOR
                frames[right].timestampMs - frames[left].timestampMs <= 0L ->
                    DerivedLandmarkRepairRefusalReason.ANCHOR_SPAN_NON_POSITIVE
                else -> DerivedLandmarkRepairRefusalReason.ANCHOR_SPAN_EXCEEDS_LIMIT
            }
            DerivedLandmarkEvidence(
                point = null,
                reliability = null,
                support = null,
                provenance = if (rejectedObserved == null) DerivedLandmarkProvenance.UNRESOLVED_MISSING
                else DerivedLandmarkProvenance.UNRESOLVED_REJECTED,
                leftAnchorTimestampMs = left?.let { frames[it].timestampMs },
                rightAnchorTimestampMs = right?.let { frames[it].timestampMs },
                repairAnchorSpanMs = if (left != null && right != null) {
                    frames[right].timestampMs - frames[left].timestampMs
                } else null,
                repairRefusalReason = refusalReason,
                rejectedObservedPoint = rejectedObserved?.point,
            )
        }
        if (left == null || right == null) return unresolved()
        val span = frames[right].timestampMs - frames[left].timestampMs
        if (span <= 0L || span > config.maximumRepairAnchorSpanMs) return unresolved()
        val a = observed[left].getValue(id)!!
        val b = observed[right].getValue(id)!!
        val weight = (frames[index].timestampMs - frames[left].timestampMs).toDouble() / span.toDouble()
        return DerivedLandmarkEvidence(
            point = Point3(
                (a.point.x + weight * (b.point.x - a.point.x)).toFloat(),
                (a.point.y + weight * (b.point.y - a.point.y)).toFloat(),
                (a.point.z + weight * (b.point.z - a.point.z)).toFloat(),
            ),
            reliability = minOf(a.reliability, b.reliability),
            support = minOf(a.support, b.support),
            provenance = DerivedLandmarkProvenance.INTERPOLATED,
            leftAnchorTimestampMs = frames[left].timestampMs,
            rightAnchorTimestampMs = frames[right].timestampMs,
            repairAnchorSpanMs = span,
        )
    }

    private fun armQom(
        frames: List<PoseFrame>,
        derived: List<SideViewDerivedFrame>,
        centers: List<Point3?>,
    ): List<ArmSignal> {
        val filters = mutableMapOf<String, CausalCoordinateMotionFilter>()
        var previousTimestampMs: Long? = null
        return frames.indices.map { index ->
            val timestamp = frames[index].timestampMs
            if (previousTimestampMs != null && timestamp - previousTimestampMs!! > config.maximumFrameGapMs) filters.clear()
            previousTimestampMs = timestamp
            val landmarks = derived[index].landmarks
            val center = centers[index]
            val bothHips = landmarks[PoseLandmarkId.LEFT_HIP]?.point != null && landmarks[PoseLandmarkId.RIGHT_HIP]?.point != null
            val effective = mutableMapOf<String, Pair<Point3, Double>>()
            if (center != null && bothHips) {
                fun single(name: String, id: PoseLandmarkId) {
                    val evidence = landmarks[id] ?: return
                    val point = evidence.point ?: return
                    val reliability = evidence.reliability ?: return
                    effective[name] = (point - center) to reliability
                }
                fun hand(name: String, ids: List<PoseLandmarkId>) {
                    val points = ids.mapNotNull { id -> landmarks[id]?.takeIf { it.point != null && it.reliability != null && it.support != null } }
                    if (points.sumOf { it.support!! } < config.minimumCompositeSupport * ids.size) return
                    val totalWeight = points.sumOf { it.reliability!! }
                    if (totalWeight <= 0.0) return
                    val point = Point3(
                        (points.sumOf { it.point!!.x * it.reliability!! } / totalWeight).toFloat(),
                        (points.sumOf { it.point!!.y * it.reliability!! } / totalWeight).toFloat(),
                        (points.sumOf { it.point!!.z * it.reliability!! } / totalWeight).toFloat(),
                    )
                    effective[name] = (point - center) to (totalWeight / ids.size)
                }
                single("LEFT_ELBOW", PoseLandmarkId.LEFT_ELBOW)
                single("RIGHT_ELBOW", PoseLandmarkId.RIGHT_ELBOW)
                single("LEFT_SHOULDER", PoseLandmarkId.LEFT_SHOULDER)
                single("RIGHT_SHOULDER", PoseLandmarkId.RIGHT_SHOULDER)
                single("LEFT_HIP", PoseLandmarkId.LEFT_HIP)
                single("RIGHT_HIP", PoseLandmarkId.RIGHT_HIP)
                hand("LEFT_HAND", MotionBodyProfile.PUNCH_HAND_LANDMARKS_LEFT)
                hand("RIGHT_HAND", MotionBodyProfile.PUNCH_HAND_LANDMARKS_RIGHT)
            }
            filters.keys.retainAll(effective.keys)
            val velocities = effective.mapNotNull { (name, value) ->
                val output = filters.getOrPut(name) {
                    CausalCoordinateMotionFilter(4, config.positionMedianSamples, config.positionMeanSamples)
                }.accept(
                    timestamp * 1000L,
                    listOf(value.first.x.toDouble(), value.first.y.toDouble(), value.first.z.toDouble(), value.second),
                    value.second,
                )
                val previous = output.previousCoordinates ?: return@mapNotNull null
                val dt = output.deltaSeconds?.takeIf { it > 0.0 } ?: return@mapNotNull null
                name to VelocityPoint((output.coordinates[0] - previous[0]) / dt, minOf(previous[3], output.coordinates[3]))
            }.toMap()
            fun arm(side: String, absolute: Boolean): Double? {
                val values = listOfNotNull(velocities["${side}_ELBOW"], velocities["${side}_HAND"])
                val weight = values.sumOf { it.reliability }
                return if (weight > 0.0) values.sumOf {
                    (if (absolute) it.absoluteX else it.signedX) * it.reliability
                } / weight else null
            }
            val leftAbsolute = arm("LEFT", absolute = true)
            val rightAbsolute = arm("RIGHT", absolute = true)
            ArmSignal(
                absoluteQom = listOfNotNull(leftAbsolute, rightAbsolute).takeIf { it.isNotEmpty() }?.average(),
                signedLeftX = arm("LEFT", absolute = false),
                signedRightX = arm("RIGHT", absolute = false),
            )
        }
    }

    private fun rollingAreas(frames: List<PoseFrame>, qom: List<Double?>): List<Double?> = frames.indices.map { index ->
        val windowStart = frames[index].timestampMs - config.rollingAreaDurationMs
        var right = 0
        while (right <= index && frames[right].timestampMs <= windowStart) right++
        if (right < 1) return@map null
        val left = right - 1
        val leftQom = qom[left] ?: return@map null
        val rightQom = qom[right] ?: return@map null
        val span = frames[right].timestampMs - frames[left].timestampMs
        if (span <= 0L || span > config.maximumFrameGapMs) return@map null
        val fraction = (windowStart - frames[left].timestampMs).toDouble() / span.toDouble()
        var previousTime = windowStart.toDouble()
        var previousValue = leftQom + fraction * (rightQom - leftQom)
        var area = 0.0
        for (sample in right..index) {
            val value = qom[sample] ?: return@map null
            val sampleTime = frames[sample].timestampMs.toDouble()
            if (sample > right && sampleTime - previousTime > config.maximumFrameGapMs) return@map null
            area += 0.5 * (previousValue + value) * ((sampleTime - previousTime) / 1000.0)
            previousTime = sampleTime
            previousValue = value
        }
        area
    }

    private fun slopes(frames: List<PoseFrame>, areas: List<Double?>): List<Double?> = frames.indices.map { index ->
        if (index < 2) return@map null
        val rawValues = (index - 2..index).map { areas[it] }
        if (rawValues.any { it == null }) return@map null
        val values = rawValues.filterNotNull()
        if (frames[index].timestampMs - frames[index - 1].timestampMs > config.maximumFrameGapMs ||
            frames[index - 1].timestampMs - frames[index - 2].timestampMs > config.maximumFrameGapMs
        ) return@map null
        val times = (index - 2..index).map { frames[it].timestampMs / 1000.0 }
        val mean = times.average()
        val centered = times.map { it - mean }
        val denominator = centered.sumOf { it * it }
        if (denominator <= 0.0) null else centered.indices.sumOf { centered[it] * values[it] } / denominator
    }

    private fun detect(frames: List<PoseFrame>, areas: List<Double?>, slopes: List<Double?>): Detection {
        val finiteAreas = areas.filterNotNull().filter(Double::isFinite)
        if (finiteAreas.size < 4) return Detection(0, emptyList(), null)
        val quietCutoff = percentile(finiteAreas, 25.0)
        val quietIndices = areas.indices.filter { areas[it]?.let { value -> value <= quietCutoff } == true }
        val quietAreas = quietIndices.mapNotNull(areas::get).filter(Double::isFinite)
        val quietSlopes = quietIndices.mapNotNull(slopes::get).filter(Double::isFinite).map(::abs)
        if (quietAreas.size < 2 || quietSlopes.isEmpty()) return Detection(0, emptyList(), null)
        val steep = percentile(quietSlopes, 95.0)
        val flat = median(quietSlopes)
        val quietRange = percentile(quietAreas, 95.0) - percentile(quietAreas, 5.0)
        if (!steep.isFinite() || !flat.isFinite() || !quietRange.isFinite() || quietRange <= 0.0) {
            return Detection(0, emptyList(), null)
        }
        val thresholds = SideViewAlternatingPunchThresholds(quietCutoff, steep, flat, quietRange)
        val candidates = mutableListOf<DetectedWindow>()
        var active = false
        var bottom: Int? = null
        var rise = 0
        var start = 0
        var peak = 0
        var falling = false
        var lastAvailable: Int? = null
        for (index in frames.indices) {
            val slope = slopes[index]
            val area = areas[index]
            if (slope == null || area == null || !slope.isFinite() || !area.isFinite()) {
                // Missing evidence and long gaps are never quiet evidence. A candidate that was
                // already established closes at its last supported sample rather than disappearing.
                if (active && lastAvailable != null && lastAvailable >= peak) {
                    candidates += DetectedWindow(
                        start,
                        lastAvailable,
                        QomEndBoundaryKind.EVIDENCE_GAP_WITHOUT_STOP_EVIDENCE,
                    )
                }
                active = false
                bottom = null
                rise = 0
                falling = false
                lastAvailable = null
                continue
            }
            lastAvailable = index
            if (bottom == null) bottom = index
            if (!active) {
                val currentBottom = bottom!!
                if (area <= areas[currentBottom]!! ||
                    (index >= 2 && areas[index - 2] != null && areas[index - 1] != null &&
                        areas[index - 2]!! > areas[index - 1]!! && areas[index - 1]!! <= area)
                ) {
                    bottom = if (index > 0 && areas[index - 1] != null && area > areas[index - 1]!!) index - 1 else index
                }
                rise = if (slope > steep) rise + 1 else 0
                if (rise >= config.minimumRiseSamples && area - areas[bottom!!]!! >= quietRange) {
                    active = true
                    start = bottom!!
                    peak = index
                    falling = false
                }
            } else {
                if (area > areas[peak]!!) {
                    peak = index
                    falling = false
                }
                if (slope < -steep && areas[peak]!! - area >= quietRange) falling = true
                if (falling && slope >= -flat) {
                    val first = maxOf(peak, index - 2)
                    val end = (first..index).minBy { areas[it]!! }
                    candidates += DetectedWindow(start, end, QomEndBoundaryKind.STOP_GATE)
                    active = false
                    bottom = end
                    rise = 0
                }
            }
        }
        if (active) candidates += DetectedWindow(
            start,
            frames.lastIndex,
            QomEndBoundaryKind.RECORDING_END_WITHOUT_STOP_EVIDENCE,
        )
        val accepted = candidates.filter { candidate ->
            val startIndex = candidate.start
            val endIndex = candidate.end
            val startArea = areas[startIndex] ?: return@filter false
            val peakArea = (startIndex..endIndex).mapNotNull(areas::get).maxOrNull() ?: return@filter false
            peakArea - startArea >= config.minimumPeakQuietRanges * quietRange
        }
        return Detection(candidates.size, accepted, thresholds)
    }

    private fun abstained(reason: String, frames: List<PoseFrame>) = SideViewAlternatingPunchResult(
        config.version, SideViewAlternatingPunchState.ABSTAINED, reason, 0, emptyList(),
        frames.map { SideViewDerivedFrame(it.timestampMs, emptyMap(), null, null, null, null) },
        frames.map { QomFrameEvidence(it.timestampMs, MotionBodyProfile.PUNCH, emptyMap(), null, 0.0, 0, 2, false, reason) },
        null, 0, null, null, 0,
    )

    private fun SupportedPoint.toObservedEvidence() = DerivedLandmarkEvidence(
        point, reliability, support, DerivedLandmarkProvenance.OBSERVED,
    )

    private fun unresolvedMissing() = DerivedLandmarkEvidence(
        null, null, null, DerivedLandmarkProvenance.UNRESOLVED_MISSING,
    )

    private fun distance(a: Point3, b: Point3): Double {
        val dx = (a.x - b.x).toDouble()
        val dy = (a.y - b.y).toDouble()
        val dz = (a.z - b.z).toDouble()
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun meanPoint(points: List<Point3>) = Point3(
        points.map { it.x.toDouble() }.average().toFloat(),
        points.map { it.y.toDouble() }.average().toFloat(),
        points.map { it.z.toDouble() }.average().toFloat(),
    )

    private fun median(values: List<Double>) = percentile(values, 50.0)

    private fun percentile(values: List<Double>, percentile: Double): Double {
        require(values.isNotEmpty() && percentile in 0.0..100.0)
        val sorted = values.sorted()
        val position = (sorted.size - 1) * percentile / 100.0
        val lower = floor(position).toInt()
        val upper = kotlin.math.ceil(position).toInt()
        if (lower == upper) return sorted[lower]
        val weight = position - lower
        return sorted[lower] * (1.0 - weight) + sorted[upper] * weight
    }

    companion object {
        /** Explicit roster matching the executable reference; suffix matching is intentionally absent. */
        val CENTROID_ROSTER: Set<PoseLandmarkId> = setOf(
            PoseLandmarkId.LEFT_SHOULDER, PoseLandmarkId.RIGHT_SHOULDER,
            PoseLandmarkId.LEFT_ELBOW, PoseLandmarkId.RIGHT_ELBOW,
            PoseLandmarkId.LEFT_WRIST, PoseLandmarkId.RIGHT_WRIST,
            PoseLandmarkId.LEFT_PINKY, PoseLandmarkId.RIGHT_PINKY,
            PoseLandmarkId.LEFT_INDEX, PoseLandmarkId.RIGHT_INDEX,
            PoseLandmarkId.LEFT_THUMB, PoseLandmarkId.RIGHT_THUMB,
            PoseLandmarkId.LEFT_HIP, PoseLandmarkId.RIGHT_HIP,
            PoseLandmarkId.LEFT_KNEE, PoseLandmarkId.RIGHT_KNEE,
            PoseLandmarkId.LEFT_ANKLE, PoseLandmarkId.RIGHT_ANKLE,
            PoseLandmarkId.LEFT_HEEL, PoseLandmarkId.RIGHT_HEEL,
            PoseLandmarkId.LEFT_FOOT_INDEX, PoseLandmarkId.RIGHT_FOOT_INDEX,
        )
    }
}
