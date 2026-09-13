package dk.lasse.karateanalyzer.capture.retrospective

import dk.lasse.karateanalyzer.capture.PoseReplayJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RetrospectiveMovementSegmenterTest {

    private fun findRepoRoot(): File {
        var dir = File(".").canonicalFile
        while (dir != null && !File(dir, "output/task5/real-kihon-10-punch.fixture.json").exists()) {
            dir = dir.parentFile
        }
        return dir ?: File("c:/Users/Lasse/karate-kihon-analyzer")
    }

    @Test
    fun testRecordingARepetitionsCadenceIsolatesAllTenPunches() {
        val root = findRepoRoot()
        val fixtureFile = File(root, "output/task5/real-kihon-10-punch.fixture.json")
        assertTrue("Fixture file must exist: ${fixtureFile.absolutePath}", fixtureFile.exists())

        val fixture = PoseReplayJson.decode(fixtureFile.readText())
        val segmenter = RetrospectiveSessionSegmenter(
            RetrospectiveSegmenterConfig(
                cadence = RetrospectiveCadence.REPETITIONS,
            )
        )

        val masterVideo = "input/task5/1000002073.mp4"
        val result = segmenter.segment(
            sequenceId = fixture.sequenceId,
            masterVideoPath = masterVideo,
            frames = fixture.frames,
        )

        assertEquals("real-kihon-10-punch-1000002073-v1", result.sequenceId)
        assertEquals(masterVideo, result.masterVideoPath)
        assertEquals(701, result.totalFrames)

        // Verifies that at least the 10 punches are cleanly detected (plus post-drill resets)
        assertTrue(
            "Expected at least 10 punch movements, found ${result.detectedMovementCount}",
            result.detectedMovementCount >= 10,
        )

        // Punches 1 through 10 checks
        val movements = result.movements
        for (i in 0 until 10) {
            val m = movements[i]
            assertEquals(i + 1, m.movementNumber)
            assertEquals(masterVideo, m.sourceRecordingPath)
            assertEquals(MovementAnalysisStatus.DETECTED, m.analysisStatus)
            assertTrue("Movement ${m.movementNumber} duration must be positive", m.durationMs > 0)
            assertTrue(
                "Movement ${m.movementNumber} duration should be ~250-600ms for a punch burst, got ${m.durationMs}ms",
                m.durationMs in 250L..650L,
            )
            // Retained margin checks
            assertEquals(
                maxOf(0L, m.logicalStartTimestampMs - 150L),
                m.retainedStartTimestampMs,
            )
            assertEquals(
                minOf(result.totalDurationMs, m.logicalEndTimestampMs + 200L),
                m.retainedEndTimestampMs,
            )
        }

        // Verify Punch 1 through 5 bounds approximately match visual landmarks
        // P1: starts ~400ms, ends ~817ms
        assertTrue(movements[0].logicalStartTimestampMs in 380L..450L)
        assertTrue(movements[0].logicalEndTimestampMs in 800L..900L)

        // P4 and P5 are completely separated (NOT merged!)
        val p4 = movements[3]
        val p5 = movements[4]
        assertTrue("P4 must end before P5 starts", p4.logicalEndTimestampMs < p5.logicalStartTimestampMs)
        val pauseBetweenP4AndP5 = p5.logicalStartTimestampMs - p4.logicalEndTimestampMs
        assertTrue("Pause between P4 and P5 must be > 200ms, got ${pauseBetweenP4AndP5}ms", pauseBetweenP4AndP5 >= 200L)
    }

    @Test
    fun testRecordingBNormalCadencePreservesRapidCombination() {
        val root = findRepoRoot()
        val fixtureFile = File(root, "output/task5/blind/blind-session.fixture.json")
        assertTrue("Fixture file must exist: ${fixtureFile.absolutePath}", fixtureFile.exists())

        val fixture = PoseReplayJson.decode(fixtureFile.readText())
        val segmenter = RetrospectiveSessionSegmenter(
            RetrospectiveSegmenterConfig(
                cadence = RetrospectiveCadence.NORMAL,
            )
        )

        val masterVideo = "input/task5/20260911_223447.mp4"
        val result = segmenter.segment(
            sequenceId = fixture.sequenceId,
            masterVideoPath = masterVideo,
            frames = fixture.frames,
        )

        assertEquals("blind-20260911_223447", result.sequenceId)
        assertEquals(763, result.totalFrames)

        // 5 distinct movements in Recording B (moves 1-3, rapid combination M4, move 5; terminal touch filtered by minMovementDurationMs)
        assertEquals(5, result.detectedMovementCount)

        val m4 = result.movements[3]
        // Movement 4 spans the rapid 1-2 strike combination without splitting
        assertTrue("M4 should span the combination (> 2000ms), got ${m4.durationMs}ms", m4.durationMs > 2000L)
    }

    @Test
    fun testCueTimelineAssociation() {
        val root = findRepoRoot()
        val fixtureFile = File(root, "output/task5/real-kihon-10-punch.fixture.json")
        val fixture = PoseReplayJson.decode(fixtureFile.readText())

        val cues = CueTimeline(
            sessionRecordingId = "test-session",
            cues = listOf(
                CueEvent(id = "cue-1", cueName = "ICHI", monotonicTimestampMs = 1000L, videoTimestampMs = 400L),
                CueEvent(id = "cue-2", cueName = "NI", monotonicTimestampMs = 1850L, videoTimestampMs = 1250L),
                CueEvent(id = "cue-3", cueName = "SAN", monotonicTimestampMs = 2650L, videoTimestampMs = 2050L),
            ),
        )

        val segmenter = RetrospectiveSessionSegmenter(
            RetrospectiveSegmenterConfig(
                cadence = RetrospectiveCadence.REPETITIONS,
            )
        )

        val result = segmenter.segment(
            sequenceId = fixture.sequenceId,
            masterVideoPath = "master.mp4",
            frames = fixture.frames,
            cueTimeline = cues,
        )

        val m1 = result.movements[0]
        assertNotNull(m1.associatedCue)
        assertEquals("ICHI", m1.associatedCue!!.cueName)

        val m2 = result.movements[1]
        assertNotNull(m2.associatedCue)
        assertEquals("NI", m2.associatedCue!!.cueName)

        val m3 = result.movements[2]
        assertNotNull(m3.associatedCue)
        assertEquals("SAN", m3.associatedCue!!.cueName)
    }
}
