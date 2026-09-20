package dk.lasse.karatecliprecorder.movement

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dk.lasse.karateanalyzer.core.PoseFrame
import java.time.Duration
import androidx.test.core.app.ApplicationProvider
import dk.lasse.karatecliprecorder.AppPreferences
import dk.lasse.karatecliprecorder.profile.ProfileRepository
import dk.lasse.karatecliprecorder.training.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MovementDetailActivityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun close() {
        TrainingServices.closeForTests()
    }

    private fun drainUntil(done: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!done() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue(done(), "Timed out waiting for UI/storage callback")
    }

    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    @Test
    fun movementDetailRendersHierarchyAndRespectsDeveloperMode() {
        runCatching { androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build()) }
        ProcessingPreferences(context).background = false
        AppPreferences(context).developerMode = false

        val training = TrainingServices.get(context)
        val profileRepository = ProfileRepository(context, AppPreferences(context))
        val user = profileRepository.activeProfile().id
        profileRepository.close()

        val session = RecordingSession(
            userId = user,
            startedAtMs = 1000L,
            activityKey = AssistedCaptureSetup.ACTIVITY_KEY,
            expectedActivity = "Alternating straight punches",
            expectedCategory = "Punches",
            expectedRepetitions = 1,
        )

        val movement = SessionMovement(
            sessionId = session.sessionId,
            startUs = 500_000L,
            endUs = 1_500_000L,
            playbackStartUs = 200_000L,
            playbackEndUs = 1_800_000L,
            segmentationSource = "test_segmenter",
            segmentationVersion = "1",
            analysisFrameUs = 1_000_000L,
        )

        var seeded = false
        training.submit({ repo ->
            repo.createUser(TrainingUser(user))
            val recId = trainingId()
            val reference = training.storage.recording(recId)
            training.storage.resolve(reference).apply { parentFile!!.mkdirs(); writeText("dummy video") }
            repo.beginSession(session, MasterRecording(recId, session.sessionId, reference, 1000L))
            repo.finishRecording(session.sessionId, 2_000_000L)
            repo.recoverJobs()

            val observations = listOf(ObservationContext(movement.movementId, nearerSide = BodySide.LEFT))
            repo.saveSegmentation(session.sessionId, listOf(movement), observations)

            val rec = repo.recording(session.sessionId)!!
            val track = LandmarkTrack(
                recordingId = rec.recordingId,
                pipelineKey = "test_pipeline",
                pipelineVersion = "1",
                configuration = "default",
                filePath = "test.mls",
                state = ProcessingState.COMPLETED,
                sourceState = SourceState.AVAILABLE,
            )
            repo.addTrack(track)

            val analysis = MovementAnalysis(
                movementId = movement.movementId,
                analyzerKey = StraightPunchMovementAdapter.policy.analyzerKey,
                analyzerVersion = "1",
                landmarkTrackId = track.landmarkTrackId,
                state = AnalysisState.PARTIAL,
                reason = "fixture_partial_analysis",
            )
            val measurements = listOf(
                MeasurementResult(
                    analysisId = analysis.analysisId,
                    measurementKey = TrainingMeasurements.PUNCH_CLOSEST_TARGET,
                    calculationVersion = "1",
                    valueType = ValueType.CATEGORICAL,
                    categoricalValue = "CHUDAN",
                    side = BodySide.LEFT,
                    state = ResultState.VALID,
                    occurrenceUs = 1_000_000L,
                    frameIndex = 30L,
                ),
                MeasurementResult(
                    analysisId = analysis.analysisId,
                    measurementKey = TrainingMeasurements.PUNCH_TARGET_ANGLE_ERROR_DEG,
                    calculationVersion = "1",
                    valueType = ValueType.NUMERIC,
                    numericValue = 3.2,
                    side = BodySide.LEFT,
                    state = ResultState.VALID,
                    occurrenceUs = 1_000_000L,
                    frameIndex = 30L,
                )
            )
            repo.saveAnalysis(analysis, measurements)
        }) { it.getOrThrow(); seeded = true }
        drainUntil { seeded }

        val intent = Intent(context, MovementDetailActivity::class.java).apply {
            putExtra(MovementDetailActivity.EXTRA_SESSION_ID, session.sessionId)
            putExtra(MovementDetailActivity.EXTRA_MOVEMENT_ID, movement.movementId)
            putExtra(MovementDetailActivity.EXTRA_DISPLAYED_NUMBER, 1)
        }

        val activity = Robolectric.buildActivity(MovementDetailActivity::class.java, intent).setup()
        try {
            val root = activity.get().findViewById<View>(android.R.id.content)
            fun texts() = views(root).filterIsInstance<TextView>().map { it.text.toString() }

            drainUntil { texts().any { "Key Results" in it } }

            // Context subtitle
            assertTrue(texts().any { "Straight punch · Left arm" in it })

            // Player tabs
            assertTrue("Video" in texts())
            assertTrue("Analysis" in texts())
            assertTrue("Graph" in texts())

            // Key Results
            assertTrue("Key Results" in texts())
            assertTrue("Target height" in texts())
            assertTrue(texts().any { "Chūdan" in it })

            // Needs Attention is absent when no findings
            assertFalse("Needs Attention" in texts())

            // Analysis Debug must be ABSENT when developerMode is false
            assertFalse("Analysis Debug" in texts())
        } finally {
            activity.pause().stop().destroy()
        }

        // Now test with developerMode = true
        AppPreferences(context).developerMode = true
        val debugActivity = Robolectric.buildActivity(MovementDetailActivity::class.java, intent).setup()
        try {
            val root = debugActivity.get().findViewById<View>(android.R.id.content)
            fun texts() = views(root).filterIsInstance<TextView>().map { it.text.toString() }

            drainUntil { texts().any { "Key Results" in it } }

            // Analysis Debug must now be PRESENT
            assertTrue("Analysis Debug" in texts())
            assertTrue(texts().any { "Movement ID" in it })
            assertTrue(texts().any { "Canonical frame" in it })
            assertTrue(texts().any { "Provenance:" in it })
            assertTrue(texts().any { "Analysis state: PARTIAL" in it })
            assertTrue(texts().any { "Analysis reason: fixture_partial_analysis" in it })
        } finally {
            debugActivity.pause().stop().destroy()
            AppPreferences(context).developerMode = false
        }
    }

    @Test
    fun modeSwitchingUpdatesPlayerViewVisibilityAndPreservesTimestamp() {
        val training = TrainingServices.get(context)
        val profileRepository = ProfileRepository(context, AppPreferences(context))
        val user = profileRepository.activeProfile().id
        profileRepository.close()

        val session = RecordingSession(
            userId = user,
            startedAtMs = 1000L,
            activityKey = AssistedCaptureSetup.ACTIVITY_KEY,
            expectedActivity = "Straight punch",
            expectedCategory = "Punches",
            expectedRepetitions = 1,
        )
        val movement = SessionMovement(
            sessionId = session.sessionId,
            startUs = 500_000L,
            endUs = 1_500_000L,
            playbackStartUs = 200_000L,
            playbackEndUs = 1_800_000L,
            segmentationSource = "test_segmenter",
            segmentationVersion = "1",
            analysisFrameUs = 1_000_000L,
        )
        var seeded = false
        training.submit({ repo ->
            repo.createUser(TrainingUser(user))
            val recId = trainingId()
            val reference = training.storage.recording(recId)
            training.storage.resolve(reference).apply { parentFile!!.mkdirs(); writeText("dummy video") }
            repo.beginSession(session, MasterRecording(recId, session.sessionId, reference, 1000L))
            repo.finishRecording(session.sessionId, 2_000_000L)
            repo.saveSegmentation(session.sessionId, listOf(movement), listOf(ObservationContext(movement.movementId, nearerSide = BodySide.LEFT)))
        }) { it.getOrThrow(); seeded = true }
        drainUntil { seeded }

        val intent = Intent(context, MovementDetailActivity::class.java).apply {
            putExtra(MovementDetailActivity.EXTRA_SESSION_ID, session.sessionId)
            putExtra(MovementDetailActivity.EXTRA_MOVEMENT_ID, movement.movementId)
            putExtra(MovementDetailActivity.EXTRA_DISPLAYED_NUMBER, 1)
        }
        val activity = Robolectric.buildActivity(MovementDetailActivity::class.java, intent).setup()
        try {
            drainUntil { activity.get().playerView != null }
            val player = activity.get().playerView!!
            val state = activity.get().timelineState!!

            state.seekUs(750_000L)
            assertEquals(750_000L, state.currentTimestampUs)

            // Switch to Video mode
            player.videoTab.performClick()
            assertEquals(PlayerMode.VIDEO, state.currentMode)
            assertEquals(750_000L, state.currentTimestampUs)
            assertEquals(View.VISIBLE, player.videoView.visibility)
            assertEquals(View.GONE, player.overlayView.visibility)
            assertEquals(View.GONE, player.graphView.visibility)

            // Switch to Analysis mode
            player.analysisTab.performClick()
            assertEquals(PlayerMode.ANALYSIS, state.currentMode)
            assertEquals(750_000L, state.currentTimestampUs)
            assertEquals(View.VISIBLE, player.videoView.visibility)
            assertEquals(View.VISIBLE, player.overlayView.visibility)
            assertEquals(View.GONE, player.graphView.visibility)

            // Switch to Graph mode
            player.graphTab.performClick()
            assertEquals(PlayerMode.GRAPH, state.currentMode)
            assertEquals(750_000L, state.currentTimestampUs)
            assertEquals(View.GONE, player.videoView.visibility)
            assertEquals(View.GONE, player.overlayView.visibility)
            assertEquals(View.VISIBLE, player.graphView.visibility)
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test
    fun strictTrackProvenanceDoesNotSubstituteArbitraryCompletedTrack() {
        val training = TrainingServices.get(context)
        val profileRepository = ProfileRepository(context, AppPreferences(context))
        val user = profileRepository.activeProfile().id
        profileRepository.close()

        val session = RecordingSession(
            userId = user,
            startedAtMs = 1000L,
            activityKey = AssistedCaptureSetup.ACTIVITY_KEY,
            expectedActivity = "Straight punch",
            expectedCategory = "Punches",
            expectedRepetitions = 1,
        )
        val movement = SessionMovement(
            sessionId = session.sessionId,
            startUs = 500_000L,
            endUs = 1_500_000L,
            playbackStartUs = 200_000L,
            playbackEndUs = 1_800_000L,
            segmentationSource = "test_segmenter",
            segmentationVersion = "1",
            analysisFrameUs = 1_000_000L,
        )
        var seeded = false
        training.submit({ repo ->
            repo.createUser(TrainingUser(user))
            val recId = trainingId()
            val reference = training.storage.recording(recId)
            training.storage.resolve(reference).apply { parentFile!!.mkdirs(); writeText("dummy video") }
            repo.beginSession(session, MasterRecording(recId, session.sessionId, reference, 1000L))
            repo.finishRecording(session.sessionId, 2_000_000L)
            repo.saveSegmentation(session.sessionId, listOf(movement), listOf(ObservationContext(movement.movementId, nearerSide = BodySide.LEFT)))

            // A genuinely readable alternative: the old silent substitution would load frames.
            val otherPath = training.storage.landmarks(trainingId())
            val otherFrames = listOf(PoseFrame(1000L, emptyMap()))
            val otherHash = LandmarkFiles.write(repo.file(otherPath), otherFrames)
            assertEquals(otherFrames, LandmarkFiles.read(repo.file(otherPath), otherHash, LandmarkFiles.FORMAT_ID))
            val rec = repo.recording(session.sessionId)!!
            val trackOther = LandmarkTrack(
                landmarkTrackId = "track_other",
                recordingId = rec.recordingId,
                pipelineKey = "test_pipeline",
                pipelineVersion = "1",
                configuration = "default",
                filePath = otherPath,
                sha256 = otherHash,
                formatId = LandmarkFiles.FORMAT_ID,
                formatVersion = LandmarkFiles.VERSION,
                state = ProcessingState.COMPLETED,
                sourceState = SourceState.AVAILABLE,
            )
            repo.addTrack(trackOther)

            // Seed a referenced track whose source file is MISSING
            val trackReferenced = LandmarkTrack(
                landmarkTrackId = "track_referenced",
                recordingId = rec.recordingId,
                pipelineKey = "test_pipeline",
                pipelineVersion = "1",
                configuration = "default",
                filePath = "missing.mls",
                state = ProcessingState.COMPLETED,
                sourceState = SourceState.MISSING,
            )
            repo.addTrack(trackReferenced)

            // Seed analysis referencing "track_referenced"
            val analysis = MovementAnalysis(
                movementId = movement.movementId,
                analyzerKey = StraightPunchMovementAdapter.policy.analyzerKey,
                analyzerVersion = "1",
                landmarkTrackId = "track_referenced",
                state = AnalysisState.COMPLETED,
            )
            val measurement = MeasurementResult(
                analysisId = analysis.analysisId,
                measurementKey = TrainingMeasurements.PUNCH_CLOSEST_TARGET,
                calculationVersion = "1",
                valueType = ValueType.CATEGORICAL,
                categoricalValue = "CHUDAN",
                side = BodySide.LEFT,
                state = ResultState.VALID,
                occurrenceUs = 1_000_000L,
            )
            repo.saveAnalysis(analysis, listOf(measurement))
        }) { it.getOrThrow(); seeded = true }
        drainUntil { seeded }

        val intent = Intent(context, MovementDetailActivity::class.java).apply {
            putExtra(MovementDetailActivity.EXTRA_SESSION_ID, session.sessionId)
            putExtra(MovementDetailActivity.EXTRA_MOVEMENT_ID, movement.movementId)
            putExtra(MovementDetailActivity.EXTRA_DISPLAYED_NUMBER, 1)
        }
        val activity = Robolectric.buildActivity(MovementDetailActivity::class.java, intent).setup()
        try {
            drainUntil { activity.get().presentationData != null }
            // Must NOT have loaded track_other into loadedFrames
            assertTrue(activity.get().loadedFrames.isEmpty(), "Must not silently substitute track_other for unavailable track_referenced")
            // Debug data accurately records the referenced track_referenced
            assertEquals("track_referenced", activity.get().presentationData?.debugData?.landmarkTrackId)
            val player = activity.get().playerView!!
            player.analysisTab.performClick()
            assertEquals(View.VISIBLE, player.unavailableLabel.visibility)
            assertTrue(player.unavailableLabel.text.contains("unavailable"))
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test
    fun dialogDismissalCleansUpDialogAndReactivatesCompactPlayer() {
        val training = TrainingServices.get(context)
        val profileRepository = ProfileRepository(context, AppPreferences(context))
        val user = profileRepository.activeProfile().id
        profileRepository.close()

        val session = RecordingSession(
            userId = user,
            startedAtMs = 1000L,
            activityKey = AssistedCaptureSetup.ACTIVITY_KEY,
            expectedActivity = "Straight punch",
            expectedCategory = "Punches",
            expectedRepetitions = 1,
        )
        val movement = SessionMovement(
            sessionId = session.sessionId,
            startUs = 500_000L,
            endUs = 1_500_000L,
            playbackStartUs = 200_000L,
            playbackEndUs = 1_800_000L,
            segmentationSource = "test_segmenter",
            segmentationVersion = "1",
            analysisFrameUs = 1_000_000L,
        )
        var seeded = false
        training.submit({ repo ->
            repo.createUser(TrainingUser(user))
            val recId = trainingId()
            val reference = training.storage.recording(recId)
            training.storage.resolve(reference).apply { parentFile!!.mkdirs(); writeText("dummy video") }
            repo.beginSession(session, MasterRecording(recId, session.sessionId, reference, 1000L))
            repo.finishRecording(session.sessionId, 2_000_000L)
            repo.saveSegmentation(session.sessionId, listOf(movement), listOf(ObservationContext(movement.movementId, nearerSide = BodySide.LEFT)))
        }) { it.getOrThrow(); seeded = true }
        drainUntil { seeded }

        val intent = Intent(context, MovementDetailActivity::class.java).apply {
            putExtra(MovementDetailActivity.EXTRA_SESSION_ID, session.sessionId)
            putExtra(MovementDetailActivity.EXTRA_MOVEMENT_ID, movement.movementId)
            putExtra(MovementDetailActivity.EXTRA_DISPLAYED_NUMBER, 1)
        }
        val activity = Robolectric.buildActivity(MovementDetailActivity::class.java, intent).setup()
        try {
            drainUntil { activity.get().playerView != null }
            val player = activity.get().playerView!!
            val state = activity.get().timelineState!!

            // Request expand
            player.onExpandRequested()
            shadowOf(Looper.getMainLooper()).idle()

            assertTrue(activity.get().isExpandedOpen)
            assertFalse(player.isPlaybackActive, "Compact player should be deactivated when dialog is open")

            val dialog = activity.get().expandedDialog
            assertNotNull(dialog)

            // Start playback while dialog is active
            state.setPlaying(true)
            assertTrue(state.isPlaying)

            // Dismiss dialog
            dialog.dismiss()
            shadowOf(Looper.getMainLooper()).idle()

            // Verify dialog cleaned up: state stopped playing
            assertFalse(state.isPlaying, "Playback should be stopped upon dialog dismissal")
            assertFalse(activity.get().isExpandedOpen, "isExpandedOpen should be false after dismissal")
            assertTrue(player.isPlaybackActive, "Compact player should be reactivated upon dialog dismissal")
            state.setMode(PlayerMode.GRAPH)
            state.seekUs(500_000L)
            state.setPlaying(true)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(330))
            // Rendering callbacks can shift the final 33 ms tick; two owners would
            // advance roughly twice this interval.
            assertTrue(state.currentTimestampUs in 797_000L..863_000L,
                "Only the compact timer may advance playback: ${state.currentTimestampUs}")
            player.onExpandRequested()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(player.isPlaybackActive)
            val reopened = activity.get().expandedDialog!!
            state.seekUs(500_000L)
            state.setPlaying(true)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(330))
            assertTrue(state.currentTimestampUs in 797_000L..863_000L,
                "Only the reopened dialog may advance playback: ${state.currentTimestampUs}")

            state.setPlaying(false)
            state.setPlaybackRate(0.25)
            state.setSelectedPlotKey("retained-selection")
            state.seekUs(750_000L)
            activity.pause().stop()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(state.isPlaying)
            assertFalse(player.isPlaybackActive)
            assertNull(activity.get().expandedDialog)
            assertTrue(activity.get().isExpandedOpen)
            activity.start().resume().visible()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(activity.get().expandedDialog!!.isShowing)
            assertFalse(player.isPlaybackActive)
            assertFalse(state.isPlaying)

            activity.recreate()
            drainUntil { activity.get().expandedDialog?.isShowing == true }
            val restored = activity.get().timelineState!!
            assertEquals(750_000L, restored.currentTimestampUs)
            assertEquals(PlayerMode.GRAPH, restored.currentMode)
            assertEquals("retained-selection", restored.selectedPlotKey)
            assertEquals(0.25, restored.playbackRate)
            assertFalse(restored.isPlaying)
            assertFalse(activity.get().playerView!!.isPlaybackActive)
            activity.get().expandedDialog!!.dismiss()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(activity.get().timelineState!!.isPlaying)
            assertTrue(activity.get().playerView!!.isPlaybackActive)
        } finally {
            activity.pause().stop().destroy()
        }
    }
}

