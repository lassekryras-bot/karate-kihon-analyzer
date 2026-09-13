package dk.lasse.karateanalyzer.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BaseMovementSegmenterTest {

    @Test
    fun testStartConfirmedAfter50MsAndBackdated() {
        val segmenter = BaseMovementSegmenter(BaseRecordingProfile(startDwellMs = 50L, quietDwellMs = 100L))
        assertEquals(BaseSegmentState.ARMED, segmenter.state)

        // 0 ms: quiet -> ARMED
        var snap = segmenter.accept(0L, 0.20, 10.0)
        assertEquals(BaseSegmentState.ARMED, snap.state)
        assertNull(snap.movingStartCandidateMs)

        // 20 ms: moving threshold crossed (ET = 0.80 >= 0.70)
        snap = segmenter.accept(20L, 0.80, 10.0)
        assertEquals(BaseSegmentState.ARMED, snap.state)
        assertEquals(20L, snap.movingStartCandidateMs)

        // 40 ms: still moving, but elapsed is 20 ms < 50 ms
        snap = segmenter.accept(40L, 0.85, 10.0)
        assertEquals(BaseSegmentState.ARMED, snap.state)
        assertEquals(20L, snap.movingStartCandidateMs)

        // 70 ms: elapsed is 70 - 20 = 50 ms >= 50 ms -> CONFIRMED MOVING!
        snap = segmenter.accept(70L, 0.90, 10.0)
        assertEquals(BaseSegmentState.MOVING, snap.state)
        assertEquals(20L, snap.movementStartBoundaryMs)
    }

    @Test
    fun testStartResetIfPositiveMotionInterruptedBefore50Ms() {
        val segmenter = BaseMovementSegmenter(BaseRecordingProfile(startDwellMs = 50L))

        segmenter.accept(20L, 0.80, 10.0)
        assertEquals(20L, segmenter.accept(40L, 0.80, 10.0).movingStartCandidateMs)

        // 50 ms: drops into MID (0.50) -> resets candidate
        val snap = segmenter.accept(50L, 0.50, 10.0)
        assertEquals(BaseSegmentState.ARMED, snap.state)
        assertNull(snap.movingStartCandidateMs)

        // 60 ms: moves again -> new candidate is 60L
        val snap2 = segmenter.accept(60L, 0.80, 10.0)
        assertEquals(BaseSegmentState.ARMED, snap2.state)
        assertEquals(60L, snap2.movingStartCandidateMs)
    }

    @Test
    fun testCompletionConfirmedAfter100MsContinuousQuietAndBackdated() {
        val segmenter = BaseMovementSegmenter(BaseRecordingProfile(startDwellMs = 50L, quietDwellMs = 100L))

        // Start movement at 20L, confirmed at 70L
        segmenter.accept(20L, 0.80, 10.0)
        segmenter.accept(70L, 0.80, 10.0)
        assertEquals(BaseSegmentState.MOVING, segmenter.state)

        // Active movement continues
        segmenter.accept(100L, 1.50, 50.0)
        segmenter.accept(150L, 1.20, 40.0)

        // 200 ms: first quiet sample (ET=0.30 <= 0.45, EA=10.0 <= 20.0)
        var snap = segmenter.accept(200L, 0.30, 10.0)
        assertEquals(BaseSegmentState.MOVING, snap.state)
        assertEquals(200L, snap.quietEndCandidateMs)

        // 250 ms: quiet continues (elapsed 50 ms < 100 ms)
        snap = segmenter.accept(250L, 0.30, 10.0)
        assertEquals(BaseSegmentState.MOVING, snap.state)
        assertEquals(200L, snap.quietEndCandidateMs)

        // 300 ms: elapsed 300 - 200 = 100 ms >= 100 ms -> COMPLETE!
        snap = segmenter.accept(300L, 0.30, 10.0)
        assertEquals(BaseSegmentState.COMPLETE, snap.state)
        val seg = snap.completedSegment
        assertNotNull(seg)
        assertEquals(1, seg!!.movementNumber)
        assertEquals(20L, seg.startBoundaryMs)
        assertEquals(200L, seg.endBoundaryMs)
        assertEquals(180L, seg.durationMs)
        assertEquals(300L, seg.confirmationTimestampMs)

        // Immediate re-arm: next frame is ARMED
        val nextSnap = segmenter.accept(320L, 0.20, 10.0)
        assertEquals(BaseSegmentState.ARMED, nextSnap.state)
        assertEquals(2, segmenter.currentMovementNumber)
    }

    @Test
    fun testMidPausesQuietAccumulationInWindow() {
        val segmenter = BaseMovementSegmenter(
            BaseRecordingProfile(
                startDwellMs = 50L,
                quietDwellMs = 100L,
                settlingWindowMs = 120L,
            )
        )

        // Trigger moving
        segmenter.accept(20L, 0.80, 10.0)
        segmenter.accept(70L, 0.80, 10.0)
        assertEquals(BaseSegmentState.MOVING, segmenter.state)

        // 200 ms: quiet begins (30 ms quiet)
        segmenter.accept(200L, 0.30, 10.0)
        segmenter.accept(230L, 0.30, 10.0)

        // 250 ms - 290 ms: MID motion (40 ms) -> pauses quiet accumulation
        var snap = segmenter.accept(250L, 0.55, 10.0)
        assertEquals(BaseSegmentState.MOVING, snap.state)
        assertNull(snap.quietEndCandidateMs)

        snap = segmenter.accept(290L, 0.55, 10.0)
        assertEquals(BaseSegmentState.MOVING, snap.state)
        assertNull(snap.quietEndCandidateMs)

        // 320 ms: quiet resumes -> candidate starts at 320L
        snap = segmenter.accept(320L, 0.30, 10.0)
        assertEquals(BaseSegmentState.MOVING, snap.state)
        assertEquals(320L, snap.quietEndCandidateMs)

        // 360 ms: in window [240L, 360L], MID took 40 ms, quiet is only 70 ms < 100 ms -> NOT complete
        snap = segmenter.accept(360L, 0.30, 10.0)
        assertEquals(BaseSegmentState.MOVING, snap.state)
        assertNull(snap.completedSegment)

        // 420 ms: quiet continues (320L to 420L = 100 ms quiet) -> COMPLETE!
        snap = segmenter.accept(420L, 0.30, 10.0)
        assertEquals(BaseSegmentState.COMPLETE, snap.state)
        assertNotNull(snap.completedSegment)
        // Backdated to 320L (start of latest quiet run)
        assertEquals(320L, snap.completedSegment!!.endBoundaryMs)
    }

    @Test
    fun testTolerantSettlingWithBriefMovingInterruptionNeverBackdatesAcrossMoving() {
        val segmenter = BaseMovementSegmenter(
            BaseRecordingProfile(
                startDwellMs = 50L,
                quietDwellMs = 100L,
                settlingWindowMs = 120L,
                maxMovingInterruptionMs = 30L,
            )
        )

        // Start movement at 20L, confirmed at 70L
        segmenter.accept(20L, 0.80, 10.0)
        segmenter.accept(70L, 0.80, 10.0)
        assertEquals(BaseSegmentState.MOVING, segmenter.state)

        // Movement body
        segmenter.accept(100L, 1.20, 30.0)
        segmenter.accept(150L, 1.00, 25.0)

        // 200L: Quiet run 1 starts
        segmenter.accept(200L, 0.30, 10.0)
        segmenter.accept(230L, 0.30, 10.0)

        // 250L: Brief moving interruption of 20 ms <= maxMovingInterruptionMs (30 ms)
        val movingSnap = segmenter.accept(250L, 0.80, 10.0)
        assertEquals(BaseSegmentState.MOVING, movingSnap.state)
        assertNull(movingSnap.quietEndCandidateMs)

        // 270L: Quiet run 2 resumes
        val resumeSnap = segmenter.accept(270L, 0.30, 10.0)
        assertEquals(270L, resumeSnap.quietEndCandidateMs)

        // 300L: In window [180L, 300L] (span 100 ms):
        // 250L is MOVING (20 ms <= 30 ms tolerance), quiet accumulation is 110 ms >= 100 ms -> COMPLETE!
        val snap = segmenter.accept(300L, 0.30, 10.0)
        assertEquals(BaseSegmentState.COMPLETE, snap.state)
        val seg = snap.completedSegment
        assertNotNull(seg)
        // MUST backdate to start of latest quiet run (270L), NEVER backdating across the 250L moving interruption
        assertEquals(270L, seg!!.endBoundaryMs)
    }

    @Test
    fun testScopedSettlingEvidenceUpperBodyIsolatesMovementWhenLegsJitter() {
        val segmenter = BaseMovementSegmenter(
            BaseRecordingProfile(
                startDwellMs = 50L,
                quietDwellMs = 100L,
                settlingWindowMs = 120L,
            )
        )

        // Start triggered by whole-body (e.g. punch onset)
        segmenter.accept(20L, startTranslationEvidence = 0.85, startAngularEvidence = 15.0)
        segmenter.accept(70L, startTranslationEvidence = 0.90, startAngularEvidence = 20.0)
        assertEquals(BaseSegmentState.MOVING, segmenter.state)

        // Movement body
        segmenter.accept(100L, 1.20, 30.0)

        // Hold period: legs continue jittering (whole body ET = 0.65, EA = 25.0 in MOVING/MID)
        // BUT upper body is genuinely quiet (ET = 0.25, EA = 10.0)
        segmenter.accept(
            timestampMs = 200L,
            startTranslationEvidence = 0.65,
            startAngularEvidence = 25.0,
            settlingTranslationEvidence = 0.25,
            settlingAngularEvidence = 10.0,
        )
        segmenter.accept(
            timestampMs = 250L,
            startTranslationEvidence = 0.72,
            startAngularEvidence = 28.0,
            settlingTranslationEvidence = 0.25,
            settlingAngularEvidence = 10.0,
        )
        val snap = segmenter.accept(
            timestampMs = 300L,
            startTranslationEvidence = 0.60,
            startAngularEvidence = 22.0,
            settlingTranslationEvidence = 0.25,
            settlingAngularEvidence = 10.0,
        )

        // Scoped upper-body settling completes the movement cleanly at 300L despite leg motion
        assertEquals(BaseSegmentState.COMPLETE, snap.state)
        assertNotNull(snap.completedSegment)
        assertEquals(200L, snap.completedSegment!!.endBoundaryMs)
    }

    @Test
    fun testScopedSettlingEvidenceUnknownPreventsCompletion() {
        val segmenter = BaseMovementSegmenter(
            BaseRecordingProfile(
                startDwellMs = 50L,
                quietDwellMs = 100L,
            )
        )

        segmenter.accept(20L, 0.85, 15.0)
        segmenter.accept(70L, 0.90, 20.0)
        assertEquals(BaseSegmentState.MOVING, segmenter.state)

        // Settling evidence is missing (UNKNOWN), even if whole body claims quiet
        segmenter.accept(200L, startTranslationEvidence = 0.20, startAngularEvidence = 10.0, settlingTranslationEvidence = null, settlingAngularEvidence = 10.0)
        segmenter.accept(250L, startTranslationEvidence = 0.20, startAngularEvidence = 10.0, settlingTranslationEvidence = null, settlingAngularEvidence = 10.0)
        segmenter.accept(300L, startTranslationEvidence = 0.20, startAngularEvidence = 10.0, settlingTranslationEvidence = null, settlingAngularEvidence = 10.0)
        val snap = segmenter.accept(330L, startTranslationEvidence = 0.20, startAngularEvidence = 10.0, settlingTranslationEvidence = null, settlingAngularEvidence = 10.0)

        // Must NOT complete because scoped evidence is UNKNOWN (cannot prove stillness)
        assertEquals(BaseSegmentState.MOVING, snap.state)
        assertNull(snap.completedSegment)
    }
}

