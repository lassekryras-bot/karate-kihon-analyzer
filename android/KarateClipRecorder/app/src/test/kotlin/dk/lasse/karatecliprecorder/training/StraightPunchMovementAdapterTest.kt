package dk.lasse.karatecliprecorder.training

import dk.lasse.karateanalyzer.core.*
import dk.lasse.karateanalyzer.capture.LateralSide
import dk.lasse.karateanalyzer.impact.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StraightPunchMovementAdapterTest {

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
}
