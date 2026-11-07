package dk.lasse.karateanalyzer.capture.retrospective

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import dk.lasse.karateanalyzer.capture.qom.QomEndBoundaryKind
import dk.lasse.karateanalyzer.geometry.CanonicalGeometryDescriptor

class LiteHeavyPrototypePlanTest {
    @Test
    fun `plans padded source-time windows and discloses overlaps without merging`() {
        val plan = LiteHeavyPrototypePlanner.plan(
            recordingId = "recording-1",
            sourceDurationMs = 10_000,
            liteTrack = liteTrack(),
            candidates = listOf(
                PrototypeCandidateInterval("movement-1", 2_000, 2_500, PrototypeCandidateKind.DETECTED_MOVEMENT),
                PrototypeCandidateInterval("unknown-1", 2_700, 2_900, PrototypeCandidateKind.UNKNOWN_EVIDENCE),
            ),
            paddingBeforeMs = 500,
            paddingAfterMs = 500,
        )

        assertEquals(2, plan.windows.size)
        assertEquals(1_500, plan.windows[0].requestedStartTimestampMs)
        assertEquals(3_000, plan.windows[0].requestedEndTimestampMs)
        assertEquals(listOf("heavy-window-2"), plan.windows[0].overlappingWindowIds)
        assertEquals(listOf("heavy-window-1"), plan.windows[1].overlappingWindowIds)
        assertEquals(PrototypeCandidateKind.UNKNOWN_EVIDENCE, plan.windows[1].candidate.kind)
        assertEquals(1, plan.executionWindows.size)
        assertEquals(listOf("heavy-window-1", "heavy-window-2"), plan.executionWindows.single().candidateWindowIds)
        assertEquals(2_700, plan.requestedCoverageSumMs)
        assertEquals(1_900, plan.executionCoverageUnionMs)
    }

    @Test
    fun `records padding truncation at recording boundaries`() {
        val plan = LiteHeavyPrototypePlanner.plan(
            "recording-1",
            5_000,
            liteTrack(requestedEndTimestampMs = 5_000, actualEndTimestampMs = 4_967),
            listOf(
                PrototypeCandidateInterval("start", 100, 300, PrototypeCandidateKind.DETECTED_MOVEMENT),
                PrototypeCandidateInterval("end", 4_800, 5_000, PrototypeCandidateKind.UNKNOWN_EVIDENCE),
            ),
            500,
            500,
        )

        assertEquals(0, plan.windows[0].requestedStartTimestampMs)
        assertTrue(plan.windows[0].truncatedAtRecordingStart)
        assertFalse(plan.windows[0].truncatedAtRecordingEnd)
        assertEquals(5_000, plan.windows[1].requestedEndTimestampMs)
        assertTrue(plan.windows[1].truncatedAtRecordingEnd)
        assertEquals(2, plan.executionWindows.size)
    }

    @Test
    fun `empty discovery explicitly requires full Heavy fallback`() {
        val plan = LiteHeavyPrototypePlanner.plan("recording-1", 10_000, liteTrack(), emptyList(), 500, 500)
        assertTrue(plan.windows.isEmpty())
        assertTrue(plan.executionWindows.isEmpty())
        assertEquals(PrototypeDiscoveryDisposition.FULL_HEAVY_FALLBACK_REQUIRED, plan.discoveryDisposition)
    }

    @Test
    fun `rejects candidates outside the source duration and incompatible tracks`() {
        assertFailsWith<IllegalArgumentException> {
            LiteHeavyPrototypePlanner.plan(
                "recording-1",
                10_000,
                liteTrack(),
                listOf(PrototypeCandidateInterval("late", 9_900, 10_001, PrototypeCandidateKind.DETECTED_MOVEMENT)),
                0,
                0,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            LiteHeavyPrototypePlanner.plan(
                "recording-1",
                10_000,
                liteTrack().copy(role = PrototypeTrackRole.HEAVY_WINDOW),
                emptyList(),
                0,
                0,
            )
        }
    }

    @Test
    fun `completed Heavy outcome keeps independent track identity and permits zero or multiple movements`() {
        val request = LiteHeavyPrototypePlanner.plan(
            "recording-1",
            10_000,
            liteTrack(),
            listOf(PrototypeCandidateInterval("movement", 2_000, 3_000, PrototypeCandidateKind.DETECTED_MOVEMENT)),
            500,
            500,
        ).executionWindows.single()
        val heavy = PrototypeTrackProvenance(
            recordingId = "recording-1",
            landmarkTrackId = "heavy-track-1",
            role = PrototypeTrackRole.HEAVY_WINDOW,
            modelKey = "pose_heavy",
            modelSha256 = "heavy-sha",
            pipelineConfiguration = "heavy-config",
            geometryBinding = geometry("heavy-track-1", "heavy-content-sha"),
            geometryCompatibilityKey = "recording-transform-1",
            fileReference = "prototype/heavy-window-1.mls",
            formatId = "movement_landmark_stream_v1",
            formatVersion = 1,
            contentSha256 = "heavy-content-sha",
            requestedStartTimestampMs = 1_500,
            requestedEndTimestampMs = 3_500,
            actualStartTimestampMs = 1_533,
            actualEndTimestampMs = 3_467,
            frameCount = 59,
            extractionElapsedMs = 750,
        )

        val empty = PrototypeHeavyWindowOutcome(
            request,
            PrototypeWindowState.COMPLETED,
            heavy,
            emptyList(),
            setOf(
                PrototypeWindowUncertainty.TRACKING_RESET_AT_WINDOW_START,
                PrototypeWindowUncertainty.ACTUAL_COVERAGE_NARROWER,
            ),
        )
        assertTrue(empty.finalMovements.isEmpty())

        val multiple = empty.copy(
            finalMovements = listOf(
                PrototypeFinalMovementInterval(1_900, 2_300, QomEndBoundaryKind.STOP_GATE),
                PrototypeFinalMovementInterval(2_600, 3_100, QomEndBoundaryKind.STOP_GATE),
            ),
        )
        assertEquals(2, multiple.finalMovements.size)
        assertTrue(multiple.heavyTrack?.landmarkTrackId != liteTrack().landmarkTrackId)
    }

    @Test
    fun `merges touching requests but retains disjoint execution windows`() {
        val plan = LiteHeavyPrototypePlanner.plan(
            "recording-1",
            10_000,
            liteTrack(),
            listOf(
                PrototypeCandidateInterval("a", 1_000, 1_500, PrototypeCandidateKind.DETECTED_MOVEMENT),
                PrototypeCandidateInterval("b", 2_500, 3_000, PrototypeCandidateKind.DETECTED_MOVEMENT),
                PrototypeCandidateInterval("c", 7_000, 7_500, PrototypeCandidateKind.DETECTED_MOVEMENT),
            ),
            500,
            500,
        )

        assertEquals(3, plan.windows.size)
        assertEquals(2, plan.executionWindows.size)
        assertEquals(listOf("heavy-window-1", "heavy-window-2"), plan.executionWindows[0].candidateWindowIds)
        assertEquals(listOf("heavy-window-3"), plan.executionWindows[1].candidateWindowIds)
        assertEquals(500, plan.executionWindows[0].requestedStartTimestampMs)
        assertEquals(3_500, plan.executionWindows[0].requestedEndTimestampMs)
    }

    private fun liteTrack(
        requestedEndTimestampMs: Long = 10_000,
        actualEndTimestampMs: Long = requestedEndTimestampMs,
    ) = PrototypeTrackProvenance(
        recordingId = "recording-1",
        landmarkTrackId = "lite-track-1",
        role = PrototypeTrackRole.LITE_DISCOVERY,
        modelKey = "pose_lite",
        modelSha256 = "lite-sha",
        pipelineConfiguration = "lite-config",
        geometryBinding = geometry("lite-track-1", "lite-content-sha"),
        geometryCompatibilityKey = "recording-transform-1",
        fileReference = "prototype/lite.mls",
        formatId = "movement_landmark_stream_v1",
        formatVersion = 1,
        contentSha256 = "lite-content-sha",
        requestedStartTimestampMs = 0,
        requestedEndTimestampMs = requestedEndTimestampMs,
        actualStartTimestampMs = 0,
        actualEndTimestampMs = actualEndTimestampMs,
        frameCount = 301,
        extractionElapsedMs = 1_500,
    )

    private fun geometry(trackId: String, trackHash: String) = CanonicalGeometryDescriptor(
        geometryId = "geometry-recording-1",
        recordingId = "recording-1",
        landmarkTrackId = trackId,
        canonicalWidth = 1_080,
        canonicalHeight = 1_920,
        sourceHash = "source-sha",
        trackHash = trackHash,
    )
}
