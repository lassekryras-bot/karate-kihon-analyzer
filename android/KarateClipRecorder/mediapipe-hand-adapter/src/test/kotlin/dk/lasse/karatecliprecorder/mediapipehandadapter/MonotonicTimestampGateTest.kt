package dk.lasse.karatecliprecorder.mediapipehandadapter

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MonotonicTimestampGateTest {
    @Test fun acceptsOnlyStrictlyIncreasingTimestamps() {
        val gate = MonotonicTimestampGate()
        assertTrue(gate.tryAccept(10))
        assertFalse(gate.tryAccept(10))
        assertFalse(gate.tryAccept(9))
        assertTrue(gate.tryAccept(11))
    }

    @Test fun resetAllowsFreshTimeline() {
        val gate = MonotonicTimestampGate()
        assertTrue(gate.tryAccept(10))
        gate.reset()
        assertTrue(gate.tryAccept(1))
    }
}
