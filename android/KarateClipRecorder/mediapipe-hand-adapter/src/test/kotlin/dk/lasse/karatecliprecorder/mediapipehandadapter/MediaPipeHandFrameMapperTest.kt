package dk.lasse.karatecliprecorder.mediapipehandadapter

import dk.lasse.karateanalyzer.core.HandLandmarkId
import dk.lasse.karateanalyzer.core.Handedness
import dk.lasse.karateanalyzer.core.LandmarkSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaPipeHandFrameMapperTest {
    private val mapper = MediaPipeHandFrameMapper()

    @Test fun all21LandmarksMapToCorrectHandLandmarkIdWithoutMirroring() {
        val points = HandLandmarkId.entries.map { id -> MediaPipePoint3(id.ordinal.toFloat(), id.ordinal.toFloat() + 0.25f, id.ordinal.toFloat() + 0.5f) }
        val frame = mapper.toHandFrame(mapper.toDetectedHands(listOf(observation(points = points))).single())

        HandLandmarkId.entries.forEach { id ->
            val sample = frame.landmarks.getValue(id)
            assertEquals(id.ordinal.toFloat(), sample.position!!.x)
            assertEquals(id.ordinal.toFloat() + 0.25f, sample.position!!.y)
            assertEquals(id.ordinal.toFloat() + 0.5f, sample.position!!.z)
            assertEquals(LandmarkSource.OBSERVED, sample.source)
        }
    }

    @Test fun leftRightAndUnknownHandednessAreMapped() {
        assertEquals(Handedness.LEFT, mapper.toHandFrame(mapper.toDetectedHands(listOf(observation(label = "Left"))).single()).handedness)
        assertEquals(Handedness.RIGHT, mapper.toHandFrame(mapper.toDetectedHands(listOf(observation(label = "Right"))).single()).handedness)
        assertEquals(Handedness.UNKNOWN, mapper.toHandFrame(mapper.toDetectedHands(listOf(observation(label = "NotAHand"))).single()).handedness)
        assertEquals(Handedness.UNKNOWN, mapper.toHandFrame(mapper.toDetectedHands(listOf(observation(label = null))).single()).handedness)
    }

    @Test fun timestampsArePreserved() {
        val hand = mapper.toDetectedHands(listOf(observation(timestamp = 9876))).single()
        assertEquals(9876, hand.frameTimestampMs)
        assertEquals(9876, mapper.toHandFrame(hand).timestampMs)
    }

    @Test fun emptyDetectionResultsAreSupported() {
        assertTrue(mapper.toDetectedHands(emptyList()).isEmpty())
        assertNull(HighestConfidenceActiveHandSelector().select(emptyList()))
    }

    @Test fun absentPointConfidenceDoesNotBecomeOne() {
        val frame = mapper.toHandFrame(mapper.toDetectedHands(listOf(observation(score = null))).single())
        assertEquals(0f, frame.landmarks.getValue(HandLandmarkId.WRIST).confidence)
    }

    @Test fun handLevelConfidencePropagatesWhenLandmarkConfidenceIsUnavailable() {
        val hand = mapper.toDetectedHands(listOf(observation(score = 0.42f))).single()
        val frame = mapper.toHandFrame(hand)
        assertEquals(0.42f, frame.landmarks.getValue(HandLandmarkId.WRIST).confidence)
        assertEquals(0.42f, hand.handConfidence)
    }

    @Test fun pointConfidencePrecedesVisibilityAndHandConfidenceAndIsClamped() {
        val point = MediaPipePoint3(0f, 0f, 0f, presence = 1.4f, visibility = 0.2f)
        val frame = mapper.toHandFrame(mapper.toDetectedHands(listOf(observation(points = listOf(point) + points(20), score = 0.1f))).single())
        assertEquals(1f, frame.landmarks.getValue(HandLandmarkId.WRIST).confidence)
    }

    @Test fun visibilityConfidenceIsUsedWhenPresenceUnavailableAndClamped() {
        val point = MediaPipePoint3(0f, 0f, 0f, presence = Float.NaN, visibility = -0.5f)
        val frame = mapper.toHandFrame(mapper.toDetectedHands(listOf(observation(points = listOf(point) + points(20), score = 0.9f))).single())
        assertEquals(0f, frame.landmarks.getValue(HandLandmarkId.WRIST).confidence)
    }

    @Test fun nonFiniteCoordinatesBecomeMissingSamples() {
        listOf(
            MediaPipePoint3(Float.NaN, 0f, 0f),
            MediaPipePoint3(Float.POSITIVE_INFINITY, 0f, 0f),
            MediaPipePoint3(Float.NEGATIVE_INFINITY, 0f, 0f),
            MediaPipePoint3(0f, Float.NaN, 0f),
            MediaPipePoint3(0f, Float.POSITIVE_INFINITY, 0f),
            MediaPipePoint3(0f, Float.NEGATIVE_INFINITY, 0f),
            MediaPipePoint3(0f, 0f, Float.NaN),
            MediaPipePoint3(0f, 0f, Float.POSITIVE_INFINITY),
            MediaPipePoint3(0f, 0f, Float.NEGATIVE_INFINITY),
        ).forEach { badPoint ->
            val frame = mapper.toHandFrame(mapper.toDetectedHands(listOf(observation(points = listOf(badPoint) + points(20)))).single())
            val wrist = frame.landmarks.getValue(HandLandmarkId.WRIST)
            assertNull(wrist.position)
            assertEquals(0f, wrist.confidence)
            assertEquals(LandmarkSource.MISSING, wrist.source)
        }
    }

    @Test fun malformedNormalizedLandmarksAreExcluded() {
        val hands = mapper.toDetectedHands(listOf(observation(points = points(20), score = 0.99f)))
        assertTrue(hands.isEmpty())
    }

    @Test fun malformedWorldLandmarksAreIgnoredSafely() {
        val hand = mapper.toDetectedHands(listOf(observation(worldPoints = points(20)))).single()
        assertNull(hand.worldLandmarks)
        assertTrue(hand.isValid)
    }

    @Test fun invalidWorldGeometryBecomesNull() {
        listOf(
            MediaPipePoint3(Float.NaN, 0f, 0f),
            MediaPipePoint3(Float.POSITIVE_INFINITY, 0f, 0f),
            MediaPipePoint3(Float.NEGATIVE_INFINITY, 0f, 0f),
            MediaPipePoint3(0f, Float.NaN, 0f),
            MediaPipePoint3(0f, Float.POSITIVE_INFINITY, 0f),
            MediaPipePoint3(0f, Float.NEGATIVE_INFINITY, 0f),
            MediaPipePoint3(0f, 0f, Float.NaN),
            MediaPipePoint3(0f, 0f, Float.POSITIVE_INFINITY),
            MediaPipePoint3(0f, 0f, Float.NEGATIVE_INFINITY),
        ).forEach { badWorldPoint ->
            val hand = mapper.toDetectedHands(listOf(observation(worldPoints = listOf(badWorldPoint) + points(20)))).single()
            assertNull(hand.worldLandmarks)
        }
    }

    @Test fun malformedHandsCannotWinActiveHandSelection() {
        val valid = mapper.toDetectedHands(listOf(observation(score = 0.20f))).single()
        val malformed = DetectedHand(0, "Right", 1f, points(20), null, null, null)
        val selected = HighestConfidenceActiveHandSelector().select(listOf(valid, malformed))
        assertEquals(valid, selected)
        assertFalse(malformed.isValid)
    }

    @Test fun activeHandSelectionUsesUsableLandmarksBeforeGenuineHandLevelConfidence() {
        val partiallyUsableHighConfidence = mapper.toDetectedHands(
            listOf(observation(points = listOf(MediaPipePoint3(Float.NaN, Float.NaN, Float.NaN)) + points(20), score = 1f)),
        ).single()
        val fullyUsableLowerConfidence = mapper.toDetectedHands(listOf(observation(score = 0.2f))).single()
        assertEquals(20, partiallyUsableHighConfidence.usableLandmarkCount)
        assertEquals(21, fullyUsableLowerConfidence.usableLandmarkCount)
        assertEquals(fullyUsableLowerConfidence, HighestConfidenceActiveHandSelector().select(listOf(partiallyUsableHighConfidence, fullyUsableLowerConfidence)))
    }

    @Test fun allNanHighConfidenceHandLosesToFiniteLowerConfidenceHand() {
        val unusableHighConfidence = mapper.toDetectedHands(listOf(observation(points = List(21) { MediaPipePoint3(Float.NaN, Float.NaN, Float.NaN) }, score = 1f))).single()
        val usableLowerConfidence = mapper.toDetectedHands(listOf(observation(score = 0.1f))).single()
        assertEquals(0, unusableHighConfidence.usableLandmarkCount)
        assertEquals(usableLowerConfidence, HighestConfidenceActiveHandSelector().select(listOf(unusableHighConfidence, usableLowerConfidence)))
    }

    @Test fun zeroUsableLandmarkHandsAreNotSelectable() {
        val unusable = mapper.toDetectedHands(listOf(observation(points = List(21) { MediaPipePoint3(Float.NaN, Float.NaN, Float.NaN) }, score = 1f))).single()
        assertNull(HighestConfidenceActiveHandSelector().select(listOf(unusable)))
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
        val validator = GestureRecognizerModelAssetValidator(
            assetExists = { assetPath ->
                assetPath == GESTURE_RECOGNIZER_MODEL_ASSET_PATH
            },
        )

        assertEquals(
            GESTURE_RECOGNIZER_MODEL_ASSET_PATH,
            validator.validate(),
        )
    }

    @Test fun modelAssetValidatorThrowsDocumentedExceptionWhenAbsent() {
        val exception = assertFailsWith<MissingGestureRecognizerModelException> {
            GestureRecognizerModelAssetValidator(
                assetExists = { false },
            ).validate()
        }

        assertTrue(
            exception.message!!.contains(
                GESTURE_RECOGNIZER_MODEL_ASSET_PATH,
            ),
        )
    }

    @Test fun multipleDtoHandsAreMappedByHandIndex() {
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
        worldPoints: List<MediaPipePoint3>? = points(21),
        label: String? = "Right",
        score: Float? = 0.75f,
        openScore: Float? = null,
        closedScore: Float? = null,
        timestamp: Long = 123,
    ) = MediaPipeHandObservation(
        normalizedLandmarks = points,
        worldLandmarks = worldPoints,
        handednessLabel = label,
        handednessScore = score,
        openPalmScore = openScore,
        closedFistScore = closedScore,
        timestampMs = timestamp,
    )

    private fun points(count: Int): List<MediaPipePoint3> = List(count) { index -> MediaPipePoint3(index / 20f, index / 20f, 0f) }
}
