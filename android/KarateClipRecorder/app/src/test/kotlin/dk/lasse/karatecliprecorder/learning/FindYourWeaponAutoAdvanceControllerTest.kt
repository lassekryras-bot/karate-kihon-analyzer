package dk.lasse.karatecliprecorder.learning

import dk.lasse.karateanalyzer.core.FeedbackCode
import dk.lasse.karateanalyzer.core.HandLessonStep
import dk.lasse.karateanalyzer.core.Handedness
import dk.lasse.karateanalyzer.core.InstantStepResult
import dk.lasse.karateanalyzer.core.InstantVerificationStatus
import dk.lasse.karateanalyzer.core.TemporalStepResult
import dk.lasse.karateanalyzer.core.TemporalVerificationStatus
import dk.lasse.karatecliprecorder.mediapipehandadapter.RecognizerLifecycleState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FindYourWeaponAutoAdvanceControllerTest {
    @Test fun acceptedCurrentStepSchedulesOnce() {
        val controller = FindYourWeaponAutoAdvanceController()
        val first = controller.onAnalysis(analysis(accepted = true, newlyAccepted = true), FindYourWeaponStep.OPEN_PALM, isSessionActive = true)
        val duplicate = controller.onAnalysis(analysis(accepted = true, newlyAccepted = true), FindYourWeaponStep.OPEN_PALM, isSessionActive = true)

        val schedule = assertIs<FindYourWeaponAutoAdvanceDecision.Schedule>(first)
        assertEquals(FindYourWeaponStep.OPEN_PALM, schedule.step)
        assertEquals(FindYourWeaponAutoAdvanceController.DEFAULT_DELAY_MS, schedule.delayMs)
        assertEquals(FindYourWeaponAutoAdvanceDecision.None, duplicate)
    }

    @Test fun alreadyAcceptedCurrentStepSchedulesWhenNewlyAcceptedPulseWasMissed() {
        val controller = FindYourWeaponAutoAdvanceController()

        val decision = controller.onAnalysis(
            analysis(accepted = true, newlyAccepted = false),
            FindYourWeaponStep.OPEN_PALM,
            isSessionActive = true,
        )

        assertIs<FindYourWeaponAutoAdvanceDecision.Schedule>(decision)
    }

    @Test fun acceptedInactiveOrWrongStepDoesNotSchedule() {
        val controller = FindYourWeaponAutoAdvanceController()
        assertEquals(
            FindYourWeaponAutoAdvanceDecision.None,
            controller.onAnalysis(analysis(accepted = true, newlyAccepted = true), FindYourWeaponStep.BEND_FINGERTIPS, isSessionActive = true),
        )
        assertEquals(
            FindYourWeaponAutoAdvanceDecision.None,
            controller.onAnalysis(analysis(accepted = true, newlyAccepted = true), FindYourWeaponStep.OPEN_PALM, isSessionActive = false),
        )
        assertEquals(
            FindYourWeaponAutoAdvanceDecision.None,
            controller.onAnalysis(analysis(accepted = false, newlyAccepted = false), FindYourWeaponStep.OPEN_PALM, isSessionActive = true),
        )
    }

    @Test fun finalStepAcceptanceDoesNotAutoAdvance() {
        val controller = FindYourWeaponAutoAdvanceController()

        val decision = controller.onAnalysis(
            analysis(step = FindYourWeaponStep.FRONT_TWO_KNUCKLES, accepted = true, newlyAccepted = true),
            FindYourWeaponStep.FRONT_TWO_KNUCKLES,
            isSessionActive = true,
        )

        assertEquals(FindYourWeaponAutoAdvanceDecision.None, decision)
    }

    @Test fun pendingAdvanceCanOnlyBeConsumedForSameActiveStep() {
        val controller = FindYourWeaponAutoAdvanceController()
        controller.onAnalysis(analysis(accepted = true, newlyAccepted = true), FindYourWeaponStep.OPEN_PALM, isSessionActive = true)

        assertFalse(controller.consumePendingAdvance(FindYourWeaponStep.BEND_FINGERTIPS, isSessionActive = true))
        assertFalse(controller.consumePendingAdvance(FindYourWeaponStep.OPEN_PALM, isSessionActive = false))
        assertTrue(controller.consumePendingAdvance(FindYourWeaponStep.OPEN_PALM, isSessionActive = true))
        assertFalse(controller.consumePendingAdvance(FindYourWeaponStep.OPEN_PALM, isSessionActive = true))
    }

    @Test fun manualResetCancelsPendingAndAllowsLaterAcceptance() {
        val controller = FindYourWeaponAutoAdvanceController()
        controller.onAnalysis(analysis(accepted = true, newlyAccepted = true), FindYourWeaponStep.OPEN_PALM, isSessionActive = true)
        controller.reset()

        assertFalse(controller.consumePendingAdvance(FindYourWeaponStep.OPEN_PALM, isSessionActive = true))
        assertIs<FindYourWeaponAutoAdvanceDecision.Schedule>(
            controller.onAnalysis(analysis(accepted = true, newlyAccepted = true), FindYourWeaponStep.OPEN_PALM, isSessionActive = true),
        )
    }

    @Test fun stepChangeCancelsPendingAndClearsDuplicateGuard() {
        val controller = FindYourWeaponAutoAdvanceController()
        controller.onAnalysis(analysis(accepted = true, newlyAccepted = true), FindYourWeaponStep.OPEN_PALM, isSessionActive = true)
        controller.onStepChanged(FindYourWeaponStep.BEND_FINGERTIPS)

        assertFalse(controller.consumePendingAdvance(FindYourWeaponStep.OPEN_PALM, isSessionActive = true))
        assertIs<FindYourWeaponAutoAdvanceDecision.Schedule>(
            controller.onAnalysis(
                analysis(step = FindYourWeaponStep.BEND_FINGERTIPS, accepted = true, newlyAccepted = true),
                FindYourWeaponStep.BEND_FINGERTIPS,
                isSessionActive = true,
            ),
        )
    }

    private fun analysis(
        step: FindYourWeaponStep = FindYourWeaponStep.OPEN_PALM,
        accepted: Boolean,
        newlyAccepted: Boolean,
    ) = FindYourWeaponAnalysisState(
        activeStep = step,
        timestampMs = 100,
        handDetected = true,
        handedness = Handedness.RIGHT,
        instantResult = instant(step),
        temporalResult = temporal(step, accepted, newlyAccepted),
        openPalmGestureScore = null,
        closedFistGestureScore = null,
        inferenceLatencyMs = null,
        recognizerState = RecognizerLifecycleState.READY,
    )

    private fun instant(step: FindYourWeaponStep) = InstantStepResult(
        step = step.toLessonStep(),
        status = InstantVerificationStatus.MATCHING,
        score = 1f,
        quality = 1f,
        feedbackCode = FeedbackCode.GOOD,
        criticalLandmarksVisible = true,
    )

    private fun temporal(step: FindYourWeaponStep, accepted: Boolean, newlyAccepted: Boolean) = TemporalStepResult(
        status = TemporalVerificationStatus.ACCEPTED,
        latestInstantResult = instant(step),
        accepted = accepted,
        newlyAccepted = newlyAccepted,
        progress = if (accepted) 1f else 0f,
        accumulatedMatchingMs = 700.0,
        reliableMatchingMs = 700.0,
        reliableHoldCreditMs = 700.0,
        weightedReliableRatio = 1.0,
    )

    private fun FindYourWeaponStep.toLessonStep(): HandLessonStep = when (this) {
        FindYourWeaponStep.OPEN_PALM -> HandLessonStep.OPEN_PALM
        FindYourWeaponStep.BEND_FINGERTIPS -> HandLessonStep.BEND_FINGERTIPS
        FindYourWeaponStep.CLOSE_FINGERS -> HandLessonStep.CLOSE_FINGERS
        FindYourWeaponStep.THUMB_ON_TOP -> HandLessonStep.THUMB_ON_TOP
        FindYourWeaponStep.FRONT_TWO_KNUCKLES -> HandLessonStep.FRONT_TWO_KNUCKLES
    }
}
