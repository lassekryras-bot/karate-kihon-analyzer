package dk.lasse.karatecliprecorder.training

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecordedVideoFpsVerifierTest {

    @Test
    fun target60Accepts59Point94Fps() {
        val result = RecordedVideoFpsVerifier.evaluateFps(59.94, 60)
        assertTrue(result.isVerified)
        assertEquals(59.94, result.actualFps)
        assertTrue(result.details.contains("Confirmed ~60fps"))
    }

    @Test
    fun target60Accepts60Point0Fps() {
        val result = RecordedVideoFpsVerifier.evaluateFps(60.0, 60)
        assertTrue(result.isVerified)
        assertEquals(60.0, result.actualFps)
    }

    @Test
    fun target60Rejects30FpsWithExplanatoryDetails() {
        val result = RecordedVideoFpsVerifier.evaluateFps(29.97, 60)
        assertFalse(result.isVerified)
        assertEquals(29.97, result.actualFps)
        assertTrue(result.details.contains("Requested 60fps, but output video was recorded at 29.97 fps"))
    }

    @Test
    fun target30Accepts29Point97Fps() {
        val result = RecordedVideoFpsVerifier.evaluateFps(29.97, 30)
        assertTrue(result.isVerified)
        assertEquals(29.97, result.actualFps)
        assertTrue(result.details.contains("Confirmed ~30fps"))
    }

    @Test
    fun target30Accepts30Point0Fps() {
        val result = RecordedVideoFpsVerifier.evaluateFps(30.0, 30)
        assertTrue(result.isVerified)
    }

    @Test
    fun target30Rejects15Fps() {
        val result = RecordedVideoFpsVerifier.evaluateFps(15.0, 30)
        assertFalse(result.isVerified)
    }
}

