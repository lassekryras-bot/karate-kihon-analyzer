package dk.lasse.karatecliprecorder.learningpath

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KarateBasicsProgressResolverTest {
    private val gettingStarted = DraftSectionDefinition(
        id = "getting-started",
        title = "Getting Started",
        activities = listOf(
            activity("create-profile", type = DraftActivityType.CONDITIONAL_PROFILE),
            activity("how-activities-work", prerequisites = listOf("create-profile")),
        ),
    )
    private val voice = DraftSectionDefinition(
        id = "voice",
        title = "Voice",
        activities = listOf(
            activity("osu", prerequisites = listOf("how-activities-work")),
            activity("ready-osu", prerequisites = listOf("osu")),
        ),
    )
    private val counting = DraftSectionDefinition(
        id = "counting",
        title = "Counting",
        activities = listOf(
            activity(
                "practice-count-1-10",
                type = DraftActivityType.JAPANESE_COUNTING_PRACTICE,
                prerequisites = listOf("how-activities-work"),
            ),
            activity(
                "test-count-1-10",
                type = DraftActivityType.JAPANESE_COUNTING_TEST,
                prerequisites = listOf("practice-count-1-10"),
            ),
        ),
    )
    private val setup = DraftSectionDefinition(
        id = "setup",
        title = "Setup",
        activities = listOf(activity("find-space", prerequisites = listOf("how-activities-work"))),
    )
    private val technique = DraftSectionDefinition(
        id = "technique",
        title = "Technique",
        activities = listOf(activity("find-weapon", prerequisites = listOf("how-activities-work"))),
    )
    private val path = DraftLearningPathDefinition(
        id = KARATE_BASICS_PATH_ID,
        title = "Karate Basics",
        purpose = "Draft",
        sections = listOf(gettingStarted, voice, counting, setup, technique),
    )

    @Test fun activeProfileCompletesConditionalStepAndUnlocksIntroduction() {
        val resolved = DraftLearningPathProgressResolver.resolve(path, emptySet(), activeProfileExists = true)

        assertEquals(DraftActivityProgressState.COMPLETED, resolved.stateOf("create-profile"))
        assertEquals(DraftActivityProgressState.AVAILABLE, resolved.stateOf("how-activities-work"))
    }

    @Test fun completingIntroductionUnlocksFourEarlyBranchesInParallel() {
        val resolved = DraftLearningPathProgressResolver.resolve(
            path,
            completedActivityIds = setOf("how-activities-work"),
            activeProfileExists = true,
        )

        assertTrue(listOf("osu", "practice-count-1-10", "find-space", "find-weapon").all {
            resolved.stateOf(it) == DraftActivityProgressState.AVAILABLE
        })
        assertEquals(DraftActivityProgressState.LOCKED, resolved.stateOf("test-count-1-10"))
    }

    @Test fun completedActivitiesRemainCompletedInsteadOfBecomingUnavailable() {
        val resolved = DraftLearningPathProgressResolver.resolve(
            path,
            completedActivityIds = setOf("how-activities-work", "practice-count-1-10"),
            activeProfileExists = true,
        )

        assertEquals(DraftActivityProgressState.COMPLETED, resolved.stateOf("practice-count-1-10"))
        assertEquals(DraftActivityProgressState.AVAILABLE, resolved.stateOf("test-count-1-10"))
    }

    @Test fun continuePrefersTheNextAvailableActivityInTheSameBranch() {
        val next = DraftLearningPathProgressResolver.nextAvailable(
            path,
            completedActivityIds = setOf("how-activities-work", "practice-count-1-10"),
            activeProfileExists = true,
            afterActivityId = "practice-count-1-10",
        )

        assertEquals("test-count-1-10", next?.definition?.id)
    }

    private fun ResolvedDraftLearningPath.stateOf(id: String) = activities
        .first { it.definition.id == id }
        .progressState

    private fun activity(
        id: String,
        type: DraftActivityType = DraftActivityType.PLACEHOLDER,
        prerequisites: List<String> = emptyList(),
    ) = DraftActivityDefinition(id, id, type, prerequisites)
}
