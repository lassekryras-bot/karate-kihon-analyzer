package dk.lasse.karatecliprecorder.learningactivity

import dk.lasse.karatecliprecorder.learning.SpeechRecognitionError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KarateTerminologyPresentationTest {
    @Test fun osuLessonRequiresThreeRecoverablePictureChoicesAndNoResultState() {
        val states = mutableListOf<OsuMeaningUsePresentation>()
        val controller = OsuMeaningUseController(states::add)

        assertEquals(ActivityShellState.READY, controller.presentation.shellState)
        assertFalse(controller.presentation.microphoneRequired)
        assertFalse(controller.presentation.cameraRequired)

        controller.start()
        assertEquals(OsuMeaningUseStage.HEARD_YOU, controller.presentation.stage)
        assertEquals(0, controller.presentation.stepIndex)
        controller.next()
        assertEquals(OsuMeaningUseStage.HEARD_YOU, controller.presentation.stage)

        controller.selectAnswer(OsuIllustration.PIZZA_DISTRACTION)
        assertTrue(controller.presentation.hasSelection)
        assertFalse(controller.presentation.answerIsCorrect)
        assertFalse(controller.presentation.nextEnabled)
        controller.next()
        assertEquals(OsuMeaningUseStage.HEARD_YOU, controller.presentation.stage)

        controller.selectAnswer(OsuIllustration.HEARD_YOU)
        assertTrue(controller.presentation.answerIsCorrect)
        assertTrue(controller.presentation.nextEnabled)
        controller.next()
        assertEquals(OsuMeaningUseStage.READY_RESPONSE, controller.presentation.stage)
        assertEquals(1, controller.presentation.stepIndex)

        controller.selectAnswer(OsuIllustration.READY_GUARD)
        controller.next()
        assertEquals(OsuMeaningUseStage.KEEP_TRYING, controller.presentation.stage)
        assertEquals(2, controller.presentation.stepIndex)
        controller.previous()
        assertEquals(OsuMeaningUseStage.READY_RESPONSE, controller.presentation.stage)
        assertEquals(OsuIllustration.READY_GUARD, controller.presentation.selectedIllustration)
        assertTrue(controller.presentation.answerIsCorrect)

        controller.next()
        controller.selectAnswer(OsuIllustration.HOME_DISTRACTION)
        assertFalse(controller.presentation.answerIsCorrect)
        controller.selectAnswer(OsuIllustration.ONE_MORE)
        assertTrue(controller.presentation.isLastQuestion)
        controller.next()

        assertEquals(ActivityShellState.COMPLETE, controller.presentation.shellState)
        assertTrue(states.none { it.shellState == ActivityShellState.RESULT })
        assertTrue(states.none { it.shellState == ActivityShellState.ERROR })

        controller.restart()
        assertEquals(OsuMeaningUseStage.HEARD_YOU, controller.presentation.stage)
        assertTrue(controller.presentation.selections.isEmpty())
    }

    @Test fun readyOsuAcceptsApprovedVariantsWithoutClaimingPronunciation() {
        var presentation = ReadyOsuPresentation()
        val controller = ReadyOsuController { presentation = it }

        assertTrue(presentation.cameraRequired)
        assertFalse(presentation.cameraActive)
        assertFalse(presentation.microphoneActive)
        controller.start()
        assertFalse(presentation.cameraActive)
        controller.beginCameraPreparation()
        assertTrue(presentation.cameraActive)
        controller.beginPrompt()
        controller.beginListening()
        assertTrue(presentation.microphoneActive)

        controller.beginChecking()
        controller.handleTranscripts(listOf("oss"))
        assertEquals(ReadyOsuPhase.CAPTURING, presentation.state.phase)
        assertTrue(presentation.state.voiceVerified)
        assertFalse(presentation.canFinish)

        controller.selfieCaptured()
        assertEquals(ReadyOsuPhase.RESULT, presentation.state.phase)
        assertEquals(ActivityShellState.RESULT, presentation.shellState)
        assertTrue(presentation.state.selfieCaptured)
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
        assertEquals(ReadyOsuPhase.RESULT, presentation.state.phase)
        assertFalse(presentation.state.voiceVerified)
        assertFalse(presentation.state.selfieCaptured)
        controller.finish()
        assertEquals(ActivityShellState.COMPLETE, presentation.shellState)

        controller.restart()
        controller.permissionFailure(cameraAllowed = false, microphoneAllowed = false)
        assertEquals(ActivityShellState.ERROR, presentation.shellState)
        assertEquals(ReadyOsuCameraError.CAMERA_PERMISSION_DENIED, presentation.state.cameraError)
        controller.continueToResultWithoutSelfie()
        assertEquals(ReadyOsuPhase.RESULT, presentation.state.phase)
        assertNull(presentation.state.error)
    }

    @Test fun selfieCaptureFailureDoesNotEraseRecognizedVoiceEvidence() {
        var presentation = ReadyOsuPresentation()
        val controller = ReadyOsuController { presentation = it }

        controller.beginCameraPreparation()
        controller.beginPrompt()
        controller.beginListening()
        controller.handleTranscripts(listOf("osu"))
        controller.failCamera(ReadyOsuCameraError.CAPTURE_FAILED)

        assertEquals(ActivityShellState.ERROR, presentation.shellState)
        assertTrue(presentation.state.voiceVerified)
        assertFalse(presentation.state.selfieCaptured)
        controller.continueToResultWithoutSelfie()
        assertEquals(ActivityShellState.RESULT, presentation.shellState)
        assertTrue(presentation.state.voiceVerified)

        controller.beginCameraPreparation()
        assertFalse(presentation.state.voiceVerified)
        assertFalse(presentation.state.selfieCaptured)
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
