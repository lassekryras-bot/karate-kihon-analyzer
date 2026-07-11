package dk.lasse.karatecliprecorder.learning

import dk.lasse.karateanalyzer.core.HandLessonStep
import dk.lasse.karateanalyzer.core.Handedness
import dk.lasse.karatecliprecorder.learning.FindYourWeaponAnalysisCoordinator.Companion.toHandLessonStep
import dk.lasse.karatecliprecorder.mediapipehandadapter.LiveGestureRecognizerOutput
import dk.lasse.karatecliprecorder.mediapipehandadapter.MediaPipeHandObservation
import dk.lasse.karatecliprecorder.mediapipehandadapter.MediaPipePoint3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FindYourWeaponAnalysisCoordinatorTest {
    @Test fun allStepMappingsAreExplicit() {
        assertEquals(HandLessonStep.OPEN_PALM, FindYourWeaponStep.OPEN_PALM.toHandLessonStep())
        assertEquals(HandLessonStep.BEND_FINGERTIPS, FindYourWeaponStep.BEND_FINGERTIPS.toHandLessonStep())
        assertEquals(HandLessonStep.CLOSE_FINGERS, FindYourWeaponStep.CLOSE_FINGERS.toHandLessonStep())
        assertEquals(HandLessonStep.THUMB_ON_TOP, FindYourWeaponStep.THUMB_ON_TOP.toHandLessonStep())
        assertEquals(HandLessonStep.FRONT_TWO_KNUCKLES, FindYourWeaponStep.FRONT_TWO_KNUCKLES.toHandLessonStep())
    }

    @Test fun emptyObservationsProduceMissingInputAndFiniteOutput() {
        val states = mutableListOf<FindYourWeaponAnalysisState>()
        val coordinator = FindYourWeaponAnalysisCoordinator(states::add)
        coordinator.setActiveStep(FindYourWeaponStep.OPEN_PALM)
        coordinator.process(output(100, emptyList(), coordinator.generationToken))
        val state = states.last()
        assertFalse(state.handDetected)
        assertEquals(Handedness.UNKNOWN, state.handedness)
        assertNotNull(state.instantResult)
        assertNotNull(state.temporalResult)
        assertFinite(state)
    }

    @Test fun missingObservationsRetainPreviousHandedness() {
        val states = mutableListOf<FindYourWeaponAnalysisState>()
        val coordinator = FindYourWeaponAnalysisCoordinator(states::add)
        coordinator.setActiveStep(FindYourWeaponStep.OPEN_PALM)
        coordinator.process(output(100, listOf(hand("Right")), coordinator.generationToken))
        coordinator.process(output(200, emptyList(), coordinator.generationToken))
        assertEquals(Handedness.RIGHT, states.last().handedness)
    }

    @Test fun duplicateOldAndStaleGenerationOutputsAreIgnored() {
        val states = mutableListOf<FindYourWeaponAnalysisState>()
        val coordinator = FindYourWeaponAnalysisCoordinator(states::add)
        coordinator.setActiveStep(FindYourWeaponStep.OPEN_PALM)
        val token = coordinator.generationToken
        coordinator.process(output(100, emptyList(), token))
        coordinator.process(output(100, emptyList(), token))
        coordinator.process(output(90, emptyList(), token))
        coordinator.setActiveStep(FindYourWeaponStep.CLOSE_FINGERS)
        coordinator.process(output(200, emptyList(), token))
        assertEquals(1, states.count { it.timestampMs != null })
    }

    @Test fun resetClearsStateAndAdvancesGeneration() {
        val states = mutableListOf<FindYourWeaponAnalysisState>()
        val coordinator = FindYourWeaponAnalysisCoordinator(states::add)
        coordinator.setActiveStep(FindYourWeaponStep.OPEN_PALM)
        val token = coordinator.generationToken
        coordinator.process(output(100, listOf(hand("Right")), token))
        coordinator.reset()
        assertNull(states.last().activeStep)
        assertEquals(Handedness.UNKNOWN, states.last().handedness)
        assertTrue(coordinator.generationToken > token)
    }

    @Test fun highCannedGestureScoresCannotCreateAcceptanceWithoutGeometry() {
        val states = mutableListOf<FindYourWeaponAnalysisState>()
        val coordinator = FindYourWeaponAnalysisCoordinator(states::add)
        coordinator.setActiveStep(FindYourWeaponStep.OPEN_PALM)
        coordinator.process(output(100, listOf(hand("Right", openPalm = 1f, closedFist = 1f)), coordinator.generationToken))
        assertFalse(states.last().temporalResult?.accepted ?: true)
    }

    private fun output(timestamp: Long, observations: List<MediaPipeHandObservation>, token: Long) = LiveGestureRecognizerOutput(
        timestampMs = timestamp,
        observations = observations,
        inputWidth = 640,
        inputHeight = 480,
        inferenceLatencyMs = 1,
        generationToken = token,
    )

    private fun hand(label: String, openPalm: Float? = null, closedFist: Float? = null) = MediaPipeHandObservation(
        normalizedLandmarks = List(21) { index -> MediaPipePoint3(index / 100f, index / 100f, 0f, presence = 1f) },
        handednessLabel = label,
        handednessScore = 0.9f,
        openPalmScore = openPalm,
        closedFistScore = closedFist,
        timestampMs = 0,
    )

    private fun assertFinite(state: FindYourWeaponAnalysisState) {
        state.instantResult?.let {
            assertTrue(it.score.isFinite() && it.score in 0f..1f)
            assertTrue(it.quality.isFinite() && it.quality in 0f..1f)
        }
        state.temporalResult?.let {
            assertTrue(it.progress.isFinite() && it.progress in 0f..1f)
            assertTrue(it.accumulatedMatchingMs.isFinite())
            assertTrue(it.reliableMatchingMs.isFinite())
            assertTrue(it.reliableHoldCreditMs.isFinite())
            assertTrue(it.weightedReliableRatio.isFinite())
        }
    }
}
