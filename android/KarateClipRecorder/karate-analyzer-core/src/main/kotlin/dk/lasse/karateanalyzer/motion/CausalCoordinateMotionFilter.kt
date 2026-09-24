package dk.lasse.karateanalyzer.motion

import kotlin.math.sqrt

/**
 * Reusable causal position filter used by motion analyzers.
 *
 * Each coordinate follows the production QoM sequence: trailing median, then trailing arithmetic
 * mean. Timestamps are retained so consumers can derive rates from observed time rather than FPS.
 */
class CausalCoordinateMotionFilter(
    private val coordinateCount: Int,
    private val medianSamples: Int = 3,
    private val meanSamples: Int = 3,
) {
    data class Output(
        val timestampUs: Long,
        val coordinates: List<Double>,
        val confidence: Double,
        val previousTimestampUs: Long?,
        val previousCoordinates: List<Double>?,
        val previousConfidence: Double?,
    ) {
        val deltaSeconds: Double?
            get() = previousTimestampUs?.let { (timestampUs - it) / 1_000_000.0 }

        val speed: Double?
            get() {
                val previous = previousCoordinates ?: return null
                val dt = deltaSeconds?.takeIf { it > 0.0 } ?: return null
                return sqrt(coordinates.indices.sumOf { index ->
                    val delta = coordinates[index] - previous[index]
                    delta * delta
                }) / dt
            }
    }

    private val rawCoordinates = ArrayDeque<List<Double>>()
    private val rawConfidences = ArrayDeque<Double>()
    private val medianCoordinates = ArrayDeque<List<Double>>()
    private val medianConfidences = ArrayDeque<Double>()
    private var current: Output? = null

    init {
        require(coordinateCount > 0) { "coordinateCount must be positive" }
        require(medianSamples > 0) { "medianSamples must be positive" }
        require(meanSamples > 0) { "meanSamples must be positive" }
    }

    fun reset() {
        rawCoordinates.clear()
        rawConfidences.clear()
        medianCoordinates.clear()
        medianConfidences.clear()
        current = null
    }

    fun accept(timestampUs: Long, coordinates: List<Double>, confidence: Double): Output {
        require(coordinates.size == coordinateCount) {
            "Expected $coordinateCount coordinates, received ${coordinates.size}"
        }
        require(coordinates.all(Double::isFinite)) { "Coordinates must be finite" }
        require(confidence.isFinite()) { "Confidence must be finite" }
        val previous = current
        require(previous == null || timestampUs > previous.timestampUs) {
            "Timestamps must be strictly increasing: previous=${previous?.timestampUs}, current=$timestampUs"
        }

        rawCoordinates.addLast(coordinates)
        rawConfidences.addLast(confidence)
        trim(rawCoordinates, medianSamples)
        trim(rawConfidences, medianSamples)

        val coordinateMedian = List(coordinateCount) { dimension ->
            median(rawCoordinates.map { it[dimension] })
        }
        medianCoordinates.addLast(coordinateMedian)
        medianConfidences.addLast(median(rawConfidences.toList()))
        trim(medianCoordinates, meanSamples)
        trim(medianConfidences, meanSamples)

        val filtered = List(coordinateCount) { dimension ->
            medianCoordinates.map { it[dimension] }.average()
        }
        val output = Output(
            timestampUs = timestampUs,
            coordinates = filtered,
            confidence = medianConfidences.average(),
            previousTimestampUs = previous?.timestampUs,
            previousCoordinates = previous?.coordinates,
            previousConfidence = previous?.confidence,
        )
        current = output
        return output
    }

    private fun <T> trim(values: ArrayDeque<T>, maximumSize: Int) {
        while (values.size > maximumSize) values.removeFirst()
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) * 0.5
    }
}
