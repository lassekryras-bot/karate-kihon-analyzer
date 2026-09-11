package dk.lasse.karateanalyzer.capture

import dk.lasse.karateanalyzer.core.LandmarkSource
import dk.lasse.karateanalyzer.core.Point3
import dk.lasse.karateanalyzer.core.PoseFrame
import dk.lasse.karateanalyzer.core.PoseLandmarkId
import dk.lasse.karateanalyzer.core.PoseLandmarkSample
import dk.lasse.karateanalyzer.observation.NonIncreasingTimestampException
import dk.lasse.karateanalyzer.observation.TimestampedHistory
import kotlin.math.sqrt

enum class AnatomicalRegion { HEAD, TORSO, LEFT_ARM, RIGHT_ARM, LEFT_LEG, RIGHT_LEG }

data class PoseMotionExtractorConfig(
    val minimumLandmarkConfidence: Double = 0.50,
    val maximumFrameGapMs: Long = 500L,
    val maximumNormalizedSpeed: Double = 8.0,
    val minimumWorldTorsoLength: Double = 0.05,
    val minimumImageTorsoLength: Double = 0.03,
    val slowDisplacementWindowMs: Long = 600L,
    val baselineRequiredSamples: Int = 5,
    val baselineMinimumCoverage: Double = 0.75,
    val baselineMaximumArticulatedMotion: Double = 0.12,
    val minimumRegionCoverageForReliableMotion: Double = 0.65,
    val regionWeights: Map<AnatomicalRegion, Double> = AnatomicalRegion.entries.associateWith { 1.0 },
) {
    init {
        require(minimumLandmarkConfidence in 0.0..1.0)
        require(maximumFrameGapMs > 0L && slowDisplacementWindowMs > 0L)
        require(maximumNormalizedSpeed.isFinite() && maximumNormalizedSpeed > 0.0)
        require(minimumWorldTorsoLength.isFinite() && minimumWorldTorsoLength > 0.0)
        require(minimumImageTorsoLength.isFinite() && minimumImageTorsoLength > 0.0)
        require(baselineRequiredSamples > 1)
        require(baselineMinimumCoverage in 0.0..1.0)
        require(minimumRegionCoverageForReliableMotion in 0.0..1.0)
        require(baselineMaximumArticulatedMotion.isFinite() && baselineMaximumArticulatedMotion >= 0.0)
        require(regionWeights.keys.containsAll(AnatomicalRegion.entries))
        require(AnatomicalRegion.entries.all { region ->
            regionWeights.getValue(region).let { weight -> weight.isFinite() && weight >= 0.0 }
        })
        require(regionWeights.values.any { it > 0.0 })
    }
}

data class PoseSequenceLabels(
    val movementStartTimestampMs: Long? = null,
    val movementEndTimestampMs: Long? = null,
    val terminalStableInterval: ReplayInterval? = null,
    val intermediateStablePlateaus: List<ReplayInterval> = emptyList(),
    val trackingLossIntervals: List<ReplayInterval> = emptyList(),
    val ambiguousIntervals: List<ReplayInterval> = emptyList(),
    val anticipatoryMovement: Boolean = false,
)

data class ReplayInterval(val startTimestampMs: Long, val endTimestampMs: Long) {
    init { require(endTimestampMs >= startTimestampMs) }
    operator fun contains(timestampMs: Long) = timestampMs in startTimestampMs..endTimestampMs
}

data class PoseReplayFixture(
    val schemaVersion: String = SCHEMA_VERSION,
    val sequenceId: String = "unnamed",
    val frames: List<PoseFrame>,
    val armTimestampMs: Long? = null,
    val cueTimestampMs: Long? = null,
    val activityDeadlineMs: Long? = null,
    val expectedEndPoseRelationship: EndPoseRelationship = EndPoseRelationship.SAME_AS_START,
    val labels: PoseSequenceLabels? = null,
    val notes: List<String> = emptyList(),
) {
    init {
        require(schemaVersion == SCHEMA_VERSION)
    }

    companion object { const val SCHEMA_VERSION = "pose-motion-replay-v1" }
}

private data class RelativePose(
    val timestampMs: Long,
    val points: Map<PoseLandmarkId, Point3>,
    val confidences: Map<PoseLandmarkId, Double>,
    val worldScale: Double,
)

private data class PoseReference(
    val points: Map<PoseLandmarkId, Point3>,
    val lateralAxis: Point3,
)

/**
 * Deterministic, capture-only extraction from repository-native pose frames.
 *
 * Work is O(number of selected landmarks) per frame. The bounded slow-motion lookup is O(history
 * capacity); with the default history size this remains negligible beside pose inference.
 */
class PoseMotionObservationExtractor(
    private val config: PoseMotionExtractorConfig = PoseMotionExtractorConfig(),
) {
    private var previousFrame: PoseFrame? = null
    private var previousRelativePose: RelativePose? = null
    private val relativeHistory = TimestampedHistory<RelativePose>(120, config.slowDisplacementWindowMs)
    private val baselineCandidates = mutableListOf<RelativePose>()
    private var baselineReference: PoseReference? = null

    fun accept(frame: PoseFrame): MotionObservation {
        val previous = previousFrame
        if (previous != null && frame.timestampMs <= previous.timestampMs) {
            throw NonIncreasingTimestampException(previous.timestampMs, frame.timestampMs)
        }

        val relative = relativePose(frame)
        val elapsedMs = previous?.let { frame.timestampMs - it.timestampMs }
        val gap = elapsedMs != null && elapsedMs > config.maximumFrameGapMs
        if (gap) relativeHistory.reset()

        val regionDiagnostics = linkedMapOf<AnatomicalRegion, RegionMotionDiagnostics>()
        val articulated = if (previous == null) {
            emptyRegionDiagnostics(regionDiagnostics)
            ChannelValue(null, MotionChannelStatus.FIRST_FRAME)
        } else if (gap) {
            emptyRegionDiagnostics(regionDiagnostics)
            ChannelValue(null, MotionChannelStatus.TIMESTAMP_GAP)
        } else if (relative == null || previousRelativePose == null) {
            emptyRegionDiagnostics(regionDiagnostics)
            ChannelValue(null, MotionChannelStatus.INVALID_BODY_SCALE)
        } else {
            articulatedMotion(previousRelativePose!!, relative, checkNotNull(elapsedMs), regionDiagnostics)
        }

        val imageTranslation = when {
            previous == null -> ImageChannel(null, null, null, null, MotionChannelStatus.FIRST_FRAME)
            gap -> ImageChannel(null, null, null, null, MotionChannelStatus.TIMESTAMP_GAP)
            else -> imageTranslation(previous, frame, checkNotNull(elapsedMs))
        }
        val coverage = currentCoverage(frame)
        updateBaseline(relative, articulated.value, coverage.minimum, gap)
        if (relative != null) relativeHistory.add(frame.timestampMs, relative)
        val displacement = relative?.let(::slowDisplacement)
        val reference = baselineReference
        val sameSimilarity = if (relative != null && reference != null) poseSimilarity(relative, reference, mirrored = false) else null
        val mirroredSimilarity = if (relative != null && reference != null) poseSimilarity(relative, reference, mirrored = true) else null

        previousFrame = frame
        previousRelativePose = relative
        return MotionObservation(
            timestampMs = frame.timestampMs,
            articulatedMotion = articulated.value,
            imageSpaceMotion = imageTranslation.value,
            coverage = coverage.minimum,
            sameAsStartSimilarity = sameSimilarity,
            mirroredStartSimilarity = mirroredSimilarity,
            accumulatedDisplacement = displacement,
            diagnostics = MotionObservationDiagnostics(
                articulatedStatus = articulated.status,
                imageSpaceStatus = imageTranslation.status,
                elapsedMs = elapsedMs,
                bodyScaleWorld = relative?.worldScale,
                bodyScaleImage = imageTranslation.scale,
                imageCenterTranslation = imageTranslation.centerTranslation,
                imageScaleChange = imageTranslation.scaleChange,
                minimumRegionCoverage = coverage.minimum,
                regionBalancedCoverage = coverage.balanced,
                baselineSampleCount = baselineCandidates.size,
                baselineReady = baselineReference != null,
                regions = regionDiagnostics,
            ),
        )
    }

    fun replay(fixture: PoseReplayFixture): List<MotionObservation> {
        reset()
        return fixture.frames.map(::accept)
    }

    fun reset() {
        previousFrame = null
        previousRelativePose = null
        relativeHistory.reset()
        baselineCandidates.clear()
        baselineReference = null
    }

    private fun relativePose(frame: PoseFrame): RelativePose? {
        val leftHip = frame.usable(PoseLandmarkId.LEFT_HIP, world = true) ?: return null
        val rightHip = frame.usable(PoseLandmarkId.RIGHT_HIP, world = true) ?: return null
        val leftShoulder = frame.usable(PoseLandmarkId.LEFT_SHOULDER, world = true) ?: return null
        val rightShoulder = frame.usable(PoseLandmarkId.RIGHT_SHOULDER, world = true) ?: return null
        val hipCenter = midpoint(leftHip.first, rightHip.first)
        val shoulderCenter = midpoint(leftShoulder.first, rightShoulder.first)
        val scale = distance(hipCenter, shoulderCenter)
        if (!scale.isFinite() || scale < config.minimumWorldTorsoLength) return null

        val points = mutableMapOf<PoseLandmarkId, Point3>()
        val confidences = mutableMapOf<PoseLandmarkId, Double>()
        selectedLandmarks.forEach { id ->
            frame.usable(id, world = true)?.let { (point, confidence) ->
                points[id] = (point - hipCenter) * (1f / scale.toFloat())
                confidences[id] = confidence
            }
        }
        return RelativePose(frame.timestampMs, points, confidences, scale)
    }

    private fun articulatedMotion(
        previous: RelativePose,
        current: RelativePose,
        elapsedMs: Long,
        output: MutableMap<AnatomicalRegion, RegionMotionDiagnostics>,
    ): ChannelValue {
        val seconds = elapsedMs / 1_000.0
        val validRegions = mutableListOf<Pair<Double, Double>>()
        var allRegionsReliable = true
        anatomicalLandmarks.forEach { (region, ids) ->
            val rawSpeeds = mutableListOf<Pair<Double, Double>>()
            ids.forEach { id ->
                val first = previous.points[id]
                val second = current.points[id]
                if (first != null && second != null) {
                    val quality = minOf(previous.confidences[id] ?: 0.0, current.confidences[id] ?: 0.0)
                    rawSpeeds += distance(first, second) / seconds to quality
                }
            }
            val raw = weightedRms(rawSpeeds)
            val robustSamples = rawSpeeds.map { (speed, quality) -> minOf(speed, config.maximumNormalizedSpeed) to quality }
            val robust = weightedRms(robustSamples)
            val coverage = rawSpeeds.sumOf { it.second } / ids.size
            val clamped = rawSpeeds.count { it.first > config.maximumNormalizedSpeed }
            output[region] = RegionMotionDiagnostics(ids.size, rawSpeeds.size, coverage.coerceIn(0.0, 1.0), raw, robust, clamped)
            if (coverage < config.minimumRegionCoverageForReliableMotion) allRegionsReliable = false
            if (robust != null) validRegions += robust to checkNotNull(config.regionWeights[region])
        }
        val aggregate = weightedRms(validRegions)
        val status = if (aggregate == null || !allRegionsReliable) {
            MotionChannelStatus.INSUFFICIENT_COVERAGE
        } else {
            MotionChannelStatus.VALID
        }
        return ChannelValue(aggregate, status)
    }

    private fun imageTranslation(previous: PoseFrame, current: PoseFrame, elapsedMs: Long): ImageChannel {
        val previousGeometry = imageTorsoGeometry(previous) ?: return ImageChannel(null, null, null, null, MotionChannelStatus.INVALID_BODY_SCALE)
        val currentGeometry = imageTorsoGeometry(current) ?: return ImageChannel(null, null, null, null, MotionChannelStatus.INVALID_BODY_SCALE)
        val scale = (previousGeometry.second + currentGeometry.second) * 0.5
        if (scale < config.minimumImageTorsoLength) return ImageChannel(null, scale, null, null, MotionChannelStatus.INVALID_BODY_SCALE)
        val seconds = elapsedMs / 1_000.0
        val translation = distance2d(previousGeometry.first, currentGeometry.first) / scale / seconds
        val scaleChange = kotlin.math.abs(currentGeometry.second - previousGeometry.second) / scale / seconds
        return ImageChannel(
            value = sqrt(translation * translation + scaleChange * scaleChange),
            scale = scale,
            centerTranslation = translation,
            scaleChange = scaleChange,
            status = MotionChannelStatus.VALID,
        )
    }

    private fun imageTorsoGeometry(frame: PoseFrame): Pair<Point3, Double>? {
        val points = torsoLandmarks.mapNotNull { frame.usable(it, world = false)?.first }
        if (points.size < 3) return null
        val center = Point3(points.map { it.x }.average().toFloat(), points.map { it.y }.average().toFloat(), 0f)
        val shoulders = listOfNotNull(
            frame.usable(PoseLandmarkId.LEFT_SHOULDER, false)?.first,
            frame.usable(PoseLandmarkId.RIGHT_SHOULDER, false)?.first,
        )
        val hips = listOfNotNull(
            frame.usable(PoseLandmarkId.LEFT_HIP, false)?.first,
            frame.usable(PoseLandmarkId.RIGHT_HIP, false)?.first,
        )
        if (shoulders.isEmpty() || hips.isEmpty()) return null
        return center to distance2d(average(shoulders), average(hips))
    }

    private fun currentCoverage(frame: PoseFrame): CoverageSummary {
        // A region-balanced minimum prevents dense, well-tracked regions from hiding loss of a
        // sparse limb. A strong available motion channel can still start movement, while this low
        // coverage prevents the segmenter from treating the frame as proof of stillness.
        val regions = anatomicalLandmarks.map { (region, ids) ->
            val value = ids.sumOf { frame.usable(it, world = false)?.second ?: 0.0 } / ids.size
            value to config.regionWeights.getValue(region)
        }
        val totalWeight = regions.sumOf { it.second }
        return CoverageSummary(
            minimum = regions.minOfOrNull { it.first } ?: 0.0,
            balanced = if (totalWeight > 0.0) regions.sumOf { it.first * it.second } / totalWeight else 0.0,
        )
    }

    private fun updateBaseline(relative: RelativePose?, motion: Double?, coverage: Double, gap: Boolean) {
        if (baselineReference != null) return
        val trustworthy = relative != null && coverage >= config.baselineMinimumCoverage &&
            (motion == null && previousFrame == null || motion != null && motion <= config.baselineMaximumArticulatedMotion) && !gap
        if (!trustworthy) {
            baselineCandidates.clear()
            return
        }
        baselineCandidates += relative!!
        if (baselineCandidates.size >= config.baselineRequiredSamples) {
            val points = selectedLandmarks.mapNotNull { id ->
                val samples = baselineCandidates.mapNotNull { it.points[id] }
                if (samples.size == baselineCandidates.size) id to average(samples) else null
            }.toMap()
            val leftHip = points[PoseLandmarkId.LEFT_HIP]
            val rightHip = points[PoseLandmarkId.RIGHT_HIP]
            if (leftHip != null && rightHip != null) {
                baselineReference = PoseReference(points, unit(leftHip - rightHip))
            }
        }
    }

    private fun poseSimilarity(current: RelativePose, reference: PoseReference, mirrored: Boolean): Double? {
        val distances = selectedLandmarks.mapNotNull { currentId ->
            val referenceId = if (mirrored) mirroredLandmark[currentId] ?: currentId else currentId
            val first = current.points[currentId]
            val rawReference = reference.points[referenceId]
            val second = if (mirrored && rawReference != null) reflectAcrossSagittalPlane(rawReference, reference.lateralAxis) else rawReference
            if (first != null && second != null) distance(first, second) else null
        }
        if (distances.size < selectedLandmarks.size * config.baselineMinimumCoverage) return null
        val rms = sqrt(distances.sumOf { it * it } / distances.size)
        return (1.0 / (1.0 + rms)).coerceIn(0.0, 1.0)
    }

    private fun slowDisplacement(current: RelativePose): Double? {
        val oldest = relativeHistory.values()
            .firstOrNull { current.timestampMs - it.timestampMs <= config.slowDisplacementWindowMs }
            ?.value ?: return null
        return poseDistance(oldest, current)
    }

    private fun poseDistance(first: RelativePose, second: RelativePose): Double? {
        val values = selectedLandmarks.mapNotNull { id ->
            val a = first.points[id]
            val b = second.points[id]
            if (a != null && b != null) distance(a, b) else null
        }
        if (values.size < selectedLandmarks.size * config.baselineMinimumCoverage) return null
        return sqrt(values.sumOf { it * it } / values.size)
    }

    private fun PoseFrame.usable(id: PoseLandmarkId, world: Boolean): Pair<Point3, Double>? {
        val sample: PoseLandmarkSample = landmarks[id] ?: return null
        if (sample.source != LandmarkSource.OBSERVED || !sample.confidence.isFinite() || sample.confidence < config.minimumLandmarkConfidence) return null
        val point = if (world) sample.worldPosition else sample.position
        if (point == null || !point.x.isFinite() || !point.y.isFinite() || !point.z.isFinite()) return null
        return point to sample.confidence.toDouble()
    }

    private fun emptyRegionDiagnostics(output: MutableMap<AnatomicalRegion, RegionMotionDiagnostics>) {
        anatomicalLandmarks.forEach { (region, ids) ->
            output[region] = RegionMotionDiagnostics(ids.size, 0, 0.0, null, null, 0)
        }
    }

    private data class ChannelValue(val value: Double?, val status: MotionChannelStatus)
    private data class ImageChannel(
        val value: Double?,
        val scale: Double?,
        val centerTranslation: Double?,
        val scaleChange: Double?,
        val status: MotionChannelStatus,
    )
    private data class CoverageSummary(val minimum: Double, val balanced: Double)
}

private fun weightedRms(values: List<Pair<Double, Double>>): Double? {
    val weight = values.sumOf { it.second }
    if (weight <= 0.0) return null
    return sqrt(values.sumOf { (value, quality) -> value * value * quality } / weight)
}

private fun midpoint(first: Point3, second: Point3) = Point3(
    (first.x + second.x) * 0.5f,
    (first.y + second.y) * 0.5f,
    (first.z + second.z) * 0.5f,
)

private fun average(points: List<Point3>) = Point3(
    points.map { it.x }.average().toFloat(),
    points.map { it.y }.average().toFloat(),
    points.map { it.z }.average().toFloat(),
)

private fun distance(first: Point3, second: Point3): Double {
    val dx = (first.x - second.x).toDouble()
    val dy = (first.y - second.y).toDouble()
    val dz = (first.z - second.z).toDouble()
    return sqrt(dx * dx + dy * dy + dz * dz)
}

private fun distance2d(first: Point3, second: Point3): Double {
    val dx = (first.x - second.x).toDouble()
    val dy = (first.y - second.y).toDouble()
    return sqrt(dx * dx + dy * dy)
}

private fun unit(point: Point3): Point3 {
    val length = sqrt((point.x * point.x + point.y * point.y + point.z * point.z).toDouble()).toFloat()
    return if (length > 0f) point * (1f / length) else Point3(1f, 0f, 0f)
}

private fun reflectAcrossSagittalPlane(point: Point3, lateralAxis: Point3): Point3 {
    val projection = point.x * lateralAxis.x + point.y * lateralAxis.y + point.z * lateralAxis.z
    return point - lateralAxis * (2f * projection)
}

private val anatomicalLandmarks = linkedMapOf(
    AnatomicalRegion.HEAD to listOf(PoseLandmarkId.NOSE, PoseLandmarkId.LEFT_EAR, PoseLandmarkId.RIGHT_EAR),
    AnatomicalRegion.TORSO to listOf(PoseLandmarkId.LEFT_SHOULDER, PoseLandmarkId.RIGHT_SHOULDER, PoseLandmarkId.LEFT_HIP, PoseLandmarkId.RIGHT_HIP),
    AnatomicalRegion.LEFT_ARM to listOf(PoseLandmarkId.LEFT_SHOULDER, PoseLandmarkId.LEFT_ELBOW, PoseLandmarkId.LEFT_WRIST),
    AnatomicalRegion.RIGHT_ARM to listOf(PoseLandmarkId.RIGHT_SHOULDER, PoseLandmarkId.RIGHT_ELBOW, PoseLandmarkId.RIGHT_WRIST),
    AnatomicalRegion.LEFT_LEG to listOf(PoseLandmarkId.LEFT_HIP, PoseLandmarkId.LEFT_KNEE, PoseLandmarkId.LEFT_ANKLE),
    AnatomicalRegion.RIGHT_LEG to listOf(PoseLandmarkId.RIGHT_HIP, PoseLandmarkId.RIGHT_KNEE, PoseLandmarkId.RIGHT_ANKLE),
)
private val selectedLandmarks = anatomicalLandmarks.values.flatten().distinct()
private val torsoLandmarks = anatomicalLandmarks.getValue(AnatomicalRegion.TORSO)
private val mirroredLandmark = mapOf(
    PoseLandmarkId.LEFT_EAR to PoseLandmarkId.RIGHT_EAR, PoseLandmarkId.RIGHT_EAR to PoseLandmarkId.LEFT_EAR,
    PoseLandmarkId.LEFT_SHOULDER to PoseLandmarkId.RIGHT_SHOULDER, PoseLandmarkId.RIGHT_SHOULDER to PoseLandmarkId.LEFT_SHOULDER,
    PoseLandmarkId.LEFT_ELBOW to PoseLandmarkId.RIGHT_ELBOW, PoseLandmarkId.RIGHT_ELBOW to PoseLandmarkId.LEFT_ELBOW,
    PoseLandmarkId.LEFT_WRIST to PoseLandmarkId.RIGHT_WRIST, PoseLandmarkId.RIGHT_WRIST to PoseLandmarkId.LEFT_WRIST,
    PoseLandmarkId.LEFT_HIP to PoseLandmarkId.RIGHT_HIP, PoseLandmarkId.RIGHT_HIP to PoseLandmarkId.LEFT_HIP,
    PoseLandmarkId.LEFT_KNEE to PoseLandmarkId.RIGHT_KNEE, PoseLandmarkId.RIGHT_KNEE to PoseLandmarkId.LEFT_KNEE,
    PoseLandmarkId.LEFT_ANKLE to PoseLandmarkId.RIGHT_ANKLE, PoseLandmarkId.RIGHT_ANKLE to PoseLandmarkId.LEFT_ANKLE,
)
