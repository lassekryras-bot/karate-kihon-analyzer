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
    }

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
