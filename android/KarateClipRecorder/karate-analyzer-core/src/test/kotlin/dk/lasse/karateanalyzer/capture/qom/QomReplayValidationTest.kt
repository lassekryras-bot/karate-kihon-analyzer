package dk.lasse.karateanalyzer.capture.qom

import dk.lasse.karateanalyzer.capture.PoseReplayJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

class QomReplayValidationTest {

    private fun findRepoRoot(): File {
        var dir = File(".").canonicalFile
        while (dir != null && !File(dir, "output/task5/real-kihon-10-punch.fixture.json").exists()) {
            dir = dir.parentFile
        }
        return dir ?: File("c:/Users/Lasse/karate-kihon-analyzer")
    }

    @Test
    fun replayAdultSixtyFpsPunchRecordingIsolatesAllTenPunches() {
        val root = findRepoRoot()
        val fixtureFile = File(root, "output/task5/real-kihon-10-punch.fixture.json")
        assertTrue("Fixture file must exist: ${fixtureFile.absolutePath}", fixtureFile.exists())

        val fixture = PoseReplayJson.decode(fixtureFile.readText())
        val extractor = QomMotionEvidenceExtractor(profile = MotionBodyProfile.PUNCH)
        val segmenter = QomMovementSegmenter()

        val segments = mutableListOf<QomMovementSegment>()
        for (frame in fixture.frames) {
            val ev = extractor.extract(frame)
            val snap = segmenter.accept(ev)
            if (snap.completedSegment != null) {
                segments.add(snap.completedSegment)
            }
        }
        val finalSeg = segmenter.finish(fixture.frames.last().timestampMs)
        if (finalSeg != null) {
            segments.add(finalSeg)
        }

        // The full 701-frame adult recording contains 14 upper-body movement bursts
        assertEquals(14, segments.size)

        // The first 10 movements are the 10 executed punches
        for (i in 0 until 10) {
            val seg = segments[i]
            assertEquals(i + 1, seg.movementNumber)
            assertTrue("Punch ${seg.movementNumber} duration must be positive", seg.durationMs > 0)
            assertTrue(
                "Punch ${seg.movementNumber} duration should be ~350-550ms, got ${seg.durationMs}ms",
                seg.durationMs in 350L..550L,
            )
            // Retained margin checks
            assertEquals(maxOf(0L, seg.logicalStartTimestampMs - 150L), seg.retainedStartTimestampMs)
            assertEquals(seg.logicalEndTimestampMs + 200L, seg.retainedEndTimestampMs)
        }

        // Verify Punch 1 through 5 approximate bounds match normative math document
        // Punch 1 starts around 567ms, ends around 1033ms
        val p1 = segments[0]
        assertTrue("P1 start should be ~567ms, got ${p1.logicalStartTimestampMs}", p1.logicalStartTimestampMs in 500L..650L)
        assertTrue("P1 end should be ~1033ms, got ${p1.logicalEndTimestampMs}", p1.logicalEndTimestampMs in 950L..1100L)

        // Verify consecutive punches are cleanly separated with no unintended merging
        for (i in 0 until 9) {
            val curr = segments[i]
            val next = segments[i + 1]
            assertTrue("Punch ${curr.movementNumber} must end before next starts", curr.logicalEndTimestampMs < next.logicalStartTimestampMs)
            val pause = next.logicalStartTimestampMs - curr.logicalEndTimestampMs
            assertTrue("Pause between ${curr.movementNumber} and ${next.movementNumber} must be > 300ms, got ${pause}ms", pause >= 300L)
        }
    }

    @Test
    fun downsampledThirtyFpsReplayProducesIdenticalFourteenIntervalsWithCloseBoundaries() {
        val root = findRepoRoot()
        val fixtureFile = File(root, "output/task5/real-kihon-10-punch.fixture.json")
        val fixture = PoseReplayJson.decode(fixtureFile.readText())

        // 60 FPS run
        val extractor60 = QomMotionEvidenceExtractor(profile = MotionBodyProfile.PUNCH)
        val segmenter60 = QomMovementSegmenter()
        val segments60 = mutableListOf<QomMovementSegment>()
        for (f in fixture.frames) {
            val snap = segmenter60.accept(extractor60.extract(f))
            snap.completedSegment?.let { segments60.add(it) }
        }
        segmenter60.finish(fixture.frames.last().timestampMs)?.let { segments60.add(it) }

        // 30 FPS run (every 2nd frame)
        val frames30 = fixture.frames.filterIndexed { index, _ -> index % 2 == 0 }
        val extractor30 = QomMotionEvidenceExtractor(profile = MotionBodyProfile.PUNCH)
        val segmenter30 = QomMovementSegmenter()
        val segments30 = mutableListOf<QomMovementSegment>()
        for (f in frames30) {
            val snap = segmenter30.accept(extractor30.extract(f))
            snap.completedSegment?.let { segments30.add(it) }
        }
        segmenter30.finish(frames30.last().timestampMs)?.let { segments30.add(it) }

        // Both produce exactly 14 intervals!
        assertEquals(14, segments60.size)
        assertEquals(14, segments30.size)

        // Compare boundaries: differences must be small (within 1-2 frames of 30 FPS, <= 70 ms)
        for (i in 0 until 14) {
            val s60 = segments60[i]
            val s30 = segments30[i]
            val startDiff = abs(s60.logicalStartTimestampMs - s30.logicalStartTimestampMs)
            val endDiff = abs(s60.logicalEndTimestampMs - s30.logicalEndTimestampMs)
            val durDiff = abs(s60.durationMs - s30.durationMs)

            assertTrue("Move ${i + 1} start diff should be <= 70ms, got ${startDiff}ms", startDiff <= 70L)
            assertTrue("Move ${i + 1} end diff should be <= 70ms, got ${endDiff}ms", endDiff <= 70L)
            assertTrue("Move ${i + 1} duration diff should be <= 70ms, got ${durDiff}ms", durDiff <= 70L)
        }
    }

    @Test
    fun replayChildThirtyThreeFpsSessionProducesCleanSeparatedMovementsWithoutNormalization() {
        val root = findRepoRoot()
        val fixtureFile = File(root, "output/task5/blind/blind-session.fixture.json")
        assertTrue("Fixture file must exist: ${fixtureFile.absolutePath}", fixtureFile.exists())

        val fixture = PoseReplayJson.decode(fixtureFile.readText())
        val extractor = QomMotionEvidenceExtractor(profile = MotionBodyProfile.PUNCH)
        val segmenter = QomMovementSegmenter()

        val segments = mutableListOf<QomMovementSegment>()
        for (frame in fixture.frames) {
            val ev = extractor.extract(frame)
            val snap = segmenter.accept(ev)
            if (snap.completedSegment != null) {
                segments.add(snap.completedSegment)
            }
        }
        segmenter.finish(fixture.frames.last().timestampMs)?.let { segments.add(it) }

        // 11 distinct movement bursts detected without chatter or false positives during quiet holds
        assertEquals(11, segments.size)

        // All movements have positive duration and valid boundaries
        for (seg in segments) {
            assertTrue(seg.durationMs in 300L..1500L)
            assertTrue(seg.retainedStartTimestampMs <= seg.logicalStartTimestampMs)
            assertTrue(seg.retainedEndTimestampMs >= seg.logicalEndTimestampMs)
        }
    }

    @Test
    fun deterministicRepeatProducesBitIdenticalTrace() {
        val root = findRepoRoot()
        val fixtureFile = File(root, "output/task5/real-kihon-10-punch.fixture.json")
        val fixture = PoseReplayJson.decode(fixtureFile.readText())

        fun run(): List<QomMovementSegment> {
            val extractor = QomMotionEvidenceExtractor(profile = MotionBodyProfile.PUNCH)
            val segmenter = QomMovementSegmenter()
            val segments = mutableListOf<QomMovementSegment>()
            for (f in fixture.frames) {
                segmenter.accept(extractor.extract(f)).completedSegment?.let { segments.add(it) }
            }
            segmenter.finish(fixture.frames.last().timestampMs)?.let { segments.add(it) }
            return segments
        }

        val run1 = run()
        val run2 = run()
        assertEquals(run1, run2)
    }
}

