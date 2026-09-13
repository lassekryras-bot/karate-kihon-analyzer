package dk.lasse.karateanalyzer.capture.retrospective

/**
 * Recording cadence profile governing movement isolation vs combination grouping.
 */
enum class RetrospectiveCadence(
    val minInterMovementPauseMs: Long,
) {
    /**
     * Repetitive practice (e.g. straight punch kihon sequence).
     * Inter-movement pause threshold of 200 ms isolates rapid repetitions without merging.
     */
    REPETITIONS(minInterMovementPauseMs = 200L),

    /**
     * Standard multi-movement karate drill containing preparation, execution, and recovery.
     * Inter-movement pause threshold of 500 ms preserves chamber-to-execution holds and combinations.
     */
    NORMAL(minInterMovementPauseMs = 500L),

    /**
     * Combination training where multiple rapid strikes are expected to form a single combination.
     */
    COMBINATION(minInterMovementPauseMs = 600L),
}

/**
 * Configuration for the authoritative retrospective movement segmenter.
 */
data class RetrospectiveSegmenterConfig(
    val cadence: RetrospectiveCadence = RetrospectiveCadence.NORMAL,
    val halfWindowDurationMs: Long = 50L,
    val minimumSamplesInWindow: Int = 3,
    val minimumWindowSpanMs: Long = 50L,
    val preRollMs: Long = 150L,
    val postRollMs: Long = 200L,
    val startThresholdNormalized: Double = 0.50,
    val quietThresholdNormalized: Double = 0.25,
    val minQuietDwellMs: Long = 60L,
    val minMovementDurationMs: Long = 120L,
    val maxMovementDurationMs: Long = 8000L,
    val q20Percentile: Double = 20.0,
    val q90Percentile: Double = 90.0,
)

