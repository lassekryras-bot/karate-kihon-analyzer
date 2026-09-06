package dk.lasse.karatecliprecorder.learningpath

import dk.lasse.karatecliprecorder.profile.LearningProgress
import dk.lasse.karatecliprecorder.profile.LearningStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RecentLearningResolverTest {
    private val standardPaths = LearningPathCatalog.create()
    private val basics = DraftLearningPathDefinition(
        id = KARATE_BASICS_PATH_ID,
        title = "Karate Basics",
        purpose = "Draft",
        sections = listOf(DraftSectionDefinition(
            id = "getting-started",
            title = "Getting Started",
            activities = listOf(
                DraftActivityDefinition("create-profile", "Create Your Profile", DraftActivityType.CONDITIONAL_PROFILE, emptyList()),
                DraftActivityDefinition("how-activities-work", "How Activities Work", DraftActivityType.PLACEHOLDER, listOf("create-profile")),
            ),
        )),
    )

    @Test fun newestKarateBasicsActivityUpdatesHomeTarget() {
        val target = RecentLearningResolver.resolve(
            standardPaths,
            basics,
            records = listOf(
                progress(LearningPathId.JAPANESE_COUNTING.name, "step-1", LearningStatus.COMPLETED, 10L),
                progress(KARATE_BASICS_PATH_ID, "how-activities-work", LearningStatus.IN_PROGRESS, 20L),
            ),
            activeProfileExists = true,
        )

        assertIs<RecentLearningTarget.Draft>(target)
        assertEquals("How Activities Work", target.activityTitle)
        assertEquals("Karate Basics", target.pathTitle)
    }

    @Test fun newestStandardActivityUpdatesHomeTargetWithTheExactActivity() {
        val target = RecentLearningResolver.resolve(
            standardPaths,
            basics,
            records = listOf(
                progress(KARATE_BASICS_PATH_ID, "how-activities-work", LearningStatus.COMPLETED, 10L),
                progress(LearningPathId.JAPANESE_COUNTING.name, "step-2", LearningStatus.IN_PROGRESS, 20L),
            ),
            activeProfileExists = true,
        )

        assertIs<RecentLearningTarget.Standard>(target)
        assertEquals("Test", target.activityTitle)
        assertEquals("Japanese Basic Counting", target.pathTitle)
    }

    private fun progress(pathId: String, activityId: String, status: LearningStatus, updatedAt: Long) = LearningProgress(
        profileId = "profile",
        learningPathId = pathId,
        activityId = activityId,
        status = status,
        lastUpdatedAt = updatedAt,
        completedAt = updatedAt.takeIf { status == LearningStatus.COMPLETED },
    )
}
