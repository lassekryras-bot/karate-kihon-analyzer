package dk.lasse.karatecliprecorder.learning

class FindYourWeaponSessionController(
    private val onStateChanged: (FindYourWeaponState) -> Unit,
) {
    private val steps = FindYourWeaponStep.entries

    var state: FindYourWeaponState = FindYourWeaponState()
        private set

    fun start() {
        updateState(FindYourWeaponState(step = steps.first(), isActive = true))
    }

    fun next() {
        val currentStep = state.step ?: return start()
        val currentIndex = steps.indexOf(currentStep)
        if (currentIndex == steps.lastIndex) {
            complete()
        } else {
            updateState(FindYourWeaponState(step = steps[currentIndex + 1], isActive = true))
        }
    }

    fun back() {
        val currentStep = state.step ?: return
        val currentIndex = steps.indexOf(currentStep)
        if (currentIndex > 0) {
            updateState(FindYourWeaponState(step = steps[currentIndex - 1], isActive = true))
        }
    }

    fun cancel() {
        updateState(FindYourWeaponState())
    }

    fun complete() {
        updateState(FindYourWeaponState(isComplete = true))
    }

    private fun updateState(nextState: FindYourWeaponState) {
        state = nextState
        onStateChanged(nextState)
    }
}
