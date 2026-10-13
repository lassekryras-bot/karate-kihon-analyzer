package dk.lasse.karatecliprecorder.training

import dk.lasse.karateanalyzer.capture.qom.QomFrameEvidence
import dk.lasse.karateanalyzer.core.ActivitySideContext
import dk.lasse.karateanalyzer.core.LimbInterpretationActivity
import dk.lasse.karateanalyzer.core.PoseFrame
import dk.lasse.karateanalyzer.core.RepetitionSideValidation
import dk.lasse.karateanalyzer.geometry.CanonicalGeometryDescriptor
import dk.lasse.karateanalyzer.geometry.ImageBodyScaleCalibration
import dk.lasse.karateanalyzer.impact.BodyScaleEvidence
import dk.lasse.karateanalyzer.impact.ImpactAnalysisStatus
import dk.lasse.karateanalyzer.motion.FourLimbMotionResult

data class DownstreamMovementAnalysis(
    val analysis: MovementAnalysis,
    val measurements: List<MeasurementResult>,
)

data class MovementAnalysisPipelineResult(
    val motion: MovementMotionOutput,
    val downstream: List<DownstreamMovementAnalysis>,
    val hadFailure: Boolean,
)

/**
 * Pure per-movement orchestration shared by durable processing and developer inspection.
 * Repository selection, persistence and publication remain owned by the caller.
 */
object MovementAnalysisPipeline {
    fun evaluate(
        movement: SessionMovement,
        trackId: String,
        frames: List<PoseFrame>,
        geometry: CanonicalGeometryDescriptor,
        activityContext: ActivitySideContext?,
        qom: List<QomFrameEvidence>,
        limbs: FourLimbMotionResult,
        sequence: RepetitionSideValidation? = null,
        bodyScale: BodyScaleEvidence? = null,
        calibration: ImageBodyScaleCalibration? = null,
        activityPlanEvidence: MotionActivityPlans.Evidence? = null,
        viewApproval: ImpactViewApproval? = null,
        analyzers: List<String>,
        videoWidth: Int? = null,
        videoHeight: Int? = null,
        requestedJodan: Boolean = false,
    ): MovementAnalysisPipelineResult {
        val motion = MovementMotionAnalysis.analyze(
            movement = movement,
            trackId = trackId,
            frames = frames,
            geometry = geometry,
            context = activityContext,
            qom = qom,
            limbs = limbs,
            sequence = sequence,
            bodyScale = bodyScale,
            calibration = calibration,
            viewApproval = viewApproval,
            activityPlanEvidence = activityPlanEvidence,
        )
        var hadFailure = false
        val downstream = analyzers.mapNotNull { analyzerKey ->
            when (analyzerKey) {
                StraightPunchMovementAdapter.policy.analyzerKey -> runCatching {
                    if (activityContext?.activity != LimbInterpretationActivity.STRAIGHT_PUNCH ||
                        motion.impact?.status != ImpactAnalysisStatus.COMPLETED) {
                        MovementAnalysis(
                            movementId = movement.movementId,
                            analyzerKey = StraightPunchMovementAdapter.policy.analyzerKey,
                            analyzerVersion = "2",
                            landmarkTrackId = trackId,
                            state = AnalysisState.ABSTAINED,
                            reason = motion.analysis.reason ?: "TERMINAL_EVIDENCE_UNAVAILABLE",
                        ) to emptyList()
                    } else {
                        StraightPunchMovementAdapter.analyze(
                            movement = movement,
                            trackId = trackId,
                            frames = frames,
                            videoWidth = videoWidth,
                            videoHeight = videoHeight,
                            impactResult = motion.impact,
                            resolvedSide = motion.side?.side,
                        )
                    }
                }.getOrElse { error ->
                    hadFailure = true
                    straightPunchFailureAnalysis(movement, trackId, error) to emptyList()
                }.let { DownstreamMovementAnalysis(it.first, it.second) }

                AndroidPunchMovementAnalyzer.policy.analyzerKey -> runCatching {
                    AndroidPunchMovementAnalyzer.analyze(movement, trackId, frames, requestedJodan)
                }.getOrElse { error ->
                    hadFailure = true
                    MovementAnalysis(
                        movementId = movement.movementId,
                        analyzerKey = AndroidPunchMovementAnalyzer.policy.analyzerKey,
                        analyzerVersion = "1",
                        landmarkTrackId = trackId,
                        state = AnalysisState.FAILED,
                        reason = error.message ?: "Analyzer execution failed",
                    ) to emptyList()
                }.let { DownstreamMovementAnalysis(it.first, it.second) }

                else -> null
            }
        }
        return MovementAnalysisPipelineResult(motion, downstream, hadFailure)
    }
}
