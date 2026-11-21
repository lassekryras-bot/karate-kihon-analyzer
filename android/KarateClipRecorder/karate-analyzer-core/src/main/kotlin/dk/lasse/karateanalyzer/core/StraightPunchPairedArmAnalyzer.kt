package dk.lasse.karateanalyzer.core

import dk.lasse.karateanalyzer.capture.LateralSide
import dk.lasse.karateanalyzer.geometry.CanonicalGeometryDescriptor
import dk.lasse.karateanalyzer.motion.CausalCoordinateMotionFilter
import kotlin.math.cos

enum class ForwardAxisStatus { RELIABLE, UNAVAILABLE, UNRELIABLE }
enum class PairedArmSupportMode { BILATERAL, SINGLE_LEFT, SINGLE_RIGHT, INSUFFICIENT }
enum class PairedArmDecisionReason {
    RESOLVED_PAIRED_SCORE,
    FORWARD_AXIS_UNAVAILABLE,
    FORWARD_AXIS_UNRELIABLE,
    INSUFFICIENT_VALID_COVERAGE,
    INSUFFICIENT_ARM_SUPPORT,
    SCORE_WITHIN_UNCERTAINTY_BAND,
}

data class StraightPunchPairedArmConfig(
    val version: String = "straight-punch-paired-arm-v1",
    val minimumConfidence: Double = 0.55,
    val maximumGapUs: Long = 100_000,
    val minimumAxisCoverage: Double = 0.60,
    val maximumAxisDeviationDeg: Double = 45.0,
    val minimumFaceNormalSeparationMeters: Double = 0.01,
    val minimumValidCoverage: Double = 0.60,
    val minimumSupportedDurationUs: Long = 60_000,
    val minimumAbsoluteScoreMeters: Double = 0.03,
    val medianSamples: Int = 3,
    val meanSamples: Int = 3,
) {
    init {
        require(version.isNotBlank())
        require(minimumConfidence in 0.0..1.0)
        require(maximumGapUs > 0 && minimumSupportedDurationUs > 0)
        require(minimumAxisCoverage in 0.0..1.0 && minimumValidCoverage in 0.0..1.0)
        require(maximumAxisDeviationDeg in 0.0..90.0)
        require(minimumFaceNormalSeparationMeters > 0.0 && minimumFaceNormalSeparationMeters.isFinite())
        require(minimumAbsoluteScoreMeters > 0.0 && minimumAbsoluteScoreMeters.isFinite())
        require(medianSamples > 0 && meanSamples > 0)
    }
}

data class ForwardAxisEvidence(
    val status: ForwardAxisStatus,
    val axis: Point3?,
    val coverage: Double,
    val minimumAgreement: Double?,
    val reliableDurationUs: Long,
    val observedSamples: Int,
    val source: String?,
)

data class ArmPairedMotionEvidence(
    val signedNetTravelMeters: Double,
    val normalizedContributionMeters: Double,
    val supportedDurationUs: Long,
    val supportedCoverage: Double,
)

data class StraightPunchPairedArmResult(
    val movementId: String,
    val landmarkTrackId: String,
    val canonicalGeometry: CanonicalGeometryDescriptor,
    val logicalStartTimestampUs: Long,
    val logicalEndTimestampUs: Long,
    val config: StraightPunchPairedArmConfig,
    val analyzerVersion: String,
    val forwardAxis: ForwardAxisEvidence,
    val pairedScoreMeters: Double,
    val validDurationUs: Long,
    val validCoverage: Double,
    val supportMode: PairedArmSupportMode,
    val leftArm: ArmPairedMotionEvidence,
    val rightArm: ArmPairedMotionEvidence,
    val side: LateralSide?,
    val decisionReason: PairedArmDecisionReason,
    val validIntervalCount: Int,
    val bilateralSupportedDurationUs: Long,
    val singleLeftSupportedDurationUs: Long,
    val singleRightSupportedDurationUs: Long,
)

/**
 * Straight-punch side evidence in hip-relative MediaPipe world coordinates.
 *
 * One anatomical torso normal is frozen for the logical movement interval. Landmark coordinates
 * then follow the shared causal trailing-three median/trailing-three mean filter. Missing evidence
 * resets that landmark's filter, so no velocity is imputed or bridged across gaps.
 */
object StraightPunchPairedArmAnalyzer {
    const val VERSION = "straight-punch-paired-arm-analyzer-v1"

    private val leftRoster = listOf(
        PoseLandmarkId.LEFT_ELBOW, PoseLandmarkId.LEFT_WRIST, PoseLandmarkId.LEFT_THUMB,
        PoseLandmarkId.LEFT_INDEX, PoseLandmarkId.LEFT_PINKY,
    )
    private val rightRoster = listOf(
        PoseLandmarkId.RIGHT_ELBOW, PoseLandmarkId.RIGHT_WRIST, PoseLandmarkId.RIGHT_THUMB,
        PoseLandmarkId.RIGHT_INDEX, PoseLandmarkId.RIGHT_PINKY,
    )

    fun analyze(
        movementId: String,
        landmarkTrackId: String,
        canonicalGeometry: CanonicalGeometryDescriptor,
        logicalStartTimestampUs: Long,
        logicalEndTimestampUs: Long,
        frames: List<PoseFrame>,
        config: StraightPunchPairedArmConfig = StraightPunchPairedArmConfig(),
    ): StraightPunchPairedArmResult {
        require(logicalStartTimestampUs < logicalEndTimestampUs)
        require(landmarkTrackId.isNotBlank() &&
            (canonicalGeometry.landmarkTrackId == null || canonicalGeometry.landmarkTrackId == landmarkTrackId))
        require(frames.zipWithNext().all { (a, b) -> a.timestampMs < b.timestampMs }) {
            "Pose frame timestamps must be strictly increasing"
        }
        val bounded = frames.filter { it.timestampMs * 1_000L in logicalStartTimestampUs..logicalEndTimestampUs }
        val axisEvidence = forwardAxis(bounded, logicalStartTimestampUs, logicalEndTimestampUs, config)
        val axis = axisEvidence.axis
        if (axis == null || axisEvidence.status != ForwardAxisStatus.RELIABLE) {
            return emptyResult(movementId, landmarkTrackId, canonicalGeometry,
                logicalStartTimestampUs, logicalEndTimestampUs, config, axisEvidence,
                if (axisEvidence.status == ForwardAxisStatus.UNAVAILABLE) PairedArmDecisionReason.FORWARD_AXIS_UNAVAILABLE
                else PairedArmDecisionReason.FORWARD_AXIS_UNRELIABLE)
        }

        data class Velocity(val value: Double, val weight: Double, val previousTimestampUs: Long)
        val filters = (leftRoster + rightRoster).associateWith {
            CausalCoordinateMotionFilter(3, config.medianSamples, config.meanSamples)
        }
        var pairedScore = 0.0
        var leftContribution = 0.0
        var rightContribution = 0.0
        var leftTravel = 0.0
        var rightTravel = 0.0
        var leftDuration = 0L
        var rightDuration = 0L
        var validDuration = 0L
        var validIntervals = 0
        var bilateralDuration = 0L
        var singleLeftDuration = 0L
        var singleRightDuration = 0L

        bounded.forEach { frame ->
            val timestampUs = frame.timestampMs * 1_000L
            val hip = hipCenter(frame, config.minimumConfidence)
            val velocities = mutableMapOf<PoseLandmarkId, Velocity>()
            for ((id, filter) in filters) {
                val sample = frame.landmarks[id]
                val point = sample?.worldPosition
                val confidence = listOfNotNull(sample?.confidence?.toDouble(), hip?.confidence).minOrNull()
                if (point == null || hip == null || sample.source != LandmarkSource.OBSERVED ||
                    confidence == null || !confidence.isFinite() || confidence !in config.minimumConfidence..1.0 ||
                    !point.isFinitePoint()) {
                    filter.reset()
                    continue
                }
                val relative = point - hip.point
                val coordinates = listOf(relative.x.toDouble(), relative.y.toDouble(), relative.z.toDouble())
                val output = filter.accept(timestampUs, coordinates, confidence)
                val previousTimestamp = output.previousTimestampUs ?: continue
                val dtUs = timestampUs - previousTimestamp
                if (dtUs <= 0 || dtUs > config.maximumGapUs) {
                    filter.reset()
                    filter.accept(timestampUs, coordinates, confidence)
                    continue
                }
                val previous = output.previousCoordinates ?: continue
                val weight = minOf(output.confidence, output.previousConfidence ?: 0.0)
                val displacement = Point3(
                    (output.coordinates[0] - previous[0]).toFloat(),
                    (output.coordinates[1] - previous[1]).toFloat(),
                    (output.coordinates[2] - previous[2]).toFloat(),
                )
                if (weight > 0.0) velocities[id] = Velocity(
                    requireNotNull(displacement.dot(axis)).toDouble() / (dtUs / 1_000_000.0), weight, previousTimestamp)
            }

            fun arm(roster: List<PoseLandmarkId>): Pair<Double, Double>? {
                val values = roster.mapNotNull { id -> velocities[id]?.takeIf { it.previousTimestampUs < timestampUs } }
                val weight = values.sumOf { it.weight }
                if (weight <= 0.0) return null
                val velocity = values.sumOf { it.value * it.weight } / weight
                val rosterConfidence = weight / roster.size
                return velocity to rosterConfidence
            }
            val left = arm(leftRoster)
            val right = arm(rightRoster)
            if (left == null && right == null) return@forEach
            val previousTimes = velocities.values.map { it.previousTimestampUs }.distinct()
            if (previousTimes.size != 1) return@forEach
            val dtUs = timestampUs - previousTimes.single()
            if (dtUs <= 0 || dtUs > config.maximumGapUs) return@forEach
            val dt = dtUs / 1_000_000.0
            val cLeft = left?.second ?: 0.0
            val cRight = right?.second ?: 0.0
            val denominator = cLeft + cRight
            if (denominator <= 0.0) return@forEach
            val leftTerm = -(cLeft * (left?.first ?: 0.0)) / denominator
            val rightTerm = (cRight * (right?.first ?: 0.0)) / denominator
            leftContribution += leftTerm * dt
            rightContribution += rightTerm * dt
            pairedScore += (leftTerm + rightTerm) * dt
            if (left != null) { leftTravel += left.first * dt; leftDuration += dtUs }
            if (right != null) { rightTravel += right.first * dt; rightDuration += dtUs }
            when {
                left != null && right != null -> bilateralDuration += dtUs
                left != null -> singleLeftDuration += dtUs
                right != null -> singleRightDuration += dtUs
            }
            validDuration += dtUs
            validIntervals++
        }

        val windowDuration = logicalEndTimestampUs - logicalStartTimestampUs
        val validCoverage = (validDuration.toDouble() / windowDuration).coerceIn(0.0, 1.0)
        val leftCoverage = (leftDuration.toDouble() / windowDuration).coerceIn(0.0, 1.0)
        val rightCoverage = (rightDuration.toDouble() / windowDuration).coerceIn(0.0, 1.0)
        val mode = when {
            bilateralDuration >= config.minimumSupportedDurationUs -> PairedArmSupportMode.BILATERAL
            leftDuration >= config.minimumSupportedDurationUs -> PairedArmSupportMode.SINGLE_LEFT
            rightDuration >= config.minimumSupportedDurationUs -> PairedArmSupportMode.SINGLE_RIGHT
            else -> PairedArmSupportMode.INSUFFICIENT
        }
        val reason = when {
            validCoverage < config.minimumValidCoverage -> PairedArmDecisionReason.INSUFFICIENT_VALID_COVERAGE
            mode == PairedArmSupportMode.INSUFFICIENT -> PairedArmDecisionReason.INSUFFICIENT_ARM_SUPPORT
            kotlin.math.abs(pairedScore) < config.minimumAbsoluteScoreMeters -> PairedArmDecisionReason.SCORE_WITHIN_UNCERTAINTY_BAND
            else -> PairedArmDecisionReason.RESOLVED_PAIRED_SCORE
        }
        val side = if (reason != PairedArmDecisionReason.RESOLVED_PAIRED_SCORE) null else
            if (pairedScore > 0.0) LateralSide.RIGHT else LateralSide.LEFT
        return StraightPunchPairedArmResult(
            movementId, landmarkTrackId, canonicalGeometry,
            logicalStartTimestampUs, logicalEndTimestampUs, config, VERSION, axisEvidence,
            pairedScore, validDuration, validCoverage, mode,
            ArmPairedMotionEvidence(leftTravel, leftContribution, leftDuration, leftCoverage),
            ArmPairedMotionEvidence(rightTravel, rightContribution, rightDuration, rightCoverage),
            side, reason, validIntervals, bilateralDuration, singleLeftDuration, singleRightDuration,
        )
    }

    private data class HipCenter(val point: Point3, val confidence: Double)

    private fun observedWorld(frame: PoseFrame, id: PoseLandmarkId, minimumConfidence: Double): Pair<Point3, Double>? {
        val sample = frame.landmarks[id] ?: return null
        val point = sample.worldPosition ?: return null
        val confidence = sample.confidence.toDouble()
        return if (sample.source == LandmarkSource.OBSERVED && confidence.isFinite() &&
            confidence in minimumConfidence..1.0 && point.isFinitePoint())
            point to sample.confidence.toDouble() else null
    }

    private fun hipCenter(frame: PoseFrame, minimumConfidence: Double): HipCenter? {
        val left = observedWorld(frame, PoseLandmarkId.LEFT_HIP, minimumConfidence) ?: return null
        val right = observedWorld(frame, PoseLandmarkId.RIGHT_HIP, minimumConfidence) ?: return null
        return HipCenter(requireNotNull(midpoint(left.first, right.first)), minOf(left.second, right.second))
    }

    private data class AxisSample(val timestampUs: Long, val axis: Point3, val confidence: Double, val source: String)

    private fun forwardAxis(frames: List<PoseFrame>, startUs: Long, endUs: Long,
                            config: StraightPunchPairedArmConfig): ForwardAxisEvidence {
        val timeline = frames.map { frame ->
            val ids = listOf(PoseLandmarkId.LEFT_SHOULDER, PoseLandmarkId.RIGHT_SHOULDER,
                PoseLandmarkId.LEFT_HIP, PoseLandmarkId.RIGHT_HIP)
            val points = ids.map { observedWorld(frame, it, config.minimumConfidence) }
            if (points.any { it == null }) return@map null
            val values = points.map { requireNotNull(it) }
            val leftSide = requireNotNull(midpoint(values[0].first, values[2].first))
            val rightSide = requireNotNull(midpoint(values[1].first, values[3].first))
            val shoulder = requireNotNull(midpoint(values[0].first, values[1].first))
            val hip = requireNotNull(midpoint(values[2].first, values[3].first))
            val unsignedNormal = (rightSide - leftSide).cross(shoulder - hip)?.normalized() ?: return@map null
            val faceCandidates = listOf(
                "NOSE" to listOf(PoseLandmarkId.NOSE),
                "MOUTH_MIDPOINT" to listOf(PoseLandmarkId.MOUTH_LEFT, PoseLandmarkId.MOUTH_RIGHT),
            )
            val face = faceCandidates.firstNotNullOfOrNull { (source, candidateIds) ->
                val candidate = candidateIds.map { observedWorld(frame, it, config.minimumConfidence) }
                if (candidate.any { it == null }) null else Triple(source,
                    requireNotNull(averageOfPoints(candidate.map { requireNotNull(it).first })),
                    candidate.minOf { requireNotNull(it).second })
            } ?: return@map null
            val torsoCenter = requireNotNull(midpoint(shoulder, hip))
            val faceNormal = requireNotNull((face.second - torsoCenter).dot(unsignedNormal)).toDouble()
            if (kotlin.math.abs(faceNormal) < config.minimumFaceNormalSeparationMeters) return@map null
            val signedNormal = if (faceNormal > 0.0) unsignedNormal else unsignedNormal * -1f
            AxisSample(frame.timestampMs * 1_000L, signedNormal, minOf(values.minOf { it.second }, face.third), face.first)
        }
        val samples = timeline.filterNotNull()
        if (samples.size < 2) return ForwardAxisEvidence(ForwardAxisStatus.UNAVAILABLE, null, 0.0, null, 0, samples.size, null)
        val sx = samples.sumOf { it.axis.x.toDouble() * it.confidence }
        val sy = samples.sumOf { it.axis.y.toDouble() * it.confidence }
        val sz = samples.sumOf { it.axis.z.toDouble() * it.confidence }
        val aggregate = Point3(sx.toFloat(), sy.toFloat(), sz.toFloat()).normalized()
            ?: return ForwardAxisEvidence(ForwardAxisStatus.UNRELIABLE, null, 0.0, null, 0, samples.size,
                samples.map { it.source }.distinct().sorted().joinToString("+"))
        val agreements = samples.map { requireNotNull(it.axis.dot(aggregate)).toDouble() }
        val reliableDuration = frames.zipWithNext().zip(timeline.zipWithNext()).sumOf { (source, axes) ->
            val (a, b) = axes
            if (a == null || b == null) 0L else
                ((source.second.timestampMs - source.first.timestampMs) * 1_000L)
                    .takeIf { it in 1..config.maximumGapUs } ?: 0L
        }
        val coverage = (reliableDuration.toDouble() / (endUs - startUs)).coerceIn(0.0, 1.0)
        val minimumAgreement = agreements.minOrNull()
        val reliable = coverage >= config.minimumAxisCoverage &&
            minimumAgreement != null && minimumAgreement >= cos(Math.toRadians(config.maximumAxisDeviationDeg))
        return ForwardAxisEvidence(if (reliable) ForwardAxisStatus.RELIABLE else ForwardAxisStatus.UNRELIABLE,
            aggregate, coverage, minimumAgreement, reliableDuration, samples.size,
            samples.map { it.source }.distinct().sorted().joinToString("+"))
    }

    private fun emptyResult(movementId: String, landmarkTrackId: String,
                            canonicalGeometry: CanonicalGeometryDescriptor, startUs: Long, endUs: Long,
                            config: StraightPunchPairedArmConfig, axis: ForwardAxisEvidence,
                            reason: PairedArmDecisionReason): StraightPunchPairedArmResult {
        val arm = ArmPairedMotionEvidence(0.0, 0.0, 0, 0.0)
        return StraightPunchPairedArmResult(movementId, landmarkTrackId, canonicalGeometry,
            startUs, endUs, config, VERSION, axis, 0.0, 0, 0.0,
            PairedArmSupportMode.INSUFFICIENT, arm, arm, null, reason, 0, 0, 0, 0)
    }
}
