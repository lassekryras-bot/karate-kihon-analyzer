package dk.lasse.karatecliprecorder.home

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import dk.lasse.karatecliprecorder.AppPreferences
import dk.lasse.karatecliprecorder.HomeScreenView
import dk.lasse.karatecliprecorder.R
import dk.lasse.karatecliprecorder.learningpath.DraftLearningPathCatalog
import dk.lasse.karatecliprecorder.learningpath.KARATE_BASICS_PATH_ID
import dk.lasse.karatecliprecorder.learningpath.LearningPathCatalog
import dk.lasse.karatecliprecorder.profile.LearningStatus
import dk.lasse.karatecliprecorder.profile.Profile
import dk.lasse.karatecliprecorder.profile.ProfileRepository
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class HomeScreenViewTest {
    private lateinit var context: Context
    private lateinit var preferences: AppPreferences
    private lateinit var repository: ProfileRepository
    private var learningNavigated = false
    private var profileNavigated = false
    private var createProfileNavigated = false
    private var recordedReps: Int? = null

    @BeforeTest
    fun setUp() {
        dk.lasse.karatecliprecorder.training.TrainingServices.closeForTests()
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("trainee_profiles.db")
        context.getSharedPreferences("karate_kihon_analyzer_preferences", Context.MODE_PRIVATE)
            .edit().clear().commit()

        preferences = AppPreferences(context)
        repository = ProfileRepository(context, preferences)
        learningNavigated = false
        profileNavigated = false
        createProfileNavigated = false
        recordedReps = null
    }

    @AfterTest
    fun tearDown() {
        repository.close()
        dk.lasse.karatecliprecorder.training.TrainingServices.closeForTests()
    }

    private fun createHomeScreen(controller: HomeOnboardingController? = null): HomeScreenView {
        val paths = LearningPathCatalog.create()
        val karateBasics = DraftLearningPathCatalog.karateBasics(context)
        return HomeScreenView(
            context = context,
            profileRepository = repository,
            onProfile = { profileNavigated = true },
            learningPaths = paths,
            karateBasics = karateBasics,
            onContinue = {},
            onLearn = { learningNavigated = true },
            onPractice = {},
            onSkillCoach = {},
            onTrain = {},
            onProgress = {},
            onSettings = {},
            preferences = preferences,
            controller = controller,
            onCreateProfile = { createProfileNavigated = true },
            onRecordStraightPunches = { reps -> recordedReps = reps },
        )
    }

    @Test
    fun homeScreenRendersStateAWithCreateProfileCard() {
        val homeScreen = createHomeScreen()

        assertEquals(View.VISIBLE, homeScreen.actionCard.visibility)
        assertEquals(SenseiHomeMode.NO_PROFILE, homeScreen.onboardingController.currentMode())

        // Action card displays "Create profile" CTA
        val button = homeScreen.actionCard.getChildAt(homeScreen.actionCard.childCount - 1) as TextView
        assertEquals(context.getString(R.string.home_action_create_profile), button.text.toString())

        // Tapping action button triggers direct profile creation
        button.performClick()
        assertTrue(createProfileNavigated)
        assertFalse(profileNavigated)
    }

    @Test
    fun homeScreenRendersStateBWithContinueLearningCard() {
        val active = repository.activeProfile()
        repository.updateProfile(active.copy(name = "Kenji"))

        val homeScreen = createHomeScreen()

        assertEquals(View.VISIBLE, homeScreen.actionCard.visibility)
        assertEquals(SenseiHomeMode.ACTIVE_PROFILE_LEARNING, homeScreen.onboardingController.currentMode())

        val button = homeScreen.actionCard.getChildAt(homeScreen.actionCard.childCount - 1) as TextView
        assertEquals(context.getString(R.string.home_action_continue_learning), button.text.toString())

        button.performClick()
        assertTrue(learningNavigated)
    }

    @Test
    fun homeScreenRendersStateCWithRecordStraightPunchesCard() {
        val active = repository.activeProfile()
        repository.updateProfile(active.copy(name = "Kenji"))

        // Complete learning path
        DraftLearningPathCatalog.karateBasics(context).activities.forEach {
            repository.saveActiveLearningProgress(KARATE_BASICS_PATH_ID, it.id, LearningStatus.COMPLETED)
        }

        val homeScreen = createHomeScreen()

        assertEquals(View.VISIBLE, homeScreen.actionCard.visibility)
        assertEquals(SenseiHomeMode.ACTIVE_PROFILE_TRAINING, homeScreen.onboardingController.currentMode())

        val button = homeScreen.actionCard.getChildAt(homeScreen.actionCard.childCount - 1) as TextView
        assertEquals(context.getString(R.string.home_action_record_punches, 30), button.text.toString())

        button.performClick()
        assertEquals(30, recordedReps)
    }

    @Test
    fun profileSaveTransitionsHomeScreenToActiveProfile() {
        val homeScreen = createHomeScreen()
        assertEquals(SenseiHomeMode.NO_PROFILE, homeScreen.onboardingController.currentMode())

        val profile = repository.createProfile(Profile.default().copy(name = "Sakura"))
        repository.switchActiveProfile(profile.id)
        homeScreen.onboardingController.onProfileCompleted()

        assertEquals(SenseiHomeMode.ACTIVE_PROFILE_LEARNING, homeScreen.onboardingController.currentMode())
    }
}
