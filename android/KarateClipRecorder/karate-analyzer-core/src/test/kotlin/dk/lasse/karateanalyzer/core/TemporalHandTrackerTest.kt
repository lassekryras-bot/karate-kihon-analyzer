package dk.lasse.karateanalyzer.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TemporalHandTrackerTest {
    private val id = HandLandmarkId.INDEX_TIP
    private val wrist = HandLandmarkId.WRIST

    @Test
    fun stableObservedLandmarksArePreservedAsObserved() {
        val tracker = TemporalHandTracker()
        val result = tracker.track(frame(0, point(1f), confidence = 0.9f))

        assertEquals(LandmarkSource.OBSERVED, result.landmarks[id]?.source)
        assertEquals(0.9f, result.landmarks[id]?.confidence)
        assertEquals(point(1f), result.landmarks[id]?.position)
    }

    @Test
    fun noisyObservationsAreSmoothed() {
        val tracker = TemporalHandTracker(TrackingConfiguration(smoothingFactor = 0.25f))
        tracker.track(frame(0, point(0f)))
        val result = tracker.track(frame(33, point(4f)))

        assertEquals(LandmarkSource.OBSERVED, result.landmarks[id]?.source)
        assertEquals(1f, result.landmarks[id]?.position?.x)
    }

    @Test
    fun oneMissingFrameIsPredicted() {
        val tracker = TemporalHandTracker()
        tracker.track(frame(0, point(2f)))
        val result = tracker.track(frame(33, null))

        assertEquals(LandmarkSource.PREDICTED, result.landmarks[id]?.source)
        assertNotNull(result.landmarks[id]?.position)
    }

    @Test
    fun multipleMissingFramesArePredictedInsideShortGap() {
        val tracker = TemporalHandTracker()
        tracker.track(frame(0, point(0f)))
        val first = tracker.track(frame(33, null))
        val second = tracker.track(frame(66, null))
        val third = tracker.track(frame(99, null))

        assertEquals(LandmarkSource.PREDICTED, first.landmarks[id]?.source)
        assertEquals(LandmarkSource.PREDICTED, second.landmarks[id]?.source)
        assertEquals(LandmarkSource.PREDICTED, third.landmarks[id]?.source)
    }

    @Test
    fun livePredictionUsesRecentVelocity() {
        val tracker = TemporalHandTracker(TrackingConfiguration(smoothingFactor = 1f))
        tracker.track(frame(0, point(0f)))
        tracker.track(frame(50, point(10f)))
        val result = tracker.track(frame(100, null))

        assertEquals(LandmarkSource.PREDICTED, result.landmarks[id]?.source)
        assertEquals(20f, result.landmarks[id]?.position?.x)
    }

    @Test
    fun landmarkReacquisitionReturnsObservedSample() {
        val tracker = TemporalHandTracker(TrackingConfiguration(smoothingFactor = 1f))
        tracker.track(frame(0, point(0f)))
        tracker.track(frame(50, null))
        val result = tracker.track(frame(100, point(10f)))

        assertEquals(LandmarkSource.OBSERVED, result.landmarks[id]?.source)
        assertEquals(point(10f), result.landmarks[id]?.position)
    }

    @Test
    fun interpolationBetweenObservationsIsProvidedOnReacquisition() {
        val tracker = TemporalHandTracker(TrackingConfiguration(smoothingFactor = 1f, maximumInterpolationGapMs = 250))
        tracker.track(frame(0, point(0f)))
        tracker.track(frame(50, null))
        val result = tracker.track(frame(100, point(10f)))

        val interpolated = result.interpolatedFrames.single()
        assertEquals(50, interpolated.timestampMs)
        assertEquals(LandmarkSource.INTERPOLATED, interpolated.landmarks[id]?.source)
        assertEquals(5f, interpolated.landmarks[id]?.position?.x)
    }

    @Test
    fun gapsExceedingConfiguredLimitsBecomeMissing() {
        val tracker = TemporalHandTracker(TrackingConfiguration(maximumPredictionGapMs = 60))
        tracker.track(frame(0, point(1f)))
        val result = tracker.track(frame(100, null))

        assertEquals(LandmarkSource.MISSING, result.landmarks[id]?.source)
        assertNull(result.landmarks[id]?.position)
    }

    @Test
    fun predictedConfidenceDecaysOverGap() {
        val tracker = TemporalHandTracker(TrackingConfiguration(maximumPredictionGapMs = 100))
        tracker.track(frame(0, point(1f), confidence = 0.8f))
        val result = tracker.track(frame(50, null))

        assertEquals(0.4f, result.landmarks[id]?.confidence)
    }

    @Test
    fun handednessRemainsStableThroughShortGap() {
        val tracker = TemporalHandTracker(TrackingConfiguration(maximumPredictionGapMs = 100))
        tracker.track(frame(0, point(1f), handedness = Handedness.RIGHT))
        val result = tracker.track(frame(50, null, handedness = Handedness.UNKNOWN))

        assertEquals(Handedness.RIGHT, result.handedness)
    }

    @Test
    fun landmarkSourceIsNeverPromotedFromPredictedToObserved() {
        val tracker = TemporalHandTracker()
        tracker.track(frame(0, point(1f)))
        val result = tracker.track(frame(50, null))

        assertEquals(LandmarkSource.PREDICTED, result.landmarks[id]?.source)
    }

    @Test
    fun criticalAndNonCriticalLandmarksRemainIndependentlyDistinguishable() {
        val tracker = TemporalHandTracker()
        val result = tracker.track(
            HandFrame(
                timestampMs = 0,
                handedness = Handedness.LEFT,
                landmarks = mapOf(
                    id to sample(point(1f)),
                    wrist to sample(null, source = LandmarkSource.MISSING),
                ),
            ),
        )

        assertEquals(LandmarkSource.OBSERVED, result.landmarks[id]?.source)
        assertEquals(LandmarkSource.MISSING, result.landmarks[wrist]?.source)
    }

    @Test
    fun unreliableObservedLandmarkIsMarkedMissing() {
        val tracker = TemporalHandTracker(TrackingConfiguration(minimumObservedConfidence = 0.5f))
        val result = tracker.track(frame(0, point(1f), confidence = 0.2f))

        assertEquals(LandmarkSource.MISSING, result.landmarks[id]?.source)
    }

    @Test
    fun interpolationIsNotCreatedForLongReacquisitionGap() {
        val tracker = TemporalHandTracker(TrackingConfiguration(smoothingFactor = 1f, maximumInterpolationGapMs = 80))
        tracker.track(frame(0, point(0f)))
        tracker.track(frame(50, null))
        val result = tracker.track(frame(100, point(10f)))

        assertTrue(result.interpolatedFrames.isEmpty())
        assertEquals(LandmarkSource.OBSERVED, result.landmarks[id]?.source)
    }

    private fun frame(
        timestampMs: Long,
        position: Point3?,
        confidence: Float = 1f,
        handedness: Handedness = Handedness.LEFT,
    ) = HandFrame(timestampMs, handedness, mapOf(id to sample(position, confidence)))

    private fun sample(
        position: Point3?,
        confidence: Float = 1f,
        source: LandmarkSource = if (position == null) LandmarkSource.MISSING else LandmarkSource.OBSERVED,
    ) = LandmarkSample(position, confidence, source)

    private fun point(x: Float) = Point3(x, 0f, 0f)
}
