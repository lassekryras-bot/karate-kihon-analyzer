package dk.lasse.karateanalyzer.audiocue

data class ImmutableCueRecord(
    val eventId: String,
    val cueTimestampUs: Long,
    val ordinal: Int,
    val countValue: Int,
    val packageVersionId: String? = null,
    val assetId: String? = null,
    val timingSource: String = AudioCueTimingProvenance.AUDIO_CUE_ANCHOR_A10,
)

data class MovementBoundaryRecord(
    val movementId: String,
    val startUs: Long,
    val endUs: Long,
    val analysisFrameUs: Long? = null,
    val peakSpeedUs: Long? = null,
)

data class CueToMovementLatency(
    val cueSessionEventId: String,
    val movementId: String,
    val repetitionOrdinal: Int,
    val cueTimestampUs: Long,
    val movementStartUs: Long,
    val latencyUs: Long,
    val cueToMovementFinishLatencyUs: Long? = null,
    val cueToTheoreticalImpactLatencyUs: Long? = null,
    val cueToMaxSpeedLatencyUs: Long? = null,
    val analyzerVersion: String = CueToMovementLatencyAnalyzer.ANALYZER_VERSION,
    val packageVersionId: String? = null,
    val timingProvenance: String = AudioCueTimingProvenance.AUDIO_CUE_ANCHOR_A10,
)

/**
 * Measures cue-to-movement latency against the best estimated audible cue ([ImmutableCueRecord.cueTimestampUs] / `spoken_count`),
 * not laboratory-grade reaction time.
 *
 * This references the capture-time immutable cue timestamp with explicit package provenance.
 * If future device-specific audio-route latency calibration is performed, it can be layered as an
 * analytical adjustment without altering historical cue records or playback request events.
 */
object CueToMovementLatencyAnalyzer {
    const val ANALYZER_VERSION = "cue_to_movement_latency_v1"

    /**
     * Compute cue-to-movement latency for each cue referencing the immutable cue timestamp.
     * Cues are matched sequentially or by nearest subsequent movement start within tolerance.
     */
    fun analyze(
        cues: List<ImmutableCueRecord>,
        movements: List<MovementBoundaryRecord>,
    ): List<CueToMovementLatency> {
        val sortedCues = cues.sortedBy { it.cueTimestampUs }
        val sortedMovements = movements.sortedBy { it.startUs }

        return sortedCues.mapIndexedNotNull { index, cue ->
            val movement = if (index < sortedMovements.size) {
                sortedMovements[index]
            } else {
                sortedMovements.firstOrNull { it.startUs >= cue.cueTimestampUs - 100_000L }
            } ?: return@mapIndexedNotNull null

            val latencyUs = movement.startUs - cue.cueTimestampUs
            val finishLatencyUs = movement.endUs - cue.cueTimestampUs
            val impactLatencyUs = movement.analysisFrameUs?.let { it - cue.cueTimestampUs }
            val peakSpeedLatencyUs = movement.peakSpeedUs?.let { it - cue.cueTimestampUs }

            val provenance = AudioCueTimingProvenance.provenanceFor(cue.packageVersionId, cue.timingSource)

            CueToMovementLatency(
                cueSessionEventId = cue.eventId,
                movementId = movement.movementId,
                repetitionOrdinal = cue.ordinal,
                cueTimestampUs = cue.cueTimestampUs,
                movementStartUs = movement.startUs,
                latencyUs = latencyUs,
                cueToMovementFinishLatencyUs = finishLatencyUs,
                cueToTheoreticalImpactLatencyUs = impactLatencyUs,
                cueToMaxSpeedLatencyUs = peakSpeedLatencyUs,
                analyzerVersion = ANALYZER_VERSION,
                packageVersionId = cue.packageVersionId,
                timingProvenance = provenance,
            )
        }
    }
}
