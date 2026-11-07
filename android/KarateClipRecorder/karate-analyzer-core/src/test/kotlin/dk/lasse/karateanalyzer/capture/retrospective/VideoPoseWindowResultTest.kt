package dk.lasse.karateanalyzer.capture.retrospective

import dk.lasse.karateanalyzer.core.PoseFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VideoPoseWindowResultTest {
    @Test
    fun `retains original source timestamps and reports actual coverage`() {
        val result = VideoPoseWindowResult(
            requestedWindow = VideoTimeWindow(1_000, 2_000),
            frames = listOf(PoseFrame(1_033, emptyMap()), PoseFrame(1_967, emptyMap())),
        )

        assertEquals(1_033, result.actualStartTimestampMs)
        assertEquals(1_967, result.actualEndTimestampMs)
        assertTrue(result.trackingStateResetAtWindowStart)
    }

    @Test
    fun `empty window has no observed coverage`() {
        val result = VideoPoseWindowResult(VideoTimeWindow(1_000, 2_000), emptyList())
        assertNull(result.actualStartTimestampMs)
        assertNull(result.actualEndTimestampMs)
    }

    @Test
    fun `rejects frames outside request or with non-monotonic source timestamps`() {
        assertFailsWith<IllegalArgumentException> {
            VideoPoseWindowResult(
                VideoTimeWindow(1_000, 2_000),
                listOf(PoseFrame(999, emptyMap())),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            VideoPoseWindowResult(
                VideoTimeWindow(1_000, 2_000),
                listOf(PoseFrame(1_500, emptyMap()), PoseFrame(1_500, emptyMap())),
            )
        }
    }
}
