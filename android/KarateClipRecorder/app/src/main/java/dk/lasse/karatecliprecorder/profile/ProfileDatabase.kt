package dk.lasse.karatecliprecorder.profile

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal class ProfileDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_PROFILES)
        db.execSQL(CREATE_LEARNING_PROGRESS)
        db.execSQL(CREATE_TRAINING_SESSIONS)
        db.execSQL(CREATE_CALIBRATIONS)
        db.execSQL("CREATE INDEX training_profile_idx ON training_sessions(profile_id, started_at DESC)")
        db.execSQL("CREATE INDEX calibration_profile_idx ON calibrations(profile_id, updated_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun profiles(): List<Profile> = readableDatabase.query(
        "profiles", null, null, null, null, null, "created_at ASC",
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.profile()) } }

    fun profile(id: String): Profile? = readableDatabase.query(
        "profiles", null, "id = ?", arrayOf(id), null, null, null, "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.profile() else null }

    fun insertProfile(profile: Profile) {
        writableDatabase.insertOrThrow("profiles", null, profile.values())
    }

    fun updateProfile(profile: Profile) {
        check(writableDatabase.update("profiles", profile.values(includeId = false), "id = ?", arrayOf(profile.id)) == 1) {
            "Profile does not exist: ${profile.id}"
        }
    }

    fun deleteProfile(id: String): Boolean = writableDatabase.delete("profiles", "id = ?", arrayOf(id)) == 1

    fun upsertLearningProgress(progress: LearningProgress) {
        writableDatabase.insertWithOnConflict(
            "learning_progress", null, progress.values(), SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun learningProgress(profileId: String): List<LearningProgress> = readableDatabase.query(
        "learning_progress", null, "profile_id = ?", arrayOf(profileId), null, null, "last_updated_at DESC",
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.learningProgress()) } }

    fun insertTrainingSession(session: TrainingSession) {
        writableDatabase.insertOrThrow("training_sessions", null, session.values())
    }

    fun trainingSessions(profileId: String): List<TrainingSession> = readableDatabase.query(
        "training_sessions", null, "profile_id = ?", arrayOf(profileId), null, null, "started_at DESC",
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.trainingSession()) } }

    fun clearTrainingSessions() {
        writableDatabase.delete("training_sessions", null, null)
    }

    fun upsertCalibration(calibration: Calibration) {
        writableDatabase.insertWithOnConflict(
            "calibrations", null, calibration.values(), SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun calibrations(profileId: String): List<Calibration> = readableDatabase.query(
        "calibrations", null, "profile_id = ?", arrayOf(profileId), null, null, "updated_at DESC",
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.calibration()) } }

    private fun Profile.values(includeId: Boolean = true) = ContentValues().apply {
        if (includeId) put("id", id)
        put("name", name.trim())
        put("gender", gender.name)
        put("age_group", ageGroup.name)
        put("avatar_base_id", avatarBaseId)
        put("skin_tone_position", skinTonePosition)
        put("hair_color_position", hairColorPosition)
        put("belt_rank", beltRank.name)
        heightCm?.let { put("height_cm", it) } ?: putNull("height_cm")
        dominantSide?.let { put("dominant_side", it.name) } ?: putNull("dominant_side")
        experienceLevel?.let { put("experience_level", it.name) } ?: putNull("experience_level")
        put("created_at", createdAt)
        put("updated_at", updatedAt)
    }

    private fun LearningProgress.values() = ContentValues().apply {
        put("profile_id", profileId)
        put("learning_path_id", learningPathId)
        put("activity_id", activityId)
        put("status", status.name)
        put("last_updated_at", lastUpdatedAt)
        completedAt?.let { put("completed_at", it) } ?: putNull("completed_at")
    }

    private fun TrainingSession.values() = ContentValues().apply {
        put("id", id)
        put("profile_id", profileId)
        put("mode", mode.name)
        put("skill_or_activity_id", skillOrActivityId)
        put("started_at", startedAt)
        completedAt?.let { put("completed_at", it) } ?: putNull("completed_at")
        resultPayload?.let { put("result_payload", it) } ?: putNull("result_payload")
    }

    private fun Calibration.values() = ContentValues().apply {
        put("id", id)
        put("profile_id", profileId)
        put("calibration_type", calibrationType)
        put("payload", payload)
        put("updated_at", updatedAt)
    }

    private fun Cursor.profile() = Profile(
        id = string("id"),
        name = string("name"),
        gender = Gender.valueOf(string("gender")),
        ageGroup = AgeGroup.valueOf(string("age_group")),
        avatarBaseId = string("avatar_base_id"),
        skinTonePosition = float("skin_tone_position"),
        hairColorPosition = float("hair_color_position"),
        beltRank = BeltRank.valueOf(string("belt_rank")),
        heightCm = nullableFloat("height_cm"),
        dominantSide = nullableString("dominant_side")?.let(DominantSide::valueOf),
        experienceLevel = nullableString("experience_level")?.let(ExperienceLevel::valueOf),
        createdAt = long("created_at"),
        updatedAt = long("updated_at"),
    )

    private fun Cursor.learningProgress() = LearningProgress(
        profileId = string("profile_id"),
        learningPathId = string("learning_path_id"),
        activityId = string("activity_id"),
        status = LearningStatus.valueOf(string("status")),
        lastUpdatedAt = long("last_updated_at"),
        completedAt = nullableLong("completed_at"),
    )

    private fun Cursor.trainingSession() = TrainingSession(
        id = string("id"),
        profileId = string("profile_id"),
        mode = TrainingMode.valueOf(string("mode")),
        skillOrActivityId = string("skill_or_activity_id"),
        startedAt = long("started_at"),
        completedAt = nullableLong("completed_at"),
        resultPayload = nullableString("result_payload"),
    )

    private fun Cursor.calibration() = Calibration(
        id = string("id"),
        profileId = string("profile_id"),
        calibrationType = string("calibration_type"),
        payload = string("payload"),
        updatedAt = long("updated_at"),
    )

    private fun Cursor.index(name: String) = getColumnIndexOrThrow(name)
    private fun Cursor.string(name: String) = getString(index(name))
    private fun Cursor.float(name: String) = getFloat(index(name))
    private fun Cursor.long(name: String) = getLong(index(name))
    private fun Cursor.nullableString(name: String) = index(name).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.nullableFloat(name: String) = index(name).let { if (isNull(it)) null else getFloat(it) }
    private fun Cursor.nullableLong(name: String) = index(name).let { if (isNull(it)) null else getLong(it) }

    companion object {
        private const val DATABASE_NAME = "trainee_profiles.db"
        private const val DATABASE_VERSION = 1

        private const val CREATE_PROFILES = """
            CREATE TABLE profiles (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                gender TEXT NOT NULL,
                age_group TEXT NOT NULL,
                avatar_base_id TEXT NOT NULL,
                skin_tone_position REAL NOT NULL,
                hair_color_position REAL NOT NULL,
                belt_rank TEXT NOT NULL,
                height_cm REAL,
                dominant_side TEXT,
                experience_level TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """

        private const val CREATE_LEARNING_PROGRESS = """
            CREATE TABLE learning_progress (
                profile_id TEXT NOT NULL,
                learning_path_id TEXT NOT NULL,
                activity_id TEXT NOT NULL,
                status TEXT NOT NULL,
                last_updated_at INTEGER NOT NULL,
                completed_at INTEGER,
                PRIMARY KEY (profile_id, learning_path_id, activity_id),
                FOREIGN KEY (profile_id) REFERENCES profiles(id) ON DELETE CASCADE
            )
        """

        private const val CREATE_TRAINING_SESSIONS = """
            CREATE TABLE training_sessions (
                id TEXT NOT NULL PRIMARY KEY,
                profile_id TEXT NOT NULL,
                mode TEXT NOT NULL,
                skill_or_activity_id TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                completed_at INTEGER,
                result_payload TEXT,
                FOREIGN KEY (profile_id) REFERENCES profiles(id) ON DELETE CASCADE
            )
        """

        private const val CREATE_CALIBRATIONS = """
            CREATE TABLE calibrations (
                id TEXT NOT NULL PRIMARY KEY,
                profile_id TEXT NOT NULL,
                calibration_type TEXT NOT NULL,
                payload TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                FOREIGN KEY (profile_id) REFERENCES profiles(id) ON DELETE CASCADE
            )
        """
    }
}
