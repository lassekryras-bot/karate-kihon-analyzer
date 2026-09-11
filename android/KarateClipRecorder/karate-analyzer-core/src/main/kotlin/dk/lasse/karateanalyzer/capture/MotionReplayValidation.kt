package dk.lasse.karateanalyzer.capture

import dk.lasse.karateanalyzer.observation.TransitionRecord

data class MotionReplayParameterSet(
    val name: String,
    val extractor: PoseMotionExtractorConfig = PoseMotionExtractorConfig(),
    val segmenter: GenericMotionSegmenterConfig = GenericMotionSegmenterConfig(),
)

enum class CaptureSafetyFailure {
    NOT_ARMED,
    MISSED_MOVEMENT,
    CLIPPED_START,
    PREMATURE_COMPLETION,
    COMPLETED_DURING_TRACKING_LOSS,
    FALSE_POSITIVE,
    UNNECESSARY_FAILURE,
}

data class CaptureSafetyScore(
    val totalPenalty: Double,
    val failures: Set<CaptureSafetyFailure>,
    val startOffsetMs: Long?,
    val endOffsetMs: Long?,
    val extraPreRollMs: Long,
    val extraPostRollMs: Long,
)

data class MotionReplayTraceRow(
    val timestampMs: Long,
    val observation: MotionObservation,
    val segmenter: MotionSegmentSnapshot,
    val cueAtThisTimestamp: Boolean,
    val deadlineAtThisTimestamp: Boolean,
)

data class MotionReplayResult(
    val sequenceId: String,
    val parameterSet: String,
    val trace: List<MotionReplayTraceRow>,
    val transitions: List<TransitionRecord<MotionSegmentState>>,
    val finalState: MotionSegmentState,
    val failure: MotionSegmentFailure?,
    val score: CaptureSafetyScore,
    val trustedLabels: PoseSequenceLabels?,
    val armTimestampMs: Long?,
    val cueTimestampMs: Long?,
    val activityDeadlineMs: Long?,
)

data class MotionReplaySweepResult(
    val parameterSet: MotionReplayParameterSet,
    val fixtures: List<MotionReplayResult>,
) {
    val totalPenalty = fixtures.sumOf { it.score.totalPenalty }
    val severeFailureCount = fixtures.sumOf { result ->
        result.score.failures.count {
            it in setOf(
                CaptureSafetyFailure.CLIPPED_START,
                CaptureSafetyFailure.PREMATURE_COMPLETION,
                CaptureSafetyFailure.COMPLETED_DURING_TRACKING_LOSS,
                CaptureSafetyFailure.FALSE_POSITIVE,
            )
        }
    }
}

class MotionReplayRunner {
    fun run(fixture: PoseReplayFixture, parameters: MotionReplayParameterSet): MotionReplayResult {
        val extractor = PoseMotionObservationExtractor(parameters.extractor)
        val segmenter = GenericMotionSegmenter(
            parameters.segmenter.copy(endPoseRelationship = fixture.expectedEndPoseRelationship),
        )
        val trace = mutableListOf<MotionReplayTraceRow>()
        var armed = false
        var previousTimestamp: Long? = null
        fixture.frames.forEach { frame ->
            val requestedArm = fixture.armTimestampMs
            val actualArm = requestedArm?.let { maxOf(it, previousTimestamp ?: it) }
            val cueStillAhead = fixture.cueTimestampMs == null ||
                (actualArm != null && actualArm <= fixture.cueTimestampMs)
            if (!armed && requestedArm != null && requestedArm <= frame.timestampMs &&
                segmenter.snapshot().baselineReady && cueStillAhead
            ) {
                segmenter.arm(
                    atTimestampMs = checkNotNull(actualArm),
                    cueTimestampMs = fixture.cueTimestampMs,
                    activityDeadlineMs = fixture.activityDeadlineMs,
                )
                armed = true
            }
            val observation = extractor.accept(frame)
            val snapshot = segmenter.accept(observation)
            trace += MotionReplayTraceRow(
                frame.timestampMs,
                observation,
                snapshot,
                cueAtThisTimestamp = fixture.cueTimestampMs == frame.timestampMs,
                deadlineAtThisTimestamp = fixture.activityDeadlineMs == frame.timestampMs,
            )
            previousTimestamp = frame.timestampMs
        }
        val transitions = segmenter.snapshot().transitions
        return MotionReplayResult(
            sequenceId = fixture.sequenceId,
            parameterSet = parameters.name,
            trace = trace,
            transitions = transitions,
            finalState = segmenter.snapshot().state,
            failure = segmenter.snapshot().failure,
            score = CaptureSafetyScorer.score(fixture, armed, transitions, segmenter.snapshot()),
            trustedLabels = fixture.labels,
            armTimestampMs = fixture.armTimestampMs,
            cueTimestampMs = fixture.cueTimestampMs,
            activityDeadlineMs = fixture.activityDeadlineMs,
        )
    }

    fun sweep(fixtures: List<PoseReplayFixture>, parameters: List<MotionReplayParameterSet>) =
        parameters.map { set -> MotionReplaySweepResult(set, fixtures.map { run(it, set) }) }
}

object CaptureSafetyScorer {
    fun score(
        fixture: PoseReplayFixture,
        armed: Boolean,
        transitions: List<TransitionRecord<MotionSegmentState>>,
        final: MotionSegmentSnapshot,
    ): CaptureSafetyScore {
        val labels = fixture.labels
        val start = transitions.firstOrNull { it.newState == MotionSegmentState.MOVING }?.estimatedBoundaryTimestampMs
        val end = transitions.firstOrNull { it.newState == MotionSegmentState.COMPLETE }?.estimatedBoundaryTimestampMs
        val failures = linkedSetOf<CaptureSafetyFailure>()
        var penalty = 0.0
        if (!armed) { failures += CaptureSafetyFailure.NOT_ARMED; penalty += 400.0 }
        if (labels?.movementStartTimestampMs == null && start != null) {
            failures += CaptureSafetyFailure.FALSE_POSITIVE; penalty += 1_500.0
        }
        if (labels?.movementStartTimestampMs != null && start == null) {
            failures += CaptureSafetyFailure.MISSED_MOVEMENT; penalty += 1_200.0
        }
        if (labels?.movementEndTimestampMs != null && start != null && end == null) {
            failures += CaptureSafetyFailure.UNNECESSARY_FAILURE; penalty += 400.0
        }
        val startOffset = if (start != null && labels?.movementStartTimestampMs != null) start - labels.movementStartTimestampMs else null
        val endOffset = if (end != null && labels?.movementEndTimestampMs != null) end - labels.movementEndTimestampMs else null
        if (startOffset != null && startOffset > 0) {
            failures += CaptureSafetyFailure.CLIPPED_START; penalty += 800.0 + startOffset * 5.0
        }
        if (endOffset != null && endOffset < 0) {
            failures += CaptureSafetyFailure.PREMATURE_COMPLETION; penalty += 2_000.0 + -endOffset * 10.0
        }
        if (end != null && labels?.intermediateStablePlateaus.orEmpty().any { end in it }) {
            failures += CaptureSafetyFailure.PREMATURE_COMPLETION; penalty += 2_000.0
        }
        if (end != null && labels?.trackingLossIntervals.orEmpty().any { end in it }) {
            failures += CaptureSafetyFailure.COMPLETED_DURING_TRACKING_LOSS; penalty += 2_500.0
        }
        if (final.state == MotionSegmentState.FAILED && labels?.movementStartTimestampMs != null &&
            CaptureSafetyFailure.UNNECESSARY_FAILURE !in failures
        ) {
            failures += CaptureSafetyFailure.UNNECESSARY_FAILURE; penalty += 400.0
        }
        val extraPre = startOffset?.let { (-it).coerceAtLeast(0L) } ?: 0L
        val extraPost = endOffset?.coerceAtLeast(0L) ?: 0L
        penalty += extraPre * 0.02 + extraPost * 0.1
        return CaptureSafetyScore(penalty, failures, startOffset, endOffset, extraPre, extraPost)
    }
}

object MotionReplayTraceJson {
    const val SCHEMA_VERSION = "motion-replay-trace-v1"

    fun encode(result: MotionReplayResult): String = buildString {
        append("{\"schema_version\":\"").append(SCHEMA_VERSION).append("\",\"sequence_id\":")
        quote(result.sequenceId); append(",\"parameter_set\":"); quote(result.parameterSet)
        append(",\"final_state\":\"").append(result.finalState.name).append("\",\"failure\":")
        nullableString(result.failure?.name)
        append(",\"trusted_movement_start_ms\":"); nullableNumber(result.trustedLabels?.movementStartTimestampMs)
        append(",\"trusted_movement_end_ms\":"); nullableNumber(result.trustedLabels?.movementEndTimestampMs)
        append(",\"arm_timestamp_ms\":"); nullableNumber(result.armTimestampMs)
        append(",\"cue_timestamp_ms\":"); nullableNumber(result.cueTimestampMs)
        append(",\"activity_deadline_ms\":"); nullableNumber(result.activityDeadlineMs)
        append(",\"terminal_stable_interval\":"); interval(result.trustedLabels?.terminalStableInterval)
        append(",\"intermediate_stable_plateaus\":"); intervals(result.trustedLabels?.intermediateStablePlateaus.orEmpty())
        append(",\"tracking_loss_intervals\":"); intervals(result.trustedLabels?.trackingLossIntervals.orEmpty())
        append(",\"ambiguous_intervals\":"); intervals(result.trustedLabels?.ambiguousIntervals.orEmpty())
        append(",\"score\":{\"total_penalty\":${result.score.totalPenalty},\"start_offset_ms\":")
        nullableNumber(result.score.startOffsetMs); append(",\"end_offset_ms\":"); nullableNumber(result.score.endOffsetMs)
        append(",\"failures\":["); result.score.failures.forEachIndexed { i, failure -> if (i > 0) append(','); quote(failure.name) }; append("]}")
        append(",\"frames\":[")
        result.trace.forEachIndexed { index, row ->
            if (index > 0) append(',')
            val observation = row.observation
            val diagnostics = observation.diagnostics
            append("{\"timestamp_ms\":${row.timestampMs},\"articulated_motion\":"); nullableNumber(observation.articulatedMotion)
            append(",\"image_space_motion\":"); nullableNumber(observation.imageSpaceMotion)
            append(",\"slow_displacement\":"); nullableNumber(observation.accumulatedDisplacement)
            append(",\"coverage\":${observation.coverage},\"same_similarity\":"); nullableNumber(observation.sameAsStartSimilarity)
            append(",\"mirrored_similarity\":"); nullableNumber(observation.mirroredStartSimilarity)
            append(",\"state\":\"").append(row.segmenter.state.name).append("\",\"cue\":${row.cueAtThisTimestamp},\"deadline\":${row.deadlineAtThisTimestamp}")
            append(",\"baseline_dwell_ms\":${row.segmenter.baselineDwell.accumulatedDurationMs}")
            append(",\"movement_dwell_ms\":${row.segmenter.movementDwell.accumulatedDurationMs}")
            append(",\"completion_dwell_ms\":${row.segmenter.completionDwell.accumulatedDurationMs}")
            append(",\"articulated_status\":"); nullableString(diagnostics?.articulatedStatus?.name)
            append(",\"image_status\":"); nullableString(diagnostics?.imageSpaceStatus?.name)
            append(",\"image_center_translation\":"); nullableNumber(diagnostics?.imageCenterTranslation)
            append(",\"image_scale_change\":"); nullableNumber(diagnostics?.imageScaleChange)
            append(",\"minimum_region_coverage\":"); nullableNumber(diagnostics?.minimumRegionCoverage)
            append(",\"region_balanced_coverage\":"); nullableNumber(diagnostics?.regionBalancedCoverage)
            append(",\"regions\":{")
            diagnostics?.regions?.entries?.sortedBy { it.key.ordinal }?.forEachIndexed { regionIndex, (region, values) ->
                if (regionIndex > 0) append(','); quote(region.name); append(":{\"coverage\":${values.coverage},\"raw_motion\":")
                nullableNumber(values.rawMotion); append(",\"robust_motion\":"); nullableNumber(values.robustMotion)
                append(",\"clamp_count\":${values.clampedLandmarkCount}}")
            }
            append("}}")
        }
        append("],\"transitions\":[")
        result.transitions.forEachIndexed { index, transition ->
            if (index > 0) append(',')
            append("{\"from\":\"").append(transition.previousState.name).append("\",\"to\":\"").append(transition.newState.name)
            append("\",\"decision_timestamp_ms\":${transition.decisionTimestampMs},\"boundary_timestamp_ms\":")
            nullableNumber(transition.estimatedBoundaryTimestampMs); append(",\"trigger\":"); quote(transition.trigger); append('}')
        }
        append("]}\n")
    }

    private fun StringBuilder.quote(value: String) = append('"').append(value.replace("\\", "\\\\").replace("\"", "\\\"")).append('"')
    private fun StringBuilder.nullableString(value: String?) { if (value == null) append("null") else quote(value) }
    private fun StringBuilder.nullableNumber(value: Number?) { append(value ?: "null") }
    private fun StringBuilder.interval(value: ReplayInterval?) { if (value == null) append("null") else append("[${value.startTimestampMs},${value.endTimestampMs}]") }
    private fun StringBuilder.intervals(values: List<ReplayInterval>) { append('['); values.forEachIndexed { index, value -> if (index > 0) append(','); interval(value) }; append(']') }
}
