package dk.lasse.karateanalyzer.observation

enum class ExpectedChangeStatus { WAITING_FOR_RESPONSE, CHANGE_IN_PROGRESS, WAITING_FOR_SETTLE, FULFILLED, TIMED_OUT }
enum class FeedbackSuppressionReason { RESPONSE_GRACE, CHANGE_IN_PROGRESS, WAITING_FOR_SETTLE, INSUFFICIENT_EVIDENCE, COOLDOWN, ALREADY_FULFILLED, TIMED_OUT }

data class FeedbackGateDecision(val allowed: Boolean, val suppressionReason: FeedbackSuppressionReason? = null)

data class ExpectedChangeSnapshot<I>(
    val changeId: I,
    val issuedAtMs: Long,
    val earliestReevaluationAtMs: Long,
    val deadlineMs: Long?,
    val responseObserved: Boolean,
    val status: ExpectedChangeStatus,
    val feedbackGate: FeedbackGateDecision,
)

class ExpectedChange<I>(
    private val changeId: I,
    private val issuedAtMs: Long,
    responseGraceMs: Long,
    timeoutMs: Long? = null,
    private val feedbackCooldownMs: Long = 0L,
) {
    init {
        require(responseGraceMs >= 0L)
        require(timeoutMs == null || timeoutMs >= 0L)
        require(feedbackCooldownMs >= 0L)
    }

    private val earliestReevaluationAtMs = Math.addExact(issuedAtMs, responseGraceMs)
    private val deadlineMs = timeoutMs?.let { Math.addExact(issuedAtMs, it) }
    private var lastTimestampMs: Long? = null
    private var responseObserved = false
    private var fulfilled = false
    private var latestStatus = ExpectedChangeStatus.WAITING_FOR_RESPONSE

    fun update(
        timestampMs: Long,
        changeEvidence: Evidence,
        settledEvidence: Evidence,
        fulfillmentEvidence: Evidence,
    ): ExpectedChangeSnapshot<I> {
        require(timestampMs >= issuedAtMs)
        requireIncreasing(lastTimestampMs, timestampMs)
        lastTimestampMs = timestampMs

        if (changeEvidence == Evidence.TRUE) responseObserved = true
        fulfilled = fulfilled || (responseObserved && settledEvidence == Evidence.TRUE && fulfillmentEvidence == Evidence.TRUE)
        latestStatus = when {
            fulfilled -> ExpectedChangeStatus.FULFILLED
            deadlineMs != null && timestampMs >= deadlineMs -> ExpectedChangeStatus.TIMED_OUT
            changeEvidence == Evidence.TRUE -> ExpectedChangeStatus.CHANGE_IN_PROGRESS
            responseObserved && settledEvidence != Evidence.TRUE -> ExpectedChangeStatus.WAITING_FOR_SETTLE
            else -> ExpectedChangeStatus.WAITING_FOR_RESPONSE
        }
        return snapshot(timestampMs, changeEvidence, settledEvidence, fulfillmentEvidence)
    }

    fun reset() {
        lastTimestampMs = null
        responseObserved = false
        fulfilled = false
        latestStatus = ExpectedChangeStatus.WAITING_FOR_RESPONSE
    }

    private fun snapshot(
        timestampMs: Long,
        changeEvidence: Evidence,
        settledEvidence: Evidence,
        fulfillmentEvidence: Evidence,
    ) = ExpectedChangeSnapshot(
        changeId = changeId,
        issuedAtMs = issuedAtMs,
        earliestReevaluationAtMs = earliestReevaluationAtMs,
        deadlineMs = deadlineMs,
        responseObserved = responseObserved,
        status = latestStatus,
        feedbackGate = feedbackGate(timestampMs, changeEvidence, settledEvidence, fulfillmentEvidence),
    )

    private fun feedbackGate(
        timestampMs: Long,
        changeEvidence: Evidence,
        settledEvidence: Evidence,
        fulfillmentEvidence: Evidence,
    ): FeedbackGateDecision {
        val reason = when {
            latestStatus == ExpectedChangeStatus.FULFILLED -> FeedbackSuppressionReason.ALREADY_FULFILLED
            latestStatus == ExpectedChangeStatus.TIMED_OUT -> FeedbackSuppressionReason.TIMED_OUT
            timestampMs < earliestReevaluationAtMs -> FeedbackSuppressionReason.RESPONSE_GRACE
            changeEvidence == Evidence.TRUE -> FeedbackSuppressionReason.CHANGE_IN_PROGRESS
            responseObserved && settledEvidence == Evidence.UNKNOWN -> FeedbackSuppressionReason.INSUFFICIENT_EVIDENCE
            responseObserved && settledEvidence == Evidence.FALSE -> FeedbackSuppressionReason.WAITING_FOR_SETTLE
            fulfillmentEvidence == Evidence.UNKNOWN -> FeedbackSuppressionReason.INSUFFICIENT_EVIDENCE
            timestampMs < issuedAtMs + feedbackCooldownMs -> FeedbackSuppressionReason.COOLDOWN
            else -> null
        }
        return FeedbackGateDecision(reason == null, reason)
    }
}
