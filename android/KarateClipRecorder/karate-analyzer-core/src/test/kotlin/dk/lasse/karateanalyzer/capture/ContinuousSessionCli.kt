package dk.lasse.karateanalyzer.capture

import java.io.File

object ContinuousSessionCli {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size >= 2) { "Expected: fixture.json output-directory [camera_near_arm_side]" }
        val input = PoseReplayJson.decode(File(args[0]).readText())
        require(input.labels == null) { "Continuous session replay must not receive review labels" }
        val output = File(args[1]).apply { mkdirs() }

        val cameraNearArmSide = if (args.size >= 3 && args[2].isNotBlank()) {
            try {
                LateralSide.valueOf(args[2].trim())
            } catch (e: Exception) {
                // If an old requiredRegions string was passed like "TORSO,RIGHT_ARM", ignore or default to RIGHT
                LateralSide.RIGHT
            }
        } else {
            LateralSide.RIGHT
        }

        val cadence = if (args.size >= 4 && args[3].isNotBlank()) {
            try {
                RecordingCadence.valueOf(args[3].trim().uppercase())
            } catch (e: Exception) {
                RecordingCadence.NORMAL
            }
        } else {
            RecordingCadence.NORMAL
        }

        val scope = if (args.size >= 5 && args[4].isNotBlank()) {
            try {
                SettlingEvidenceScope.valueOf(args[4].trim().uppercase())
            } catch (e: Exception) {
                SettlingEvidenceScope.WHOLE_BODY
            }
        } else {
            SettlingEvidenceScope.WHOLE_BODY
        }

        val profile = BaseRecordingProfile(
            cadence = cadence,
            settlingEvidenceScope = scope,
        )

        println("Running minimal BASE continuous motion controller on ${input.sequenceId} (${input.frames.size} frames)...")
        println("Profile: Cadence=${profile.cadence}, Scope=${profile.settlingEvidenceScope} (ET >= ${profile.translationMovingThreshold} / EA >= ${profile.angularMovingThreshold} moving; ET <= ${profile.translationQuietThreshold} / EA <= ${profile.angularQuietThreshold} quiet; ${profile.startDwellMs}ms start dwell; ${profile.quietDwellMs}ms quiet dwell, ${profile.settlingWindowMs}ms window, ${profile.maxMovingInterruptionMs}ms moving tolerance)")
        println("Camera near arm side: $cameraNearArmSide")

        val config = ContinuousMotionControllerConfig(
            profile = profile,
            cameraNearArmSide = cameraNearArmSide,
        )

        val controller = ContinuousMotionController(config)
        val result = controller.run(input)

        println("Session finished. Detected movements: ${result.detectedMovementCount}")
        for (seg in result.segments) {
            println("  Movement ${seg.movementNumber}: completed=${seg.completed}, state=${seg.finalState}, " +
                "start=${seg.startBoundaryTimestampMs}ms (frame ${seg.startBoundaryFrameIndex}), " +
                "end=${seg.terminalBoundaryTimestampMs}ms (frame ${seg.terminalBoundaryFrameIndex}), " +
                "duration=${seg.durationMs}ms")
        }

        // Write summary JSON
        val segmentsJson = result.segments.joinToString(",\n", "[\n", "\n]") { seg ->
            val trs = seg.transitions.joinToString(",\n") { tr ->
                """{"from":"${tr.previousState}","to":"${tr.newState}","decision_timestamp_ms":${tr.decisionTimestampMs},"boundary_timestamp_ms":${tr.estimatedBoundaryTimestampMs},"trigger":"${tr.trigger}"}"""
            }
            """  {
    "movement_number": ${seg.movementNumber},
    "completed": ${seg.completed},
    "final_state": "${seg.finalState}",
    "arm_timestamp_ms": ${seg.armTimestampMs},
    "arm_frame_index": ${seg.armFrameIndex},
    "start_boundary_timestamp_ms": ${seg.startBoundaryTimestampMs},
    "start_decision_timestamp_ms": ${seg.startDecisionTimestampMs},
    "terminal_boundary_timestamp_ms": ${seg.terminalBoundaryTimestampMs},
    "completion_decision_timestamp_ms": ${seg.completionDecisionTimestampMs},
    "start_boundary_frame_index": ${seg.startBoundaryFrameIndex},
    "start_decision_frame_index": ${seg.startDecisionFrameIndex},
    "terminal_boundary_frame_index": ${seg.terminalBoundaryFrameIndex},
    "completion_decision_frame_index": ${seg.completionDecisionFrameIndex},
    "duration_ms": ${seg.durationMs},
    "settling_resumptions": ${seg.settlingResumptions},
    "coverage_unknown_frames": ${seg.coverageUnknownFrames},
    "start_reason": ${if (seg.startReason != null) "\"${seg.startReason}\"" else "null"},
    "terminal_reason": ${if (seg.terminalReason != null) "\"${seg.terminalReason}\"" else "null"},
    "failure_reason": ${if (seg.failureReason != null) "\"${seg.failureReason}\"" else "null"},
    "transitions": [
$trs
    ]
  }"""
        }

        val sessionJson = """{
  "sequence_id": "${result.sequenceId}",
  "total_frames": ${result.totalFrames},
  "total_duration_ms": ${result.totalDurationMs},
  "detected_movement_count": ${result.detectedMovementCount},
  "segments": $segmentsJson
}
"""
        File(output, "blind-session-result.json").writeText(sessionJson)

        fun segmentForTimestamp(t: Long): DetectedMovementSegment? {
            return result.segments.firstOrNull { seg ->
                val armT = seg.armTimestampMs ?: 0L
                val endT = seg.completionDecisionTimestampMs ?: seg.terminalBoundaryTimestampMs ?: Long.MAX_VALUE
                t in armT..endT
            } ?: result.segments.lastOrNull { t >= (it.armTimestampMs ?: 0L) }
        }

        // Write full trace JSON
        val traceJson = buildString {
            append("{\n")
            append("  \"sequence_id\": \"${result.sequenceId}\",\n")
            append("  \"total_frames\": ${result.totalFrames},\n")
            append("  \"frames\": [\n")
            result.trace.forEachIndexed { idx, row ->
                val t = row.timestampMs
                val seg = segmentForTimestamp(t)
                val diag = row.observation.diagnostics
                val regionsJson = diag?.regions?.entries?.joinToString(",") { (reg, rdiag) ->
                    "\"$reg\":{\"coverage\":${rdiag.coverage},\"raw_motion\":${rdiag.rawMotion},\"robust_motion\":${rdiag.robustMotion}}"
                } ?: ""
                val kin = row.observation.kinematics
                val top2 = kin?.top2
                val dt = row.segmenterSnapshot.translationDecision.name
                val da = row.segmenterSnapshot.angularDecision.name

                val candStart = row.segmenterSnapshot.movingStartCandidateMs ?: seg?.startBoundaryTimestampMs
                val confStart = if (row.segmenterSnapshot.state in setOf(BaseSegmentState.MOVING, BaseSegmentState.COMPLETE)) {
                    seg?.startDecisionTimestampMs
                } else null

                val candEnd = row.segmenterSnapshot.quietEndCandidateMs ?: seg?.terminalBoundaryTimestampMs
                val confEnd = if (row.segmenterSnapshot.state == BaseSegmentState.COMPLETE) {
                    seg?.completionDecisionTimestampMs
                } else null

                val top2Json = if (top2 != null) {
                    """, "top2":{"translation_evidence":${top2.translationEvidence},"translation_channel_1":${if (top2.translationChannel1 != null) "\"${top2.translationChannel1}\"" else "null"},"translation_channel_2":${if (top2.translationChannel2 != null) "\"${top2.translationChannel2}\"" else "null"},"translation_count":${top2.translationChannelCount},"translation_decision":"$dt","angular_evidence":${top2.angularEvidence},"angular_channel_1":${if (top2.angularChannel1 != null) "\"${top2.angularChannel1}\"" else "null"},"angular_channel_2":${if (top2.angularChannel2 != null) "\"${top2.angularChannel2}\"" else "null"},"angular_count":${top2.angularChannelCount},"angular_decision":"$da","reference_scale":${top2.referenceScale}}"""
                } else ""

                val channelJson = kin?.positiveEvidence?.channels?.joinToString(",", "[", "]") { c ->
                    """{"name":"${c.name}","raw":${c.rawValue},"smoothed":${c.smoothedValue},"coherence":${c.coherence},"activity":${c.activity},"threshold":${c.threshold},"confidence":${c.confidence},"evidence":"${c.evidence}","used_in_gating":${c.usedInGating}}"""
                } ?: "[]"

                val timingJson = """, "candidate_start":$candStart,"confirmed_start":$confStart,"candidate_end":$candEnd,"confirmed_end":$confEnd,"backdated_movement_start":${seg?.startBoundaryTimestampMs},"backdated_movement_end":${seg?.terminalBoundaryTimestampMs},"rearm_timestamp":${seg?.armTimestampMs}"""

                val extraJson = """, "quiet_evidence":"${dt == "QUIET" && da == "QUIET"}","movement_evidence":"${dt == "MOVING" || da == "MOVING"}","kinematics_altered_evidence_or_dwell":false,"mirrored_similarity":${row.observation.mirroredStartSimilarity},"world_scale":${diag?.bodyScaleWorld},"image_scale":${diag?.bodyScaleImage},"image_scale_change":${diag?.imageScaleChange},"channels":$channelJson$top2Json$timingJson"""

                val kinJson = if (kin != null) {
                    val channels = kin.positiveEvidence.triggeringChannels.joinToString(",") { "\"$it\"" }
                    ",\"kinematics\":{\"left_wrist_speed\":${kin.leftArm.wristTorsoRelativeSpeed},\"left_wrist_radial\":${kin.leftArm.wristRadialVelocity},\"right_wrist_speed\":${kin.rightArm.wristTorsoRelativeSpeed},\"right_wrist_radial\":${kin.rightArm.wristRadialVelocity},\"left_ankle_speed\":${kin.leftLeg.ankleTorsoRelativeSpeed},\"left_ankle_radial\":${kin.leftLeg.ankleRadialVelocity},\"right_ankle_speed\":${kin.rightLeg.ankleTorsoRelativeSpeed},\"right_ankle_radial\":${kin.rightLeg.ankleRadialVelocity},\"left_knee_speed\":${kin.leftLeg.kneeTorsoRelativeSpeed},\"left_knee_radial\":${kin.leftLeg.kneeRadialVelocity},\"right_knee_speed\":${kin.rightLeg.kneeTorsoRelativeSpeed},\"right_knee_radial\":${kin.rightLeg.kneeRadialVelocity},\"left_elbow_angle\":${kin.leftArm.elbowAngleDegrees},\"left_elbow_velocity\":${kin.leftArm.elbowAngularVelocityDegPerSec},\"right_elbow_angle\":${kin.rightArm.elbowAngleDegrees},\"right_elbow_velocity\":${kin.rightArm.elbowAngularVelocityDegPerSec},\"left_knee_angle\":${kin.leftLeg.kneeAngleDegrees},\"left_knee_velocity\":${kin.leftLeg.kneeAngularVelocityDegPerSec},\"right_knee_angle\":${kin.rightLeg.kneeAngleDegrees},\"right_knee_velocity\":${kin.rightLeg.kneeAngularVelocityDegPerSec},\"left_upper_arm_orientation\":${kin.leftArm.upperArmOrientationChangeDegPerSec},\"left_forearm_orientation\":${kin.leftArm.forearmOrientationChangeDegPerSec},\"right_upper_arm_orientation\":${kin.rightArm.upperArmOrientationChangeDegPerSec},\"right_forearm_orientation\":${kin.rightArm.forearmOrientationChangeDegPerSec},\"left_thigh_orientation\":${kin.leftLeg.thighOrientationChangeDegPerSec},\"left_shin_orientation\":${kin.leftLeg.shinOrientationChangeDegPerSec},\"right_thigh_orientation\":${kin.rightLeg.thighOrientationChangeDegPerSec},\"right_shin_orientation\":${kin.rightLeg.shinOrientationChangeDegPerSec},\"pelvis_rotation_3d\":${kin.pelvis.rotationRateDegPerSec},\"pelvis_rotation_yaw\":${kin.pelvis.yawRotationRateDegPerSec},\"any_limb_motion\":\"${kin.positiveEvidence.anyLimbMotion}\",\"triggering_channels\":[$channels]}"
                } else ""

                append("    {\"timestamp_ms\":${row.timestampMs},\"state\":\"${row.segmenterSnapshot.state}\",\"articulated_motion\":${row.observation.articulatedMotion},\"image_space_motion\":${row.observation.imageSpaceMotion},\"slow_displacement\":${row.observation.accumulatedDisplacement},\"coverage\":${row.observation.coverage},\"same_similarity\":${row.observation.sameAsStartSimilarity},\"baseline_dwell_ms\":0,\"movement_dwell_ms\":0,\"completion_dwell_ms\":0,\"regions\":{$regionsJson}$kinJson$extraJson}")
                if (idx < result.trace.size - 1) append(",")
                append("\n")
            }
            append("  ]\n")
            append("}\n")
        }
        File(output, "blind-session.trace.json").writeText(traceJson)

        // Write audit TSV
        val auditTsv = buildString {
            append("frame\ttimestamp_ms\tET\tEA\tDT\tDA\tstate\tcandidate_start\tconfirmed_start\tcandidate_end\tconfirmed_end\tbackdated_movement_start\tbackdated_movement_end\trearm_timestamp\tdisplacement\tcoverage\tarticulated_motion\timage_motion\n")
            result.trace.forEachIndexed { idx, row ->
                val t = row.timestampMs
                val seg = segmentForTimestamp(t)
                val top2 = row.observation.kinematics?.top2
                val dt = row.segmenterSnapshot.translationDecision.name
                val da = row.segmenterSnapshot.angularDecision.name

                val candStart = row.segmenterSnapshot.movingStartCandidateMs ?: seg?.startBoundaryTimestampMs
                val confStart = if (row.segmenterSnapshot.state in setOf(BaseSegmentState.MOVING, BaseSegmentState.COMPLETE)) {
                    seg?.startDecisionTimestampMs
                } else null

                val candEnd = row.segmenterSnapshot.quietEndCandidateMs ?: seg?.terminalBoundaryTimestampMs
                val confEnd = if (row.segmenterSnapshot.state == BaseSegmentState.COMPLETE) {
                    seg?.completionDecisionTimestampMs
                } else null

                append("$idx\t$t\t${top2?.translationEvidence}\t${top2?.angularEvidence}\t$dt\t$da\t${row.segmenterSnapshot.state}\t$candStart\t$confStart\t$candEnd\t$confEnd\t${seg?.startBoundaryTimestampMs}\t${seg?.terminalBoundaryTimestampMs}\t${seg?.armTimestampMs}\t${row.observation.accumulatedDisplacement}\t${row.observation.coverage}\t${row.observation.articulatedMotion}\t${row.observation.imageSpaceMotion}\n")
            }
        }
        File(output, "blind-session.tsv").writeText(auditTsv)

        println("Saved blind-session-result.json, blind-session.trace.json, blind-session.tsv to $output")
    }
}
