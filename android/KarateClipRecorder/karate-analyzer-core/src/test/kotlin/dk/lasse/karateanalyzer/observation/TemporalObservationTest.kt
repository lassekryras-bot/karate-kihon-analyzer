package dk.lasse.karateanalyzer.observation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TemporalObservationTest {
    @Test fun exactDwellBoundaryFulfills() {
        val condition = SustainedCondition(200)
        assertFalse(condition.update(10, Evidence.TRUE).fulfilled)
        assertFalse(condition.update(109, Evidence.TRUE).fulfilled)
        val result = condition.update(210, Evidence.TRUE)
        assertTrue(result.fulfilled)
        assertEquals(200, result.accumulatedDurationMs)
        assertEquals(10, result.firstSupportingTimestampMs)
        assertEquals(210, result.latestSupportingTimestampMs)
    }

    @Test fun falseResetsDwell() {
        val condition = SustainedCondition(100)
        condition.update(0, Evidence.TRUE)
        condition.update(80, Evidence.FALSE)
        val result = condition.update(180, Evidence.TRUE)
        assertEquals(0, result.accumulatedDurationMs)
        assertFalse(result.fulfilled)
    }

    @Test fun unknownNeverFulfillsAndResetPolicyClearsSupport() {
        val condition = SustainedCondition(100, UnknownEvidencePolicy.RESET)
        condition.update(0, Evidence.TRUE)
        val unknown = condition.update(100, Evidence.UNKNOWN)
        assertFalse(unknown.fulfilled)
        assertEquals(null, unknown.firstSupportingTimestampMs)
        assertEquals(Evidence.UNKNOWN, unknown.currentEvidence)
    }

    @Test fun unknownMayPauseOnlyWithinConfiguredGrace() {
        val condition = SustainedCondition(200, UnknownEvidencePolicy.PAUSE_WITH_GRACE, unknownGraceMs = 50)
        condition.update(0, Evidence.TRUE)
        condition.update(100, Evidence.TRUE)
        assertEquals(100, condition.update(120, Evidence.UNKNOWN).accumulatedDurationMs)
        assertEquals(100, condition.update(170, Evidence.UNKNOWN).accumulatedDurationMs)
        val expired = condition.update(171, Evidence.UNKNOWN)
        assertEquals(0, expired.accumulatedDurationMs)
        assertFalse(expired.fulfilled)
    }

    @Test fun irregularTimestampsUseElapsedTime() {
        val condition = SustainedCondition(250)
        listOf(0L, 17L, 91L, 249L).forEach { condition.update(it, Evidence.TRUE) }
        assertFalse(condition.snapshot().fulfilled)
        assertTrue(condition.update(250, Evidence.TRUE).fulfilled)
    }

    @Test fun nonIncreasingTimestampsAreRejectedRatherThanSorted() {
        val condition = SustainedCondition(100)
        condition.update(20, Evidence.TRUE)
        assertFailsWith<NonIncreasingTimestampException> { condition.update(20, Evidence.TRUE) }
        assertFailsWith<NonIncreasingTimestampException> { condition.update(19, Evidence.TRUE) }
    }

    @Test fun timeoutIsExplicitAndDoesNotRepresentSuccess() {
        val timeout = TimeoutTracker()
        assertEquals(TimeoutStatus.WAITING, timeout.arm(100, 300).status)
        assertEquals(TimeoutStatus.WAITING, timeout.update(399).status)
        assertEquals(TimeoutStatus.TIMED_OUT, timeout.update(400).status)
        timeout.reset()
        assertEquals(TimeoutStatus.DISARMED, timeout.update(999).status)
    }

    @Test fun historyEvictsByAgeAndCapacityInOriginalOrder() {
        val history = TimestampedHistory<String>(capacity = 3, maxAgeMs = 100)
        history.add(0, "old")
        history.add(50, "a")
        history.add(100, "b")
        history.add(125, "c")
        assertEquals(listOf("a", "b", "c"), history.values().map { it.value })
        history.add(175, "d")
        assertEquals(listOf("b", "c", "d"), history.values().map { it.value })
        assertFailsWith<NonIncreasingTimestampException> { history.add(175, "duplicate") }
    }

    @Test fun expectedChangeRequiresObservedResponseReliableSettleAndFulfillment() {
        val expected = ExpectedChange("move", issuedAtMs = 100, responseGraceMs = 50, timeoutMs = 500)
        val grace = expected.update(120, Evidence.FALSE, Evidence.UNKNOWN, Evidence.UNKNOWN)
        assertEquals(FeedbackSuppressionReason.RESPONSE_GRACE, grace.feedbackGate.suppressionReason)
        val moving = expected.update(160, Evidence.TRUE, Evidence.FALSE, Evidence.FALSE)
        assertEquals(ExpectedChangeStatus.CHANGE_IN_PROGRESS, moving.status)
        assertEquals(FeedbackSuppressionReason.CHANGE_IN_PROGRESS, moving.feedbackGate.suppressionReason)
        val unknown = expected.update(200, Evidence.FALSE, Evidence.UNKNOWN, Evidence.UNKNOWN)
        assertEquals(ExpectedChangeStatus.WAITING_FOR_SETTLE, unknown.status)
        assertEquals(FeedbackSuppressionReason.INSUFFICIENT_EVIDENCE, unknown.feedbackGate.suppressionReason)
        val fulfilled = expected.update(250, Evidence.FALSE, Evidence.TRUE, Evidence.TRUE)
        assertEquals(ExpectedChangeStatus.FULFILLED, fulfilled.status)
        assertEquals(FeedbackSuppressionReason.ALREADY_FULFILLED, fulfilled.feedbackGate.suppressionReason)
    }

    @Test fun expectedChangeTimeoutDoesNotFulfill() {
        val expected = ExpectedChange("turn", issuedAtMs = 0, responseGraceMs = 0, timeoutMs = 100)
        val result = expected.update(100, Evidence.UNKNOWN, Evidence.UNKNOWN, Evidence.UNKNOWN)
        assertEquals(ExpectedChangeStatus.TIMED_OUT, result.status)
        assertEquals(FeedbackSuppressionReason.TIMED_OUT, result.feedbackGate.suppressionReason)
    }

    @Test fun feedbackCooldownIsReportedWithoutPerformingFeedback() {
        val expected = ExpectedChange("shift", issuedAtMs = 0, responseGraceMs = 0, feedbackCooldownMs = 200)
        val coolingDown = expected.update(100, Evidence.FALSE, Evidence.TRUE, Evidence.FALSE)
        assertEquals(FeedbackSuppressionReason.COOLDOWN, coolingDown.feedbackGate.suppressionReason)
        val allowed = expected.update(200, Evidence.FALSE, Evidence.TRUE, Evidence.FALSE)
        assertTrue(allowed.feedbackGate.allowed)
    }

    @Test fun transitionRecordPreservesDecisionAndBoundaryTimes() {
        val record = TransitionRecord("MOVING", "COMPLETE", 1_200, 900, "stable", 0.91, mapOf("samples" to "10"))
        assertEquals(1_200, record.decisionTimestampMs)
        assertEquals(900, record.estimatedBoundaryTimestampMs)
    }

    @Test fun resetMakesDwellReplayDeterministic() {
        val condition = SustainedCondition(100)
        fun replay(): List<DwellSnapshot> = listOf(0L, 40L, 100L).map { condition.update(it, Evidence.TRUE) }
        val first = replay()
        condition.reset()
        assertEquals(first, replay())
    }
}
