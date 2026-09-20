package dk.lasse.karatecliprecorder.chrome

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dk.lasse.karatecliprecorder.AppDestination
import dk.lasse.karatecliprecorder.AppNavigationState
import dk.lasse.karatecliprecorder.AppNavigationStateRepository
import dk.lasse.karatecliprecorder.AppPreferences
import dk.lasse.karatecliprecorder.learningpath.DraftLearningPathCatalog
import dk.lasse.karatecliprecorder.learningpath.KARATE_BASICS_PATH_ID
import dk.lasse.karatecliprecorder.profile.AgeGroup
import dk.lasse.karatecliprecorder.profile.BeltRank
import dk.lasse.karatecliprecorder.profile.Gender
import dk.lasse.karatecliprecorder.profile.LearningStatus
import dk.lasse.karatecliprecorder.profile.Profile
import dk.lasse.karatecliprecorder.profile.ProfileRepository
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class AppNavigationStateRepositoryTest {
    private lateinit var context: Context
    private lateinit var preferences: AppPreferences
    private lateinit var repository: ProfileRepository
    private lateinit var navRepository: AppNavigationStateRepository

    @BeforeTest
    fun setUp() {
        dk.lasse.karatecliprecorder.training.TrainingServices.closeForTests()
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("trainee_profiles.db")
        context.getSharedPreferences("karate_kihon_analyzer_preferences", Context.MODE_PRIVATE)
            .edit().clear().commit()

        preferences = AppPreferences(context)
        repository = ProfileRepository(context, preferences)
        navRepository = AppNavigationStateRepository(context, preferences, repository)
    }

    @AfterTest
    fun tearDown() {
        navRepository.dispose()
        repository.close()
        dk.lasse.karatecliprecorder.training.TrainingServices.closeForTests()
    }

    @Test
    fun cleanInstallHasSingleHomeDestination() {
        val state = navRepository.currentNavigationState()
        assertEquals(listOf(AppDestination.HOME), state.visibleDestinations)
        assertEquals(AppDestination.HOME, state.selectedDestination)
        assertTrue(state.newlyUnlockedDestinations.isEmpty())
        assertNull(state.attentionDestination)
    }

    @Test
    fun activeProfileWithIncompleteLearningShowsHomeAndLearning() {
        val profile = repository.createProfile(Profile.default().copy(name = "Kenji"))
        repository.switchActiveProfile(profile.id)

        val state = navRepository.currentNavigationState()
        assertEquals(listOf(AppDestination.HOME, AppDestination.LEARNING), state.visibleDestinations)
        assertEquals(AppDestination.HOME, state.selectedDestination)
        assertEquals(setOf(AppDestination.LEARNING), state.newlyUnlockedDestinations)
        assertEquals(AppDestination.LEARNING, state.attentionDestination)
    }

    @Test
    fun globalConsistency_visibleDestinationsPersistAcrossPageNavigation() {
        val profile = repository.createProfile(Profile.default().copy(name = "Kenji"))
        repository.switchActiveProfile(profile.id)

        // Starting on Home
        val initialState = navRepository.currentNavigationState()
        assertEquals(listOf(AppDestination.HOME, AppDestination.LEARNING), initialState.visibleDestinations)
        assertEquals(AppDestination.HOME, initialState.selectedDestination)

        // Navigate to Learning
        val target = navRepository.selectDestination(AppDestination.LEARNING)
        assertEquals(AppDestination.LEARNING, target)

        val learningState = navRepository.currentNavigationState()
        assertEquals(listOf(AppDestination.HOME, AppDestination.LEARNING), learningState.visibleDestinations)
        assertEquals(AppDestination.LEARNING, learningState.selectedDestination)

        // Navigate back to Home
        navRepository.selectDestination(AppDestination.HOME)
        val homeState = navRepository.currentNavigationState()
        assertEquals(listOf(AppDestination.HOME, AppDestination.LEARNING), homeState.visibleDestinations)
        assertEquals(AppDestination.HOME, homeState.selectedDestination)
    }

    @Test
    fun activeProfileWithCompletedLearningShowsAllFiveDestinationsInCanonicalOrder() {
        val profile = repository.createProfile(Profile.default().copy(name = "Kenji"))
        repository.switchActiveProfile(profile.id)

        DraftLearningPathCatalog.karateBasics(context).activities.forEach {
            repository.saveActiveLearningProgress(KARATE_BASICS_PATH_ID, it.id, LearningStatus.COMPLETED)
        }

        val state = navRepository.currentNavigationState()
        val expected = listOf(
            AppDestination.HOME,
            AppDestination.LEARNING,
            AppDestination.TRAIN,
            AppDestination.PROGRESS,
            AppDestination.SETTINGS,
        )
        assertEquals(expected, state.visibleDestinations)
        assertEquals(AppDestination.HOME, state.selectedDestination)
        assertTrue(state.newlyUnlockedDestinations.isEmpty())
        assertNull(state.attentionDestination)
    }

    @Test
    fun profileSwitchSafelyFallsBackToHomeWhenCurrentDestinationUnavailable() {
        // Profile A: completed learning (all 5 destinations)
        val profileA = repository.createProfile(Profile.default().copy(name = "Master A"))
        repository.switchActiveProfile(profileA.id)
        DraftLearningPathCatalog.karateBasics(context).activities.forEach {
            repository.saveActiveLearningProgress(KARATE_BASICS_PATH_ID, it.id, LearningStatus.COMPLETED)
        }

        // Navigate to TRAIN
        navRepository.selectDestination(AppDestination.TRAIN)
        assertEquals(AppDestination.TRAIN, navRepository.currentNavigationState().selectedDestination)

        // Profile B: learning incomplete (only HOME and LEARNING)
        val profileB = repository.createProfile(Profile.default().copy(name = "Student B"))
        repository.switchActiveProfile(profileB.id)

        // Navigation state must safely re-resolve to HOME
        val stateAfterSwitch = navRepository.currentNavigationState()
        assertEquals(listOf(AppDestination.HOME, AppDestination.LEARNING), stateAfterSwitch.visibleDestinations)
        assertEquals(AppDestination.HOME, stateAfterSwitch.selectedDestination)
    }

    @Test
    fun profileSwitchPreservesDestinationWhenStillVisible() {
        // Profile A: completed learning
        val profileA = repository.createProfile(Profile.default().copy(name = "Master A"))
        repository.switchActiveProfile(profileA.id)
        DraftLearningPathCatalog.karateBasics(context).activities.forEach {
            repository.saveActiveLearningProgress(KARATE_BASICS_PATH_ID, it.id, LearningStatus.COMPLETED)
        }

        // Navigate to LEARNING
        navRepository.selectDestination(AppDestination.LEARNING)
        assertEquals(AppDestination.LEARNING, navRepository.currentNavigationState().selectedDestination)

        // Profile B: learning incomplete, supports LEARNING
        val profileB = repository.createProfile(Profile.default().copy(name = "Student B"))
        repository.switchActiveProfile(profileB.id)

        val stateAfterSwitch = navRepository.currentNavigationState()
        assertEquals(listOf(AppDestination.HOME, AppDestination.LEARNING), stateAfterSwitch.visibleDestinations)
        assertEquals(AppDestination.LEARNING, stateAfterSwitch.selectedDestination)
    }

    @Test
    fun unlockAcknowledgementClearsAttentionAndDoesNotReplayOnSubsequentVisits() {
        val profile = repository.createProfile(Profile.default().copy(name = "Kenji"))
        repository.switchActiveProfile(profile.id)

        // First unlock: attention on LEARNING
        val unacknowledgedState = navRepository.currentNavigationState()
        assertEquals(setOf(AppDestination.LEARNING), unacknowledgedState.newlyUnlockedDestinations)
        assertEquals(AppDestination.LEARNING, unacknowledgedState.attentionDestination)

        // User navigates to LEARNING -> acknowledged
        navRepository.selectDestination(AppDestination.LEARNING)
        val acknowledgedState = navRepository.currentNavigationState()
        assertTrue(acknowledgedState.newlyUnlockedDestinations.isEmpty())
        assertNull(acknowledgedState.attentionDestination)
        assertTrue(preferences.hasAcknowledgedLearningUnlock)

        // Navigate back to Home and back to Learning: no attention replayed
        navRepository.selectDestination(AppDestination.HOME)
        assertNull(navRepository.currentNavigationState().attentionDestination)

        navRepository.selectDestination(AppDestination.LEARNING)
        assertNull(navRepository.currentNavigationState().attentionDestination)
    }

    @Test
    fun selectingUnavailableDestinationFallsBackToHome() {
        val state = navRepository.currentNavigationState()
        assertEquals(listOf(AppDestination.HOME), state.visibleDestinations)

        // Attempt to select SETTINGS when only HOME is available
        val target = navRepository.selectDestination(AppDestination.SETTINGS)
        assertEquals(AppDestination.HOME, target)
        assertEquals(AppDestination.HOME, navRepository.currentNavigationState().selectedDestination)
    }
}

