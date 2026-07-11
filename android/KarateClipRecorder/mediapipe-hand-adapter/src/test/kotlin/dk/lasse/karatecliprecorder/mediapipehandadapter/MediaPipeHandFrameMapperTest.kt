package dk.lasse.karatecliprecorder.mediapipehandadapter

import dk.lasse.karateanalyzer.core.HandLandmarkId
import dk.lasse.karateanalyzer.core.Handedness
import dk.lasse.karateanalyzer.core.LandmarkSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaPipeHandFrameMapperTest {
    private val mapper = MediaPipeHandFrameMapper()

    @Test fun all21LandmarksMapToCorrectIds() {
        val frame = mapper.map(42, points(), null, null)
        mediaPipeLandmarkIds.forEachIndexed { index, id ->
            assertEquals(index.toFloat(), frame.landmarks[id]?.position?.x)
        }
    }

    @Test fun mapsLeftRightAndUnknownHandedness() {
        assertEquals(Handedness.LEFT, mapper.map(1, points(), null, MediaPipeHandedness("Left")).handedness)
        assertEquals(Handedness.RIGHT, mapper.map(1, points(), null, MediaPipeHandedness("Right")).handedness)
        assertEquals(Handedness.UNKNOWN, mapper.map(1, points(), null, MediaPipeHandedness("Ambidextrous")).handedness)
        assertEquals(Handedness.UNKNOWN, mapper.map(1, points(), null, null).handedness)
    }

    @Test fun malformedLandmarkCountReturnsMissingSamples() {
        val frame = mapper.map(1, points().drop(1), null, null)
        assertTrue(frame.landmarks.values.all { it.source == LandmarkSource.MISSING && it.position == null })
        assertEquals(HandLandmarkId.entries.size, frame.landmarks.size)
    }

    @Test fun rejectsNanAndInfiniteCoordinates() {
        val bad = points().toMutableList()
        bad[0] = MediaPipePoint(Float.NaN, 0f, 0f)
        bad[1] = MediaPipePoint(0f, Float.POSITIVE_INFINITY, 0f)
        val frame = mapper.map(1, bad, null, null)
        assertEquals(LandmarkSource.MISSING, frame.landmarks[HandLandmarkId.WRIST]?.source)
        assertEquals(LandmarkSource.MISSING, frame.landmarks[HandLandmarkId.THUMB_CMC]?.source)
        assertEquals(LandmarkSource.OBSERVED, frame.landmarks[HandLandmarkId.THUMB_MCP]?.source)
    }

    @Test fun clampsConfidence() {
        val pts = points(confidence = 2f).toMutableList()
        pts[0] = MediaPipePoint(0f, 0f, 0f, -1f)
        pts[1] = MediaPipePoint(1f, 0f, 0f, Float.NaN)
        val frame = mapper.map(1, pts, null, null)
        assertEquals(0f, frame.landmarks[HandLandmarkId.WRIST]?.confidence)
        assertEquals(0f, frame.landmarks[HandLandmarkId.THUMB_CMC]?.confidence)
        assertEquals(1f, frame.landmarks[HandLandmarkId.THUMB_MCP]?.confidence)
    }

    @Test fun missingWorldLandmarksAreAllowed() {
        val hand = mapper.mapDetectedHand(1, points(), null, null)
        assertTrue(hand.worldLandmarks.isEmpty())
    }

    @Test fun emptyDetectionResultIsSupported() {
        assertTrue(mapper.mapDetectedHands(emptyList()).isEmpty())
        assertNull(HighestConfidenceActiveHandSelector.select(emptyList()))
    }

    @Test fun highestConfidenceActiveHandSelection() {
        val low = mapper.mapDetectedHand(1, points(confidence = .2f), null, MediaPipeHandedness("Left", .2f))
        val high = mapper.mapDetectedHand(1, points(confidence = .8f), null, MediaPipeHandedness("Right", .8f))
        assertEquals(high, HighestConfidenceActiveHandSelector.select(listOf(low, high)))
    }

    @Test fun multipleHandsRemainSeparate() {
        val hands = mapper.mapDetectedHands(listOf(
            MediaPipeHandObservation(1, points(offset = 0f), handedness = MediaPipeHandedness("Left", .4f)),
            MediaPipeHandObservation(2, points(offset = 100f), handedness = MediaPipeHandedness("Right", .9f)),
        ))
        assertEquals(2, hands.size)
        assertEquals(0f, hands[0].frame.landmarks[HandLandmarkId.WRIST]?.position?.x)
        assertEquals(100f, hands[1].frame.landmarks[HandLandmarkId.WRIST]?.position?.x)
    }

    @Test fun mappedObservationsUseObservedSource() {
        val frame = mapper.map(1, points(), null, null)
        assertTrue(frame.landmarks.values.all { it.source == LandmarkSource.OBSERVED })
    }

    @Test fun timestampsArePreserved() {
        assertEquals(1234L, mapper.map(1234L, points(), null, null).timestampMs)
    }

    @Test fun noMirroringIsAppliedSilently() {
        val pts = points().toMutableList()
        pts[0] = MediaPipePoint(.25f, .5f, .75f)
        val wrist = mapper.map(1, pts, null, null).landmarks[HandLandmarkId.WRIST]?.position
        assertEquals(.25f, wrist?.x)
        assertEquals(.5f, wrist?.y)
        assertEquals(.75f, wrist?.z)
    }

    private fun points(offset: Float = 0f, confidence: Float? = null): List<MediaPipePoint> =
        (0 until MEDIAPIPE_HAND_LANDMARK_COUNT).map { MediaPipePoint(offset + it, offset + it + .1f, offset + it + .2f, confidence) }
}
