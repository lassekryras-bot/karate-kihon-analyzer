package dk.lasse.karateanalyzer.capture.qom

import kotlin.math.max

/**
 * State of the QoM movement hysteresis decision.
 */
enum class QomSegmentState {
    STILL,
    MOVING,
}

enum class QomEndBoundaryKind {
    STOP_GATE,
    RECORDING_END_WITHOUT_STOP_EVIDENCE,
}

data class QomUnknownEvidenceInterval(
    val startTimestampMs: Long,
    val endTimestampMs: Long,
)

/**
 * Bounded physical movement interval produced by the QoM hysteresis segmenter.
 */
data class QomMovementSegment(
    val movementNumber: Int,
    val logicalStartTimestampMs: Long,
    val logicalEndTimestampMs: Long,
    val durationMs: Long,
    val retainedStartTimestampMs: Long,
    val retainedEndTimestampMs: Long,
    val endBoundaryKind: QomEndBoundaryKind = QomEndBoundaryKind.STOP_GATE,
    val unknownEvidenceIntervals: List<QomUnknownEvidenceInterval> = emptyList(),
)

/**
 * Per-frame snapshot of the QoM segmenter state.
 */
data class QomSegmenterSnapshot(
    val timestampMs: Long,
    val state: QomSegmentState,
    val currentMovementNumber: Int,
    val activeStartBoundaryMs: Long?,
    val completedSegment: QomMovementSegment?,
    val trigger: String?,
)

/**
 * Authoritative Quantity-of-Motion movement segmenter.
 *
 * Implements clean two-state hysteresis with immediate re-arming:
 * - STILL -> MOVING when rolling area >= startGate (default 0.08 m)
 * - MOVING -> STILL when rolling area <= stopGate (default 0.04 m)
 * - Between gates: preserves current state.
 *
 * Logical boundaries mark physical motion onset and cessation.
 * Retained boundaries add configurable pre-roll and post-roll for viewing.
 */
class QomMovementSegmenter(
    val config: QomDetectorConfig = QomDetectorConfig(),
) {
    var state: QomSegmentState = QomSegmentState.STILL
        private set

    var currentMovementNumber: Int = 1
        private set

    private var activeStartBoundaryMs: Long? = null
    private var activeUnknownStartMs: Long? = null
    private val activeUnknownIntervals = mutableListOf<QomUnknownEvidenceInterval>()
    private var lastEvidenceTimestampMs: Long? = null

    /**
     * Resets the segmenter state.
     */
    fun reset() {
        state = QomSegmentState.STILL
        currentMovementNumber = 1
        activeStartBoundaryMs = null
        activeUnknownStartMs = null
        activeUnknownIntervals.clear()
        lastEvidenceTimestampMs = null
    }

    /**
     * Accepts a frame's QoM evidence and advances the hysteresis state machine.
     */
    fun accept(evidence: QomFrameEvidence): QomSegmenterSnapshot {
        val t = evidence.timestampMs
        val gapStart = lastEvidenceTimestampMs?.takeIf { t - it > config.maximumFrameGapMs }
        lastEvidenceTimestampMs = t
        if (config.evidencePolicy == QomEvidencePolicy.OBSERVED_WORLD_V2 && !evidence.isAvailable) {
            if (state == QomSegmentState.MOVING && activeUnknownStartMs == null) activeUnknownStartMs = gapStart ?: t
            return QomSegmenterSnapshot(t, state, currentMovementNumber, activeStartBoundaryMs, null, null)
        }
        activeUnknownStartMs?.let { start ->
            activeUnknownIntervals += QomUnknownEvidenceInterval(start, t)
            activeUnknownStartMs = null
        }
        val area = evidence.rollingArea

        var completedSegment: QomMovementSegment? = null
        var trigger: String? = null

        when (state) {
            QomSegmentState.STILL -> {
                if (area >= config.startGateMeters) {
                    state = QomSegmentState.MOVING
                    activeStartBoundaryMs = t
                    trigger = "start_gate_crossing"
                }
            }

            QomSegmentState.MOVING -> {
                if (area <= config.stopGateMeters) {
                    val startMs = checkNotNull(activeStartBoundaryMs)
                    val endMs = t
                    val duration = endMs - startMs

                    completedSegment = QomMovementSegment(
                        movementNumber = currentMovementNumber,
                        logicalStartTimestampMs = startMs,
                        logicalEndTimestampMs = endMs,
                        durationMs = duration,
                        retainedStartTimestampMs = max(0L, startMs - config.preRollMs),
                        retainedEndTimestampMs = endMs + config.postRollMs,
                        unknownEvidenceIntervals = activeUnknownIntervals.toList(),
                    )

                    state = QomSegmentState.STILL
                    currentMovementNumber++
                    activeStartBoundaryMs = null
                    activeUnknownIntervals.clear()
                    trigger = "stop_gate_crossing"
                }
            }
        }

        return QomSegmenterSnapshot(
            timestampMs = t,
            state = state,
            currentMovementNumber = currentMovementNumber,
            activeStartBoundaryMs = activeStartBoundaryMs,
            completedSegment = completedSegment,
            trigger = trigger,
        )
    }

    /**
     * Finalizes the segmenter at the end of a recording.
     * If currently MOVING, completes the in-progress interval up to [finalTimestampMs].
     */
    fun finish(finalTimestampMs: Long): QomMovementSegment? {
        if (state == QomSegmentState.MOVING && activeStartBoundaryMs != null) {
            val startMs = activeStartBoundaryMs!!
            val endMs = max(startMs, finalTimestampMs)
            val segment = QomMovementSegment(
                movementNumber = currentMovementNumber,
                logicalStartTimestampMs = startMs,
                logicalEndTimestampMs = endMs,
                durationMs = endMs - startMs,
                retainedStartTimestampMs = max(0L, startMs - config.preRollMs),
                retainedEndTimestampMs = endMs + config.postRollMs,
                endBoundaryKind = QomEndBoundaryKind.RECORDING_END_WITHOUT_STOP_EVIDENCE,
                unknownEvidenceIntervals = buildList {
                    addAll(activeUnknownIntervals)
                    activeUnknownStartMs?.let { add(QomUnknownEvidenceInterval(it, endMs)) }
                },
            )
            state = QomSegmentState.STILL
            currentMovementNumber++
            activeStartBoundaryMs = null
            activeUnknownStartMs = null
            activeUnknownIntervals.clear()
            return segment
        }
        return null
    }
}

