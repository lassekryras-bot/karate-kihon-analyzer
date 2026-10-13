package dk.lasse.karatecliprecorder.movement

import dk.lasse.karateanalyzer.capture.qom.MotionBodyProfile
import dk.lasse.karateanalyzer.capture.qom.QomMotionEvidenceExtractor
import dk.lasse.karateanalyzer.core.ActivitySideContext
import dk.lasse.karateanalyzer.core.LimbInterpretationActivity
import dk.lasse.karateanalyzer.core.PoseFrame
import dk.lasse.karateanalyzer.geometry.CanonicalGeometryCodec
import dk.lasse.karateanalyzer.geometry.ImageBodyScaleProvider
import dk.lasse.karatecliprecorder.training.*

enum class DebugActivityOverride { STRAIGHT_PUNCH }

data class MovementDebugOverrideResult(
    val override: DebugActivityOverride,
    val context: ActivitySideContext,
    val originalMotionAnalysis: MovementAnalysis,
    val evaluated: MovementAnalysisPipelineResult,
)

sealed interface MovementDebugOverrideOutcome {
    data class Evaluated(val result: MovementDebugOverrideResult) : MovementDebugOverrideOutcome
    data class Unavailable(val reason: String) : MovementDebugOverrideOutcome
}

object MovementDebugToolPolicy {
    fun enabled(debugBuild: Boolean, developerMode: Boolean): Boolean = debugBuild && developerMode
}

internal class MovementDebugEvaluationGate {
    private var generation = 0L

    fun begin(): Long = ++generation
    fun invalidate() { generation += 1 }
    fun accepts(token: Long): Boolean = token == generation
}

/** Ephemeral inspection only. This object has no repository or persistence dependency. */
object MovementDebugOverrideEvaluator {
    const val DEBUG_PLAN_ID = "debug-straight-punch-override-v1"
    const val DEBUG_SOURCE = "DEBUG_OVERRIDE"

    fun evaluate(
        override: DebugActivityOverride,
        evidence: MovementEvidence,
        sessionEvents: List<SessionEvent>,
        frames: List<PoseFrame>,
        loadedTrackId: String?,
    ): MovementDebugOverrideOutcome {
        if (MotionActivityPlans.eventState(sessionEvents) != MotionActivityPlans.EventState.MISSING) {
            return MovementDebugOverrideOutcome.Unavailable("SESSION_ACTIVITY_PLAN_NOT_MISSING")
        }
        val original = selectMovementAnalysis(evidence.analyses, MovementMotionAnalysis.policy, includeUnavailable = true)
            ?: return MovementDebugOverrideOutcome.Unavailable("RETAINED_MOTION_ANALYSIS_UNAVAILABLE")
        val retained = MovementMotionInspection.read(evidence)
            ?: return MovementDebugOverrideOutcome.Unavailable("RETAINED_MOTION_EVIDENCE_UNAVAILABLE")
        if (!retained.isNull("activity") || retained.optString("impactReason") != "ACTIVITY_PLAN_UNAVAILABLE") {
            return MovementDebugOverrideOutcome.Unavailable("HISTORICAL_ACTIVITY_PREREQUISITE_NOT_MISSING")
        }
        if (loadedTrackId != original.landmarkTrackId) {
            return MovementDebugOverrideOutcome.Unavailable("MATCHING_LANDMARK_TRACK_NOT_LOADED")
        }
        val track = evidence.landmarkTracks.singleOrNull {
            it.landmarkTrackId == original.landmarkTrackId &&
                it.state == ProcessingState.COMPLETED && it.sourceState == SourceState.AVAILABLE
        } ?: return MovementDebugOverrideOutcome.Unavailable("MATCHING_LANDMARK_TRACK_UNAVAILABLE")
        if (frames.isEmpty()) return MovementDebugOverrideOutcome.Unavailable("MATCHING_LANDMARK_FRAMES_UNAVAILABLE")
        val geometry = CanonicalGeometryCodec.decode(track.canonicalGeometryJson)
            ?: return MovementDebugOverrideOutcome.Unavailable("MATCHING_CANONICAL_GEOMETRY_UNAVAILABLE")
        if (geometry.recordingId != evidence.recording.recordingId || geometry.landmarkTrackId != track.landmarkTrackId) {
            return MovementDebugOverrideOutcome.Unavailable("MATCHING_CANONICAL_GEOMETRY_CONFLICT")
        }
        val context = when (override) {
            DebugActivityOverride.STRAIGHT_PUNCH -> ActivitySideContext(
                planId = DEBUG_PLAN_ID,
                planVersion = "1",
                activity = LimbInterpretationActivity.STRAIGHT_PUNCH,
                alternating = false,
            )
        }
        return runCatching {
            val calibration = ImageBodyScaleStore.current(sessionEvents)
            val qom = QomMotionEvidenceExtractor(profile = MotionBodyProfile.PUNCH).let { extractor ->
                frames.map(extractor::extract)
            }
            val limbs = MovementMotionAnalysis.characterize(
                evidence.movement,
                track.landmarkTrackId,
                frames,
                geometry,
            )
            val evaluated = MovementAnalysisPipeline.evaluate(
                movement = evidence.movement,
                trackId = track.landmarkTrackId,
                frames = frames,
                geometry = geometry,
                activityContext = context,
                qom = qom,
                limbs = limbs,
                sequence = null,
                bodyScale = ImageBodyScaleProvider.evidence(
                    calibration,
                    geometry,
                    evidence.movement.startUs,
                    evidence.movement.endUs,
                ),
                calibration = calibration,
                activityPlanEvidence = null,
                viewApproval = null,
                analyzers = listOf(StraightPunchMovementAdapter.policy.analyzerKey),
                videoWidth = evidence.recording.width,
                videoHeight = evidence.recording.height,
                requestedJodan = false,
            )
            MovementDebugOverrideOutcome.Evaluated(
                MovementDebugOverrideResult(override, context, original, evaluated)
            )
        }.getOrElse { error ->
            MovementDebugOverrideOutcome.Unavailable(error.message ?: "DEBUG_OVERRIDE_EVALUATION_FAILED")
        }
    }
}
