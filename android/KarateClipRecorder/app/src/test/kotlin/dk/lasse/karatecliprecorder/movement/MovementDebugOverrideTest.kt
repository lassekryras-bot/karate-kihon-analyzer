package dk.lasse.karatecliprecorder.movement

import dk.lasse.karateanalyzer.core.*
import dk.lasse.karateanalyzer.geometry.CanonicalGeometryCodec
import dk.lasse.karateanalyzer.geometry.CanonicalGeometryDescriptor
import dk.lasse.karateanalyzer.geometry.ImageBodyScaleCalibration
import dk.lasse.karateanalyzer.geometry.ImageBodyScaleProvider
import dk.lasse.karateanalyzer.geometry.SourceNormalizedPoint
import dk.lasse.karateanalyzer.motion.LimbId
import dk.lasse.karatecliprecorder.training.*
import dk.lasse.karatecliprecorder.training.LandmarkTrack
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MovementDebugOverrideTest {
    private val geometry = CanonicalGeometryDescriptor(
        geometryId = "geometry",
        recordingId = "recording",
        landmarkTrackId = "track",
        canonicalWidth = 1000,
        canonicalHeight = 1000,
    )
    private val movement = SessionMovement(
        movementId = "movement",
        sessionId = "session",
        startUs = 0,
        endUs = 1_000_000,
        playbackStartUs = 0,
        playbackEndUs = 1_000_000,
        segmentationSource = "test",
        segmentationVersion = "segmenter-1",
        segmentationTrackId = "track",
    )

    private fun frames() = (0L..1000L step 10).map { time ->
        val samples = mutableMapOf<PoseLandmarkId, PoseLandmarkSample>()
        fun sample(x: Double, y: Double) = PoseLandmarkSample(
            position = Point3(x.toFloat(), y.toFloat(), 0f),
            visibility = .95f,
            presence = .95f,
            source = LandmarkSource.OBSERVED,
        )
        LimbId.entries.forEach { limb ->
            val x = if (limb.ordinal % 2 == 0) .3 else .7
            val y = if (limb.ordinal < 2) .3 else .7
            val progress = ((time - 200) / 600.0).coerceIn(0.0, 1.0)
            val angle = Math.toRadians(if (limb == LimbId.LEFT_ARM) 40 + 100 * progress else 150 - 100 * progress)
            samples[limb.proximal] = sample(x + .1, y)
            samples[limb.joint] = sample(x, y)
            samples[limb.distal] = sample(x + .1 * cos(angle), y + .1 * sin(angle))
        }
        PoseFrame(time, samples)
    }

    private fun evidence(frames: List<PoseFrame> = frames()): MovementEvidence {
        val original = MovementMotionAnalysis.analyze(movement, "track", frames, geometry, null, emptyList()).analysis
        return MovementEvidence(
            movement = movement,
            recording = MasterRecording(
                recordingId = "recording",
                sessionId = "session",
                filePath = "unused",
                createdAtMs = 1,
                width = 1000,
                height = 1000,
                sourceState = SourceState.AVAILABLE,
            ),
            observation = null,
            labels = emptyList(),
            events = emptyList(),
            analyses = listOf(original),
            measurements = emptyList(),
            landmarkTracks = listOf(LandmarkTrack(
                landmarkTrackId = "track",
                recordingId = "recording",
                pipelineKey = "fixture",
                pipelineVersion = "1",
                configuration = "fixture",
                filePath = "unused",
                state = ProcessingState.COMPLETED,
                sourceState = SourceState.AVAILABLE,
                canonicalGeometryJson = CanonicalGeometryCodec.encode(geometry),
            )),
        )
    }

    @Test
    fun overrideSuppliesOnlyNonAlternatingActivityAndKeepsImpactGated() {
        val frames = frames()
        val outcome = MovementDebugOverrideEvaluator.evaluate(
            DebugActivityOverride.STRAIGHT_PUNCH, evidence(frames), emptyList(), frames, "track",
        )
        val result = assertIs<MovementDebugOverrideOutcome.Evaluated>(outcome).result

        assertFalse(result.context.alternating)
        assertEquals(MovementDebugOverrideEvaluator.DEBUG_PLAN_ID, result.context.planId)
        val motion = JSONObject(result.evaluated.motion.analysis.geometryJson!!)
        assertEquals("STRAIGHT_PUNCH", motion.getString("activity"))
        assertEquals("UNSUPPORTED_ANALYSIS_PROFILE", motion.getString("impactReason"))
        assertEquals("NOT_EVALUATED", motion.getString("impactStatus"))
        assertNull(result.evaluated.motion.impact)
        assertEquals(AnalysisState.ABSTAINED, result.evaluated.downstream.single().analysis.state)
    }

    @Test
    fun overrideRejectsNonMatchingTrackAndNormalBuildPolicy() {
        val frames = frames()
        val outcome = MovementDebugOverrideEvaluator.evaluate(
            DebugActivityOverride.STRAIGHT_PUNCH, evidence(frames), emptyList(), frames, "different-track",
        )
        assertEquals(
            "MATCHING_LANDMARK_TRACK_NOT_LOADED",
            assertIs<MovementDebugOverrideOutcome.Unavailable>(outcome).reason,
        )
        assertFalse(MovementDebugToolPolicy.enabled(debugBuild = false, developerMode = true))
        assertFalse(MovementDebugToolPolicy.enabled(debugBuild = true, developerMode = false))
        assertTrue(MovementDebugToolPolicy.enabled(debugBuild = true, developerMode = true))
    }

    @Test
    fun generationGateRejectsResultsAfterClearReloadOrNavigation() {
        val gate = MovementDebugEvaluationGate()
        val first = gate.begin()
        gate.invalidate()
        assertFalse(gate.accepts(first))
        val second = gate.begin()
        assertTrue(gate.accepts(second))
        val third = gate.begin()
        assertFalse(gate.accepts(second))
        assertTrue(gate.accepts(third))
    }

    @Test
    fun reportKeepsHistoricalMissingActivitySeparateFromLaterSessionDeclaration() {
        val evidence = evidence(emptyList())
        val presentation = MovementPresentationMapper.map(evidence, 1, emptyList())
        val later = SessionEvent(
            sessionEventId = "later-event",
            sessionId = "session",
            type = MotionActivityPlans.EVENT_TYPE,
            timestampUs = 2_000_000,
            data = MotionActivityPlans.ALTERNATING_PUNCH,
            timingSource = MotionActivityPlans.OPERATOR_POST_CAPTURE_ORIGIN,
        )
        val report = MovementDebugReportFactory.create(evidence, listOf(later), presentation)
        val values = report.requireSection("debug_overrides").entries.associate { it.key to it.value.asPlainText() }

        assertEquals("UNAVAILABLE", values["activity_original"])
        assertEquals("ACTIVITY_PLAN_UNAVAILABLE", values["activity_original_reason"])
        assertEquals("ASSIGNED", values["current_session_plan_state"])
        assertEquals("STRAIGHT_PUNCH", values["current_session_activity"])
        assertEquals("later-event", values["current_session_plan_event_id"])
        assertEquals("UNAVAILABLE", values["activity_source"])
        val analysis = report.requireSection("analysis").entries.associate { it.key to it.value.asPlainText() }
        assertEquals("UNAVAILABLE", analysis["effective_representative_timestamp_us"])
        assertEquals("PRESENTATION_RECOMPUTED_SHARED_MODEL", analysis["presentation_source"])
        assertTrue(report.renderSectionPlainText("terminal_impact").contains("Retained impact evidence contract [impact_evidence_contract]: UNAVAILABLE"))
    }

    @Test
    fun reportIncludesRetainedImpactTrackMotionAndCalibrationProvenanceInEveryAdapter() {
        val inputFrames = frames()
        val context = MotionActivityPlans.context(MotionActivityPlans.ALTERNATING_PUNCH)!!
        val calibration = ImageBodyScaleCalibration(
            "calibration", geometry, 100_000, 10,
            SourceNormalizedPoint(.4f, .1f), SourceNormalizedPoint(.6f, .9f),
            0, 2_000_000, true,
        )
        val motion = MovementMotionAnalysis.analyze(
            movement = movement,
            trackId = "track",
            frames = inputFrames,
            geometry = geometry,
            context = context,
            qom = emptyList(),
            bodyScale = ImageBodyScaleProvider.evidence(calibration, geometry, movement.startUs, movement.endUs),
            calibration = calibration,
            viewApproval = ImpactViewApproval(context.planId, "test-side-view-v1", "fixture-view-approval"),
        )
        val evidence = evidence(inputFrames).copy(analyses = listOf(motion.analysis))
        val report = MovementDebugReportFactory.create(
            evidence,
            emptyList(),
            MovementPresentationMapper.map(evidence, 1, inputFrames),
        )
        val terminal = report.renderSectionPlainText("terminal_impact")
        val provenance = report.renderSectionPlainText("provenance")

        assertTrue(terminal.contains("Retained impact evidence contract [impact_evidence_contract]: impact-analysis-evidence"))
        assertTrue(terminal.contains("Retained impact evidence viewApproval.source [impact_evidence_viewApproval_source]: fixture-view-approval"))
        assertTrue(terminal.contains("Retained impact evidence profile.configVersion"))
        assertTrue(terminal.contains("Retained impact evidence result.quality.trackingCoverage"))
        assertTrue(terminal.contains("Retained impact evidence result.provenance.analyzerVersion"))
        assertTrue(provenance.contains("Track pipeline version [track_pipeline_version]: 1"))
        assertTrue(provenance.contains("Motion configuration version [motion_config_version]"))
        assertTrue(provenance.contains("Body scale provenance sourceId [body_scale_sourceId]: calibration"))
        assertTrue(provenance.contains("Image-height calibration id [image_height_calibration_id]: calibration"))
        assertTrue(report.toPlainText().contains(terminal))
        assertTrue(report.toMarkdown().contains("impact-analysis-evidence"))
        assertTrue(report.toMarkdown().contains("fixture-view-approval"))
    }
}
