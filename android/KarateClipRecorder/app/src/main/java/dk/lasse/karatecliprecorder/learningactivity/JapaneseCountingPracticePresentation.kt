package dk.lasse.karatecliprecorder.learningactivity

import dk.lasse.karatecliprecorder.learning.JapaneseCountLesson
import dk.lasse.karatecliprecorder.learning.JapaneseCountLessonItem
import dk.lasse.karatecliprecorder.learning.JapaneseCountLevel1State

data class JapaneseCountingPracticePresentation(
    val shellState: ActivityShellState,
    val itemIndex: Int? = null,
    val item: JapaneseCountLessonItem? = null,
) {
    val pathPosition: String = "1 / 2"
    val previousEnabled: Boolean = shellState == ActivityShellState.ACTIVE && (itemIndex ?: 0) > 0
    val nextLabel: String = if (itemIndex == JapaneseCountLesson.items.lastIndex) "Finish  →" else "Next  →"
    val cameraRequired: Boolean = false
    val microphoneRequired: Boolean = false

    companion object {
        fun ready() = JapaneseCountingPracticePresentation(ActivityShellState.READY)

        fun fromLevel1(state: JapaneseCountLevel1State): JapaneseCountingPracticePresentation = when {
            state.isComplete -> JapaneseCountingPracticePresentation(ActivityShellState.COMPLETE)
            state.isActive && state.item != null -> JapaneseCountingPracticePresentation(
                shellState = ActivityShellState.ACTIVE,
                itemIndex = state.itemIndex,
                item = state.item,
            )
            else -> ready()
        }
    }
}
