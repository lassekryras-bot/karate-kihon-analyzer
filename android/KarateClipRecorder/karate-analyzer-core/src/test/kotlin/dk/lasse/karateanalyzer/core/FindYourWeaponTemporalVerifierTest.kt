package dk.lasse.karateanalyzer.core

import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNotEquals
import kotlin.test.assertFailsWith

class FindYourWeaponTemporalVerifierTest {
    private val instantVerifier = FindYourWeaponVerifier()
    private val extractor = HandFeatureExtractor()

    @Test fun observedSixHundredMsAccepts() {
        val result = runOpenPalm(LandmarkSource.OBSERVED, listOf(0L, 200L, 400L, 600L)).last()
        assertTrue(result.accepted)
        assertTrue(result.newlyAccepted)
        assertEquals(600.0, result.reliableHoldCreditMs, 0.001)
    }

    @Test fun interpolatedSixHundredMsDoesNotAccept() {
        val result = runOpenPalm(LandmarkSource.INTERPOLATED, listOf(0L, 200L, 400L, 600L)).last()
        assertFalse(result.accepted)
        assertEquals(450.0, result.reliableHoldCreditMs, 0.001)
    }

    @Test fun interpolatedEightHundredMsMayAccept() {
        val result = runOpenPalm(LandmarkSource.INTERPOLATED, listOf(0L, 200L, 400L, 600L, 800L)).last()
        assertTrue(result.accepted)
        assertEquals(600.0, result.reliableHoldCreditMs, 0.001)
        assertEquals(0.75, result.weightedReliableRatio, 0.001)
    }

    @Test fun observedAcceptanceIsEquivalentAtCommonFrameRates() {
        for (fps in listOf(15, 30, 60)) {
            val timestamps = (0..fps).map { it * 600L / fps }
            val result = runOpenPalm(LandmarkSource.OBSERVED, timestamps).last()
            assertTrue(result.accepted, "Expected acceptance at $fps FPS")
            assertEquals(600.0, result.reliableHoldCreditMs, 0.001)
        }
    }

    @Test fun exerciseStepsRequireTwoSecondHold() {
        val cases = listOf(
            HandLessonStep.BEND_FINGERTIPS to { timestampMs: Long -> hand(timestampMs, curl = 125f) },
            HandLessonStep.CLOSE_FINGERS to { timestampMs: Long -> hand(timestampMs, curl = 70f) },
            HandLessonStep.THUMB_ON_TOP to { timestampMs: Long -> hand(timestampMs, curl = 70f, thumbTipX = -0.55f) },
        )

        for ((step, frameAt) in cases) {
            val verifier = FindYourWeaponTemporalVerifier()
            val before = runStep(verifier, step, listOf(0L, 500L, 1000L, 1500L).map(frameAt)).last()
            assertFalse(before.accepted, "Expected $step to keep holding before 2 seconds")
            assertEquals(0.75f, before.progress, 0.001f)

            val accepted = update(verifier, step, frameAt(2000L))
            assertTrue(accepted.accepted, "Expected $step to accept after 2 seconds")
            assertEquals(1f, accepted.progress)
        }
    }

    @Test fun finalKnuckleStepRequiresOneSecondHold() {
        val verifier = FindYourWeaponTemporalVerifier()
        val matching = matching(HandLessonStep.FRONT_TWO_KNUCKLES, quality = 0.9f)
        val before = listOf(0L, 250L, 500L, 750L)
            .map { timestamp -> verifier.update(hand(timestamp, curl = 70f, thumbCrossing = true), matching) }
            .last()
        assertFalse(before.accepted)
        assertEquals(0.75f, before.progress, 0.001f)

        val accepted = verifier.update(hand(1000L, curl = 70f, thumbCrossing = true), matching)
        assertTrue(accepted.accepted)
        assertEquals(1f, accepted.progress)
    }


    @Test fun twoFramesSixHundredMsApartDoNotAcceptWithDefaultFrameGap() {
        val result = runOpenPalm(LandmarkSource.OBSERVED, listOf(0L, 600L)).last()
        assertFalse(result.accepted)
        assertEquals(0.0, result.reliableHoldCreditMs, 0.001)
        assertEquals(TemporalVerificationStatus.WAITING_FOR_DATA, result.status)
    }

    @Test fun lowQualityMatchingDoesNotCreateReliableCreditOrAcceptance() {
        val verifier = FindYourWeaponTemporalVerifier()
        val frames = listOf(hand(0), hand(200), hand(400), hand(600))
        val results = frames.map { frame -> verifier.update(frame, matching(HandLessonStep.OPEN_PALM, quality = 0.1f)) }
        val result = results.last()
        assertFalse(result.accepted)
        assertEquals(600.0, result.accumulatedMatchingMs, 0.001)
        assertEquals(0.0, result.reliableMatchingMs, 0.001)
        assertEquals(0.0, result.reliableHoldCreditMs, 0.001)
    }

    @Test fun matchingGoodAtInstantMatchingQualityBuildsTemporalProgress() {
        val verifier = FindYourWeaponTemporalVerifier()
        verifier.update(hand(0), matching(HandLessonStep.OPEN_PALM, quality = 0.56f))
        val result = verifier.update(hand(200), matching(HandLessonStep.OPEN_PALM, quality = 0.56f))

        assertEquals(TemporalVerificationStatus.BUILDING_PROGRESS, result.status)
        assertTrue(result.progress > 0f)
        assertEquals(200.0, result.reliableHoldCreditMs, 0.001)
    }

    @Test fun slowerLiveRecognizerGapsStillBuildProgress() {
        val verifier = FindYourWeaponTemporalVerifier()
        verifier.update(hand(0), matching(HandLessonStep.OPEN_PALM, quality = 0.9f))
        val second = verifier.update(hand(400), matching(HandLessonStep.OPEN_PALM, quality = 0.9f))
        val third = verifier.update(hand(800), matching(HandLessonStep.OPEN_PALM, quality = 0.9f))

        assertEquals(TemporalVerificationStatus.BUILDING_PROGRESS, second.status)
        assertTrue(second.progress > 0f)
        assertTrue(third.accepted)
        assertEquals(1f, third.progress)
    }

    @Test fun lowQualityMatchingFollowedByOneHighQualityFrameCannotAccept() {
        val verifier = FindYourWeaponTemporalVerifier()
        for (frame in listOf(hand(0), hand(200), hand(400), hand(600))) {
            verifier.update(frame, matching(HandLessonStep.OPEN_PALM, quality = 0.1f))
        }
        val highQuality = hand(800)
        val result = verifier.update(highQuality, instant(HandLessonStep.OPEN_PALM, highQuality))
        assertFalse(result.accepted)
        assertEquals(0.0, result.reliableMatchingMs, 0.001)
        assertEquals(0.0, result.reliableHoldCreditMs, 0.001)
    }



    @Test fun lowQualityGoodMatchingAtBeginningReportsPaused() {
        val verifier = FindYourWeaponTemporalVerifier()
        val result = verifier.update(hand(0), matching(HandLessonStep.OPEN_PALM, quality = 0.1f))
        assertEquals(TemporalVerificationStatus.PAUSED, result.status)
        assertFalse(result.accepted)
        assertEquals(0.0, result.reliableHoldCreditMs, 0.001)
    }

    @Test fun predictedGoodMatchingAtBeginningReportsPaused() {
        val verifier = FindYourWeaponTemporalVerifier()
        val frame = hand(0, predicted = setOf(HandLandmarkId.INDEX_PIP))
        val result = verifier.update(frame, matching(HandLessonStep.OPEN_PALM, quality = 0.9f))
        assertEquals(TemporalVerificationStatus.PAUSED, result.status)
        assertFalse(result.accepted)
        assertEquals(0.0, result.reliableHoldCreditMs, 0.001)
    }

    @Test fun firstReliableFrameWaitsThenNextReliableIntervalBuildsProgress() {
        val verifier = FindYourWeaponTemporalVerifier()
        val first = update(verifier, HandLessonStep.OPEN_PALM, hand(0))
        val second = update(verifier, HandLessonStep.OPEN_PALM, hand(200))
        assertEquals(TemporalVerificationStatus.WAITING_FOR_DATA, first.status)
        assertEquals(TemporalVerificationStatus.BUILDING_PROGRESS, second.status)
    }

    @Test fun predictedFollowedByOneObservedFrameAddsNoReliableInterval() {
        val verifier = FindYourWeaponTemporalVerifier()
        verifier.update(hand(0, predicted = setOf(HandLandmarkId.INDEX_PIP)), matching(HandLessonStep.OPEN_PALM, quality = 0.9f))
        val result = verifier.update(hand(200), instant(HandLessonStep.OPEN_PALM, hand(200)))
        assertFalse(result.accepted)
        assertEquals(0.0, result.reliableMatchingMs, 0.001)
        assertEquals(0.0, result.reliableHoldCreditMs, 0.001)
    }

    @Test fun twoReliableObservedEndpointsAddElapsedTime() {
        val result = runOpenPalm(LandmarkSource.OBSERVED, listOf(0L, 200L)).last()
        assertEquals(200.0, result.reliableMatchingMs, 0.001)
        assertEquals(200.0, result.reliableHoldCreditMs, 0.001)
    }

    @Test fun twoInterpolatedEndpointsApplyDeterministicWeight() {
        val result = runOpenPalm(LandmarkSource.INTERPOLATED, listOf(0L, 200L)).last()
        assertEquals(150.0, result.reliableMatchingMs, 0.001)
        assertEquals(150.0, result.reliableHoldCreditMs, 0.001)
        assertEquals(0.75, result.weightedReliableRatio, 0.001)
    }

    @Test fun fullyDecayedCreditResetsActiveAttemptRatioForNextAttempt() {
        val verifier = FindYourWeaponTemporalVerifier(FindYourWeaponTemporalConfiguration(progressDecayPerSecond = 10.0))
        runStep(verifier, HandLessonStep.OPEN_PALM, listOf(hand(0), hand(200)))
        val decayed = verifier.update(missingHand(400, Handedness.RIGHT), insufficient(HandLessonStep.OPEN_PALM))
        assertEquals(0.0, decayed.reliableHoldCreditMs, 0.001)
        assertEquals(0.0, decayed.weightedReliableRatio, 0.001)
    }

    @Test fun cleanAttemptSucceedsAfterPoisonedAttemptFullyDecays() {
        val verifier = FindYourWeaponTemporalVerifier(FindYourWeaponTemporalConfiguration(progressDecayPerSecond = 10.0))
        for (frame in listOf(hand(0), hand(200), hand(400), hand(600))) {
            verifier.update(frame, matching(HandLessonStep.OPEN_PALM, quality = 0.1f))
        }
        verifier.update(missingHand(800, Handedness.RIGHT), insufficient(HandLessonStep.OPEN_PALM))
        val result = runStep(verifier, HandLessonStep.OPEN_PALM, listOf(hand(1000), hand(1200), hand(1400), hand(1600))).last()
        assertTrue(result.accepted)
    }

    @Test fun nanAndInfinityPartialScoresProduceFiniteProgress() {
        val verifier = FindYourWeaponTemporalVerifier()
        val nan = verifier.update(hand(0), partial(HandLessonStep.OPEN_PALM, Float.NaN))
        val infinity = verifier.update(hand(50), partial(HandLessonStep.OPEN_PALM, Float.POSITIVE_INFINITY))
        listOf(nan, infinity).forEach { result ->
            assertTrue(result.progress.isFinite())
            assertTrue(result.progress in 0f..1f)
            assertTrue(result.weightedReliableRatio.isFinite())
            assertTrue(result.weightedReliableRatio in 0.0..1.0)
        }
    }

    @Test fun unreliableMatchingWithExistingCreditReportsPaused() {
        val verifier = FindYourWeaponTemporalVerifier()
        runStep(verifier, HandLessonStep.OPEN_PALM, listOf(hand(0), hand(200)))
        val result = verifier.update(hand(400), matching(HandLessonStep.OPEN_PALM, quality = 0.1f))
        assertEquals(TemporalVerificationStatus.PAUSED, result.status)
        assertEquals(200.0, result.reliableHoldCreditMs, 0.001)
    }

    @Test fun temporalStatusesTransitionAsExpected() {
        val verifier = FindYourWeaponTemporalVerifier()
        assertEquals(TemporalVerificationStatus.WAITING_FOR_DATA, update(verifier, HandLessonStep.OPEN_PALM, hand(0)).status)
        assertEquals(TemporalVerificationStatus.BUILDING_PROGRESS, update(verifier, HandLessonStep.OPEN_PALM, hand(200)).status)
        assertEquals(TemporalVerificationStatus.PAUSED, verifier.update(missingHand(260, Handedness.RIGHT), insufficient(HandLessonStep.OPEN_PALM)).status)
        assertEquals(TemporalVerificationStatus.LOSING_PROGRESS, verifier.update(missingHand(420, Handedness.RIGHT), insufficient(HandLessonStep.OPEN_PALM)).status)

        val holding = FindYourWeaponTemporalVerifier(
            FindYourWeaponTemporalConfiguration(requiredHoldDurationMs = 300, minimumReliableMatchingRatio = 0.9),
        )
        val holdingResult = runStep(holding, HandLessonStep.OPEN_PALM, listOf(hand(0, source = LandmarkSource.INTERPOLATED), hand(200, source = LandmarkSource.INTERPOLATED), hand(400, source = LandmarkSource.INTERPOLATED))).last()
        assertFalse(holdingResult.accepted)
        assertEquals(1f, holdingResult.progress)
        assertEquals(TemporalVerificationStatus.HOLDING, holdingResult.status)

        val accepted = runOpenPalm(LandmarkSource.OBSERVED, listOf(0L, 200L, 400L, 600L)).last()
        assertEquals(TemporalVerificationStatus.ACCEPTED, accepted.status)
    }

    @Test fun partialFramesCannotIncreaseReliableMatchingMs() {
        val verifier = FindYourWeaponTemporalVerifier()
        val partialFrame = hand(0, curl = 125f)
        val partial = verifier.update(partialFrame, partial(HandLessonStep.OPEN_PALM))
        assertFalse(partial.accepted)
        assertTrue(partial.progress > 0f)
        assertEquals(0.0, partial.reliableMatchingMs, 0.001)
        val laterPartialFrame = hand(200, curl = 125f)
        val laterPartial = verifier.update(laterPartialFrame, partial(HandLessonStep.OPEN_PALM))
        assertEquals(0.0, laterPartial.reliableMatchingMs, 0.001)
        assertEquals(0.0, laterPartial.reliableHoldCreditMs, 0.001)
    }

    @Test fun partialProgressFollowedByOneMatchingFrameCannotAccept() {
        val verifier = FindYourWeaponTemporalVerifier()
        val partialFrame = hand(0, curl = 125f)
        verifier.update(partialFrame, partial(HandLessonStep.OPEN_PALM))
        val matchingFrame = hand(200)
        val result = verifier.update(matchingFrame, instant(HandLessonStep.OPEN_PALM, matchingFrame))
        assertFalse(result.accepted)
        assertEquals(0.0, result.reliableMatchingMs, 0.001)
    }

    @Test fun zeroDecayStillCannotTurnPartialCreditIntoReliableTime() {
        val verifier = FindYourWeaponTemporalVerifier(
            FindYourWeaponTemporalConfiguration(progressDecayPerSecond = 0.0),
        )
        for (frame in listOf(hand(0, curl = 125f), hand(200, curl = 125f))) {
            verifier.update(frame, partial(HandLessonStep.OPEN_PALM))
        }
        val matchingFrame = hand(400)
        val result = verifier.update(matchingFrame, instant(HandLessonStep.OPEN_PALM, matchingFrame))
        assertFalse(result.accepted)
        assertEquals(0.0, result.reliableMatchingMs, 0.001)
        assertEquals(0.0, result.reliableHoldCreditMs, 0.001)
    }

    @Test fun backwardsTimestampsAndExcessiveFrameGapsResetSafely() {
        val backwards = FindYourWeaponTemporalVerifier()
        runStep(backwards, HandLessonStep.OPEN_PALM, listOf(hand(0), hand(150), hand(300)))
        val afterBackwards = update(backwards, HandLessonStep.OPEN_PALM, hand(200))
        assertFalse(afterBackwards.accepted)
        assertEquals(0.0, afterBackwards.reliableHoldCreditMs, 0.001)

        val gap = FindYourWeaponTemporalVerifier()
        runStep(gap, HandLessonStep.OPEN_PALM, listOf(hand(0), hand(150), hand(300)))
        val afterGap = update(gap, HandLessonStep.OPEN_PALM, hand(2000))
        assertFalse(afterGap.accepted)
        assertEquals(0.0, afterGap.reliableHoldCreditMs, 0.001)
    }

    @Test fun resetAndResetForStepClearAcceptance() {
        val verifier = FindYourWeaponTemporalVerifier()
        runStep(verifier, HandLessonStep.OPEN_PALM, listOf(hand(0), hand(200), hand(400), hand(600)))
        verifier.reset()
        val afterReset = update(verifier, HandLessonStep.OPEN_PALM, hand(1200))
        assertFalse(afterReset.accepted)

        runStep(verifier, HandLessonStep.OPEN_PALM, listOf(hand(1800), hand(2000), hand(2200), hand(2400)))
        verifier.resetForStep(HandLessonStep.CLOSE_FINGERS)
        val afterStepReset = update(verifier, HandLessonStep.CLOSE_FINGERS, hand(3000, curl = 70f))
        assertFalse(afterStepReset.accepted)
    }


    @Test fun noHandMissingInputPreservesProgressDuringGraceAndDoesNotResetHand() {
        val verifier = FindYourWeaponTemporalVerifier()
        runStep(verifier, HandLessonStep.OPEN_PALM, listOf(hand(0), hand(150), hand(300)))
        val progressBeforeMissing = update(verifier, HandLessonStep.OPEN_PALM, hand(300)).progress
        val missingFrame = missingHand(360, Handedness.RIGHT)
        val missing = verifier.update(missingFrame, insufficient(HandLessonStep.OPEN_PALM))
        assertEquals(progressBeforeMissing, missing.progress)
        assertEquals(300.0, missing.reliableHoldCreditMs, 0.001)

        val matchingAfterMissing = update(verifier, HandLessonStep.OPEN_PALM, hand(420))
        assertFalse(matchingAfterMissing.accepted)
        assertEquals(300.0, matchingAfterMissing.reliableHoldCreditMs, 0.001)
    }

    @Test fun decayAfterGraceIsFpsIndependent() {
        val remainingCredits = listOf(15, 30, 60).map { fps ->
            val verifier = FindYourWeaponTemporalVerifier()
            runStep(verifier, HandLessonStep.OPEN_PALM, listOf(hand(0), hand(150), hand(300)))
            val interruptionStartMs = 300L
            val interruptionDurationMs = 240L
            val intervals = maxOf(1, (fps * interruptionDurationMs / 1000.0).toInt())
            for (i in 0..intervals) {
                val timestamp = interruptionStartMs + i * interruptionDurationMs / intervals
                verifier.update(missingHand(timestamp, Handedness.RIGHT), insufficient(HandLessonStep.OPEN_PALM))
            }
            verifier.update(missingHand(interruptionStartMs + interruptionDurationMs, Handedness.RIGHT), insufficient(HandLessonStep.OPEN_PALM)).reliableHoldCreditMs
        }
        remainingCredits.forEach { remaining ->
            assertEquals(228.0, remaining, 0.001)
        }
    }

    @Test fun invalidConfigurationIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            FindYourWeaponTemporalVerifier(FindYourWeaponTemporalConfiguration(requiredHoldDurationMs = 0))
        }
        assertFailsWith<IllegalArgumentException> {
            FindYourWeaponTemporalVerifier(FindYourWeaponTemporalConfiguration(exerciseHoldDurationMs = 0))
        }
        assertFailsWith<IllegalArgumentException> {
            FindYourWeaponTemporalVerifier(FindYourWeaponTemporalConfiguration(finalKnuckleHoldDurationMs = 0))
        }
        assertFailsWith<IllegalArgumentException> {
            FindYourWeaponTemporalVerifier(FindYourWeaponTemporalConfiguration(minimumReliableMatchingRatio = Double.NaN))
        }
        assertFailsWith<IllegalArgumentException> {
            FindYourWeaponTemporalVerifier(FindYourWeaponTemporalConfiguration(partialDisplayCreditRatio = Float.NaN))
        }
    }

    @Test fun missingThumbMcpOrIpDoesNotMakeInstantThumbResultInsufficient() {
        val missingMcp = instant(
            HandLessonStep.THUMB_ON_TOP,
            hand(0, curl = 70f, thumbCrossing = true, missing = setOf(HandLandmarkId.THUMB_MCP)),
        )
        val missingIp = instant(
            HandLessonStep.THUMB_ON_TOP,
            hand(0, curl = 70f, thumbCrossing = true, missing = setOf(HandLandmarkId.THUMB_IP)),
        )
        assertNotEquals(InstantVerificationStatus.INSUFFICIENT_DATA, missingMcp.status)
        assertNotEquals(InstantVerificationStatus.INSUFFICIENT_DATA, missingIp.status)
    }

    @Test fun progressIsAlwaysFiniteAndWithinRange() {
        val verifier = FindYourWeaponTemporalVerifier()
        val results = mutableListOf<TemporalStepResult>()
        results += runStep(verifier, HandLessonStep.OPEN_PALM, listOf(hand(0), hand(150), hand(300)))
        results += verifier.update(missingHand(360, Handedness.RIGHT), insufficient(HandLessonStep.OPEN_PALM))
        val partialFrame = hand(420, curl = 125f)
        results += verifier.update(partialFrame, partial(HandLessonStep.OPEN_PALM))
        results.forEach { result ->
            assertTrue(result.progress.isFinite())
            assertTrue(result.progress in 0f..1f)
        }
    }

    @Test fun thumbStepCannotAcceptWhenClosedFingerLandmarksArePredictedOrMissing() {
        val predicted = runStep(
            HandLessonStep.THUMB_ON_TOP,
            listOf(hand(0, curl = 70f, thumbCrossing = true, predicted = setOf(HandLandmarkId.INDEX_PIP)), hand(200, curl = 70f, thumbCrossing = true, predicted = setOf(HandLandmarkId.INDEX_PIP)), hand(400, curl = 70f, thumbCrossing = true, predicted = setOf(HandLandmarkId.INDEX_PIP)), hand(600, curl = 70f, thumbCrossing = true, predicted = setOf(HandLandmarkId.INDEX_PIP)), hand(800, curl = 70f, thumbCrossing = true, predicted = setOf(HandLandmarkId.INDEX_PIP))),
        ).last()
        val missing = runStep(
            HandLessonStep.THUMB_ON_TOP,
            listOf(hand(0, curl = 70f, thumbCrossing = true, missing = setOf(HandLandmarkId.RING_DIP)), hand(200, curl = 70f, thumbCrossing = true, missing = setOf(HandLandmarkId.RING_DIP)), hand(400, curl = 70f, thumbCrossing = true, missing = setOf(HandLandmarkId.RING_DIP)), hand(600, curl = 70f, thumbCrossing = true, missing = setOf(HandLandmarkId.RING_DIP)), hand(800, curl = 70f, thumbCrossing = true, missing = setOf(HandLandmarkId.RING_DIP))),
        ).last()
        assertFalse(predicted.accepted)
        assertFalse(missing.accepted)
    }

    @Test fun knuckleStepAcceptsWhenNonWeaponFingerLandmarksArePredictedOrMissing() {
        val predicted = runStep(
            HandLessonStep.FRONT_TWO_KNUCKLES,
            listOf(hand(0, curl = 70f, thumbCrossing = true, predicted = setOf(HandLandmarkId.MIDDLE_PIP), backOfHandFacingCamera = true), hand(200, curl = 70f, thumbCrossing = true, predicted = setOf(HandLandmarkId.MIDDLE_PIP), backOfHandFacingCamera = true), hand(400, curl = 70f, thumbCrossing = true, predicted = setOf(HandLandmarkId.MIDDLE_PIP), backOfHandFacingCamera = true), hand(600, curl = 70f, thumbCrossing = true, predicted = setOf(HandLandmarkId.MIDDLE_PIP), backOfHandFacingCamera = true), hand(800, curl = 70f, thumbCrossing = true, predicted = setOf(HandLandmarkId.MIDDLE_PIP), backOfHandFacingCamera = true), hand(1000, curl = 70f, thumbCrossing = true, predicted = setOf(HandLandmarkId.MIDDLE_PIP), backOfHandFacingCamera = true)),
        ).last()
        val missing = runStep(
            HandLessonStep.FRONT_TWO_KNUCKLES,
            listOf(hand(0, curl = 70f, thumbCrossing = true, missing = setOf(HandLandmarkId.LITTLE_DIP), backOfHandFacingCamera = true), hand(200, curl = 70f, thumbCrossing = true, missing = setOf(HandLandmarkId.LITTLE_DIP), backOfHandFacingCamera = true), hand(400, curl = 70f, thumbCrossing = true, missing = setOf(HandLandmarkId.LITTLE_DIP), backOfHandFacingCamera = true), hand(600, curl = 70f, thumbCrossing = true, missing = setOf(HandLandmarkId.LITTLE_DIP), backOfHandFacingCamera = true), hand(800, curl = 70f, thumbCrossing = true, missing = setOf(HandLandmarkId.LITTLE_DIP), backOfHandFacingCamera = true), hand(1000, curl = 70f, thumbCrossing = true, missing = setOf(HandLandmarkId.LITTLE_DIP), backOfHandFacingCamera = true)),
        ).last()
        assertTrue(predicted.accepted)
        assertTrue(missing.accepted)
    }

    @Test fun knuckleStepAcceptsWhenThumbLandmarksArePredictedOrMissing() {
        val predicted = runStep(
            HandLessonStep.FRONT_TWO_KNUCKLES,
            listOf(hand(0, curl = 70f, thumbCrossing = true, predicted = setOf(HandLandmarkId.THUMB_TIP), backOfHandFacingCamera = true), hand(200, curl = 70f, thumbCrossing = true, predicted = setOf(HandLandmarkId.THUMB_TIP), backOfHandFacingCamera = true), hand(400, curl = 70f, thumbCrossing = true, predicted = setOf(HandLandmarkId.THUMB_TIP), backOfHandFacingCamera = true), hand(600, curl = 70f, thumbCrossing = true, predicted = setOf(HandLandmarkId.THUMB_TIP), backOfHandFacingCamera = true), hand(800, curl = 70f, thumbCrossing = true, predicted = setOf(HandLandmarkId.THUMB_TIP), backOfHandFacingCamera = true), hand(1000, curl = 70f, thumbCrossing = true, predicted = setOf(HandLandmarkId.THUMB_TIP), backOfHandFacingCamera = true)),
        ).last()
        val missing = runStep(
            HandLessonStep.FRONT_TWO_KNUCKLES,
            listOf(hand(0, curl = 70f, thumbCrossing = true, missing = setOf(HandLandmarkId.THUMB_TIP), backOfHandFacingCamera = true), hand(200, curl = 70f, thumbCrossing = true, missing = setOf(HandLandmarkId.THUMB_TIP), backOfHandFacingCamera = true), hand(400, curl = 70f, thumbCrossing = true, missing = setOf(HandLandmarkId.THUMB_TIP), backOfHandFacingCamera = true), hand(600, curl = 70f, thumbCrossing = true, missing = setOf(HandLandmarkId.THUMB_TIP), backOfHandFacingCamera = true), hand(800, curl = 70f, thumbCrossing = true, missing = setOf(HandLandmarkId.THUMB_TIP), backOfHandFacingCamera = true), hand(1000, curl = 70f, thumbCrossing = true, missing = setOf(HandLandmarkId.THUMB_TIP), backOfHandFacingCamera = true)),
        ).last()
        assertTrue(predicted.accepted)
        assertTrue(missing.accepted)
    }

    @Test fun acceptedProgressRemainsOneAfterLaterIncorrectFrames() {
        val verifier = FindYourWeaponTemporalVerifier()
        runStep(verifier, HandLessonStep.OPEN_PALM, listOf(hand(0), hand(200), hand(400), hand(600)))
        val laterIncorrect = update(verifier, HandLessonStep.OPEN_PALM, hand(800, curl = 70f))
        assertTrue(laterIncorrect.accepted)
        assertFalse(laterIncorrect.newlyAccepted)
        assertEquals(1f, laterIncorrect.progress)
    }

    private fun runOpenPalm(source: LandmarkSource, timestamps: List<Long>): List<TemporalStepResult> =
        runStep(HandLessonStep.OPEN_PALM, timestamps.map { hand(it, source = source) })

    private fun runStep(step: HandLessonStep, frames: List<TrackedHandFrame>): List<TemporalStepResult> =
        runStep(FindYourWeaponTemporalVerifier(), step, frames)

    private fun runStep(verifier: FindYourWeaponTemporalVerifier, step: HandLessonStep, frames: List<TrackedHandFrame>): List<TemporalStepResult> =
        frames.map { update(verifier, step, it) }

    private fun update(verifier: FindYourWeaponTemporalVerifier, step: HandLessonStep, frame: TrackedHandFrame): TemporalStepResult =
        verifier.update(frame, instant(step, frame))

    private fun instant(step: HandLessonStep, frame: TrackedHandFrame): InstantStepResult =
        instantVerifier.verify(step, frame, extractor.extract(frame))

    private fun insufficient(step: HandLessonStep): InstantStepResult = InstantStepResult(
        step = step,
        status = InstantVerificationStatus.INSUFFICIENT_DATA,
        score = 0f,
        quality = 0f,
        feedbackCode = FeedbackCode.INSUFFICIENT_VISIBILITY,
        criticalLandmarksVisible = false,
    )

    private fun partial(step: HandLessonStep, score: Float = 0.8f): InstantStepResult = InstantStepResult(
        step = step,
        status = InstantVerificationStatus.PARTIAL_MATCH,
        score = score,
        quality = 0.8f,
        feedbackCode = FeedbackCode.HOLD_STILL,
        criticalLandmarksVisible = true,
    )

    private fun matching(step: HandLessonStep, quality: Float): InstantStepResult = InstantStepResult(
        step = step,
        status = InstantVerificationStatus.MATCHING,
        score = 1f,
        quality = quality,
        feedbackCode = FeedbackCode.GOOD,
        criticalLandmarksVisible = true,
    )

    private fun missingHand(timestampMs: Long, handedness: Handedness): TrackedHandFrame = TrackedHandFrame(
        timestampMs = timestampMs,
        handedness = handedness,
        landmarks = HandLandmarkId.entries.associateWith { LandmarkSample(null, 0f, LandmarkSource.MISSING) },
    )

    private fun hand(
        timestampMs: Long,
        handedness: Handedness = Handedness.RIGHT,
        curl: Float = 175f,
        thumbCrossing: Boolean = false,
        source: LandmarkSource = LandmarkSource.OBSERVED,
        missing: Set<HandLandmarkId> = emptySet(),
        predicted: Set<HandLandmarkId> = emptySet(),
        mcpDirection: Double = 90.0,
        thumbTipX: Float? = null,
        backOfHandFacingCamera: Boolean = false,
    ): TrackedHandFrame {
        val scale = 1f
        val offset = Point3(0f, 0f, 0f)
        val handednessMirror = if (handedness == Handedness.LEFT) -1f else 1f
        val mirror = if (backOfHandFacingCamera) -handednessMirror else handednessMirror
        val map = mutableMapOf<HandLandmarkId, Point3>()
        map[HandLandmarkId.WRIST] = transform(0f, 0f, scale, offset, mirror)
        finger(map, HandLandmarkId.INDEX_MCP, HandLandmarkId.INDEX_PIP, HandLandmarkId.INDEX_DIP, HandLandmarkId.INDEX_TIP, -.6f, 1f, curl, scale, offset, mirror, mcpDirection)
        finger(map, HandLandmarkId.MIDDLE_MCP, HandLandmarkId.MIDDLE_PIP, HandLandmarkId.MIDDLE_DIP, HandLandmarkId.MIDDLE_TIP, -.2f, 1f, curl, scale, offset, mirror, mcpDirection)
        finger(map, HandLandmarkId.RING_MCP, HandLandmarkId.RING_PIP, HandLandmarkId.RING_DIP, HandLandmarkId.RING_TIP, .2f, 1f, curl, scale, offset, mirror, mcpDirection)
        finger(map, HandLandmarkId.LITTLE_MCP, HandLandmarkId.LITTLE_PIP, HandLandmarkId.LITTLE_DIP, HandLandmarkId.LITTLE_TIP, .6f, 1f, curl, scale, offset, mirror, mcpDirection)
        val resolvedThumbTipX = thumbTipX ?: if (thumbCrossing) 0.05f else -2.2f
        map[HandLandmarkId.THUMB_CMC] = transform(-.75f, .45f, scale, offset, mirror)
        map[HandLandmarkId.THUMB_MCP] = transform(-.95f, .9f, scale, offset, mirror)
        map[HandLandmarkId.THUMB_IP] = transform(resolvedThumbTipX - .2f, 1.1f, scale, offset, mirror)
        map[HandLandmarkId.THUMB_TIP] = transform(resolvedThumbTipX, 1.15f, scale, offset, mirror)
        return TrackedHandFrame(
            timestampMs = timestampMs,
            handedness = handedness,
            landmarks = HandLandmarkId.entries.associateWith { id ->
                val point = map[id]
                when {
                    id in missing || point == null -> LandmarkSample(null, 0f, LandmarkSource.MISSING)
                    id in predicted -> LandmarkSample(point, 1f, LandmarkSource.PREDICTED)
                    else -> LandmarkSample(point, 1f, source)
                }
            },
        )
    }

    private fun finger(
        map: MutableMap<HandLandmarkId, Point3>,
        mcp: HandLandmarkId,
        pip: HandLandmarkId,
        dip: HandLandmarkId,
        tip: HandLandmarkId,
        x: Float,
        y: Float,
        angle: Float,
        scale: Float,
        offset: Point3,
        mirror: Float,
        mcpDirection: Double,
    ) {
        val p = transform(x, y, scale, offset, mirror)
        val q = p + vector(.7f, mcpDirection, scale, mirror)
        val r = q + vector(.6f, mcpDirection + 180.0 - angle, scale, mirror)
        val s = r + vector(.5f, mcpDirection + 360.0 - 2 * angle, scale, mirror)
        map[mcp] = p
        map[pip] = q
        map[dip] = r
        map[tip] = s
    }

    private fun transform(x: Float, y: Float, scale: Float, offset: Point3, mirror: Float): Point3 =
        Point3(offset.x + x * scale * mirror, offset.y + y * scale, offset.z)

    private fun vector(length: Float, degrees: Double, scale: Float, mirror: Float): Point3 {
        val radians = Math.toRadians(degrees)
        return Point3((cos(radians) * length * scale * mirror).toFloat(), (sin(radians) * length * scale).toFloat(), 0f)
    }
}
