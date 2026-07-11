package dk.lasse.karateanalyzer.core

import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FindYourWeaponTemporalVerifierTest {
    @Test fun observedSixHundredMsAccepts() {
        val result = runOpenPalm(LandmarkSource.OBSERVED, listOf(0L, 600L)).last()
        assertTrue(result.accepted)
        assertTrue(result.newlyAccepted)
        assertEquals(600.0, result.reliableHoldCreditMs, 0.001)
    }

    @Test fun interpolatedSixHundredMsDoesNotAccept() {
        val result = runOpenPalm(LandmarkSource.INTERPOLATED, listOf(0L, 600L)).last()
        assertFalse(result.accepted)
        assertEquals(450.0, result.reliableHoldCreditMs, 0.001)
    }

    @Test fun interpolatedEightHundredMsMayAccept() {
        val result = runOpenPalm(LandmarkSource.INTERPOLATED, listOf(0L, 800L)).last()
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

    @Test fun partialFramesCannotIncreaseReliableMatchingMs() {
        val verifier = FindYourWeaponTemporalVerifier()
        val partial = verifier.update(HandLessonStep.OPEN_PALM, hand(0, curl = 125f))
        assertFalse(partial.accepted)
        assertTrue(partial.progress > 0f)
        assertEquals(0.0, partial.reliableMatchingMs, 0.001)
        val laterPartial = verifier.update(HandLessonStep.OPEN_PALM, hand(600, curl = 125f))
        assertEquals(0.0, laterPartial.reliableMatchingMs, 0.001)
        assertEquals(0.0, laterPartial.reliableHoldCreditMs, 0.001)
    }

    @Test fun partialProgressFollowedByOneMatchingFrameCannotAccept() {
        val verifier = FindYourWeaponTemporalVerifier()
        verifier.update(HandLessonStep.OPEN_PALM, hand(0, curl = 125f))
        val result = verifier.update(HandLessonStep.OPEN_PALM, hand(600))
        assertFalse(result.accepted)
        assertEquals(0.0, result.reliableMatchingMs, 0.001)
    }

    @Test fun zeroDecayStillCannotTurnPartialCreditIntoReliableTime() {
        val verifier = FindYourWeaponTemporalVerifier(
            FindYourWeaponTemporalConfiguration(incorrectFrameDecayRatio = 0.0),
        )
        verifier.update(HandLessonStep.OPEN_PALM, hand(0, curl = 125f))
        verifier.update(HandLessonStep.OPEN_PALM, hand(600, curl = 125f))
        val result = verifier.update(HandLessonStep.OPEN_PALM, hand(1200))
        assertFalse(result.accepted)
        assertEquals(0.0, result.reliableMatchingMs, 0.001)
        assertEquals(0.0, result.reliableHoldCreditMs, 0.001)
    }

    @Test fun thumbStepCannotAcceptWhenClosedFingerLandmarksArePredictedOrMissing() {
        val predicted = runStep(
            HandLessonStep.THUMB_ON_TOP,
            listOf(hand(0, curl = 70f, thumbCrossing = true, predicted = setOf(HandLandmarkId.INDEX_PIP)), hand(600, curl = 70f, thumbCrossing = true, predicted = setOf(HandLandmarkId.INDEX_PIP)), hand(1200, curl = 70f, thumbCrossing = true, predicted = setOf(HandLandmarkId.INDEX_PIP))),
        ).last()
        val missing = runStep(
            HandLessonStep.THUMB_ON_TOP,
            listOf(hand(0, curl = 70f, thumbCrossing = true, missing = setOf(HandLandmarkId.RING_DIP)), hand(600, curl = 70f, thumbCrossing = true, missing = setOf(HandLandmarkId.RING_DIP)), hand(1200, curl = 70f, thumbCrossing = true, missing = setOf(HandLandmarkId.RING_DIP))),
        ).last()
        assertFalse(predicted.accepted)
        assertFalse(missing.accepted)
    }

    @Test fun knuckleStepCannotAcceptWhenClosedFingerLandmarksArePredictedOrMissing() {
        val predicted = runStep(
            HandLessonStep.FRONT_TWO_KNUCKLES,
            listOf(hand(0, curl = 70f, predicted = setOf(HandLandmarkId.MIDDLE_PIP)), hand(600, curl = 70f, predicted = setOf(HandLandmarkId.MIDDLE_PIP)), hand(1200, curl = 70f, predicted = setOf(HandLandmarkId.MIDDLE_PIP))),
        ).last()
        val missing = runStep(
            HandLessonStep.FRONT_TWO_KNUCKLES,
            listOf(hand(0, curl = 70f, missing = setOf(HandLandmarkId.LITTLE_DIP)), hand(600, curl = 70f, missing = setOf(HandLandmarkId.LITTLE_DIP)), hand(1200, curl = 70f, missing = setOf(HandLandmarkId.LITTLE_DIP))),
        ).last()
        assertFalse(predicted.accepted)
        assertFalse(missing.accepted)
    }

    @Test fun acceptedProgressRemainsOneAfterLaterIncorrectFrames() {
        val verifier = FindYourWeaponTemporalVerifier()
        verifier.update(HandLessonStep.OPEN_PALM, hand(0))
        val accepted = verifier.update(HandLessonStep.OPEN_PALM, hand(600))
        assertTrue(accepted.accepted)
        assertTrue(accepted.newlyAccepted)
        val laterIncorrect = verifier.update(HandLessonStep.OPEN_PALM, hand(1200, curl = 70f))
        assertTrue(laterIncorrect.accepted)
        assertFalse(laterIncorrect.newlyAccepted)
        assertEquals(1f, laterIncorrect.progress)
    }

    private fun runOpenPalm(source: LandmarkSource, timestamps: List<Long>): List<TemporalStepResult> =
        runStep(HandLessonStep.OPEN_PALM, timestamps.map { hand(it, source = source) })

    private fun runStep(step: HandLessonStep, frames: List<TrackedHandFrame>): List<TemporalStepResult> {
        val verifier = FindYourWeaponTemporalVerifier()
        return frames.map { verifier.update(step, it) }
    }

    private fun hand(
        timestampMs: Long,
        handedness: Handedness = Handedness.RIGHT,
        curl: Float = 175f,
        thumbCrossing: Boolean = false,
        source: LandmarkSource = LandmarkSource.OBSERVED,
        missing: Set<HandLandmarkId> = emptySet(),
        predicted: Set<HandLandmarkId> = emptySet(),
        mcpDirection: Double = 90.0,
    ): TrackedHandFrame {
        val scale = 1f
        val offset = Point3(0f, 0f, 0f)
        val mirror = if (handedness == Handedness.LEFT) -1f else 1f
        val map = mutableMapOf<HandLandmarkId, Point3>()
        map[HandLandmarkId.WRIST] = transform(0f, 0f, scale, offset, mirror)
        finger(map, HandLandmarkId.INDEX_MCP, HandLandmarkId.INDEX_PIP, HandLandmarkId.INDEX_DIP, HandLandmarkId.INDEX_TIP, -.6f, 1f, curl, scale, offset, mirror, mcpDirection)
        finger(map, HandLandmarkId.MIDDLE_MCP, HandLandmarkId.MIDDLE_PIP, HandLandmarkId.MIDDLE_DIP, HandLandmarkId.MIDDLE_TIP, -.2f, 1f, curl, scale, offset, mirror, mcpDirection)
        finger(map, HandLandmarkId.RING_MCP, HandLandmarkId.RING_PIP, HandLandmarkId.RING_DIP, HandLandmarkId.RING_TIP, .2f, 1f, curl, scale, offset, mirror, mcpDirection)
        finger(map, HandLandmarkId.LITTLE_MCP, HandLandmarkId.LITTLE_PIP, HandLandmarkId.LITTLE_DIP, HandLandmarkId.LITTLE_TIP, .6f, 1f, curl, scale, offset, mirror, mcpDirection)
        val thumbTipX = if (thumbCrossing) 0.05f else -1.15f
        map[HandLandmarkId.THUMB_CMC] = transform(-.75f, .45f, scale, offset, mirror)
        map[HandLandmarkId.THUMB_MCP] = transform(-.95f, .9f, scale, offset, mirror)
        map[HandLandmarkId.THUMB_IP] = transform(thumbTipX - .2f, 1.1f, scale, offset, mirror)
        map[HandLandmarkId.THUMB_TIP] = transform(thumbTipX, 1.15f, scale, offset, mirror)
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
