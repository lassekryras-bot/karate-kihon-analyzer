package dk.lasse.karatecliprecorder.training

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dk.lasse.karateanalyzer.capture.retrospective.VideoPoseProcessor
import dk.lasse.karateanalyzer.core.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AssistedCaptureStorageTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val directory = kotlin.io.path.createTempDirectory("assisted-test").toFile()
    private val storage = TrainingStorage(directory)
    // Robolectric scopes this to the test; keep the Windows WAL path below MAX_PATH.
    private val name = "ac"
    private lateinit var db: KarateTrainingDatabase
    private lateinit var repo: TrainingRepository
    private val user = TrainingUser()
    private val session = RecordingSession(userId = user.userId, startedAtMs = 1,
        activityKey = AssistedCaptureSetup.ACTIVITY_KEY, expectedRepetitions = 20,
        cadenceUs = 900_000, spokenCounting = true, firstCueDelayUs = 500_000)
    private val id = trainingId()
    private val master = MasterRecording(id, session.sessionId, storage.recording(id), 1)
    private var decodes = 0
    private var failDecode = false
    private val frames = listOf(PoseFrame(0, emptyMap()), PoseFrame(33, mapOf(PoseLandmarkId.RIGHT_WRIST to
        PoseLandmarkSample(Point3(.5f, .6f, -.1f), Point3(.2f, -.3f, .4f), .9f, .8f, LandmarkSource.OBSERVED))))
    private fun open() {
        db = Room.databaseBuilder(context, KarateTrainingDatabase::class.java, name).allowMainThreadQueries().build()
        repo = TrainingRepository(db, storage)
    }
    @Before fun setup() {
        open(); repo.createUser(user); repo.beginSession(session, master)
        storage.resolve(master.filePath).apply { parentFile!!.mkdirs(); writeText("fake decoder source") }
        repo.finishRecording(session.sessionId, 1_000_000, width = 1920, height = 1080, frameRate = 30.0)
    }
    @After fun cleanup() { db.close(); context.deleteDatabase(name); directory.deleteRecursively() }
    private fun processor() = TrainingSessionProcessor(repo, File(directory, "landmarks"), object : VideoPoseProcessor {
        override fun processVideo(videoFile: File, onProgress: (Float, Long) -> Unit): List<PoseFrame> {
            assertEquals(storage.resolve(master.filePath), videoFile)
            assertEquals(SourceState.AVAILABLE, repo.recording(session.sessionId)!!.sourceState)
            decodes++
            if (failDecode) error("Native extraction failed")
            return frames
        }
    }, "fixture-model-sha256")

    @Test fun completedEvidenceReopensWithoutSecondDecodeAndDoesNotInventMovements() {
        repo.addEvent(SessionEvent(sessionId = session.sessionId, type = "spoken_count", timestampUs = 500_000,
            data = "count=1;ordinal=1", timingSource = "playback_request"))
        val track = processor().ensureLandmarks(session.sessionId)
        assertEquals("landmarks/${track.landmarkTrackId}.mls", track.filePath)
        assertEquals("recordings/$id.mp4", repo.recording(session.sessionId)!!.filePath)
        assertEquals(LandmarkFiles.FORMAT_ID, track.formatId)
        assertEquals(1, track.formatVersion)
        assertEquals(frames, LandmarkFiles.read(repo.file(track.filePath), track.sha256))
        assertFalse(File(repo.file(track.filePath).path + ".tmp").exists())
        db.close(); open()
        assertEquals(track, processor().ensureLandmarks(session.sessionId))
        assertEquals(1, decodes)
        assertEquals(0, repo.movementCount(session.sessionId))
        assertEquals(20, repo.session(session.sessionId)!!.expectedRepetitions)
        assertEquals(900_000L, repo.session(session.sessionId)!!.cadenceUs)
        assertEquals(500_000L, repo.events(session.sessionId).single().timestampUs)
        assertEquals(SessionState.LANDMARKS_READY, repo.session(session.sessionId)!!.state)
        assertEquals(1920, repo.recording(session.sessionId)!!.width)
    }

    @Test fun extractionFailureKeepsMp4AndRetryCreatesNewTrack() {
        failDecode = true
        assertFails { processor().ensureLandmarks(session.sessionId) }
        assertEquals(SourceState.AVAILABLE, repo.recording(session.sessionId)!!.sourceState)
        assertTrue(repo.file(master.filePath).isFile)
        val failed = repo.tracks(id).single()
        assertEquals(ProcessingState.FAILED, failed.state)
        failDecode = false
        val successful = processor().ensureLandmarks(session.sessionId)
        assertNotEquals(failed.landmarkTrackId, successful.landmarkTrackId)
        assertEquals(2, repo.tracks(id).size)
        repo.deleteVideo(session.sessionId)
        assertEquals(successful, processor().ensureLandmarks(session.sessionId))
        assertEquals(2, decodes)
        assertEquals(frames, LandmarkFiles.read(repo.file(successful.filePath), successful.sha256))
    }

    @Test fun publishedTrackAfterProcessDeathIsValidatedAndRecovered() {
        val track = processor().ensureLandmarks(session.sessionId)
        repo.updateTrack(track.copy(state = ProcessingState.PROCESSING, sourceState = SourceState.PENDING, sha256 = null))
        repo.setSessionState(session.sessionId, SessionState.LANDMARKS_PROCESSING)
        db.close(); open(); repo.recoverInterruptedWork()
        val recovered = processor().ensureLandmarks(session.sessionId)
        assertEquals(track.landmarkTrackId, recovered.landmarkTrackId)
        assertEquals(track.sha256, recovered.sha256)
        assertEquals(1, decodes)
    }

    @Test fun staleTemporaryFileNeverBecomesCompletedEvidence() {
        val trackId = trainingId()
        val relative = storage.landmarks(trackId)
        repo.addTrack(LandmarkTrack(trackId, id, "mediapipe_pose_video", "fixture-model-sha256", "fixture", relative,
            state = ProcessingState.PROCESSING, formatId = LandmarkFiles.FORMAT_ID, formatVersion = 1))
        val temporary = File(storage.resolve(relative).path + ".tmp").apply { parentFile!!.mkdirs(); writeText("partial header") }
        val ready = processor().ensureLandmarks(session.sessionId)
        assertNotEquals(trackId, ready.landmarkTrackId)
        assertFalse(storage.resolve(relative).exists())
        assertTrue(temporary.exists())
        assertEquals(ProcessingState.FAILED, repo.tracks(id).first { it.landmarkTrackId == trackId }.state)
    }

    @Test fun corruptCompletedTrackIsSurfacedAndMp4RemainsUsable() {
        val track = processor().ensureLandmarks(session.sessionId)
        repo.file(track.filePath).appendText("corruption")
        assertFails { processor().ensureLandmarks(session.sessionId) }
        assertEquals(1, decodes)
        assertEquals(SourceState.AVAILABLE, repo.recording(session.sessionId)!!.sourceState)
        assertEquals(ProcessingState.FAILED, repo.tracks(id).single().state)
        assertEquals(SessionState.PARTIAL, repo.session(session.sessionId)!!.state)
    }

    @Test fun storageRejectsEscapesAndPreservesExplicitLegacyReferences() {
        assertFails { storage.resolve("../escape.mp4") }
        assertFails { storage.recording("date-and-technique") }
        assertEquals(directory.absoluteFile, storage.resolve(directory.absolutePath))
        assertEquals(master.filePath, storage.reference(storage.resolve(master.filePath)))
    }

    @Test fun preferencesRememberDefaultsAndSupportFutureUserScopeWithoutSchemaChanges() {
        context.getSharedPreferences("training_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        val preferences = TrainingPreferences(context)
        assertEquals(AssistedCaptureSetup(), preferences.read(user.userId))
        assertFails { AssistedCaptureSetup(cadenceMs = 600) }
        val changed = AssistedCaptureSetup(25, 1200, false)
        preferences.save(user.userId, changed)
        assertEquals(changed, TrainingPreferences(context).read("other-user"))
        val scoped = TrainingPreferences(context, sharedDevelopmentDefaults = false)
        scoped.save(user.userId, changed)
        assertEquals(changed, scoped.read(user.userId))
        assertEquals(AssistedCaptureSetup(), scoped.read("other-user"))
    }

    @Test fun freshEntryResetsOnlyActivityAndStoredContextSurvivesReopen() {
        val changed = AssistedCaptureSetup(14, 1400, false, "Front kicks", "Kicks")
        val preferences = TrainingPreferences(context)
        preferences.save(user.userId, changed)
        val fresh = preferences.read(user.userId)
        assertEquals(changed.copy(expectedActivity = "Alternating straight punches", expectedCategory = "Punches"), fresh)
        // Current setup remains an immutable snapshot for Record another.
        assertEquals("Front kicks", changed.expectedActivity)
        val contextual = session.copy(sessionId = trainingId(), expectedActivity = changed.expectedActivity, expectedCategory = changed.expectedCategory)
        repo.beginSession(contextual, MasterRecording(sessionId = contextual.sessionId, filePath = "context.mp4", createdAtMs = 1))
        db.close(); open()
        assertEquals("Front kicks", repo.session(contextual.sessionId)!!.expectedActivity)
        assertEquals("Kicks", repo.session(contextual.sessionId)!!.expectedCategory)
    }

    @Test fun extractionYieldsAtFrameForCaptureThenRestartsWithNewTrackAfterReopen() {
        val interrupted = TrainingSessionProcessor(repo, File(directory, "landmarks"), object : VideoPoseProcessor {
            override fun processVideo(videoFile: File, onProgress: (Float, Long) -> Unit): List<PoseFrame> {
                ProcessingCoordinator.reserveCapture().use { onProgress(.5f, 33L) }
                error("Capture must preempt before another frame")
            }
        }, "fixture-model-sha256", { ProcessingCoordinator.check(session.sessionId) })
        assertFailsWith<ProcessingYield> { interrupted.ensureLandmarks(session.sessionId) }
        val stale = repo.tracks(id).single()
        assertEquals(ProcessingState.PROCESSING, stale.state)
        assertEquals(SourceState.AVAILABLE, repo.recording(session.sessionId)!!.sourceState)
        assertFalse(repo.file(stale.filePath).exists())
        db.close(); open(); repo.recoverJobs()
        val ready = processor().ensureLandmarks(session.sessionId)
        assertNotEquals(stale.landmarkTrackId, ready.landmarkTrackId)
        assertEquals(ProcessingState.COMPLETED, ready.state)
        assertEquals(0, repo.movementCount(session.sessionId))
    }
}
