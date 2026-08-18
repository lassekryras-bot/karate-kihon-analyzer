package dk.lasse.karatecliprecorder.learningpath

import dk.lasse.karatecliprecorder.profile.LearningProgress
import dk.lasse.karatecliprecorder.profile.LearningStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class LearningPathProgressResolverTest {
    private val paths = LearningPathCatalog.create()

    @Test fun persistedCompletionUpdatesTheResolvedStepCount() {
        val records = listOf(progress(LearningPathId.JODAN_PUNCH, "step-4", updatedAt = 10L))

        val jodan = LearningPathProgressResolver.resolve(paths, records)
            .first { it.id == LearningPathId.JODAN_PUNCH }

        assertEquals(4, jodan.progressValue)
        assertEquals(LearningProgressState.COMPLETED, jodan.steps[3].progressState)
    }

    @Test fun continueCardUsesTheMostRecentlyUpdatedLearningPath() {
        val records = listOf(
            progress(LearningPathId.JODAN_PUNCH, "step-4", updatedAt = 10L),
            progress(LearningPathId.JAPANESE_COUNTING, "step-1", updatedAt = 20L),
        )

        val current = LearningPathProgressResolver.continuePath(paths, records)

        assertEquals(LearningPathId.JAPANESE_COUNTING, current.id)
        assertEquals(1, current.progressValue)
        assertEquals(2, current.totalSteps)
    }

    @Test fun completedActivityKeepsItsDestinationAndUnlocksTheNextActivity() {
        val records = listOf(progress(LearningPathId.JAPANESE_COUNTING, "step-1", updatedAt = 20L))

        val counting = LearningPathProgressResolver.resolve(paths, records)
            .first { it.id == LearningPathId.JAPANESE_COUNTING }

        assertEquals(LearningProgressState.COMPLETED, counting.steps[0].progressState)
        assertEquals(LearningDestination.JAPANESE_COUNTING_PRACTICE, counting.steps[0].destination)
        assertEquals(LearningProgressState.CURRENT, counting.steps[1].progressState)
    }

    private fun progress(path: LearningPathId, activityId: String, updatedAt: Long) = LearningProgress(
        profileId = "profile",
        learningPathId = path.name,
        activityId = activityId,
        status = LearningStatus.COMPLETED,
        lastUpdatedAt = updatedAt,
        completedAt = updatedAt,
    )
}
