package dk.lasse.karatecliprecorder.training

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dk.lasse.karateanalyzer.capture.retrospective.*
import dk.lasse.karateanalyzer.core.PoseFrame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SegmenterIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun persistedLandmarksSegmentOnceAndActualCountSurvivesReopen() {
        val name = "segments-${trainingId()}"
        val directory = kotlin.io.path.createTempDirectory("segments").toFile()
        fun open() = Room.databaseBuilder(context, KarateTrainingDatabase::class.java, name)
            .allowMainThreadQueries().build()
        var database = open()
        try {
            var repository = TrainingRepository(database, TrainingStorage(directory))
            val session = seed(repository, directory, planned = 10)
            repository.enqueue(session.sessionId)
            repository.addEvent(SessionEvent(sessionId = session.sessionId, type = "spoken_count",
                timestampUs = 900_000, data = "count=1;ordinal=1"))
            repository.addEvent(SessionEvent(sessionId = session.sessionId, type = "STOP_REQUESTED",
                timestampUs = 950_000, data = "voice_stop"))
            var decodes = 0
            var segmentations = 0
            var suppliedCues = emptyList<CueEvent>()
            var clock = 100L
            val movement = dk.lasse.karateanalyzer.capture.retrospective.SessionMovement(
                movementNumber = 1, sourceRecordingPath = repository.recording(session.sessionId)!!.filePath,
                logicalStartTimestampMs = 1_000, logicalEndTimestampMs = 1_500,
                retainedStartTimestampMs = 800, retainedEndTimestampMs = 1_800)
            fun processor(repo: TrainingRepository) = TrainingSessionProcessor(repo, File(directory, "landmarks"),
                object : VideoPoseProcessor {
                    override fun processVideo(videoFile: File, onProgress: (Float, Long) -> Unit): List<PoseFrame> {
                        decodes++; return listOf(PoseFrame(0, emptyMap()), PoseFrame(2_000, emptyMap()))
                    }
                }, "fixture", elapsedRealtimeMs = { clock.also { clock += 5 } },
                segment = { recordingId, path, frames, cues ->
                    segmentations++; suppliedCues = cues.cues
                    RetrospectiveSessionResult(recordingId, path, frames.size, 2_000, 1, listOf(movement), emptyList(), stats())
                })

            assertEquals(1, processor(repository).process(session.sessionId))
            val stored = repository.movements(session.sessionId).single()
            assertEquals(1_000_000, stored.startUs)
            assertEquals(1_500_000, stored.endUs)
            assertEquals(800_000, stored.playbackStartUs)
            assertEquals(1_800_000, stored.playbackEndUs)
            assertEquals(10, repository.session(session.sessionId)!!.expectedRepetitions)
            assertEquals(listOf("spoken_count"), suppliedCues.map { repository.events(session.sessionId)
                .single { event -> event.sessionEventId == it.id }.type })
            assertEquals(SessionState.COMPLETED, repository.session(session.sessionId)!!.state)
            assertEquals(ProcessingPhase.READY, repository.job(session.sessionId)!!.phase)
            assertEquals(5L, repository.job(session.sessionId)!!.landmarkDurationMs)
            assertEquals(5L, repository.job(session.sessionId)!!.segmentationDurationMs)
            val identity = stored.movementId

            database.close(); database = open(); repository = TrainingRepository(database, TrainingStorage(directory))
            assertEquals(identity, repository.movements(session.sessionId).single().movementId)
            assertEquals(1, processor(repository).process(session.sessionId))
            assertEquals(1, repository.movementCount(session.sessionId))
            assertEquals(1, decodes)
            assertEquals(1, segmentations)
        } finally {
            database.close(); context.deleteDatabase(name); directory.deleteRecursively()
        }
    }

    @Test fun zeroMovementsIsReadyAndSegmentationFailurePreservesUpstreamEvidence() {
        val database = Room.inMemoryDatabaseBuilder(context, KarateTrainingDatabase::class.java)
            .allowMainThreadQueries().build()
        val directory = kotlin.io.path.createTempDirectory("segment-failure").toFile()
        try {
            val repository = TrainingRepository(database, TrainingStorage(directory))
            val zero = seed(repository, directory, planned = 10)
            repository.enqueue(zero.sessionId)
            val decoder = object : VideoPoseProcessor {
                override fun processVideo(videoFile: File, onProgress: (Float, Long) -> Unit) =
                    listOf(PoseFrame(0, emptyMap()), PoseFrame(2_000, emptyMap()))
            }
            val empty = TrainingSessionProcessor(repository, File(directory, "landmarks"), decoder, "fixture",
                segment = { id, path, frames, _ -> RetrospectiveSessionResult(id, path, frames.size, 2_000, 0,
                    emptyList(), emptyList(), stats()) })
            assertEquals(0, empty.process(zero.sessionId))
            assertEquals(SessionState.COMPLETED, repository.session(zero.sessionId)!!.state)
            assertEquals(ProcessingPhase.READY, repository.job(zero.sessionId)!!.phase)

            val failed = seed(repository, directory, planned = 10)
            repository.enqueue(failed.sessionId)
            val processor = TrainingSessionProcessor(repository, File(directory, "landmarks"), decoder, "fixture",
                segment = { _, _, _, _ -> error("segmentation fixture failed") })
            assertFailsWith<IllegalStateException> { processor.process(failed.sessionId) }
            assertEquals(0, repository.movementCount(failed.sessionId))
            assertEquals(SourceState.AVAILABLE, repository.recording(failed.sessionId)!!.sourceState)
            assertEquals(ProcessingState.COMPLETED, repository.tracks(repository.recording(failed.sessionId)!!.recordingId).single().state)
            assertEquals(ProcessingPhase.FAILED, repository.job(failed.sessionId)!!.phase)
            assertContains(repository.job(failed.sessionId)!!.error.orEmpty(), "segmentation fixture failed")
        } finally {
            database.close(); directory.deleteRecursively()
        }
    }

    private fun seed(repository: TrainingRepository, directory: File, planned: Int): RecordingSession {
        val user = TrainingUser(); repository.createUser(user)
        val session = RecordingSession(userId = user.userId, startedAtMs = System.currentTimeMillis(),
            activityKey = AssistedCaptureSetup.ACTIVITY_KEY, expectedActivity = "Alternating straight punches",
            expectedCategory = "Punches", expectedRepetitions = planned)
        val id = trainingId()
        val path = "recordings/$id.mp4"
        File(directory, path).apply { parentFile!!.mkdirs(); writeText("video fixture") }
        repository.beginSession(session, MasterRecording(id, session.sessionId, path, session.startedAtMs))
        repository.finishRecording(session.sessionId, 2_000_000, width = 1280, height = 720, frameRate = 30.0)
        return session
    }

    private fun stats() = RetrospectiveSignalStats(0.0, 0.0, 0.0, 0.0, 1.0)
}
