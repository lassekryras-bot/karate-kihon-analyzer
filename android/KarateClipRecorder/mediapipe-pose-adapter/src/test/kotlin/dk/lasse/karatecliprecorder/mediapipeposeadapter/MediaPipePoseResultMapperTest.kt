package dk.lasse.karatecliprecorder.mediapipeposeadapter

import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.Landmark
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import dk.lasse.karateanalyzer.core.PoseLandmarkId
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MediaPipePoseResultMapperTest {
    @Test fun mapsAllThirtyThreeLandmarksWithVisibilityPresenceAndWorldCoordinates() {
        val normalized = PoseLandmarkId.entries.mapIndexed { index, _ ->
            NormalizedLandmark.create(index / 100f, index / 90f, -index / 110f, Optional.of(0.8f), Optional.of(0.7f))
        }
        val world = PoseLandmarkId.entries.mapIndexed { index, _ ->
            Landmark.create(index.toFloat(), index + 0.5f, -index.toFloat(), Optional.of(0.9f), Optional.of(0.85f))
        }
        val result = object : PoseLandmarkerResult() {
            override fun landmarks(): MutableList<MutableList<NormalizedLandmark>> = mutableListOf(normalized.toMutableList())
            override fun worldLandmarks(): MutableList<MutableList<Landmark>> = mutableListOf(world.toMutableList())
            override fun segmentationMasks(): Optional<MutableList<MPImage>> = Optional.empty()
            override fun timestampMs(): Long = 42L
        }

        val frame = MediaPipePoseResultMapper.map(result)

        assertEquals(42L, frame.timestampMs)
        assertEquals(33, frame.landmarks.size)
        val rightFoot = assertNotNull(frame.landmarks[PoseLandmarkId.RIGHT_FOOT_INDEX])
        assertEquals(0.8f, rightFoot.visibility)
        assertEquals(0.7f, rightFoot.presence)
        assertEquals(32f, rightFoot.worldPosition?.x)
    }
}
