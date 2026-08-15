package dk.lasse.karatecliprecorder.learning

class FindYourWeaponAutoAdvanceController(
    val delayMs: Long = DEFAULT_DELAY_MS,
) {
    private var pendingStep: FindYourWeaponStep? = null
    private var consumedStep: FindYourWeaponStep? = null

    fun onAnalysis(
        state: FindYourWeaponAnalysisState,
        currentStep: FindYourWeaponStep?,
        isSessionActive: Boolean,
    ): FindYourWeaponAutoAdvanceDecision {
        if (!isSessionActive || currentStep == null || state.activeStep != currentStep) {
            return FindYourWeaponAutoAdvanceDecision.None
        }
        if (currentStep == FindYourWeaponStep.FRONT_TWO_KNUCKLES) {
            return FindYourWeaponAutoAdvanceDecision.None
        }
        if (state.temporalResult?.accepted != true) {
            return FindYourWeaponAutoAdvanceDecision.None
        }
        if (pendingStep == currentStep || consumedStep == currentStep) {
            return FindYourWeaponAutoAdvanceDecision.None
        }
        pendingStep = currentStep
        consumedStep = currentStep
        return FindYourWeaponAutoAdvanceDecision.Schedule(currentStep, delayMs)
    }

    fun onStepChanged(step: FindYourWeaponStep?) {
        if (pendingStep != step) {
            pendingStep = null
        }
        if (consumedStep != step) {
            consumedStep = null
        }
    }

    fun consumePendingAdvance(
        step: FindYourWeaponStep?,
        isSessionActive: Boolean,
    ): Boolean {
        if (!isSessionActive || step == null || pendingStep != step) {
            return false
        }
        pendingStep = null
        return true
    }

    fun cancelPending() {
        pendingStep = null
    }

    fun reset() {
        pendingStep = null
        consumedStep = null
    }

    companion object {
        const val DEFAULT_DELAY_MS = FindYourWeaponCoachTextGate.DEFAULT_MINIMUM_DISPLAY_MS
    }
}

sealed class FindYourWeaponAutoAdvanceDecision {
    data object None : FindYourWeaponAutoAdvanceDecision()

    data class Schedule(
        val step: FindYourWeaponStep,
        val delayMs: Long,
    ) : FindYourWeaponAutoAdvanceDecision()
}
