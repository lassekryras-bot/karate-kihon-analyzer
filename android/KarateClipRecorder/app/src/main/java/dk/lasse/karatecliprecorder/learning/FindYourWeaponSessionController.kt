package dk.lasse.karatecliprecorder.learning

class FindYourWeaponSessionController(
    private val onStateChanged: (FindYourWeaponState) -> Unit,
) {
    var state: FindYourWeaponState = FindYourWeaponState.IDLE
        private set

    fun start() {
        updateState(FindYourWeaponState.OPEN_PALM_GUIDE)
    }

    fun cancel() {
        updateState(FindYourWeaponState.CANCELLED)
    }

    private fun updateState(nextState: FindYourWeaponState) {
        state = nextState
        onStateChanged(nextState)
    }
}
