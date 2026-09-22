package dk.lasse.karateanalyzer.geometry

import kotlin.math.sqrt

/**
 * Single timestamped spatial point along an observed trajectory.
 */
data class TrajectorySample(
    val timestampUs: Long,
    val position: UpwardMetricPoint,
    val isValid: Boolean = true,
)

/**
 * Outcome of a displacement calculation.
 */
data class DisplacementResult(
    val vector: UpwardMetricPoint,
    val magnitude: Float,
    val startTimestampUs: Long,
    val endTimestampUs: Long,
)

/**
 * Outcome of travel distance evaluation along consecutive valid trajectory edges.
 */
data class TravelDistanceResult(
    val totalDistance: Float,
    val validEdgeCount: Int,
    val gapCount: Int,
    val state: MeasurementResultState,
    val observedSpans: List<Pair<Long, Long>>,
)

/**
 * Path deviation results against an explicit reference line.
 */
data class PathDeviationResult(
    val sampleRms: Float,
    val maxDeviation: Float,
    val maxDeviationTimestampUs: Long,
    val sampleCount: Int,
)

/**
 * Instantaneous velocity and speed over an interval [startTimestampUs, endTimestampUs]
 * calculated using backward finite differences.
 */
data class FiniteDifferenceVelocity(
    val startTimestampUs: Long,
    val endTimestampUs: Long,
    val velocityVector: UpwardMetricPoint,
    val speed: Float,
    val deltaSeconds: Float,
)

/**
 * Pure motion operations through an interval: displacement, travel distance,
 * path deviation, and backward finite difference velocity.
 */
object LandmarkTrajectories {

    /**
     * Vector displacement and magnitude between start and end samples.
     */
    fun displacementOf(
        start: TrajectorySample,
        end: TrajectorySample,
    ): DisplacementResult {
        val vec = end.position - start.position
        return DisplacementResult(
            vector = vec,
            magnitude = vec.length(),
            startTimestampUs = start.timestampUs,
            endTimestampUs = end.timestampUs,
        )
    }

    /**
     * Relative displacement of subject with respect to reference in a common fixed basis:
     * [Subject(end) - Reference(end)] - [Subject(start) - Reference(start)]
     * Cancels shared translation (e.g. torso motion during hand movement).
     */
    fun relativeDisplacementOf(
        subjectStart: TrajectorySample,
        subjectEnd: TrajectorySample,
        referenceStart: TrajectorySample,
        referenceEnd: TrajectorySample,
    ): DisplacementResult {
        val startRel = subjectStart.position - referenceStart.position
        val endRel = subjectEnd.position - referenceEnd.position
        val vec = endRel - startRel
        return DisplacementResult(
            vector = vec,
            magnitude = vec.length(),
            startTimestampUs = subjectStart.timestampUs,
            endTimestampUs = subjectEnd.timestampUs,
        )
    }

    /**
     * Evaluates travel distance by summing consecutive valid trajectory edge lengths.
     * Does NOT bridge gaps exceeding [maxGapUs] or invalid samples.
     * Returns PARTIAL if any valid edges are disconnected by gaps.
     */
    fun travelDistanceOf(
        samples: List<TrajectorySample>,
        maxGapUs: Long = 100_000L,
    ): TravelDistanceResult {
        if (samples.size < 2) {
            return TravelDistanceResult(
                totalDistance = 0f,
                validEdgeCount = 0,
                gapCount = 0,
                state = if (samples.isEmpty()) MeasurementResultState.UNAVAILABLE else MeasurementResultState.AVAILABLE,
                observedSpans = emptyList(),
            )
        }

        var totalDist = 0f
        var edgeCount = 0
        var gapCount = 0
        val spans = mutableListOf<Pair<Long, Long>>()
        var currentSpanStart: Long? = null
        var lastValidSample: TrajectorySample? = null

        for (sample in samples) {
            if (!sample.isValid) {
                if (currentSpanStart != null && lastValidSample != null && lastValidSample.timestampUs > currentSpanStart) {
                    spans.add(Pair(currentSpanStart, lastValidSample.timestampUs))
                }
                currentSpanStart = null
                lastValidSample = null
                gapCount++
                continue
            }

            if (lastValidSample == null) {
                currentSpanStart = sample.timestampUs
                lastValidSample = sample
            } else {
                val dt = sample.timestampUs - lastValidSample.timestampUs
                if (dt > maxGapUs) {
                    gapCount++
                    if (currentSpanStart != null && lastValidSample.timestampUs > currentSpanStart) {
                        spans.add(Pair(currentSpanStart, lastValidSample.timestampUs))
                    }
                    currentSpanStart = sample.timestampUs
                } else {
                    val d = LandmarkRelations.distance(lastValidSample.position, sample.position)
                    totalDist += d
                    edgeCount++
                }
                lastValidSample = sample
            }
        }

        if (currentSpanStart != null && lastValidSample != null && lastValidSample.timestampUs > currentSpanStart) {
            spans.add(Pair(currentSpanStart, lastValidSample.timestampUs))
        }

        val state = when {
            edgeCount == 0 -> MeasurementResultState.UNAVAILABLE
            gapCount > 0 -> MeasurementResultState.PARTIAL
            else -> MeasurementResultState.AVAILABLE
        }

        return TravelDistanceResult(
            totalDistance = totalDist,
            validEdgeCount = edgeCount,
            gapCount = gapCount,
            state = state,
            observedSpans = spans,
        )
    }

    /**
     * Evaluates path deviation (sample RMS and maximum deviation) of trajectory samples
     * against an explicit directed reference line.
     * Earliest timestamp tie-breaking policy is applied for equal maximum deviations.
     */
    fun pathDeviationOf(
        samples: List<TrajectorySample>,
        referenceLine: DirectedLandmarkLine,
    ): PathDeviationResult? {
        if (referenceLine.isDegenerate) return null
        val validSamples = samples.filter { it.isValid }
        if (validSamples.isEmpty()) return null

        var sumSq = 0.0
        var maxDev = -1f
        var maxDevTs = validSamples.first().timestampUs

        for (sample in validSamples) {
            val signedDist = LandmarkRelations.signedDistanceToLine(sample.position, referenceLine) ?: return null
            val dist = kotlin.math.abs(signedDist)
            sumSq += (dist * dist).toDouble()

            // Deterministic earliest-timestamp tie-breaking rule
            if (dist > maxDev + 1e-6f) {
                maxDev = dist
                maxDevTs = sample.timestampUs
            }
        }

        val n = validSamples.size
        val rms = sqrt(sumSq / n).toFloat()

        return PathDeviationResult(
            sampleRms = rms,
            maxDeviation = maxDev,
            maxDeviationTimestampUs = maxDevTs,
            sampleCount = n,
        )
    }

    /**
     * Backward finite difference velocities between adjacent valid samples:
     * v = (P_i - P_{i-1}) / (t_i - t_{i-1})
     */
    fun velocityAndSpeedOf(
        samples: List<TrajectorySample>,
        maxGapUs: Long = 100_000L,
    ): List<FiniteDifferenceVelocity> {
        val results = mutableListOf<FiniteDifferenceVelocity>()
        if (samples.size < 2) return results

        for (i in 1 until samples.size) {
            val prev = samples[i - 1]
            val curr = samples[i]
            if (!prev.isValid || !curr.isValid) continue

            val dtUs = curr.timestampUs - prev.timestampUs
            if (dtUs <= 0 || dtUs > maxGapUs) continue

            val dtSec = dtUs.toFloat() / 1_000_000f
            val dp = curr.position - prev.position
            val vel = dp * (1f / dtSec)
            results.add(
                FiniteDifferenceVelocity(
                    startTimestampUs = prev.timestampUs,
                    endTimestampUs = curr.timestampUs,
                    velocityVector = vel,
                    speed = vel.length(),
                    deltaSeconds = dtSec,
                )
            )
        }

        return results
    }
}
