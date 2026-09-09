package dk.lasse.karatecliprecorder.learningactivity

import dk.lasse.karatecliprecorder.learning.CountTrainingPhase
import dk.lasse.karatecliprecorder.learning.CountTrainingSession
import dk.lasse.karatecliprecorder.learning.CountTranscriptNormalizer

data class JapaneseCountingTestPresentation(
    val shellState: ActivityShellState,
    val session: CountTrainingSession,
) {
    private val liveCount = if (session.phase == CountTrainingPhase.LISTENING ||
        session.phase == CountTrainingPhase.FINALIZING) {
        session.partialTranscripts.lastOrNull()?.let {
            CountTranscriptNormalizer.normalizeJapaneseTranscript(it).normalizedSequence.size
        } ?: 0
    } else 0
    val recognizedCount: Int = maxOf(session.normalizedSequence.size, liveCount)
        .coerceIn(0, session.expectedSequence.size)
    val highlightedIndex: Int? = (recognizedCount - 1).takeIf { it >= 0 }
    val isListening: Boolean = session.phase == CountTrainingPhase.LISTENING
    val isFinalizing: Boolean = session.phase == CountTrainingPhase.FINALIZING
    val isSuccessful: Boolean = session.phase == CountTrainingPhase.RESULT && session.successful

    companion object {
        fun fromSession(session: CountTrainingSession): JapaneseCountingTestPresentation =
            JapaneseCountingTestPresentation(
                shellState = when (session.phase) {
                    CountTrainingPhase.IDLE,
                    CountTrainingPhase.READY -> ActivityShellState.READY
                    CountTrainingPhase.LISTENING,
                    CountTrainingPhase.FINALIZING -> ActivityShellState.ACTIVE
                    CountTrainingPhase.RESULT -> ActivityShellState.RESULT
                    CountTrainingPhase.ERROR -> ActivityShellState.ERROR
                },
                session = session,
            )
    }
}
