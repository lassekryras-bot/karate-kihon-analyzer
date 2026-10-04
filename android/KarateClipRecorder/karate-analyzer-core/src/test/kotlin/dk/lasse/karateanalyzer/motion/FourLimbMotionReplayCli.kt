package dk.lasse.karateanalyzer.motion

import dk.lasse.karateanalyzer.capture.PoseReplayJson
import dk.lasse.karateanalyzer.capture.qom.MotionBodyProfile
import dk.lasse.karateanalyzer.capture.retrospective.RetrospectiveSegmenterConfig
import dk.lasse.karateanalyzer.capture.retrospective.RetrospectiveSessionSegmenter
import dk.lasse.karateanalyzer.core.LandmarkSource
import dk.lasse.karateanalyzer.geometry.CanonicalGeometryDescriptor
import java.io.File

/** Diagnostic export from production segmentation and characterization. No independent analysis math. */
object FourLimbMotionReplayCli {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 2) { "Expected fixture.json output-directory" }
        val fixture = PoseReplayJson.decode(File(args[0]).readText())
        val out = File(args[1]).apply { mkdirs() }
        val config = FourLimbMotionConfig()
        val segmentation = RetrospectiveSessionSegmenter(RetrospectiveSegmenterConfig(profile = MotionBodyProfile.PUNCH))
            .segment(fixture.sequenceId, "original-source.mp4", fixture.frames)
        require(segmentation.movements.isNotEmpty()) { "No segmented movements" }
        val results = segmentation.movements.map { movement ->
            val input = FourLimbMotionInput("${fixture.sequenceId}-${movement.movementNumber}", "fixture-track",
                movement.logicalStartTimestampMs * 1000, movement.logicalEndTimestampMs * 1000, fixture.frames,
                CanonicalGeometryDescriptor("fixture-geometry", recordingId = fixture.sequenceId,
                    landmarkTrackId = "fixture-track", canonicalWidth = requireNotNull(fixture.sourceWidth),
                    canonicalHeight = requireNotNull(fixture.sourceHeight)), segmenterVersion = "activity_qom_hysteresis_v1")
            FourLimbMotionCharacterizer.characterize(input, config).also {
                check(it == FourLimbMotionCharacterizer.characterize(input, config)) { "Replay was nondeterministic" }
            }
        }
        File(out, "summary.csv").writeText(buildString {
            appendLine("movement,limb,start_us,end_us,samples,valid_samples,sample_coverage,connected_time_coverage,low_confidence,missing,non_observed,invalid_xy,pattern,net_deg,travel_deg,onset_us,settling_us,abstention")
            results.forEach { r -> r.profiles.values.forEach { p ->
                val frames = fixture.frames.filter { it.timestampMs * 1000 in r.logicalStartTimestampUs..r.logicalEndTimestampUs }
                fun count(reject: (dk.lasse.karateanalyzer.core.PoseLandmarkSample?) -> Boolean) = frames.count { f -> p.limbId.sourceLandmarks.any { reject(f.landmarks[it]) } }
                val connected = p.samples.zipWithNext().sumOf { (a, b) ->
                    if (a.blockId != null && a.blockId == b.blockId) b.timestampUs - a.timestampUs else 0L
                }
                appendLine(listOf(r.movementId, p.limbId, r.logicalStartTimestampUs, r.logicalEndTimestampUs,
                    frames.size, p.samples.count { it.angleDeg != null }, p.validSampleCoverage,
                    connected.toDouble() / (r.logicalEndTimestampUs - r.logicalStartTimestampUs),
                    count { it != null && (!it.confidence.isFinite() || it.confidence < config.minimumConfidence) },
                    count { it?.position == null }, count { it != null && it.source != LandmarkSource.OBSERVED },
                    count { it?.position?.let { point -> !point.x.isFinite() || !point.y.isFinite() } == true },
                    p.motionPattern, p.metrics?.netAngleChangeDeg, p.metrics?.meaningfulAngularTravelDeg,
                    p.metrics?.motionStartTimestampUs, p.metrics?.motionEndTimestampUs, p.abstentionReason).joinToString(","))
            } }
        })
        File(out, "evidence.csv").writeText(buildString {
            appendLine("movement,limb,timestamp_us,raw_angle_deg,angle_deg,delta_deg,speed_deg_s,block")
            results.forEach { r -> r.profiles.values.forEach { p -> p.samples.forEach { s ->
                appendLine(listOf(r.movementId, p.limbId, s.timestampUs, s.rawAngleDeg, s.angleDeg,
                    s.meaningfulDeltaDeg, s.angularSpeedDegPerSec, s.blockId).joinToString(","))
            } } }
        })
        File(out, "qom.csv").writeText(buildString {
            appendLine("evidence")
            segmentation.qomTimeline.forEach { appendLine(it.toString()) }
        })
        val measured = results.sumOf { r -> listOf(r.leftArm, r.rightArm).count { it.metrics != null } }
        println("movements=${results.size}; measurable_arm_profiles=$measured; report=${out.canonicalPath}")
    }
}
