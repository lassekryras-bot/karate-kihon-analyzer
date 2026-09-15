package dk.lasse.karatecliprecorder.assisted

import android.os.Looper
import android.os.SystemClock
import dk.lasse.karatecliprecorder.training.AssistedCaptureSetup
import dk.lasse.karatecliprecorder.sharedcapture.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AssistedCaptureControllerTest {
    private var prepared: (((() -> Unit)) -> Unit)? = null
    private var starts = 0
    private var stops = 0
    private var audioWorks = true
    private val cues = mutableListOf<Triple<Int, Int, Long>>()
    private val shown = mutableListOf<String>()
    private val controller = AssistedCaptureController(
        prepare = { _, ready -> prepared = ready }, stopCamera = { stops++ },
        playCount = { audioWorks }, stopAudio = {}, persistCue = { a, b, t -> cues += Triple(a, b, t) },
        changed = { _, text -> shown += text },
    )
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun commit() { requireNotNull(prepared).invoke { starts++ } }
    private fun request(setup: AssistedCaptureSetup = AssistedCaptureSetup()) =
        SharedCaptureRequests.recordAndAnalyze(setup.expectedActivity, setup.expectedCategory,
            setup.repetitions, setup.cadenceMs, setup.spokenCounting)
    private fun begin(setup: AssistedCaptureSetup = AssistedCaptureSetup()): Long {
        controller.record(request(setup)); commit(); advance(3000)
        val start = SystemClock.elapsedRealtime()
        controller.recordingStarted(start)
        return start
    }

    @Test fun databaseCommitPrecedesVisualCountdownAndCameraStartsOnlyAtZero() {
        controller.record(request())
        advance(5000)
        assertEquals(AssistedCaptureState.PREPARING, controller.state)
        assertEquals(0, starts)
        commit()
        assertEquals("3", shown.last())
        advance(1000); assertEquals("2", shown.last())
        advance(1000); assertEquals("1", shown.last())
        advance(999); assertEquals(0, starts)
        advance(1); assertEquals(1, starts)
        assertTrue(cues.isEmpty())
    }

    @Test fun cuesUseActualCameraStartPlusHalfSecondRepeatCycleAndAutoStop() {
        controller.record(request(AssistedCaptureSetup(repetitions = 20)))
        commit(); advance(5000) // Camera Start callback can arrive after the countdown.
        val start = SystemClock.elapsedRealtime()
        controller.recordingStarted(start)
        advance(499); assertTrue(cues.isEmpty())
        advance(1); assertEquals(Triple(1, 1, start + 500), cues.single())
        advance(19_000)
        assertEquals((1..10).toList() + (1..10).toList(), cues.map { it.first })
        assertEquals((1..20).toList(), cues.map { it.second })
        assertEquals((0..19).map { start + 500 + it * 1000 }, cues.map { it.third })
        assertEquals(AssistedCaptureState.FINISHING, controller.state)
        advance(1_499)
        assertEquals(20, cues.size)
        assertEquals(0, stops)
        advance(1)
        assertEquals(1, stops)
        assertEquals(AssistedCaptureState.FINALIZING, controller.state)
    }

    @Test fun silentAppCuedRecordingPersistsVisualCueEvidenceAndAutoStops() {
        begin(AssistedCaptureSetup(spokenCounting = false))
        advance(11_000)
        assertEquals(10, cues.size)
        assertEquals(AssistedCaptureState.FINALIZING, controller.state)
        assertEquals(1, stops)
    }

    @Test fun cancellingPreparationRejectsLateCommitAndDoesNotStartCamera() {
        controller.record(request())
        controller.stop()
        commit(); advance(10_000)
        assertEquals(0, starts)
        assertEquals(AssistedCaptureState.CANCELLED, controller.state)
    }

    @Test fun countdownStopAndNewAttemptIgnoreOldScheduledCallbacks() {
        controller.record(request()); commit(); advance(1000)
        controller.stop()
        controller.record(request()); commit()
        advance(2999); assertEquals(0, starts)
        advance(1); assertEquals(1, starts)
        controller.close()
    }

    @Test fun backgroundStopCancelsCuesAndWaitsForFinalizationBeforeSaved() {
        begin(); advance(500)
        controller.stop(); advance(5000)
        assertEquals(1, cues.size)
        assertEquals(AssistedCaptureState.FINALIZING, controller.state)
        controller.saved(); assertEquals(AssistedCaptureState.SAVED, controller.state)
        controller.processing(); assertEquals(AssistedCaptureState.PROCESSING, controller.state)
        controller.complete(); assertEquals(AssistedCaptureState.COMPLETE, controller.state)
    }

    @Test fun failedPlaybackCreatesNoPhantomEventAndStopsCapture() {
        audioWorks = false
        begin(); advance(500)
        assertTrue(cues.isEmpty())
        assertEquals(AssistedCaptureState.FAILED, controller.state)
        assertEquals(1, stops)
    }

    @Test fun cameraFinalizingWithoutOperatorStopImmediatelyCancelsCues() {
        begin(); advance(400)
        controller.recordingFinalizing(); advance(5000)
        assertTrue(cues.isEmpty())
        assertEquals(AssistedCaptureState.FINALIZING, controller.state)
    }

    @Test fun failureBeforePreparedInvalidatesCountdownCallback() {
        controller.record(request())
        controller.fail("database unavailable")
        commit(); advance(5000)
        assertEquals(0, starts)
        assertEquals(AssistedCaptureState.FAILED, controller.state)
    }
}
