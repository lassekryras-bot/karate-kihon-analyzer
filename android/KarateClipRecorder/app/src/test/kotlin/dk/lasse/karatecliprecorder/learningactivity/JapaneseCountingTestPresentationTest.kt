package dk.lasse.karatecliprecorder.learningactivity

import dk.lasse.karatecliprecorder.learning.CountTrainingPhase
import dk.lasse.karatecliprecorder.learning.CountTrainingSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JapaneseCountingTestPresentationTest {
    @Test fun listeningHighlightsOnlyNumbersAlreadyHeard() {
        val empty = CountTrainingSession(phase = CountTrainingPhase.LISTENING)
        assertEquals(null, JapaneseCountingTestPresentation.fromSession(empty).highlightedIndex)
        val live = JapaneseCountingTestPresentation.fromSession(empty.copy(
            partialTranscripts = listOf("ichi ni san"),
        ))
        assertEquals(3, live.recognizedCount)
        assertEquals(2, live.highlightedIndex)
        val result = JapaneseCountingTestPresentation.fromSession(empty.copy(
            phase = CountTrainingPhase.RESULT,
            partialTranscripts = listOf("ichi ni san"),
            normalizedSequence = listOf("1", "2"),
        ))
        assertEquals(2, result.recognizedCount)
    }
    @Test fun readyAndIdleSessionsRenderTheReadyShell() {
        assertEquals(
            ActivityShellState.READY,
            JapaneseCountingTestPresentation.fromSession(CountTrainingSession()).shellState,
        )
        assertEquals(
            ActivityShellState.READY,
            JapaneseCountingTestPresentation.fromSession(
                CountTrainingSession(phase = CountTrainingPhase.READY),
            ).shellState,
        )
    }

    @Test fun listeningAndFinalizingRemainActiveButOnlyListeningCanStop() {
        val listening = JapaneseCountingTestPresentation.fromSession(
            CountTrainingSession(phase = CountTrainingPhase.LISTENING),
        )
        val finalizing = JapaneseCountingTestPresentation.fromSession(
            CountTrainingSession(phase = CountTrainingPhase.FINALIZING),
        )

        assertEquals(ActivityShellState.ACTIVE, listening.shellState)
        assertTrue(listening.isListening)
        assertEquals(ActivityShellState.ACTIVE, finalizing.shellState)
        assertTrue(finalizing.isFinalizing)
        assertFalse(finalizing.isListening)
    }

    @Test fun resultAndErrorHaveDedicatedShellStates() {
        val successful = JapaneseCountingTestPresentation.fromSession(
            CountTrainingSession(phase = CountTrainingPhase.RESULT, successful = true),
        )
        val error = JapaneseCountingTestPresentation.fromSession(
            CountTrainingSession(phase = CountTrainingPhase.ERROR),
        )

        assertEquals(ActivityShellState.RESULT, successful.shellState)
        assertTrue(successful.isSuccessful)
        assertEquals(ActivityShellState.ERROR, error.shellState)
    }

    @Test fun recognizedProgressIsBoundedByExpectedCount() {
        val presentation = JapaneseCountingTestPresentation.fromSession(
            CountTrainingSession(
                phase = CountTrainingPhase.LISTENING,
                normalizedSequence = List(14) { "1" },
            ),
        )

        assertEquals(10, presentation.recognizedCount)
    }
}
