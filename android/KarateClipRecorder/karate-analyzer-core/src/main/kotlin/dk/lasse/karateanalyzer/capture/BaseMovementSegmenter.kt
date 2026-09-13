package dk.lasse.karateanalyzer.capture

/**
 * Minimal, generic movement segmenter for continuous karate capture.
 *
 * Driven by causal Top-2 mechanical evidence:
 * - Movement onset is strictly WHOLE-BODY: any limb positive motion starts a movement candidate.
 * - Movement completion is governed by the profile's [SettlingEvidenceScope] (e.g. WHOLE_BODY, UPPER_BODY).
 * - Settling requires qualifying quiet dwell within a trailing [BaseRecordingProfile.settlingWindowMs].
 * - Tolerates brief moving interruptions up to [BaseRecordingProfile.maxMovingInterruptionMs].
 * - When completing, terminal end boundary is placed at the start of the latest qualifying quiet run,
 *   never backdating across a moving interruption.
 * - Immediately re-arms for the next movement upon completion without dead-time.
 */
class BaseMovementSegmenter(
    val profile: BaseRecordingProfile = BaseRecordingProfile(),
) {
    var state: BaseSegmentState = BaseSegmentState.ARMED
        private set

    var currentMovementNumber: Int = 1
        private set

    private var previousTimestampMs: Long? = null
    private var movingStartCandidateMs: Long? = null
    private var activeStartBoundaryMs: Long? = null

    private data class SettlingSample(
        val timestampMs: Long,
        val decision: KinematicDecisionState,
    )
    private val settlingHistory = mutableListOf<SettlingSample>()

    fun accept(
        timestampMs: Long,
        startTranslationEvidence: Double?,
        startAngularEvidence: Double?,
        settlingTranslationEvidence: Double? = startTranslationEvidence,
        settlingAngularEvidence: Double? = startAngularEvidence,
    ): BaseSegmenterSnapshot {
        val prev = previousTimestampMs
        if (prev != null && timestampMs <= prev) {
            throw IllegalArgumentException("Timestamps must be strictly increasing: previous=$prev, current=$timestampMs")
        }
        previousTimestampMs = timestampMs

        // 1. Classify start evidence (whole-body)
        val startTransDecision = classifyTranslation(startTranslationEvidence)
        val startAngDecision = classifyAngular(startAngularEvidence)
        val isMovingStart = (startTransDecision == KinematicDecisionState.MOVING || startAngDecision == KinematicDecisionState.MOVING)

        // 2. Classify settling evidence (scoped)
        val settlingTransDecision = classifyTranslation(settlingTranslationEvidence)
        val settlingAngDecision = classifyAngular(settlingAngularEvidence)
        val isSettlingQuiet = (settlingTransDecision == KinematicDecisionState.QUIET && settlingAngDecision == KinematicDecisionState.QUIET)
        val isSettlingMoving = (settlingTransDecision == KinematicDecisionState.MOVING || settlingAngDecision == KinematicDecisionState.MOVING)
        val settlingDecision = when {
            settlingTransDecision == KinematicDecisionState.UNKNOWN || settlingAngDecision == KinematicDecisionState.UNKNOWN -> KinematicDecisionState.UNKNOWN
            isSettlingMoving -> KinematicDecisionState.MOVING
            isSettlingQuiet -> KinematicDecisionState.QUIET
            else -> KinematicDecisionState.MID
        }

        settlingHistory.add(SettlingSample(timestampMs, settlingDecision))
        val historyCutoff = timestampMs - (profile.settlingWindowMs + 100L)
        settlingHistory.removeAll { it.timestampMs < historyCutoff }

        var completedSegment: BaseCompletedSegment? = null
        var trigger: String? = null
        var candidateEndMs: Long? = null

        when (state) {
            BaseSegmentState.ARMED -> {
                if (isMovingStart) {
                    if (movingStartCandidateMs == null) {
                        movingStartCandidateMs = timestampMs
                        trigger = "positive_motion_candidate"
                    }
                    val elapsed = timestampMs - movingStartCandidateMs!!
                    if (elapsed >= profile.startDwellMs) {
                        state = BaseSegmentState.MOVING
                        activeStartBoundaryMs = movingStartCandidateMs
                        settlingHistory.clear()
                        settlingHistory.add(SettlingSample(timestampMs, settlingDecision))
                        trigger = "start_confirmed_50ms"
                    }
                } else {
                    movingStartCandidateMs = null
                }
            }

            BaseSegmentState.MOVING -> {
                val startMs = checkNotNull(activeStartBoundaryMs)
                val windowCutoff = timestampMs - profile.settlingWindowMs
                val activeSamples = settlingHistory.filter { it.timestampMs >= startMs && it.timestampMs >= windowCutoff }

                val windowSpan = if (activeSamples.isNotEmpty()) timestampMs - activeSamples.first().timestampMs else 0L
                val isWindowFull = windowSpan >= (profile.settlingWindowMs - 25L)
                val currentNotMoving = (settlingDecision != KinematicDecisionState.MOVING)

                var isSettled = false
                var terminalEndMs: Long? = null

                if (isWindowFull && currentNotMoving) {
                    var consecMovingMs = 0L
                    var totalMovingMs = 0L
                    var quietAccumMs = 0L
                    var hasUnknown = false

                    for (i in activeSamples.indices) {
                        val sample = activeSamples[i]
                        val sampleDt = if (i + 1 < activeSamples.size) {
                            activeSamples[i + 1].timestampMs - sample.timestampMs
                        } else if (i > 0) {
                            sample.timestampMs - activeSamples[i - 1].timestampMs
                        } else 20L

                        when (sample.decision) {
                            KinematicDecisionState.MOVING -> {
                                consecMovingMs += sampleDt
                                totalMovingMs += sampleDt
                                if (consecMovingMs > profile.maxMovingInterruptionMs || totalMovingMs > (profile.maxMovingInterruptionMs + 10L)) {
                                    break
                                }
                            }
                            KinematicDecisionState.QUIET -> {
                                consecMovingMs = 0L
                                quietAccumMs += sampleDt
                            }
                            KinematicDecisionState.MID -> {
                                consecMovingMs = 0L
                                // MID pauses quiet accumulation
                            }
                            KinematicDecisionState.UNKNOWN -> {
                                hasUnknown = true
                                break
                            }
                        }
                    }

                    if (!hasUnknown && consecMovingMs <= profile.maxMovingInterruptionMs &&
                        totalMovingMs <= (profile.maxMovingInterruptionMs + 10L) &&
                        quietAccumMs >= profile.quietDwellMs
                    ) {
                        isSettled = true

                        // End boundary: start of the latest quiet run associated with completion.
                        // Tracing backwards from current sample to prevent backdating across moving interruptions.
                        var latestQuietRunStart: Long? = null
                        for (i in activeSamples.indices.reversed()) {
                            val s = activeSamples[i]
                            if (s.decision == KinematicDecisionState.MOVING) {
                                break
                            }
                            if (s.decision == KinematicDecisionState.QUIET) {
                                latestQuietRunStart = s.timestampMs
                            }
                        }
                        terminalEndMs = latestQuietRunStart
                            ?: activeSamples.firstOrNull { it.decision == KinematicDecisionState.QUIET }?.timestampMs
                            ?: timestampMs
                    }
                }

                if (isSettled && terminalEndMs != null) {
                    val endMs = terminalEndMs
                    completedSegment = BaseCompletedSegment(
                        movementNumber = currentMovementNumber,
                        startBoundaryMs = startMs,
                        endBoundaryMs = endMs,
                        durationMs = endMs - startMs,
                        confirmationTimestampMs = timestampMs,
                    )
                    trigger = "terminal_quiet_confirmed_${profile.cadence.name.lowercase()}"

                    // Immediately re-arm for next movement
                    currentMovementNumber++
                    state = BaseSegmentState.ARMED
                    movingStartCandidateMs = if (isMovingStart) timestampMs else null
                    activeStartBoundaryMs = null
                    settlingHistory.clear()
                } else {
                    candidateEndMs = if (settlingDecision == KinematicDecisionState.QUIET) {
                        var runStart: Long? = null
                        for (i in settlingHistory.indices.reversed()) {
                            val s = settlingHistory[i]
                            if (s.decision != KinematicDecisionState.QUIET) break
                            runStart = s.timestampMs
                        }
                        runStart
                    } else null
                }
            }

            BaseSegmentState.COMPLETE -> {
                state = BaseSegmentState.ARMED
                movingStartCandidateMs = if (isMovingStart) timestampMs else null
                activeStartBoundaryMs = null
                settlingHistory.clear()
            }
        }

        return BaseSegmenterSnapshot(
            state = if (completedSegment != null) BaseSegmentState.COMPLETE else state,
            translationEvidence = startTranslationEvidence,
            angularEvidence = startAngularEvidence,
            translationDecision = startTransDecision,
            angularDecision = startAngDecision,
            movingStartCandidateMs = movingStartCandidateMs,
            quietEndCandidateMs = candidateEndMs,
            movementStartBoundaryMs = activeStartBoundaryMs ?: completedSegment?.startBoundaryMs,
            movementEndBoundaryMs = completedSegment?.endBoundaryMs,
            completedSegment = completedSegment,
            lastTrigger = trigger,
            settlingTranslationEvidence = settlingTranslationEvidence,
            settlingAngularEvidence = settlingAngularEvidence,
            settlingTranslationDecision = settlingTransDecision,
            settlingAngularDecision = settlingAngDecision,
        )
    }

    fun reset() {
        state = BaseSegmentState.ARMED
        currentMovementNumber = 1
        previousTimestampMs = null
        movingStartCandidateMs = null
        activeStartBoundaryMs = null
        settlingHistory.clear()
    }

    private fun classifyTranslation(evidence: Double?): KinematicDecisionState = when {
        evidence == null || !evidence.isFinite() -> KinematicDecisionState.UNKNOWN
        evidence >= profile.translationMovingThreshold -> KinematicDecisionState.MOVING
        evidence <= profile.translationQuietThreshold -> KinematicDecisionState.QUIET
        else -> KinematicDecisionState.MID
    }

    private fun classifyAngular(evidence: Double?): KinematicDecisionState = when {
        evidence == null || !evidence.isFinite() -> KinematicDecisionState.UNKNOWN
        evidence >= profile.angularMovingThreshold -> KinematicDecisionState.MOVING
        evidence <= profile.angularQuietThreshold -> KinematicDecisionState.QUIET
        else -> KinematicDecisionState.MID
    }
}

enum class BaseSegmentState {
    ARMED,
    MOVING,
    COMPLETE,
}

data class BaseCompletedSegment(
    val movementNumber: Int,
    val startBoundaryMs: Long,
    val endBoundaryMs: Long,
    val durationMs: Long,
    val confirmationTimestampMs: Long,
)

data class BaseSegmenterSnapshot(
    val state: BaseSegmentState,
    val translationEvidence: Double?,
    val angularEvidence: Double?,
    val translationDecision: KinematicDecisionState,
    val angularDecision: KinematicDecisionState,
    val movingStartCandidateMs: Long?,
    val quietEndCandidateMs: Long?,
    val movementStartBoundaryMs: Long?,
    val movementEndBoundaryMs: Long?,
    val completedSegment: BaseCompletedSegment?,
    val lastTrigger: String? = null,
    val settlingTranslationEvidence: Double? = translationEvidence,
    val settlingAngularEvidence: Double? = angularEvidence,
    val settlingTranslationDecision: KinematicDecisionState = translationDecision,
    val settlingAngularDecision: KinematicDecisionState = angularDecision,
)
