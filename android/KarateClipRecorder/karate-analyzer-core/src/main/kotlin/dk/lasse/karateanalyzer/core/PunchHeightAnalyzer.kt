package dk.lasse.karateanalyzer.core

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.sqrt

class PunchHeightAnalyzer {
    private val tracker = TemporalPoseTracker()
    private val setupSamples = mutableListOf<Pair<Long, ReferenceCandidate>>()
    private val initializationSamples = mutableListOf<Pair<Long, ReferenceCandidate>>()
    private val chinEstimator = SideViewChinEstimator()
    private val jodanModel = JodanTargetModel(chinEstimator)
    private val chudanModel = ChudanTargetModel()
    private val gedanModel = GedanTargetModel()
    private var bodyReference: BodyReference? = null
    private var lockedArm = ActiveArm.NONE
    private var armCandidate = ActiveArm.NONE
    private var armCandidateSinceMs: Long? = null
    private var armLostSinceMs: Long? = null
    private val stabilitySamples = ArrayDeque<StabilitySample>()
    private var holdStartedAtMs: Long? = null
    private var captureEmitted = false

    fun reset() {
        tracker.reset()
        setupSamples.clear()
        initializationSamples.clear()
        bodyReference = null
        chinEstimator.reset()
        resetTarget()
    }

    fun resetSetup() {
        setupSamples.clear()
    }

    fun resetBodyInitialization() {
        initializationSamples.clear()
        bodyReference = null
        chinEstimator.reset()
    }

    fun resetTarget() {
        lockedArm = ActiveArm.NONE
        armCandidate = ActiveArm.NONE
        armCandidateSinceMs = null
        armLostSinceMs = null
        stabilitySamples.clear()
        holdStartedAtMs = null
        captureEmitted = false
    }

    fun currentBodyReference(): BodyReference? = bodyReference

    fun processSetup(rawFrame: PoseFrame): SetupEvaluation {
        val frame = tracker.track(rawFrame)
        val candidate = referenceCandidate(frame)
            ?: return setupFailure(rawFrame.timestampMs, SetupGuidance.NO_BODY)
        val positions = candidate.criticalPositions
        val head = candidate.headPoint
        if (head == null || !head.insideFrame(FRAME_MARGIN)) {
            return setupFailure(rawFrame.timestampMs, SetupGuidance.KEEP_HEAD_IN_FRAME, candidate.torsoLength)
        }
        if (positions.any { !it.insideFrame(FRAME_MARGIN) }) {
            return setupFailure(rawFrame.timestampMs, SetupGuidance.KEEP_ARM_IN_FRAME, candidate.torsoLength)
        }
        if (candidate.torsoLength > MAX_SETUP_TORSO_LENGTH) {
            return setupFailure(rawFrame.timestampMs, SetupGuidance.MOVE_FARTHER_BACK, candidate.torsoLength)
        }
        if (candidate.torsoLength < MIN_SETUP_TORSO_LENGTH) {
            return setupFailure(rawFrame.timestampMs, SetupGuidance.MOVE_CLOSER, candidate.torsoLength)
        }
        if (candidate.shoulderBreadth / candidate.torsoLength > MAX_SIDEWAYS_SHOULDER_RATIO) {
            return setupFailure(rawFrame.timestampMs, SetupGuidance.TURN_MORE_SIDEWAYS, candidate.torsoLength)
        }
        setupSamples += rawFrame.timestampMs to candidate
        setupSamples.removeAll { rawFrame.timestampMs - it.first > SETUP_HOLD_MS }
        val elapsed = setupSamples.firstOrNull()?.let { rawFrame.timestampMs - it.first } ?: 0L
        val stable = setupSamples.size >= SETUP_MIN_SAMPLES && referenceJitter(setupSamples) <= MAX_REFERENCE_JITTER
        val progress = minOf(elapsed.toFloat() / SETUP_HOLD_MS, setupSamples.size.toFloat() / SETUP_MIN_SAMPLES)
            .coerceIn(0f, 1f)
        return if (stable && elapsed >= SETUP_HOLD_MS) {
            SetupEvaluation(SetupGuidance.CAMERA_READY, 1f, true, candidate.torsoLength)
        } else {
            SetupEvaluation(SetupGuidance.HOLD_STILL, progress, false, candidate.torsoLength)
        }
    }

    fun processBodyInitialization(rawFrame: PoseFrame, chinProjectionMultiplier: Float): BodyInitializationEvaluation {
        val frame = tracker.track(rawFrame)
        val candidate = referenceCandidate(frame) ?: return BodyInitializationEvaluation(0f, null)
        initializationSamples += rawFrame.timestampMs to candidate
        initializationSamples.removeAll { rawFrame.timestampMs - it.first > INITIALIZATION_WINDOW_MS }
        val elapsed = initializationSamples.firstOrNull()?.let { rawFrame.timestampMs - it.first } ?: 0L
        val progress = minOf(
            elapsed.toFloat() / INITIALIZATION_WINDOW_MS,
            initializationSamples.size.toFloat() / INITIALIZATION_MIN_SAMPLES,
        ).coerceIn(0f, 1f)
        if (
            elapsed < INITIALIZATION_WINDOW_MS ||
            initializationSamples.size < INITIALIZATION_MIN_SAMPLES ||
            referenceJitter(initializationSamples) > MAX_REFERENCE_JITTER
        ) return BodyInitializationEvaluation(progress, null)

        val leftWins = initializationSamples.count { it.second.preferredSide == VisibleSide.LEFT }
        val rightWins = initializationSamples.count { it.second.preferredSide == VisibleSide.RIGHT }
        val total = initializationSamples.size.toFloat()
        val side = when {
            leftWins / total >= SIDE_LOCK_FRACTION && averageSideMargin(VisibleSide.LEFT) >= SIDE_QUALITY_MARGIN -> VisibleSide.LEFT
            rightWins / total >= SIDE_LOCK_FRACTION && averageSideMargin(VisibleSide.RIGHT) >= SIDE_QUALITY_MARGIN -> VisibleSide.RIGHT
            else -> return BodyInitializationEvaluation(progress, null)
        }
        val shoulder = average(initializationSamples.map { it.second.shoulderPoint })
        val hip = average(initializationSamples.map { it.second.hipPoint })
        val torsoVector = hip - shoulder
        val torsoLength = torsoVector.length2d()
        if (torsoLength <= 0f) return BodyInitializationEvaluation(progress, null)
        val reference = BodyReference(
            visibleSide = side,
            shoulderPoint = shoulder,
            hipPoint = hip,
            torsoAxis = torsoVector * (1f / torsoLength),
            torsoLength = torsoLength,
            confidence = initializationSamples.map { it.second.confidence }.average().toFloat().coerceIn(0f, 1f),
        )
        bodyReference = reference
        chinEstimator.initialize(frame, reference, chinProjectionMultiplier)
        return BodyInitializationEvaluation(1f, reference)
    }

    fun evaluateTarget(
        targetType: PunchHeightTargetType,
        rawFrame: PoseFrame,
        chinProjectionMultiplier: Float,
    ): PunchHeightEvaluation? {
        val reference = bodyReference ?: return null
        val frame = tracker.track(rawFrame)
        val target = targetModel(targetType).evaluate(frame, reference, chinProjectionMultiplier)
        val bodyReliable = listOf(
            PoseLandmarkId.LEFT_SHOULDER,
            PoseLandmarkId.RIGHT_SHOULDER,
            PoseLandmarkId.LEFT_HIP,
            PoseLandmarkId.RIGHT_HIP,
        ).all { frame.landmarks[it]?.isObserved() == true }
        val arm = selectArm(frame, reference)
        val armGeometry = arm.takeUnless { it == ActiveArm.NONE }?.let { armGeometry(frame, it, reference) }
        val fist = armGeometry?.fistCenter
        val targetError = if (target != null && fist != null) {
            ((fist - target.targetPoint).dot2d(reference.torsoAxis) / reference.torsoLength)
        } else null
        val inside = target != null && targetError != null && abs(targetError) <= target.tolerance
        val extended = armGeometry?.elbowAngleDegrees?.let { it >= CAPTURE_ELBOW_ANGLE } == true
        val criticalReliable = armGeometry?.captureReliable == true
        updateStability(rawFrame.timestampMs, fist, target?.targetPoint, frame, reference)
        val stable = motionWithinLimits(reference)
        val captureConditions = target != null &&
            target.captureEligible && target.confidence >= TARGET_CAPTURE_CONFIDENCE &&
            bodyReliable && arm != ActiveArm.NONE && criticalReliable && extended && inside && stable
        if (captureConditions) {
            if (holdStartedAtMs == null) holdStartedAtMs = rawFrame.timestampMs
        } else {
            holdStartedAtMs = null
        }
        val holdMs = holdStartedAtMs?.let { rawFrame.timestampMs - it }?.coerceAtLeast(0L) ?: 0L
        val ready = holdMs >= CAPTURE_HOLD_MS && !captureEmitted
        if (ready) captureEmitted = true
        val guidance = when {
            ready -> PunchHeightGuidanceState.CAPTURE_READY
            !bodyReliable || target == null || !target.captureEligible || target.confidence < TARGET_CAPTURE_CONFIDENCE -> PunchHeightGuidanceState.TRACKING_UNRELIABLE
            arm == ActiveArm.NONE || armGeometry == null -> PunchHeightGuidanceState.WAITING_FOR_ARM
            !criticalReliable -> PunchHeightGuidanceState.TRACKING_UNRELIABLE
            !extended -> PunchHeightGuidanceState.ARM_NOT_EXTENDED
            targetError == null -> PunchHeightGuidanceState.TRACKING_UNRELIABLE
            targetError < -target.tolerance -> PunchHeightGuidanceState.FIST_TOO_HIGH
            targetError > target.tolerance -> PunchHeightGuidanceState.FIST_TOO_LOW
            !stable -> PunchHeightGuidanceState.CORRECT_BUT_MOVING
            else -> PunchHeightGuidanceState.CORRECT_AND_HOLDING
        }
        return PunchHeightEvaluation(
            timestampMs = rawFrame.timestampMs,
            target = target,
            guidance = guidance,
            activeArm = arm,
            fistCenter = fist,
            elbowPoint = armGeometry?.elbow,
            shoulderPoint = armGeometry?.shoulder,
            wristPoint = armGeometry?.wrist,
            elbowAngleDegrees = armGeometry?.elbowAngleDegrees,
            signedHeightErrorTorsoRatio = targetError,
            holdProgress = (holdMs.toFloat() / CAPTURE_HOLD_MS).coerceIn(0f, 1f),
            stableHoldMs = holdMs,
            captureReady = ready,
            bodyReference = reference,
        )
    }

    fun captureSnapshot(
        targetType: PunchHeightTargetType,
        evaluation: PunchHeightEvaluation,
        chinProjectionMultiplier: Float,
    ): PunchHeightCaptureSnapshot? {
        val target = evaluation.target ?: return null
        return PunchHeightCaptureSnapshot(
            targetType = targetType,
            timestampMs = evaluation.timestampMs,
            target = target,
            bodyReference = evaluation.bodyReference,
            activeArm = evaluation.activeArm,
            fistCenter = evaluation.fistCenter ?: return null,
            shoulderPoint = evaluation.shoulderPoint ?: return null,
            elbowPoint = evaluation.elbowPoint ?: return null,
            wristPoint = evaluation.wristPoint ?: return null,
            elbowAngleDegrees = evaluation.elbowAngleDegrees ?: return null,
            signedHeightErrorTorsoRatio = evaluation.signedHeightErrorTorsoRatio ?: return null,
            stableHoldMs = evaluation.stableHoldMs,
            chinProjectionMultiplier = chinProjectionMultiplier,
        )
    }

    private fun setupFailure(timestampMs: Long, guidance: SetupGuidance, torsoLength: Float? = null): SetupEvaluation {
        setupSamples.removeAll { timestampMs - it.first > SETUP_HOLD_MS }
        setupSamples.clear()
        return SetupEvaluation(guidance, 0f, false, torsoLength)
    }

    private fun targetModel(type: PunchHeightTargetType): PunchHeightTargetModel = when (type) {
        PunchHeightTargetType.JODAN -> jodanModel
        PunchHeightTargetType.CHUDAN -> chudanModel
        PunchHeightTargetType.GEDAN -> gedanModel
    }

    private fun referenceCandidate(frame: TrackedPoseFrame): ReferenceCandidate? {
        val leftShoulder = frame.observedPoint(PoseLandmarkId.LEFT_SHOULDER) ?: return null
        val rightShoulder = frame.observedPoint(PoseLandmarkId.RIGHT_SHOULDER) ?: return null
        val leftHip = frame.observedPoint(PoseLandmarkId.LEFT_HIP) ?: return null
        val rightHip = frame.observedPoint(PoseLandmarkId.RIGHT_HIP) ?: return null
        val shoulder = midpoint(leftShoulder, rightShoulder)
        val hip = midpoint(leftHip, rightHip)
        val torsoLength = (hip - shoulder).length2d()
        if (torsoLength <= 0f) return null
        val criticalIds = listOf(
            PoseLandmarkId.LEFT_ELBOW, PoseLandmarkId.RIGHT_ELBOW,
            PoseLandmarkId.LEFT_WRIST, PoseLandmarkId.RIGHT_WRIST,
        )
        val critical = criticalIds.mapNotNull(frame::observedPoint)
        if (critical.size < criticalIds.size) return null
        var leftQuality = frame.sideQuality(VisibleSide.LEFT)
        var rightQuality = frame.sideQuality(VisibleSide.RIGHT)
        val leftDepth = listOf(leftShoulder.z, leftHip.z).average().toFloat()
        val rightDepth = listOf(rightShoulder.z, rightHip.z).average().toFloat()
        if (leftDepth.isFinite() && rightDepth.isFinite() && abs(leftDepth - rightDepth) > 0.01f) {
            if (leftDepth < rightDepth) leftQuality += 0.10f else rightQuality += 0.10f
        }
        val preferred = when {
            leftQuality > rightQuality -> VisibleSide.LEFT
            rightQuality > leftQuality -> VisibleSide.RIGHT
            else -> VisibleSide.UNKNOWN
        }
        return ReferenceCandidate(
            shoulderPoint = shoulder,
            hipPoint = hip,
            headPoint = frame.observedPoint(PoseLandmarkId.NOSE)
                ?: frame.observedPoint(if (preferred == VisibleSide.LEFT) PoseLandmarkId.LEFT_EAR else PoseLandmarkId.RIGHT_EAR),
            criticalPositions = critical + leftShoulder + rightShoulder + leftHip + rightHip,
            torsoLength = torsoLength,
            shoulderBreadth = (rightShoulder - leftShoulder).length2d(),
            preferredSide = preferred,
            leftQuality = leftQuality,
            rightQuality = rightQuality,
            confidence = listOf(leftQuality, rightQuality).maxOrNull() ?: 0f,
        )
    }

    private fun averageSideMargin(side: VisibleSide): Float = initializationSamples.map { sample ->
        when (side) {
            VisibleSide.LEFT -> sample.second.leftQuality - sample.second.rightQuality
            VisibleSide.RIGHT -> sample.second.rightQuality - sample.second.leftQuality
            VisibleSide.UNKNOWN -> 0f
        }
    }.average().toFloat()

    private fun selectArm(frame: TrackedPoseFrame, reference: BodyReference): ActiveArm {
        val left = armGeometry(frame, ActiveArm.LEFT, reference)
        val right = armGeometry(frame, ActiveArm.RIGHT, reference)
        val best = listOfNotNull(left, right)
            .filter { it.selectionReliable && it.elbowAngleDegrees >= ARM_CANDIDATE_ANGLE && it.reachRatio >= ARM_CANDIDATE_REACH }
            .maxByOrNull { it.selectionScore }
            ?.arm ?: ActiveArm.NONE
        val now = frame.timestampMs
        if (lockedArm != ActiveArm.NONE) {
            val lockedGeometry = when (lockedArm) { ActiveArm.LEFT -> left; ActiveArm.RIGHT -> right; ActiveArm.NONE -> null }
                ?.takeIf { it.selectionReliable }
            if (lockedGeometry == null) {
                if (armLostSinceMs == null) armLostSinceMs = now
                if (now - armLostSinceMs!! > ARM_LOST_RELEASE_MS) lockedArm = ActiveArm.NONE
            } else armLostSinceMs = null
            if (lockedArm != ActiveArm.NONE) return lockedArm
        }
        if (best != armCandidate) {
            armCandidate = best
            armCandidateSinceMs = now
        }
        if (best != ActiveArm.NONE && armCandidateSinceMs?.let { now - it >= ARM_LOCK_MS } == true) lockedArm = best
        return lockedArm
    }

    private fun armGeometry(frame: TrackedPoseFrame, arm: ActiveArm, reference: BodyReference): ArmGeometry? {
        val ids = when (arm) {
            ActiveArm.LEFT -> ArmIds(PoseLandmarkId.LEFT_SHOULDER, PoseLandmarkId.LEFT_ELBOW, PoseLandmarkId.LEFT_WRIST, PoseLandmarkId.LEFT_INDEX, PoseLandmarkId.LEFT_PINKY)
            ActiveArm.RIGHT -> ArmIds(PoseLandmarkId.RIGHT_SHOULDER, PoseLandmarkId.RIGHT_ELBOW, PoseLandmarkId.RIGHT_WRIST, PoseLandmarkId.RIGHT_INDEX, PoseLandmarkId.RIGHT_PINKY)
            ActiveArm.NONE -> return null
        }
        val shoulder = frame.point(ids.shoulder) ?: return null
        val elbow = frame.point(ids.elbow) ?: return null
        val wrist = frame.point(ids.wrist) ?: return null
        val handSamples = listOf(ids.index, ids.pinky).mapNotNull(frame.landmarks::get)
        val handPoints = handSamples.mapNotNull { it.position }
        val fist = average(listOf(wrist) + handPoints)
        val angle = angleDegrees(shoulder, elbow, wrist)
        val reach = (wrist - shoulder).length2d() / reference.torsoLength
        val samples = listOf(ids.shoulder, ids.elbow, ids.wrist).mapNotNull(frame.landmarks::get)
        val selectionReliable = samples.size == 3 && samples.all { it.isObserved() }
        val handReliable = handSamples.any { it.isObserved() }
        val reliable = samples.size == 3 && samples.all { it.isObserved(ARM_CAPTURE_CONFIDENCE) } && handReliable
        val confidence = samples.minOfOrNull { it.confidence } ?: 0f
        return ArmGeometry(arm, shoulder, elbow, wrist, fist, angle, reach, selectionReliable, reliable, angle / 180f + reach + confidence)
    }

    private fun updateStability(timestampMs: Long, fist: Point3?, target: Point3?, frame: TrackedPoseFrame, reference: BodyReference) {
        val shoulderPoints = listOfNotNull(frame.point(PoseLandmarkId.LEFT_SHOULDER), frame.point(PoseLandmarkId.RIGHT_SHOULDER))
        val hipPoints = listOfNotNull(frame.point(PoseLandmarkId.LEFT_HIP), frame.point(PoseLandmarkId.RIGHT_HIP))
        val shoulder = shoulderPoints.takeIf { it.isNotEmpty() }?.let(::average)
        val hip = hipPoints.takeIf { it.isNotEmpty() }?.let(::average)
        stabilitySamples += StabilitySample(timestampMs, fist, target, shoulder, hip)
        while (stabilitySamples.isNotEmpty() && timestampMs - stabilitySamples.first().timestampMs > STABILITY_WINDOW_MS) {
            stabilitySamples.removeFirst()
        }
    }

    private fun motionWithinLimits(reference: BodyReference): Boolean {
        if (stabilitySamples.size < MIN_STABILITY_SAMPLES) return false
        fun spread(points: List<Point3?>): Float {
            val valid = points.filterNotNull()
            if (valid.size != points.size || valid.isEmpty()) return Float.POSITIVE_INFINITY
            val center = average(valid)
            return valid.maxOf { (it - center).length2d() } / reference.torsoLength
        }
        return spread(stabilitySamples.map { it.fist }) <= MAX_FIST_MOTION &&
            spread(stabilitySamples.map { it.target }) <= MAX_TARGET_DRIFT &&
            spread(stabilitySamples.map { it.shoulder }) <= MAX_BODY_MOTION &&
            spread(stabilitySamples.map { it.hip }) <= MAX_BODY_MOTION
    }

    private fun referenceJitter(samples: List<Pair<Long, ReferenceCandidate>>): Float {
        if (samples.size < 2) return 0f
        val shoulderCenter = average(samples.map { it.second.shoulderPoint })
        val hipCenter = average(samples.map { it.second.hipPoint })
        return samples.maxOf { sample ->
            max(
                (sample.second.shoulderPoint - shoulderCenter).length2d(),
                (sample.second.hipPoint - hipCenter).length2d(),
            ) / sample.second.torsoLength
        }
    }

    private data class ReferenceCandidate(
        val shoulderPoint: Point3,
        val hipPoint: Point3,
        val headPoint: Point3?,
        val criticalPositions: List<Point3>,
        val torsoLength: Float,
        val shoulderBreadth: Float,
        val preferredSide: VisibleSide,
        val leftQuality: Float,
        val rightQuality: Float,
        val confidence: Float,
    )

    private data class ArmIds(val shoulder: PoseLandmarkId, val elbow: PoseLandmarkId, val wrist: PoseLandmarkId, val index: PoseLandmarkId, val pinky: PoseLandmarkId)
    private data class ArmGeometry(
        val arm: ActiveArm,
        val shoulder: Point3,
        val elbow: Point3,
        val wrist: Point3,
        val fistCenter: Point3,
        val elbowAngleDegrees: Float,
        val reachRatio: Float,
        val selectionReliable: Boolean,
        val captureReliable: Boolean,
        val selectionScore: Float,
    )
    private data class StabilitySample(val timestampMs: Long, val fist: Point3?, val target: Point3?, val shoulder: Point3?, val hip: Point3?)

    companion object {
        const val DEFAULT_CHIN_PROJECTION_MULTIPLIER = 1.10f
        const val MIN_CHIN_PROJECTION_MULTIPLIER = 0.70f
        const val MAX_CHIN_PROJECTION_MULTIPLIER = 1.50f
        const val CHIN_PROJECTION_STEP = 0.05f
        private const val FRAME_MARGIN = 0.04f
        private const val MIN_SETUP_TORSO_LENGTH = 0.18f
        private const val MAX_SETUP_TORSO_LENGTH = 0.55f
        private const val MAX_SIDEWAYS_SHOULDER_RATIO = 0.45f
        private const val SETUP_HOLD_MS = 1_200L
        private const val SETUP_MIN_SAMPLES = 10
        private const val INITIALIZATION_WINDOW_MS = 1_500L
        private const val INITIALIZATION_MIN_SAMPLES = 12
        private const val MAX_REFERENCE_JITTER = 0.025f
        private const val SIDE_LOCK_FRACTION = 0.80f
        private const val SIDE_QUALITY_MARGIN = 0.08f
        private const val ARM_CANDIDATE_ANGLE = 130f
        private const val ARM_CANDIDATE_REACH = 0.65f
        private const val ARM_LOCK_MS = 300L
        private const val ARM_LOST_RELEASE_MS = 500L
        private const val ARM_CAPTURE_CONFIDENCE = 0.60f
        private const val CAPTURE_ELBOW_ANGLE = 165f
        private const val TARGET_CAPTURE_CONFIDENCE = 0.70f
        private const val STABILITY_WINDOW_MS = 400L
        private const val MIN_STABILITY_SAMPLES = 3
        private const val MAX_FIST_MOTION = 0.025f
        private const val MAX_TARGET_DRIFT = 0.015f
        private const val MAX_BODY_MOTION = 0.02f
        private const val CAPTURE_HOLD_MS = 1_200L
    }
}

class JodanTargetModel(private val estimator: SideViewChinEstimator) : PunchHeightTargetModel {
    override fun evaluate(frame: TrackedPoseFrame, bodyReference: BodyReference, chinProjectionMultiplier: Float): PunchHeightTarget? {
        val chin = estimator.estimate(frame, bodyReference, chinProjectionMultiplier)
        val point = chin.smoothedPoint ?: return null
        val scalar = chin.torsoScalar ?: return null
        return PunchHeightTarget(
            type = PunchHeightTargetType.JODAN,
            targetPoint = point,
            torsoScalar = scalar,
            tolerance = 0.055f,
            confidence = chin.confidence,
            sourceLandmarks = setOf(PoseLandmarkId.NOSE, bodyReference.visibleSide.mouthId()),
            calculationStrategy = "side-view pose chin projection (${chin.source.name.lowercase()})",
            explanation = "Jodan is aligned with your estimated chin height.",
            captureEligible = chin.captureEligible,
            chinEstimate = chin,
        )
    }
}

class ChudanTargetModel : PunchHeightTargetModel {
    override fun evaluate(frame: TrackedPoseFrame, bodyReference: BodyReference, chinProjectionMultiplier: Float): PunchHeightTarget =
        torsoRatioTarget(PunchHeightTargetType.CHUDAN, bodyReference, 0.45f, "Chudan is aligned with your solar plexus.")
}

class GedanTargetModel : PunchHeightTargetModel {
    override fun evaluate(frame: TrackedPoseFrame, bodyReference: BodyReference, chinProjectionMultiplier: Float): PunchHeightTarget =
        torsoRatioTarget(PunchHeightTargetType.GEDAN, bodyReference, 0.80f, "Gedan is aligned with your lower abdomen.")
}

class SideViewChinEstimator {
    private var lastEstimate: ChinEstimate? = null
    private var lastObservedEstimateTimestampMs: Long? = null
    private var lastFreshFaceTimestampMs: Long? = null
    private var anchorOffsets: Map<PoseLandmarkId, Point3> = emptyMap()
    private var lastMultiplier = PunchHeightAnalyzer.DEFAULT_CHIN_PROJECTION_MULTIPLIER

    fun reset() {
        lastEstimate = null
        lastObservedEstimateTimestampMs = null
        lastFreshFaceTimestampMs = null
        anchorOffsets = emptyMap()
    }

    fun initialize(frame: TrackedPoseFrame, body: BodyReference, multiplier: Float) {
        reset()
        estimate(frame, body, multiplier)
    }

    fun estimate(frame: TrackedPoseFrame, body: BodyReference, multiplier: Float): ChinEstimate {
        if (abs(multiplier - lastMultiplier) > 0.0001f) reset()
        lastMultiplier = multiplier
        val mouthId = body.visibleSide.mouthId()
        val earId = body.visibleSide.earId()
        val eyeId = body.visibleSide.eyeId()
        val shoulderId = body.visibleSide.shoulderId()
        val nose = frame.observedPoint(PoseLandmarkId.NOSE)
        val mouth = frame.observedPoint(mouthId)
        if (nose != null && mouth != null) {
            val raw = mouth + (mouth - nose) * multiplier.coerceIn(
                PunchHeightAnalyzer.MIN_CHIN_PROJECTION_MULTIPLIER,
                PunchHeightAnalyzer.MAX_CHIN_PROJECTION_MULTIPLIER,
            )
            val smoothed = lastEstimate?.smoothedPoint?.let { lerp(it, raw, 0.30f) } ?: raw
            anchorOffsets = listOf(PoseLandmarkId.NOSE, earId, eyeId, shoulderId).mapNotNull { id ->
                frame.observedPoint(id)?.let { anchor -> id to (raw - anchor) }
            }.toMap()
            return build(body, raw, smoothed, ChinTrackingSource.FRESH_FACE, minOf(frame.confidence(PoseLandmarkId.NOSE), frame.confidence(mouthId)), 0L, true)
                .also {
                    lastFreshFaceTimestampMs = frame.timestampMs
                    rememberObserved(it, frame.timestampMs)
                }
        }
        val fallbacks = listOf(
            PoseLandmarkId.NOSE to ChinTrackingSource.NOSE_RELATIVE,
            earId to ChinTrackingSource.EAR_RELATIVE,
            eyeId to ChinTrackingSource.EYE_RELATIVE,
            shoulderId to ChinTrackingSource.SHOULDER_RELATIVE,
        )
        fallbacks.forEach { (id, source) ->
            val anchor = frame.observedPoint(id)
            val offset = anchorOffsets[id]
            if (anchor != null && offset != null) {
                val faceAge = lastFreshFaceTimestampMs?.let { frame.timestampMs - it } ?: Long.MAX_VALUE
                if (source == ChinTrackingSource.SHOULDER_RELATIVE && faceAge > 500L) return@forEach
                val raw = anchor + offset
                val factor = when (source) {
                    ChinTrackingSource.NOSE_RELATIVE -> 0.90f
                    ChinTrackingSource.EAR_RELATIVE -> 0.82f
                    ChinTrackingSource.EYE_RELATIVE -> 0.78f
                    ChinTrackingSource.SHOULDER_RELATIVE -> 0.65f
                    else -> 0f
                }
                val age = faceAge.coerceAtLeast(0L)
                val eligible = source != ChinTrackingSource.SHOULDER_RELATIVE
                return build(body, raw, raw, source, frame.confidence(id) * factor, age, eligible)
                    .also { rememberObserved(it, frame.timestampMs) }
            }
        }
        val previous = lastEstimate
        val age = lastObservedEstimateTimestampMs?.let { frame.timestampMs - it } ?: Long.MAX_VALUE
        if (previous?.smoothedPoint != null && age in 0..150L) {
            return build(body, previous.smoothedPoint, previous.smoothedPoint, ChinTrackingSource.PREDICTED, previous.confidence * 0.5f, age, false)
                .also { lastEstimate = it }
        }
        return ChinEstimate(null, null, null, ChinTrackingSource.LOST, 0f, age.coerceAtMost(301L), false).also { lastEstimate = it }
    }

    private fun rememberObserved(estimate: ChinEstimate, timestampMs: Long) {
        lastEstimate = estimate
        lastObservedEstimateTimestampMs = timestampMs
    }

    private fun build(body: BodyReference, raw: Point3, smoothed: Point3, source: ChinTrackingSource, confidence: Float, age: Long, eligible: Boolean) =
        ChinEstimate(
            rawPoint = raw,
            smoothedPoint = smoothed,
            torsoScalar = (smoothed - body.shoulderPoint).dot2d(body.torsoAxis) / body.torsoLength,
            source = source,
            confidence = confidence.coerceIn(0f, 1f),
            ageMs = age,
            captureEligible = eligible && confidence >= 0.70f,
        )
}

private class TemporalPoseTracker {
    private data class Track(val sample: PoseLandmarkSample, val timestampMs: Long)
    private val tracks = mutableMapOf<PoseLandmarkId, Track>()

    fun reset() = tracks.clear()

    fun track(frame: PoseFrame): TrackedPoseFrame {
        val rawScale = currentTorsoLength(frame).takeIf { it > 0f } ?: 0.25f
        val output = PoseLandmarkId.entries.associateWith { id ->
            val observed = frame.landmarks[id]
            val previous = tracks[id]
            if (observed?.isObserved() == true) {
                val raw = observed.position!!
                val previousPoint = previous?.sample?.position
                val elapsed = previous?.let { frame.timestampMs - it.timestampMs }
                val accepted = previousPoint == null || elapsed == null || elapsed > 300L || (raw - previousPoint).length2d() <= 0.20f * rawScale
                if (accepted) {
                    val smoothed = previousPoint?.let { lerp(it, raw, 0.30f) } ?: raw
                    observed.copy(position = smoothed, source = LandmarkSource.OBSERVED).also { tracks[id] = Track(it, frame.timestampMs) }
                } else predictedOrMissing(previous, frame.timestampMs)
            } else predictedOrMissing(previous, frame.timestampMs)
        }
        return TrackedPoseFrame(frame.timestampMs, output)
    }

    private fun predictedOrMissing(previous: Track?, timestampMs: Long): PoseLandmarkSample {
        if (previous == null) return PoseLandmarkSample(null)
        val age = timestampMs - previous.timestampMs
        return if (age in 0..150L) {
            previous.sample.copy(
                visibility = previous.sample.visibility * (1f - age / 150f),
                presence = previous.sample.presence * (1f - age / 150f),
                source = LandmarkSource.PREDICTED,
            )
        } else PoseLandmarkSample(null)
    }

    private fun currentTorsoLength(frame: PoseFrame): Float {
        val shoulders = listOfNotNull(frame.landmarks[PoseLandmarkId.LEFT_SHOULDER]?.position, frame.landmarks[PoseLandmarkId.RIGHT_SHOULDER]?.position)
        val hips = listOfNotNull(frame.landmarks[PoseLandmarkId.LEFT_HIP]?.position, frame.landmarks[PoseLandmarkId.RIGHT_HIP]?.position)
        if (shoulders.isEmpty() || hips.isEmpty()) return 0f
        return (average(hips) - average(shoulders)).length2d()
    }
}

private fun torsoRatioTarget(type: PunchHeightTargetType, body: BodyReference, ratio: Float, explanation: String): PunchHeightTarget =
    PunchHeightTarget(
        type = type,
        targetPoint = body.shoulderPoint + body.torsoAxis * (body.torsoLength * ratio),
        torsoScalar = ratio,
        tolerance = 0.06f,
        confidence = body.confidence,
        sourceLandmarks = setOf(PoseLandmarkId.LEFT_SHOULDER, PoseLandmarkId.RIGHT_SHOULDER, PoseLandmarkId.LEFT_HIP, PoseLandmarkId.RIGHT_HIP),
        calculationStrategy = "shoulder-to-hip torso ratio $ratio",
        explanation = explanation,
        captureEligible = body.confidence >= 0.70f,
    )

private fun TrackedPoseFrame.point(id: PoseLandmarkId): Point3? = landmarks[id]?.position
private fun TrackedPoseFrame.observedPoint(id: PoseLandmarkId): Point3? = landmarks[id]?.takeIf { it.isObserved() }?.position
private fun TrackedPoseFrame.confidence(id: PoseLandmarkId): Float = landmarks[id]?.confidence ?: 0f
private fun TrackedPoseFrame.sideQuality(side: VisibleSide): Float {
    val ids = when (side) {
        VisibleSide.LEFT -> listOf(PoseLandmarkId.LEFT_EAR, PoseLandmarkId.LEFT_EYE, PoseLandmarkId.LEFT_SHOULDER, PoseLandmarkId.LEFT_ELBOW, PoseLandmarkId.LEFT_WRIST, PoseLandmarkId.LEFT_HIP)
        VisibleSide.RIGHT -> listOf(PoseLandmarkId.RIGHT_EAR, PoseLandmarkId.RIGHT_EYE, PoseLandmarkId.RIGHT_SHOULDER, PoseLandmarkId.RIGHT_ELBOW, PoseLandmarkId.RIGHT_WRIST, PoseLandmarkId.RIGHT_HIP)
        VisibleSide.UNKNOWN -> return 0f
    }
    return ids.map(::confidence).average().toFloat()
}
private fun VisibleSide.mouthId() = if (this == VisibleSide.LEFT) PoseLandmarkId.MOUTH_LEFT else PoseLandmarkId.MOUTH_RIGHT
private fun VisibleSide.earId() = if (this == VisibleSide.LEFT) PoseLandmarkId.LEFT_EAR else PoseLandmarkId.RIGHT_EAR
private fun VisibleSide.eyeId() = if (this == VisibleSide.LEFT) PoseLandmarkId.LEFT_EYE else PoseLandmarkId.RIGHT_EYE
private fun VisibleSide.shoulderId() = if (this == VisibleSide.LEFT) PoseLandmarkId.LEFT_SHOULDER else PoseLandmarkId.RIGHT_SHOULDER
private fun Point3.insideFrame(margin: Float) = x in margin..(1f - margin) && y in margin..(1f - margin)
private fun Point3.length2d() = sqrt(x * x + y * y)
private fun Point3.dot2d(other: Point3) = x * other.x + y * other.y
private fun midpoint(a: Point3, b: Point3) = (a + b) * 0.5f
private fun average(points: List<Point3>): Point3 {
    if (points.isEmpty()) return Point3(0f, 0f, 0f)
    return points.reduce(Point3::plus) * (1f / points.size)
}
private fun lerp(a: Point3, b: Point3, amount: Float) = a + (b - a) * amount.coerceIn(0f, 1f)
private fun angleDegrees(a: Point3, vertex: Point3, c: Point3): Float {
    val first = a - vertex
    val second = c - vertex
    val denominator = first.length2d() * second.length2d()
    if (denominator <= 0f) return 0f
    val cosine = (first.dot2d(second) / denominator).coerceIn(-1f, 1f)
    return Math.toDegrees(acos(cosine).toDouble()).toFloat()
}
