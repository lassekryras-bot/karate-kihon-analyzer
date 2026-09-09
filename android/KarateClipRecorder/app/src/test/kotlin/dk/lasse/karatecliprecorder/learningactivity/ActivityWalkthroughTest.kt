package dk.lasse.karatecliprecorder.learningactivity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActivityWalkthroughTest {
    @Test fun reviewDoesNotCompleteOrSkipTheWalkthrough() {
        val flow = ActivityWalkthrough()
        flow.previous()
        assertEquals(0, flow.step)
        flow.next()
        flow.next()
        assertFalse(flow.complete)
        flow.previous()
        assertEquals(1, flow.step)
        flow.next()
        assertFalse(flow.complete)
        flow.next()
        assertTrue(flow.complete)
    }

    @Test fun completionRemainsStableAfterFurtherNavigation() {
        val flow = ActivityWalkthrough()
        repeat(3) { flow.next() }
        flow.next()
        flow.previous()
        assertTrue(flow.complete)
        assertEquals(3, flow.step)
    }
}
