package dk.lasse.karatecliprecorder

import android.net.Uri
import dk.lasse.karateanalyzer.capture.retrospective.VideoPoseProcessor
import dk.lasse.karateanalyzer.core.Point3
import dk.lasse.karateanalyzer.core.PoseFrame
import dk.lasse.karateanalyzer.core.PoseLandmarkId
import dk.lasse.karateanalyzer.core.PoseLandmarkSample
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class GuidedJodanSessionControllerTest {

    private class FakeSessionRecordingAdapter : SessionRecordingAdapter {
        var measurementSessionActive = false
        var activeRecordingName: String? = null
        var recordingStartedCount = 0
        var recordingStoppedCount = 0
        val createdFiles = mutableListOf<File>()

        override fun beginMeasurementSession() {
            measurementSessionActive = true
        }

        override fun endMeasurementSession() {
            measurementSessionActive = false
        }

        override fun startRecording(customName: String?) {
            activeRecordingName = customName
            recordingStartedCount++
        }

        override fun stopRecording() {
            recordingStoppedCount++
        }

        override fun createGuidedSessionFile(fileName: String): File {
            val file = File.createTempFile("guided_", "_$fileName")
            createdFiles.add(file)
            return file
        }
    }

    @Test
    fun startsContinuousRecordingAtYoiAndDoesNotStopBetweenStrikes() {
        val adapter = FakeSessionRecordingAdapter()
        val states = mutableListOf<GuidedSessionState>()
        val prompts = mutableListOf<String>()
        val strikes = mutableListOf<GuidedStrikePlan?>()

        val controller = GuidedJodanSessionController(
            recordingAdapter = adapter,
            onStateChanged = states::add,
            onPromptChanged = prompts::add,
            onStrikeChanged = strikes::add,
            onSavedClipCountChanged = {},
            onComplete = {},
            onError = {},
            countdownBeforeTrainingMs = 1_000L,
        )

        controller.start()
        assertTrue(adapter.measurementSessionActive)
        assertEquals(GuidedSessionState.READY, states.last())

        // Fast-forward through countdown to YOI
        ShadowLooper.idleMainLooper(1_000L, TimeUnit.MILLISECONDS)
        assertEquals(GuidedSessionState.YOI, states.last())
        assertEquals(1, adapter.recordingStartedCount)
        assertTrue(adapter.activeRecordingName?.startsWith("master_guided_jodan_") == true)
        assertEquals(0, adapter.recordingStoppedCount)

        // Fast-forward through strikes 1 to 5
        ShadowLooper.idleMainLooper(5 * 2_000L, TimeUnit.MILLISECONDS)
        // Verify camera is still continuously recording without per-strike stops
        assertEquals(1, adapter.recordingStartedCount)
        assertEquals(0, adapter.recordingStoppedCount)
    }

    @Test
    fun finalizesMasterRecordingAndEmitsResult() {
        val adapter = FakeSessionRecordingAdapter()
        val states = mutableListOf<GuidedSessionState>()
        var completedResult: GuidedSessionResult? = null

        val controller = GuidedJodanSessionController(
            recordingAdapter = adapter,
            onStateChanged = states::add,
            onPromptChanged = {},
            onStrikeChanged = {},
            onSavedClipCountChanged = {},
            onComplete = { completedResult = it },
            onError = {},
            countdownBeforeTrainingMs = 500L,
        )

        controller.start()

        // Fast forward through entire session (countdown + yoi + 10 strikes)
        ShadowLooper.idleMainLooper(25_000L, TimeUnit.MILLISECONDS)

        // Verifies camera recording was stopped once at completion
        assertEquals(1, adapter.recordingStartedCount)
        assertEquals(1, adapter.recordingStoppedCount)
        assertEquals(GuidedSessionState.SAVING, states.last())

        // Simulate CameraX writing the master video file
        val masterVideo = File.createTempFile("master_test_", ".mp4")
        controller.handleRecordingSaved(
            RecordingResult(
                fileName = masterVideo.name,
                absolutePath = masterVideo.absolutePath,
                uri = Uri.fromFile(masterVideo),
            ),
        )

        assertNotNull(completedResult)
        assertTrue(completedResult!!.completed)
        assertEquals(10, completedResult!!.expectedClipCount)
        assertEquals(masterVideo.absolutePath, completedResult!!.masterVideoPath)
        assertEquals(GuidedSessionState.COMPLETE, states.last())
        assertFalse(adapter.measurementSessionActive)
    }

    @Test
    fun runsRetrospectiveSegmentationWhenVideoProcessorProvided() {
        val adapter = FakeSessionRecordingAdapter()
        val states = mutableListOf<GuidedSessionState>()
        var completedResult: GuidedSessionResult? = null

        // Synthetic pose processor returning stationary frames
        val fakeProcessor = object : VideoPoseProcessor {
            override fun processVideo(
                videoFile: File,
                onProgress: (Float, Long) -> Unit,
            ): List<PoseFrame> {
                onProgress(1f, 1000L)
                return List(20) { i ->
                    PoseFrame(
                        timestampMs = i * 33L,
                        landmarks = mapOf(
                            PoseLandmarkId.LEFT_SHOULDER to PoseLandmarkSample(Point3(0.5f, 0.5f, 0f)),
                            PoseLandmarkId.LEFT_ELBOW to PoseLandmarkSample(Point3(0.5f, 0.65f, 0f)),
                        ),
                    )
                }
            }
        }

        val controller = GuidedJodanSessionController(
            recordingAdapter = adapter,
            videoPoseProcessor = fakeProcessor,
            onStateChanged = states::add,
            onPromptChanged = {},
            onStrikeChanged = {},
            onSavedClipCountChanged = {},
            onComplete = { completedResult = it },
            onError = {},
            countdownBeforeTrainingMs = 500L,
            postProcessingExecutor = { it.run() }, // Direct executor for synchronous test execution
        )

        controller.start()
        ShadowLooper.idleMainLooper(25_000L, TimeUnit.MILLISECONDS)

        val masterVideo = File.createTempFile("master_test_retro_", ".mp4")
        controller.handleRecordingSaved(
            RecordingResult(
                fileName = masterVideo.name,
                absolutePath = masterVideo.absolutePath,
                uri = Uri.fromFile(masterVideo),
            ),
        )
        ShadowLooper.idleMainLooper()

        assertNotNull(completedResult)
        assertTrue(completedResult!!.completed)
        assertNotNull(completedResult!!.retrospectiveResult)
        assertEquals(masterVideo.absolutePath, completedResult!!.masterVideoPath)
        assertEquals(GuidedSessionState.COMPLETE, states.last())
    }
}
