package dk.lasse.karatecliprecorder.learningpath

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LearningPathModelTest {
    @Test fun progressionGapUsesCardsAndClampsAvailableSpace() {
        fun gap(availableHeight: Int?) = ProgressionGapCalculator.calculate(
            availableHeight = availableHeight,
            totalCardHeight = 420,
            verticalPadding = 16,
            gapCount = 3,
            minimumGap = 28,
            targetGap = 48,
            maximumGap = 56,
        )
        assertEquals(48, gap(null))
        assertEquals(28, gap(500))
        assertEquals(48, gap(580))
        assertEquals(56, gap(760))
    }

    @Test fun japaneseCountingIsOnePathWithPracticeAndTestSteps() {
        val paths = LearningPathCatalog.create()
        val countingPaths = paths.filter { it.id == LearningPathId.JAPANESE_COUNTING }

        assertEquals(1, countingPaths.size)
        val path = countingPaths.single()
        assertEquals("Japanese Basic Counting", path.title)
        assertEquals(2, path.totalSteps)
        assertEquals(listOf("Practice", "Test"), path.steps.map(LearningStep::title))
        assertEquals(
            LearningDestination.JAPANESE_COUNTING_PRACTICE,
            path.steps.first().destination,
        )
        assertEquals(
            LearningDestination.JAPANESE_COUNTING_TEST,
            path.steps.last().destination,
        )
    }

    @Test fun regularStatesResolveFromThreeBaseMarkersAndOneLockOverlay() {
        val completed = ProgressMarkerVisualResolver.resolve(
            LearningStepType.REGULAR,
            LearningProgressState.COMPLETED,
        )
        val current = ProgressMarkerVisualResolver.resolve(
            LearningStepType.REGULAR,
            LearningProgressState.CURRENT,
        )
        val available = ProgressMarkerVisualResolver.resolve(
            LearningStepType.REGULAR,
            LearningProgressState.AVAILABLE,
        )
        val locked = ProgressMarkerVisualResolver.resolve(
            LearningStepType.REGULAR,
            LearningProgressState.LOCKED,
        )

        assertEquals(ProgressMarkerAsset.COMPLETED, completed.baseAsset)
        assertEquals(ProgressMarkerTint.RED, completed.baseTint)
        assertEquals(ProgressMarkerTint.RED, completed.centerFillTint)
        assertEquals(ProgressMarkerAsset.CURRENT, current.baseAsset)
        assertEquals(ProgressMarkerTint.RED, current.baseTint)
        assertEquals(ProgressMarkerTint.RED, current.centerFillTint)
        assertEquals(ProgressMarkerAsset.AVAILABLE, available.baseAsset)
        assertEquals(ProgressMarkerTint.RED, available.baseTint)
        assertEquals(ProgressMarkerTint.PALE_RED, available.centerFillTint)
        assertNull(locked.baseAsset)
        assertTrue(locked.drawLockedSurface)
        assertTrue(locked.lockOverlay)
    }

    @Test fun milestoneStatesAlwaysReuseTheBlossomAsset() {
        LearningProgressState.entries.forEach { state ->
            val visual = ProgressMarkerVisualResolver.resolve(LearningStepType.MILESTONE, state)
            assertEquals(ProgressMarkerAsset.MILESTONE, visual.baseAsset)
            assertFalse(visual.drawLockedSurface)
        }

        val completed = ProgressMarkerVisualResolver.resolve(
            LearningStepType.MILESTONE,
            LearningProgressState.COMPLETED,
        )
        val locked = ProgressMarkerVisualResolver.resolve(
            LearningStepType.MILESTONE,
            LearningProgressState.LOCKED,
        )
        assertEquals(ProgressMarkerTint.RED, completed.baseTint)
        assertEquals(ProgressMarkerTint.GRAY, locked.baseTint)
        assertTrue(locked.lockOverlay)
    }
}
