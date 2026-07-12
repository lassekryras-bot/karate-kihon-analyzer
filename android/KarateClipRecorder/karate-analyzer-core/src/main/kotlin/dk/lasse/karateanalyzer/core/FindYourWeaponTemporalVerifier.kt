package dk.lasse.karateanalyzer.core

/**
 * Stateful temporal acceptance for Find Your Weapon steps.
 *
 * This accumulator consumes an already-computed [InstantStepResult] so callers do not duplicate
 * feature extraction or instant verification. It keeps raw matching time, weighted reliable
 * matching time, and decaying hold credit separate; display-only partial progress is never
 * converted into reliable observation time.
 */
data class FindYourWeaponTemporalConfiguration(
    val requiredHoldDurationMs: Long = 600,
    val minimumReliableMatchingRatio: Double = 0.75,
    val minimumLatestQuality: Float = 0.70f,
    val maximumFrameGapMs: Long = 250,
    val missingDataGracePeriodMs: Long = 120,
    val partialMatchGracePeriodMs: Long = 120,
    val progressDecayPerSecond: Double = 1.0,
    val partialDisplayCreditRatio: Float = 0.35f,
)

enum class TemporalVerificationStatus {
    WAITING_FOR_DATA,
    BUILDING_PROGRESS,
    HOLDING,
    PAUSED,
    LOSING_PROGRESS,
    ACCEPTED,
}

data class TemporalStepResult(
    val status: TemporalVerificationStatus,
    val latestInstantResult: InstantStepResult,
    val accepted: Boolean,
    val newlyAccepted: Boolean,
    val progress: Float,
    val accumulatedMatchingMs: Double,
    val reliableMatchingMs: Double,
    val reliableHoldCreditMs: Double,
    val weightedReliableRatio: Double,
)

class FindYourWeaponTemporalVerifier(
    private val configuration: FindYourWeaponTemporalConfiguration = FindYourWeaponTemporalConfiguration(),
) {
    init {
        require(configuration.requiredHoldDurationMs > 0) { "requiredHoldDurationMs must be > 0" }
        require(configuration.minimumReliableMatchingRatio.isFinite() && configuration.minimumReliableMatchingRatio in 0.0..1.0) { "minimumReliableMatchingRatio must be finite and within 0..1" }
        require(configuration.minimumLatestQuality.isFinite() && configuration.minimumLatestQuality in 0f..1f) { "minimumLatestQuality must be finite and within 0..1" }
        require(configuration.maximumFrameGapMs >= 0) { "maximumFrameGapMs must be >= 0" }
        require(configuration.missingDataGracePeriodMs >= 0) { "missingDataGracePeriodMs must be >= 0" }
        require(configuration.partialMatchGracePeriodMs >= 0) { "partialMatchGracePeriodMs must be >= 0" }
        require(configuration.progressDecayPerSecond.isFinite() && configuration.progressDecayPerSecond >= 0.0) { "progressDecayPerSecond must be finite and >= 0" }
        require(configuration.partialDisplayCreditRatio.isFinite() && configuration.partialDisplayCreditRatio in 0f..1f) { "partialDisplayCreditRatio must be finite and within 0..1" }
    }

    private var state = TemporalAcceptanceState()

    fun reset() {
        state = TemporalAcceptanceState()
    }

    fun resetForStep(step: HandLessonStep) {
        state = TemporalAcceptanceState(step = step)
    }

    fun update(frame: TrackedHandFrame, instantResult: InstantStepResult): TemporalStepResult {
        val step = instantResult.step
        val handedness = frame.handedness
        val timestampMs = frame.timestampMs

        if (state.step != step || state.handedness != handedness) {
            val currentFrameReliability = frameReliability(step, frame, instantResult)
            state = TemporalAcceptanceState(
                step = step,
                handedness = handedness,
                lastTimestampMs = timestampMs,
                previousMatchingGood = isMatchingGood(instantResult),
                previousFrameReliability = currentFrameReliability,
            )
            return result(
                instantResult,
                newlyAccepted = false,
                status = initialStatus(instantResult, currentFrameReliability),
            )
        }

        val elapsedMs = elapsedSinceLast(timestampMs)
        if (elapsedMs == null || elapsedMs > configuration.maximumFrameGapMs) {
            val currentFrameReliability = frameReliability(step, frame, instantResult)
            state = TemporalAcceptanceState(
                step = step,
                handedness = handedness,
                lastTimestampMs = timestampMs,
                previousMatchingGood = isMatchingGood(instantResult),
                previousFrameReliability = currentFrameReliability,
            )
            return result(
                instantResult,
                newlyAccepted = false,
                status = initialStatus(instantResult, currentFrameReliability),
            )
        }
        state.lastTimestampMs = timestampMs

        if (state.accepted) {
            return result(instantResult, newlyAccepted = false, forcedProgress = 1f, status = TemporalVerificationStatus.ACCEPTED)
        }

        val matchingGood = isMatchingGood(instantResult)
        val continuousMatching = matchingGood && state.previousMatchingGood
        var decayed = false
        var paused = false
        var addedReliableCredit = false
        val currentFrameReliability = frameReliability(step, frame, instantResult)
        if (continuousMatching) {
            state.missingDataMs = 0.0
            state.partialMatchMs = 0.0
            state.accumulatedMatchingMs += elapsedMs
            state.activeAttemptMatchingMs += elapsedMs
            val intervalReliability = minOf(state.previousFrameReliability, currentFrameReliability)
            if (intervalReliability > 0.0) {
                val weightedReliableDeltaMs = elapsedMs * intervalReliability
                state.reliableMatchingMs += weightedReliableDeltaMs
                state.activeAttemptReliableMs += weightedReliableDeltaMs
                state.reliableHoldCreditMs += weightedReliableDeltaMs
                addedReliableCredit = weightedReliableDeltaMs > 0.0
            }
        } else {
            val interruption = applyNonMatchingElapsed(elapsedMs, instantResult)
            decayed = interruption.decayed
            paused = interruption.paused
        }

        val ratio = state.weightedReliableRatio()
        val latestQualitySufficient = latestQualitySufficient(step, frame, instantResult)
        val shouldAccept = matchingGood && latestQualitySufficient &&
            state.reliableHoldCreditMs >= configuration.requiredHoldDurationMs.toDouble() &&
            state.activeAttemptMatchingMs >= configuration.requiredHoldDurationMs.toDouble() &&
            ratio >= configuration.minimumReliableMatchingRatio

        state.previousMatchingGood = matchingGood
        state.previousFrameReliability = currentFrameReliability
        if (shouldAccept) {
            state.accepted = true
            return result(instantResult, newlyAccepted = true, forcedProgress = 1f, status = TemporalVerificationStatus.ACCEPTED)
        }

        return result(
            instant = instantResult,
            newlyAccepted = false,
            status = currentStatus(
                addedReliableCredit = addedReliableCredit,
                paused = paused || (matchingGood && currentFrameReliability == 0.0),
                decayed = decayed,
            ),
        )
    }

    private fun initialStatus(instantResult: InstantStepResult, frameReliability: Double): TemporalVerificationStatus =
        if (isMatchingGood(instantResult) && frameReliability == 0.0) {
            TemporalVerificationStatus.PAUSED
        } else {
            TemporalVerificationStatus.WAITING_FOR_DATA
        }

    private fun applyNonMatchingElapsed(elapsedMs: Double, instantResult: InstantStepResult): InterruptionUpdate {
        val decayElapsedMs = when (instantResult.status) {
            InstantVerificationStatus.PARTIAL_MATCH -> {
                val previous = state.partialMatchMs
                state.partialMatchMs += elapsedMs
                state.missingDataMs = 0.0
                elapsedBeyondGrace(previous, state.partialMatchMs, configuration.partialMatchGracePeriodMs.toDouble())
            }
            InstantVerificationStatus.INSUFFICIENT_DATA -> {
                val previous = state.missingDataMs
                state.missingDataMs += elapsedMs
                state.partialMatchMs = 0.0
                elapsedBeyondGrace(previous, state.missingDataMs, configuration.missingDataGracePeriodMs.toDouble())
            }
            InstantVerificationStatus.NOT_MATCHING -> {
                state.missingDataMs = 0.0
                state.partialMatchMs = 0.0
                elapsedMs
            }
            InstantVerificationStatus.MATCHING -> {
                state.missingDataMs = 0.0
                state.partialMatchMs = 0.0
                0.0
            }
        }
        val decayCreditMs = configuration.requiredHoldDurationMs.toDouble() *
            configuration.progressDecayPerSecond *
            (decayElapsedMs / 1000.0)
        val previousCredit = state.reliableHoldCreditMs
        state.reliableHoldCreditMs = (state.reliableHoldCreditMs - decayCreditMs).takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
        if (state.reliableHoldCreditMs == 0.0) {
            state.activeAttemptMatchingMs = 0.0
            state.activeAttemptReliableMs = 0.0
            state.previousMatchingGood = false
            state.previousFrameReliability = 0.0
        }
        return InterruptionUpdate(
            paused = decayElapsedMs == 0.0 && instantResult.status in setOf(InstantVerificationStatus.PARTIAL_MATCH, InstantVerificationStatus.INSUFFICIENT_DATA),
            decayed = state.reliableHoldCreditMs < previousCredit,
        )
    }

    private fun frameReliability(step: HandLessonStep, frame: TrackedHandFrame, instantResult: InstantStepResult): Double =
        if (isMatchingGood(instantResult) && latestQualitySufficient(step, frame, instantResult)) {
            provenanceReliability(step, frame).coerceIn(0.0, 1.0)
        } else {
            0.0
        }

    private fun latestQualitySufficient(step: HandLessonStep, frame: TrackedHandFrame, instantResult: InstantStepResult): Boolean =
        instantResult.quality.isFinite() &&
            instantResult.quality >= configuration.minimumLatestQuality &&
            instantResult.criticalLandmarksVisible &&
            criticalLandmarksReliable(step, frame)

    private fun currentStatus(addedReliableCredit: Boolean, paused: Boolean, decayed: Boolean): TemporalVerificationStatus = when {
        state.accepted -> TemporalVerificationStatus.ACCEPTED
        decayed -> TemporalVerificationStatus.LOSING_PROGRESS
        paused -> TemporalVerificationStatus.PAUSED
        state.reliableHoldCreditMs >= configuration.requiredHoldDurationMs.toDouble() -> TemporalVerificationStatus.HOLDING
        addedReliableCredit -> TemporalVerificationStatus.BUILDING_PROGRESS
        state.reliableHoldCreditMs > 0.0 -> TemporalVerificationStatus.PAUSED
        else -> TemporalVerificationStatus.WAITING_FOR_DATA
    }

    private fun elapsedBeyondGrace(previousStreakMs: Double, currentStreakMs: Double, graceMs: Double): Double =
        (currentStreakMs - graceMs).coerceAtLeast(0.0) - (previousStreakMs - graceMs).coerceAtLeast(0.0)

    private fun isMatchingGood(instant: InstantStepResult): Boolean =
        instant.status == InstantVerificationStatus.MATCHING && instant.feedbackCode == FeedbackCode.GOOD

    private fun elapsedSinceLast(timestampMs: Long): Double? {
        val previous = state.lastTimestampMs
        if (previous == null) return 0.0
        if (timestampMs < previous) return null
        return (timestampMs - previous).toDouble()
    }

    private fun result(
        instant: InstantStepResult,
        newlyAccepted: Boolean,
        forcedProgress: Float? = null,
        status: TemporalVerificationStatus = currentStatus(addedReliableCredit = false, paused = false, decayed = false),
    ): TemporalStepResult {
        val reliableProgress = (state.reliableHoldCreditMs / configuration.requiredHoldDurationMs.toDouble()).coerceIn(0.0, 1.0).toFloat()
        val safeScore = instant.score.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        val partialDisplayProgress = if (instant.status == InstantVerificationStatus.PARTIAL_MATCH) {
            safeScore * configuration.partialDisplayCreditRatio.coerceIn(0f, 1f)
        } else {
            0f
        }
        val progress = (forcedProgress ?: maxOf(reliableProgress, partialDisplayProgress)).takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        return TemporalStepResult(
            status = status,
            latestInstantResult = instant,
            accepted = state.accepted,
            newlyAccepted = newlyAccepted,
            progress = progress,
            accumulatedMatchingMs = state.accumulatedMatchingMs,
            reliableMatchingMs = state.reliableMatchingMs,
            reliableHoldCreditMs = state.reliableHoldCreditMs,
            weightedReliableRatio = state.weightedReliableRatio().coerceIn(0.0, 1.0),
        )
    }

    private fun criticalLandmarksReliable(step: HandLessonStep, frame: TrackedHandFrame): Boolean =
        criticalLandmarksFor(step).all { id ->
            val sample = frame.landmarks[id]
            sample?.position != null && sample.source != LandmarkSource.MISSING && sample.source != LandmarkSource.PREDICTED
        }

    private fun provenanceReliability(step: HandLessonStep, frame: TrackedHandFrame): Double =
        criticalLandmarksFor(step).map { id ->
            when (frame.landmarks[id]?.source ?: LandmarkSource.MISSING) {
                LandmarkSource.OBSERVED -> 1.0
                LandmarkSource.INTERPOLATED -> 0.75
                LandmarkSource.PREDICTED -> 0.0
                LandmarkSource.MISSING -> 0.0
            }
        }.average().takeIf { it.isFinite() } ?: 0.0

    private fun criticalLandmarksFor(step: HandLessonStep): List<HandLandmarkId> = when (step) {
        HandLessonStep.OPEN_PALM -> listOf(HandLandmarkId.WRIST) + temporalFourFingerCriticalLandmarks
        HandLessonStep.BEND_FINGERTIPS -> temporalFourFingerCriticalLandmarks
        HandLessonStep.CLOSE_FINGERS -> temporalFourFingerCriticalLandmarks
        HandLessonStep.THUMB_ON_TOP -> temporalFourFingerCriticalLandmarks + temporalThumbCriticalLandmarks
        HandLessonStep.FRONT_TWO_KNUCKLES -> temporalFourFingerCriticalLandmarks + temporalThumbCriticalLandmarks
    }
}

private data class InterruptionUpdate(
    val paused: Boolean,
    val decayed: Boolean,
)

private data class TemporalAcceptanceState(
    val step: HandLessonStep? = null,
    val handedness: Handedness? = null,
    var lastTimestampMs: Long? = null,
    var accumulatedMatchingMs: Double = 0.0,
    var reliableMatchingMs: Double = 0.0,
    var activeAttemptMatchingMs: Double = 0.0,
    var activeAttemptReliableMs: Double = 0.0,
    var reliableHoldCreditMs: Double = 0.0,
    var accepted: Boolean = false,
    var previousMatchingGood: Boolean = false,
    var previousFrameReliability: Double = 0.0,
    var missingDataMs: Double = 0.0,
    var partialMatchMs: Double = 0.0,
) {
    fun weightedReliableRatio(): Double = if (activeAttemptMatchingMs <= 0.0) 0.0 else (activeAttemptReliableMs / activeAttemptMatchingMs).takeIf { it.isFinite() } ?: 0.0
}

private val temporalFourFingerCriticalLandmarks = listOf(
    HandLandmarkId.INDEX_MCP,
    HandLandmarkId.INDEX_PIP,
    HandLandmarkId.INDEX_DIP,
    HandLandmarkId.INDEX_TIP,
    HandLandmarkId.MIDDLE_MCP,
    HandLandmarkId.MIDDLE_PIP,
    HandLandmarkId.MIDDLE_DIP,
    HandLandmarkId.MIDDLE_TIP,
    HandLandmarkId.RING_MCP,
    HandLandmarkId.RING_PIP,
    HandLandmarkId.RING_DIP,
    HandLandmarkId.RING_TIP,
    HandLandmarkId.LITTLE_MCP,
    HandLandmarkId.LITTLE_PIP,
    HandLandmarkId.LITTLE_DIP,
    HandLandmarkId.LITTLE_TIP,
)

private val temporalThumbCriticalLandmarks = listOf(
    HandLandmarkId.THUMB_CMC,
    HandLandmarkId.THUMB_MCP,
    HandLandmarkId.THUMB_IP,
    HandLandmarkId.THUMB_TIP,
)
