package dk.lasse.karatecliprecorder.training

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals

/** Preserve exported evidence through the production migrations. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TrainingSchemaTest {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), KarateTrainingDatabase::class.java)

    @Test fun versionTwoSavedAssistedRecordingIsBackfilledWithoutInventingContext() {
        val name = "queue-migration"
        helper.createDatabase(name, 2).apply {
            execSQL("INSERT INTO TrainingUser(userId,createdAtMs) VALUES ('user',10)")
            execSQL("INSERT INTO RecordingSession(sessionId,userId,startedAtMs,guided,state,activityKey,expectedRepetitions,cadenceUs,spokenCounting) VALUES ('saved','user',10,0,'RECORDED','record_and_analyze_assisted_v1',10,1000000,1)")
            execSQL("INSERT INTO MasterRecording(recordingId,sessionId,filePath,createdAtMs,sourceState) VALUES ('video','saved','recordings/old.mp4',10,'AVAILABLE')")
            execSQL("INSERT INTO RecordingSession(sessionId,userId,startedAtMs,guided,state,activityKey) VALUES ('cancelled','user',20,0,'CANCELLED','record_and_analyze_assisted_v1')")
            execSQL("INSERT INTO MasterRecording(recordingId,sessionId,filePath,createdAtMs,sourceState) VALUES ('failed','cancelled','recordings/failed.mp4',20,'FAILED')")
            close()
        }
        helper.runMigrationsAndValidate(name, 6, true, KarateTrainingDatabase.MIGRATION_2_3,
            KarateTrainingDatabase.MIGRATION_3_4, KarateTrainingDatabase.MIGRATION_4_5, KarateTrainingDatabase.MIGRATION_5_6).use { db ->
            db.query("SELECT sessionId,state,queuedAtMs,manual FROM RecordingProcessing").use {
                assertEquals(1, it.count); org.junit.Assert.assertTrue(it.moveToFirst())
                assertEquals("saved", it.getString(0)); assertEquals("QUEUED", it.getString(1))
                assertEquals(10L, it.getLong(2)); assertEquals(0, it.getInt(3))
            }
            db.query("SELECT phase,planKey,planVersion,landmarkDurationMs,segmentationDurationMs,sourceLandmarkTrackId,segmentationVersion FROM RecordingProcessing").use {
                org.junit.Assert.assertTrue(it.moveToFirst())
                assertEquals("QUEUED", it.getString(0)); assertEquals("straight_punch_segments", it.getString(1))
                assertEquals(1, it.getInt(2)); (3..6).forEach { column -> org.junit.Assert.assertTrue(it.isNull(column)) }
            }
            db.query("SELECT expectedActivity,expectedCategory,interruptionReason,cadenceUs FROM RecordingSession WHERE sessionId='saved'").use {
                org.junit.Assert.assertTrue(it.moveToFirst())
                (0..2).forEach { column -> org.junit.Assert.assertTrue(it.isNull(column)) }
                assertEquals(1_000_000L, it.getLong(3))
            }
        }
    }

    @Test fun exportedVersionOneOpensInProductionRoomWithoutLosingData() {
        val name = "training-migration-test"
        val id = "c22ec25a-6e96-4d0b-9037-82f22f869d5b"
        helper.createDatabase(name, 1).apply {
            execSQL("INSERT INTO TrainingUser(userId, createdAtMs) VALUES (?, ?)", arrayOf(id, 10L))
            execSQL("INSERT INTO RecordingSession(sessionId,userId,startedAtMs,guided,state) VALUES ('session',?,10,0,'RECORDED')", arrayOf(id))
            execSQL("INSERT INTO MasterRecording(recordingId,sessionId,filePath,createdAtMs,sourceState) VALUES ('recording','session','/legacy/master.mp4',10,'AVAILABLE')")
            execSQL("INSERT INTO LandmarkTrack(landmarkTrackId,recordingId,pipelineKey,pipelineVersion,configuration,filePath,createdAtMs,state,sourceState) VALUES ('track','recording','pose','legacy','fixture','/legacy/track.pose',10,'COMPLETED','AVAILABLE')")
            close()
        }
        helper.runMigrationsAndValidate(name, 6, true, KarateTrainingDatabase.MIGRATION_1_2,
            KarateTrainingDatabase.MIGRATION_2_3, KarateTrainingDatabase.MIGRATION_3_4,
            KarateTrainingDatabase.MIGRATION_4_5, KarateTrainingDatabase.MIGRATION_5_6).close()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.databaseBuilder(context, KarateTrainingDatabase::class.java, name).addMigrations(
            KarateTrainingDatabase.MIGRATION_1_2, KarateTrainingDatabase.MIGRATION_2_3,
            KarateTrainingDatabase.MIGRATION_3_4, KarateTrainingDatabase.MIGRATION_4_5,
            KarateTrainingDatabase.MIGRATION_5_6).build()
        try {
            database.openHelper.readableDatabase.query("SELECT userId FROM TrainingUser").use { cursor ->
                cursor.moveToFirst()
                assertEquals(id, cursor.getString(0))
            }
            database.openHelper.readableDatabase.query("SELECT s.userId,s.cadenceUs,s.spokenCounting,s.firstCueDelayUs,t.filePath,t.formatId,t.formatVersion FROM RecordingSession s JOIN MasterRecording r ON r.sessionId=s.sessionId JOIN LandmarkTrack t ON t.recordingId=r.recordingId").use { cursor ->
                org.junit.Assert.assertTrue(cursor.moveToFirst())
                assertEquals(id, cursor.getString(0))
                listOf(1, 2, 3, 5, 6).forEach { org.junit.Assert.assertTrue(cursor.isNull(it)) }
                assertEquals("/legacy/track.pose", cursor.getString(4))
            }
        } finally { database.close() }
    }

    @Test fun versionFourMediaDefaultsToVideoAndGetsNullableSharedRequestFields() {
        val name = "shared-capture-migration"
        helper.createDatabase(name, 4).apply {
            execSQL("INSERT INTO TrainingUser(userId,createdAtMs) VALUES ('user',10)")
            execSQL("INSERT INTO RecordingSession(sessionId,userId,startedAtMs,guided,state) VALUES ('session','user',10,0,'RECORDED')")
            execSQL("INSERT INTO MasterRecording(recordingId,sessionId,filePath,createdAtMs,sourceState) VALUES ('media','session','recordings/legacy.mp4',10,'AVAILABLE')")
            close()
        }
        helper.runMigrationsAndValidate(name, 6, true, KarateTrainingDatabase.MIGRATION_4_5,
            KarateTrainingDatabase.MIGRATION_5_6).use { db ->
            db.query("SELECT captureType,mimeType FROM MasterRecording WHERE recordingId='media'").use {
                org.junit.Assert.assertTrue(it.moveToFirst())
                assertEquals("VIDEO", it.getString(0)); assertEquals("video/mp4", it.getString(1))
            }
            db.query("SELECT callerId,parentId,captureTrigger,cueMode,requestedView,completionPrompt,captureOutcome FROM RecordingSession WHERE sessionId='session'").use {
                org.junit.Assert.assertTrue(it.moveToFirst())
                (0..6).forEach { column -> org.junit.Assert.assertTrue(it.isNull(column)) }
            }
        }
    }
}
