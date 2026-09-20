package dk.lasse.karatecliprecorder.home

const val DEFAULT_RECOMMENDED_PUNCH_REPETITIONS = 30

enum class SenseiHomeMode {
    NO_PROFILE,                 // State A: First-run / no configured active profile
    ACTIVE_PROFILE_LEARNING,    // State B: Active profile exists, app learning path incomplete
    ACTIVE_PROFILE_TRAINING,    // State C: Active profile exists, app learning path complete
}

enum class HomeOnboardingPhase {
    WELCOME,            // State A
    PROFILE_DISCOVERY,  // Legacy intermediate state (reconciled when active profile exists)
    LEARNING_DISCOVERY, // State B
    COMPLETED;          // State C / Normal

    companion object {
        fun fromPersistedValue(value: String?): HomeOnboardingPhase = entries.firstOrNull {
            it.name.equals(value, ignoreCase = true)
        } ?: WELCOME
    }
}

data class SenseiMessage(
    val title: String? = null,
    val body: String,
)

sealed interface HomeAction {
    data object CreateProfile : HomeAction
    data object ContinueLearning : HomeAction
    data class RecordStraightPunches(val repetitions: Int = DEFAULT_RECOMMENDED_PUNCH_REPETITIONS) : HomeAction

    // Retained for compatibility
    data class PrimaryCta(
        val category: String,
        val title: String,
        val body: String,
        val buttonLabel: String,
        val onClick: () -> Unit,
    ) : HomeAction

    data class Guidance(
        val text: String,
    ) : HomeAction
}

data class HomeActionCardContent(
    val category: String,
    val title: String,
    val body: String,
    val buttonLabel: String,
)

data class SenseiHomeState(
    val mode: SenseiHomeMode = SenseiHomeMode.NO_PROFILE,
    val message: SenseiMessage,
    val action: HomeAction?,
    val actionContent: HomeActionCardContent? = null,
    val onboardingPhase: HomeOnboardingPhase = when (mode) {
        SenseiHomeMode.NO_PROFILE -> HomeOnboardingPhase.WELCOME
        SenseiHomeMode.ACTIVE_PROFILE_LEARNING -> HomeOnboardingPhase.LEARNING_DISCOVERY
        SenseiHomeMode.ACTIVE_PROFILE_TRAINING -> HomeOnboardingPhase.COMPLETED
    },
)
