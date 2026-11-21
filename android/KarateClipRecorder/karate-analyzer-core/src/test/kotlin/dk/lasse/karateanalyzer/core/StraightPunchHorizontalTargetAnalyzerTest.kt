package dk.lasse.karateanalyzer.core

import dk.lasse.karateanalyzer.capture.LateralSide
import dk.lasse.karateanalyzer.capture.qom.ExtremityPointComposer
import dk.lasse.karateanalyzer.capture.qom.MotionBodyProfile
import dk.lasse.karateanalyzer.geometry.CanonicalGeometryDescriptor
import org.junit.Assert.*
import org.junit.Test

class StraightPunchHorizontalTargetAnalyzerTest {
    private fun sample(x: Float, y: Float, z: Float = 0f, source: LandmarkSource = LandmarkSource.OBSERVED) =
        PoseLandmarkSample(Point3(x, y, z), Point3(x, y, z), .95f, .95f, source)

    private fun frame(
        fistY: Float = .40f,
        shoulderLeftY: Float = .30f,
        shoulderRightY: Float = .34f,
        hipLeftY: Float = .70f,
        hipRightY: Float = .74f,
        remove: Set<PoseLandmarkId> = emptySet(),
    ): PoseFrame {
        val values = mutableMapOf(
            PoseLandmarkId.NOSE to sample(.35f, .15f),
            PoseLandmarkId.MOUTH_LEFT to sample(.36f, .18f),
            PoseLandmarkId.MOUTH_RIGHT to sample(.40f, .18f),
            PoseLandmarkId.LEFT_SHOULDER to sample(.45f, shoulderLeftY, -.1f),
            PoseLandmarkId.RIGHT_SHOULDER to sample(.55f, shoulderRightY, .1f),
            PoseLandmarkId.LEFT_HIP to sample(.47f, hipLeftY),
            PoseLandmarkId.RIGHT_HIP to sample(.53f, hipRightY),
            PoseLandmarkId.LEFT_WRIST to sample(.20f, fistY),
            PoseLandmarkId.LEFT_THUMB to sample(.20f, fistY),
            PoseLandmarkId.LEFT_INDEX to sample(.20f, fistY),
            PoseLandmarkId.LEFT_PINKY to sample(.20f, fistY),
        )
        remove.forEach(values::remove)
        return PoseFrame(1_834L, values)
    }

    private val geometry = CanonicalGeometryDescriptor(
        geometryId = "geometry", recordingId = "recording", landmarkTrackId = "track",
        canonicalWidth = 1080, canonicalHeight = 1920,
    )

    private fun analyze(frame: PoseFrame, facing: ImageFacingDirection = ImageFacingDirection.UNKNOWN): StraightPunchHorizontalTargetResult {
        val fist = requireNotNull(ExtremityPointComposer.compositeSourcePoint(frame, MotionBodyProfile.PUNCH_HAND_LANDMARKS_LEFT)).point
        return requireNotNull(StraightPunchHorizontalTargetAnalyzer.analyze(StraightPunchHorizontalTargetInput(
            frame, 12, 1_834_000, "recording", "track", geometry, LateralSide.LEFT, fist, facing = facing,
        )))
    }

    @Test
    fun signedErrorsUseHorizontalImageYAndLeanAveragedAnchors() {
        val result = analyze(frame(), ImageFacingDirection.IMAGE_LEFT)
        val chudan = result.guides.getValue(PunchHeightTargetType.CHUDAN)
        val expectedShoulderY = .32
        val expectedHipY = .72
        val expectedChudanY = expectedShoulderY + .45 * (expectedHipY - expectedShoulderY)
        assertEquals(expectedChudanY, chudan.targetPoint!!.y.toDouble(), 1e-6)
        assertEquals(expectedChudanY - .40, chudan.signedErrorNormalized!!, 1e-6)
        assertEquals((expectedChudanY - .40) * 1920.0, chudan.signedErrorCanonicalPx!!, 1e-3)
        assertTrue("positive means fist above", chudan.signedErrorNormalized!! > 0)
        assertEquals(ImageFacingDirection.IMAGE_LEFT, result.facing)
    }

    @Test
    fun rightFacingAndNegativeSignAreIndependentOfPunchSide() {
        val base = frame(fistY = .80f)
        val changed = base.copy(landmarks = base.landmarks.toMutableMap().apply {
            put(PoseLandmarkId.NOSE, sample(.70f, .15f))
            put(PoseLandmarkId.MOUTH_LEFT, sample(.69f, .18f))
        })
        val result = analyze(changed, ImageFacingDirection.IMAGE_RIGHT)
        assertEquals(ImageFacingDirection.IMAGE_RIGHT, result.facing)
        assertTrue("negative means fist below", result.guides.getValue(PunchHeightTargetType.GEDAN).signedErrorNormalized!! < 0)
    }

    @Test
    fun guideAvailabilityIsIndependent() {
        val result = analyze(frame(remove = setOf(PoseLandmarkId.LEFT_SHOULDER, PoseLandmarkId.RIGHT_SHOULDER,
            PoseLandmarkId.MOUTH_RIGHT)))
        assertEquals(HorizontalGuideState.AVAILABLE, result.guides.getValue(PunchHeightTargetType.JODAN).state)
        assertEquals(HorizontalGuideState.UNAVAILABLE, result.guides.getValue(PunchHeightTargetType.CHUDAN).state)
        assertEquals(HorizontalGuideState.AVAILABLE, result.guides.getValue(PunchHeightTargetType.GEDAN).state)
        assertTrue(result.closestReason.contains("compared=JODAN,GEDAN"))
    }

    @Test
    fun exactTieIsAmbiguousAndIdentityMismatchAbstains() {
        val tied = analyze(frame(fistY = .52f, shoulderLeftY = .32f, shoulderRightY = .32f, hipLeftY = .72f, hipRightY = .72f))
        // Chudan is .50; Gedan .72, so place fist at their midpoint .61 for an exact tie.
        val exact = analyze(frame(fistY = .61f, shoulderLeftY = .32f, shoulderRightY = .32f, hipLeftY = .72f, hipRightY = .72f,
            remove = setOf(PoseLandmarkId.NOSE, PoseLandmarkId.MOUTH_LEFT, PoseLandmarkId.MOUTH_RIGHT)))
        assertNotNull(tied)
        assertEquals(HorizontalClosestState.AMBIGUOUS, exact.closestState)
        val bad = StraightPunchHorizontalTargetAnalyzer.analyze(StraightPunchHorizontalTargetInput(
            frame(), 12, 1_834_000, "other-recording", "track", geometry, LateralSide.LEFT,
            dk.lasse.karateanalyzer.geometry.SourceNormalizedPoint(.2f, .4f),
        ))
        assertNull(bad)
    }

    @Test
    fun legacyChinTrackingRetainsObservedBelowPointSevenWhileHorizontalGuideAbstains() {
        val base = frame()
        val medium = PoseLandmarkSample(Point3(.35f, .15f, 0f), Point3(.35f, .15f, 0f), .60f, .60f, LandmarkSource.OBSERVED)
        val mediumMouth = PoseLandmarkSample(Point3(.36f, .18f, 0f), Point3(.36f, .18f, 0f), .60f, .60f, LandmarkSource.OBSERVED)
        val changed = base.copy(landmarks = base.landmarks.toMutableMap().apply {
            put(PoseLandmarkId.NOSE, medium)
            put(PoseLandmarkId.MOUTH_LEFT, mediumMouth)
            remove(PoseLandmarkId.MOUTH_RIGHT)
        })
        val body = BodyReference(VisibleSide.LEFT, Point3(.45f, .30f, 0f), Point3(.47f, .70f, 0f),
            Point3(0f, 1f, 0f), .40f, .8f)
        val legacy = SideViewChinEstimator().estimate(TrackedPoseFrame(changed.timestampMs, changed.landmarks), body,
            PunchHeightAnalyzer.DEFAULT_CHIN_PROJECTION_MULTIPLIER)
        assertEquals(ChinTrackingSource.FRESH_FACE, legacy.source)
        assertFalse(legacy.captureEligible)

        val horizontal = analyze(changed)
        assertEquals(HorizontalGuideState.UNAVAILABLE, horizontal.guides.getValue(PunchHeightTargetType.JODAN).state)
        assertEquals(HorizontalGuideState.AVAILABLE, horizontal.guides.getValue(PunchHeightTargetType.CHUDAN).state)
    }
}
