package dk.lasse.karatecliprecorder.chrome

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import dk.lasse.karatecliprecorder.AppHeaderState
import dk.lasse.karatecliprecorder.AppHeaderView
import dk.lasse.karatecliprecorder.HeaderLeadingAction
import dk.lasse.karatecliprecorder.HeaderTrailingAction
import dk.lasse.karatecliprecorder.R
import dk.lasse.karatecliprecorder.profile.AgeGroup
import dk.lasse.karatecliprecorder.profile.BeltRank
import dk.lasse.karatecliprecorder.profile.Gender
import dk.lasse.karatecliprecorder.profile.Profile
import dk.lasse.karatecliprecorder.profile.ProfileAvatarButton
import dk.lasse.karatecliprecorder.profile.ProfileAvatarState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppChromeHeaderTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val testProfile = Profile(
        name = "Lasse",
        gender = Gender.MALE,
        ageGroup = AgeGroup.ADULT,
        avatarBaseId = "avatar_01",
        skinTonePosition = 0.5f,
        hairColorPosition = 0.3f,
        beltRank = BeltRank.WHITE,
    )

    @Test fun unknownProfileStateRendersSelectProfileDescription() {
        val button = ProfileAvatarButton(context, ProfileAvatarState.UnknownProfile) {}
        assertEquals(context.getString(R.string.select_profile), button.contentDescription)
        assertTrue(button.isClickable)
        assertTrue(button.isFocusable)
    }

    @Test fun activeProfileWithAvatarRendersProfileName() {
        val button = ProfileAvatarButton(context, ProfileAvatarState.ActiveWithAvatar(testProfile)) {}
        assertEquals("Open Lasse's profile", button.contentDescription)
    }

    @Test fun activeProfileWithoutAvatarRendersProfileName() {
        val button = ProfileAvatarButton(context, ProfileAvatarState.ActiveWithoutAvatar(testProfile)) {}
        assertEquals("Open Lasse's profile", button.contentDescription)
    }

    @Test fun headerMaintainsStableTitleGeometryAcrossSlotChanges() {
        val header = AppHeaderView(context)
        val density = context.resources.displayMetrics.density
        val expected56px = (56 * density).toInt()

        // State 1: No leading, no trailing
        header.setHeaderState(AppHeaderState(
            title = "Karate Analyzer",
            subtitle = "Welcome",
            leadingAction = HeaderLeadingAction.None,
            trailingAction = HeaderTrailingAction.None,
        ))

        val titleContainer = header.getChildAt(0) // titleContainer is first child
        val params1 = titleContainer.layoutParams as FrameLayout.LayoutParams
        assertEquals(expected56px, params1.marginStart)
        assertEquals(expected56px, params1.marginEnd)

        // State 2: Back button visible, trailing profile visible
        header.setHeaderState(AppHeaderState(
            title = "Karate Analyzer",
            subtitle = "Welcome Lasse",
            leadingAction = HeaderLeadingAction.Back("Back") {},
            trailingAction = HeaderTrailingAction.ProfileShortcut(ProfileAvatarState.ActiveWithAvatar(testProfile)) {},
        ))

        val params2 = titleContainer.layoutParams as FrameLayout.LayoutParams
        assertEquals(expected56px, params2.marginStart)
        assertEquals(expected56px, params2.marginEnd)

        // State 3: Unknown profile, no back button
        header.setHeaderState(AppHeaderState(
            title = "Karate Analyzer",
            subtitle = "Welcome",
            leadingAction = HeaderLeadingAction.None,
            trailingAction = HeaderTrailingAction.ProfileShortcut(ProfileAvatarState.UnknownProfile) {},
        ))

        val params3 = titleContainer.layoutParams as FrameLayout.LayoutParams
        assertEquals(expected56px, params3.marginStart)
        assertEquals(expected56px, params3.marginEnd)
    }

    @Test fun backActionTriggersCallback() {
        val header = AppHeaderView(context)
        var backClicked = false

        header.setHeaderState(AppHeaderState(
            title = "Detail",
            leadingAction = HeaderLeadingAction.Back("Back") { backClicked = true },
        ))

        // Find and click the back button
        val leadingHost = header.getChildAt(1) as FrameLayout
        assertEquals(View.VISIBLE, leadingHost.visibility)
        val backButton = leadingHost.getChildAt(0)
        backButton.performClick()

        assertTrue(backClicked)
    }
}

