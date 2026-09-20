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
    ProcessingRunRow::class,
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
], version = 9, exportSchema = true)
abstract class KarateTrainingDatabase : RoomDatabase() {
    internal abstract fun trainingDao(): TrainingDao

    companion object {
        val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE MovementAnalysis ADD COLUMN geometryJson TEXT")
            }
        }
        val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE RecordingSession ADD COLUMN audioCuePackageVersionId TEXT")
            }
        }
        val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS ProcessingRun (
                        runId TEXT NOT NULL,
                        sessionId TEXT NOT NULL,
                        createdAtMs INTEGER NOT NULL,
                        completedAtMs INTEGER,
                        mode TEXT NOT NULL,
                        sourceLandmarkTrackId TEXT,
                        planKey TEXT NOT NULL,
                        planVersion INTEGER NOT NULL,
                        segmenterVersion TEXT,
                        analyzerKey TEXT,
                        analyzerVersion TEXT,
                        state TEXT NOT NULL,
                        isCurrent INTEGER NOT NULL,
                        error TEXT,
                        landmarkDurationMs INTEGER,
                        segmentationDurationMs INTEGER,
                        analysisDurationMs INTEGER,
                        PRIMARY KEY(runId),
                        FOREIGN KEY(sessionId) REFERENCES RecordingSession(sessionId) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(sourceLandmarkTrackId) REFERENCES LandmarkTrack(landmarkTrackId) ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ProcessingRun_sessionId_createdAtMs ON ProcessingRun(sessionId, createdAtMs)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ProcessingRun_sessionId_isCurrent ON ProcessingRun(sessionId, isCurrent)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ProcessingRun_sourceLandmarkTrackId ON ProcessingRun(sourceLandmarkTrackId)")

                db.execSQL("ALTER TABLE SessionMovement ADD COLUMN runId TEXT")
                db.execSQL("ALTER TABLE SessionMovement ADD COLUMN analysisFrameUs INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_SessionMovement_runId ON SessionMovement(runId)")

                db.execSQL("""
                    INSERT INTO ProcessingRun (
                        runId, sessionId, createdAtMs, completedAtMs, mode,
                        sourceLandmarkTrackId, planKey, planVersion, segmenterVersion,
                        analyzerKey, analyzerVersion, state, isCurrent, error,
                        landmarkDurationMs, segmentationDurationMs, analysisDurationMs
                    )
                    SELECT
                        s.sessionId || '-initial-run',
                        s.sessionId,
                        s.startedAtMs,
                        s.endedAtMs,
                        'INITIAL',
                        p.sourceLandmarkTrackId,
                        COALESCE(p.planKey, 'straight_punch_target_analysis'),
                        COALESCE(p.planVersion, 1),
                        p.segmentationVersion,
                        NULL,
                        NULL,
                        CASE WHEN p.state = 'READY' OR s.state = 'COMPLETED' THEN 'COMPLETED'
                             WHEN p.state = 'FAILED' THEN 'FAILED'
                             ELSE 'COMPLETED' END,
                        1,
                        p.error,
                        p.landmarkDurationMs,
                        p.segmentationDurationMs,
                        NULL
                    FROM RecordingSession s
                    LEFT JOIN RecordingProcessing p ON p.sessionId = s.sessionId
                    WHERE EXISTS (SELECT 1 FROM SessionMovement WHERE sessionId = s.sessionId)
                       OR p.state = 'READY'
                """.trimIndent())

                db.execSQL("""
                    UPDATE SessionMovement
                    SET runId = (
                        SELECT r.runId FROM ProcessingRun r
                        WHERE r.sessionId = SessionMovement.sessionId
                        LIMIT 1
                    )
                    WHERE runId IS NULL
                """.trimIndent())
            }
        }
        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE RecordingProcessing ADD COLUMN phase TEXT NOT NULL DEFAULT 'QUEUED'")
                db.execSQL("ALTER TABLE RecordingProcessing ADD COLUMN planKey TEXT NOT NULL DEFAULT 'straight_punch_segments'")
                db.execSQL("ALTER TABLE RecordingProcessing ADD COLUMN planVersion INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE RecordingProcessing ADD COLUMN landmarkDurationMs INTEGER")
                db.execSQL("ALTER TABLE RecordingProcessing ADD COLUMN segmentationDurationMs INTEGER")
                db.execSQL("ALTER TABLE RecordingProcessing ADD COLUMN sourceLandmarkTrackId TEXT")
                db.execSQL("ALTER TABLE RecordingProcessing ADD COLUMN segmentationVersion TEXT")
                db.execSQL("UPDATE RecordingProcessing SET phase = CASE state WHEN 'READY' THEN 'READY' WHEN 'FAILED' THEN 'FAILED' WHEN 'PROCESSING' THEN 'LANDMARKS' ELSE 'QUEUED' END")
            }
        }
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
                "karate-training.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9).build().also { instance = it }
        }
    }
}
