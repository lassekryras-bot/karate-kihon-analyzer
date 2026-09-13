package dk.lasse.karateanalyzer.capture

import dk.lasse.karateanalyzer.observation.SustainedCondition
import java.io.File

/** Development-only experiment runner. Plans contain parameters, never review labels. */
object MotionCalibrationCli {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 3) { "Expected fixture.json plan.tsv output-directory" }
        val input = PoseReplayJson.decode(File(args[0]).readText())
        require(input.labels == null) { "Calibration runtime must not receive review labels" }
        val output = File(args[2]).apply { mkdirs() }
        val lines = File(args[1]).readLines().filter { it.isNotBlank() }
        val keys = lines.first().split('\t')
        lines.drop(1).forEach { line ->
            val values = keys.zip(line.split('\t')).toMap()
            fun double(key: String) = values.getValue(key).toDouble()
            fun long(key: String) = values.getValue(key).toLong()
            val name = values.getValue("name")
            require(name.matches(Regex("[a-zA-Z0-9-]+")))
            val requiredRegions = values["required_regions"]?.takeIf { it.isNotBlank() }
                ?.split(",")?.map { AnatomicalRegion.valueOf(it.trim()) }?.toSet()
                ?: AnatomicalRegion.entries.toSet()
            val expectedRelationship = values["end_relationship"]?.takeIf { it.isNotBlank() }
                ?.let { EndPoseRelationship.valueOf(it) } ?: input.expectedEndPoseRelationship
            val parameters = MotionReplayParameterSet(
                name = name,
                extractor = PoseMotionExtractorConfig(
                    baselineRequiredSamples = 3,
                    baselineMaximumArticulatedMotion = double("extractor_threshold"),
                    slowDisplacementWindowMs = long("window_ms"),
                    requiredRegions = requiredRegions,
                ),
                segmenter = GenericMotionSegmenterConfig(
                    baselineDwellMs = long("baseline_dwell_ms"),
                    movementStartDwellMs = 100, settlingDwellMs = long("settling_dwell_ms"),
                    noMovementTimeoutMs = 300,
                    startMotionThreshold = double("start_threshold"),
                    quietMotionThreshold = double("quiet_threshold"),
                    minimumCoverage = double("coverage"), terminalPoseSimilarity = .8,
                    maximumStableDisplacement = double("displacement_limit"),
                    requiredRegionsForTerminalStillness = requiredRegions,
                ),
                requiredRegions = requiredRegions,
            )
            val firstFrame = values["first_frame"]?.toInt() ?: 0
            val lastFrame = values["last_frame"]?.toInt() ?: (input.frames.size - 1)
            // Diagnostic windows are external replay inputs; no label enters either component.
            val fixture = input.copy(
                sequenceId = "${input.sequenceId}-$name",
                frames = input.frames.subList(firstFrame, lastFrame + 1),
                armTimestampMs = input.frames[firstFrame].timestampMs,
                cueTimestampMs = null, activityDeadlineMs = null,
                expectedEndPoseRelationship = expectedRelationship,
            )
            val runner = MotionReplayRunner()
            val result = runner.run(fixture, parameters)
            val encoded = MotionReplayTraceJson.encode(result)
            check(encoded == MotionReplayTraceJson.encode(runner.run(fixture, parameters)))
            File(output, "$name.trace.json").writeText(encoded)
            val continuousDwell = SustainedCondition(parameters.segmenter.baselineDwellMs)
            val audit = buildString {
                append("frame\ttimestamp_ms\treference_ready\treference_samples\tquiet_evidence\tmovement_evidence\tcontinuous_quiet_dwell_ms\tcontinuous_quiet_fulfilled\n")
                result.trace.forEachIndexed { index, row ->
                    val diagnostics = checkNotNull(row.observation.diagnostics)
                    val dwell = continuousDwell.update(row.timestampMs, checkNotNull(row.segmenter.latestQuietEvidence))
                    append("${index + firstFrame}\t${row.timestampMs}\t${diagnostics.baselineReady}\t${diagnostics.baselineSampleCount}\t${row.segmenter.latestQuietEvidence}\t${row.segmenter.latestMotionEvidence}\t${dwell.accumulatedDurationMs}\t${dwell.fulfilled}\n")
                }
            }
            File(output, "$name.audit.tsv").writeText(audit)
            // Exhaustive three-frame fresh-start probes expose startup/reference risks at every
            // possible position, including movement. They never change the full replay state.
            val probes = buildString {
                append("first_frame\tformed_frame\n")
                (firstFrame..lastFrame - 2).forEach { start ->
                    val extractor = PoseMotionObservationExtractor(parameters.extractor)
                    val observations = input.frames.subList(start, start + 3).map(extractor::accept)
                    if (observations.last().diagnostics?.baselineReady == true) append("$start\t${start + 2}\n")
                }
            }
            File(output, "$name.reference-probes.tsv").writeText(probes)
            println("$name: ${result.finalState}; ${result.transitions.map { it.newState to it.decisionTimestampMs }}")
        }
    }
}
