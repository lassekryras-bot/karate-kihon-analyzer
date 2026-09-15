package dk.lasse.karatecliprecorder.recordings

import dk.lasse.karatecliprecorder.training.*
import org.junit.After
import org.junit.Test
import kotlin.test.*

class QueueManagerTrayTest {
    @After fun reset() = QueueManagerTraySession.resetForTests()
    private fun job(id: String, state: QueueState, phase: ProcessingPhase, changed: Long = 1,
        movements: Int = 0, audience: QueueJobAudience = QueueJobAudience.RECENT_USER,
        plan: String = RecordingProcessingPlans.STRAIGHT_PUNCH_SEGMENTS.key) =
        QueueManagerJob(id, "recording-$id", id, AssistedCaptureSetup.ACTIVITY_KEY, "Straight punches", 10,
            state, phase, plan, movements, null, changed, audience)

    @Test fun zeroQueuedActiveStagesReadyAndFailureHaveHonestPresentation() {
        assertTrue(QueueManagerPresentation.snapshot(emptyList(), emptySet()).visible.isEmpty())
        assertEquals("Waiting", job("q", QueueState.QUEUED, ProcessingPhase.QUEUED).statusLabel)
        assertEquals("Processing landmarks", job("l", QueueState.PROCESSING, ProcessingPhase.LANDMARKS).statusLabel)
        assertEquals("Finding movements", job("s", QueueState.PROCESSING, ProcessingPhase.SEGMENTATION).statusLabel)
        assertEquals("Analyzing movements", job("a", QueueState.PROCESSING, ProcessingPhase.ANALYSIS).statusLabel)
        assertEquals("Segments ready", job("r", QueueState.READY, ProcessingPhase.READY).statusLabel)
        assertEquals("Processing incomplete", job("i", QueueState.FAILED, ProcessingPhase.FAILED, movements = 2).statusLabel)
        assertEquals("Processing failed", job("f", QueueState.FAILED, ProcessingPhase.FAILED).statusLabel)
        assertFalse(job("s", QueueState.PROCESSING, ProcessingPhase.SEGMENTATION).ready)
        assertTrue(job("r", QueueState.READY, ProcessingPhase.READY).ready)
    }

    @Test fun expandedTrayShowsThreeInStablePriorityOrderAndUsefulOverflow() {
        val jobs = listOf(
            job("old-wait", QueueState.QUEUED, ProcessingPhase.QUEUED, 1),
            job("ready", QueueState.READY, ProcessingPhase.READY, 4),
            job("active-old", QueueState.PROCESSING, ProcessingPhase.LANDMARKS, 2),
            job("wait", QueueState.QUEUED, ProcessingPhase.QUEUED, 5),
            job("active-new", QueueState.PROCESSING, ProcessingPhase.SEGMENTATION, 3),
            job("ready-hidden", QueueState.READY, ProcessingPhase.READY, 1))
        val state = QueueManagerPresentation.snapshot(jobs, emptySet())
        assertEquals(listOf("active-new", "active-old", "ready"), state.visible.map { it.sessionId })
        assertEquals("+3 more · 1 ready · 0 processing · 2 waiting", state.overflowLabel)
    }

    @Test fun collapseIsPresentationOnlyAndCompletionDoesNotForceExpansion() {
        QueueManagerTraySession.observe(emptyList()); QueueManagerTraySession.collapse()
        val processing = job("one", QueueState.PROCESSING, ProcessingPhase.SEGMENTATION)
        QueueManagerTraySession.observe(listOf(processing))
        val completed = processing.copy(state = QueueState.READY, phase = ProcessingPhase.READY)
        assertEquals(listOf("one"), QueueManagerTraySession.observe(listOf(completed)).visible.map { it.sessionId })
        assertTrue(QueueManagerTraySession.userCollapsed)
    }

    @Test fun recordingHideRestoresOpenOrUserCollapsedState() {
        QueueManagerTraySession.observe(emptyList()); QueueManagerTraySession.hideForRecording(true)
        QueueManagerTraySession.hideForRecording(false)
        assertFalse(QueueManagerTraySession.userCollapsed)
        QueueManagerTraySession.collapse(); QueueManagerTraySession.hideForRecording(true)
        QueueManagerTraySession.hideForRecording(false)
        assertTrue(QueueManagerTraySession.userCollapsed)
    }

    @Test fun acknowledgementAndFreshLaunchHideReadyWithoutDeletingTruth() {
        val active = job("active", QueueState.PROCESSING, ProcessingPhase.LANDMARKS)
        val historical = job("historical", QueueState.READY, ProcessingPhase.READY)
        assertEquals(listOf("active"), QueueManagerTraySession.observe(listOf(active, historical)).visible.map { it.sessionId })
        val completed = active.copy(state = QueueState.READY, phase = ProcessingPhase.READY)
        assertEquals(listOf("active"), QueueManagerTraySession.observe(listOf(completed, historical)).visible.map { it.sessionId })
        QueueManagerTraySession.acknowledgeReady("active")
        assertTrue(QueueManagerTraySession.observe(listOf(completed, historical)).visible.isEmpty())
        assertTrue(completed.ready, "Acknowledgement removes presentation only, not durable job truth")
    }

    @Test fun maintenanceIsExcludedAndActiveJobsCannotBeDismissed() {
        val active = job("active", QueueState.PROCESSING, ProcessingPhase.SEGMENTATION)
        val maintenance = job("maintenance", QueueState.PROCESSING, ProcessingPhase.ANALYSIS,
            audience = QueueJobAudience.MAINTENANCE)
        val state = QueueManagerPresentation.snapshot(listOf(maintenance, active), setOf("active"))
        assertEquals(listOf("active"), state.visible.map { it.sessionId })
        assertFalse(active.dismissible)
        assertTrue(job("ready", QueueState.READY, ProcessingPhase.READY).dismissible)
    }
}
