package dk.lasse.karatecliprecorder.learningactivity

import dk.lasse.karatecliprecorder.learning.CountTrainingPhase
import dk.lasse.karatecliprecorder.learning.CountTrainingSession

data class JapaneseCountingTestPresentation(
    val shellState: ActivityShellState,
    val session: CountTrainingSession,
) {
    val recognizedCount: Int = session.normalizedSequence.size.coerceIn(0, session.expectedSequence.size)
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
