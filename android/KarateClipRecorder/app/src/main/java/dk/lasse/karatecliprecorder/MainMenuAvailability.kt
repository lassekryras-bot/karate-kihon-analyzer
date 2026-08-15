package dk.lasse.karatecliprecorder

data class MainMenuAvailability(
    val cameraActionsEnabled: Boolean,
    val countActionsEnabled: Boolean,
    val cancelEnabled: Boolean,
)

fun mainMenuAvailability(
    recordingState: RecordingState,
    guidedSessionActive: Boolean,
    findYourWeaponActive: Boolean,
    japaneseCountActive: Boolean,
    punchHeightActive: Boolean,
    cameraSetupActive: Boolean = false,
): MainMenuAvailability {
    val sessionActive = guidedSessionActive || findYourWeaponActive || japaneseCountActive || punchHeightActive || cameraSetupActive
    val cameraReady = recordingState == RecordingState.IDLE ||
        recordingState == RecordingState.SAVED ||
        recordingState == RecordingState.FAILED
    return MainMenuAvailability(
        cameraActionsEnabled = cameraReady && !sessionActive,
        countActionsEnabled = !sessionActive,
        cancelEnabled = sessionActive,
    )
}
