package dk.lasse.karatecliprecorder.movement

import dk.lasse.karateanalyzer.core.*
import dk.lasse.karatecliprecorder.training.*
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MovementEvidenceRegressionTest {
    private val movement = SessionMovement(sessionId = "s", startUs = 0L, endUs = 1_000_000L,
        playbackStartUs = 0L, playbackEndUs = 1_000_000L, analysisFrameUs = 400_000L,
        segmentationSource = "fixture", segmentationVersion = "1")
    private fun analysis(id: String, state: AnalysisState = AnalysisState.COMPLETED, geometry: String? = null) =
        MovementAnalysis(analysisId = id, movementId = movement.movementId,
            analyzerKey = StraightPunchMovementAdapter.policy.analyzerKey, analyzerVersion = "1",
            landmarkTrackId = "track-$id", state = state, geometryJson = geometry)
    private fun evidence(analyses: List<MovementAnalysis>, measurements: List<MeasurementResult> = emptyList()) =
        MovementEvidence(movement, MasterRecording(sessionId = "s", filePath = "unused.mp4", createdAtMs = 0),
            null, emptyList(), emptyList(), analyses, measurements, emptyList())

    private fun geometry(): String = assertNotNull(StraightPunchGeometryCodec.encode(
        StraightPunchTargetEvaluation(TargetRayState.VALID, ActiveArm.LEFT, 500L,
            shoulderPoint = Point3(0.4f, 0.5f, 0f), closestTarget = PunchHeightTargetType.CHUDAN,
            targetResults = mapOf(PunchHeightTargetType.CHUDAN to TargetRayEvaluation(
                PunchHeightTargetType.CHUDAN, TargetRayState.VALID, idealEndpoint = Point3(0.8f, 0.5f, 0f)
            ))), 15L))

    @Test fun selectedAnalysisOwnsItsMeasurementsGeometryAndSide() {
        val old = analysis("old", geometry = geometry()).copy(createdAtMs = 10)
        val newer = analysis("new", AnalysisState.ABSTAINED).copy(createdAtMs = 20)
        fun target(a: MovementAnalysis, value: String?, side: BodySide) = MeasurementResult(
            analysisId = a.analysisId, measurementKey = TrainingMeasurements.PUNCH_CLOSEST_TARGET,
            calculationVersion = "1", valueType = ValueType.CATEGORICAL, categoricalValue = value,
            side = side, state = if (value == null) ResultState.ABSTAINED else ResultState.VALID,
            occurrenceUs = 500_000L)
        val data = MovementPresentationMapper.map(evidence(listOf(newer, old), listOf(
            target(newer, null, BodySide.RIGHT), target(old, "CHUDAN", BodySide.LEFT))), 1)
        assertEquals("track-old", data.debugData.landmarkTrackId)
        assertEquals("old", data.debugData.analysisId)
        assertTrue(data.keyResults.first { it.id == "target_height" }.isAvailable)
        assertEquals(setOf("old"), data.debugData.measurements.map { it.analysisId }.toSet())
        assertEquals("LEFT", data.debugData.activeArm)
        assertEquals(500_000L, data.canonicalImpactUs)
        assertEquals(15L, data.debugData.canonicalFrameIndex)
        assertEquals(0.8f, data.overlayDefinition!!.rays.single().targetPoint!!.x)
    }

    @Test fun unsupportedNewerVersionsCannotSupplyEvidence() {
        val approved = analysis("approved", geometry = geometry()).copy(createdAtMs = 10)
        val unsupported = analysis("unsupported").copy(analyzerVersion = "99", createdAtMs = 20)
        assertEquals(approved, evidence(listOf(unsupported, approved)).presentationAnalysis())
        assertNull(evidence(listOf(unsupported)).presentationAnalysis())
    }

    @Test fun retainedGeometryWorksWithoutLandmarkFrames() {
        val data = MovementPresentationMapper.map(evidence(listOf(analysis("a", geometry = geometry()))), 1)
        assertEquals(1, data.overlayDefinition!!.rays.size)
        assertNull(data.overlayDefinition.arm)
        assertEquals("Landmark playback is unavailable. Showing retained target geometry.", data.analysisNotice)
    }

    @Test fun malformedGeometryIsRejectedAtomically() {
        val valid = geometry()
        val invalid = listOf(
            "{broken",
            JSONObject(valid).apply { remove("origin") }.toString(),
            JSONObject(valid).put("contract", "unknown_v2").toString(),
            JSONObject(valid).put("coordinateSpace", "world_metres").toString(),
            JSONObject(valid).put("activeSide", "UNKNOWN").toString(),
            JSONObject(valid).put("timestampUs", -1).toString(),
            JSONObject(valid).apply { getJSONArray("rays").put(JSONObject().put("targetType", "JODAN")) }.toString(),
            JSONObject(valid).apply { getJSONObject("origin").put("x", "NaN") }.toString(),
            JSONObject(valid).apply { getJSONObject("origin").put("x", 1e100) }.toString(),
            JSONObject(valid).apply { remove("contract") }.toString(),
        )
        invalid.forEach { json ->
            assertNull(StraightPunchGeometryCodec.decode(json), json)
            val data = MovementPresentationMapper.map(evidence(listOf(analysis("a", geometry = json))), 1)
            assertNull(data.overlayDefinition, json)
            assertNotNull(data.analysisNotice)
        }
    }

    @Test fun geometryOutsideTheRetainedMovementIsUnavailable() {
        val json = JSONObject(geometry()).put("timestampUs", 9_000_000L).toString()
        val data = MovementPresentationMapper.map(evidence(listOf(analysis("a", geometry = json))), 1)
        assertNull(data.overlayDefinition)
    }

    @Test fun failedAnalysisReasonRemainsDiagnosticAndHasNoTechniqueFinding() {
        val failed = analysis("a", AnalysisState.FAILED).copy(reason = "missing_required_arm_landmarks")
        val data = MovementPresentationMapper.map(evidence(listOf(failed)), 1)
        assertEquals(AnalysisState.FAILED, data.debugData.analysisState)
        assertEquals(failed.reason, data.debugData.reason)
        assertTrue(data.findings.isEmpty())
        assertNotNull(data.analysisNotice)
    }
}
