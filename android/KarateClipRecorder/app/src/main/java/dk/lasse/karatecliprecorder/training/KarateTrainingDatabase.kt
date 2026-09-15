package dk.lasse.karatecliprecorder.training

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [
    TrainingUserRow::class,
    RecordingProcessingRow::class,
    RecordingSessionRow::class,
    MasterRecordingRow::class,
    LandmarkTrackRow::class,
    SessionMovementRow::class,
    ObservationContextRow::class,
    SessionEventRow::class,
    MovementSessionEventRow::class,
    LabelRow::class,
    MovementLabelRow::class,
    MovementAnalysisRow::class,
    MeasurementResultRow::class,
    UserBodyMeasurementRow::class,
    UserCalibrationRow::class,
    AnalysisBodyMeasurementRow::class,
    AnalysisCalibrationRow::class,
    SessionBodyMeasurementRow::class
], version = 5, exportSchema = true)
abstract class KarateTrainingDatabase : RoomDatabase() {
    internal abstract fun trainingDao(): TrainingDao

    companion object {
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE RecordingSession ADD COLUMN callerId TEXT")
                db.execSQL("ALTER TABLE RecordingSession ADD COLUMN parentId TEXT")
                db.execSQL("ALTER TABLE RecordingSession ADD COLUMN captureTrigger TEXT")
                db.execSQL("ALTER TABLE RecordingSession ADD COLUMN cueMode TEXT")
                db.execSQL("ALTER TABLE RecordingSession ADD COLUMN requestedView TEXT")
                db.execSQL("ALTER TABLE RecordingSession ADD COLUMN completionPrompt TEXT")
                db.execSQL("ALTER TABLE RecordingSession ADD COLUMN captureOutcome TEXT")
                db.execSQL("ALTER TABLE MasterRecording ADD COLUMN captureType TEXT NOT NULL DEFAULT 'VIDEO'")
                db.execSQL("ALTER TABLE MasterRecording ADD COLUMN mimeType TEXT DEFAULT 'video/mp4'")
            }
        }
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS RecordingProcessing (sessionId TEXT NOT NULL, queuedAtMs INTEGER NOT NULL, state TEXT NOT NULL, promotedAtMs INTEGER, manual INTEGER NOT NULL, error TEXT, PRIMARY KEY(sessionId), FOREIGN KEY(sessionId) REFERENCES RecordingSession(sessionId) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_RecordingProcessing_state_queuedAtMs ON RecordingProcessing(state, queuedAtMs)")
                db.execSQL("INSERT INTO RecordingProcessing(sessionId,queuedAtMs,state,manual) SELECT s.sessionId,s.startedAtMs,'QUEUED',0 FROM RecordingSession s JOIN MasterRecording r ON r.sessionId=s.sessionId WHERE s.activityKey='record_and_analyze_assisted_v1' AND r.sourceState='AVAILABLE'")
            }
        }
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE RecordingSession ADD COLUMN expectedActivity TEXT")
                db.execSQL("ALTER TABLE RecordingSession ADD COLUMN expectedCategory TEXT")
                db.execSQL("ALTER TABLE RecordingSession ADD COLUMN interruptionReason TEXT")
            }
        }
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE RecordingSession ADD COLUMN cadenceUs INTEGER")
                db.execSQL("ALTER TABLE RecordingSession ADD COLUMN spokenCounting INTEGER")
                db.execSQL("ALTER TABLE RecordingSession ADD COLUMN firstCueDelayUs INTEGER")
                db.execSQL("ALTER TABLE LandmarkTrack ADD COLUMN formatId TEXT")
                db.execSQL("ALTER TABLE LandmarkTrack ADD COLUMN formatVersion INTEGER")
            }
        }
        @Volatile private var instance: KarateTrainingDatabase? = null
        internal fun closeForTests() = synchronized(this) { instance?.close(); instance = null }
        fun get(context: Context): KarateTrainingDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, KarateTrainingDatabase::class.java,
                "karate-training.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build().also { instance = it }
        }
    }
}
