package dk.lasse.karatecliprecorder.learning

data class JapaneseCountLevel1State(
    val isActive: Boolean = false,
    val itemIndex: Int = 0,
    val isComplete: Boolean = false,
) {
    val item: JapaneseCountLessonItem?
        get() = if (isActive) JapaneseCountLesson.items.getOrNull(itemIndex) else null
}

class JapaneseCountLevel1Controller(
    private val onStateChanged: (JapaneseCountLevel1State) -> Unit,
) {
    var state: JapaneseCountLevel1State = JapaneseCountLevel1State()
        private set

    fun start() {
        state = JapaneseCountLevel1State(isActive = true, itemIndex = 0)
        onStateChanged(state)
    }

    fun next() {
        if (!state.isActive) return
        val nextIndex = state.itemIndex + 1
        state = if (nextIndex in JapaneseCountLesson.items.indices) {
            JapaneseCountLevel1State(isActive = true, itemIndex = nextIndex)
        } else {
            JapaneseCountLevel1State(isActive = false, itemIndex = state.itemIndex, isComplete = true)
        }
        onStateChanged(state)
    }

    fun back() {
        if (!state.isActive) return
        state = state.copy(itemIndex = (state.itemIndex - 1).coerceAtLeast(0))
        onStateChanged(state)
    }

    fun cancel() {
        if (!state.isActive) return
        state = JapaneseCountLevel1State()
        onStateChanged(state)
    }
}
