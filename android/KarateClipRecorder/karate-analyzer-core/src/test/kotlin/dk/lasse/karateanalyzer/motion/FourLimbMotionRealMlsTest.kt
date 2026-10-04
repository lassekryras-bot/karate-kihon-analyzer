package dk.lasse.karateanalyzer.motion

import dk.lasse.karateanalyzer.capture.PoseReplayJson
import dk.lasse.karateanalyzer.capture.qom.MotionBodyProfile
import dk.lasse.karateanalyzer.capture.retrospective.RetrospectiveSegmenterConfig
import dk.lasse.karateanalyzer.capture.retrospective.RetrospectiveSessionSegmenter
import dk.lasse.karateanalyzer.geometry.CanonicalGeometryDescriptor
import java.io.File
import kotlin.math.abs
import kotlin.test.*

class FourLimbMotionRealMlsTest {
    @Test fun `sparse geometry fixture cannot masquerade as continuous movement evidence`() {
        val text = javaClass.classLoader.getResourceAsStream("fixtures/real-kihon-sample.fixture.json")!!
            .bufferedReader().use { it.readText() }
        val fixture = PoseReplayJson.decode(text)
        // This legacy resource contains five short geometry windows, not a full motion replay.
        assertEquals(50, fixture.frames.size)
        assertTrue(fixture.frames.zipWithNext().any { (a, b) -> b.timestampMs - a.timestampMs > 1000 })
        val segmentation = RetrospectiveSessionSegmenter(RetrospectiveSegmenterConfig(profile = MotionBodyProfile.PUNCH))
            .segment(fixture.sequenceId, "fixture.mp4", fixture.frames)
        assertTrue(segmentation.movements.isNotEmpty())
        val results = segmentation.movements.map { movement ->
            val input = FourLimbMotionInput(
                "${fixture.sequenceId}-${movement.movementNumber}", "fixture-track",
                movement.logicalStartTimestampMs * 1000, movement.logicalEndTimestampMs * 1000, fixture.frames,
                // Same source-normalized fixture contract used by existing core replay tests.
                CanonicalGeometryDescriptor("fixture-geometry", recordingId = fixture.sequenceId,
                    landmarkTrackId = "fixture-track", canonicalWidth = requireNotNull(fixture.sourceWidth),
                    canonicalHeight = requireNotNull(fixture.sourceHeight)), segmenterVersion = "activity_qom_hysteresis_v1",
            )
            val result = FourLimbMotionCharacterizer.characterize(input)
            assertEquals(result, FourLimbMotionCharacterizer.characterize(input))
            assertEquals(input.logicalStartTimestampUs, result.logicalStartTimestampUs)
            assertEquals(input.logicalEndTimestampUs, result.logicalEndTimestampUs)
            result.profiles.values.filter { it.metrics != null }.forEach { profile ->
                val m = profile.metrics!!
                assertTrue(m.meaningfulAngularTravelDeg.isFinite())
                assertEquals(m.meaningfulAngularTravelDeg, profile.samples.sumOf { abs(it.meaningfulDeltaDeg ?: 0.0) }, 1e-9)
                if (m.motionEndTimestampUs != null) {
                    assertTrue(m.motionEndTimestampUs >= m.lastMeaningfulMotionTimestampUs!!)
                    assertTrue(m.settlingConfirmedAtTimestampUs!! > m.motionEndTimestampUs)
                }
            }
            result
        }
        val out = File("build/reports/four-limb").apply { mkdirs() }
        File(out, "real-mls-summary.csv").writeText(buildString {
            appendLine("movement,limb,start_us,end_us,coverage,pattern,net_deg,travel_deg,onset_us,settling_us,abstention")
            results.forEach { result -> result.profiles.values.forEach { p ->
                appendLine(listOf(result.movementId, p.limbId, result.logicalStartTimestampUs, result.logicalEndTimestampUs,
                    p.validSampleCoverage, p.motionPattern, p.metrics?.netAngleChangeDeg, p.metrics?.meaningfulAngularTravelDeg,
                    p.metrics?.motionStartTimestampUs, p.metrics?.motionEndTimestampUs, p.abstentionReason).joinToString(","))
            } }
        })
        File(out, "real-mls-evidence.csv").writeText(buildString {
            appendLine("movement,limb,timestamp_us,angle_deg,delta_deg,speed_deg_s,block")
            results.forEach { result -> result.profiles.values.forEach { p -> p.samples.forEach { s ->
                appendLine(listOf(result.movementId, p.limbId, s.timestampUs, s.angleDeg, s.meaningfulDeltaDeg,
                    s.angularSpeedDegPerSec, s.blockId).joinToString(","))
            } } }
        })
        println(File(out, "real-mls-summary.csv").readText())
        assertTrue(results.all { it.leftArm.metrics == null && it.rightArm.metrics == null })
        val result = results.single()
        assertEquals(LimbAbstentionReason.TRACKING_QUALITY_INSUFFICIENT, result.rightArm.abstentionReason)
        assertEquals(1.0, result.rightArm.validSampleCoverage)
        assertEquals(LimbAbstentionReason.JOINT_GEOMETRY_UNAVAILABLE, result.leftArm.abstentionReason)
    }
}
