package dk.lasse.karatecliprecorder.learningartwork

import dk.lasse.karatecliprecorder.R
import dk.lasse.karatecliprecorder.enso.EnsoThemeTokens
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class LearningArtworkTest {
    @Test fun activityTypeIsExplicitSemanticMetadata() {
        assertEquals(
            listOf(LearningActivityType.PRACTICE, LearningActivityType.TEST),
            LearningActivityType.entries,
        )
    }

    @Test fun activityTypeSelectsCentralizedEnsoColorWithoutChangingForeground() {
        assertEquals(
            EnsoThemeTokens.ensoPracticeBaseColor,
            LearningArtworkStyleResolver.ensoBaseColor(LearningActivityType.PRACTICE),
        )
        assertEquals(
            EnsoThemeTokens.ensoTestBaseColor,
            LearningArtworkStyleResolver.ensoBaseColor(LearningActivityType.TEST),
        )
        assertNotEquals(
            LearningArtworkStyleResolver.ensoBaseColor(LearningActivityType.PRACTICE),
            LearningArtworkStyleResolver.ensoBaseColor(LearningActivityType.TEST),
        )
        assertEquals(R.raw.japanese_counting, LearningArtworkForeground.JAPANESE_COUNTING.rawResourceId)
    }
}
