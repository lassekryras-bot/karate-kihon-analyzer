package dk.lasse.karatecliprecorder.movement

import dk.lasse.karateanalyzer.geometry.CanonicalGeometryCodec
import dk.lasse.karateanalyzer.geometry.CanonicalGeometryDescriptor
import dk.lasse.karatecliprecorder.training.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MovementMotionInspectionTest {
    private val movement = SessionMovement(
        movementId = "movement", sessionId = "session", startUs = 100_000, endUs = 900_000,
        playbackStartUs = 0, playbackEndUs = 1_000_000, segmentationSource = "test", segmentationVersion = "qom-test",
    )
    private val recording = MasterRecording(recordingId = "recording", sessionId = "session", filePath = "recording.mp4", createdAtMs = 1)
    private val geometry = CanonicalGeometryDescriptor(
        geometryId = "geometry", recordingId = recording.recordingId, landmarkTrackId = "track",
        canonicalWidth = 1000, canonicalHeight = 1000,
    )

    @Test fun `inspection reads valid v1 without inventing mode and v2 with supporting episode`() {
        val v1 = payload("movement-motion-v1")
        val readV1 = assertNotNull(MovementMotionInspection.read(evidence("1", v1)))
        assertFalse(readV1.has("sideEvidenceMode"))
        assertFalse(MovementMotionInspection.rows(readV1).any { it.startsWith("Side evidence mode:") })

        val episode = JSONObject()
            .put("limb", "LEFT_ARM").put("episodeIndex", 0).put("block", 0)
            .put("startUs", 200_000).put("endUs", 500_000)
            .put("excursionDeg", 90.0).put("travelDeg", 90.0).put("observedSupportUs", 300_000)
            .put("characterizerVersion", "four-limb-motion-v2")
            .put("angularPolicyVersion", "angular-excursion-hysteresis-v1-provisional")
            .put("resolverVersion", "activity-starting-side-v2")
        val v2 = payload("movement-motion-v2")
            .put("sideEvidenceMode", "SINGLE_ARM_ONLY")
            .put("sideSupportingEpisode", episode)
        val readV2 = assertNotNull(MovementMotionInspection.read(evidence("2", v2)))
        assertEquals("SINGLE_ARM_ONLY", readV2.getString("sideEvidenceMode"))
        assertEquals(200_000L, readV2.getJSONObject("sideSupportingEpisode").getLong("startUs"))
        assertContains(MovementMotionInspection.rows(readV2), "Side evidence mode: SINGLE_ARM_ONLY")
        assertTrue(MovementMotionInspection.rows(readV2).any { it.contains("Supporting episode: LEFT_ARM") })
    }

    @Test fun `plots preserve retained gaps logical bounds and missing arm evidence`() {
        val profiles = JSONArray()
            .put(profile("LEFT_ARM", JSONArray()
                .put(sample(200_000, 145.0, 0)).put(sample(300_000, 120.0, 0))
                .put(JSONObject().put("timeUs", 400_000).put("angleDeg", JSONObject.NULL).put("block", JSONObject.NULL))
                .put(sample(600_000, 100.0, 1)).put(sample(700_000, 130.0, 1))))
            .put(profile("RIGHT_ARM", JSONArray()
                .put(JSONObject().put("timeUs", 250_000).put("angleDeg", JSONObject.NULL).put("block", JSONObject.NULL))))
        val read = assertNotNull(MovementMotionInspection.read(evidence("2", payload("movement-motion-v2").put("profiles", profiles))))

        val plots = MovementMotionInspection.plots(read)
        val left = assertNotNull(plots["LEFT_ARM_angle"])
        assertEquals(2, left.sampleSegments.size)
        assertEquals(listOf(2, 2), left.sampleSegments.map { it.size })
        assertEquals(movement.startUs, left.logicalStartUs)
        assertEquals(movement.endUs, left.logicalEndUs)
        assertContains(left.accessibleSummary, "Missing evidence is shown as gaps")
        assertFalse(plots.containsKey("RIGHT_ARM_angle"), "missing right-arm evidence must not become a zero trace")
    }

    @Test fun `unavailable sample splits a retained block rather than interpolating across it`() {
        val samples = JSONArray()
            .put(sample(200_000, 145.0, 0)).put(sample(300_000, 120.0, 0))
            .put(JSONObject().put("timeUs", 400_000).put("angleDeg", JSONObject.NULL).put("block", JSONObject.NULL))
            .put(sample(500_000, 100.0, 0)).put(sample(600_000, 130.0, 0))
        val read = assertNotNull(MovementMotionInspection.read(evidence("2", payload("movement-motion-v2")
            .put("profiles", JSONArray().put(profile("LEFT_ARM", samples))))))

        val plot = assertNotNull(MovementMotionInspection.plots(read)["LEFT_ARM_angle"])
        assertEquals(listOf(listOf(200_000L, 300_000L), listOf(500_000L, 600_000L)),
            plot.sampleSegments.map { segment -> segment.map { it.timestampUs } })
    }

    @Test fun `legacy retained samples still produce an accessible elbow plot`() {
        val profiles = JSONArray().put(profile("RIGHT_ARM", JSONArray()
            .put(sample(200_000, 90.0, 0)).put(sample(300_000, 110.0, 0))))
        val read = assertNotNull(MovementMotionInspection.read(evidence("1", payload("movement-motion-v1").put("profiles", profiles))))
        val plot = assertNotNull(MovementMotionInspection.plots(read)["RIGHT_ARM_angle"])
        assertEquals("Right elbow angle", plot.label)
        assertEquals("°", plot.unit)
    }

    @Test fun `plot affordance stays in limb inspection rather than product key results`() {
        val profiles = JSONArray().put(profile("LEFT_ARM", JSONArray()
            .put(sample(200_000, 90.0, 0)).put(sample(300_000, 110.0, 0))))
        val mapped = MovementPresentationMapper.map(evidence("2", payload("movement-motion-v2").put("profiles", profiles)), 1)

        assertEquals(listOf("LEFT_ARM_angle"), mapped.motionInspectionPlotLinks.map { it.id })
        assertTrue(mapped.motionInspectionPlotLinks.single().isAvailable)
        assertEquals(PlayerMode.GRAPH, mapped.motionInspectionPlotLinks.single().preferredMode)
        assertFalse(mapped.keyResults.any { it.id == "LEFT_ARM_angle" })
    }

    private fun profile(limb: String, samples: JSONArray) = JSONObject()
        .put("limb", limb).put("pattern", "INSUFFICIENT_EVIDENCE").put("samples", samples)

    private fun sample(timeUs: Long, angleDeg: Double, block: Int) = JSONObject()
        .put("timeUs", timeUs).put("angleDeg", angleDeg).put("block", block)

    private fun payload(contract: String) = JSONObject()
        .put("contract", contract)
        .put("movementId", movement.movementId)
        .put("trackId", "track")
        .put("geometry", CanonicalGeometryCodec.encode(geometry))
        .put("logicalStartUs", movement.startUs)
        .put("logicalEndUs", movement.endUs)
        .put("activity", "STRAIGHT_PUNCH")
        .put("side", "LEFT")
        .put("sideReason", "SINGLE_SUPPORTED_SIDE")
        .put("alternationStatus", "UNVERIFIABLE")
        .put("impactStatus", "NOT_EVALUATED")
        .put("impactReason", "UNSUPPORTED_ANALYSIS_PROFILE")
        .put("profiles", JSONArray())

    private fun evidence(version: String, payload: JSONObject) = MovementEvidence(
        movement = movement,
        recording = recording,
        observation = null,
        labels = emptyList(),
        events = emptyList(),
        analyses = listOf(MovementAnalysis(
            movementId = movement.movementId,
            analyzerKey = MovementMotionAnalysis.policy.analyzerKey,
            analyzerVersion = version,
            landmarkTrackId = "track",
            state = AnalysisState.PARTIAL,
            geometryJson = payload.toString(),
        )),
        measurements = emptyList(),
        landmarkTracks = emptyList(),
    )
}
