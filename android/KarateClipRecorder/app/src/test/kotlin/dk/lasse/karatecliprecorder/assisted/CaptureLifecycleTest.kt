package dk.lasse.karatecliprecorder.assisted

import android.os.Looper
import android.os.SystemClock
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import dk.lasse.karatecliprecorder.training.*
import dk.lasse.karatecliprecorder.captureprofile.*
import dk.lasse.karatecliprecorder.sharedcapture.*
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CaptureLifecycleTest {
    private var stops = 0
    private var cues = 0
    private val events = mutableListOf<Pair<String, Long>>()
    private val controller = AssistedCaptureController({ _, ready -> ready {} }, { stops++ }, { true }, {},
        { _, _, _ -> cues++ }, { _, _ -> }, persistBoundary = { type, time, _ -> events += type to time })
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun start(spoken: Boolean = true): Long {
        controller.record(SharedCaptureRequests.recordAndAnalyze("Alternating straight punches", "Punches", 10, 1000, spoken)); advance(3000)
        val time = SystemClock.elapsedRealtime(); controller.recordingStarted(time); return time
    }
    @Test fun gracefulStopUsesLastEmittedCueAndSuppressesFutureCues() {
        val start = start(); advance(900); controller.stop()
        assertEquals(listOf("STOP_REQUESTED" to start + 900), events)
        assertEquals(AssistedCaptureState.FINISHING, controller.state)
        advance(1099); assertEquals(0, stops); assertEquals(1, cues)
        advance(1); assertEquals(1, stops)
        assertEquals(AssistedCaptureState.FINALIZING, controller.state)
    }
    @Test fun secondTapStopsImmediatelyAndPersistsSeparateRequest() {
        val start = start(); advance(700); controller.stop(); advance(100); controller.stop()
        assertEquals(listOf("STOP_REQUESTED" to start + 700, "FORCE_STOP_REQUESTED" to start + 800), events)
        assertEquals(1, stops); advance(5000); assertEquals(1, stops)
    }
    @Test fun silentFallbackIsRequestPlusOneAndAHalfCadences() {
        val start = start(false); advance(200); controller.stop(); advance(1499)
        assertEquals(0, stops); advance(1); assertEquals(1, stops)
        assertEquals(start + 1700, AssistedCaptureController.gracefulEndMs(null, start + 200, 1000))
    }
    @Test fun interruptionEndsGracefulWaitAndPreservesReasonBoundary() {
        start(); advance(600); controller.stop(); controller.interrupt("phone_locked")
        assertEquals(1, stops); assertEquals("INTERRUPTION_DETECTED", events.last().first)
        controller.saved(); assertEquals(AssistedCaptureState.SAVED, controller.state)
        controller.recordAnother(); assertEquals(AssistedCaptureState.READY, controller.state)
    }
    @Test fun qualityFallbackUsesResolutionAndFpsPairs() {
        val supported = listOf(CaptureQuality("FHD", 30), CaptureQuality("HD", 60))
        assertEquals(CaptureQuality("HD", 60), AssistedCameraOptions.ordered(supported).first())
    }
}
