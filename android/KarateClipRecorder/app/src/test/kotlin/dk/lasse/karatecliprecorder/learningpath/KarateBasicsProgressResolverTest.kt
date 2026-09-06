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
            activity("count-1-5", prerequisites = listOf("how-activities-work")),
            activity("count-6-10", prerequisites = listOf("count-1-5")),
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

        assertTrue(listOf("osu", "count-1-5", "find-space", "find-weapon").all {
            resolved.stateOf(it) == DraftActivityProgressState.AVAILABLE
        })
        assertEquals(DraftActivityProgressState.LOCKED, resolved.stateOf("count-6-10"))
    }

    @Test fun completedActivitiesRemainCompletedInsteadOfBecomingUnavailable() {
        val resolved = DraftLearningPathProgressResolver.resolve(
            path,
            completedActivityIds = setOf("how-activities-work", "count-1-5"),
            activeProfileExists = true,
        )

        assertEquals(DraftActivityProgressState.COMPLETED, resolved.stateOf("count-1-5"))
        assertEquals(DraftActivityProgressState.AVAILABLE, resolved.stateOf("count-6-10"))
    }

    @Test fun continuePrefersTheNextAvailableActivityInTheSameBranch() {
        val next = DraftLearningPathProgressResolver.nextAvailable(
            path,
            completedActivityIds = setOf("how-activities-work", "count-1-5"),
            activeProfileExists = true,
            afterActivityId = "count-1-5",
        )

        assertEquals("count-6-10", next?.definition?.id)
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
