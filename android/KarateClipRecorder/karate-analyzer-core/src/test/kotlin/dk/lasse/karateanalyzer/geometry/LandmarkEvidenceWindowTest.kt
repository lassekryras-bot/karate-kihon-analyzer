package dk.lasse.karateanalyzer.geometry

import org.junit.Assert.*
import org.junit.Test

class LandmarkEvidenceWindowTest {

    data class MockSample(
        val timestampUs: Long,
        val trackId: String? = null,
        val payload: String = "data",
    )

    @Test
    fun exactSampleModeSelectsAnchorOnly() {
        val samples = listOf(
            MockSample(100_000L),
            MockSample(133_333L),
            MockSample(166_666L),
        )

        val policy = WindowPolicy(mode = WindowMode.EXACT_SAMPLE, radius = 2)
        val res = LandmarkEvidenceWindow.selectWindow(
            evaluationTimestampUs = 133_000L,
            samples = samples,
            timestampExtractor = { it.timestampUs },
            policy = policy,
        )

        assertEquals(MeasurementResultState.AVAILABLE, res.state)
        assertEquals(133_333L, res.selectedSample?.timestampUs)
        assertEquals(1, res.contributingSamples.size)
        assertEquals(WindowState.SINGLE_FRAME_REQUEST, res.diagnostics.windowState)
    }

    @Test
    fun centeredSamplesModeSelectsNeighborsBothSides() {
        val samples = (0..6).map { MockSample(it * 33_333L) }
        val policy = WindowPolicy(mode = WindowMode.CENTERED_SAMPLES, radius = 1)
        val res = LandmarkEvidenceWindow.selectWindow(
            evaluationTimestampUs = 99_999L, // index 3
            samples = samples,
            timestampExtractor = { it.timestampUs },
            policy = policy,
        )

        assertEquals(MeasurementResultState.AVAILABLE, res.state)
        assertEquals(99_999L, res.selectedSample?.timestampUs)
        assertEquals(3, res.contributingSamples.size)
        assertEquals(listOf(66_666L, 99_999L, 133_332L), res.diagnostics.contributingTimestampsUs)
        assertEquals(WindowState.FULL_REQUESTED_WINDOW, res.diagnostics.windowState)
    }

    @Test
    fun trailingModeEnforcesStrictCausality() {
        val historicalSamples = listOf(
            MockSample(100_000L),
            MockSample(133_333L),
            MockSample(166_666L),
        )

        val policy = WindowPolicy(mode = WindowMode.TRAILING_SAMPLES, radius = 2)
        val resWithoutFuture = LandmarkEvidenceWindow.selectWindow(
            evaluationTimestampUs = 166_666L,
            samples = historicalSamples,
            timestampExtractor = { it.timestampUs },
            policy = policy,
        )

        // Add future frames after evaluation timestamp
        val withFutureSamples = historicalSamples + listOf(
            MockSample(200_000L),
            MockSample(233_333L),
            MockSample(266_666L),
        )

        val resWithFuture = LandmarkEvidenceWindow.selectWindow(
            evaluationTimestampUs = 166_666L,
            samples = withFutureSamples,
            timestampExtractor = { it.timestampUs },
            policy = policy,
        )

        assertEquals(MeasurementResultState.AVAILABLE, resWithoutFuture.state)
        assertEquals(MeasurementResultState.AVAILABLE, resWithFuture.state)
        assertEquals(resWithoutFuture.contributingSamples, resWithFuture.contributingSamples)
        assertEquals(resWithoutFuture.diagnostics.contributingTimestampsUs, resWithFuture.diagnostics.contributingTimestampsUs)
        // Verify future timestamps are completely absent
        assertFalse(resWithFuture.diagnostics.contributingTimestampsUs.any { it > 166_666L })
    }

    @Test
    fun gapBarrierDisconnectsAllFartherSamplesWithoutBackfill() {
        val samples = listOf(
            MockSample(0L),
            MockSample(33_333L),
            MockSample(200_000L), // Gap > 100ms from 33_333L
            MockSample(233_333L),
            MockSample(266_666L), // Evaluation anchor at 266_666L
            MockSample(300_000L),
        )

        val policy = WindowPolicy(mode = WindowMode.CENTERED_SAMPLES, radius = 3, maxTimestampGapUs = 100_000L)
        val res = LandmarkEvidenceWindow.selectWindow(
            evaluationTimestampUs = 266_666L,
            samples = samples,
            timestampExtractor = { it.timestampUs },
            policy = policy,
        )

        // Anchor is at index 4 (266_666L).
        // Backward: index 3 (233_333L) is connected (gap 33ms).
        // index 2 (200_000L) is connected (gap 33ms).
        // index 1 (33_333L) has gap 166_667L > 100ms: disconnects index 1 and index 0!
        assertEquals(listOf(200_000L, 233_333L, 266_666L, 300_000L), res.diagnostics.contributingTimestampsUs)
        assertTrue(res.diagnostics.excludedTimestampsUs.contains(33_333L))
        assertTrue(res.diagnostics.exclusionReasons.containsKey(33_333L))
        assertEquals(WindowState.REDUCED_WINDOW, res.diagnostics.windowState)
    }

    @Test
    fun outOfRangeToleranceRejectsDistantAnchor() {
        val samples = listOf(MockSample(1_000_000L))
        val policy = WindowPolicy(maxTimeToleranceUs = 50_000L)
        val res = LandmarkEvidenceWindow.selectWindow(
            evaluationTimestampUs = 900_000L, // distance 100_000us > 50_000us
            samples = samples,
            timestampExtractor = { it.timestampUs },
            policy = policy,
        )

        assertEquals(MeasurementResultState.UNAVAILABLE, res.state)
        assertTrue(res.failureReason?.contains("outside_sample_tolerance") == true)
    }

    @Test
    fun maxTotalTimeSpanExclusion() {
        val samples = listOf(
            MockSample(100_000L),
            MockSample(133_333L),
            MockSample(166_666L),
            MockSample(200_000L),
            MockSample(233_333L),
        )

        // Radius 2 around 166_666L gives total span 133_333us. If maxTotalTimeSpanUs is 80_000us:
        val policy = WindowPolicy(radius = 2, maxTotalTimeSpanUs = 80_000L)
        val res = LandmarkEvidenceWindow.selectWindow(
            evaluationTimestampUs = 166_666L,
            samples = samples,
            timestampExtractor = { it.timestampUs },
            policy = policy,
        )

        assertEquals(listOf(133_333L, 166_666L, 200_000L), res.diagnostics.contributingTimestampsUs)
        assertTrue(res.diagnostics.excludedTimestampsUs.contains(100_000L))
        assertTrue(res.diagnostics.excludedTimestampsUs.contains(233_333L))
    }

    @Test
    fun minimumUsableSamplesThreshold() {
        val samples = listOf(MockSample(100_000L))
        val policy = WindowPolicy(radius = 1, minimumUsableSamples = 3)
        val res = LandmarkEvidenceWindow.selectWindow(
            evaluationTimestampUs = 100_000L,
            samples = samples,
            timestampExtractor = { it.timestampUs },
            policy = policy,
        )

        assertEquals(MeasurementResultState.UNAVAILABLE, res.state)
        assertTrue(res.failureReason?.contains("insufficient_usable_samples") == true)
    }

    @Test
    fun nonMonotonicOrDuplicateTimestampsRejected() {
        val duplicateSamples = listOf(
            MockSample(100_000L),
            MockSample(100_000L), // Duplicate
            MockSample(133_333L),
        )

        val res = LandmarkEvidenceWindow.selectWindow(
            evaluationTimestampUs = 100_000L,
            samples = duplicateSamples,
            timestampExtractor = { it.timestampUs },
            policy = WindowPolicy(isStrictMonotonicityRequired = true),
        )

        assertEquals(MeasurementResultState.INVALID_REQUEST, res.state)
        assertTrue(res.failureReason?.contains("non_monotonic_or_duplicate_timestamps") == true)
    }

    @Test
    fun trackMismatchExcludedFromEvidence() {
        val samples = listOf(
            MockSample(100_000L, trackId = "track_A"),
            MockSample(133_333L, trackId = "track_A"),
            MockSample(166_666L, trackId = "track_B"), // Mismatched track
            MockSample(200_000L, trackId = "track_A"),
        )

        val res = LandmarkEvidenceWindow.selectWindow(
            evaluationTimestampUs = 133_333L,
            samples = samples,
            timestampExtractor = { it.timestampUs },
            trackIdExtractor = { it.trackId },
            expectedTrackId = "track_A",
            policy = WindowPolicy(radius = 2),
        )

        assertFalse(res.diagnostics.contributingTimestampsUs.contains(166_666L))
        assertTrue(res.diagnostics.excludedTimestampsUs.contains(166_666L))
        assertTrue(res.diagnostics.exclusionReasons[166_666L]?.contains("track_mismatch") == true)
        // 200_000L is beyond the track_B barrier, so it is also disconnected and excluded
        assertFalse(res.diagnostics.contributingTimestampsUs.contains(200_000L))
        assertTrue(res.diagnostics.excludedTimestampsUs.contains(200_000L))
    }

    @Test
    fun trailingWindowNeverConsumesFutureEvidenceAsAnchor() {
        val samples = listOf(
            MockSample(50L),
            MockSample(100L),
            MockSample(200L),
        )

        val policy = WindowPolicy(mode = WindowMode.TRAILING_SAMPLES, radius = 1)

        // Evaluation at 190L: closest sample in all samples is 200L,
        // BUT trailing mode MUST select 100L (closest sample at or before 190L)
        val res = LandmarkEvidenceWindow.selectWindow(
            evaluationTimestampUs = 190L,
            samples = samples,
            timestampExtractor = { it.timestampUs },
            policy = policy,
        )

        assertEquals(MeasurementResultState.AVAILABLE, res.state)
        assertEquals(100L, res.selectedSample?.timestampUs)
        assertEquals(listOf(50L, 100L), res.diagnostics.contributingTimestampsUs)
        assertFalse(res.diagnostics.contributingTimestampsUs.contains(200L))

        // Evaluation at boundary with no preceding samples:
        val boundarySamples = listOf(MockSample(100L), MockSample(200L))
        val resBoundary = LandmarkEvidenceWindow.selectWindow(
            evaluationTimestampUs = 190L,
            samples = boundarySamples,
            timestampExtractor = { it.timestampUs },
            policy = policy,
        )
        // Anchor is 100L, but because radius=1 has no preceding sample, it falls back to PARTIAL
        assertEquals(MeasurementResultState.PARTIAL, resBoundary.state)
        assertEquals(100L, resBoundary.selectedSample?.timestampUs)
        assertEquals(listOf(100L), resBoundary.diagnostics.contributingTimestampsUs)
        assertFalse(resBoundary.diagnostics.contributingTimestampsUs.contains(200L))

        // Evaluation at 40L: all samples are in the future -> UNAVAILABLE
        val resFutureOnly = LandmarkEvidenceWindow.selectWindow(
            evaluationTimestampUs = 40L,
            samples = samples,
            timestampExtractor = { it.timestampUs },
            policy = policy,
        )

        assertEquals(MeasurementResultState.UNAVAILABLE, resFutureOnly.state)
        assertNull(resFutureOnly.selectedSample)
    }

    @Test
    fun trackMismatchActsAsBoundaryBarrierDisconnectingBeyond() {
        // Interleaved sequence: A -> B -> A
        val samples = listOf(
            MockSample(100_000L, trackId = "track_A"),
            MockSample(133_333L, trackId = "track_B"), // Interleaved track B
            MockSample(166_666L, trackId = "track_A"), // Evaluation anchor at 166_666L
        )

        val res = LandmarkEvidenceWindow.selectWindow(
            evaluationTimestampUs = 166_666L,
            samples = samples,
            timestampExtractor = { it.timestampUs },
            trackIdExtractor = { it.trackId },
            expectedTrackId = "track_A",
            policy = WindowPolicy(radius = 2),
        )

        assertEquals(166_666L, res.selectedSample?.timestampUs)
        // Only 166_666L should contribute!
        // 133_333L (B) is a track mismatch barrier, which disconnects 100_000L (A) across it!
        assertEquals(listOf(166_666L), res.diagnostics.contributingTimestampsUs)
        assertTrue(res.diagnostics.excludedTimestampsUs.contains(133_333L))
        assertTrue(res.diagnostics.excludedTimestampsUs.contains(100_000L))
    }
}
