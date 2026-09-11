package dk.lasse.karateanalyzer.observation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GuidedCameraSetupProofTest {
    @Test fun oneCorrectionWaitsForMovementAndReliableSettleBeforeFulfillment() {
        val reducer = CameraSetupProofReducer(settleMs = 100, timeoutMs = 1_000)
        reducer.issueCorrection(CameraCorrection.MOVE_LEFT, 0)

        val grace = reducer.update(50, Evidence.FALSE, Evidence.UNKNOWN, Evidence.UNKNOWN)
        assertFalse(grace.feedbackGate.allowed)
        assertEquals(FeedbackSuppressionReason.RESPONSE_GRACE, grace.feedbackGate.suppressionReason)

        val moving = reducer.update(150, Evidence.TRUE, Evidence.FALSE, Evidence.FALSE)
        assertEquals(CameraProofState.PERSON_MOVING, moving.state)
        assertEquals(FeedbackSuppressionReason.CHANGE_IN_PROGRESS, moving.feedbackGate.suppressionReason)

        val unknown = reducer.update(200, Evidence.FALSE, Evidence.UNKNOWN, Evidence.UNKNOWN)
        assertEquals(CameraProofState.WAITING_FOR_SETTLE, unknown.state)
        assertFalse(unknown.feedbackGate.allowed)

        val settling = reducer.update(250, Evidence.FALSE, Evidence.TRUE, Evidence.TRUE)
        assertEquals(CameraProofState.WAITING_FOR_SETTLE, settling.state)
        val ready = reducer.update(350, Evidence.FALSE, Evidence.TRUE, Evidence.TRUE)
        assertEquals(CameraProofState.READY, ready.state)
        assertEquals(250, ready.transitions.last().estimatedBoundaryTimestampMs)
    }

    @Test fun stableButUnfulfilledCorrectionPermitsOneNewEvaluation() {
        val reducer = CameraSetupProofReducer(settleMs = 100, timeoutMs = 1_000)
        reducer.issueCorrection(CameraCorrection.MOVE_RIGHT, 0)
        reducer.update(150, Evidence.TRUE, Evidence.FALSE, Evidence.FALSE)
        reducer.update(200, Evidence.FALSE, Evidence.TRUE, Evidence.FALSE)
        val result = reducer.update(300, Evidence.FALSE, Evidence.TRUE, Evidence.FALSE)
        assertEquals(CameraProofState.NEEDS_FEEDBACK, result.state)
        assertTrue(result.feedbackGate.allowed)
    }

    @Test fun setupTimeoutIsExplicitAndReplayIsDeterministic() {
        fun replay(): CameraProofSnapshot {
            val reducer = CameraSetupProofReducer(settleMs = 100, timeoutMs = 300)
            reducer.issueCorrection(CameraCorrection.MOVE_LEFT, 0)
            reducer.update(100, Evidence.FALSE, Evidence.UNKNOWN, Evidence.UNKNOWN)
            return reducer.update(300, Evidence.FALSE, Evidence.UNKNOWN, Evidence.UNKNOWN)
        }
        val first = replay()
        assertEquals(CameraProofState.TIMED_OUT, first.state)
        assertEquals(first, replay())
    }
}

private enum class CameraCorrection { MOVE_LEFT, MOVE_RIGHT }
private enum class CameraProofState { IDLE, EXPECTING_ADJUSTMENT, PERSON_MOVING, WAITING_FOR_SETTLE, NEEDS_FEEDBACK, READY, TIMED_OUT }

private data class CameraProofSnapshot(
    val state: CameraProofState,
    val feedbackGate: FeedbackGateDecision,
    val transitions: List<TransitionRecord<CameraProofState>>,
)

/** Test-only consumer: camera vocabulary and transition choices remain outside the shared core. */
private class CameraSetupProofReducer(private val settleMs: Long, private val timeoutMs: Long) {
    private var state = CameraProofState.IDLE
    private var expected: ExpectedChange<CameraCorrection>? = null
    private var settle = SustainedCondition(settleMs)
    private val timeout = TimeoutTracker()
    private val transitions = mutableListOf<TransitionRecord<CameraProofState>>()

    fun issueCorrection(correction: CameraCorrection, timestampMs: Long) {
        expected = ExpectedChange(correction, timestampMs, responseGraceMs = 100, timeoutMs = timeoutMs)
        timeout.arm(timestampMs, timeoutMs)
        transition(CameraProofState.EXPECTING_ADJUSTMENT, timestampMs, "correction_issued")
    }

    fun update(timestampMs: Long, movement: Evidence, stable: Evidence, fulfilled: Evidence): CameraProofSnapshot {
        val change = checkNotNull(expected)
        val timedOut = timeout.update(timestampMs).status == TimeoutStatus.TIMED_OUT
        if (timedOut) {
            transition(CameraProofState.TIMED_OUT, timestampMs, "adjustment_timeout")
            val status = change.update(timestampMs, movement, stable, fulfilled)
            return snapshot(status.feedbackGate)
        }

        if (movement == Evidence.TRUE) {
            settle.reset()
            if (state != CameraProofState.PERSON_MOVING) transition(CameraProofState.PERSON_MOVING, timestampMs, "response_movement")
        } else if (state == CameraProofState.PERSON_MOVING) {
            transition(CameraProofState.WAITING_FOR_SETTLE, timestampMs, "movement_ended", timestampMs)
        }

        val stableDwell = settle.update(timestampMs, stable)
        val expectedStatus = change.update(
            timestampMs,
            movement,
            if (stableDwell.fulfilled) Evidence.TRUE else if (stable == Evidence.UNKNOWN) Evidence.UNKNOWN else Evidence.FALSE,
            if (stableDwell.fulfilled) fulfilled else Evidence.UNKNOWN,
        )
        if (stableDwell.fulfilled) {
            if (fulfilled == Evidence.TRUE) {
                transition(CameraProofState.READY, timestampMs, "correction_fulfilled", stableDwell.firstSupportingTimestampMs)
            } else if (fulfilled == Evidence.FALSE) {
                transition(CameraProofState.NEEDS_FEEDBACK, timestampMs, "stable_but_unfulfilled", stableDwell.firstSupportingTimestampMs)
            }
        }
        return snapshot(expectedStatus.feedbackGate)
    }

    private fun snapshot(gate: FeedbackGateDecision) = CameraProofSnapshot(state, gate, transitions.toList())

    private fun transition(newState: CameraProofState, atMs: Long, trigger: String, boundaryMs: Long? = null) {
        if (state == newState) return
        val previous = state
        state = newState
        transitions += TransitionRecord(previous, newState, atMs, boundaryMs, trigger)
    }
}
