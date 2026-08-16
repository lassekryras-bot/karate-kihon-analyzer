package dk.lasse.karatecliprecorder.learningartwork

import dk.lasse.karatecliprecorder.R
import dk.lasse.karatecliprecorder.enso.EnsoThemeTokens

/** Semantic activity metadata shared by every learning path. */
enum class LearningActivityType {
    PRACTICE,
    TEST,
}

/** Foreground identity is independent from activity type and Enso treatment. */
enum class LearningArtworkForeground(internal val rawResourceId: Int) {
    JAPANESE_COUNTING(R.raw.japanese_counting),
}

/** Generic visual-language mapping: practice is neutral, tests use muted iron red. */
object LearningArtworkStyleResolver {
    fun ensoBaseColor(activityType: LearningActivityType): Int = when (activityType) {
        LearningActivityType.PRACTICE -> EnsoThemeTokens.ensoPracticeBaseColor
        LearningActivityType.TEST -> EnsoThemeTokens.ensoTestBaseColor
    }
}
