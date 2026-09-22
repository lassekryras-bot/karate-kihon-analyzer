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
        helper.runMigrationsAndValidate(name, 9, true, KarateTrainingDatabase.MIGRATION_2_3,
            KarateTrainingDatabase.MIGRATION_3_4, KarateTrainingDatabase.MIGRATION_4_5, KarateTrainingDatabase.MIGRATION_5_6,
            KarateTrainingDatabase.MIGRATION_6_7, KarateTrainingDatabase.MIGRATION_7_8,
            KarateTrainingDatabase.MIGRATION_8_9).use { db ->
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
            db.query("SELECT expectedActivity,expectedCategory,interruptionReason,cadenceUs,audioCuePackageVersionId FROM RecordingSession WHERE sessionId='saved'").use {
                org.junit.Assert.assertTrue(it.moveToFirst())
                (0..2).forEach { column -> org.junit.Assert.assertTrue(it.isNull(column)) }
                assertEquals(1_000_000L, it.getLong(3))
                org.junit.Assert.assertTrue(it.isNull(4))
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
        helper.runMigrationsAndValidate(name, 10, true, KarateTrainingDatabase.MIGRATION_1_2,
            KarateTrainingDatabase.MIGRATION_2_3, KarateTrainingDatabase.MIGRATION_3_4,
            KarateTrainingDatabase.MIGRATION_4_5, KarateTrainingDatabase.MIGRATION_5_6,
            KarateTrainingDatabase.MIGRATION_6_7, KarateTrainingDatabase.MIGRATION_7_8,
            KarateTrainingDatabase.MIGRATION_8_9, KarateTrainingDatabase.MIGRATION_9_10).close()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.databaseBuilder(context, KarateTrainingDatabase::class.java, name).addMigrations(
            KarateTrainingDatabase.MIGRATION_1_2, KarateTrainingDatabase.MIGRATION_2_3,
            KarateTrainingDatabase.MIGRATION_3_4, KarateTrainingDatabase.MIGRATION_4_5,
            KarateTrainingDatabase.MIGRATION_5_6, KarateTrainingDatabase.MIGRATION_6_7,
            KarateTrainingDatabase.MIGRATION_7_8, KarateTrainingDatabase.MIGRATION_8_9,
            KarateTrainingDatabase.MIGRATION_9_10).build()
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
        helper.runMigrationsAndValidate(name, 9, true, KarateTrainingDatabase.MIGRATION_4_5,
            KarateTrainingDatabase.MIGRATION_5_6, KarateTrainingDatabase.MIGRATION_6_7,
            KarateTrainingDatabase.MIGRATION_7_8, KarateTrainingDatabase.MIGRATION_8_9).use { db ->
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

    @Test fun versionSixMigratesToVersionSevenWithProcessingRunAndMovementColumns() {
        val name = "version-6-to-7-migration"
        helper.createDatabase(name, 6).apply {
            execSQL("INSERT INTO TrainingUser(userId,createdAtMs) VALUES ('user',10)")
            execSQL("INSERT INTO RecordingSession(sessionId,userId,startedAtMs,guided,state) VALUES ('session','user',10,0,'RECORDED')")
            execSQL("INSERT INTO MasterRecording(recordingId,sessionId,filePath,createdAtMs,sourceState,captureType) VALUES ('media','session','recordings/legacy.mp4',10,'AVAILABLE','VIDEO')")
            execSQL("INSERT INTO RecordingProcessing(sessionId,state,phase,planKey,planVersion,segmentationVersion,queuedAtMs,manual) VALUES ('session','READY','READY','straight_punch_segments',1,'1',10,0)")
            execSQL("INSERT INTO SessionMovement(movementId,sessionId,startUs,endUs,playbackStartUs,playbackEndUs,segmentationSource,segmentationVersion,state) VALUES ('mov1','session',100,200,50,250,'seg','1','DETECTED')")
            close()
        }
        helper.runMigrationsAndValidate(name, 9, true, KarateTrainingDatabase.MIGRATION_6_7,
            KarateTrainingDatabase.MIGRATION_7_8, KarateTrainingDatabase.MIGRATION_8_9).use { db ->
            db.query("SELECT runId,sessionId,mode,state,planKey,planVersion,isCurrent FROM ProcessingRun WHERE sessionId='session'").use {
                assertEquals(1, it.count)
                org.junit.Assert.assertTrue(it.moveToFirst())
                val runId = it.getString(0)
                assertEquals("session-initial-run", runId)
                assertEquals("session", it.getString(1))
                assertEquals("INITIAL", it.getString(2))
                assertEquals("COMPLETED", it.getString(3))
                assertEquals("straight_punch_segments", it.getString(4))
                assertEquals(1, it.getInt(5))
                assertEquals(1, it.getInt(6))
            }
            db.query("SELECT runId,analysisFrameUs FROM SessionMovement WHERE movementId='mov1'").use {
                org.junit.Assert.assertTrue(it.moveToFirst())
                val movementRunId = it.getString(0)
                assertEquals("session-initial-run", movementRunId)
                org.junit.Assert.assertTrue(it.isNull(1))
            }
        }
    }

    @Test fun versionSevenMigratesToVersionEightWithAudioCuePackageVersionId() {
        val name = "version-7-to-8-migration"
        helper.createDatabase(name, 7).apply {
            execSQL("INSERT INTO TrainingUser(userId,createdAtMs) VALUES ('user',10)")
            execSQL("INSERT INTO RecordingSession(sessionId,userId,startedAtMs,guided,state) VALUES ('session','user',10,0,'RECORDED')")
            close()
        }
        helper.runMigrationsAndValidate(name, 8, true, KarateTrainingDatabase.MIGRATION_7_8).use { db ->
            db.query("SELECT sessionId,audioCuePackageVersionId FROM RecordingSession WHERE sessionId='session'").use {
                assertEquals(1, it.count)
                org.junit.Assert.assertTrue(it.moveToFirst())
                assertEquals("session", it.getString(0))
                org.junit.Assert.assertTrue(it.isNull(1))
            }
        }
    }

    @Test fun versionEightMigratesToVersionNineWithGeometryJson() {
        val name = "version-8-to-9-migration"
        helper.createDatabase(name, 8).apply {
            execSQL("INSERT INTO TrainingUser(userId,createdAtMs) VALUES ('user',10)")
            execSQL("INSERT INTO RecordingSession(sessionId,userId,startedAtMs,guided,state) VALUES ('session','user',10,0,'RECORDED')")
            execSQL("INSERT INTO MasterRecording(recordingId,sessionId,filePath,createdAtMs,sourceState,captureType) VALUES ('media','session','recordings/legacy.mp4',10,'AVAILABLE','VIDEO')")
            execSQL("INSERT INTO LandmarkTrack(landmarkTrackId,recordingId,pipelineKey,pipelineVersion,configuration,filePath,createdAtMs,state,sourceState) VALUES ('track','media','pose','1','conf','file.pose',10,'COMPLETED','AVAILABLE')")
            execSQL("INSERT INTO SessionMovement(movementId,sessionId,startUs,endUs,playbackStartUs,playbackEndUs,segmentationSource,segmentationVersion,state) VALUES ('mov1','session',100,200,50,250,'seg','1','DETECTED')")
            execSQL("INSERT INTO MovementAnalysis(analysisId,movementId,analyzerKey,analyzerVersion,landmarkTrackId,state,createdAtMs,reason) VALUES ('a1','mov1','straight_punch_target','1','track','COMPLETED',10,'ok')")
            close()
        }
        helper.runMigrationsAndValidate(name, 9, true, KarateTrainingDatabase.MIGRATION_8_9).use { db ->
            db.query("SELECT analysisId,analyzerKey,geometryJson FROM MovementAnalysis WHERE analysisId='a1'").use {
                assertEquals(1, it.count)
                org.junit.Assert.assertTrue(it.moveToFirst())
                assertEquals("a1", it.getString(0))
                assertEquals("straight_punch_target", it.getString(1))
                org.junit.Assert.assertTrue(it.isNull(2))
            }
        }
    }

    @Test fun versionNineMigratesToVersionTenWithCanonicalGeometryAndRotation() {
        val name = "version-9-to-10-migration"
        helper.createDatabase(name, 9).apply {
            execSQL("INSERT INTO TrainingUser(userId,createdAtMs) VALUES ('user',10)")
            execSQL("INSERT INTO RecordingSession(sessionId,userId,startedAtMs,guided,state) VALUES ('session','user',10,0,'RECORDED')")
            execSQL("INSERT INTO MasterRecording(recordingId,sessionId,filePath,createdAtMs,sourceState,captureType) VALUES ('media','session','recordings/legacy.mp4',10,'AVAILABLE','VIDEO')")
            execSQL("INSERT INTO LandmarkTrack(landmarkTrackId,recordingId,pipelineKey,pipelineVersion,configuration,filePath,createdAtMs,state,sourceState) VALUES ('track','media','pose','1','conf','file.pose',10,'COMPLETED','AVAILABLE')")
            close()
        }
        helper.runMigrationsAndValidate(name, 10, true, KarateTrainingDatabase.MIGRATION_9_10).use { db ->
            db.query("SELECT recordingId,rotation,canonicalGeometryJson FROM MasterRecording WHERE recordingId='media'").use {
                assertEquals(1, it.count)
                org.junit.Assert.assertTrue(it.moveToFirst())
                assertEquals("media", it.getString(0))
                org.junit.Assert.assertTrue(it.isNull(1))
                org.junit.Assert.assertTrue(it.isNull(2))
            }
            db.query("SELECT landmarkTrackId,canonicalGeometryJson FROM LandmarkTrack WHERE landmarkTrackId='track'").use {
                assertEquals(1, it.count)
                org.junit.Assert.assertTrue(it.moveToFirst())
                assertEquals("track", it.getString(0))
                org.junit.Assert.assertTrue(it.isNull(1))
            }
        }
    }
}
