package dk.lasse.karatecliprecorder.skillcoach

enum class SkillCoachAction {
    RECORD_MOVEMENTS,
    TECHNIQUE_REVIEW,
    FOCUS_ON_ONE_THING,
    RECORD_AND_ANALYZE,
    VIEW_RECENT_ANALYSIS,
}

enum class SkillCoachIllustration {
    SENSEI_SPEAKING,
}

data class PersonalizedGuidanceState(
    /** Factual app information: an observation, recommendation, or feature explanation. */
    val recommendation: String,
    /** Short-form Sensei dialogue; keep it to about three short lines at the standard text size. */
    val senseiSpeech: String,
    /** Concise label for the primary action offered by this guidance state. */
    val ctaLabel: String,
    val ctaAction: SkillCoachAction,
    val illustration: SkillCoachIllustration? = SkillCoachIllustration.SENSEI_SPEAKING,
)

data class SkillCoachToolState(
    val title: String,
    val subtitle: String,
    val action: SkillCoachAction,
)

data class SkillCoachRecentAnalysisState(
    val title: String,
    val detail: String,
    val action: SkillCoachAction,
    val isDemo: Boolean,
)

data class SkillCoachLandingState(
    val guidance: PersonalizedGuidanceState,
    val tools: List<SkillCoachToolState>,
    val recentAnalyses: List<SkillCoachRecentAnalysisState>,
) {
    companion object {
        fun initial(includeDemoRecent: Boolean): SkillCoachLandingState = SkillCoachLandingState(
            guidance = PersonalizedGuidanceState(
                recommendation = "Record a few movements to find what to improve next.",
                senseiSpeech = "Show me a few movements and I’ll know where to start.",
                ctaLabel = "Record movements",
                ctaAction = SkillCoachAction.RECORD_MOVEMENTS,
            ),
            tools = listOf(
                SkillCoachToolState(
                    title = "Technique Review",
                    subtitle = "Choose a technique and get feedback on the whole movement.",
                    action = SkillCoachAction.TECHNIQUE_REVIEW,
                ),
                SkillCoachToolState(
                    title = "Focus on One Thing",
                    subtitle = "Work on height, hikite, directness, speed, and more.",
                    action = SkillCoachAction.FOCUS_ON_ONE_THING,
                ),
                SkillCoachToolState(
                    title = "Record & Analyze",
                    subtitle = "Capture movements hands-free or with help, then review them.",
                    action = SkillCoachAction.RECORD_AND_ANALYZE,
                ),
            ),
            recentAnalyses = if (includeDemoRecent) {
                listOf(
                    SkillCoachRecentAnalysisState(
                        title = "Chūdan punches",
                        detail = "8 movements · Today",
                        action = SkillCoachAction.VIEW_RECENT_ANALYSIS,
                        isDemo = true,
                    ),
                )
            } else {
                emptyList()
            },
        )
    }
}
