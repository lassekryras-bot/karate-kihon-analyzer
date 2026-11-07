package dk.lasse.karatecliprecorder.mediapipeposeadapter

import java.io.File
import dk.lasse.karateanalyzer.capture.retrospective.VideoTimeWindow
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SequentialVideoPoseDecoderTest {

    @Test
    fun throwsWhenVideoFileDoesNotExist() {
        val nonExistent = File("build/tmp/does-not-exist-${System.currentTimeMillis()}.mp4")
        val exception = assertFailsWith<IllegalArgumentException> {
            // SequentialVideoPoseDecoder requires context, but videoFile existence is checked before context usage
            val decoder = SequentialVideoPoseDecoder(
                context = android.app.Application(),
            )
            decoder.processVideo(nonExistent)
        }
        assertTrue(exception.message?.contains("Master video file does not exist") == true)
    }

    @Test
    fun windowExtractionChecksSourceBeforeUsingAndroidContext() {
        val nonExistent = File("build/tmp/window-does-not-exist-${System.currentTimeMillis()}.mp4")
        val exception = assertFailsWith<IllegalArgumentException> {
            SequentialVideoPoseDecoder(android.app.Application()).processWindow(
                nonExistent,
                VideoTimeWindow(1_000, 2_000),
            )
        }
        assertTrue(exception.message?.contains("Master video file does not exist") == true)
    }
}
