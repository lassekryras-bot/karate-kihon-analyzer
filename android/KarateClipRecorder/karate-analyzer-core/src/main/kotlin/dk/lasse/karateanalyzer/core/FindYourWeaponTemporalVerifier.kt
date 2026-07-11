package dk.lasse.karateanalyzer.core

/**
 * Stateful temporal acceptance for Find Your Weapon steps.
 *
 * This class keeps raw matching time, weighted reliable matching time, and decaying hold credit as
 * separate values. Display-only partial progress is never converted into reliable observation time.
 */
data class FindYourWeaponTemporalConfiguration(
    val requiredHoldDurationMs: Long = 600,
    val minimumReliableMatchingRatio: Double = 0.75,
    val minimumLatestQuality: Float = 0.70f,
    val incorrectFrameDecayRatio: Double = 1.0,
    val partialDisplayCreditRatio: Float = 0.35f,
)

data class TemporalStepResult(
    val instant: InstantStepResult?,
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
    private val verifier: FindYourWeaponVerifier = FindYourWeaponVerifier(),
    private val extractor: HandFeatureExtractor = HandFeatureExtractor(),
) {
    private var state = TemporalAcceptanceState()

    fun reset() {
        state = TemporalAcceptanceState()
    }

    fun update(step: HandLessonStep, frame: TrackedHandFrame?): TemporalStepResult {
        val instant = frame?.let { verifier.verify(step, it, extractor.extract(it)) }
        val hand = frame?.handedness
        val timestampMs = frame?.timestampMs

        if (state.step != step || state.handedness != hand) {
            state = TemporalAcceptanceState(step = step, handedness = hand, lastTimestampMs = timestampMs, previousMatchingGood = isMatchingGood(instant))
            return result(instant, newlyAccepted = false)
        }

        val elapsedMs = elapsedSinceLast(timestampMs)
        state.lastTimestampMs = timestampMs

        if (state.accepted) {
            return result(instant, newlyAccepted = false, forcedProgress = 1f)
        }

        val matchingGood = isMatchingGood(instant)
        val continuousMatching = matchingGood && state.previousMatchingGood
        if (continuousMatching && frame != null) {
            val reliability = provenanceReliability(step, frame)
            val weightedReliableDeltaMs = elapsedMs * reliability
            state.accumulatedMatchingMs += elapsedMs
            state.reliableMatchingMs += weightedReliableDeltaMs
            state.reliableHoldCreditMs += weightedReliableDeltaMs
        } else {
            val decayMs = elapsedMs * configuration.incorrectFrameDecayRatio.coerceAtLeast(0.0)
            state.reliableHoldCreditMs = (state.reliableHoldCreditMs - decayMs).coerceAtLeast(0.0)
        }

        val ratio = state.weightedReliableRatio()
        val latestQualitySufficient = (instant?.quality ?: 0f) >= configuration.minimumLatestQuality &&
            frame?.let { criticalLandmarksReliable(step, it) } == true
        val shouldAccept = matchingGood && latestQualitySufficient &&
            state.reliableHoldCreditMs >= configuration.requiredHoldDurationMs.toDouble() &&
            state.accumulatedMatchingMs >= configuration.requiredHoldDurationMs.toDouble() &&
            ratio >= configuration.minimumReliableMatchingRatio

        state.previousMatchingGood = matchingGood
        if (shouldAccept) {
            state.accepted = true
            return result(instant, newlyAccepted = true, forcedProgress = 1f)
        }

        return result(instant, newlyAccepted = false)
    }

    private fun isMatchingGood(instant: InstantStepResult?): Boolean =
        instant?.status == InstantVerificationStatus.MATCHING && instant.feedbackCode == FeedbackCode.GOOD

    private fun elapsedSinceLast(timestampMs: Long?): Double {
        val previous = state.lastTimestampMs
        return if (timestampMs == null || previous == null) 0.0 else (timestampMs - previous).coerceAtLeast(0L).toDouble()
    }

    private fun result(
        instant: InstantStepResult?,
        newlyAccepted: Boolean,
        forcedProgress: Float? = null,
    ): TemporalStepResult {
        val reliableProgress = (state.reliableHoldCreditMs / configuration.requiredHoldDurationMs.toDouble()).coerceIn(0.0, 1.0).toFloat()
        val partialDisplayProgress = if (instant?.status == InstantVerificationStatus.PARTIAL_MATCH) {
            instant.score.coerceIn(0f, 1f) * configuration.partialDisplayCreditRatio.coerceIn(0f, 1f)
        } else {
            0f
        }
        val progress = forcedProgress ?: maxOf(reliableProgress, partialDisplayProgress).coerceIn(0f, 1f)
        return TemporalStepResult(
            instant = instant,
            accepted = state.accepted,
            newlyAccepted = newlyAccepted,
            progress = progress,
            accumulatedMatchingMs = state.accumulatedMatchingMs,
            reliableMatchingMs = state.reliableMatchingMs,
            reliableHoldCreditMs = state.reliableHoldCreditMs,
            weightedReliableRatio = state.weightedReliableRatio(),
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
        HandLessonStep.OPEN_PALM -> listOf(HandLandmarkId.WRIST) + fourFingerCriticalLandmarks
        HandLessonStep.BEND_FINGERTIPS -> fourFingerCriticalLandmarks
        HandLessonStep.CLOSE_FINGERS -> fourFingerCriticalLandmarks
        HandLessonStep.THUMB_ON_TOP -> fourFingerCriticalLandmarks + thumbCriticalLandmarks
        HandLessonStep.FRONT_TWO_KNUCKLES -> fourFingerCriticalLandmarks
    }
}

private data class TemporalAcceptanceState(
    val step: HandLessonStep? = null,
    val handedness: Handedness? = null,
    var lastTimestampMs: Long? = null,
    var accumulatedMatchingMs: Double = 0.0,
    var reliableMatchingMs: Double = 0.0,
    var reliableHoldCreditMs: Double = 0.0,
    var accepted: Boolean = false,
    var previousMatchingGood: Boolean = false,
) {
    fun weightedReliableRatio(): Double = if (accumulatedMatchingMs <= 0.0) 0.0 else reliableMatchingMs / accumulatedMatchingMs
}

internal val fourFingerCriticalLandmarks = listOf(
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

internal val thumbCriticalLandmarks = listOf(
    HandLandmarkId.THUMB_CMC,
    HandLandmarkId.THUMB_MCP,
    HandLandmarkId.THUMB_IP,
    HandLandmarkId.THUMB_TIP,
)
