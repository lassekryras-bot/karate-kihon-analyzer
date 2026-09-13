package dk.lasse.karateanalyzer.capture

import dk.lasse.karateanalyzer.observation.TransitionRecord

data class ContinuousMotionControllerConfig(
    val profile: BaseRecordingProfile = BaseRecordingProfile(),
    val cameraNearArmSide: LateralSide? = LateralSide.RIGHT,
)

data class DetectedMovementSegment(
    val movementNumber: Int,
    val completed: Boolean,
    val finalState: String,
    val armTimestampMs: Long?,
    val armFrameIndex: Int?,
    val startBoundaryTimestampMs: Long?,
    val startDecisionTimestampMs: Long?,
    val terminalBoundaryTimestampMs: Long?,
    val completionDecisionTimestampMs: Long?,
    val startBoundaryFrameIndex: Int?,
    val startDecisionFrameIndex: Int?,
    val terminalBoundaryFrameIndex: Int?,
    val completionDecisionFrameIndex: Int?,
    val durationMs: Long?,
    val transitions: List<TransitionRecord<String>> = emptyList(),
    val settlingResumptions: Int = 0,
    val coverageUnknownFrames: Int = 0,
    val startReason: String?,
    val terminalReason: String?,
    val failureReason: String?,
)

data class ContinuousTraceFrame(
    val timestampMs: Long,
    val frameIndex: Int,
    val observation: MotionObservation,
    val segmenterSnapshot: BaseSegmenterSnapshot,
)

data class ContinuousSessionResult(
    val sequenceId: String,
    val totalFrames: Int,
    val totalDurationMs: Long,
    val detectedMovementCount: Int,
    val segments: List<DetectedMovementSegment>,
    val trace: List<ContinuousTraceFrame>,
)

class ContinuousMotionController(
    val config: ContinuousMotionControllerConfig = ContinuousMotionControllerConfig(),
) {
    fun run(fixture: PoseReplayFixture): ContinuousSessionResult {
        val extractor = PoseMotionObservationExtractor(
            PoseMotionExtractorConfig(
                cameraNearArmSide = config.cameraNearArmSide,
                requiredRegions = AnatomicalRegion.entries.toSet(),
            )
        )
        val segmenter = BaseMovementSegmenter(config.profile)

        val completedSegments = mutableListOf<DetectedMovementSegment>()
        val trace = mutableListOf<ContinuousTraceFrame>()

        val timestampToFrameIndex = mutableMapOf<Long, Int>()
        fixture.frames.forEachIndexed { idx, frame ->
            timestampToFrameIndex[frame.timestampMs] = idx
        }

        var currentArmTimestampMs: Long? = fixture.frames.firstOrNull()?.timestampMs
        var currentArmFrameIndex: Int? = if (fixture.frames.isNotEmpty()) 0 else null

        for ((frameIndex, frame) in fixture.frames.withIndex()) {
            val observation = extractor.accept(frame)
            val top2 = observation.kinematics?.top2
            val etStart = top2?.translationEvidence
            val eaStart = top2?.angularEvidence

            val scope = config.profile.settlingEvidenceScope
            val etSettle = top2?.settlingTranslationEvidence(scope)
            val eaSettle = top2?.settlingAngularEvidence(scope)

            val snapshot = segmenter.accept(
                timestampMs = frame.timestampMs,
                startTranslationEvidence = etStart,
                startAngularEvidence = eaStart,
                settlingTranslationEvidence = etSettle,
                settlingAngularEvidence = eaSettle,
            )

            trace += ContinuousTraceFrame(
                timestampMs = frame.timestampMs,
                frameIndex = frameIndex,
                observation = observation,
                segmenterSnapshot = snapshot,
            )

            val seg = snapshot.completedSegment
            if (seg != null) {
                val startFrame = timestampToFrameIndex[seg.startBoundaryMs]
                val endFrame = timestampToFrameIndex[seg.endBoundaryMs]
                val confirmFrame = timestampToFrameIndex[seg.confirmationTimestampMs]

                val trs = listOf(
                    TransitionRecord(
                        previousState = "ARMED",
                        newState = "MOVING",
                        decisionTimestampMs = seg.startBoundaryMs + config.profile.startDwellMs,
                        estimatedBoundaryTimestampMs = seg.startBoundaryMs,
                        trigger = "start_confirmed_50ms",
                    ),
                    TransitionRecord(
                        previousState = "MOVING",
                        newState = "COMPLETE",
                        decisionTimestampMs = seg.confirmationTimestampMs,
                        estimatedBoundaryTimestampMs = seg.endBoundaryMs,
                        trigger = "terminal_quiet_confirmed_100ms",
                    ),
                )

                completedSegments += DetectedMovementSegment(
                    movementNumber = seg.movementNumber,
                    completed = true,
                    finalState = "COMPLETE",
                    armTimestampMs = currentArmTimestampMs,
                    armFrameIndex = currentArmFrameIndex,
                    startBoundaryTimestampMs = seg.startBoundaryMs,
                    startDecisionTimestampMs = seg.startBoundaryMs + config.profile.startDwellMs,
                    terminalBoundaryTimestampMs = seg.endBoundaryMs,
                    completionDecisionTimestampMs = seg.confirmationTimestampMs,
                    startBoundaryFrameIndex = startFrame,
                    startDecisionFrameIndex = startFrame,
                    terminalBoundaryFrameIndex = endFrame,
                    completionDecisionFrameIndex = confirmFrame,
                    durationMs = seg.durationMs,
                    transitions = trs,
                    startReason = "start_confirmed_50ms",
                    terminalReason = "terminal_quiet_confirmed_100ms",
                    failureReason = null,
                )

                currentArmTimestampMs = frame.timestampMs
                currentArmFrameIndex = frameIndex
            }
        }

        val lastSnapshot = trace.lastOrNull()?.segmenterSnapshot
        if (lastSnapshot != null && lastSnapshot.state == BaseSegmentState.MOVING) {
            val startMs = lastSnapshot.movementStartBoundaryMs
            if (startMs != null) {
                val startFrame = timestampToFrameIndex[startMs]
                completedSegments += DetectedMovementSegment(
                    movementNumber = segmenter.currentMovementNumber,
                    completed = false,
                    finalState = "MOVING",
                    armTimestampMs = currentArmTimestampMs,
                    armFrameIndex = currentArmFrameIndex,
                    startBoundaryTimestampMs = startMs,
                    startDecisionTimestampMs = startMs + config.profile.startDwellMs,
                    terminalBoundaryTimestampMs = null,
                    completionDecisionTimestampMs = null,
                    startBoundaryFrameIndex = startFrame,
                    startDecisionFrameIndex = startFrame,
                    terminalBoundaryFrameIndex = null,
                    completionDecisionFrameIndex = null,
                    durationMs = null,
                    transitions = listOf(
                        TransitionRecord(
                            previousState = "ARMED",
                            newState = "MOVING",
                            decisionTimestampMs = startMs + config.profile.startDwellMs,
                            estimatedBoundaryTimestampMs = startMs,
                            trigger = "start_confirmed_50ms",
                        )
                    ),
                    startReason = "start_confirmed_50ms",
                    terminalReason = null,
                    failureReason = "INCOMPLETE_AT_EOF (final state was MOVING)",
                )
            }
        }

        val totalDurationMs = if (fixture.frames.isNotEmpty()) {
            fixture.frames.last().timestampMs - fixture.frames.first().timestampMs
        } else 0L

        return ContinuousSessionResult(
            sequenceId = fixture.sequenceId,
            totalFrames = fixture.frames.size,
            totalDurationMs = totalDurationMs,
            detectedMovementCount = completedSegments.count { it.completed },
            segments = completedSegments,
            trace = trace,
        )
    }
}
