package dk.lasse.karatecliprecorder.learningactivity

import dk.lasse.karatecliprecorder.learning.SpeechRecognitionError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KarateTerminologyPresentationTest {
    @Test fun osuLessonUsesThreePassiveStepsAndNoResultState() {
        val states = mutableListOf<OsuMeaningUsePresentation>()
        val controller = OsuMeaningUseController(states::add)

        assertEquals(ActivityShellState.READY, controller.presentation.shellState)
        assertFalse(controller.presentation.microphoneRequired)
        assertFalse(controller.presentation.cameraRequired)

        controller.start()
        assertEquals(0, controller.presentation.stepIndex)
        controller.next()
        assertEquals(1, controller.presentation.stepIndex)
        controller.next()
        assertEquals(2, controller.presentation.stepIndex)
        assertEquals("Finish lesson  →", controller.presentation.nextLabel)
        controller.next()

        assertEquals(ActivityShellState.COMPLETE, controller.presentation.shellState)
        assertTrue(states.none { it.shellState == ActivityShellState.RESULT })
        assertTrue(states.none { it.shellState == ActivityShellState.ERROR })
    }

    @Test fun readyOsuAcceptsApprovedVariantsWithoutClaimingPronunciation() {
        var presentation = ReadyOsuPresentation()
        val controller = ReadyOsuController { presentation = it }

        controller.start()
        controller.beginPrompt()
        controller.beginListening()
        assertTrue(presentation.microphoneActive)

        controller.beginChecking()
        controller.handleTranscripts(listOf("oss"))
        assertEquals(ReadyOsuPhase.FEEDBACK_CONFIRMED, presentation.state.phase)
        assertTrue(presentation.state.voiceVerified)
        assertTrue(presentation.canFinish)

        controller.finish()
        assertEquals(ActivityShellState.COMPLETE, presentation.shellState)
        assertTrue(presentation.state.voiceVerified)
    }

    @Test fun readyOsuNoMatchAndPermissionFailureBothHaveNonTrappingRoutes() {
        var presentation = ReadyOsuPresentation()
        val controller = ReadyOsuController { presentation = it }

        controller.start()
        controller.beginPrompt()
        controller.beginListening()
        controller.handleTranscripts(listOf("something else"))
        assertEquals(ReadyOsuPhase.FEEDBACK_UNCONFIRMED, presentation.state.phase)
        assertFalse(presentation.state.voiceVerified)
        controller.finish()
        assertEquals(ActivityShellState.COMPLETE, presentation.shellState)

        controller.restart()
        controller.fail(SpeechRecognitionError.MICROPHONE_PERMISSION_DENIED)
        assertEquals(ActivityShellState.ERROR, presentation.shellState)
        controller.continueWithoutVerification()
        assertEquals(ReadyOsuPhase.FEEDBACK_UNCONFIRMED, presentation.state.phase)
        assertNull(presentation.state.error)
    }

    @Test fun stopCountKeepsCompletionAndVoiceEvidenceSeparate() {
        var presentation = StopCountPresentation()
        val controller = StopCountController { presentation = it }

        controller.start(voiceEnabled = true)
        controller.showNumber(3)
        assertEquals(4, presentation.currentNumber)
        assertTrue(presentation.isCounting)

        controller.stop(StopCountMethod.BUTTON)
        assertEquals(StopCountPhase.STOPPED, presentation.state.phase)
        assertFalse(presentation.voiceVerified)
        controller.finish()
        assertEquals(ActivityShellState.COMPLETE, presentation.shellState)
        assertFalse(presentation.voiceVerified)

        controller.restart()
        controller.start(voiceEnabled = true)
        controller.stop(StopCountMethod.VOICE)
        controller.finish()
        assertTrue(presentation.voiceVerified)
    }

    @Test fun countReachingTenRequiresRetryAndVoiceFailureFallsBackToButton() {
        var presentation = StopCountPresentation()
        val controller = StopCountController { presentation = it }

        controller.start(voiceEnabled = true)
        controller.countFinished()
        assertEquals(StopCountPhase.COUNT_FINISHED, presentation.state.phase)
        assertEquals(ActivityShellState.ACTIVE, presentation.shellState)
        assertFalse(presentation.voiceVerified)

        controller.permissionError(SpeechRecognitionError.MICROPHONE_PERMISSION_DENIED)
        assertEquals(ActivityShellState.ERROR, presentation.shellState)
        controller.continueButtonOnlyAfterError()
        assertEquals(StopCountPhase.COUNTING, presentation.state.phase)
        assertFalse(presentation.state.voiceEnabled)
    }

    @Test fun interruptedVoiceAttemptsReturnThroughRecoverableErrorStates() {
        var ready = ReadyOsuPresentation()
        val readyController = ReadyOsuController { ready = it }
        readyController.start()
        readyController.beginPrompt()
        readyController.beginListening()
        readyController.fail(SpeechRecognitionError.CLIENT, interrupted = true)
        assertEquals(ActivityShellState.ERROR, ready.shellState)
        assertTrue(ready.state.interrupted)

        var stop = StopCountPresentation()
        val stopController = StopCountController { stop = it }
        stopController.start(voiceEnabled = true)
        stopController.interrupt()
        assertEquals(ActivityShellState.ERROR, stop.shellState)
        assertTrue(stop.state.interrupted)
    }
}
