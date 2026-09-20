package dk.lasse.karatecliprecorder.home

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dk.lasse.karatecliprecorder.AppDestination
import dk.lasse.karatecliprecorder.AppPreferences
import dk.lasse.karatecliprecorder.HeaderAttentionTarget
import dk.lasse.karatecliprecorder.HeaderTrailingAction
import dk.lasse.karatecliprecorder.R
import dk.lasse.karatecliprecorder.learningpath.DraftLearningPathCatalog
import dk.lasse.karatecliprecorder.learningpath.KARATE_BASICS_PATH_ID
import dk.lasse.karatecliprecorder.profile.LearningStatus
import dk.lasse.karatecliprecorder.profile.Profile
import dk.lasse.karatecliprecorder.profile.ProfileAvatarState
import dk.lasse.karatecliprecorder.profile.ProfileRepository
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class HomeOnboardingControllerTest {
    private lateinit var context: Context
    private lateinit var preferences: AppPreferences
    private lateinit var repository: ProfileRepository

    private var profileNavigationCount = 0
    private var createProfileCount = 0
    private var learningNavigationCount = 0
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
        profileNavigationCount = 0
        createProfileCount = 0
        learningNavigationCount = 0
        recordedReps = null
    }

    @AfterTest
    fun tearDown() {
        repository.close()
        dk.lasse.karatecliprecorder.training.TrainingServices.closeForTests()
    }

    private fun createController() = HomeOnboardingController(
        context = context,
        preferences = preferences,
        profileRepository = repository,
        onOpenProfile = { profileNavigationCount++ },
        onOpenLearning = { learningNavigationCount++ },
        onCreateProfile = { createProfileCount++ },
        onRecordStraightPunches = { reps -> recordedReps = reps },
    )

    @Test
    fun noActiveProfile_showsStateANoProfileHome() {
        val controller = createController()

        assertEquals(SenseiHomeMode.NO_PROFILE, controller.currentMode())
        assertEquals(HomeOnboardingPhase.WELCOME, controller.phase)

        // Header: Title Karate Analyzer, subtitle Welcome, trailing UnknownProfile
        val header = controller.currentHeaderState()
        assertEquals(context.getString(R.string.app_display_name), header.title)
        assertEquals(context.getString(R.string.home_welcome), header.subtitle)
        val trailing = header.trailingAction
        assertIs<HeaderTrailingAction.ProfileShortcut>(trailing)
        assertEquals(ProfileAvatarState.UnknownProfile, trailing.state)
        assertEquals(HeaderAttentionTarget.NONE, header.attentionTarget)

        // Tapping the unknown avatar in State A invokes direct create-profile route!
        trailing.onClick()
        assertEquals(1, createProfileCount)
        assertEquals(0, profileNavigationCount)

        // Navigation: HOME destination only, HOME selected
        val nav = controller.currentNavigationState()
        assertEquals(listOf(AppDestination.HOME), nav.visibleDestinations)
        assertEquals(AppDestination.HOME, nav.selectedDestination)

        // Home State: Sensei welcome + "Create profile" CTA
        val home = controller.currentHomeState()
        assertEquals(SenseiHomeMode.NO_PROFILE, home.mode)
        assertEquals(context.getString(R.string.home_sensei_welcome_title), home.message.title)
        assertEquals(context.getString(R.string.home_sensei_no_profile_body), home.message.body)

        val action = home.action
        assertIs<HomeAction.CreateProfile>(action)
        val content = home.actionContent
        assertNotNull(content)
        assertEquals(context.getString(R.string.home_first_step_label), content.category)
        assertEquals(context.getString(R.string.home_first_step_title), content.title)
        assertEquals(context.getString(R.string.home_no_profile_step_body), content.body)
        assertEquals(context.getString(R.string.home_action_create_profile), content.buttonLabel)

        // Executing action opens direct profile creation
        controller.executeAction(action)
        assertEquals(2, createProfileCount)
    }

    @Test
    fun existingActiveProfileWithStaleDiscoveryPreference_resolvesActiveProfileDirectly() {
        // App already contains an active profile with user name
        val active = repository.activeProfile()
        repository.updateProfile(active.copy(name = "Ryu"))

        // Legacy / stale preference set
        preferences.homeOnboardingPhase = "PROFILE_DISCOVERY"
        preferences.isProfileSetupComplete = false

        val controller = createController()

        // Authoritative active profile wins: never shows NO_PROFILE or PROFILE_DISCOVERY trap
        assertTrue(repository.hasConfiguredActiveProfile())
        assertFalse(controller.currentMode() == SenseiHomeMode.NO_PROFILE)

        val header = controller.currentHeaderState()
        assertEquals(context.getString(R.string.home_welcome_named, "Ryu"), header.subtitle)
        val trailing = header.trailingAction
        assertIs<HeaderTrailingAction.ProfileShortcut>(trailing)
        assertIs<ProfileAvatarState.ActiveWithAvatar>(trailing.state)
    }

    @Test
    fun activeProfileWithIncompleteAppLearning_showsStateBActiveProfileLearningHome() {
        val active = repository.activeProfile()
        repository.updateProfile(active.copy(name = "Kenji"))

        val controller = createController()

        assertEquals(SenseiHomeMode.ACTIVE_PROFILE_LEARNING, controller.currentMode())
        assertEquals(HomeOnboardingPhase.LEARNING_DISCOVERY, controller.phase)

        // Header shows known avatar and Welcome Kenji
        val header = controller.currentHeaderState()
        assertEquals(context.getString(R.string.home_welcome_named, "Kenji"), header.subtitle)
        val trailing = header.trailingAction
        assertIs<HeaderTrailingAction.ProfileShortcut>(trailing)
        assertIs<ProfileAvatarState.ActiveWithAvatar>(trailing.state)

        // Nav unlocks Learning, Home remains selected
        val nav = controller.currentNavigationState()
        assertEquals(listOf(AppDestination.HOME, AppDestination.LEARNING), nav.visibleDestinations)
        assertEquals(AppDestination.HOME, nav.selectedDestination)

        // Sensei directs learner to Learning
        val home = controller.currentHomeState()
        assertEquals(SenseiHomeMode.ACTIVE_PROFILE_LEARNING, home.mode)
        assertEquals(context.getString(R.string.home_sensei_named_greeting, "Kenji"), home.message.title)
        assertEquals(context.getString(R.string.home_sensei_learning_body), home.message.body)

        val action = home.action
        assertIs<HomeAction.ContinueLearning>(action)
        val content = home.actionContent
        assertNotNull(content)
        assertEquals(context.getString(R.string.home_action_continue_learning), content.buttonLabel)

        // Executing action navigates to Learning
        controller.executeAction(action)
        assertEquals(1, learningNavigationCount)
    }

    @Test
    fun activeProfileWithCompleteAppLearning_showsStateCActiveProfileTrainingHome() {
        val active = repository.activeProfile()
        repository.updateProfile(active.copy(name = "Chun-Li"))

        // Mark all activities in Karate Basics complete for this trainee
        val karateBasics = DraftLearningPathCatalog.karateBasics(context)
        karateBasics.activities.forEach { activity ->
            repository.saveActiveLearningProgress(
                learningPathId = KARATE_BASICS_PATH_ID,
                activityId = activity.id,
                status = LearningStatus.COMPLETED,
            )
        }

        val controller = createController()

        assertEquals(SenseiHomeMode.ACTIVE_PROFILE_TRAINING, controller.currentMode())
        assertEquals(HomeOnboardingPhase.COMPLETED, controller.phase)

        // Nav has all standard destinations
        val nav = controller.currentNavigationState()
        assertEquals(
            listOf(AppDestination.HOME, AppDestination.LEARNING, AppDestination.TRAIN, AppDestination.PROGRESS, AppDestination.SETTINGS),
            nav.visibleDestinations,
        )
        assertEquals(AppDestination.HOME, nav.selectedDestination)

        // Sensei greeting: Ready for some training?
        val home = controller.currentHomeState()
        assertEquals(SenseiHomeMode.ACTIVE_PROFILE_TRAINING, home.mode)
        assertEquals(context.getString(R.string.home_sensei_named_greeting, "Chun-Li"), home.message.title)
        assertEquals(context.getString(R.string.home_sensei_training_body), home.message.body)

        // Primary action: Record 30 straight punches
        val action = home.action
        assertIs<HomeAction.RecordStraightPunches>(action)
        assertEquals(DEFAULT_RECOMMENDED_PUNCH_REPETITIONS, action.repetitions)
        assertEquals(30, action.repetitions)

        val content = home.actionContent
        assertNotNull(content)
        assertEquals(context.getString(R.string.home_training_action_category), content.category)
        assertEquals(context.getString(R.string.home_action_record_punches, 30), content.buttonLabel)

        // Executing action passes 30 repetitions to recording launcher
        controller.executeAction(action)
        assertEquals(30, recordedReps)
    }

    @Test
    fun profileSwitching_reResolvesHomeStateImmediately() {
        // Profile 1: learning complete -> State C
        val p1 = repository.activeProfile().copy(name = "Master")
        repository.updateProfile(p1)
        DraftLearningPathCatalog.karateBasics(context).activities.forEach {
            repository.saveActiveLearningProgress(KARATE_BASICS_PATH_ID, it.id, LearningStatus.COMPLETED)
        }

        // Profile 2: brand-new / learning incomplete -> State B
        val p2 = repository.createProfile(Profile.default().copy(name = "Novice"))

        // Switch active back to p1
        repository.switchActiveProfile(p1.id)
        val controller = createController()
        assertEquals(SenseiHomeMode.ACTIVE_PROFILE_TRAINING, controller.currentMode())

        // Switch active to p2
        repository.switchActiveProfile(p2.id)
        assertEquals(SenseiHomeMode.ACTIVE_PROFILE_LEARNING, controller.currentMode())
        assertEquals(context.getString(R.string.home_welcome_named, "Novice"), controller.currentHeaderState().subtitle)
    }

    @Test
    fun createProfileDirectlyFromHome_transitionsToActiveProfileStateOnCompletion() {
        val controller = createController()
        assertEquals(SenseiHomeMode.NO_PROFILE, controller.currentMode())

        // User saves new profile via editor
        val newProfile = repository.createProfile(Profile.default().copy(name = "Sakura"))
        repository.switchActiveProfile(newProfile.id)
        controller.onProfileCompleted()

        // Home immediately re-resolves to active profile
        assertEquals(SenseiHomeMode.ACTIVE_PROFILE_LEARNING, controller.currentMode())
        assertEquals(context.getString(R.string.home_welcome_named, "Sakura"), controller.currentHeaderState().subtitle)
    }
}
