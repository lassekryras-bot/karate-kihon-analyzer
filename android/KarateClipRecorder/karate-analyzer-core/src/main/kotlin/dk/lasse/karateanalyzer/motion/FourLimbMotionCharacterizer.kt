package dk.lasse.karateanalyzer.motion

import dk.lasse.karateanalyzer.core.LandmarkSource
import dk.lasse.karateanalyzer.geometry.CanonicalGeometryDescriptor
import dk.lasse.karateanalyzer.geometry.LandmarkRelations
import dk.lasse.karateanalyzer.geometry.SourceNormalizedPoint
import dk.lasse.karateanalyzer.geometry.UpwardMetricPoint
import kotlin.math.abs
import kotlin.math.sign

/** Generic offline articulation evidence. No activity, side-selection, weapon or impact semantics. */
object FourLimbMotionCharacterizer {
    const val VERSION = "four-limb-motion-v1"

    fun characterize(input: FourLimbMotionInput, config: FourLimbMotionConfig = FourLimbMotionConfig()): FourLimbMotionResult {
        val failure = validate(input)
        val profiles = LimbId.entries.associateWith { limb ->
            if (failure != null) unavailable(limb, input, failure)
            else characterizeLimb(limb, input, config)
        }
        val measurable = profiles.values.filter { it.metrics != null }
        val active = measurable.filter { it.metrics!!.meaningfulAngularTravelDeg > 0.0 }
            .sortedByDescending { it.metrics!!.meaningfulAngularTravelDeg }
        val total = active.sumOf { it.metrics!!.meaningfulAngularTravelDeg }
        val complete = measurable.size == LimbId.entries.size
        val primary = active.firstOrNull()?.takeIf {
            complete && (active.size == 1 || it.metrics!!.meaningfulAngularTravelDeg >=
                active[1].metrics!!.meaningfulAngularTravelDeg * config.dominantTravelRatio)
        }?.limbId
        return FourLimbMotionResult(
            input.movementId, input.landmarkTrackId, input.logicalStartTimestampUs, input.logicalEndTimestampUs,
            input.canonicalGeometry, input.segmenterVersion, config, VERSION, AngularMotionEvidence.VERSION,
            input.stablePreWindow, input.stablePostWindow, profiles,
            LimbActivityRanking(
                if (total > 0) measurable.associate { it.limbId to it.metrics!!.meaningfulAngularTravelDeg / total } else null,
                active.map { it.limbId }, measurable.filter { it.motionPattern == LimbMotionPattern.STABLE }.map { it.limbId }.toSet(),
                primary, complete,
            ),
        )
    }

    private fun validate(input: FourLimbMotionInput): LimbAbstentionReason? {
        if (input.movementId.isBlank() || input.logicalStartTimestampUs < 0 ||
            input.logicalEndTimestampUs <= input.logicalStartTimestampUs) return LimbAbstentionReason.INVALID_BOUNDS
        val pre = input.stablePreWindow
        val post = input.stablePostWindow
        if (listOfNotNull(pre, post).any { it.startTimestampUs < 0 || it.endTimestampUs <= it.startTimestampUs } ||
            (pre != null && pre.endTimestampUs > input.logicalStartTimestampUs) ||
            (post != null && post.startTimestampUs < input.logicalEndTimestampUs)) return LimbAbstentionReason.INVALID_BOUNDS
        if (!input.canonicalGeometry.isAvailable ||
            input.canonicalGeometry.contractVersion != CanonicalGeometryDescriptor.CONTRACT_VERSION) return LimbAbstentionReason.FRAME_GEOMETRY_UNAVAILABLE
        if (input.landmarkTrackId.isBlank() || input.canonicalGeometry.landmarkTrackId != input.landmarkTrackId)
            return LimbAbstentionReason.TRACK_IDENTITY_MISMATCH
        if (input.frames.any { it.timestampMs < 0 || it.timestampMs > Long.MAX_VALUE / 1000 } ||
            input.frames.zipWithNext().any { (a, b) -> b.timestampMs <= a.timestampMs }) return LimbAbstentionReason.INVALID_TIMESTAMPS
        return null
    }

    private fun unavailable(
        limb: LimbId, input: FourLimbMotionInput, reason: LimbAbstentionReason,
        samples: List<LimbMotionSample> = emptyList(), coverage: Double = 0.0,
        flags: Set<LimbQualityFlag> = emptySet(),
    ) = LimbMotionProfile(limb, input.landmarkTrackId, null, LimbMotionPattern.INSUFFICIENT_EVIDENCE,
        coverage, samples.mapNotNull { it.confidence }.minOrNull(), flags, reason, samples)

    private fun characterizeLimb(limb: LimbId, input: FourLimbMotionInput, config: FourLimbMotionConfig): LimbMotionProfile {
        val geometry = input.canonicalGeometry.toFrameGeometry()
        val flags = mutableSetOf<LimbQualityFlag>()
        var block = 0
        var previousTime: Long? = null
        val filter = CausalCoordinateMotionFilter(1, config.medianSamples, config.meanSamples)
        val from = input.stablePreWindow?.startTimestampUs ?: input.logicalStartTimestampUs
        val to = input.stablePostWindow?.endTimestampUs ?: input.logicalEndTimestampUs
        val observed = input.frames.filter { it.timestampMs * 1000 in from..to }.map { frame ->
            val time = frame.timestampMs * 1000
            val landmarks = limb.sourceLandmarks.map { frame.landmarks[it] }
            if (landmarks.any { it != null && it.source != LandmarkSource.OBSERVED }) flags += LimbQualityFlag.NON_OBSERVED_SAMPLES
            val valid = landmarks.all { it != null && it.isObserved(config.minimumConfidence) &&
                it.position!!.x.isFinite() && it.position.y.isFinite() && it.confidence.isFinite() }
            val points = if (valid) landmarks.map { landmark ->
                val p = landmark!!.position!!
                UpwardMetricPoint.fromSource(SourceNormalizedPoint(p.x, p.y), geometry)
            } else emptyList()
            val raw = if (valid) LandmarkRelations.jointAngle(points[0], points[1], points[2])?.toDouble() else null
            if (raw == null || !raw.isFinite()) {
                flags += LimbQualityFlag.GAPS
                filter.reset()
                previousTime = null
                block++
                LimbMotionSample(time, null, null, null, null)
            } else {
                if (previousTime != null && time - previousTime!! > config.maximumGapUs) {
                    flags += LimbQualityFlag.GAPS
                    block++
                    filter.reset()
                }
                val confidence = landmarks.minOf { it!!.confidence.toDouble() }
                val filtered = filter.accept(time, listOf(raw), confidence)
                previousTime = time
                LimbMotionSample(time, raw, filtered.coordinates.single(), confidence, block)
            }
        }
        val logical = observed.filter { it.timestampUs in input.logicalStartTimestampUs..input.logicalEndTimestampUs }.toMutableList()
        val valid = logical.filter { it.angleDeg != null }
        val sampleCoverage = if (logical.isEmpty()) 0.0 else valid.size.toDouble() / logical.size
        val connectedDuration = logical.zipWithNext().sumOf { (a, b) ->
            if (a.blockId != null && a.blockId == b.blockId) b.timestampUs - a.timestampUs else 0L
        }
        val span = input.logicalEndTimestampUs - input.logicalStartTimestampUs
        val timeCoverage = connectedDuration.toDouble() / span
        if (valid.size < 2) return unavailable(limb, input,
            if (logical.size >= 2) LimbAbstentionReason.JOINT_GEOMETRY_UNAVAILABLE else LimbAbstentionReason.INSUFFICIENT_VALID_SAMPLES,
            observed, sampleCoverage, flags)
        if (minOf(sampleCoverage, timeCoverage) < config.minimumCoverage) return unavailable(limb, input,
            LimbAbstentionReason.TRACKING_QUALITY_INSUFFICIENT, observed, sampleCoverage, flags)

        fun reference(window: LimbEvidenceWindow?, fallback: Double): Double? {
            if (window == null) {
                flags += LimbQualityFlag.BOUNDARY_REFERENCE
                return fallback
            }
            val samples = observed.filter { it.timestampUs in window.startTimestampUs..window.endTimestampUs }
            if (samples.size < 2 || samples.any { it.angleDeg == null } || samples.map { it.blockId }.distinct().size != 1) return null
            if (samples.first().timestampUs - window.startTimestampUs > config.maximumGapUs ||
                window.endTimestampUs - samples.last().timestampUs > config.maximumGapUs ||
                samples.last().timestampUs - samples.first().timestampUs < config.quietConfirmationUs) return null
            // Raw observations prevent optional filter lag from manufacturing a stable reference.
            val values = samples.map { it.rawAngleDeg!! }.sorted()
            if (values.last() - values.first() > config.angularDeadbandDeg) return null
            return if (values.size % 2 == 1) values[values.size / 2] else
                (values[values.size / 2 - 1] + values[values.size / 2]) / 2
        }
        val start = reference(input.stablePreWindow, valid.first().angleDeg!!)
        val end = reference(input.stablePostWindow, valid.last().angleDeg!!)
        if (start == null || end == null) return unavailable(limb, input, LimbAbstentionReason.INVALID_STABLE_REFERENCE,
            observed, sampleCoverage, flags)

        val reversals = mutableListOf<LimbReversal>()
        var onset: Long? = null
        val groups = logical.indices.filter { logical[it].blockId != null }.groupBy { logical[it].blockId }
        for (indices in groups.values) {
            val series = indices.map { AngularMotionEvidence.Sample(logical[it].timestampUs, logical[it].angleDeg!!) }
            val motion = AngularMotionEvidence.characterize(series, config.angularDeadbandDeg)
            for (i in 1 until indices.size) {
                val index = indices[i]
                val dt = series[i].timestampUs - series[i - 1].timestampUs
                val delta = motion.meaningfulDeltasDeg[i]
                logical[index] = logical[index].copy(meaningfulDeltaDeg = delta, angularSpeedDegPerSec = abs(delta) * 1_000_000 / dt)
            }
            for (run in motion.runs) {
                val support = (run.startIndex + 1..run.endIndex).sumOf { i ->
                    if (motion.meaningfulDeltasDeg[i] != 0.0) series[i].timestampUs - series[i - 1].timestampUs else 0L
                }
                if (onset == null && support >= config.onsetConfirmationUs) onset = series[run.startIndex].timestampUs
            }
            for ((before, after) in motion.runs.zipWithNext()) {
                val turning = series[before.endIndex]
                reversals += LimbReversal(turning.timestampUs, turning.angleDeg, before.direction, after.direction,
                    minOf(abs(turning.angleDeg - series[before.startIndex].angleDeg),
                        abs(series[after.endIndex].angleDeg - turning.angleDeg)))
            }
        }
        val moving = logical.indices.filter { (logical[it].meaningfulDeltaDeg ?: 0.0) != 0.0 }
        val positive = logical.sumOf { (it.meaningfulDeltaDeg ?: 0.0).coerceAtLeast(0.0) }
        val negative = -logical.sumOf { (it.meaningfulDeltaDeg ?: 0.0).coerceAtMost(0.0) }
        val movingDuration = moving.sumOf { logical[it].timestampUs - logical[it - 1].timestampUs }
        val quietWindows = mutableListOf<Pair<Int, Int>>()
        var quietStart: Int? = null
        fun finishQuiet(endIndex: Int) {
            quietStart?.let { s ->
                if (logical[endIndex].timestampUs - logical[s].timestampUs >= config.quietConfirmationUs &&
                    (s..endIndex).maxOf { logical[it].rawAngleDeg!! } - (s..endIndex).minOf { logical[it].rawAngleDeg!! } <= config.angularDeadbandDeg)
                    quietWindows += s to endIndex
            }
            quietStart = null
        }
        for (i in 1 until logical.size) {
            if (logical[i].meaningfulDeltaDeg == 0.0) {
                if (quietStart == null) quietStart = i - 1
            } else finishQuiet(i - 1)
        }
        if (logical.isNotEmpty()) finishQuiet(logical.lastIndex)
        val lastMoving = moving.lastOrNull()
        // Require a contiguous final quiet suffix after the last observed motion, not an earlier pause.
        val settling = quietWindows.lastOrNull()?.takeIf { (s, e) ->
            lastMoving != null && onset != null && s >= lastMoving && e == logical.lastIndex &&
                logical[s].blockId == logical[lastMoving].blockId &&
                input.logicalEndTimestampUs - logical[e].timestampUs <= config.maximumGapUs
        }
        if (onset == null && moving.isNotEmpty()) flags += LimbQualityFlag.UNCONFIRMED_ONSET
        if (settling == null && moving.isNotEmpty()) flags += LimbQualityFlag.UNCONFIRMED_SETTLING
        val excursion = valid.maxBy { abs(it.angleDeg!! - start) }
        val peak = logical.filter { (it.angularSpeedDegPerSec ?: 0.0) > 0 }.maxByOrNull { it.angularSpeedDegPerSec!! }
        val pattern = when {
            moving.isEmpty() -> LimbMotionPattern.STABLE
            reversals.size > 1 -> LimbMotionPattern.MULTI_DIRECTIONAL
            reversals.size == 1 -> if (reversals.single().beforeDirection > 0) LimbMotionPattern.OPEN_CLOSE_REVERSAL else LimbMotionPattern.CLOSE_OPEN_REVERSAL
            positive > 0 && negative > 0 -> LimbMotionPattern.MULTI_DIRECTIONAL
            positive > 0 -> LimbMotionPattern.MOSTLY_OPENING
            else -> LimbMotionPattern.MOSTLY_CLOSING
        }
        val metrics = LimbMotionMetrics(start, end, valid.minOf { it.angleDeg!! }, valid.maxOf { it.angleDeg!! }, end - start,
            positive + negative, positive, negative, abs(excursion.angleDeg!! - start), sign(excursion.angleDeg - start).toInt(),
            excursion.timestampUs, movingDuration, quietWindows.sumOf { (s, e) -> logical[e].timestampUs - logical[s].timestampUs },
            span - connectedDuration, moving.firstOrNull()?.let { logical[it - 1].timestampUs }, lastMoving?.let { logical[it].timestampUs },
            onset, settling?.let { logical[it.first].timestampUs }, settling?.let { (s, e) ->
                (s..e).first { logical[it].timestampUs - logical[s].timestampUs >= config.quietConfirmationUs }.let { logical[it].timestampUs }
            }, peak?.angularSpeedDegPerSec, peak?.timestampUs, reversals.toList())
        val logicalByTime = logical.associateBy { it.timestampUs }
        return LimbMotionProfile(limb, input.landmarkTrackId, metrics, pattern, sampleCoverage,
            valid.minOf { it.confidence!! }, flags.toSet(), null, observed.map { logicalByTime[it.timestampUs] ?: it })
    }
}
