package dk.lasse.karatecliprecorder.learningactivity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActivityWalkthroughTest {
    @Test fun startsReadyAndCannotAdvanceBeforeInteraction() {
        val flow = ActivityWalkthrough()
        assertEquals(ActivityWalkthroughStep.READY, flow.step)
        assertEquals(ActivityShellState.READY, flow.shellState)
        assertFalse(flow.canShowFeedback)

        flow.showFeedback()
        assertEquals(ActivityWalkthroughStep.READY, flow.step)
    }

    @Test fun startOpensTargetAndTapEnablesFeedback() {
        val flow = ActivityWalkthrough()
        flow.start()
        assertEquals(ActivityWalkthroughStep.TARGET, flow.step)
        assertEquals(ActivityShellState.ACTIVE, flow.shellState)
        assertFalse(flow.canShowFeedback)

        flow.tapTarget()
        assertTrue(flow.targetTapped)
        assertTrue(flow.canShowFeedback)
    }

    @Test fun feedbackRequiresTargetAttempt() {
        val flow = ActivityWalkthrough()
        flow.start()
        flow.showFeedback()
        assertEquals(ActivityWalkthroughStep.TARGET, flow.step)

        flow.tapTarget()
        flow.showFeedback()
        assertEquals(ActivityWalkthroughStep.FEEDBACK, flow.step)
        assertEquals(ActivityShellState.RESULT, flow.shellState)
    }

    @Test fun previousReturnsToCompletedTargetInteraction() {
        val flow = flowAtFeedback()
        flow.previous()
        assertEquals(ActivityWalkthroughStep.TARGET, flow.step)
        assertTrue(flow.targetTapped)
        assertTrue(flow.canShowFeedback)
    }

    @Test fun completionIsOnlyAvailableAfterContinue() {
        val flow = flowAtFeedback()
        var saveCount = 0
        assertFalse(flow.canFinish())
        assertFalse(flow.finish { saveCount++; true })
        assertEquals(0, saveCount)

        flow.continueToComplete()
        assertEquals(ActivityWalkthroughStep.COMPLETE, flow.step)
        assertEquals(ActivityShellState.COMPLETE, flow.shellState)
        assertTrue(flow.canFinish())
        assertTrue(flow.finish { saveCount++; true })
        assertEquals(1, saveCount)
    }

    @Test fun replayStartsACompleteWalkthroughAtReady() {
        val completedRun = flowAtFeedback().apply { continueToComplete() }
        assertTrue(completedRun.canFinish())

        val replay = ActivityWalkthrough()
        assertEquals(ActivityWalkthroughStep.READY, replay.step)
        assertFalse(replay.targetTapped)
        assertFalse(replay.canFinish())
    }

    private fun flowAtFeedback() = ActivityWalkthrough().apply {
        start()
        tapTarget()
        showFeedback()
    }
}
