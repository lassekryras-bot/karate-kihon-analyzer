package dk.lasse.karateanalyzer.capture

import java.io.File

/** Offline entry point; the conservative-v1 values match MotionReplayValidationTest. */
object RealMotionReplayCli {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 2) { "Expected fixture.json trace.json" }
        val fixture = PoseReplayJson.decode(File(args[0]).readText())
        val parameters = MotionReplayParameterSet(
            name = "conservative-v1",
            extractor = PoseMotionExtractorConfig(baselineRequiredSamples = 3),
            segmenter = GenericMotionSegmenterConfig(
                baselineDwellMs = 100, movementStartDwellMs = 100,
                settlingDwellMs = 100, noMovementTimeoutMs = 300,
                startMotionThreshold = 0.2, quietMotionThreshold = 0.1,
                minimumCoverage = 0.7, terminalPoseSimilarity = 0.8,
                maximumStableDisplacement = 0.1,
            ),
        )
        val result = MotionReplayRunner().run(fixture, parameters)
        val repeated = MotionReplayRunner().run(fixture, parameters)
        check(MotionReplayTraceJson.encode(result) == MotionReplayTraceJson.encode(repeated))
        File(args[1]).apply { parentFile?.mkdirs() }.writeText(MotionReplayTraceJson.encode(result))
        println("${result.trace.size} frames; ${result.finalState}; ${result.score}; deterministic repeat passed")
    }
}
