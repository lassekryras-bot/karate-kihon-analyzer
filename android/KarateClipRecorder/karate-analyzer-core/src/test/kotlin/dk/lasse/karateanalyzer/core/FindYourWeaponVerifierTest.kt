package dk.lasse.karateanalyzer.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.*

class FindYourWeaponVerifierTest {
    private val extractor = HandFeatureExtractor()
    private val verifier = FindYourWeaponVerifier()

    @Test fun openPalmRightAndLeftMatchAndCurledDoesNot() {
        assertEquals(InstantVerificationStatus.MATCHING, verify(HandLessonStep.OPEN_PALM, hand(Handedness.RIGHT)).status)
        assertEquals(InstantVerificationStatus.MATCHING, verify(HandLessonStep.OPEN_PALM, hand(Handedness.LEFT)).status)
        assertNotEquals(InstantVerificationStatus.MATCHING, verify(HandLessonStep.OPEN_PALM, hand(curl = 70f)).status)
        val closedThumb = verify(HandLessonStep.OPEN_PALM, hand(thumbCrossing = true))
        assertNotEquals(InstantVerificationStatus.MATCHING, closedThumb.status)
        assertEquals(FeedbackCode.OPEN_THUMB, closedThumb.feedbackCode)
    }

    @Test fun openPalmRejectsBackOfHandFacingCamera() {
        val rightBack = verify(HandLessonStep.OPEN_PALM, hand(Handedness.RIGHT, backOfHandFacingCamera = true))
        val leftBack = verify(HandLessonStep.OPEN_PALM, hand(Handedness.LEFT, backOfHandFacingCamera = true))

        assertNotEquals(InstantVerificationStatus.MATCHING, rightBack.status)
        assertNotEquals(InstantVerificationStatus.MATCHING, leftBack.status)
        assertEquals(FeedbackCode.TURN_FIST_TOWARD_CAMERA, rightBack.feedbackCode)
        assertEquals(FeedbackCode.TURN_FIST_TOWARD_CAMERA, leftBack.feedbackCode)
    }

    @Test fun openPalmConfigAllowsThreeFingersButInsufficientAndPredictedDoNotMatch() {
        val loose = FindYourWeaponVerifier(FindYourWeaponVerifierConfiguration(minimumVisibleFingerCount = 3))
        val missingLittle = hand(missing = setOf(HandLandmarkId.LITTLE_PIP, HandLandmarkId.LITTLE_DIP, HandLandmarkId.LITTLE_TIP))
        assertTrue(loose.verify(HandLessonStep.OPEN_PALM, missingLittle, extractor.extract(missingLittle)).status >= InstantVerificationStatus.PARTIAL_MATCH)
        assertEquals(InstantVerificationStatus.INSUFFICIENT_DATA, verify(HandLessonStep.OPEN_PALM, hand(missing = setOf(HandLandmarkId.WRIST))).status)
        assertNotEquals(InstantVerificationStatus.MATCHING, verify(HandLessonStep.OPEN_PALM, hand(source = LandmarkSource.PREDICTED)).status)
    }

    @Test fun bendFingertipsRecognizesOnlyIntermediateShape() {
        assertNotEquals(InstantVerificationStatus.MATCHING, verify(HandLessonStep.BEND_FINGERTIPS, hand(curl = 175f)).status)
        assertEquals(InstantVerificationStatus.MATCHING, verify(HandLessonStep.BEND_FINGERTIPS, hand(curl = 125f)).status)
        assertNotEquals(InstantVerificationStatus.MATCHING, verify(HandLessonStep.BEND_FINGERTIPS, hand(curl = 70f)).status)
        val closedThumb = verify(HandLessonStep.BEND_FINGERTIPS, hand(curl = 125f, thumbCrossing = true))
        assertNotEquals(InstantVerificationStatus.MATCHING, closedThumb.status)
        assertEquals(FeedbackCode.OPEN_THUMB, closedThumb.feedbackCode)
        val folded = verify(HandLessonStep.BEND_FINGERTIPS, hand(curl = 125f, mcpDirection = 235.0))
        assertNotEquals(InstantVerificationStatus.MATCHING, folded.status)
        assertEquals(FeedbackCode.DO_NOT_CLOSE_YET, folded.feedbackCode)
        val uneven = verify(HandLessonStep.BEND_FINGERTIPS, hand(perFinger = listOf(125f,125f,95f,160f)))
        assertNotEquals(InstantVerificationStatus.MATCHING, uneven.status)
        assertEquals(FeedbackCode.FINGERS_UNEVEN, uneven.feedbackCode)
    }

    @Test fun closedFingersRequiresThumbOpenAndReportsUnevenClosure() {
        assertEquals(InstantVerificationStatus.MATCHING, verify(HandLessonStep.CLOSE_FINGERS, hand(curl = 70f)).status)
        assertNotEquals(InstantVerificationStatus.MATCHING, verify(HandLessonStep.CLOSE_FINGERS, hand()).status)
        assertEquals(InstantVerificationStatus.MATCHING, verify(HandLessonStep.CLOSE_FINGERS, hand(curl = 70f, thumbCrossing = false)).status)
        val closedThumb = verify(HandLessonStep.CLOSE_FINGERS, hand(curl = 70f, thumbCrossing = true))
        assertNotEquals(InstantVerificationStatus.MATCHING, closedThumb.status)
        assertEquals(FeedbackCode.OPEN_THUMB, closedThumb.feedbackCode)
        assertNotEquals(InstantVerificationStatus.MATCHING, verify(HandLessonStep.CLOSE_FINGERS, hand(curl = 70f, missing = thumbIds)).status)
        val uneven = verify(HandLessonStep.CLOSE_FINGERS, hand(perFinger = listOf(70f,70f,70f,175f)))
        assertTrue(uneven.score < verify(HandLessonStep.CLOSE_FINGERS, hand(curl = 70f)).score)
        val moderatelyUneven = verify(HandLessonStep.CLOSE_FINGERS, hand(perFinger = listOf(70f,70f,70f,120f)))
        assertNotEquals(InstantVerificationStatus.MATCHING, moderatelyUneven.status)
        assertEquals(FeedbackCode.FINGERS_UNEVEN, moderatelyUneven.feedbackCode)
    }

    @Test fun thumbOnTopRequiresReliableThumbAcrossAndMirrors() {
        assertEquals(InstantVerificationStatus.MATCHING, verify(HandLessonStep.THUMB_ON_TOP, hand(curl = 70f, thumbCrossing = true)).status)
        assertEquals(InstantVerificationStatus.MATCHING, verify(HandLessonStep.THUMB_ON_TOP, hand(curl = 70f, thumbTipX = -0.25f)).status)
        assertNotEquals(InstantVerificationStatus.MATCHING, verify(HandLessonStep.THUMB_ON_TOP, hand(curl = 70f, thumbCrossing = false)).status)
        val openThumb = verify(HandLessonStep.THUMB_ON_TOP, hand(curl = 70f, thumbTipX = -0.9f))
        assertNotEquals(InstantVerificationStatus.MATCHING, openThumb.status)
        assertEquals(FeedbackCode.MOVE_THUMB_ACROSS, openThumb.feedbackCode)
        val outsideIndexLine = verify(HandLessonStep.THUMB_ON_TOP, hand(curl = 70f, thumbTipX = -0.9f))
        assertNotEquals(InstantVerificationStatus.MATCHING, outsideIndexLine.status)
        assertEquals(FeedbackCode.MOVE_THUMB_ACROSS, outsideIndexLine.feedbackCode)
        val missingThumb = verify(HandLessonStep.THUMB_ON_TOP, hand(curl = 70f, missing = setOf(HandLandmarkId.THUMB_TIP)))
        assertEquals(InstantVerificationStatus.INSUFFICIENT_DATA, missingThumb.status)
        assertEquals(FeedbackCode.INSUFFICIENT_VISIBILITY, missingThumb.feedbackCode)
        assertNotEquals(InstantVerificationStatus.MATCHING, verify(HandLessonStep.THUMB_ON_TOP, hand(curl = 70f, thumbCrossing = true, source = LandmarkSource.PREDICTED)).status)
        assertEquals(verify(HandLessonStep.THUMB_ON_TOP, hand(Handedness.RIGHT, 70f, true)).status, verify(HandLessonStep.THUMB_ON_TOP, hand(Handedness.LEFT, 70f, true)).status)
    }

    @Test fun frontTwoKnucklesHighlightsAndHandlesLimitedOrientation() {
        val ok = verify(HandLessonStep.FRONT_TWO_KNUCKLES, hand(curl = 70f, thumbCrossing = true))
        assertEquals(InstantVerificationStatus.MATCHING, ok.status)
        assertTrue(ok.highlightLandmarks.containsAll(setOf(HandLandmarkId.INDEX_MCP, HandLandmarkId.MIDDLE_MCP)))
        val thumbNotReady = verify(HandLessonStep.FRONT_TWO_KNUCKLES, hand(curl = 70f, thumbCrossing = false))
        assertNotEquals(InstantVerificationStatus.MATCHING, thumbNotReady.status)
        assertEquals(FeedbackCode.MOVE_THUMB_ACROSS, thumbNotReady.feedbackCode)
        assertNotEquals(InstantVerificationStatus.MATCHING, verify(HandLessonStep.FRONT_TWO_KNUCKLES, hand(curl = 70f, thumbTipX = -0.9f)).status)
        assertNotEquals(InstantVerificationStatus.MATCHING, verify(HandLessonStep.FRONT_TWO_KNUCKLES, hand()).status)
        val missingKnuckle = verify(HandLessonStep.FRONT_TWO_KNUCKLES, hand(curl = 70f, thumbCrossing = true, missing = setOf(HandLandmarkId.INDEX_MCP)))
        assertEquals(InstantVerificationStatus.INSUFFICIENT_DATA, missingKnuckle.status)
        assertFalse(HandLandmarkId.INDEX_MCP in missingKnuckle.highlightLandmarks)
        assertTrue(HandLandmarkId.MIDDLE_MCP in missingKnuckle.highlightLandmarks)
        assertTrue(ok.quality.isFinite())
    }

    @Test fun thumbStepsUseShiftedIndexEdgeBoundary() {
        val relaxed = FindYourWeaponVerifier(
            FindYourWeaponVerifierConfiguration(
                matchingScoreThreshold = 0.45f,
                thumbAcrossMinimumLateralRatio = -0.8f,
            ),
        )
        val centerLine = hand(curl = 70f, thumbTipX = -0.6f)
        val outsideEdge = hand(curl = 70f, thumbTipX = -0.9f)

        assertEquals(InstantVerificationStatus.MATCHING, relaxed.verify(HandLessonStep.THUMB_ON_TOP, centerLine, extractor.extract(centerLine)).status)
        assertEquals(InstantVerificationStatus.MATCHING, relaxed.verify(HandLessonStep.FRONT_TWO_KNUCKLES, centerLine, extractor.extract(centerLine)).status)
        assertNotEquals(InstantVerificationStatus.MATCHING, relaxed.verify(HandLessonStep.THUMB_ON_TOP, outsideEdge, extractor.extract(outsideEdge)).status)
        assertNotEquals(InstantVerificationStatus.MATCHING, relaxed.verify(HandLessonStep.FRONT_TWO_KNUCKLES, outsideEdge, extractor.extract(outsideEdge)).status)
    }

    @Test fun matchingResultsAlwaysUseGoodFeedback() {
        val fixtures = listOf(
            hand(),
            hand(curl = 125f),
            hand(curl = 70f),
            hand(curl = 70f, thumbCrossing = true),
            hand(perFinger = listOf(125f, 125f, 95f, 160f)),
            hand(perFinger = listOf(70f, 70f, 70f, 120f)),
            hand(curl = 70f, thumbCrossing = true, middleMcpZ = 1.6f),
        )

        for (step in HandLessonStep.entries) {
            for (frame in fixtures) {
                val result = verify(step, frame)
                if (result.status == InstantVerificationStatus.MATCHING) {
                    assertEquals(FeedbackCode.GOOD, result.feedbackCode)
                    assertNotEquals(FeedbackCode.FINGERS_UNEVEN, result.feedbackCode)
                    assertNotEquals(FeedbackCode.TURN_FIST_TOWARD_CAMERA, result.feedbackCode)
                }
            }
        }
    }

    @Test fun frontTwoKnucklesBelowOrientationToleranceDoesNotMatch() {
        val result = verify(HandLessonStep.FRONT_TWO_KNUCKLES, hand(curl = 70f, thumbCrossing = true, middleMcpZ = 1.6f))
        assertNotEquals(InstantVerificationStatus.MATCHING, result.status)
        assertEquals(FeedbackCode.TURN_FIST_TOWARD_CAMERA, result.feedbackCode)
    }

    @Test fun qualityAndConfigurationGatesPreventMatching() {
        for (step in HandLessonStep.entries) {
            val frame = hand(curl = 70f, thumbCrossing = true, source = LandmarkSource.PREDICTED)
            val result = verify(step, frame)
            assertNotEquals(InstantVerificationStatus.MATCHING, result.status)
            assertFalse(result.status == InstantVerificationStatus.MATCHING && result.feedbackCode == FeedbackCode.HOLD_STILL)
        }

        val strictReliability = FindYourWeaponVerifier(
            FindYourWeaponVerifierConfiguration(minimumMatchingReliableCriticalQuality = 0.9f),
        )
        val interpolated = hand(source = LandmarkSource.INTERPOLATED)
        assertNotEquals(
            InstantVerificationStatus.MATCHING,
            strictReliability.verify(HandLessonStep.OPEN_PALM, interpolated, extractor.extract(interpolated)).status,
        )

        val strictOpenPalm = FindYourWeaponVerifier(
            FindYourWeaponVerifierConfiguration(openPalmExtensionThreshold = 1.1f),
        )
        val open = hand()
        assertNotEquals(
            InstantVerificationStatus.MATCHING,
            strictOpenPalm.verify(HandLessonStep.OPEN_PALM, open, extractor.extract(open)).status,
        )
    }

    @Test fun generalInvariants() {
        for (step in HandLessonStep.entries) for (frame in listOf(hand(), hand(Handedness.LEFT, 125f, true, scale=3f, offset=Point3(9f,-2f,1f)), hand(source=LandmarkSource.INTERPOLATED), hand(missing=HandLandmarkId.entries.toSet()))) {
            val r = verify(step, frame)
            assertTrue(r.score in 0f..1f && r.score.isFinite())
            assertTrue(r.quality in 0f..1f && r.quality.isFinite())
        }
        val base = verify(HandLessonStep.CLOSE_FINGERS, hand(curl=70f))
        val moved = verify(HandLessonStep.CLOSE_FINGERS, hand(curl=70f, scale=2f, offset=Point3(5f,5f,0f)))
        assertEquals(base.score, moved.score, .001f)
    }

    private fun verify(step: HandLessonStep, frame: TrackedHandFrame) = verifier.verify(step, frame, extractor.extract(frame))

    private fun hand(handedness: Handedness = Handedness.RIGHT, curl: Float = 175f, thumbCrossing: Boolean = false, missing: Set<HandLandmarkId> = emptySet(), source: LandmarkSource = LandmarkSource.OBSERVED, scale: Float = 1f, offset: Point3 = Point3(0f,0f,0f), perFinger: List<Float>? = null, mcpDirection: Double = 90.0, middleMcpZ: Float = 0f, backOfHandFacingCamera: Boolean = false, thumbTipX: Float? = null): TrackedHandFrame {
        val handednessMirror = if (handedness == Handedness.LEFT) -1f else 1f
        val mirror = if (backOfHandFacingCamera) -handednessMirror else handednessMirror
        val map = mutableMapOf<HandLandmarkId, Point3>(); map[HandLandmarkId.WRIST]=t(0f,0f,scale,offset,mirror)
        val curls = perFinger ?: listOf(curl,curl,curl,curl)
        finger(map, HandLandmarkId.INDEX_MCP,HandLandmarkId.INDEX_PIP,HandLandmarkId.INDEX_DIP,HandLandmarkId.INDEX_TIP,-.6f,1f,curls[0],scale,offset,mirror,mcpDirection)
        finger(map, HandLandmarkId.MIDDLE_MCP,HandLandmarkId.MIDDLE_PIP,HandLandmarkId.MIDDLE_DIP,HandLandmarkId.MIDDLE_TIP,-.2f,1f,curls[1],scale,offset,mirror,mcpDirection)
        if (middleMcpZ != 0f) {
            for (id in listOf(HandLandmarkId.MIDDLE_MCP, HandLandmarkId.MIDDLE_PIP, HandLandmarkId.MIDDLE_DIP, HandLandmarkId.MIDDLE_TIP)) {
                map[id] = map.getValue(id).copy(z = middleMcpZ)
            }
        }
        finger(map, HandLandmarkId.RING_MCP,HandLandmarkId.RING_PIP,HandLandmarkId.RING_DIP,HandLandmarkId.RING_TIP,.2f,1f,curls[2],scale,offset,mirror,mcpDirection)
        finger(map, HandLandmarkId.LITTLE_MCP,HandLandmarkId.LITTLE_PIP,HandLandmarkId.LITTLE_DIP,HandLandmarkId.LITTLE_TIP,.6f,1f,curls[3],scale,offset,mirror,mcpDirection)
        val tx=thumbTipX ?: if(thumbCrossing).45f else -2.2f
        map[HandLandmarkId.THUMB_CMC]=t(-.75f,.45f,scale,offset,mirror); map[HandLandmarkId.THUMB_MCP]=t(-.95f,.9f,scale,offset,mirror); map[HandLandmarkId.THUMB_IP]=t(tx-.2f,1.1f,scale,offset,mirror); map[HandLandmarkId.THUMB_TIP]=t(tx,1.15f,scale,offset,mirror)
        return TrackedHandFrame(1, handedness, HandLandmarkId.entries.associateWith { id -> if (id in missing || map[id] == null) LandmarkSample(null,0f,LandmarkSource.MISSING) else LandmarkSample(map[id],1f,source) })
    }
    private fun finger(m: MutableMap<HandLandmarkId, Point3>, a:HandLandmarkId,b:HandLandmarkId,c:HandLandmarkId,d:HandLandmarkId,x:Float,y:Float,ang:Float,scale:Float,off:Point3,mir:Float,mcpDirection:Double){ val p=t(x,y,scale,off,mir); val q=p+v(.7f,mcpDirection,scale,mir); val r=q+v(.6f,mcpDirection+180.0-ang,scale,mir); val s=r+v(.5f,mcpDirection+360.0-2*ang,scale,mir); m[a]=p;m[b]=q;m[c]=r;m[d]=s }
    private fun v(l:Float, deg:Double, scale:Float, mir:Float)=Point3((cos(deg*PI/180)*l*scale*mir).toFloat(), (sin(deg*PI/180)*l*scale).toFloat(), 0f)
    private fun t(x:Float,y:Float,scale:Float,off:Point3,mir:Float)=Point3(x*scale*mir+off.x,y*scale+off.y,off.z)
    private val thumbIds = setOf(HandLandmarkId.THUMB_CMC,HandLandmarkId.THUMB_MCP,HandLandmarkId.THUMB_IP,HandLandmarkId.THUMB_TIP)
}
