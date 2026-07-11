package dk.lasse.karateanalyzer.core

/** Stable temporal state emitted by [TemporalStepVerifier]. */
enum class TemporalVerificationStatus {
    WAITING_FOR_DATA,
    BUILDING_PROGRESS,
    HOLDING,
    PAUSED,
    LOSING_PROGRESS,
    ACCEPTED,
}

/**
 * Temporal verification policy for converting instant hand-shape results into deliberate holds.
 *
 * @property requiredHoldDurationMs reliable GOOD/MATCHING time required before acceptance.
 * @property missingDataGracePeriodMs short insufficient-data gaps that pause progress before decay.
 * @property partialMatchGracePeriodMs short partial-match gaps that preserve progress before decay.
 * @property progressDecayPerSecond fraction of progress removed per second after grace or wrong shapes.
 * @property partialProgressMultiplier optional partial-match time contribution multiplier; it never accepts alone.
 * @property minimumReliableMatchingRatio minimum reliable/accumulated matching-time ratio required to accept.
 * @property minimumAcceptedQuality minimum latest instant quality and per-frame reliable quality.
 * @property maximumFrameGapMs unexpectedly large timestamp gap that resets state rather than bridging time.
 */
data class TemporalVerificationConfiguration(
    val requiredHoldDurationMs: Long = 600,
    val missingDataGracePeriodMs: Long = 200,
    val partialMatchGracePeriodMs: Long = 150,
    val progressDecayPerSecond: Float = 1.25f,
    val partialProgressMultiplier: Float = 0.20f,
    val minimumReliableMatchingRatio: Float = 0.70f,
    val minimumAcceptedQuality: Float = 0.70f,
    val maximumFrameGapMs: Long = 250,
)

/** Result of timestamp-based temporal verification for one frame. */
data class TemporalStepResult(
    val step: HandLessonStep,
    val status: TemporalVerificationStatus,
    val progress: Float,
    val accepted: Boolean,
    val newlyAccepted: Boolean,
    val feedbackCode: FeedbackCode,
    val latestInstantResult: InstantStepResult,
    val accumulatedMatchingMs: Long,
    val reliableMatchingMs: Long,
)

/** Weighted provenance statistics for the landmarks relevant to a lesson step. */
data class LandmarkProvenanceSummary(
    val criticalLandmarks: Set<HandLandmarkId>,
    val visible: Boolean,
    val reliability: Float,
    val predictedOnly: Boolean,
)

/**
 * Accumulates consecutive [InstantStepResult] values into stable Find Your Weapon acceptance.
 *
 * Instant matching describes one frame; temporal acceptance requires timestamp-based reliable holding across
 * consecutive [TrackedHandFrame.timestampMs] values, so behavior is independent of camera FPS. Short partial
 * or missing-data gaps pause progress for configurable grace periods; longer interruptions decay progress
 * instead of instantly resetting a nearly completed hold. Incorrect shapes decay progress using elapsed time.
 *
 * Reliable observations use critical landmarks for each step: OPEN_PALM and BEND_FINGERTIPS use the wrist and
 * four finger chains; CLOSE_FINGERS uses four finger chains; THUMB_ON_TOP uses thumb landmarks plus index and
 * middle MCP knuckles; FRONT_TWO_KNUCKLES uses index and middle MCP knuckles. OBSERVED and INTERPOLATED
 * landmark provenance can make a frame reliable, while predicted-only data is explicitly excluded so tracking
 * guesses cannot complete a step. Acceptance is emitted once via [TemporalStepResult.newlyAccepted] and then
 * remains accepted until [reset], [resetForStep], a step/hand change, backwards timestamp, or an excessive
 * frame gap resets isolated state.
 */
class TemporalStepVerifier(
    private val configuration: TemporalVerificationConfiguration = TemporalVerificationConfiguration(),
) {
    private var activeStep: HandLessonStep? = null
    private var activeHandedness: Handedness? = null
    private var lastTimestampMs: Long? = null
    private var accumulatedMatchingMs: Long = 0
    private var reliableMatchingMs: Long = 0
    private var progressOverride: Float? = null
    private var accepted: Boolean = false
    private var missingMs: Long = 0
    private var partialMs: Long = 0

    fun update(frame: TrackedHandFrame, instantResult: InstantStepResult): TemporalStepResult {
        if (activeStep != instantResult.step || activeHandedness != frame.handedness) clearFor(instantResult.step, frame.handedness)
        val previousTimestamp = lastTimestampMs
        var elapsed = previousTimestamp?.let { frame.timestampMs - it } ?: 0L
        if (elapsed < 0 || elapsed > safeMaximumFrameGap()) {
            clearFor(instantResult.step, frame.handedness)
            elapsed = 0L
        }
        lastTimestampMs = frame.timestampMs

        val status = when (instantResult.status) {
            InstantVerificationStatus.MATCHING -> applyMatching(frame, instantResult, elapsed)
            InstantVerificationStatus.PARTIAL_MATCH -> applyPartial(elapsed)
            InstantVerificationStatus.INSUFFICIENT_DATA -> applyMissing(elapsed)
            InstantVerificationStatus.NOT_MATCHING -> applyDecay(elapsed)
        }
        val canAccept = !accepted && instantResult.status == InstantVerificationStatus.MATCHING &&
            instantResult.feedbackCode == FeedbackCode.GOOD && progress() >= 1f &&
            accumulatedMatchingMs >= requiredHold() && reliableRatio() >= configuration.minimumReliableMatchingRatio &&
            instantResult.quality.isFinite() && instantResult.quality >= configuration.minimumAcceptedQuality
        val newlyAccepted = canAccept
        if (canAccept) accepted = true
        return TemporalStepResult(
            step = instantResult.step,
            status = if (accepted) TemporalVerificationStatus.ACCEPTED else status,
            progress = progress(),
            accepted = accepted,
            newlyAccepted = newlyAccepted,
            feedbackCode = instantResult.feedbackCode,
            latestInstantResult = instantResult,
            accumulatedMatchingMs = accumulatedMatchingMs,
            reliableMatchingMs = reliableMatchingMs,
        )
    }

    fun reset() = clearFor(null, null)

    fun resetForStep(step: HandLessonStep) = clearFor(step, activeHandedness)

    private fun applyMatching(frame: TrackedHandFrame, result: InstantStepResult, elapsed: Long): TemporalVerificationStatus {
        missingMs = 0; partialMs = 0
        if (result.feedbackCode == FeedbackCode.GOOD) {
            progressOverride?.let { reliableMatchingMs = (it * requiredHold()).toLong().coerceAtLeast(0) }
            accumulatedMatchingMs += elapsed.coerceAtLeast(0)
            if (isReliable(frame, result)) reliableMatchingMs += elapsed.coerceAtLeast(0)
            progressOverride = null
        }
        return if (progress() >= 1f) TemporalVerificationStatus.HOLDING else TemporalVerificationStatus.BUILDING_PROGRESS
    }

    private fun applyPartial(elapsed: Long): TemporalVerificationStatus {
        missingMs = 0; partialMs += elapsed.coerceAtLeast(0)
        if (partialMs <= configuration.partialMatchGracePeriodMs) return TemporalVerificationStatus.PAUSED
        progressOverride = (progress() + elapsed * configuration.partialProgressMultiplier / requiredHold()).coerceIn(0f, 0.99f)
        decay(elapsed)
        return if (progress() > 0f) TemporalVerificationStatus.LOSING_PROGRESS else TemporalVerificationStatus.WAITING_FOR_DATA
    }

    private fun applyMissing(elapsed: Long): TemporalVerificationStatus {
        partialMs = 0; missingMs += elapsed.coerceAtLeast(0)
        if (missingMs <= configuration.missingDataGracePeriodMs) return TemporalVerificationStatus.PAUSED
        decay(elapsed)
        return if (progress() > 0f) TemporalVerificationStatus.LOSING_PROGRESS else TemporalVerificationStatus.WAITING_FOR_DATA
    }

    private fun applyDecay(elapsed: Long): TemporalVerificationStatus { missingMs = 0; partialMs = 0; decay(elapsed); return if (progress() > 0f) TemporalVerificationStatus.LOSING_PROGRESS else TemporalVerificationStatus.WAITING_FOR_DATA }

    private fun decay(elapsed: Long) { progressOverride = (progress() - elapsed.coerceAtLeast(0) / 1000f * configuration.progressDecayPerSecond).coerceIn(0f, 1f) }
    private fun progress(): Float = (progressOverride ?: (reliableMatchingMs.toFloat() / requiredHold().toFloat())).takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    private fun requiredHold(): Long = configuration.requiredHoldDurationMs.coerceAtLeast(1)
    private fun safeMaximumFrameGap(): Long = configuration.maximumFrameGapMs.coerceAtLeast(0)
    private fun reliableRatio(): Float = if (accumulatedMatchingMs <= 0) 0f else (reliableMatchingMs.toFloat() / accumulatedMatchingMs.toFloat()).takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    private fun isReliable(frame: TrackedHandFrame, result: InstantStepResult): Boolean = result.quality >= configuration.minimumAcceptedQuality && result.criticalLandmarksVisible && provenanceSummary(result.step, frame).let { it.visible && !it.predictedOnly && it.reliability >= configuration.minimumAcceptedQuality }
    private fun clearFor(step: HandLessonStep?, handedness: Handedness?) { activeStep = step; activeHandedness = handedness; lastTimestampMs = null; accumulatedMatchingMs = 0; reliableMatchingMs = 0; progressOverride = null; accepted = false; missingMs = 0; partialMs = 0 }
}

fun provenanceSummary(step: HandLessonStep, frame: TrackedHandFrame): LandmarkProvenanceSummary {
    val ids = criticalLandmarksFor(step)
    val samples = ids.map { frame.landmarks[it] }
    val visible = samples.all { it?.position != null && it.source != LandmarkSource.MISSING }
    val weights = samples.map { sourceWeight(it?.source ?: LandmarkSource.MISSING) }
    val reliability = weights.average().takeIf { it.isFinite() }?.toFloat()?.coerceIn(0f, 1f) ?: 0f
    val predictedOnly = samples.isNotEmpty() && samples.all { it?.source == LandmarkSource.PREDICTED }
    return LandmarkProvenanceSummary(ids, visible, reliability, predictedOnly)
}

fun criticalLandmarksFor(step: HandLessonStep): Set<HandLandmarkId> = when (step) {
    HandLessonStep.OPEN_PALM, HandLessonStep.BEND_FINGERTIPS -> setOf(HandLandmarkId.WRIST) + temporalFourFingerIds
    HandLessonStep.CLOSE_FINGERS -> temporalFourFingerIds
    HandLessonStep.THUMB_ON_TOP -> temporalThumbIds + setOf(HandLandmarkId.INDEX_MCP, HandLandmarkId.MIDDLE_MCP)
    HandLessonStep.FRONT_TWO_KNUCKLES -> setOf(HandLandmarkId.INDEX_MCP, HandLandmarkId.MIDDLE_MCP)
}

private fun sourceWeight(source: LandmarkSource): Float = when (source) { LandmarkSource.OBSERVED -> 1f; LandmarkSource.INTERPOLATED -> 0.75f; LandmarkSource.PREDICTED -> 0.35f; LandmarkSource.MISSING -> 0f }
private val temporalThumbIds = setOf(HandLandmarkId.THUMB_CMC, HandLandmarkId.THUMB_MCP, HandLandmarkId.THUMB_IP, HandLandmarkId.THUMB_TIP)
private val temporalFourFingerIds = HandLandmarkId.entries.filter { it.name.startsWith("INDEX_") || it.name.startsWith("MIDDLE_") || it.name.startsWith("RING_") || it.name.startsWith("LITTLE_") }.toSet()
