package dk.lasse.karatecliprecorder.mediapipehandadapter

import dk.lasse.karateanalyzer.core.HandLandmarkId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaPipeHandFrameMapperTest {
    private val mapper = MediaPipeHandFrameMapper()

    @Test fun absentPointConfidenceDoesNotBecomeOne() {
        val hand = mapper.toDetectedHands(listOf(observation(score = null))).single()
        val frame = mapper.toHandFrame(hand)
        assertEquals(0f, frame.landmarks.getValue(HandLandmarkId.WRIST).confidence)
    }

    @Test fun handLevelConfidencePropagatesWhenLandmarkConfidenceIsUnavailable() {
        val hand = mapper.toDetectedHands(listOf(observation(score = 0.42f))).single()
        val frame = mapper.toHandFrame(hand)
        assertEquals(0.42f, frame.landmarks.getValue(HandLandmarkId.WRIST).confidence)
        assertEquals(0.42f, hand.handConfidence)
    }

    @Test fun malformedNormalizedLandmarksAreExcluded() {
        val hands = mapper.toDetectedHands(listOf(observation(points = points(20), score = 0.99f)))
        assertTrue(hands.isEmpty())
    }

    @Test fun malformedHandsCannotWinActiveHandSelection() {
        val valid = mapper.toDetectedHands(listOf(observation(score = 0.20f))).single()
        val malformed = DetectedHand(0, "Right", 1f, points(20), null, null, null)
        val selected = HighestConfidenceActiveHandSelector().select(listOf(valid, malformed))
        assertEquals(valid, selected)
        assertFalse(malformed.isValid)
    }

    @Test fun gestureScoresAreClampedAndNonFiniteScoresBecomeNull() {
        val hands = mapper.toDetectedHands(
            listOf(
                observation(openScore = 1.4f, closedScore = -0.2f),
                observation(openScore = Float.NaN, closedScore = Float.POSITIVE_INFINITY),
            ),
        )
        assertEquals(1f, hands[0].openPalmScore)
        assertEquals(0f, hands[0].closedFistScore)
        assertNull(hands[1].openPalmScore)
        assertNull(hands[1].closedFistScore)
    }

    @Test fun modelAssetValidatorSucceedsWhenPresent() {
        val validator = GestureRecognizerModelAssetValidator { it == GESTURE_RECOGNIZER_MODEL_ASSET_PATH }
        assertEquals(GESTURE_RECOGNIZER_MODEL_ASSET_PATH, validator.validate())
    }

    @Test fun modelAssetValidatorThrowsDocumentedExceptionWhenAbsent() {
        val exception = assertFailsWith<MissingGestureRecognizerModelException> {
            GestureRecognizerModelAssetValidator { false }.validate()
        }
        assertTrue(exception.message!!.contains(GESTURE_RECOGNIZER_MODEL_ASSET_PATH))
    }

    @Test fun multipleHandsAreMappedByHandIndex() {
        val hands = mapper.toDetectedHands(
            listOf(
                observation(label = "Left", score = 0.7f, openScore = 0.2f, timestamp = 10),
                observation(label = "Right", score = 0.9f, closedScore = 0.8f, timestamp = 10),
            ),
        )
        assertEquals(2, hands.size)
        assertEquals("Left", hands[0].handednessLabel)
        assertEquals(0.2f, hands[0].openPalmScore)
        assertEquals("Right", hands[1].handednessLabel)
        assertEquals(0.8f, hands[1].closedFistScore)
    }

    private fun observation(
        points: List<MediaPipePoint3> = points(21),
        label: String? = "Right",
        score: Float? = 0.75f,
        openScore: Float? = null,
        closedScore: Float? = null,
        timestamp: Long = 123,
    ) = MediaPipeHandObservation(
        normalizedLandmarks = points,
        worldLandmarks = points,
        handednessLabel = label,
        handednessScore = score,
        openPalmScore = openScore,
        closedFistScore = closedScore,
        timestampMs = timestamp,
    )

    private fun points(count: Int): List<MediaPipePoint3> = List(count) { index -> MediaPipePoint3(index / 20f, index / 20f, 0f) }
}
