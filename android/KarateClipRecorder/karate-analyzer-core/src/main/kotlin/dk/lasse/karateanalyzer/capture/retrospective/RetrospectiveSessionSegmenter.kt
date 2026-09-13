package dk.lasse.karateanalyzer.capture.retrospective

import dk.lasse.karateanalyzer.core.PoseFrame
import kotlin.math.max
import kotlin.math.min

/**
 * Result of authoritative retrospective session segmentation.
 */
data class RetrospectiveSessionResult(
    val sequenceId: String,
    val masterVideoPath: String,
    val totalFrames: Int,
    val totalDurationMs: Long,
    val detectedMovementCount: Int,
    val movements: List<SessionMovement>,
    val kinematicsTimeline: List<RetrospectiveKinematicFrame>,
    val stats: RetrospectiveSignalStats,
)

/**
 * Authoritative retrospective movement segmenter.
 *
 * Operates over the completed landmark timeline from the finalized continuous master recording.
 * Applies centered temporal kinematics and session-relative q20/q90 normalization.
 *
 * Segments movements into logical [SessionMovement] intervals without cutting physical MP4 files.
 */
class RetrospectiveSessionSegmenter(
    val config: RetrospectiveSegmenterConfig = RetrospectiveSegmenterConfig(),
) {
    private val extractor = RetrospectiveKinematicsExtractor(config)

    fun segment(
        sequenceId: String,
        masterVideoPath: String,
        frames: List<PoseFrame>,
        cueTimeline: CueTimeline? = null,
    ): RetrospectiveSessionResult {
        if (frames.isEmpty()) {
            return RetrospectiveSessionResult(
                sequenceId = sequenceId,
                masterVideoPath = masterVideoPath,
                totalFrames = 0,
                totalDurationMs = 0L,
                detectedMovementCount = 0,
                movements = emptyList(),
                kinematicsTimeline = emptyList(),
                stats = RetrospectiveSignalStats(0.0, 0.0, 0.0, 0.0, 0.1580),
            )
        }

        val totalDurationMs = frames.last().timestampMs
        val (timeline, stats) = extractor.extract(frames)
        val n = timeline.size

        // 1. Detect raw movement bursts using normalized hysteresis
        data class RawBurst(val startMs: Long, val endMs: Long)
        val rawBursts = mutableListOf<RawBurst>()

        var inMovement = false
        var currentStartMs = 0L

        for (i in 0 until n) {
            val f = timeline[i]
            val comb = f.combinedMovementEvidence ?: continue

            if (!inMovement) {
                if (comb >= config.startThresholdNormalized) {
                    inMovement = true
                    // Trace back to when signal rose above quiet threshold (up to 300 ms back)
                    var j = i
                    val tCurrent = f.timestampMs
                    while (j > 0) {
                        val prevFrame = timeline[j - 1]
                        if ((tCurrent - prevFrame.timestampMs) > 300L) break
                        val prevComb = prevFrame.combinedMovementEvidence ?: break
                        if (prevComb <= config.quietThresholdNormalized) break
                        j--
                    }
                    currentStartMs = timeline[j].timestampMs
                }
            } else {
                if (comb <= config.quietThresholdNormalized) {
                    // Check if quiet persists for minQuietDwellMs
                    var k = i
                    var quietDuration = 0L
                    while (k < n && (timeline[k].combinedMovementEvidence ?: 1.0) <= config.quietThresholdNormalized && quietDuration < (config.minQuietDwellMs + 20L)) {
                        quietDuration = timeline[k].timestampMs - f.timestampMs
                        k++
                    }
                    if (quietDuration >= config.minQuietDwellMs) {
                        inMovement = false
                        val burstEndMs = f.timestampMs
                        val dur = burstEndMs - currentStartMs
                        if (dur >= config.minMovementDurationMs && dur <= config.maxMovementDurationMs) {
                            rawBursts.add(RawBurst(currentStartMs, burstEndMs))
                        }
                    }
                }
            }
        }

        // 2. Merge bursts separated by less than cadence threshold
        val mergedBursts = mutableListOf<RawBurst>()
        val minPauseMs = config.cadence.minInterMovementPauseMs

        for (burst in rawBursts) {
            if (mergedBursts.isEmpty()) {
                mergedBursts.add(burst)
            } else {
                val last = mergedBursts.last()
                val pause = burst.startMs - last.endMs
                if (pause < minPauseMs) {
                    mergedBursts[mergedBursts.size - 1] = RawBurst(last.startMs, burst.endMs)
                } else {
                    mergedBursts.add(burst)
                }
            }
        }

        // 3. Create SessionMovement logical intervals and associate cues
        val sessionMovements = mutableListOf<SessionMovement>()
        val availableCues = cueTimeline?.cues.orEmpty()

        for ((idx, burst) in mergedBursts.withIndex()) {
            val movementNumber = idx + 1
            val logicalStart = burst.startMs
            val logicalEnd = burst.endMs

            val retainedStart = max(0L, logicalStart - config.preRollMs)
            val retainedEnd = min(totalDurationMs, logicalEnd + config.postRollMs)

            // Associate nearest matching cue preceding or coinciding with movement start
            val cue = availableCues.firstOrNull { c ->
                val cueTime = c.videoTimestampMs ?: c.monotonicTimestampMs
                cueTime in (logicalStart - 600L)..(logicalStart + 200L)
            }

            // Select preferred snapshot timestamp: peak movement evidence frame within the movement
            var peakTimestampMs = (logicalStart + logicalEnd) / 2L
            var peakEvidence = -1.0
            for (f in timeline) {
                if (f.timestampMs in logicalStart..logicalEnd) {
                    val ev = f.combinedMovementEvidence ?: 0.0
                    if (ev > peakEvidence) {
                        peakEvidence = ev
                        peakTimestampMs = f.timestampMs
                    }
                }
            }

            sessionMovements.add(
                SessionMovement(
                    movementNumber = movementNumber,
                    sourceRecordingPath = masterVideoPath,
                    logicalStartTimestampMs = logicalStart,
                    logicalEndTimestampMs = logicalEnd,
                    durationMs = logicalEnd - logicalStart,
                    retainedStartTimestampMs = retainedStart,
                    retainedEndTimestampMs = retainedEnd,
                    preferredSnapshotTimestampMs = peakTimestampMs,
                    associatedCue = cue,
                    segmentationConfidence = 1.0,
                    analysisStatus = MovementAnalysisStatus.DETECTED,
                )
            )
        }

        return RetrospectiveSessionResult(
            sequenceId = sequenceId,
            masterVideoPath = masterVideoPath,
            totalFrames = frames.size,
            totalDurationMs = totalDurationMs,
            detectedMovementCount = sessionMovements.size,
            movements = sessionMovements,
            kinematicsTimeline = timeline,
            stats = stats,
        )
    }
}

