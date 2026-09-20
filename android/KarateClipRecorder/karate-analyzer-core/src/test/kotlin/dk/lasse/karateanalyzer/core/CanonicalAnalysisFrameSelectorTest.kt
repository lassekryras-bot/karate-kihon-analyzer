package dk.lasse.karateanalyzer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CanonicalAnalysisFrameSelectorTest {

    private fun landmarkSample(x: Float, y: Float, confidence: Float = 0.9f) =
        PoseLandmarkSample(Point3(x, y, 0f), Point3(x, y, 0f), confidence, confidence, LandmarkSource.OBSERVED)

    private fun buildFrame(timestampMs: Long, reachX: Float): PoseFrame {
        val landmarks = mutableMapOf<PoseLandmarkId, PoseLandmarkSample>()
        landmarks[PoseLandmarkId.LEFT_SHOULDER] = landmarkSample(0.5f, 0.3f)
        landmarks[PoseLandmarkId.LEFT_WRIST] = landmarkSample(0.5f - reachX, 0.3f)
        return PoseFrame(timestampMs, landmarks)
    }

    @Test
    fun selectsPeakExtensionFrameWithinWindow() {
        val frames = listOf(
            buildFrame(100L, 0.10f),
            buildFrame(200L, 0.25f),
            buildFrame(300L, 0.38f), // Peak extension
            buildFrame(400L, 0.32f),
            buildFrame(500L, 0.15f),
        )

        val result = CanonicalAnalysisFrameSelector.select(
            startUs = 150_000L,
            endUs = 450_000L,
            frames = frames,
        )

        assertNotNull(result)
        assertEquals(300L, result?.timestampMs)
        assertEquals(300_000L, result?.timestampUs)
        assertEquals(2L, result?.frameIndex)
        assertEquals(CanonicalAnalysisFrameSelector.CanonicalFrameResult.STRATEGY_PEAK_EXTENSION, result?.strategy)
    }

    @Test
    fun returnsNullWhenFramesEmpty() {
        val result = CanonicalAnalysisFrameSelector.select(0L, 100_000L, emptyList())
        assertNull(result)
        val resolved = CanonicalAnalysisFrameSelector.resolve(0L, 100_000L, 50_000L, emptyList())
        assertNull(resolved)
    }

    @Test
    fun resolvesPersistedCanonicalFrameDirectly() {
        val frames = listOf(
            buildFrame(100L, 0.10f),
            buildFrame(200L, 0.25f),
            buildFrame(300L, 0.38f),
            buildFrame(400L, 0.32f),
        )

        val resolved = CanonicalAnalysisFrameSelector.resolve(
            startUs = 100_000L,
            endUs = 400_000L,
            analysisFrameUs = 200_000L,
            frames = frames,
        )

        assertNotNull(resolved)
        val (result, frame) = resolved!!
        assertEquals(200L, result.timestampMs)
        assertEquals(200_000L, result.timestampUs)
        assertEquals(1L, result.frameIndex)
        assertEquals(CanonicalAnalysisFrameSelector.CanonicalFrameResult.STRATEGY_PERSISTED_CANONICAL, result.strategy)
        assertEquals(200L, frame.timestampMs)
    }

    @Test
    fun resolveFallsBackToSelectWhenAnalysisFrameUsIsNull() {
        val frames = listOf(
            buildFrame(100L, 0.10f),
            buildFrame(200L, 0.25f),
            buildFrame(300L, 0.38f), // Peak
            buildFrame(400L, 0.32f),
        )

        val resolved = CanonicalAnalysisFrameSelector.resolve(
            startUs = 150_000L,
            endUs = 350_000L,
            analysisFrameUs = null,
            frames = frames,
        )

        assertNotNull(resolved)
        val (result, frame) = resolved!!
        assertEquals(300L, result.timestampMs)
        assertEquals(2L, result.frameIndex)
        assertEquals(CanonicalAnalysisFrameSelector.CanonicalFrameResult.STRATEGY_PEAK_EXTENSION, result.strategy)
        assertEquals(300L, frame.timestampMs)
    }
}
