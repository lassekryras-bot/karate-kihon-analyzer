package dk.lasse.karateanalyzer.capture

import dk.lasse.karateanalyzer.observation.Evidence
import dk.lasse.karateanalyzer.observation.DwellSnapshot
import dk.lasse.karateanalyzer.observation.SustainedCondition
import dk.lasse.karateanalyzer.observation.TimestampedHistory
import dk.lasse.karateanalyzer.observation.TimeoutStatus
import dk.lasse.karateanalyzer.observation.TimeoutTracker
import dk.lasse.karateanalyzer.observation.TransitionRecord
import dk.lasse.karateanalyzer.observation.UnknownEvidencePolicy

enum class MotionSegmentState { BASELINE, ARMED, MOVING, SETTLING, COMPLETE, FAILED }
enum class MotionSegmentFailure { NO_MOVEMENT_TIMEOUT, ACTIVITY_DEADLINE_EXCEEDED }

data class GenericMotionSegmenterConfig(
    val baselineDwellMs: Long = 300L,
    val movementStartDwellMs: Long = 80L,
    val settlingDwellMs: Long = 300L,
    val noMovementTimeoutMs: Long? = 2_000L,
    val startMotionThreshold: Double = 0.20,
    val quietMotionThreshold: Double = 0.08,
    val minimumCoverage: Double = 0.70,
    val terminalPoseSimilarity: Double = 0.80,
    val maximumStableDisplacement: Double = 0.10,
    val historyCapacity: Int = 120,
    val historyDurationMs: Long = 4_000L,
    val endPoseRelationship: EndPoseRelationship = EndPoseRelationship.SAME_AS_START,
    val requiredRegionsForTerminalStillness: Set<AnatomicalRegion> = AnatomicalRegion.entries.toSet(),
    // Task 5H replay rejects default activation; explicit offline candidate runs opt in.
    val enableKinematicsPositiveEvidence: Boolean = false,
    // Phase 4 Top-2 causal kinematics decision layer
    val top2Kinematics: Top2KinematicsConfig = Top2KinematicsConfig(),
    // Phase 5 terminal slow-displacement policy
    val slowDisplacementPolicy: SlowDisplacementPolicy = SlowDisplacementPolicy.TRAILING_WINDOW,
    val settlingLocalDisplacementLimit: Double = 0.03,
) {
    init {
        require(baselineDwellMs >= 0L && movementStartDwellMs >= 0L && settlingDwellMs >= 0L)
        require((noMovementTimeoutMs == null || noMovementTimeoutMs >= 0L) && historyDurationMs >= 0L && historyCapacity > 0)
        require(startMotionThreshold >= quietMotionThreshold && quietMotionThreshold >= 0.0)
        require(minimumCoverage in 0.0..1.0 && terminalPoseSimilarity in 0.0..1.0)
        require(maximumStableDisplacement >= 0.0 && settlingLocalDisplacementLimit >= 0.0)
    }
}

data class MotionSegmentSnapshot(
    val state: MotionSegmentState,
    val failure: MotionSegmentFailure?,
    val baselineReady: Boolean,
    val latestMotionEvidence: Evidence?,
    val latestQuietEvidence: Evidence?,
    val transitions: List<TransitionRecord<MotionSegmentState>>,
    val baselineDwell: DwellSnapshot,
    val movementDwell: DwellSnapshot,
    val completionDwell: DwellSnapshot,
    val kinematicsAlteredDecision: Boolean = false,
    val top2TranslationDecision: KinematicDecisionState? = null,
    val top2AngularDecision: KinematicDecisionState? = null,
)

/**
 * Historical multi-safeguard research segmenter.
 * Archived for offline experiment reproducibility; not used in the production capture pipeline.
 * See docs/validation/task5/archive/README.md.
 */
@Deprecated("Historical multi-safeguard research segmenter. See docs/validation/task5/archive/README.md.")
class GenericMotionSegmenter(
    val config: GenericMotionSegmenterConfig = GenericMotionSegmenterConfig(),
) {
    var state: MotionSegmentState = MotionSegmentState.BASELINE
        private set

    var failure: MotionSegmentFailure? = null
        private set

    var baselineReady: Boolean = false
        private set

    var lastObservationTimestampMs: Long? = null
        private set

    var cueTimestampMs: Long? = null
        private set

    var activityDeadlineMs: Long? = null
        private set

    var latestMotionEvidence: Evidence? = null
        private set

    var latestQuietEvidence: Evidence? = null
        private set

    var latestTop2TranslationDecision: KinematicDecisionState? = null
        private set

    var latestTop2AngularDecision: KinematicDecisionState? = null
        private set

    private var settlingAnchorRelativePose: RelativePose? = null
    private var movementEvidenceObserved = false
    private val baselineDwell = SustainedCondition(config.baselineDwellMs)
    private val movementDwell = SustainedCondition(config.movementStartDwellMs)
    private val completionDwell = SustainedCondition(
        config.settlingDwellMs,
        unknownPolicy = UnknownEvidencePolicy.REMAIN_UNRESOLVED,
    )
    private val noMovementTimeout = TimeoutTracker()
    private val history = TimestampedHistory<MotionObservation>(capacity = config.historyCapacity, maxAgeMs = config.historyDurationMs)
    private val mutableTransitions = mutableListOf<TransitionRecord<MotionSegmentState>>()

    fun arm(atTimestampMs: Long, cueTimestampMs: Long? = null, activityDeadlineMs: Long? = null): MotionSegmentSnapshot {
        require(state == MotionSegmentState.BASELINE) { "Segmenter can only be armed from BASELINE, current state is $state" }
        require(baselineReady) { "A confirmed quiet baseline is required before arming" }
        require(lastObservationTimestampMs == null || atTimestampMs >= lastObservationTimestampMs!!)
        require(cueTimestampMs == null || cueTimestampMs >= atTimestampMs)
        require(activityDeadlineMs == null || activityDeadlineMs >= atTimestampMs)
        this.cueTimestampMs = cueTimestampMs
        this.activityDeadlineMs = activityDeadlineMs
        config.noMovementTimeoutMs?.let { noMovementTimeout.arm(cueTimestampMs ?: atTimestampMs, it) }
        transition(MotionSegmentState.ARMED, atTimestampMs, "armed")
        return snapshot()
    }

    fun rearmAfterCompletion(atTimestampMs: Long): MotionSegmentSnapshot {
        require(state == MotionSegmentState.COMPLETE) { "Segmenter can only be rearmed from COMPLETE, current state is $state" }
        require(lastObservationTimestampMs == null || atTimestampMs >= lastObservationTimestampMs!!)
        mutableTransitions.clear()
        failure = null
        cueTimestampMs = null
        activityDeadlineMs = null
        latestMotionEvidence = null
        latestQuietEvidence = null
        latestTop2TranslationDecision = null
        latestTop2AngularDecision = null
        settlingAnchorRelativePose = null
        movementEvidenceObserved = false
        baselineDwell.reset()
        movementDwell.reset()
        completionDwell.reset()
        noMovementTimeout.reset()
        config.noMovementTimeoutMs?.let { noMovementTimeout.arm(atTimestampMs, it) }
        transition(MotionSegmentState.ARMED, atTimestampMs, "rearmed_from_stable_terminal_pose")
        return snapshot()
    }

    fun accept(observation: MotionObservation): MotionSegmentSnapshot {
        val previousTimestamp = lastObservationTimestampMs
        if (previousTimestamp != null && observation.timestampMs <= previousTimestamp) {
            throw IllegalArgumentException("Observation timestamp ${observation.timestampMs} must be greater than $previousTimestamp")
        }
        lastObservationTimestampMs = observation.timestampMs
        history.add(observation.timestampMs, observation)

        val previousState = state
        if (config.top2Kinematics.enabled) {
            val top2 = observation.kinematics?.top2
            latestTop2TranslationDecision = config.top2Kinematics.classifyTranslation(top2?.translationEvidence)
            latestTop2AngularDecision = config.top2Kinematics.classifyAngular(top2?.angularEvidence)
        } else {
            latestTop2TranslationDecision = null
            latestTop2AngularDecision = null
        }

        latestMotionEvidence = movementEvidence(observation)
        latestQuietEvidence = quietEvidence(observation)

        val alteredEvidence = isKinematicsAlteringEvidence(observation)

        when (state) {
            MotionSegmentState.BASELINE -> {
                baselineReady = baselineDwell.update(observation.timestampMs, latestQuietEvidence!!).fulfilled
            }
            MotionSegmentState.ARMED -> processArmed(observation)
            MotionSegmentState.MOVING -> processMoving(observation)
            MotionSegmentState.SETTLING -> processSettling(observation)
            MotionSegmentState.COMPLETE, MotionSegmentState.FAILED -> Unit
        }

        val alteredDecision = alteredEvidence && previousState in setOf(
            MotionSegmentState.BASELINE, MotionSegmentState.ARMED, MotionSegmentState.MOVING, MotionSegmentState.SETTLING,
        )
        return snapshot(kinematicsAlteredDecision = alteredDecision)
    }

    fun reset() {
        state = MotionSegmentState.BASELINE
        failure = null
        baselineReady = false
        lastObservationTimestampMs = null
        cueTimestampMs = null
        activityDeadlineMs = null
        latestMotionEvidence = null
        latestQuietEvidence = null
        latestTop2TranslationDecision = null
        latestTop2AngularDecision = null
        settlingAnchorRelativePose = null
        movementEvidenceObserved = false
        baselineDwell.reset()
        movementDwell.reset()
        completionDwell.reset()
        noMovementTimeout.reset()
        history.reset()
        mutableTransitions.clear()
    }

    fun snapshot(kinematicsAlteredDecision: Boolean = false) = MotionSegmentSnapshot(
        state,
        failure,
        baselineReady,
        latestMotionEvidence,
        latestQuietEvidence,
        mutableTransitions.toList(),
        baselineDwell.snapshot(),
        movementDwell.snapshot(),
        completionDwell.snapshot(),
        kinematicsAlteredDecision,
        latestTop2TranslationDecision,
        latestTop2AngularDecision,
    )

    fun observationHistory() = history.values()

    private fun isLimbMovingInMonitoredChains(observation: MotionObservation): Boolean {
        if (config.top2Kinematics.enabled) {
            return latestTop2TranslationDecision == KinematicDecisionState.MOVING ||
                latestTop2AngularDecision == KinematicDecisionState.MOVING
        }
        if (!config.enableKinematicsPositiveEvidence) return false
        val kinematics = observation.kinematics?.positiveEvidence ?: return false
        return kinematics.anyLimbMotion == Evidence.TRUE
    }

    private fun isKinematicsAlteringEvidence(observation: MotionObservation): Boolean {
        if (!config.enableKinematicsPositiveEvidence && !config.top2Kinematics.enabled) return false
        val baseMotion = observation.articulatedMotion?.let { it >= config.startMotionThreshold } == true ||
            observation.imageSpaceMotion?.let { it >= config.startMotionThreshold } == true
        val baseQuiet = baselineQuietEvidence(observation) == Evidence.TRUE
        return (latestMotionEvidence == Evidence.TRUE && !baseMotion) ||
            (latestQuietEvidence == Evidence.FALSE && baseQuiet)
    }

    private fun processArmed(observation: MotionObservation) {
        if (deadlineExceeded(observation.timestampMs)) return
        val motion = latestMotionEvidence
        if (motion == Evidence.TRUE) {
            movementEvidenceObserved = true
            val dwell = movementDwell.update(observation.timestampMs, Evidence.TRUE)
            if (dwell.fulfilled) {
                val boundary = selectedStartBoundary(dwell.firstSupportingTimestampMs)
                transition(MotionSegmentState.MOVING, observation.timestampMs, "sustained_motion", boundary, observation.coverage)
            }
        } else {
            movementDwell.reset()
            val timeoutStart = cueTimestampMs ?: noMovementTimeout.armTimestamp()
            if (timeoutStart != null && !movementEvidenceObserved && observation.timestampMs >= timeoutStart &&
                noMovementTimeout.update(observation.timestampMs).status == TimeoutStatus.TIMED_OUT
            ) fail(MotionSegmentFailure.NO_MOVEMENT_TIMEOUT, observation.timestampMs, "no_movement_timeout")
        }
    }

    private fun processMoving(observation: MotionObservation) {
        if (deadlineExceeded(observation.timestampMs)) return
        if (latestQuietEvidence == Evidence.TRUE) {
            completionDwell.reset()
            settlingAnchorRelativePose = observation.relativePose
            transition(MotionSegmentState.SETTLING, observation.timestampMs, "quiet_candidate", observation.timestampMs, observation.coverage)
            completionDwell.update(observation.timestampMs, completionEvidence(observation))
        }
    }

    private fun selectedStartBoundary(firstSupportMs: Long?): Long? {
        if (firstSupportMs == null) return null
        if (config.top2Kinematics.enabled) {
            return firstSupportMs
        }
        if (!config.enableKinematicsPositiveEvidence) return firstSupportMs
        val samples = history.values().map { it.value }
        val support = samples.firstOrNull { it.timestampMs == firstSupportMs } ?: return firstSupportMs
        val kinematics = support.kinematics?.positiveEvidence ?: return firstSupportMs
        val triggers = kinematics.channels.filter { it.usedInGating && it.evidence == Evidence.TRUE }.map { it.name }.toSet()
        if (triggers.isEmpty()) return firstSupportMs
        var boundary = firstSupportMs
        val armedAt = mutableTransitions.lastOrNull { it.newState == MotionSegmentState.ARMED }?.decisionTimestampMs ?: firstSupportMs
        for (sample in samples.asReversed().filter { it.timestampMs < firstSupportMs &&
            it.timestampMs >= maxOf(armedAt, firstSupportMs - kinematics.windowDurationMs) }) {
            val rawSupport = sample.kinematics?.positiveEvidence?.channels?.any {
                it.name in triggers && it.rawValue != null &&
                    kotlin.math.abs(it.rawValue) >= it.threshold
            } == true
            if (!rawSupport) break
            boundary = sample.timestampMs
        }
        val firstRaw = samples.firstOrNull { it.timestampMs == boundary }
        if (firstRaw?.kinematics?.positiveEvidence?.channels?.any {
                it.name in triggers && it.rawValue != null && kotlin.math.abs(it.rawValue) >= it.threshold
            } == true) {
            boundary = samples.lastOrNull { it.timestampMs < boundary && it.timestampMs >= armedAt }?.timestampMs ?: boundary
        }
        return boundary
    }

    private fun processSettling(observation: MotionObservation) {
        if (deadlineExceeded(observation.timestampMs)) return
        if (latestMotionEvidence == Evidence.TRUE) {
            completionDwell.reset()
            settlingAnchorRelativePose = null
            transition(MotionSegmentState.MOVING, observation.timestampMs, "movement_resumed", observation.timestampMs, observation.coverage)
            return
        }
        val dwell = completionDwell.update(observation.timestampMs, completionEvidence(observation))
        if (dwell.fulfilled) {
            transition(
                MotionSegmentState.COMPLETE,
                observation.timestampMs,
                "terminal_stability_confirmed",
                dwell.firstSupportingTimestampMs,
                observation.coverage,
            )
        }
    }

    private fun deadlineExceeded(timestampMs: Long): Boolean {
        val deadline = activityDeadlineMs ?: return false
        if (timestampMs < deadline) return false
        fail(MotionSegmentFailure.ACTIVITY_DEADLINE_EXCEEDED, timestampMs, "activity_deadline_exceeded")
        return true
    }

    private fun movementEvidence(observation: MotionObservation): Evidence {
        if (config.top2Kinematics.enabled) {
            val dt = latestTop2TranslationDecision ?: KinematicDecisionState.UNKNOWN
            val da = latestTop2AngularDecision ?: KinematicDecisionState.UNKNOWN
            if (dt == KinematicDecisionState.MOVING || da == KinematicDecisionState.MOVING) {
                return Evidence.TRUE
            }
            if (dt == KinematicDecisionState.UNKNOWN || da == KinematicDecisionState.UNKNOWN ||
                coverageEvidence(observation) != Evidence.TRUE) {
                return Evidence.UNKNOWN
            }
            return Evidence.FALSE
        }
        val limbMoving = isLimbMovingInMonitoredChains(observation)
        return when {
            observation.articulatedMotion?.let { it >= config.startMotionThreshold } == true -> Evidence.TRUE
            observation.imageSpaceMotion?.let { it >= config.startMotionThreshold } == true -> Evidence.TRUE
            limbMoving -> Evidence.TRUE
            coverageEvidence(observation) != Evidence.TRUE -> Evidence.UNKNOWN
            observation.articulatedMotion == null || observation.imageSpaceMotion == null -> Evidence.UNKNOWN
            else -> Evidence.FALSE
        }
    }

    private fun baselineQuietEvidence(observation: MotionObservation): Evidence {
        val coverage = coverageEvidence(observation)
        if (coverage != Evidence.TRUE) return coverage
        if (observation.articulatedMotion == null || observation.imageSpaceMotion == null) return Evidence.UNKNOWN
        if (observation.accumulatedDisplacement == null) return Evidence.UNKNOWN
        if (observation.accumulatedDisplacement > config.maximumStableDisplacement) return Evidence.FALSE
        if (observation.articulatedMotion > config.quietMotionThreshold) return Evidence.FALSE
        if (observation.imageSpaceMotion > config.quietMotionThreshold) return Evidence.FALSE
        return Evidence.TRUE
    }

    private fun quietEvidence(observation: MotionObservation): Evidence {
        if (config.top2Kinematics.enabled) {
            val coverage = coverageEvidence(observation)
            if (coverage != Evidence.TRUE) return coverage

            val dt = latestTop2TranslationDecision ?: KinematicDecisionState.UNKNOWN
            val da = latestTop2AngularDecision ?: KinematicDecisionState.UNKNOWN

            if (dt == KinematicDecisionState.MOVING || da == KinematicDecisionState.MOVING) {
                return Evidence.FALSE
            }
            if (dt == KinematicDecisionState.UNKNOWN || da == KinematicDecisionState.UNKNOWN) {
                return Evidence.UNKNOWN
            }
            if (dt == KinematicDecisionState.MID || da == KinematicDecisionState.MID) {
                return Evidence.FALSE
            }
            if (config.slowDisplacementPolicy == SlowDisplacementPolicy.TRAILING_WINDOW) {
                if (observation.accumulatedDisplacement == null) return Evidence.UNKNOWN
                if (observation.accumulatedDisplacement > config.maximumStableDisplacement) return Evidence.FALSE
            }
            return Evidence.TRUE
        }
        val baseQuiet = baselineQuietEvidence(observation)
        if (baseQuiet != Evidence.TRUE) return baseQuiet
        if (isLimbMovingInMonitoredChains(observation)) return Evidence.FALSE
        return Evidence.TRUE
    }

    private fun coverageEvidence(observation: MotionObservation): Evidence {
        val regions = observation.diagnostics?.regions
        if (regions != null) {
            val missingOrLow = config.requiredRegionsForTerminalStillness.any { region ->
                val diag = regions[region]
                diag == null || diag.coverage < config.minimumCoverage
            }
            return if (missingOrLow) Evidence.UNKNOWN else Evidence.TRUE
        }
        return if (observation.coverage >= config.minimumCoverage) Evidence.TRUE else Evidence.UNKNOWN
    }

    private fun completionEvidence(observation: MotionObservation): Evidence {
        val coverage = coverageEvidence(observation)
        if (coverage != Evidence.TRUE) return Evidence.FALSE

        if (config.top2Kinematics.enabled) {
            val dt = latestTop2TranslationDecision ?: KinematicDecisionState.UNKNOWN
            val da = latestTop2AngularDecision ?: KinematicDecisionState.UNKNOWN

            if (dt == KinematicDecisionState.MOVING || da == KinematicDecisionState.MOVING) {
                return Evidence.FALSE
            }
            if (dt == KinematicDecisionState.UNKNOWN || da == KinematicDecisionState.UNKNOWN) {
                return Evidence.UNKNOWN
            }
            if (dt == KinematicDecisionState.MID || da == KinematicDecisionState.MID) {
                return Evidence.UNKNOWN
            }

            when (config.slowDisplacementPolicy) {
                SlowDisplacementPolicy.TRAILING_WINDOW -> {
                    if (observation.accumulatedDisplacement != null &&
                        observation.accumulatedDisplacement > config.maximumStableDisplacement) {
                        return Evidence.FALSE
                    }
                }
                SlowDisplacementPolicy.DISABLED -> {
                }
                SlowDisplacementPolicy.SETTLING_LOCAL -> {
                    val anchor = settlingAnchorRelativePose
                    val current = observation.relativePose
                    if (anchor != null && current != null) {
                        val d = relativePoseDistance(anchor, current, config.minimumCoverage)
                        if (d != null && d > config.settlingLocalDisplacementLimit) {
                            return Evidence.FALSE
                        }
                    }
                }
            }
        } else {
            val quiet = quietEvidence(observation)
            if (quiet != Evidence.TRUE) return quiet
        }

        return when (config.endPoseRelationship) {
            EndPoseRelationship.SAME_AS_START -> similarityEvidence(observation.sameAsStartSimilarity, greaterThan = true)
            EndPoseRelationship.MIRRORED_START -> similarityEvidence(observation.mirroredStartSimilarity, greaterThan = true)
            EndPoseRelationship.DIFFERENT_STABLE_POSE -> similarityEvidence(observation.sameAsStartSimilarity, greaterThan = false)
            EndPoseRelationship.ANY_STABLE_POSE_AFTER_MOVEMENT -> Evidence.TRUE
        }
    }

    private fun similarityEvidence(value: Double?, greaterThan: Boolean): Evidence = when {
        value == null -> Evidence.UNKNOWN
        greaterThan && value >= config.terminalPoseSimilarity -> Evidence.TRUE
        !greaterThan && value < config.terminalPoseSimilarity -> Evidence.TRUE
        else -> Evidence.FALSE
    }

    private fun fail(value: MotionSegmentFailure, timestampMs: Long, trigger: String) {
        failure = value
        transition(MotionSegmentState.FAILED, timestampMs, trigger)
    }

    private fun transition(
        newState: MotionSegmentState,
        decisionTimestampMs: Long,
        trigger: String,
        estimatedBoundaryTimestampMs: Long? = null,
        quality: Double? = null,
    ) {
        val previous = state
        state = newState
        mutableTransitions += TransitionRecord(
            previousState = previous,
            newState = newState,
            decisionTimestampMs = decisionTimestampMs,
            estimatedBoundaryTimestampMs = estimatedBoundaryTimestampMs,
            trigger = trigger,
            quality = quality,
        )
    }
}
