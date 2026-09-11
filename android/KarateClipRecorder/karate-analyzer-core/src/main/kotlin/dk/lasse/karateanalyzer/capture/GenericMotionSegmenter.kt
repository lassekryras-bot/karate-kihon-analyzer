package dk.lasse.karateanalyzer.capture

import dk.lasse.karateanalyzer.observation.Evidence
import dk.lasse.karateanalyzer.observation.DwellSnapshot
import dk.lasse.karateanalyzer.observation.SustainedCondition
import dk.lasse.karateanalyzer.observation.TimestampedHistory
import dk.lasse.karateanalyzer.observation.TimeoutStatus
import dk.lasse.karateanalyzer.observation.TimeoutTracker
import dk.lasse.karateanalyzer.observation.TransitionRecord

enum class MotionSegmentState { BASELINE, ARMED, MOVING, SETTLING, COMPLETE, FAILED }
enum class MotionSegmentFailure { NO_MOVEMENT_TIMEOUT, ACTIVITY_DEADLINE_EXCEEDED }
enum class EndPoseRelationship { SAME_AS_START, MIRRORED_START, DIFFERENT_STABLE_POSE }

data class MotionObservation(
    val timestampMs: Long,
    val articulatedMotion: Double?,
    val imageSpaceMotion: Double?,
    val coverage: Double,
    val sameAsStartSimilarity: Double? = null,
    val mirroredStartSimilarity: Double? = null,
    val accumulatedDisplacement: Double? = null,
    val diagnostics: MotionObservationDiagnostics? = null,
) {
    init {
        require(coverage.isFinite() && coverage in 0.0..1.0)
        listOf(articulatedMotion, imageSpaceMotion, sameAsStartSimilarity, mirroredStartSimilarity, accumulatedDisplacement)
            .filterNotNull().forEach { require(it.isFinite()) }
    }
}

enum class MotionChannelStatus { VALID, FIRST_FRAME, INSUFFICIENT_COVERAGE, INVALID_BODY_SCALE, TIMESTAMP_GAP }

data class RegionMotionDiagnostics(
    val expectedLandmarkCount: Int,
    val usableLandmarkCount: Int,
    val coverage: Double,
    val rawMotion: Double?,
    val robustMotion: Double?,
    val clampedLandmarkCount: Int,
)

data class MotionObservationDiagnostics(
    val articulatedStatus: MotionChannelStatus,
    val imageSpaceStatus: MotionChannelStatus,
    val elapsedMs: Long?,
    val bodyScaleWorld: Double?,
    val bodyScaleImage: Double?,
    val imageCenterTranslation: Double?,
    val imageScaleChange: Double?,
    val minimumRegionCoverage: Double,
    val regionBalancedCoverage: Double,
    val baselineSampleCount: Int,
    val baselineReady: Boolean,
    val regions: Map<AnatomicalRegion, RegionMotionDiagnostics>,
)

data class GenericMotionSegmenterConfig(
    val baselineDwellMs: Long = 300L,
    val movementStartDwellMs: Long = 80L,
    val settlingDwellMs: Long = 300L,
    val noMovementTimeoutMs: Long = 2_000L,
    val startMotionThreshold: Double = 0.20,
    val quietMotionThreshold: Double = 0.08,
    val minimumCoverage: Double = 0.70,
    val terminalPoseSimilarity: Double = 0.80,
    val maximumStableDisplacement: Double = 0.10,
    val historyCapacity: Int = 120,
    val historyDurationMs: Long = 4_000L,
    val endPoseRelationship: EndPoseRelationship = EndPoseRelationship.SAME_AS_START,
) {
    init {
        require(baselineDwellMs >= 0L && movementStartDwellMs >= 0L && settlingDwellMs >= 0L)
        require(noMovementTimeoutMs >= 0L && historyDurationMs >= 0L && historyCapacity > 0)
        require(startMotionThreshold >= quietMotionThreshold && quietMotionThreshold >= 0.0)
        require(minimumCoverage in 0.0..1.0 && terminalPoseSimilarity in 0.0..1.0)
        require(maximumStableDisplacement >= 0.0)
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
)

/** Capture-oriented proof consumer. Values and boundaries are provisional, not coaching truth. */
class GenericMotionSegmenter(private val config: GenericMotionSegmenterConfig = GenericMotionSegmenterConfig()) {
    private var state = MotionSegmentState.BASELINE
    private var failure: MotionSegmentFailure? = null
    private var baselineReady = false
    private var lastObservationTimestampMs: Long? = null
    private var cueTimestampMs: Long? = null
    private var activityDeadlineMs: Long? = null
    private var latestMotionEvidence: Evidence? = null
    private var latestQuietEvidence: Evidence? = null
    private var movementEvidenceObserved = false
    private val baselineDwell = SustainedCondition(config.baselineDwellMs)
    private val movementDwell = SustainedCondition(config.movementStartDwellMs)
    private val completionDwell = SustainedCondition(config.settlingDwellMs)
    private val noMovementTimeout = TimeoutTracker()
    private val history = TimestampedHistory<MotionObservation>(config.historyCapacity, config.historyDurationMs)
    private val mutableTransitions = mutableListOf<TransitionRecord<MotionSegmentState>>()

    fun arm(atTimestampMs: Long, cueTimestampMs: Long? = null, activityDeadlineMs: Long? = null): MotionSegmentSnapshot {
        check(state == MotionSegmentState.BASELINE && baselineReady) { "A stable baseline is required before arming" }
        require(lastObservationTimestampMs == null || atTimestampMs >= lastObservationTimestampMs!!)
        require(cueTimestampMs == null || cueTimestampMs >= atTimestampMs)
        require(activityDeadlineMs == null || activityDeadlineMs >= atTimestampMs)
        this.cueTimestampMs = cueTimestampMs
        this.activityDeadlineMs = activityDeadlineMs
        noMovementTimeout.arm(cueTimestampMs ?: atTimestampMs, config.noMovementTimeoutMs)
        transition(MotionSegmentState.ARMED, atTimestampMs, "armed")
        return snapshot()
    }

    fun accept(observation: MotionObservation): MotionSegmentSnapshot {
        val previousTimestamp = lastObservationTimestampMs
        if (previousTimestamp != null && observation.timestampMs <= previousTimestamp) {
            throw IllegalArgumentException("Observation timestamp ${observation.timestampMs} must be greater than $previousTimestamp")
        }
        lastObservationTimestampMs = observation.timestampMs
        history.add(observation.timestampMs, observation)
        latestMotionEvidence = movementEvidence(observation)
        latestQuietEvidence = quietEvidence(observation)

        when (state) {
            MotionSegmentState.BASELINE -> {
                baselineReady = baselineDwell.update(observation.timestampMs, latestQuietEvidence!!).fulfilled
            }
            MotionSegmentState.ARMED -> processArmed(observation)
            MotionSegmentState.MOVING -> processMoving(observation)
            MotionSegmentState.SETTLING -> processSettling(observation)
            MotionSegmentState.COMPLETE, MotionSegmentState.FAILED -> Unit
        }
        return snapshot()
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
        movementEvidenceObserved = false
        baselineDwell.reset()
        movementDwell.reset()
        completionDwell.reset()
        noMovementTimeout.reset()
        history.reset()
        mutableTransitions.clear()
    }

    fun snapshot() = MotionSegmentSnapshot(
        state,
        failure,
        baselineReady,
        latestMotionEvidence,
        latestQuietEvidence,
        mutableTransitions.toList(),
        baselineDwell.snapshot(),
        movementDwell.snapshot(),
        completionDwell.snapshot(),
    )

    fun observationHistory() = history.values()

    private fun processArmed(observation: MotionObservation) {
        if (latestMotionEvidence == Evidence.TRUE) movementEvidenceObserved = true
        val dwell = movementDwell.update(observation.timestampMs, latestMotionEvidence!!)
        if (dwell.fulfilled) {
            transition(
                MotionSegmentState.MOVING,
                observation.timestampMs,
                "sustained_motion",
                dwell.firstSupportingTimestampMs,
                observation.coverage,
            )
            return
        }
        val timeoutStart = cueTimestampMs ?: checkNotNull(noMovementTimeout.armTimestamp())
        if (!movementEvidenceObserved && observation.timestampMs >= timeoutStart &&
            noMovementTimeout.update(observation.timestampMs).status == TimeoutStatus.TIMED_OUT
        ) fail(MotionSegmentFailure.NO_MOVEMENT_TIMEOUT, observation.timestampMs, "no_movement_timeout")
    }

    private fun processMoving(observation: MotionObservation) {
        if (deadlineExceeded(observation.timestampMs)) return
        if (latestQuietEvidence == Evidence.TRUE) {
            completionDwell.reset()
            transition(MotionSegmentState.SETTLING, observation.timestampMs, "quiet_candidate", observation.timestampMs, observation.coverage)
            completionDwell.update(observation.timestampMs, completionEvidence(observation))
        }
    }

    private fun processSettling(observation: MotionObservation) {
        if (deadlineExceeded(observation.timestampMs)) return
        if (latestMotionEvidence == Evidence.TRUE) {
            completionDwell.reset()
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

    private fun movementEvidence(observation: MotionObservation): Evidence = when {
        observation.articulatedMotion?.let { it >= config.startMotionThreshold } == true -> Evidence.TRUE
        observation.imageSpaceMotion?.let { it >= config.startMotionThreshold } == true -> Evidence.TRUE
        observation.coverage < config.minimumCoverage -> Evidence.UNKNOWN
        observation.articulatedMotion == null || observation.imageSpaceMotion == null -> Evidence.UNKNOWN
        else -> Evidence.FALSE
    }

    private fun quietEvidence(observation: MotionObservation): Evidence = when {
        observation.coverage < config.minimumCoverage -> Evidence.UNKNOWN
        observation.articulatedMotion == null || observation.imageSpaceMotion == null -> Evidence.UNKNOWN
        observation.accumulatedDisplacement == null -> Evidence.UNKNOWN
        observation.accumulatedDisplacement > config.maximumStableDisplacement -> Evidence.FALSE
        observation.articulatedMotion <= config.quietMotionThreshold &&
            observation.imageSpaceMotion <= config.quietMotionThreshold -> Evidence.TRUE
        else -> Evidence.FALSE
    }

    private fun completionEvidence(observation: MotionObservation): Evidence {
        val quiet = quietEvidence(observation)
        if (quiet != Evidence.TRUE) return quiet
        return when (config.endPoseRelationship) {
            EndPoseRelationship.SAME_AS_START -> similarityEvidence(observation.sameAsStartSimilarity, greaterThan = true)
            EndPoseRelationship.MIRRORED_START -> similarityEvidence(observation.mirroredStartSimilarity, greaterThan = true)
            EndPoseRelationship.DIFFERENT_STABLE_POSE -> similarityEvidence(observation.sameAsStartSimilarity, greaterThan = false)
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
