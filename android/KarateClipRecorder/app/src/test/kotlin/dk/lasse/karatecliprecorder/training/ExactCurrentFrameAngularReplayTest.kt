package dk.lasse.karatecliprecorder.training

import dk.lasse.karateanalyzer.capture.LateralSide
import dk.lasse.karateanalyzer.capture.qom.ExtremityPointComposer
import dk.lasse.karateanalyzer.capture.qom.MotionBodyProfile
import dk.lasse.karateanalyzer.core.*
import dk.lasse.karateanalyzer.geometry.CanonicalGeometryDescriptor
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Opt-in private replay; no captured user data or recording-specific acceptance threshold is committed. */
class ExactCurrentFrameAngularReplayTest {
    @Test fun privateReplayReportsCurrentFrameAngularEvidence() {
        val path = System.getenv("KARATE_PUNCH_DIAGNOSTIC_MLS")
        assumeTrue("Private exact replay MLS was not supplied", !path.isNullOrBlank())
        val frames = LandmarkFiles.read(File(path!!), expectedFormatId = LandmarkFiles.FORMAT_ID)
        val frameIndex = frames.indexOfFirst { it.timestampMs == 1_834L }
        assumeTrue("Requested private replay frame is unavailable", frameIndex >= 0)
        val frame = frames[frameIndex]
        val geometry = CanonicalGeometryDescriptor(
            geometryId = "geom_recovered_e9406fe1-90ba-4d0d-9558-ac6a518cc0db_rec",
            recordingId = "e9406fe1-90ba-4d0d-9558-ac6a518cc0db",
            landmarkTrackId = "4826f011-499f-4a12-b36a-d9b4b208f6a5",
            canonicalWidth = 1080,
            canonicalHeight = 1920,
        )
        val fist = assertNotNull(ExtremityPointComposer.compositeSourcePoint(
            frame, MotionBodyProfile.PUNCH_HAND_LANDMARKS_LEFT)).point
        val horizontal = assertNotNull(StraightPunchHorizontalTargetAnalyzer.analyze(
            StraightPunchHorizontalTargetInput(frame, frameIndex.toLong(), 1_834_000L,
                geometry.recordingId, geometry.landmarkTrackId!!, geometry, LateralSide.LEFT, fist,
                facing = ImageFacingDirection.IMAGE_LEFT,
                facingSource = "straight-punch-paired-arm-analyzer-v1:NOSE")))
        val angular = StraightPunchCurrentFrameAngularAnalyzer.analyze(
            StraightPunchCurrentFrameAngularInput(1_500_000L, frame, frameIndex.toLong(), horizontal,
                frames, geometry))
        assertEquals(CurrentFrameAngularState.RESOLVED, angular.state)
        assertEquals(PunchHeightTargetType.CHUDAN, angular.selectedTarget)
        println(listOf(
            "timestampUs=${angular.identity.timestampUs}",
            "frameIndexZeroBased=${angular.identity.frameIndex}",
            "frameOrdinalOneBased=${angular.identity.frameIndex + 1}",
            "shoulder=${angular.shoulder}",
            "fist=${angular.fist}",
            "reachRadiusAspectCorrect=${angular.reach.radiusAspectCorrect}",
            "upperArmP80AspectCorrect=${angular.reach.upperArmP80AspectCorrect}",
            "distalCompositeFistP80AspectCorrect=${angular.reach.distalCompositeFistP80AspectCorrect}",
            "reachSupportCount=${angular.reach.supportedTimestampsUs.size}",
            "actualAngleDeg=${angular.actualAngleDeg}",
            "selectedTarget=${angular.selectedTarget?.name}",
            "signedErrorDeg=${angular.signedErrorDeg}",
        ).joinToString("\n"))
    }
}
