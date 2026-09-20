package dk.lasse.karatecliprecorder.chrome

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dk.lasse.karatecliprecorder.AppBottomNavigationView
import dk.lasse.karatecliprecorder.AppDestination
import dk.lasse.karatecliprecorder.AppHeaderState
import dk.lasse.karatecliprecorder.AppHeaderView
import dk.lasse.karatecliprecorder.AppNavigationState
import dk.lasse.karatecliprecorder.HeaderAttentionTarget
import dk.lasse.karatecliprecorder.HeaderTrailingAction
import dk.lasse.karatecliprecorder.R
import dk.lasse.karatecliprecorder.profile.AgeGroup
import dk.lasse.karatecliprecorder.profile.BeltRank
import dk.lasse.karatecliprecorder.profile.Gender
import dk.lasse.karatecliprecorder.profile.Profile
import dk.lasse.karatecliprecorder.profile.ProfileAvatarState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppChromeOnboardingStateTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val userProfile = Profile(
        name = "Lasse",
        gender = Gender.MALE,
        ageGroup = AgeGroup.ADULT,
        avatarBaseId = "avatar_01",
        skinTonePosition = 0.5f,
        hairColorPosition = 0.35f,
        beltRank = BeltRank.WHITE,
    )

    @Test fun fullOnboardingStateTransitionDemonstration() {
        val header = AppHeaderView(context)
        val navigation = AppBottomNavigationView(context)
        var currentNavSelection: AppDestination = AppDestination.HOME
        navigation.onDestinationSelected = { currentNavSelection = it }

        // ----------------------------------------------------
        // State A: First launch
        // Header: Karate Analyzer / Welcome, Unknown profile avatar
        // Bottom nav: [ Home ] only, Home is selected
        // ----------------------------------------------------
        val stateAHeader = AppHeaderState(
            title = context.getString(R.string.app_display_name),
            subtitle = context.getString(R.string.home_welcome),
            trailingAction = HeaderTrailingAction.ProfileShortcut(ProfileAvatarState.UnknownProfile) {},
        )
        val stateANav = AppNavigationState(
            visibleDestinations = listOf(AppDestination.HOME),
            selectedDestination = AppDestination.HOME,
        )

        header.setHeaderState(stateAHeader)
        navigation.setNavigationState(stateANav)

        assertEquals(1, navigation.childCount)
        assertTrue(navigation.getChildAt(0).isSelected)
        assertEquals(AppDestination.HOME, stateANav.resolvedSelectedDestination())

        // ----------------------------------------------------
        // State B: Profile discovery
        // Sensei directs attention to the profile avatar
        // Bottom nav remains [ Home ] only
        // ----------------------------------------------------
        val stateBHeader = stateAHeader.copy(
            attentionTarget = HeaderAttentionTarget.TRAILING_PROFILE,
        )
        header.setHeaderState(stateBHeader)

        assertEquals(1, navigation.childCount)
        assertTrue(navigation.getChildAt(0).isSelected)

        // ----------------------------------------------------
        // State C / D: Learning unlocked!
        // Profile setup complete -> Known profile in header
        // Navigation expands: [ Home ] [ Learning ]
        // Learning has discovery attention, but HOME REMAINS SELECTED
        // ----------------------------------------------------
        val stateCHeader = AppHeaderState(
            title = context.getString(R.string.app_display_name),
            subtitle = context.getString(R.string.home_welcome_named, userProfile.name),
            trailingAction = HeaderTrailingAction.ProfileShortcut(ProfileAvatarState.ActiveWithAvatar(userProfile)) {},
        )
        val stateCNav = AppNavigationState(
            visibleDestinations = listOf(AppDestination.HOME, AppDestination.LEARNING),
            selectedDestination = AppDestination.HOME,
            newlyUnlockedDestinations = setOf(AppDestination.LEARNING),
            attentionDestination = AppDestination.LEARNING,
        )

        header.setHeaderState(stateCHeader)
        navigation.setNavigationState(stateCNav)

        assertEquals(2, navigation.childCount)
        val homeView = navigation.getChildAt(0)
        val learningView = navigation.getChildAt(1)

        // Critical rule: Home remains selected, no automatic navigation to Learning
        assertTrue(homeView.isSelected, "Home must remain selected when Learning unlocks")
        assertFalse(learningView.isSelected, "Learning must not be selected automatically")
        assertEquals(AppDestination.HOME, currentNavSelection)

        // ----------------------------------------------------
        // State E: User manually taps Learning
        // Learning becomes selected
        // ----------------------------------------------------
        learningView.performClick()
        assertEquals(AppDestination.LEARNING, currentNavSelection)

        val stateENav = AppNavigationState(
            visibleDestinations = listOf(AppDestination.HOME, AppDestination.LEARNING),
            selectedDestination = AppDestination.LEARNING,
        )
        navigation.setNavigationState(stateENav)

        assertFalse(navigation.getChildAt(0).isSelected)
        assertTrue(navigation.getChildAt(1).isSelected)
    }
}

