package dk.lasse.karatecliprecorder.training

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals

/** Preserve the exported v1 graph through all production migrations. */
@RunWith(AndroidJUnit4::class)
class TrainingMigrationTest {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), KarateTrainingDatabase::class.java)

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
        helper.runMigrationsAndValidate(name, 5, true, KarateTrainingDatabase.MIGRATION_1_2,
            KarateTrainingDatabase.MIGRATION_2_3, KarateTrainingDatabase.MIGRATION_3_4,
            KarateTrainingDatabase.MIGRATION_4_5).close()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.databaseBuilder(context, KarateTrainingDatabase::class.java, name).addMigrations(
            KarateTrainingDatabase.MIGRATION_1_2, KarateTrainingDatabase.MIGRATION_2_3,
            KarateTrainingDatabase.MIGRATION_3_4, KarateTrainingDatabase.MIGRATION_4_5).build()
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
}
