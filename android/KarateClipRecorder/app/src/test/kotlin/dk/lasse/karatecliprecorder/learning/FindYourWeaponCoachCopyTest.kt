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
import kotlin.test.assertTrue

class FindYourWeaponCoachCopyTest {
    @Test fun everyStepHasRequirementText() {
        FindYourWeaponStep.entries.forEach { step ->
            assertTrue(FindYourWeaponCoachCopy.requirementText(step).isNotBlank())
        }
    }

    @Test fun everyFeedbackCodeHasCorrectionTextForEveryStep() {
        FindYourWeaponStep.entries.forEach { step ->
            FeedbackCode.entries.forEach { feedbackCode ->
                assertTrue(FindYourWeaponCoachCopy.correctionText(step, feedbackCode).isNotBlank())
            }
        }
    }

    @Test fun everyTemporalStatusHasHoldTextForEveryStep() {
        FindYourWeaponStep.entries.forEach { step ->
            TemporalVerificationStatus.entries.forEach { status ->
                assertTrue(FindYourWeaponCoachCopy.temporalText(step, status).isNotBlank())
            }
        }
    }

    @Test fun noHandShowsVisibilityGuidance() {
        val text = FindYourWeaponCoachCopy.messageText(
            step = FindYourWeaponStep.OPEN_PALM,
            state = analysis(
                step = FindYourWeaponStep.OPEN_PALM,
                handDetected = false,
                instantStatus = InstantVerificationStatus.INSUFFICIENT_DATA,
                feedbackCode = FeedbackCode.NONE,
            ),
        )

        assertEquals(
            "I cannot see the hand clearly yet. Move the whole hand into the camera view and keep it steady.",
            text,
        )
    }

    @Test fun nonGoodFeedbackShowsCorrectionText() {
        val text = FindYourWeaponCoachCopy.messageText(
            step = FindYourWeaponStep.CLOSE_FINGERS,
            state = analysis(
                step = FindYourWeaponStep.CLOSE_FINGERS,
                feedbackCode = FeedbackCode.OPEN_THUMB,
            ),
        )

        assertEquals(
            "Keep the thumb outside the line. Do not put the thumb across until the next step.",
            text,
        )
    }

    @Test fun goodFeedbackShowsTemporalProgressText() {
        val text = FindYourWeaponCoachCopy.messageText(
            step = FindYourWeaponStep.THUMB_ON_TOP,
            state = analysis(
                step = FindYourWeaponStep.THUMB_ON_TOP,
                feedbackCode = FeedbackCode.GOOD,
                temporalStatus = TemporalVerificationStatus.BUILDING_PROGRESS,
            ),
        )

        assertEquals("Good. Keep holding this shape.", text)
    }

    @Test fun acceptedFinalStepShowsWeaponText() {
        val text = FindYourWeaponCoachCopy.messageText(
            step = FindYourWeaponStep.FRONT_TWO_KNUCKLES,
            state = analysis(
                step = FindYourWeaponStep.FRONT_TWO_KNUCKLES,
                feedbackCode = FeedbackCode.GOOD,
                temporalStatus = TemporalVerificationStatus.ACCEPTED,
                accepted = true,
            ),
            finalAccepted = true,
        )

        assertEquals("This is your weapon. Hit with these two knuckles.", text)
    }

    @Test fun missingCurrentAnalyzerResultShowsStepRequirement() {
        val text = FindYourWeaponCoachCopy.messageText(
            step = FindYourWeaponStep.BEND_FINGERTIPS,
            state = null,
        )

        assertEquals(
            "Now bend only the top parts of your fingers. Keep your thumb outside and away from the fist.",
            text,
        )
    }

    private fun analysis(
        step: FindYourWeaponStep,
        handDetected: Boolean = true,
        instantStatus: InstantVerificationStatus = InstantVerificationStatus.PARTIAL_MATCH,
        feedbackCode: FeedbackCode,
        temporalStatus: TemporalVerificationStatus = TemporalVerificationStatus.WAITING_FOR_DATA,
        accepted: Boolean = false,
    ) = FindYourWeaponAnalysisState(
        activeStep = step,
        timestampMs = 100,
        handDetected = handDetected,
        handedness = Handedness.RIGHT,
        instantResult = instant(step, instantStatus, feedbackCode),
        temporalResult = temporal(step, temporalStatus, accepted, feedbackCode),
        openPalmGestureScore = null,
        closedFistGestureScore = null,
        inferenceLatencyMs = null,
        recognizerState = RecognizerLifecycleState.READY,
    )

    private fun instant(
        step: FindYourWeaponStep,
        status: InstantVerificationStatus,
        feedbackCode: FeedbackCode,
    ) = InstantStepResult(
        step = step.toLessonStep(),
        status = status,
        score = if (feedbackCode == FeedbackCode.GOOD) 1f else 0.5f,
        quality = 1f,
        feedbackCode = feedbackCode,
        criticalLandmarksVisible = true,
    )

    private fun temporal(
        step: FindYourWeaponStep,
        status: TemporalVerificationStatus,
        accepted: Boolean,
        feedbackCode: FeedbackCode,
    ) = TemporalStepResult(
        status = status,
        latestInstantResult = instant(step, InstantVerificationStatus.MATCHING, feedbackCode),
        accepted = accepted,
        newlyAccepted = accepted,
        progress = if (accepted) 1f else 0.5f,
        accumulatedMatchingMs = if (accepted) 700.0 else 300.0,
        reliableMatchingMs = if (accepted) 700.0 else 300.0,
        reliableHoldCreditMs = if (accepted) 700.0 else 300.0,
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
