package dk.lasse.karateanalyzer.impact

import dk.lasse.karateanalyzer.capture.LateralSide
import dk.lasse.karateanalyzer.capture.PoseReplayJson
import dk.lasse.karateanalyzer.capture.qom.MotionBodyProfile
import dk.lasse.karateanalyzer.capture.retrospective.RetrospectiveSegmenterConfig
import dk.lasse.karateanalyzer.capture.retrospective.RetrospectiveSessionSegmenter
import dk.lasse.karateanalyzer.geometry.CanonicalGeometryDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImpactAnalyzerRealMlsTest {
    @Test
    fun `committed real MLS punch segment replays deterministically`() {
        val stream = javaClass.classLoader.getResourceAsStream("fixtures/real-kihon-sample.fixture.json")
            ?: error("Committed real MLS fixture is unavailable")
        val fixture = PoseReplayJson.decode(stream.bufferedReader().use { it.readText() })
        val segmentation = RetrospectiveSessionSegmenter(
            RetrospectiveSegmenterConfig(profile = MotionBodyProfile.PUNCH),
        ).segment(fixture.sequenceId, "committed-fixture.mp4", fixture.frames)
        assertTrue(segmentation.movements.isNotEmpty())
        val movement = segmentation.movements.first()
        val width = requireNotNull(fixture.sourceWidth)
        val height = requireNotNull(fixture.sourceHeight)

        fun analyze(side: LateralSide): ImpactAnalysisResult = ImpactAnalyzer.analyze(
            ImpactMovementInput(
                movementId = "${fixture.sequenceId}-${movement.movementNumber}",
                logicalStartTimestampUs = movement.logicalStartTimestampMs * 1000L,
                logicalEndTimestampUs = movement.logicalEndTimestampMs * 1000L,
                evidenceStartTimestampUs = movement.retainedStartTimestampMs * 1000L,
                evidenceEndTimestampUs = movement.retainedEndTimestampMs * 1000L,
                frames = fixture.frames,
                qomTimeline = segmentation.qomTimeline,
                segmenterVersion = "activity_qom_hysteresis_v1",
                landmarkTrackId = "committed-real-mls-track",
                canonicalGeometry = CanonicalGeometryDescriptor(
                    geometryId = "committed-real-mls-geometry",
                    recordingId = fixture.sequenceId,
                    landmarkTrackId = "committed-real-mls-track",
                    canonicalWidth = width,
                    canonicalHeight = height,
                ),
                // Characterization-only fixture evidence; this is not a production body-scale derivation.
                bodyScale = BodyScaleEvidence(0.80, "fixture_characterization", "v1_not_calibrated"),
                profile = ImpactAnalysisProfile(
                    activityProfileId = "straight-punch-side-characterization-v1",
                    side = side,
                    weapon = WeaponPointDefinition.COMPOSITE_HAND,
                    limbFamily = ImpactLimbFamily.UPPER_LIMB,
                    approvedViewProfile = "recording-specific-characterization",
                ),
            ),
        )

        val left = analyze(LateralSide.LEFT)
        val right = analyze(LateralSide.RIGHT)
        assertEquals(left, analyze(LateralSide.LEFT))
        assertEquals(right, analyze(LateralSide.RIGHT))
        assertTrue(listOf(left, right).any { it.quality.usableSampleCount > 0 })
        listOf(left, right).filter { it.status == ImpactAnalysisStatus.COMPLETED }.forEach { result ->
            assertTrue(result.qomPeakTimestampUs!! <= result.terminalTransitionTimestampUs!!)
            assertTrue(result.terminalTransitionTimestampUs!! < result.stableWindowStartTimestampUs!!)
        }
    }
}
