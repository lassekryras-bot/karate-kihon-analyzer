package dk.lasse.karatecliprecorder.training

import dk.lasse.karateanalyzer.core.*
import dk.lasse.karateanalyzer.capture.LateralSide
import dk.lasse.karateanalyzer.impact.*
import dk.lasse.karateanalyzer.capture.qom.ExtremityPointComposer
import dk.lasse.karateanalyzer.capture.qom.MotionBodyProfile
import dk.lasse.karateanalyzer.geometry.CanonicalGeometryDescriptor
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StraightPunchMovementAdapterTest {

    private fun paired(movement: SessionMovement, geometry: CanonicalGeometryDescriptor) = StraightPunchPairedArmResult(
        movement.movementId, "test-track", geometry, movement.startUs, movement.endUs,
        StraightPunchPairedArmConfig(), StraightPunchPairedArmAnalyzer.VERSION,
        ForwardAxisEvidence(ForwardAxisStatus.RELIABLE, Point3(-1f, 0f, 0f), 1.0, 1.0,
            movement.endUs - movement.startUs, 4, "NOSE"),
        -0.3, movement.endUs - movement.startUs, 1.0, PairedArmSupportMode.BILATERAL,
        ArmPairedMotionEvidence(-.3, -.3, movement.endUs - movement.startUs, 1.0),
        ArmPairedMotionEvidence(0.0, 0.0, movement.endUs - movement.startUs, 1.0),
        LateralSide.LEFT, PairedArmDecisionReason.RESOLVED_PAIRED_SCORE, 3,
        movement.endUs - movement.startUs, 0, 0,
    )

    private fun sample(x: Float, y: Float) =
        PoseLandmarkSample(Point3(x, y, 0f), Point3(x, y, 0f), 0.95f, 0.95f, LandmarkSource.OBSERVED)

    private fun buildNeutralFrame(timestampMs: Long): PoseFrame {
        val map = mutableMapOf<PoseLandmarkId, PoseLandmarkSample>()
        map[PoseLandmarkId.NOSE] = sample(0.5f, 0.15f)
        map[PoseLandmarkId.MOUTH_LEFT] = sample(0.48f, 0.18f)
        map[PoseLandmarkId.MOUTH_RIGHT] = sample(0.52f, 0.18f)
        map[PoseLandmarkId.LEFT_EAR] = sample(0.45f, 0.15f)
        map[PoseLandmarkId.RIGHT_EAR] = sample(0.55f, 0.15f)
        map[PoseLandmarkId.LEFT_EYE] = sample(0.47f, 0.14f)
        map[PoseLandmarkId.RIGHT_EYE] = sample(0.53f, 0.14f)

        map[PoseLandmarkId.LEFT_SHOULDER] = sample(0.45f, 0.30f)
        map[PoseLandmarkId.RIGHT_SHOULDER] = sample(0.55f, 0.30f)
        map[PoseLandmarkId.LEFT_ELBOW] = sample(0.40f, 0.45f)
        map[PoseLandmarkId.RIGHT_ELBOW] = sample(0.60f, 0.45f)
        map[PoseLandmarkId.LEFT_WRIST] = sample(0.42f, 0.50f)
        map[PoseLandmarkId.RIGHT_WRIST] = sample(0.58f, 0.50f)
        map[PoseLandmarkId.LEFT_HIP] = sample(0.47f, 0.70f)
        map[PoseLandmarkId.RIGHT_HIP] = sample(0.53f, 0.70f)
        return PoseFrame(timestampMs, map)
    }

    private fun buildImpactFrame(timestampMs: Long, fistX: Float = 0.15f, fistY: Float = 0.45f): PoseFrame {
        val map = mutableMapOf<PoseLandmarkId, PoseLandmarkSample>()
        map[PoseLandmarkId.NOSE] = sample(0.5f, 0.15f)
        map[PoseLandmarkId.MOUTH_LEFT] = sample(0.48f, 0.18f)
        map[PoseLandmarkId.MOUTH_RIGHT] = sample(0.52f, 0.18f)
        map[PoseLandmarkId.LEFT_SHOULDER] = sample(0.45f, 0.30f)
        map[PoseLandmarkId.RIGHT_SHOULDER] = sample(0.55f, 0.30f)
        map[PoseLandmarkId.LEFT_ELBOW] = sample(0.30f, 0.38f)
        map[PoseLandmarkId.RIGHT_ELBOW] = sample(0.60f, 0.45f)
        map[PoseLandmarkId.LEFT_WRIST] = sample(fistX, fistY)
        map[PoseLandmarkId.LEFT_INDEX] = sample(fistX - 0.02f, fistY)
        map[PoseLandmarkId.LEFT_PINKY] = sample(fistX - 0.02f, fistY + 0.01f)
        map[PoseLandmarkId.RIGHT_WRIST] = sample(0.58f, 0.50f)
        map[PoseLandmarkId.LEFT_HIP] = sample(0.47f, 0.70f)
        map[PoseLandmarkId.RIGHT_HIP] = sample(0.53f, 0.70f)
        return PoseFrame(timestampMs, map)
    }

    @Test
    fun singleImpactFrameWithoutStableNeutralReferenceAbstains() {
        val impactMs = 1_000L
        val singleImpactFrame = buildImpactFrame(impactMs)

        val movement = SessionMovement(
            sessionId = "test-session",
            startUs = 800_000L,
            endUs = 1_200_000L,
            playbackStartUs = 600_000L,
            playbackEndUs = 1_400_000L,
            segmentationSource = "test",
            segmentationVersion = "1.0",
            analysisFrameUs = impactMs * 1000L,
        )

        // Only single impact frame supplied; no prior neutral initialization window
        val (analysis, results) = StraightPunchMovementAdapter.analyze(
            movement = movement,
            trackId = "test-track",
            frames = listOf(singleImpactFrame),
            videoWidth = 1080,
            videoHeight = 1920,
        )

        // Must NOT produce COMPLETED analysis
        assertNotEquals(AnalysisState.COMPLETED, analysis.state)
        assertEquals(AnalysisState.ABSTAINED, analysis.state)
        assertEquals("neutral_body_reference_unavailable", analysis.reason)
        assertTrue(results.isEmpty(), "No measurements should be published without stable neutral reference")
    }

    @Test
    fun impactAbstentionIsPreservedInsteadOfFallingBackToLegacyFrameSelection() {
        val movement = SessionMovement(
            sessionId = "test-session",
            startUs = 800_000L,
            endUs = 1_200_000L,
            playbackStartUs = 600_000L,
            playbackEndUs = 1_400_000L,
            segmentationSource = "test",
            segmentationVersion = "1.0",
            analysisFrameUs = 1_000_000L,
        )
        val impact = ImpactAnalysisResult(
            status = ImpactAnalysisStatus.ABSTAINED,
            movementId = movement.movementId,
            weaponId = WeaponPointDefinition.COMPOSITE_HAND,
            side = LateralSide.LEFT,
            limbFamily = ImpactLimbFamily.UPPER_LIMB,
            viewProfile = "approved-side",
            quality = ImpactQualityDiagnostics(1, 0, 0.0, null, null, null),
            provenance = ImpactAnalysisProvenance(
                landmarkTrackId = "test-track",
                recordingId = "recording",
                frameGeometryId = "geometry",
                frameGeometryContractVersion = "v1",
                sourceHash = null,
                trackHash = null,
                segmenterVersion = "segmenter-v1",
                bodyScaleSourceId = null,
                bodyScaleSourceVersion = null,
                analyzerVersion = ImpactAnalyzer.ANALYZER_VERSION,
                configurationVersion = ImpactAnalysisProfile.DEFAULT_CONFIG_VERSION,
            ),
            abstentionReason = ImpactAbstentionReason.BODY_SCALE_UNAVAILABLE,
        )

        val (analysis, results) = StraightPunchMovementAdapter.analyze(
            movement = movement,
            trackId = "test-track",
            frames = listOf(buildNeutralFrame(900L), buildImpactFrame(1_000L)),
            impactResult = impact,
        )

        assertEquals(AnalysisState.ABSTAINED, analysis.state)
        assertEquals("impact_analysis_BODY_SCALE_UNAVAILABLE", analysis.reason)
        assertTrue(results.isEmpty())
    }

    @Test
    fun completedImpactUsesExactRepresentativeAndPersistsHorizontalContract() {
        val movement = SessionMovement(
            sessionId = "test-session", startUs = 1_500_000, endUs = 2_000_000,
            playbackStartUs = 1_400_000, playbackEndUs = 2_100_000,
            segmentationSource = "test", segmentationVersion = "1",
        )
        val transition = buildImpactFrame(1_734, fistY = .60f)
        val representative = buildImpactFrame(1_834, fistY = .45f)
        val geometry = CanonicalGeometryDescriptor("geometry", recordingId = "recording",
            landmarkTrackId = "test-track", canonicalWidth = 1080, canonicalHeight = 1920)
        val expectedFist = requireNotNull(ExtremityPointComposer.compositeSourcePoint(
            representative, MotionBodyProfile.PUNCH_HAND_LANDMARKS_LEFT)).point
        val impact = ImpactAnalysisResult(
            status = ImpactAnalysisStatus.COMPLETED,
            movementId = movement.movementId,
            weaponId = WeaponPointDefinition.COMPOSITE_HAND,
            side = LateralSide.LEFT,
            limbFamily = ImpactLimbFamily.UPPER_LIMB,
            viewProfile = "approved-side",
            terminalTransitionTimestampUs = 1_734_000,
            stableRepresentativeTimestampUs = 1_834_000,
            stableRepresentativeWeaponPoint = expectedFist,
            quality = ImpactQualityDiagnostics(2, 2, 1.0, 1.0, 1.0, 1.0),
            provenance = ImpactAnalysisProvenance(
                landmarkTrackId = "test-track", recordingId = "recording",
                frameGeometryId = "geometry", frameGeometryContractVersion = geometry.contractVersion,
                sourceHash = null, trackHash = null, segmenterVersion = "segmenter-v1",
                bodyScaleSourceId = null, bodyScaleSourceVersion = null,
                analyzerVersion = ImpactAnalyzer.ANALYZER_VERSION,
                configurationVersion = ImpactAnalysisProfile.DEFAULT_CONFIG_VERSION,
            ),
        )

        val frames = listOf(buildImpactFrame(1_500), buildImpactFrame(1_600), transition, representative)
        val (analysis, results) = StraightPunchMovementAdapter.analyze(
            movement, "test-track", frames, impactResult = impact,
            resolvedSide = LateralSide.LEFT, canonicalGeometry = geometry, pairedArmEvidence = paired(movement, geometry),
        )

        assertEquals("4", analysis.analyzerVersion)
        assertNotEquals(AnalysisState.ABSTAINED, analysis.state)
        val persisted = requireNotNull(StraightPunchCurrentFrameAngularGeometryCodec.decode(analysis.geometryJson))
        assertEquals(1_834_000, persisted.timestampUs)
        assertEquals(3L, persisted.frameIndex)
        assertEquals(expectedFist.x, requireNotNull(persisted.fist).x)
        assertEquals(expectedFist.y, requireNotNull(persisted.fist).y)
        assertTrue(results.all { it.occurrenceUs == 1_834_000L && it.frameIndex == 3L })
        assertNotNull(results.single { it.measurementKey == TrainingMeasurements.PUNCH_CURRENT_FRAME_ANGULAR_ERROR_DEG }.numericValue)
        assertNotNull(results.single { it.measurementKey == TrainingMeasurements.PUNCH_CHUDAN_HEIGHT_ERROR_CANONICAL_PX }.numericValue)
    }

    @Test
    fun missingExactRepresentativeFrameDoesNotUseTransitionOrNearestFrame() {
        val movement = SessionMovement(
            sessionId = "test-session", startUs = 1_500_000, endUs = 2_000_000,
            playbackStartUs = 1_400_000, playbackEndUs = 2_100_000,
            segmentationSource = "test", segmentationVersion = "1",
        )
        val transition = buildImpactFrame(1_734)
        val geometry = CanonicalGeometryDescriptor("geometry", recordingId = "recording",
            landmarkTrackId = "test-track", canonicalWidth = 1080, canonicalHeight = 1920)
        val fist = requireNotNull(ExtremityPointComposer.compositeSourcePoint(
            transition, MotionBodyProfile.PUNCH_HAND_LANDMARKS_LEFT)).point
        val impact = ImpactAnalysisResult(
            status = ImpactAnalysisStatus.COMPLETED, movementId = movement.movementId,
            weaponId = WeaponPointDefinition.COMPOSITE_HAND, side = LateralSide.LEFT,
            limbFamily = ImpactLimbFamily.UPPER_LIMB, viewProfile = "approved-side",
            stableRepresentativeTimestampUs = 1_834_000, stableRepresentativeWeaponPoint = fist,
            quality = ImpactQualityDiagnostics(1, 1, 1.0, 1.0, 1.0, 1.0),
            provenance = ImpactAnalysisProvenance("test-track", "recording", "geometry", geometry.contractVersion,
                null, null, "segmenter", null, null, ImpactAnalyzer.ANALYZER_VERSION,
                ImpactAnalysisProfile.DEFAULT_CONFIG_VERSION),
        )
        val (analysis, results) = StraightPunchMovementAdapter.analyze(
            movement, "test-track", listOf(transition), impactResult = impact,
            resolvedSide = LateralSide.LEFT, canonicalGeometry = geometry,
        )
        assertEquals(AnalysisState.ABSTAINED, analysis.state)
        assertEquals("exact_impact_frame_unavailable", analysis.reason)
        assertTrue(results.isEmpty())
    }
}
