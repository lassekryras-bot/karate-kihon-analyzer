package dk.lasse.karatecliprecorder.training

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import dk.lasse.karateanalyzer.core.PoseFrame
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RecordingQueueTest {
    @Test fun heavySlotSerializesWorkersAndCaptureWaitsForRelease() {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(3)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        try {
            val first = pool.submit {
                ProcessingCoordinator.heavy.lock()
                try { entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
                finally { ProcessingCoordinator.heavy.unlock() }
            }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val second = pool.submit<Boolean> { val acquired = ProcessingCoordinator.heavy.tryLock(); if (acquired) ProcessingCoordinator.heavy.unlock(); acquired }
            assertFalse(second.get(5, java.util.concurrent.TimeUnit.SECONDS))
            ProcessingCoordinator.reserveCapture().use {
                val capture = pool.submit { ProcessingCoordinator.awaitCapturePriority() }
                assertFalse(capture.isDone)
                release.countDown()
                first.get(5, java.util.concurrent.TimeUnit.SECONDS)
                capture.get(5, java.util.concurrent.TimeUnit.SECONDS)
            }
        } finally { release.countDown(); pool.shutdownNow() }
    }
    @Test fun batteryIsAStartBoundaryChargingOverridesAndStorageHasTwoLevels() {
        val low = ResourceSnapshot(9, false, ProcessingPolicy.WARN_STORAGE_BYTES)
        assertFalse(ProcessingPolicy.mayStart(low, 20))
        assertTrue(ProcessingPolicy.mayStart(low.copy(charging = true), 20))
        assertTrue(ProcessingPolicy.mayStart(low.copy(batteryPercent = 20), 20))
        assertNotNull(ProcessingPolicy.recordingBlock(low, 20))
        assertNull(ProcessingPolicy.recordingBlock(low.copy(charging = true), 20))
        assertNotNull(ProcessingPolicy.recordingBlock(low.copy(charging = true, freeBytes = 1), 20))
        // No policy check is imposed on an active job; only the next start is gated.
        assertTrue(ProcessingPolicy.mayProcess(false, true, true))
        assertFalse(ProcessingPolicy.mayProcess(false, false, true))
        assertFalse(ProcessingPolicy.mayProcess(false, true, false))
        assertTrue(ProcessingPolicy.mayProcess(true, false, false))
    }
    @Test fun fifoPromotionRecoveryAndCaptureABCDoNotDependOnMls() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, KarateTrainingDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repo = TrainingRepository(db)
            val user = TrainingUser(); repo.createUser(user)
            val ids = (1..3).map { n ->
                val session = RecordingSession(userId = user.userId, startedAtMs = n.toLong(), activityKey = AssistedCaptureSetup.ACTIVITY_KEY)
                repo.beginSession(session, MasterRecording(sessionId = session.sessionId, filePath = "test-$n.mp4", createdAtMs = n.toLong()))
                repo.finishRecording(session.sessionId, 1000); session.sessionId
            }
            assertTrue(repo.jobs().isEmpty())
            repo.recoverJobs()
            assertEquals(ids, repo.jobs().map { it.sessionId })
            assertTrue(ids.all { repo.movementCount(it) == 0 })
            repo.promote(ids[2]); assertEquals(ids[2], repo.jobs().first().sessionId)
            val active = repo.jobs().first(); repo.updateJob(active.copy(state = QueueState.PROCESSING))
            repo.recoverJobs(); assertEquals(QueueState.QUEUED, repo.job(ids[2])!!.state)
            repo.updateJob(repo.job(ids[2])!!.copy(state = QueueState.READY)); repo.recoverJobs()
            assertEquals(QueueState.READY, repo.job(ids[2])!!.state)
            assertEquals(3, repo.jobs().size)
        } finally { db.close() }
    }
    @Test fun capturePreemptsHeavyWorkAndReleasePermitsRestart() {
        val id = trainingId()
        ProcessingCoordinator.check(id)
        val lease = ProcessingCoordinator.reserveCapture()
        assertFailsWith<ProcessingYield> { ProcessingCoordinator.check(id) }
        lease.close(); lease.close()
        ProcessingCoordinator.check(id)
        assertFalse(ProcessingCoordinator.capturing)
    }
    @Test fun deletionBetweenWriteAndPublishCannotResurrectMls() {
        val directory = kotlin.io.path.createTempDirectory("delete-race").toFile()
        try {
            val id = trainingId(); val file = File(directory, "$id.mls")
            var fenceCalls = 0
            assertFailsWith<ProcessingYield> {
                LandmarkFiles.write(file, listOf(PoseFrame(0, emptyMap())), { ProcessingCoordinator.check(id) }) { operation ->
                    synchronized(ProcessingCoordinator.publication) {
                        fenceCalls++
                        if (fenceCalls == 2) { ProcessingCoordinator.markDeleted(id); File(file.path + ".tmp").delete() }
                        operation()
                    }
                }
            }
            assertFalse(file.exists()); assertFalse(File(file.path + ".tmp").exists())
        } finally { directory.deleteRecursively() }
    }
    @Test fun confirmedDeleteRemovesOwnedGraphAndAllMediaButNotUser() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, KarateTrainingDatabase::class.java).allowMainThreadQueries().build()
        val directory = kotlin.io.path.createTempDirectory("queue-delete").toFile()
        try {
            val repo = TrainingRepository(db, TrainingStorage(directory)); val user = TrainingUser(); repo.createUser(user)
            val session = RecordingSession(userId = user.userId, startedAtMs = 1, activityKey = AssistedCaptureSetup.ACTIVITY_KEY)
            val file = File(directory, "video.mp4").apply { writeText("test") }
            val master = MasterRecording(sessionId = session.sessionId, filePath = "video.mp4", createdAtMs = 1)
            repo.beginSession(session, master); repo.finishRecording(session.sessionId, 1000)
            repo.addTrack(LandmarkTrack(recordingId = master.recordingId, pipelineKey = "pose", pipelineVersion = "1", configuration = "test", filePath = "track.mls"))
            File(directory, "track.mls.tmp").writeText("partial")
            repo.deleteRecordingAndEvidence(session.sessionId)
            assertNull(repo.session(session.sessionId)); assertNull(repo.job(session.sessionId)); assertFalse(file.exists())
            assertFalse(File(directory, "track.mls.tmp").exists())
            assertNotNull(db.trainingDao().user(user.userId))
        } finally { db.close(); directory.deleteRecursively() }
    }
}
