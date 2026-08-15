package dk.lasse.karatecliprecorder

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MainMenuAvailabilityTest {
    @Test fun closingCompletedLevel1ReenablesEveryMainMenuAction() {
        val availability = mainMenuAvailability(
            recordingState = RecordingState.IDLE,
            guidedSessionActive = false,
            findYourWeaponActive = false,
            japaneseCountActive = false,
            punchHeightActive = false,
        )

        assertTrue(availability.cameraActionsEnabled)
        assertTrue(availability.countActionsEnabled)
        assertFalse(availability.cancelEnabled)
    }

    @Test fun activeLevel1DisablesMainMenuActionsUntilItIsClosed() {
        val availability = mainMenuAvailability(
            recordingState = RecordingState.IDLE,
            guidedSessionActive = false,
            findYourWeaponActive = false,
            japaneseCountActive = true,
            punchHeightActive = false,
        )

        assertFalse(availability.cameraActionsEnabled)
        assertFalse(availability.countActionsEnabled)
        assertTrue(availability.cancelEnabled)
    }

    @Test fun punchHeightSessionExclusivelyOwnsTheMainMenu() {
        val availability = mainMenuAvailability(
            recordingState = RecordingState.IDLE,
            guidedSessionActive = false,
            findYourWeaponActive = false,
            japaneseCountActive = false,
            punchHeightActive = true,
        )

        assertFalse(availability.cameraActionsEnabled)
        assertFalse(availability.countActionsEnabled)
        assertTrue(availability.cancelEnabled)
    }

    @Test fun cameraSetupSessionExclusivelyOwnsTheMainMenu() {
        val availability = mainMenuAvailability(
            recordingState = RecordingState.IDLE,
            guidedSessionActive = false,
            findYourWeaponActive = false,
            japaneseCountActive = false,
            punchHeightActive = false,
            cameraSetupActive = true,
        )

        assertFalse(availability.cameraActionsEnabled)
        assertFalse(availability.countActionsEnabled)
        assertTrue(availability.cancelEnabled)
    }
}
