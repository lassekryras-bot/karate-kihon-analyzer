package dk.lasse.karateanalyzer.core

import dk.lasse.karateanalyzer.capture.LateralSide
import dk.lasse.karateanalyzer.motion.*

/** Explicit interpretation selected by the known activity plan, never inferred from pose evidence. */
enum class LimbInterpretationActivity { STRAIGHT_PUNCH, CHAMBER_EXTENSION_KICK }
enum class SideResolutionStatus { RESOLVED, AMBIGUOUS }
enum class SideResolutionReason { SINGLE_SUPPORTED_SIDE, INADEQUATE_EVIDENCE, NO_SUPPORTED_PATTERN, MULTIPLE_SUPPORTED_SIDES, CONFLICTING_OPPOSITE_EVIDENCE }
enum class AlternationStatus { MATCH, MISMATCH, UNVERIFIABLE, NOT_REQUESTED }
enum class SideEvidenceMode { SINGLE_ARM_ONLY, BILATERAL_CORROBORATED }

/** A projection of the product-owned plan, not a second activity-plan implementation.
 * Target is provenance only: target height must not change observed side evidence.
 */
data class ActivitySideContext(
    val planId: String,
    val planVersion: String,
    val activity: LimbInterpretationActivity,
    val alternating: Boolean,
    val targetId: String? = null,
) {
    init { require(planId.isNotBlank() && planVersion.isNotBlank()) }
}

data class StartingSideConfig(
    val version: String = "activity-side-v1-provisional",
    val minimumExcursionDeg: Double = 15.0,
    val minimumTravelDeg: Double = 15.0,
    val minimumDirectionalShare: Double = 0.75,
    val kickToSupportTravelRatio: Double = 2.0,
) {
    init {
        require(version.isNotBlank())
        require(minimumExcursionDeg.isFinite() && minimumExcursionDeg > 0)
        require(minimumTravelDeg.isFinite() && minimumTravelDeg > 0)
        require(minimumDirectionalShare.isFinite() && minimumDirectionalShare > 0.5 && minimumDirectionalShare <= 1.0)
        require(kickToSupportTravelRatio.isFinite() && kickToSupportTravelRatio > 1.0)
    }
}

data class ActivitySideResolution(
    val status: SideResolutionStatus,
    val side: LateralSide?,
    val reason: SideResolutionReason,
    val supportedCandidates: Set<LateralSide>,
    val context: ActivitySideContext,
    val config: StartingSideConfig,
    val resolverVersion: String,
    /** Straight-punch evidence provenance. Null for unresolved and unchanged kick interpretation. */
    val evidenceMode: SideEvidenceMode? = null,
    /** Earliest qualifying opening episode for the uniquely supported anatomical arm. */
    val supportingEpisode: LimbDirectionalEpisode? = null,
    /** Exact characterizer result retains geometry, boundaries, profiles and configuration provenance. */
    val evidence: FourLimbMotionResult,
)

/** The caller supplies the plan's zero-based repetition index, preserving omitted repetitions.
 * List position is not a substitute for repetition identity.
 */
data class IndexedLimbMovement(val repetitionIndex: Int, val evidence: FourLimbMotionResult)
data class RepetitionSideValidation(
    val repetitionIndex: Int,
    val observed: ActivitySideResolution,
    val expectedSide: LateralSide?,
    val alternationStatus: AlternationStatus,
)
data class ActivitySequenceSideResult(
    val startingSide: LateralSide?,
    val startingSideStatus: SideResolutionStatus,
    val repetitions: List<RepetitionSideValidation>,
)

/** Karate interpretation belongs downstream of generic articulation, within the shared analytical core.
 * An expected side never replaces a missing or conflicting observed side.
 */
object ActivityStartingSideResolver {
    const val VERSION = "activity-starting-side-v2"

    fun resolve(
        evidence: FourLimbMotionResult,
        context: ActivitySideContext,
        config: StartingSideConfig = StartingSideConfig(),
    ): ActivitySideResolution {
        val pair = when (context.activity) {
            LimbInterpretationActivity.STRAIGHT_PUNCH -> evidence.leftArm to evidence.rightArm
            LimbInterpretationActivity.CHAMBER_EXTENSION_KICK -> evidence.leftLeg to evidence.rightLeg
        }
        fun usable(profile: LimbMotionProfile): Boolean = profile.metrics != null && profile.abstentionReason == null &&
            LimbQualityFlag.GAPS !in profile.qualityFlags &&
            (profile.motionPattern == LimbMotionPattern.STABLE || profile.metrics.motionStartTimestampUs != null)
        val usableProfiles = listOf(pair.first, pair.second).filter(::usable)
        fun qualifyingOpening(profile: LimbMotionProfile): LimbDirectionalEpisode? = profile.directionalEpisodes
            .asSequence()
            .filter { episode ->
                episode.direction > 0 &&
                    episode.excursionDeg >= config.minimumExcursionDeg &&
                    episode.meaningfulTravelDeg >= config.minimumTravelDeg &&
                    episode.observedSupportDurationUs >= evidence.config.onsetConfirmationUs
            }
            .minByOrNull { it.startTimestampUs }
        fun corroboratesPunchOpposite(profile: LimbMotionProfile): Boolean {
            val metrics = profile.metrics ?: return false
            return profile.motionPattern == LimbMotionPattern.STABLE ||
                (profile.motionPattern == LimbMotionPattern.MOSTLY_CLOSING &&
                    metrics.netAngleChangeDeg <= -config.minimumExcursionDeg &&
                    metrics.meaningfulAngularTravelDeg > 0 &&
                    metrics.negativeAngularTravelDeg / metrics.meaningfulAngularTravelDeg >= config.minimumDirectionalShare)
        }
        fun kickCandidate(active: LimbMotionProfile, opposite: LimbMotionProfile): Boolean {
            val a = active.metrics ?: return false
            val b = opposite.metrics ?: return false
            if (a.maxExcursionDeg < config.minimumExcursionDeg || a.meaningfulAngularTravelDeg < config.minimumTravelDeg) return false
            return active.motionPattern == LimbMotionPattern.CLOSE_OPEN_REVERSAL && a.reversals.size == 1 &&
                a.reversals.single().magnitudeDeg >= config.minimumExcursionDeg &&
                a.positiveAngularTravelDeg >= config.minimumTravelDeg && a.negativeAngularTravelDeg >= config.minimumTravelDeg &&
                (opposite.motionPattern == LimbMotionPattern.STABLE ||
                    (b.reversals.isEmpty() && a.meaningfulAngularTravelDeg >= b.meaningfulAngularTravelDeg * config.kickToSupportTravelRatio))
        }
        if (context.activity == LimbInterpretationActivity.STRAIGHT_PUNCH) {
            val episodes = listOf(pair.first, pair.second).map { profile ->
                profile.takeIf(::usable)?.let(::qualifyingOpening)
            }
            val candidates = buildSet {
                if (episodes[0] != null) add(LateralSide.LEFT)
                if (episodes[1] != null) add(LateralSide.RIGHT)
            }
            val candidateSide = candidates.singleOrNull()
            val candidateIndex = when (candidateSide) { LateralSide.LEFT -> 0; LateralSide.RIGHT -> 1; null -> null }
            val opposite = candidateIndex?.let { if (it == 0) pair.second else pair.first }
            val mode = when {
                candidateIndex == null -> null
                !usable(opposite!!) -> SideEvidenceMode.SINGLE_ARM_ONLY
                corroboratesPunchOpposite(opposite) -> SideEvidenceMode.BILATERAL_CORROBORATED
                else -> null
            }
            val side = candidateSide?.takeIf { mode != null }
            val reason = when {
                usableProfiles.isEmpty() -> SideResolutionReason.INADEQUATE_EVIDENCE
                candidates.isEmpty() -> SideResolutionReason.NO_SUPPORTED_PATTERN
                candidates.size > 1 -> SideResolutionReason.MULTIPLE_SUPPORTED_SIDES
                mode == null -> SideResolutionReason.CONFLICTING_OPPOSITE_EVIDENCE
                else -> SideResolutionReason.SINGLE_SUPPORTED_SIDE
            }
            return ActivitySideResolution(
                status = if (side == null) SideResolutionStatus.AMBIGUOUS else SideResolutionStatus.RESOLVED,
                side = side,
                reason = reason,
                supportedCandidates = candidates,
                context = context,
                config = config,
                resolverVersion = VERSION,
                evidenceMode = mode,
                supportingEpisode = candidateIndex?.let { episodes[it] }.takeIf { side != null },
                evidence = evidence,
            )
        }
        val adequate = usable(pair.first) && usable(pair.second)
        val candidates = if (!adequate) emptySet() else buildSet {
            if (kickCandidate(pair.first, pair.second)) add(LateralSide.LEFT)
            if (kickCandidate(pair.second, pair.first)) add(LateralSide.RIGHT)
        }
        val side = candidates.singleOrNull()
        val reason = when {
            !adequate -> SideResolutionReason.INADEQUATE_EVIDENCE
            candidates.isEmpty() -> SideResolutionReason.NO_SUPPORTED_PATTERN
            side == null -> SideResolutionReason.MULTIPLE_SUPPORTED_SIDES
            else -> SideResolutionReason.SINGLE_SUPPORTED_SIDE
        }
        return ActivitySideResolution(if (side == null) SideResolutionStatus.AMBIGUOUS else SideResolutionStatus.RESOLVED,
            side, reason, candidates, context, config, VERSION, evidence = evidence)
    }

    fun resolveSequence(
        movements: List<IndexedLimbMovement>,
        context: ActivitySideContext,
        config: StartingSideConfig = StartingSideConfig(),
    ): ActivitySequenceSideResult {
        require(movements.all { it.repetitionIndex >= 0 }) { "Repetition indices must be zero-based and non-negative" }
        require(movements.zipWithNext().all { (a, b) -> a.repetitionIndex < b.repetitionIndex &&
            a.evidence.logicalEndTimestampUs <= b.evidence.logicalStartTimestampUs }) { "Repetitions must be ordered and non-overlapping" }
        require(movements.map { it.evidence.movementId }.distinct().size == movements.size) { "Movement identities must be unique" }
        require(movements.map { it.evidence.canonicalGeometry.recordingId to it.evidence.landmarkTrackId }.distinct().size <= 1) {
            "Sequence evidence must belong to one recording and landmark track"
        }
        val resolved = movements.map { it to resolve(it.evidence, context, config) }
        // Do not back-infer a missing first side from later repetitions or assumed alternation.
        val start = resolved.firstOrNull()?.takeIf { it.first.repetitionIndex == 0 }?.second?.side
        val repetitions = resolved.map { (movement, observed) ->
            val expected = if (!context.alternating || start == null) null else
                if (movement.repetitionIndex % 2 == 0) start else opposite(start)
            val status = when {
                !context.alternating -> AlternationStatus.NOT_REQUESTED
                expected == null || observed.side == null -> AlternationStatus.UNVERIFIABLE
                observed.side == expected -> AlternationStatus.MATCH
                else -> AlternationStatus.MISMATCH
            }
            RepetitionSideValidation(movement.repetitionIndex, observed, expected, status)
        }
        return ActivitySequenceSideResult(start, if (start == null) SideResolutionStatus.AMBIGUOUS else SideResolutionStatus.RESOLVED, repetitions)
    }

    private fun opposite(side: LateralSide) = if (side == LateralSide.LEFT) LateralSide.RIGHT else LateralSide.LEFT
}
