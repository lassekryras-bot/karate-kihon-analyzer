package dk.lasse.karatecliprecorder.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import dk.lasse.karatecliprecorder.AppPreferences
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
class ProfileRepositoryTest {
    private lateinit var context: Context
    private var repository: ProfileRepository? = null

    @BeforeTest
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("trainee_profiles.db")
        context.getSharedPreferences("karate_kihon_analyzer_preferences", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @AfterTest
    fun tearDown() {
        repository?.close()
    }

    @Test
    fun firstRunSeedsAndPersistsOneActiveDefaultProfile() {
        val preferences = AppPreferences(context)
        repository = ProfileRepository(context, preferences)

        val profile = repository!!.activeProfile()

        assertEquals("Trainee", profile.name)
        assertEquals(profile.id, preferences.activeProfileId)
        assertEquals(listOf(profile), repository!!.listProfiles())
    }

    @Test fun resetLearningOnlyRemovesTheSelectedProfilesProgressAndPersists() {
        repository = ProfileRepository(context, AppPreferences(context))
        val repo = repository!!
        val active = repo.activeProfile()
        val other = repo.createProfile(testProfile("Other"))
        listOf(active, other).forEach { profile ->
            repo.saveLearningProgress(LearningProgress(profile.id, "basics", "one", LearningStatus.COMPLETED))
            repo.saveLearningProgress(LearningProgress(profile.id, "basics", "two", LearningStatus.IN_PROGRESS))
        }
        repo.saveTrainingSession(TrainingSession(profileId = other.id, mode = TrainingMode.LEARN, skillOrActivityId = "one"))
        repo.saveCalibration(Calibration(profileId = other.id, calibrationType = "camera", payload = "{}"))
        repo.resetLearningProgress(other.id)
        repo.close()
        repository = ProfileRepository(context, AppPreferences(context))
        assertTrue(repository!!.learningProgress(other.id).isEmpty())
        assertEquals(2, repository!!.learningProgress(active.id).size)
        assertEquals(1, repository!!.trainingSessions(other.id).size)
        assertEquals(1, repository!!.calibrations(other.id).size)
        assertEquals(active.id, repository!!.activeProfile().id)
        assertEquals(other, repository!!.listProfiles().first { it.id == other.id })
    }

    @Test fun clearTrainingHistoryIsIsolatedAndPersists() {
        repository = ProfileRepository(context, AppPreferences(context))
        val repo = repository!!
        val active = repo.activeProfile()
        val other = repo.createProfile(testProfile("Other"))
        listOf(active, other).forEach { profile ->
            repo.saveTrainingSession(TrainingSession(profileId = profile.id, mode = TrainingMode.LEARN, skillOrActivityId = "one"))
            repo.saveLearningProgress(LearningProgress(profile.id, "basics", "one", LearningStatus.COMPLETED))
            repo.saveCalibration(Calibration(profileId = profile.id, calibrationType = "camera", payload = "{}"))
        }
        val pictures = context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)!!
        val owned = java.io.File(pictures, "punch_height_level_1/profiles/${other.id}/capture.jpg")
        val untouched = java.io.File(pictures, "punch_height_level_1/profiles/${active.id}/capture.jpg")
        val legacy = java.io.File(pictures, "punch_height_level_1/legacy.jpg")
        val calibration = java.io.File(pictures, "camera_setup/profiles/${other.id}/capture.jpg")
        listOf(owned, untouched, legacy, calibration).forEach { it.parentFile!!.mkdirs(); it.writeText("fixture") }
        val store = dk.lasse.karatecliprecorder.TrainingHistoryStore(context)
        kotlin.test.assertFailsWith<IllegalArgumentException> { store.clear("../camera_setup") }
        assertTrue(store.clear(other.id).succeeded)
        assertTrue(!owned.exists())
        listOf(untouched, legacy, calibration).forEach { assertTrue(it.exists()) }
        repo.clearTrainingSessions(other.id)
        repo.close()
        repository = ProfileRepository(context, AppPreferences(context))
        assertTrue(repository!!.trainingSessions(other.id).isEmpty())
        assertEquals(1, repository!!.trainingSessions(active.id).size)
        assertEquals(1, repository!!.learningProgress(other.id).size)
        assertEquals(1, repository!!.calibrations(other.id).size)
        assertEquals(active.id, repository!!.activeProfile().id)
    }

    @Test fun bodyMeasurementsPersistAndCanBeCleared() {
        repository = ProfileRepository(context, AppPreferences(context))
        val profile = repository!!.activeProfile()
        repository!!.updateProfile(profile.copy(heightCm = 180f, forearmLengthCm = 27.5f, lowerLegLengthCm = 43f))
        repository!!.close()
        repository = ProfileRepository(context, AppPreferences(context))
        val saved = repository!!.activeProfile()
        assertEquals(180f, saved.heightCm)
        assertEquals(27.5f, saved.forearmLengthCm)
        assertEquals(43f, saved.lowerLegLengthCm)
        repository!!.updateProfile(saved.copy(heightCm = null, forearmLengthCm = null, lowerLegLengthCm = null))
        assertEquals(null, repository!!.activeProfile().forearmLengthCm)
        assertEquals(null, repository!!.activeProfile().lowerLegLengthCm)
    }

    @Test fun versionOneDatabaseAddsOptionalMeasurementsWithoutLosingProfiles() {
        val db = context.openOrCreateDatabase("trainee_profiles.db", Context.MODE_PRIVATE, null)
        db.execSQL("""CREATE TABLE profiles (id TEXT PRIMARY KEY, name TEXT, gender TEXT,
            age_group TEXT, avatar_base_id TEXT, skin_tone_position REAL, hair_color_position REAL,
            belt_rank TEXT, height_cm REAL, dominant_side TEXT, experience_level TEXT,
            created_at INTEGER, updated_at INTEGER)""")
        db.execSQL("""INSERT INTO profiles VALUES ('kept', 'Existing', 'MALE', 'ADULT',
            'avatar_01', 0.5, 0.5, 'WHITE', 175, NULL, NULL, 1, 1)""")
        db.version = 1
        db.close()
        repository = ProfileRepository(context, AppPreferences(context))
        val saved = repository!!.activeProfile()
        assertEquals("kept", saved.id)
        assertEquals(175f, saved.heightCm)
        assertEquals(null, saved.forearmLengthCm)
        assertEquals(null, saved.lowerLegLengthCm)
    }

    @Test
    fun creationAndActiveSelectionPersistAcrossRepositoryRecreation() {
        val preferences = AppPreferences(context)
        repository = ProfileRepository(context, preferences)
        val created = repository!!.createProfile(testProfile("Mika"))
        repository!!.switchActiveProfile(created.id)
        repository!!.close()

        repository = ProfileRepository(context, AppPreferences(context))

        assertNotNull(repository!!.listProfiles().singleOrNull { it.id == created.id })
        assertEquals(created.id, repository!!.activeProfile().id)
    }

    @Test
    fun editingIdentityPreservesOwnedProgressAndHistory() {
        repository = ProfileRepository(context, AppPreferences(context))
        val profile = repository!!.activeProfile()
        repository!!.saveLearningProgress(LearningProgress(
            profile.id, "JODAN_PUNCH", "controlled_technique", LearningStatus.COMPLETED,
        ))
        repository!!.saveTrainingSession(TrainingSession(
            profileId = profile.id,
            mode = TrainingMode.PRACTICE,
            skillOrActivityId = "guided_jodan_session",
        ))

        repository!!.updateProfile(profile.copy(
            name = "Renamed",
            skinTonePosition = 0.17f,
            hairColorPosition = 0.83f,
            beltRank = BeltRank.GREEN,
        ))

        assertEquals(1, repository!!.learningProgress(profile.id).size)
        assertEquals(1, repository!!.trainingSessions(profile.id).size)
        assertEquals(0.17f, repository!!.activeProfile().skinTonePosition)
        assertEquals(0.83f, repository!!.activeProfile().hairColorPosition)
    }

    @Test
    fun switchingChangesContextAndDeletingActiveProfileCascadesAndRepairsActiveId() {
        val preferences = AppPreferences(context)
        repository = ProfileRepository(context, preferences)
        val original = repository!!.activeProfile()
        val second = repository!!.createProfile(testProfile("Second"))
        repository!!.saveLearningProgress(LearningProgress(
            second.id, "COUNTING", "practice", LearningStatus.IN_PROGRESS,
        ))
        repository!!.saveTrainingSession(TrainingSession(
            profileId = second.id, mode = TrainingMode.LEARN, skillOrActivityId = "practice",
        ))
        repository!!.saveCalibration(Calibration(
            profileId = second.id, calibrationType = "camera", payload = "{}",
        ))

        repository!!.switchActiveProfile(second.id)
        assertEquals(second.id, repository!!.activeProfile().id)
        repository!!.deleteProfile(second.id)

        assertEquals(original.id, repository!!.activeProfile().id)
        assertNotEquals(second.id, preferences.activeProfileId)
        assertTrue(repository!!.learningProgress(second.id).isEmpty())
        assertTrue(repository!!.trainingSessions(second.id).isEmpty())
        assertTrue(repository!!.calibrations(second.id).isEmpty())
    }

    @Test
    fun deletingOnlyProfileSeedsAReplacementRatherThanLeavingAStaleActiveId() {
        val preferences = AppPreferences(context)
        repository = ProfileRepository(context, preferences)
        val deletedId = repository!!.activeProfile().id

        repository!!.deleteProfile(deletedId)

        val replacement = repository!!.activeProfile()
        assertNotEquals(deletedId, replacement.id)
        assertEquals(replacement.id, preferences.activeProfileId)
        assertEquals(1, repository!!.listProfiles().size)
    }

    @Test
    fun touchingACompletedActivityUpdatesRecencyWithoutLosingCompletion() {
        repository = ProfileRepository(context, AppPreferences(context))
        repository!!.saveActiveLearningProgress("karate-basics", "how-activities-work", LearningStatus.COMPLETED)
        val before = repository!!.learningProgress().single()

        repository!!.touchActiveLearningActivity("karate-basics", "how-activities-work")

        val after = repository!!.learningProgress().single()
        assertEquals(LearningStatus.COMPLETED, after.status)
        assertEquals(before.completedAt, after.completedAt)
        assertTrue(after.lastUpdatedAt >= before.lastUpdatedAt)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun allTwelvePreprocessedAvatarModelsParseAndRender() {
        Profile.AVATAR_BASE_IDS.forEach { baseId ->
            val bitmap = Bitmap.createBitmap(180, 180, Bitmap.Config.ARGB_8888)
            val view = AvatarView(context).apply {
                setAvatar(baseId, 0.42f, 0.58f, BeltRank.ORANGE)
                measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(180, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(180, android.view.View.MeasureSpec.EXACTLY),
                )
                layout(0, 0, 180, 180)
                draw(Canvas(bitmap))
            }
            assertTrue((0 until bitmap.width step 8).any { x ->
                (0 until bitmap.height step 8).any { y -> bitmap.getPixel(x, y) != Color.TRANSPARENT }
            }, "$baseId rendered no visible pixels (${view.width}x${view.height})")
            bitmap.recycle()
        }
    }

    private fun testProfile(name: String) = Profile(
        name = name,
        gender = Gender.MALE,
        ageGroup = AgeGroup.ADULT,
        avatarBaseId = "avatar_04",
        skinTonePosition = 0.4f,
        hairColorPosition = 0.6f,
        beltRank = BeltRank.BLUE,
    )
}
