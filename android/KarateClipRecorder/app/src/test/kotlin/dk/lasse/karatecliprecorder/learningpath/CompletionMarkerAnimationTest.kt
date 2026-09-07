package dk.lasse.karatecliprecorder.learningpath

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompletionMarkerAnimationTest {
    @Test fun completionAnimationUsesAllTwelveVectorKeyframes() {
        assertEquals(12, CompletionMarkerAnimationSpec.frameResourceIds.size)
        assertEquals(
            CompletionMarkerAnimationSpec.frameResourceIds.last(),
            ProgressMarkerAsset.COMPLETED.rawResourceId,
        )
    }

    @Test fun animationCrossfadesBetweenAdjacentVectorFrames() {
        assertEquals(CompletionMarkerFrameBlend(0, 1, 0f), CompletionMarkerAnimationSpec.blend(0f))
        assertEquals(CompletionMarkerFrameBlend(4, 5, 0.5f), CompletionMarkerAnimationSpec.blend(4.5f))
        assertEquals(CompletionMarkerFrameBlend(11, 11, 0f), CompletionMarkerAnimationSpec.blend(99f))
        assertTrue(CompletionMarkerAnimationSpec.DURATION_MS in 500L..1_000L)
    }
}
