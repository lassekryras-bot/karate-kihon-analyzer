package dk.lasse.karatecliprecorder.mediapipehandadapter

import com.google.mediapipe.tasks.components.containers.Category
import com.google.mediapipe.tasks.components.containers.Landmark
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import java.util.Optional

/** Converts the production MediaPipe Tasks Vision Gesture Recognizer result into pure adapter DTOs. */
class GestureRecognizerResultAdapter {
    fun adapt(result: GestureRecognizerResult, timestampMs: Long? = null): List<MediaPipeHandObservation> {
        val normalizedHands = result.landmarks()
        val worldHands = result.worldLandmarks()
        val handedness = result.handedness()
        val gestures = result.gestures()
        val timestamp = timestampMs ?: result.timestampMs()
        return normalizedHands.indices.mapNotNull { index ->
            val normalized = normalizedHands.getOrNull(index).orEmpty()
            if (normalized.size != MEDIAPIPE_HAND_LANDMARK_COUNT) return@mapNotNull null
            val handCategories = handedness.getOrNull(index).orEmpty()
            val bestHand = handCategories.maxByOrNull { it.score().finiteUnitOrNull() ?: -1f }
            val gestureCategories = gestures.getOrNull(index).orEmpty()
            MediaPipeHandObservation(
                normalizedLandmarks = normalized.map { it.toPoint() },
                worldLandmarks = worldHands.getOrNull(index)?.takeIf { it.size == MEDIAPIPE_HAND_LANDMARK_COUNT }?.map { it.toPoint() },
                handednessLabel = bestHand?.categoryName(),
                handednessScore = bestHand?.score().finiteUnitOrNull(),
                openPalmScore = gestureCategories.scoreFor("Open_Palm"),
                closedFistScore = gestureCategories.scoreFor("Closed_Fist"),
                timestampMs = timestamp,
            )
        }
    }

    private fun NormalizedLandmark.toPoint(): MediaPipePoint3 = MediaPipePoint3(
        x = x(),
        y = y(),
        z = z(),
        presence = presence().finiteUnitOrNull(),
        visibility = visibility().finiteUnitOrNull(),
    )

    private fun Landmark.toPoint(): MediaPipePoint3 = MediaPipePoint3(
        x = x(),
        y = y(),
        z = z(),
        presence = presence().finiteUnitOrNull(),
        visibility = visibility().finiteUnitOrNull(),
    )

    private fun Optional<Float>.finiteUnitOrNull(): Float? = if (isPresent) get().finiteUnitOrNull() else null

    private fun List<Category>.scoreFor(name: String): Float? = firstOrNull { it.categoryName() == name }
        ?.score()
        .finiteUnitOrNull()
}
