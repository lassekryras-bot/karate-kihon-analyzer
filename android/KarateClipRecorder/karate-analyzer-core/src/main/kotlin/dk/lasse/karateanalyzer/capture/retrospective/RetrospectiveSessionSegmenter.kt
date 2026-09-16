package dk.lasse.karateanalyzer.capture.retrospective

import dk.lasse.karateanalyzer.capture.qom.QomFrameEvidence
import dk.lasse.karateanalyzer.capture.qom.QomMotionEvidenceExtractor
import dk.lasse.karateanalyzer.capture.qom.QomMovementSegmenter
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
    val qomTimeline: List<QomFrameEvidence> = emptyList(),
)

/**
 * Authoritative retrospective movement segmenter.
 *
 * Operates over the completed landmark timeline from the finalized continuous master recording.
 * Uses activity-aware Quantity of Motion (QoM) and two-state hysteresis as the normative physical
 * movement boundary evidence.
 *
 * Segments movements into logical [SessionMovement] intervals without cutting physical MP4 files.
 */
class RetrospectiveSessionSegmenter(
    val config: RetrospectiveSegmenterConfig = RetrospectiveSegmenterConfig(),
) {
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
                qomTimeline = emptyList(),
            )
        }

        val totalDurationMs = frames.last().timestampMs
        val extractor = QomMotionEvidenceExtractor(config = config.qomConfig, profile = config.profile)
        val segmenter = QomMovementSegmenter(config = config.qomConfig)

        // 1. Detect raw physical movement bursts using QoM hysteresis
        data class RawBurst(val startMs: Long, val endMs: Long)
        val rawBursts = mutableListOf<RawBurst>()
        val qomTimeline = ArrayList<QomFrameEvidence>(frames.size)

        for (frame in frames) {
            val ev = extractor.extract(frame)
            qomTimeline.add(ev)
            val snap = segmenter.accept(ev)
            if (snap.completedSegment != null) {
                val seg = snap.completedSegment
                if (seg.durationMs in config.minMovementDurationMs..config.maxMovementDurationMs) {
                    rawBursts.add(RawBurst(seg.logicalStartTimestampMs, seg.logicalEndTimestampMs))
                }
            }
        }
        val finalSeg = segmenter.finish(totalDurationMs)
        if (finalSeg != null && finalSeg.durationMs in config.minMovementDurationMs..config.maxMovementDurationMs) {
            rawBursts.add(RawBurst(finalSeg.logicalStartTimestampMs, finalSeg.logicalEndTimestampMs))
        }

        // 2. Merge bursts separated by less than cadence threshold (preserves combinations if configured)
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

            // Select preferred snapshot timestamp: peak rolling area frame within the movement
            var peakTimestampMs = (logicalStart + logicalEnd) / 2L
            var peakEvidence = -1.0
            for (ev in qomTimeline) {
                if (ev.timestampMs in logicalStart..logicalEnd) {
                    if (ev.rollingArea > peakEvidence) {
                        peakEvidence = ev.rollingArea
                        peakTimestampMs = ev.timestampMs
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

        // Maintain compatibility kinematics timeline populated with QoM metrics
        val kinematicsTimeline = qomTimeline.mapIndexed { index, ev ->
            RetrospectiveKinematicFrame(
                timestampMs = ev.timestampMs,
                frameIndex = index,
                rawTranslationEvidence = ev.aggregateQom,
                rawAngularEvidence = null,
                normalizedTranslationEvidence = ev.rollingArea,
                normalizedAngularEvidence = null,
                combinedMovementEvidence = ev.rollingArea,
                isMoving = ev.rollingArea >= config.qomConfig.startGateMeters,
                isQuiet = ev.rollingArea <= config.qomConfig.stopGateMeters,
            )
        }

        val stats = RetrospectiveSignalStats(
            translationQ20 = 0.0,
            translationQ90 = 0.0,
            angularQ20 = 0.0,
            angularQ90 = 0.0,
            referenceScale = 1.0,
        )

        return RetrospectiveSessionResult(
            sequenceId = sequenceId,
            masterVideoPath = masterVideoPath,
            totalFrames = frames.size,
            totalDurationMs = totalDurationMs,
            detectedMovementCount = sessionMovements.size,
            movements = sessionMovements,
            kinematicsTimeline = kinematicsTimeline,
            stats = stats,
            qomTimeline = qomTimeline,
        )
    }
}


