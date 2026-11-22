package dk.lasse.karateanalyzer.core

import dk.lasse.karateanalyzer.capture.LateralSide
import dk.lasse.karateanalyzer.capture.qom.ExtremityPointComposer
import dk.lasse.karateanalyzer.capture.qom.MotionBodyProfile
import dk.lasse.karateanalyzer.geometry.CanonicalGeometryDescriptor
import dk.lasse.karateanalyzer.geometry.SourceNormalizedPoint
import org.junit.Assert.*
import org.junit.Test

class StraightPunchCurrentFrameAngularAnalyzerTest {
    private val geometry = CanonicalGeometryDescriptor(
        geometryId = "geometry", recordingId = "recording", landmarkTrackId = "track",
        canonicalWidth = 1000, canonicalHeight = 1000,
    )

    private fun sample(x: Float, y: Float, confidence: Float = .95f) = PoseLandmarkSample(
        Point3(x, y, 0f), Point3(x, y, 0f), confidence, confidence, LandmarkSource.OBSERVED)

    private fun frame(
        timestampMs: Long,
        facing: ImageFacingDirection = ImageFacingDirection.IMAGE_LEFT,
        fistY: Float = .5f,
        shoulderY: Float = .5f,
        includeShoulder: Boolean = true,
        fistXOverride: Float? = null,
    ): PoseFrame {
        val left = facing != ImageFacingDirection.IMAGE_RIGHT
        val shoulderX = if (left) .7f else .3f
        val elbowX = if (left) .55f else .45f
        val fistX = fistXOverride ?: if (left) .4f else .6f
        val landmarks = mutableMapOf<PoseLandmarkId, PoseLandmarkSample>()
        if (includeShoulder) landmarks[PoseLandmarkId.LEFT_SHOULDER] = sample(shoulderX, shoulderY)
        landmarks[PoseLandmarkId.LEFT_ELBOW] = sample(elbowX, (shoulderY + fistY) * .5f)
        listOf(PoseLandmarkId.LEFT_WRIST, PoseLandmarkId.LEFT_THUMB, PoseLandmarkId.LEFT_INDEX, PoseLandmarkId.LEFT_PINKY)
            .forEach { landmarks[it] = sample(fistX, fistY) }
        return PoseFrame(timestampMs, landmarks)
    }

    private fun horizontal(
        frame: PoseFrame,
        facing: ImageFacingDirection,
        points: Map<PunchHeightTargetType, SourceNormalizedPoint?>,
        frameIndex: Long = 3,
    ): StraightPunchHorizontalTargetResult {
        val fist = requireNotNull(ExtremityPointComposer.compositeSourcePoint(
            frame, MotionBodyProfile.PUNCH_HAND_LANDMARKS_LEFT)).point
        val guides = PunchHeightTargetType.entries.associateWith { target ->
            val point = points[target]
            if (point == null) HorizontalTargetGuide(target, HorizontalGuideState.UNAVAILABLE,
                estimatorVersion = "test", reason = "missing")
            else HorizontalTargetGuide(target, HorizontalGuideState.AVAILABLE, point,
                (point.y - fist.y).toDouble(), (point.y - fist.y) * 1000.0, "test", "available")
        }
        return StraightPunchHorizontalTargetResult(
            HorizontalTargetIdentity("recording", "track", "geometry", geometry.contractVersion,
                1000, 1000, frame.timestampMs * 1000L, frameIndex, LateralSide.LEFT),
            fist, StraightPunchHorizontalTargetAnalyzer.FIST_ESTIMATOR, null, null, guides,
            HorizontalClosestState.UNAVAILABLE, null, "not-used", facing, "paired-axis",
        )
    }

    private fun analyze(
        facing: ImageFacingDirection = ImageFacingDirection.IMAGE_LEFT,
        fistY: Float = .5f,
        shoulderY: Float = .5f,
        points: Map<PunchHeightTargetType, SourceNormalizedPoint?> = mapOf(
            PunchHeightTargetType.JODAN to SourceNormalizedPoint(.5f, .35f),
            PunchHeightTargetType.CHUDAN to SourceNormalizedPoint(.5f, .5f),
            PunchHeightTargetType.GEDAN to SourceNormalizedPoint(.5f, .65f),
        ),
        declared: String? = null,
        frameCount: Int = 4,
        includeShoulder: Boolean = true,
        fistXOverride: Float? = null,
    ): StraightPunchCurrentFrameAngularResult {
        val frames = (0 until frameCount).map { index ->
            frame(1_500L + index * 100L, facing, fistY, shoulderY, includeShoulder, fistXOverride)
        }
        val exact = frames.last()
        return StraightPunchCurrentFrameAngularAnalyzer.analyze(StraightPunchCurrentFrameAngularInput(
            movementStartUs = 1_500_000L,
            frame = exact,
            frameIndex = frames.lastIndex.toLong(),
            horizontal = horizontal(exact, facing, points, frames.lastIndex.toLong()),
            frames = frames,
            canonicalGeometry = geometry,
            declaredTargetId = declared,
        ))
    }

    @Test fun shortenedActualRayOnIdealDirectionIsZeroDegrees() {
        val result = analyze()
        assertEquals(CurrentFrameAngularState.RESOLVED, result.state)
        assertEquals(PunchHeightTargetType.CHUDAN, result.selectedTarget)
        assertEquals(0.0, result.signedErrorDeg!!, 1e-9)
        assertEquals(.3, result.reach.radiusAspectCorrect!!, 1e-6)
        assertEquals(4, result.reach.supportedTimestampsUs.size)
    }

    @Test fun mirrorPreservesHighAndLowSign() {
        val leftHigh = analyze(ImageFacingDirection.IMAGE_LEFT, fistY = .45f,
            points = PunchHeightTargetType.entries.associateWith { SourceNormalizedPoint(.5f, .5f) }, declared = "CHUDAN")
        val rightHigh = analyze(ImageFacingDirection.IMAGE_RIGHT, fistY = .45f,
            points = PunchHeightTargetType.entries.associateWith { SourceNormalizedPoint(.5f, .5f) }, declared = "CHUDAN")
        assertTrue(leftHigh.signedErrorDeg!! > 0)
        assertEquals(leftHigh.signedErrorDeg!!, rightHigh.signedErrorDeg!!, 1e-6)
        val low = analyze(fistY = .55f,
            points = PunchHeightTargetType.entries.associateWith { SourceNormalizedPoint(.5f, .5f) }, declared = "CHUDAN")
        assertTrue(low.signedErrorDeg!! < 0)
    }

    @Test fun horizontalBasisDoesNotRotateWithBodyLean() {
        val result = analyze(shoulderY = .42f, fistY = .42f,
            points = PunchHeightTargetType.entries.associateWith { SourceNormalizedPoint(.5f, .42f) }, declared = "CHUDAN")
        assertEquals(0.0, result.actualAngleDeg!!, 1e-9)
        assertEquals(0.0, result.signedErrorDeg!!, 1e-9)
    }

    @Test fun declaredUnavailableDoesNotSubstituteAnotherTarget() {
        val result = analyze(points = mapOf(
            PunchHeightTargetType.JODAN to null,
            PunchHeightTargetType.CHUDAN to SourceNormalizedPoint(.5f, .5f),
            PunchHeightTargetType.GEDAN to SourceNormalizedPoint(.5f, .6f),
        ), declared = "JODAN")
        assertEquals(CurrentFrameAngularState.UNAVAILABLE, result.state)
        assertEquals(PunchHeightTargetType.JODAN, result.selectedTarget)
        assertNull(result.signedErrorDeg)
        assertTrue(result.selectionReason.contains("declared_target_unavailable"))
    }

    @Test fun unreachableInvalidDeclarationFacingAndReachAreExplicit() {
        val unreachable = analyze(points = PunchHeightTargetType.entries.associateWith { SourceNormalizedPoint(.5f, .05f) }, declared = "CHUDAN")
        assertEquals(CurrentFrameAngularTargetState.UNREACHABLE,
            unreachable.targets.getValue(PunchHeightTargetType.CHUDAN).state)
        assertTrue(unreachable.selectionReason.contains("declared_target_unreachable"))

        val invalid = analyze(declared = "HEAD")
        assertEquals("HEAD", invalid.declaredTargetRaw)
        assertTrue(invalid.selectionReason.contains("invalid_declared_target:HEAD"))

        assertEquals("facing_unavailable", analyze(ImageFacingDirection.UNKNOWN).selectionReason)
        assertEquals("insufficient_supported_reach_samples", analyze(frameCount = 2).selectionReason)
        assertEquals("striking_shoulder_unavailable", analyze(includeShoulder = false).selectionReason)
        assertEquals("zero_actual_shoulder_fist_ray", analyze(fistXOverride = .7f).selectionReason)
        assertEquals("actual_fist_behind_resolved_facing", analyze(fistXOverride = .8f).selectionReason)
    }

    @Test fun openExactTieIsAmbiguousAndIdentityMismatchAbstains() {
        val tied = analyze(points = mapOf(
            PunchHeightTargetType.JODAN to SourceNormalizedPoint(.5f, .4f),
            PunchHeightTargetType.CHUDAN to null,
            PunchHeightTargetType.GEDAN to SourceNormalizedPoint(.5f, .4f),
        ))
        assertEquals(CurrentFrameAngularState.AMBIGUOUS, tied.state)

        val frames = (0..3).map { frame(1_500L + it * 100L) }
        val exact = frames.last()
        val mismatch = StraightPunchCurrentFrameAngularAnalyzer.analyze(StraightPunchCurrentFrameAngularInput(
            1_500_000L, exact, 99, horizontal(exact, ImageFacingDirection.IMAGE_LEFT,
                PunchHeightTargetType.entries.associateWith { SourceNormalizedPoint(.5f, .5f) }), frames, geometry))
        assertEquals("horizontal_exact_frame_identity_mismatch", mismatch.selectionReason)
    }
}
