package dk.lasse.karatecliprecorder.training

import dk.lasse.karateanalyzer.core.*
import dk.lasse.karateanalyzer.geometry.*
import dk.lasse.karateanalyzer.motion.*
import dk.lasse.karateanalyzer.impact.*
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*
import kotlin.math.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MovementMotionAnalysisTest {
    private val geometry = CanonicalGeometryDescriptor("g", recordingId = "r", landmarkTrackId = "t", canonicalWidth = 1000, canonicalHeight = 1000)
    private val movement = SessionMovement(sessionId = "s", startUs = 0, endUs = 1_000_000, playbackStartUs = 0,
        playbackEndUs = 1_000_000, segmentationSource = "test", segmentationVersion = "qom-test")
    private fun frames() = (0L..1000L step 10).map { time ->
        val samples = mutableMapOf<PoseLandmarkId, PoseLandmarkSample>()
        fun sample(x: Double, y: Double) = PoseLandmarkSample(Point3(x.toFloat(), y.toFloat(), 0f),
            visibility = .95f, presence = .95f, source = LandmarkSource.OBSERVED)
        LimbId.entries.forEach { limb ->
            val x = if (limb.ordinal % 2 == 0) .3 else .7
            val y = if (limb.ordinal < 2) .3 else .7
            val progress = ((time - 200) / 600.0).coerceIn(0.0, 1.0)
            val angle = Math.toRadians(when(limb) { LimbId.LEFT_ARM -> 40 + 100 * progress; LimbId.RIGHT_ARM -> 150 - 100 * progress; else -> 150.0 })
            samples[limb.proximal] = sample(x + .1, y)
            samples[limb.joint] = sample(x, y)
            samples[limb.distal] = sample(x + .1 * cos(angle), y + .1 * sin(angle))
        }
        PoseFrame(time, samples)
    }

    @Test fun `known plan resolves side but missing calibration blocks impact and retains evidence`() {
        val result = MovementMotionAnalysis.analyze(movement, "t", frames(), geometry,
            MotionActivityPlans.context(MotionActivityPlans.ALTERNATING_PUNCH), emptyList())
        assertEquals(dk.lasse.karateanalyzer.capture.LateralSide.LEFT, result.side?.side)
        assertEquals(ImpactAbstentionReason.BODY_SCALE_UNAVAILABLE, result.impact?.abstentionReason)
        val json = JSONObject(result.analysis.geometryJson!!)
        assertEquals(movement.movementId, json.getString("movementId"))
        assertEquals(4, json.getJSONArray("profiles").length())
        assertEquals(101, json.getJSONArray("profiles").getJSONObject(0).getJSONArray("samples").length())
        assertEquals("t", json.getString("trackId"))
    }

    @Test fun `unknown activity retains generic evidence without inferring a plan`() {
        val result = MovementMotionAnalysis.analyze(movement, "t", frames(), geometry, null, emptyList())
        assertNull(result.side)
        assertNull(result.impact)
        assertNotNull(result.limbs.leftArm.metrics)
        assertEquals("ACTIVITY_PLAN_UNAVAILABLE", result.analysis.reason)
    }

    @Test fun `calibration round trips provenance and revoking latest never revives older reference`() {
        val c = ImageBodyScaleCalibration("c", geometry, 100_000, 10, SourceNormalizedPoint(.4f, .1f),
            SourceNormalizedPoint(.6f, .9f), 0, 2_000_000, true)
        val encoded = ImageBodyScaleStore.encode(c)
        assertEquals(c, ImageBodyScaleStore.decode(encoded))
        fun event(id: String, saved: Long) = SessionEvent(sessionId = "s", type = ImageBodyScaleStore.EVENT, timestampUs = 100_000,
            data = JSONObject(ImageBodyScaleStore.encode(c.copy(calibrationId = id))).put("savedAtMs", saved).toString())
        val events = listOf(event("old", 1), event("new", 2))
        assertEquals("new", ImageBodyScaleStore.current(events)?.calibrationId)
        assertNull(ImageBodyScaleStore.current(events + SessionEvent(sessionId = "s", type = ImageBodyScaleStore.REVOKED, timestampUs = 100_000, data = "new")))
        val scale = ImageBodyScaleProvider.evidence(c, geometry, movement.startUs, movement.endUs)
        val result = MovementMotionAnalysis.analyze(movement, "t", frames(), geometry,
            MotionActivityPlans.context(MotionActivityPlans.ALTERNATING_PUNCH), emptyList(), bodyScale = scale, calibration = c)
        assertNotEquals(ImpactAbstentionReason.BODY_SCALE_UNAVAILABLE, result.impact?.abstentionReason)
        assertEquals("c", result.impact?.provenance?.bodyScaleSourceId)
        assertEquals("c", JSONObject(result.analysis.geometryJson!!).getJSONObject("calibration").getString("id"))
    }
}
