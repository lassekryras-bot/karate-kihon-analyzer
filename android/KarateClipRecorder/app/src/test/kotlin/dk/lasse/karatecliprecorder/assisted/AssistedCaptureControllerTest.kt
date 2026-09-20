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
    private var packageValid = true
    private val cues = mutableListOf<Triple<Int, Int, Long>>()
    private val playbackStarts = mutableListOf<Triple<Int, Int, Long>>()
    private val shown = mutableListOf<String>()
    private val controller = AssistedCaptureController(
        prepare = { _, ready -> prepared = ready }, stopCamera = { stops++ },
        playCount = { audioWorks }, stopAudio = {}, persistCue = { a, b, t -> cues += Triple(a, b, t) },
        changed = { _, text -> shown += text },
        persistPlaybackStart = { a, b, t -> playbackStarts += Triple(a, b, t) },
        isAudioPackageValid = { packageValid },
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

    @Test fun preRollPlaybackStartsAheadOfCueTimeWhileCueEventsLandOnCadence() {
        val start = begin(AssistedCaptureSetup(repetitions = 2, cadenceMs = 1000))
        // At 368ms, playback for Ichi (offset 131ms from 500ms = 369ms) has not started yet
        advance(368)
        assertTrue(playbackStarts.isEmpty())
        assertTrue(cues.isEmpty())

        // At 369ms, playback for Ichi starts
        advance(1)
        assertEquals(1, playbackStarts.size)
        assertEquals(Triple(1, 1, start + 369), playbackStarts.single())
        assertTrue(cues.isEmpty()) // Cue event has not landed yet!

        // At 500ms, cue 1 lands
        advance(131)
        assertEquals(1, cues.size)
        assertEquals(Triple(1, 1, start + 500), cues.single())

        // At 1441ms, playback for Ni (offset 58ms from 1500ms = 1442ms) has not started yet
        advance(941)
        assertEquals(1, playbackStarts.size)

        // At 1442ms, playback for Ni starts
        advance(1)
        assertEquals(2, playbackStarts.size)
        assertEquals(Triple(2, 2, start + 1442), playbackStarts.last())
        assertEquals(1, cues.size) // Cue 2 has not landed yet!

        // At 1500ms, cue 2 lands
        advance(58)
        assertEquals(2, cues.size)
        assertEquals(Triple(2, 2, start + 1500), cues.last())

        // Exact 1000ms cue spacing
        assertEquals(1000L, cues[1].third - cues[0].third)
        // Pre-roll playback spacing differs (1073ms)
        assertEquals(1073L, playbackStarts[1].third - playbackStarts[0].third)
    }

    @Test fun assetHashMismatchPreventsUseOfStaleMetadataAndFailsSafely() {
        packageValid = false
        begin()
        advance(500)
        assertTrue(cues.isEmpty())
        assertTrue(playbackStarts.isEmpty())
        assertEquals(AssistedCaptureState.FAILED, controller.state)
        assertEquals(1, stops)
    }

    @Test fun cancellationRemovesPendingFuturePlaybackWithoutMutatingPersistedCues() {
        val start = begin(AssistedCaptureSetup(repetitions = 10, cadenceMs = 1000))
        advance(1550) // After rep 1 (369ms/500ms) and rep 2 (1442ms/1500ms)
        assertEquals(2, cues.size)
        assertEquals(2, playbackStarts.size)

        controller.stop()
        advance(20_000)

        // No new cues or playback starts were emitted after cancellation
        assertEquals(2, cues.size)
        assertEquals(2, playbackStarts.size)
        assertEquals(listOf(start + 500, start + 1500), cues.map { it.third })
    }

    @Test fun cadenceTooFastForAudioPackageFailsSafelyBeforeCapture() {
        // JapaneseCountAudioPackage requires at least 596ms cadence to prevent Shichi (7) -> Hachi (8) truncation.
        // Requesting 500ms cadence in SharedCaptureRequest should fail safely in controller and explain why.
        controller.record(SharedCaptureRequests.recordAndAnalyze("Punches", "Punches", 10, 500L, true))
        assertEquals(AssistedCaptureState.FAILED, controller.state)
        assertTrue(shown.last().contains("too fast"))
        assertEquals(0, starts)
    }
}
