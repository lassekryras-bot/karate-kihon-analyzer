package dk.lasse.karatecliprecorder.mediapipehandadapter

import com.google.mediapipe.tasks.components.containers.Category
import com.google.mediapipe.tasks.components.containers.Landmark
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GestureRecognizerResultAdapterCompileTest {
    @Test fun productionBridgeCompilesAgainstTasksVisionAndConvertsHandsByIndex() {
        val result = object : GestureRecognizerResult() {
            override fun gestures(): List<List<Category>> = listOf(
                mutableListOf(Category.create(1.4f, -1, "Open_Palm", "")),
                mutableListOf(Category.create(Float.NaN, -1, "Closed_Fist", "")),
            )

            override fun handedness(): List<List<Category>> = listOf(
                mutableListOf(Category.create(0.6f, 0, "Left", "")),
                mutableListOf(Category.create(0.9f, 1, "Right", "")),
            )

            override fun landmarks(): List<List<NormalizedLandmark>> = listOf(
                normalizedHand(),
                normalizedHand(),
            )

            override fun worldLandmarks(): List<List<Landmark>> = listOf(
                worldHand(),
                worldHand(),
            )

            override fun timestampMs(): Long = 77L
        }

        val hands = GestureRecognizerResultAdapter().adapt(result)

        assertEquals(2, hands.size)
        assertEquals("Left", hands[0].handednessLabel)
        assertEquals(0.6f, hands[0].handednessScore)
        assertEquals(1f, hands[0].openPalmScore)
        assertEquals("Right", hands[1].handednessLabel)
        assertEquals(0.9f, hands[1].handednessScore)
        assertNull(hands[1].closedFistScore)
        assertEquals(77L, hands[0].timestampMs)
    }

    private fun normalizedHand(): List<NormalizedLandmark> = List(MEDIAPIPE_HAND_LANDMARK_COUNT) { index ->
        NormalizedLandmark.create(index / 20f, index / 20f, 0f, Optional.empty(), Optional.of(0.5f))
    }

    private fun worldHand(): List<Landmark> = List(MEDIAPIPE_HAND_LANDMARK_COUNT) { index ->
        Landmark.create(index.toFloat(), index.toFloat(), 0f, Optional.empty(), Optional.empty())
    }
}
