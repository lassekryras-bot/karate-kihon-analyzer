package dk.lasse.karateanalyzer.capture

import dk.lasse.karateanalyzer.core.Point3
import dk.lasse.karateanalyzer.core.PoseLandmarkId
import dk.lasse.karateanalyzer.core.angleBetweenThreePoints
import dk.lasse.karateanalyzer.core.midpoint
import dk.lasse.karateanalyzer.observation.Evidence
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt

internal class KinematicChainExtractor(
    val config: KinematicsConfig = KinematicsConfig(),
) {
    private val smoother = CausalKinematicSmoother(config)
    private val linearSmoother = LinearRegressionSmoother(
        windowDurationMs = config.windowDurationMs,
        minimumSamples = config.minimumSamples,
        minimumWindowSpanRatio = config.minimumWindowSpanRatio,
        minimumLandmarkConfidence = config.minimumLandmarkConfidence,
    )
    private val poseHistory = mutableListOf<RelativePose>()
    private val channelDiagnostics = mutableListOf<KinematicChannelEvidence>()
    private var rawPair: Pair<RelativePose, RelativePose>? = null

    private fun instantaneousRate(name: String, landmarks: List<PoseLandmarkId>): Double? {
        val (before, after) = rawPair ?: return null
        val dt = (after.timestampMs - before.timestampMs) / 1000.0
        if (dt <= 0 || dt > config.windowDurationMs / 1000.0) return null
        if (listOf(before, after).any { p -> landmarks.any missing@{
                val confidence = p.confidences[it] ?: return@missing true
                !confidence.isFinite() || confidence < config.minimumLandmarkConfidence || it !in p.points
            } }) return null
        fun point(p: RelativePose, index: Int) = p.points.getValue(landmarks[index])
        return when {
            name.endsWith("YAW") -> {
                val v1 = point(before, 0) - point(before, 1)
                val v2 = point(after, 0) - point(after, 1)
                if (v1.x * v1.x + v1.z * v1.z < 1e-12 || v2.x * v2.x + v2.z * v2.z < 1e-12) return null
                abs(KinematicsMath.circularDiffDeg(Math.toDegrees(atan2(v2.z.toDouble(), v2.x.toDouble())),
                    Math.toDegrees(atan2(v1.z.toDouble(), v1.x.toDouble())))) / dt
            }
            name.endsWith("RADIAL") -> (KinematicsMath.distance(point(after, 0), Point3(0f, 0f, 0f)) -
                KinematicsMath.distance(point(before, 0), Point3(0f, 0f, 0f))) / dt
            name.endsWith("SPEED") -> KinematicsMath.distance(point(after, 0), point(before, 0)) / dt
            name.endsWith("ORIENTATION") || name.endsWith("3D") -> KinematicsMath.angleBetweenVectorsDeg(
                point(before, 1) - point(before, 0), point(after, 1) - point(after, 0))?.div(dt)
            name.endsWith("VELOCITY") -> {
                val a = angleBetweenThreePoints(point(before, 0), point(before, 1), point(before, 2)) ?: return null
                val b = angleBetweenThreePoints(point(after, 0), point(after, 1), point(after, 2)) ?: return null
                abs(b - a) / dt
            }
            else -> null
        }?.takeIf { it.isFinite() }
    }

    private fun channel(name: String, rate: Double?, coherence: Double?, threshold: Double,
                        window: List<RelativePose>?, landmarks: List<PoseLandmarkId>,
                        raw: Double? = null, gating: Boolean = true): KinematicChannelEvidence {
        val activity = if (rate != null && coherence != null) abs(rate) * coherence else null
        val diagnostic = KinematicChannelEvidence(name, instantaneousRate(name, landmarks), rate, coherence, activity, threshold,
            window?.flatMap { pose -> landmarks.map { pose.confidences[it] ?: 0.0 } }?.minOrNull(),
            when { activity == null -> Evidence.UNKNOWN; activity >= threshold -> Evidence.TRUE; else -> Evidence.FALSE }, gating)
        channelDiagnostics += diagnostic
        return diagnostic
    }

    fun accept(current: RelativePose) {
        poseHistory.add(current)
        val cutoff = current.timestampMs - maxOf(400L, config.windowDurationMs)
        while (poseHistory.isNotEmpty() && poseHistory.first().timestampMs < cutoff) {
            poseHistory.removeAt(0)
        }
        smoother.accept(current)
    }

    fun preserveConfirmedWindow(stableStartTimestampMs: Long, stableEndTimestampMs: Long) {
        val preserved = poseHistory.filter { it.timestampMs in stableStartTimestampMs..stableEndTimestampMs }
        poseHistory.clear()
        poseHistory.addAll(preserved)
        smoother.preserveConfirmedWindow(stableStartTimestampMs, stableEndTimestampMs)
    }

    fun reset() {
        poseHistory.clear()
        smoother.reset()
    }

    fun extract(
        previous: RelativePose?,
        current: RelativePose,
        elapsedMs: Long,
        maxNormalizedSpeed: Double = 8.0,
    ): BodyKinematics {
        channelDiagnostics.clear()
        rawPair = previous?.let { it to current }
        accept(current)

        if (!config.enabled) {
            return emptyKinematics(current)
        }

        val seconds = if (elapsedMs > 0L) elapsedMs / 1_000.0 else null
        val minConf = config.minimumLandmarkConfidence

        val prevTorsoCenter = previous?.let(::torsoCenter)
        val currTorsoCenter = torsoCenter(current)

        val leftArm = extractArm(
            side = "LEFT",
            shoulderId = PoseLandmarkId.LEFT_SHOULDER,
            elbowId = PoseLandmarkId.LEFT_ELBOW,
            wristId = PoseLandmarkId.LEFT_WRIST,
            previous = previous,
            current = current,
            prevTorsoCenter = prevTorsoCenter,
            currTorsoCenter = currTorsoCenter,
            seconds = seconds,
            minConf = minConf,
            maxSpeed = maxNormalizedSpeed,
        )

        val rightArm = extractArm(
            side = "RIGHT",
            shoulderId = PoseLandmarkId.RIGHT_SHOULDER,
            elbowId = PoseLandmarkId.RIGHT_ELBOW,
            wristId = PoseLandmarkId.RIGHT_WRIST,
            previous = previous,
            current = current,
            prevTorsoCenter = prevTorsoCenter,
            currTorsoCenter = currTorsoCenter,
            seconds = seconds,
            minConf = minConf,
            maxSpeed = maxNormalizedSpeed,
        )

        val leftLeg = extractLeg(
            side = "LEFT",
            hipId = PoseLandmarkId.LEFT_HIP,
            kneeId = PoseLandmarkId.LEFT_KNEE,
            ankleId = PoseLandmarkId.LEFT_ANKLE,
            previous = previous,
            current = current,
            prevTorsoCenter = prevTorsoCenter,
            currTorsoCenter = currTorsoCenter,
            seconds = seconds,
            minConf = minConf,
            maxSpeed = maxNormalizedSpeed,
        )

        val rightLeg = extractLeg(
            side = "RIGHT",
            hipId = PoseLandmarkId.RIGHT_HIP,
            kneeId = PoseLandmarkId.RIGHT_KNEE,
            ankleId = PoseLandmarkId.RIGHT_ANKLE,
            previous = previous,
            current = current,
            prevTorsoCenter = prevTorsoCenter,
            currTorsoCenter = currTorsoCenter,
            seconds = seconds,
            minConf = minConf,
            maxSpeed = maxNormalizedSpeed,
        )

        val pelvis = extractPelvis(
            previous = previous,
            current = current,
            seconds = seconds,
            minConf = minConf,
        )

        val allTriggeringChannels = mutableListOf<String>()
        allTriggeringChannels += leftArm.activeChannels
        allTriggeringChannels += rightArm.activeChannels
        allTriggeringChannels += leftLeg.activeChannels
        allTriggeringChannels += rightLeg.activeChannels
        allTriggeringChannels += pelvis.activeChannels

        val limbEvidences = listOf(
            leftArm.motionEvidence,
            rightArm.motionEvidence,
            leftLeg.motionEvidence,
            rightLeg.motionEvidence,
            pelvis.motionEvidence,
        )

        // Strict ternary epistemics (amendment 4):
        // TRUE if any trustworthy channel is TRUE.
        // FALSE only if all required channels are evaluable and below threshold.
        // Otherwise UNKNOWN.
        val anyLimbMotion = when {
            allTriggeringChannels.isNotEmpty() -> Evidence.TRUE
            limbEvidences.all { it == Evidence.FALSE } -> Evidence.FALSE
            else -> Evidence.UNKNOWN
        }

        // Identify primary triggering channel for diagnostic explainability
        val firstTrigger = allTriggeringChannels.firstOrNull()
        val (triggeringSide, triggeringChain) = when {
            firstTrigger == null -> null to null
            firstTrigger.startsWith("LEFT_ARM") -> "LEFT" to "ARM"
            firstTrigger.startsWith("RIGHT_ARM") -> "RIGHT" to "ARM"
            firstTrigger.startsWith("LEFT_LEG") -> "LEFT" to "LEG"
            firstTrigger.startsWith("RIGHT_LEG") -> "RIGHT" to "LEG"
            firstTrigger.startsWith("PELVIS") -> "PELVIS" to "PELVIS"
            else -> null to null
        }

        val (rawValue, smoothedValue, triggerThreshold) = when (firstTrigger) {
            "LEFT_ARM_WRIST_SPEED" -> Triple(leftArm.rawWristSpeed, leftArm.wristTorsoRelativeSpeed, config.endpointSpeedThreshold)
            "LEFT_ARM_WRIST_RADIAL" -> Triple(null, leftArm.wristRadialVelocity, config.radialSpeedThreshold)
            "LEFT_ARM_ELBOW_VELOCITY" -> Triple(leftArm.rawElbowVelocity, leftArm.elbowAngularVelocityDegPerSec, config.jointAngularVelocityThreshold)
            "LEFT_ARM_UPPER_ORIENTATION" -> Triple(null, leftArm.upperArmOrientationChangeDegPerSec, config.segmentOrientationRateThreshold)
            "LEFT_ARM_FOREARM_ORIENTATION" -> Triple(null, leftArm.forearmOrientationChangeDegPerSec, config.segmentOrientationRateThreshold)

            "RIGHT_ARM_WRIST_SPEED" -> Triple(rightArm.rawWristSpeed, rightArm.wristTorsoRelativeSpeed, config.endpointSpeedThreshold)
            "RIGHT_ARM_WRIST_RADIAL" -> Triple(null, rightArm.wristRadialVelocity, config.radialSpeedThreshold)
            "RIGHT_ARM_ELBOW_VELOCITY" -> Triple(rightArm.rawElbowVelocity, rightArm.elbowAngularVelocityDegPerSec, config.jointAngularVelocityThreshold)
            "RIGHT_ARM_UPPER_ORIENTATION" -> Triple(null, rightArm.upperArmOrientationChangeDegPerSec, config.segmentOrientationRateThreshold)
            "RIGHT_ARM_FOREARM_ORIENTATION" -> Triple(null, rightArm.forearmOrientationChangeDegPerSec, config.segmentOrientationRateThreshold)

            "LEFT_LEG_ANKLE_SPEED" -> Triple(leftLeg.rawAnkleSpeed, leftLeg.ankleTorsoRelativeSpeed, config.endpointSpeedThreshold)
            "LEFT_LEG_ANKLE_RADIAL" -> Triple(null, leftLeg.ankleRadialVelocity, config.radialSpeedThreshold)
            "LEFT_LEG_KNEE_SPEED" -> Triple(null, leftLeg.kneeTorsoRelativeSpeed, config.endpointSpeedThreshold)
            "LEFT_LEG_KNEE_RADIAL" -> Triple(null, leftLeg.kneeRadialVelocity, config.radialSpeedThreshold)
            "LEFT_LEG_KNEE_VELOCITY" -> Triple(leftLeg.rawKneeVelocity, leftLeg.kneeAngularVelocityDegPerSec, config.jointAngularVelocityThreshold)
            "LEFT_LEG_THIGH_ORIENTATION" -> Triple(null, leftLeg.thighOrientationChangeDegPerSec, config.segmentOrientationRateThreshold)
            "LEFT_LEG_SHIN_ORIENTATION" -> Triple(null, leftLeg.shinOrientationChangeDegPerSec, config.segmentOrientationRateThreshold)

            "RIGHT_LEG_ANKLE_SPEED" -> Triple(rightLeg.rawAnkleSpeed, rightLeg.ankleTorsoRelativeSpeed, config.endpointSpeedThreshold)
            "RIGHT_LEG_ANKLE_RADIAL" -> Triple(null, rightLeg.ankleRadialVelocity, config.radialSpeedThreshold)
            "RIGHT_LEG_KNEE_SPEED" -> Triple(null, rightLeg.kneeTorsoRelativeSpeed, config.endpointSpeedThreshold)
            "RIGHT_LEG_KNEE_RADIAL" -> Triple(null, rightLeg.kneeRadialVelocity, config.radialSpeedThreshold)
            "RIGHT_LEG_KNEE_VELOCITY" -> Triple(rightLeg.rawKneeVelocity, rightLeg.kneeAngularVelocityDegPerSec, config.jointAngularVelocityThreshold)
            "RIGHT_LEG_THIGH_ORIENTATION" -> Triple(null, rightLeg.thighOrientationChangeDegPerSec, config.segmentOrientationRateThreshold)
            "RIGHT_LEG_SHIN_ORIENTATION" -> Triple(null, rightLeg.shinOrientationChangeDegPerSec, config.segmentOrientationRateThreshold)

            "PELVIS_ROTATION_YAW" -> Triple(pelvis.rawYawRateDegPerSec, pelvis.yawRotationRateDegPerSec, config.pelvisYawRateThreshold)
            "PELVIS_ROTATION_3D" -> Triple(null, pelvis.rotationRateDegPerSec, config.pelvisRotationRateThreshold)
            else -> Triple(null, null, null)
        }

        val positiveEvidence = KinematicPositiveMotionEvidence(
            motionEvidence = anyLimbMotion,
            leftArm = leftArm.motionEvidence,
            rightArm = rightArm.motionEvidence,
            leftLeg = leftLeg.motionEvidence,
            rightLeg = rightLeg.motionEvidence,
            pelvis = pelvis.motionEvidence,
            triggeringSide = triggeringSide,
            triggeringChain = triggeringChain,
            triggeringSignal = firstTrigger,
            rawValue = channelDiagnostics.firstOrNull { it.name == firstTrigger }?.rawValue,
            smoothedValue = smoothedValue,
            threshold = triggerThreshold,
            confidence = channelDiagnostics.firstOrNull { it.name == firstTrigger }?.confidence ?: 0.0,
            channels = channelDiagnostics.toList(),
            diagnosticCoherence = channelDiagnostics.firstOrNull { it.name == firstTrigger }?.coherence,
            windowDurationMs = config.windowDurationMs,
            triggeringChannels = allTriggeringChannels,
        )

        val top2 = extractTop2(current)

        return BodyKinematics(
            leftArm = leftArm,
            rightArm = rightArm,
            leftLeg = leftLeg,
            rightLeg = rightLeg,
            pelvis = pelvis,
            positiveEvidence = positiveEvidence,
            top2 = top2,
        )
    }

    private fun extractArm(
        side: String,
        shoulderId: PoseLandmarkId,
        elbowId: PoseLandmarkId,
        wristId: PoseLandmarkId,
        previous: RelativePose?,
        current: RelativePose,
        prevTorsoCenter: Point3?,
        currTorsoCenter: Point3?,
        seconds: Double?,
        minConf: Double,
        maxSpeed: Double,
    ): ArmKinematics {
        val s2 = current.points[shoulderId]
        val e2 = current.points[elbowId]
        val w2 = current.points[wristId]

        val confS = current.confidences[shoulderId] ?: 0.0
        val confE = current.confidences[elbowId] ?: 0.0
        val confW = current.confidences[wristId] ?: 0.0
        val coverage = (confS + confE + confW) / 3.0

        // 1. Raw 1-frame values (diagnostic baseline)
        val rawWristSpeed = if (previous != null && seconds != null && seconds > 0.0 && confW >= minConf) {
            val w1 = previous.points[wristId]
            if (w1 != null && w2 != null) minOf(KinematicsMath.distance(w1, w2) / seconds, maxSpeed) else null
        } else null

        val rawElbowVel = if (previous != null && seconds != null && seconds > 0.0 && minOf(confS, confE, confW) >= minConf) {
            val s1 = previous.points[shoulderId]
            val e1 = previous.points[elbowId]
            val w1 = previous.points[wristId]
            if (s1 != null && e1 != null && w1 != null && s2 != null && e2 != null && w2 != null) {
                val ang1 = angleBetweenThreePoints(s1, e1, w1)?.toDouble()
                val ang2 = angleBetweenThreePoints(s2, e2, w2)?.toDouble()
                if (ang1 != null && ang2 != null) minOf(abs(ang2 - ang1) / seconds, config.maximumAngularVelocity) else null
            } else null
        } else null

        // 2. Causal smoothed values over backward time window
        val armLandmarks = listOf(shoulderId, elbowId, wristId)
        val window = smoother.getUsableWindow(current.timestampMs, armLandmarks)

        val wristSpeedsWindow = smoother.getUsableWindow(current.timestampMs, listOf(wristId))
        val wristSpeeds = wristSpeedsWindow?.let { smoother.smoothTorsoRelativeSpeed(it, wristId, maxSpeed) }

        val smoothedWristSpeed = wristSpeeds?.first
        val smoothedRadialVelocity = wristSpeeds?.second

        val elbowPair = if (window != null) {
            smoother.smoothScalarAngleRate(window, shoulderId, elbowId, wristId)
        } else null
        val smoothedElbowVel = elbowPair?.first

        val upperArmPairWindow = smoother.getUsableWindow(current.timestampMs, listOf(shoulderId, elbowId))
        val upperArmPair = upperArmPairWindow?.let { smoother.smoothOrientationRate(it, shoulderId, elbowId) }
        val smoothedUpperArmOrient = upperArmPair?.first

        val forearmPairWindow = smoother.getUsableWindow(current.timestampMs, listOf(elbowId, wristId))
        val forearmPair = forearmPairWindow?.let { smoother.smoothOrientationRate(it, elbowId, wristId) }
        val smoothedForearmOrient = forearmPair?.first

        val currentElbowAngle = if (s2 != null && e2 != null && w2 != null && minOf(confS, confE, confW) >= minConf) {
            angleBetweenThreePoints(s2, e2, w2)?.toDouble()
        } else null

        val wristCoherence = wristSpeeds?.third ?: 0.0
        val elbowCoherence = elbowPair?.second ?: 0.0
        val upperArmCoherence = upperArmPair?.second ?: 0.0
        val forearmCoherence = forearmPair?.second ?: 0.0

        val channels = listOf(
            channel("${side}_ARM_WRIST_SPEED", smoothedWristSpeed, wristCoherence, config.endpointSpeedThreshold, wristSpeedsWindow, listOf(wristId), rawWristSpeed),
            channel("${side}_ARM_WRIST_RADIAL", smoothedRadialVelocity, wristCoherence, config.radialSpeedThreshold, wristSpeedsWindow, listOf(wristId), null),
            channel("${side}_ARM_ELBOW_VELOCITY", smoothedElbowVel, elbowCoherence, config.jointAngularVelocityThreshold, window, listOf(shoulderId, elbowId, wristId), rawElbowVel),
            channel("${side}_ARM_UPPER_ORIENTATION", smoothedUpperArmOrient, upperArmCoherence, config.segmentOrientationRateThreshold, upperArmPairWindow, listOf(shoulderId, elbowId), null),
            channel("${side}_ARM_FOREARM_ORIENTATION", smoothedForearmOrient, forearmCoherence, config.segmentOrientationRateThreshold, forearmPairWindow, listOf(elbowId, wristId), null),
        )
        val active = channels.filter { it.evidence == Evidence.TRUE }.map { it.name }
        val motionEvidence = when {
            active.isNotEmpty() -> Evidence.TRUE
            channels.all { it.evidence == Evidence.FALSE } -> Evidence.FALSE
            else -> Evidence.UNKNOWN
        }

        return ArmKinematics(
            wristTorsoRelativeSpeed = smoothedWristSpeed,
            wristRadialVelocity = smoothedRadialVelocity,
            elbowAngleDegrees = currentElbowAngle,
            elbowAngularVelocityDegPerSec = smoothedElbowVel,
            upperArmOrientationChangeDegPerSec = smoothedUpperArmOrient,
            forearmOrientationChangeDegPerSec = smoothedForearmOrient,
            rawWristSpeed = rawWristSpeed,
            rawElbowVelocity = rawElbowVel,
            coverage = coverage,
            motionEvidence = motionEvidence,
            activeChannels = active,
        )
    }

    private fun extractLeg(
        side: String,
        hipId: PoseLandmarkId,
        kneeId: PoseLandmarkId,
        ankleId: PoseLandmarkId,
        previous: RelativePose?,
        current: RelativePose,
        prevTorsoCenter: Point3?,
        currTorsoCenter: Point3?,
        seconds: Double?,
        minConf: Double,
        maxSpeed: Double,
    ): LegKinematics {
        val h2 = current.points[hipId]
        val k2 = current.points[kneeId]
        val a2 = current.points[ankleId]

        val confH = current.confidences[hipId] ?: 0.0
        val confK = current.confidences[kneeId] ?: 0.0
        val confA = current.confidences[ankleId] ?: 0.0
        val coverage = (confH + confK + confA) / 3.0

        val rawAnkleSpeed = if (previous != null && seconds != null && seconds > 0.0 && confA >= minConf) {
            val a1 = previous.points[ankleId]
            if (a1 != null && a2 != null) minOf(KinematicsMath.distance(a1, a2) / seconds, maxSpeed) else null
        } else null

        val rawKneeVel = if (previous != null && seconds != null && seconds > 0.0 && minOf(confH, confK, confA) >= minConf) {
            val h1 = previous.points[hipId]
            val k1 = previous.points[kneeId]
            val a1 = previous.points[ankleId]
            if (h1 != null && k1 != null && a1 != null && h2 != null && k2 != null && a2 != null) {
                val ang1 = angleBetweenThreePoints(h1, k1, a1)?.toDouble()
                val ang2 = angleBetweenThreePoints(h2, k2, a2)?.toDouble()
                if (ang1 != null && ang2 != null) minOf(abs(ang2 - ang1) / seconds, config.maximumAngularVelocity) else null
            } else null
        } else null

        val legLandmarks = listOf(hipId, kneeId, ankleId)
        val window = smoother.getUsableWindow(current.timestampMs, legLandmarks)

        val ankleSpeedsWindow = smoother.getUsableWindow(current.timestampMs, listOf(ankleId))
        val ankleSpeeds = ankleSpeedsWindow?.let { smoother.smoothTorsoRelativeSpeed(it, ankleId, maxSpeed) }
        val smoothedAnkleSpeed = ankleSpeeds?.first
        val smoothedAnkleRadial = ankleSpeeds?.second

        val kneeSpeedsWindow = smoother.getUsableWindow(current.timestampMs, listOf(kneeId))
        val kneeSpeeds = kneeSpeedsWindow?.let { smoother.smoothTorsoRelativeSpeed(it, kneeId, maxSpeed) }
        val smoothedKneeSpeed = kneeSpeeds?.first
        val smoothedKneeRadial = kneeSpeeds?.second

        val kneePair = if (window != null) {
            smoother.smoothScalarAngleRate(window, hipId, kneeId, ankleId)
        } else null
        val smoothedKneeVel = kneePair?.first

        val thighPairWindow = smoother.getUsableWindow(current.timestampMs, listOf(hipId, kneeId))
        val thighPair = thighPairWindow?.let { smoother.smoothOrientationRate(it, hipId, kneeId) }
        val smoothedThighOrient = thighPair?.first

        val shinPairWindow = smoother.getUsableWindow(current.timestampMs, listOf(kneeId, ankleId))
        val shinPair = shinPairWindow?.let { smoother.smoothOrientationRate(it, kneeId, ankleId) }
        val smoothedShinOrient = shinPair?.first

        val currentKneeAngle = if (h2 != null && k2 != null && a2 != null && minOf(confH, confK, confA) >= minConf) {
            angleBetweenThreePoints(h2, k2, a2)?.toDouble()
        } else null

        val ankleCoherence = ankleSpeeds?.third ?: 0.0
        val kneeCoherence = kneeSpeeds?.third ?: 0.0
        val kneeAngleCoherence = kneePair?.second ?: 0.0
        val thighCoherence = thighPair?.second ?: 0.0
        val shinCoherence = shinPair?.second ?: 0.0

        val channels = listOf(
            channel("${side}_LEG_ANKLE_SPEED", smoothedAnkleSpeed, ankleCoherence, config.endpointSpeedThreshold, ankleSpeedsWindow, listOf(ankleId), rawAnkleSpeed),
            channel("${side}_LEG_ANKLE_RADIAL", smoothedAnkleRadial, ankleCoherence, config.radialSpeedThreshold, ankleSpeedsWindow, listOf(ankleId), null),
            channel("${side}_LEG_KNEE_SPEED", smoothedKneeSpeed, kneeCoherence, config.endpointSpeedThreshold, kneeSpeedsWindow, listOf(kneeId), null),
            channel("${side}_LEG_KNEE_RADIAL", smoothedKneeRadial, kneeCoherence, config.radialSpeedThreshold, kneeSpeedsWindow, listOf(kneeId), null),
            channel("${side}_LEG_KNEE_VELOCITY", smoothedKneeVel, kneeAngleCoherence, config.jointAngularVelocityThreshold, window, listOf(hipId, kneeId, ankleId), rawKneeVel),
            channel("${side}_LEG_THIGH_ORIENTATION", smoothedThighOrient, thighCoherence, config.segmentOrientationRateThreshold, thighPairWindow, listOf(hipId, kneeId), null),
            channel("${side}_LEG_SHIN_ORIENTATION", smoothedShinOrient, shinCoherence, config.segmentOrientationRateThreshold, shinPairWindow, listOf(kneeId, ankleId), null),
        )
        val active = channels.filter { it.evidence == Evidence.TRUE }.map { it.name }
        val motionEvidence = when {
            active.isNotEmpty() -> Evidence.TRUE
            channels.all { it.evidence == Evidence.FALSE } -> Evidence.FALSE
            else -> Evidence.UNKNOWN
        }

        return LegKinematics(
            ankleTorsoRelativeSpeed = smoothedAnkleSpeed,
            ankleRadialVelocity = smoothedAnkleRadial,
            kneeTorsoRelativeSpeed = smoothedKneeSpeed,
            kneeRadialVelocity = smoothedKneeRadial,
            kneeAngleDegrees = currentKneeAngle,
            kneeAngularVelocityDegPerSec = smoothedKneeVel,
            thighOrientationChangeDegPerSec = smoothedThighOrient,
            shinOrientationChangeDegPerSec = smoothedShinOrient,
            rawAnkleSpeed = rawAnkleSpeed,
            rawKneeVelocity = rawKneeVel,
            coverage = coverage,
            motionEvidence = motionEvidence,
            activeChannels = active,
        )
    }

    private fun extractPelvis(
        previous: RelativePose?,
        current: RelativePose,
        seconds: Double?,
        minConf: Double,
    ): PelvisKinematics {
        val confL = current.confidences[PoseLandmarkId.LEFT_HIP] ?: 0.0
        val confR = current.confidences[PoseLandmarkId.RIGHT_HIP] ?: 0.0
        val coverage = (confL + confR) / 2.0

        val rawYawRate = if (previous != null && seconds != null && seconds > 0.0 && minOf(confL, confR) >= minConf) {
            val lh1 = previous.points[PoseLandmarkId.LEFT_HIP]
            val rh1 = previous.points[PoseLandmarkId.RIGHT_HIP]
            val lh2 = current.points[PoseLandmarkId.LEFT_HIP]
            val rh2 = current.points[PoseLandmarkId.RIGHT_HIP]
            if (lh1 != null && rh1 != null && lh2 != null && rh2 != null) {
                val psi1 = atan2((lh1.z - rh1.z).toDouble(), (lh1.x - rh1.x).toDouble())
                val psi2 = atan2((lh2.z - rh2.z).toDouble(), (lh2.x - rh2.x).toDouble())
                val diffDeg = KinematicsMath.wrapDegrees((psi2 - psi1) * 180.0 / Math.PI)
                minOf(abs(diffDeg) / seconds, config.maximumAngularVelocity)
            } else null
        } else null

        val pelvisLandmarks = listOf(PoseLandmarkId.LEFT_HIP, PoseLandmarkId.RIGHT_HIP)
        val window = smoother.getUsableWindow(current.timestampMs, pelvisLandmarks)

        val yawPair = if (window != null) {
            smoother.smoothPelvisYawRate(window)
        } else null
        val smoothedYawRate = yawPair?.first

        val yawCoherence = yawPair?.second ?: 0.0

        val rotation3dPair = if (window != null) {
            smoother.smoothOrientationRate(window, PoseLandmarkId.RIGHT_HIP, PoseLandmarkId.LEFT_HIP)
        } else null
        val smoothedRotation3D = rotation3dPair?.first
        val rotation3dCoherence = rotation3dPair?.second ?: 0.0

        val channels = listOf(
            channel("PELVIS_ROTATION_YAW", smoothedYawRate, yawCoherence, config.pelvisYawRateThreshold, window, pelvisLandmarks, rawYawRate),
            channel("PELVIS_ROTATION_3D", smoothedRotation3D, rotation3dCoherence, config.pelvisRotationRateThreshold, window, pelvisLandmarks, gating = config.pelvis3dRotationInGating),
        )
        val active = channels.filter { it.usedInGating && it.evidence == Evidence.TRUE }.map { it.name }
        val motionEvidence = when {
            active.isNotEmpty() -> Evidence.TRUE
            channels.filter { it.usedInGating }.all { it.evidence == Evidence.FALSE } -> Evidence.FALSE
            else -> Evidence.UNKNOWN
        }

        return PelvisKinematics(
            rotationRateDegPerSec = smoothedRotation3D,
            yawRotationRateDegPerSec = smoothedYawRate,
            rawYawRateDegPerSec = rawYawRate,
            coverage = coverage,
            motionEvidence = motionEvidence,
            activeChannels = active,
        )
    }

    private fun torsoCenter(pose: RelativePose): Point3? {
        val ls = pose.points[PoseLandmarkId.LEFT_SHOULDER]
        val rs = pose.points[PoseLandmarkId.RIGHT_SHOULDER]
        val shoulderCenter = if (ls != null && rs != null) midpoint(ls, rs) else null
        val hipCenter = Point3(0f, 0f, 0f)
        return if (shoulderCenter != null) midpoint(hipCenter, shoulderCenter) else Point3(0f, 0.5f, 0f)
    }

    private fun emptyKinematics(current: RelativePose): BodyKinematics {
        val unknownArm = ArmKinematics(motionEvidence = Evidence.UNKNOWN)
        val unknownLeg = LegKinematics(motionEvidence = Evidence.UNKNOWN)
        val unknownPelvis = PelvisKinematics(motionEvidence = Evidence.UNKNOWN)
        val top2 = extractTop2(current)
        return BodyKinematics(
            leftArm = unknownArm,
            rightArm = unknownArm,
            leftLeg = unknownLeg,
            rightLeg = unknownLeg,
            pelvis = unknownPelvis,
            positiveEvidence = KinematicPositiveMotionEvidence(
                motionEvidence = Evidence.UNKNOWN,
                leftArm = Evidence.UNKNOWN,
                rightArm = Evidence.UNKNOWN,
                leftLeg = Evidence.UNKNOWN,
                rightLeg = Evidence.UNKNOWN,
                pelvis = Evidence.UNKNOWN,
                triggeringChannels = emptyList(),
                windowDurationMs = config.windowDurationMs,
            ),
            top2 = top2,
        )
    }

    fun extractTop2(current: RelativePose): KinematicEvidenceTop2 {
        val targetTimeSec = current.timestampMs / 1000.0
        val lRef = current.referenceScale ?: run {
            val side = config.cameraNearArmSide ?: return@run null
            val sId = if (side == LateralSide.LEFT) PoseLandmarkId.LEFT_SHOULDER else PoseLandmarkId.RIGHT_SHOULDER
            val eId = if (side == LateralSide.LEFT) PoseLandmarkId.LEFT_ELBOW else PoseLandmarkId.RIGHT_ELBOW
            val lengths = poseHistory.mapNotNull { p ->
                val points = if (p.imagePoints.isNotEmpty()) p.imagePoints else p.points
                val s = points[sId] ?: return@mapNotNull null
                val e = points[eId] ?: return@mapNotNull null
                val cS = p.confidences[sId] ?: return@mapNotNull null
                val cE = p.confidences[eId] ?: return@mapNotNull null
                if (cS < config.minimumLandmarkConfidence || cE < config.minimumLandmarkConfidence) return@mapNotNull null
                val dx = (e.x - s.x).toDouble()
                val dy = (e.y - s.y).toDouble()
                val len = sqrt(dx * dx + dy * dy)
                if (len.isFinite() && len > 1e-4) len else null
            }
            if (lengths.isNotEmpty()) lengths.sorted()[lengths.size / 2] else null
        }

        val translationVelocities = mutableMapOf<String, Velocity2D>()
        val angularVelocities = mutableMapOf<String, AngularVelocity>()

        // 1. Translation Channels (scaled by lRef; units: L_ref / s)
        if (lRef != null && lRef > 1e-6) {
            val translationLandmarks = listOf(
                PoseLandmarkId.LEFT_SHOULDER, PoseLandmarkId.RIGHT_SHOULDER,
                PoseLandmarkId.LEFT_ELBOW, PoseLandmarkId.RIGHT_ELBOW,
                PoseLandmarkId.LEFT_WRIST, PoseLandmarkId.RIGHT_WRIST,
                PoseLandmarkId.LEFT_HIP, PoseLandmarkId.RIGHT_HIP,
                PoseLandmarkId.LEFT_KNEE, PoseLandmarkId.RIGHT_KNEE,
                PoseLandmarkId.LEFT_ANKLE, PoseLandmarkId.RIGHT_ANKLE,
            )
            for (id in translationLandmarks) {
                val samples = poseHistory.mapNotNull { p ->
                    val points = if (p.imagePoints.isNotEmpty()) p.imagePoints else p.points
                    val pt = points[id] ?: return@mapNotNull null
                    val conf = p.confidences[id] ?: return@mapNotNull null
                    TimestampedPoint2D(
                        timestampSec = p.timestampMs / 1000.0,
                        x = pt.x.toDouble() / lRef,
                        y = pt.y.toDouble() / lRef,
                        confidence = conf,
                    )
                }
                val vel = linearSmoother.fitVelocity2D(samples, targetTimeSec)
                if (vel != null) {
                    translationVelocities[id.name] = vel
                }
            }
        }

        // 2. Angular Channels: Joint interior angles
        val jointAngles = listOf(
            Triple(PoseLandmarkId.LEFT_SHOULDER, PoseLandmarkId.LEFT_ELBOW, PoseLandmarkId.LEFT_WRIST) to "LEFT_ELBOW_ANGLE",
            Triple(PoseLandmarkId.RIGHT_SHOULDER, PoseLandmarkId.RIGHT_ELBOW, PoseLandmarkId.RIGHT_WRIST) to "RIGHT_ELBOW_ANGLE",
            Triple(PoseLandmarkId.LEFT_HIP, PoseLandmarkId.LEFT_KNEE, PoseLandmarkId.LEFT_ANKLE) to "LEFT_KNEE_ANGLE",
            Triple(PoseLandmarkId.RIGHT_HIP, PoseLandmarkId.RIGHT_KNEE, PoseLandmarkId.RIGHT_ANKLE) to "RIGHT_KNEE_ANGLE",
        )
        for ((joints, name) in jointAngles) {
            val (jA, jB, jC) = joints
            val samples = poseHistory.mapNotNull { p ->
                val points = if (p.imagePoints.isNotEmpty()) p.imagePoints else p.points
                val a = points[jA] ?: return@mapNotNull null
                val b = points[jB] ?: return@mapNotNull null
                val c = points[jC] ?: return@mapNotNull null
                val cA = p.confidences[jA] ?: return@mapNotNull null
                val cB = p.confidences[jB] ?: return@mapNotNull null
                val cC = p.confidences[jC] ?: return@mapNotNull null
                val minC = minOf(cA, cB, cC)
                val uX = (a.x - b.x).toDouble()
                val uY = (a.y - b.y).toDouble()
                val vX = (c.x - b.x).toDouble()
                val vY = (c.y - b.y).toDouble()
                val uLen = sqrt(uX * uX + uY * uY)
                val vLen = sqrt(vX * vX + vY * vY)
                if (uLen < 1e-6 || vLen < 1e-6) return@mapNotNull null
                val dot = (uX * vX + uY * vY) / (uLen * vLen)
                val angleDeg = Math.toDegrees(acos(dot.coerceIn(-1.0, 1.0)))
                TimestampedAngle(p.timestampMs / 1000.0, angleDeg, minC, isUndirectedAxis = false)
            }
            val rate = linearSmoother.fitAngularRate(samples, targetTimeSec)
            if (rate != null) {
                angularVelocities[name] = rate
            }
        }

        // 3. Angular Channels: Segment orientations (directed 360 deg)
        val segments = listOf(
            Pair(PoseLandmarkId.LEFT_SHOULDER, PoseLandmarkId.LEFT_ELBOW) to "LEFT_UPPER_ARM_ORIENTATION",
            Pair(PoseLandmarkId.RIGHT_SHOULDER, PoseLandmarkId.RIGHT_ELBOW) to "RIGHT_UPPER_ARM_ORIENTATION",
            Pair(PoseLandmarkId.LEFT_ELBOW, PoseLandmarkId.LEFT_WRIST) to "LEFT_FOREARM_ORIENTATION",
            Pair(PoseLandmarkId.RIGHT_ELBOW, PoseLandmarkId.RIGHT_WRIST) to "RIGHT_FOREARM_ORIENTATION",
            Pair(PoseLandmarkId.LEFT_HIP, PoseLandmarkId.LEFT_KNEE) to "LEFT_THIGH_ORIENTATION",
            Pair(PoseLandmarkId.RIGHT_HIP, PoseLandmarkId.RIGHT_KNEE) to "RIGHT_THIGH_ORIENTATION",
            Pair(PoseLandmarkId.LEFT_KNEE, PoseLandmarkId.LEFT_ANKLE) to "LEFT_SHIN_ORIENTATION",
            Pair(PoseLandmarkId.RIGHT_KNEE, PoseLandmarkId.RIGHT_ANKLE) to "RIGHT_SHIN_ORIENTATION",
        )
        val effectiveScale = lRef ?: 1.0
        val minSegLen = config.minSegmentLengthRatio * effectiveScale
        for ((pair, name) in segments) {
            val (oId, tId) = pair
            val samples = poseHistory.mapNotNull { p ->
                val points = if (p.imagePoints.isNotEmpty()) p.imagePoints else p.points
                val p1 = points[oId] ?: return@mapNotNull null
                val p2 = points[tId] ?: return@mapNotNull null
                val c1 = p.confidences[oId] ?: return@mapNotNull null
                val c2 = p.confidences[tId] ?: return@mapNotNull null
                val dx = (p2.x - p1.x).toDouble()
                val dy = (p2.y - p1.y).toDouble()
                val len = sqrt(dx * dx + dy * dy)
                if (len < minSegLen) return@mapNotNull null
                val angleDeg = Math.toDegrees(atan2(dy, dx))
                TimestampedAngle(p.timestampMs / 1000.0, angleDeg, minOf(c1, c2), isUndirectedAxis = false)
            }
            val rate = linearSmoother.fitAngularRate(samples, targetTimeSec)
            if (rate != null) {
                angularVelocities[name] = rate
            }
        }

        // 4. Angular Channels: Undirected axes (180 deg line symmetry)
        val axes = listOf(
            Pair(PoseLandmarkId.LEFT_SHOULDER, PoseLandmarkId.RIGHT_SHOULDER) to "SHOULDER_AXIS_ORIENTATION",
            Pair(PoseLandmarkId.LEFT_HIP, PoseLandmarkId.RIGHT_HIP) to "HIP_AXIS_ORIENTATION",
        )
        val minAxisLen = config.minAxisLengthRatio * effectiveScale
        for ((pair, name) in axes) {
            val (p1Id, p2Id) = pair
            val samples = poseHistory.mapNotNull { p ->
                val points = if (p.imagePoints.isNotEmpty()) p.imagePoints else p.points
                val p1 = points[p1Id] ?: return@mapNotNull null
                val p2 = points[p2Id] ?: return@mapNotNull null
                val c1 = p.confidences[p1Id] ?: return@mapNotNull null
                val c2 = p.confidences[p2Id] ?: return@mapNotNull null
                val dx = (p2.x - p1.x).toDouble()
                val dy = (p2.y - p1.y).toDouble()
                val len = sqrt(dx * dx + dy * dy)
                if (len < minAxisLen) return@mapNotNull null
                val angleDeg = Math.toDegrees(atan2(dy, dx))
                TimestampedAngle(p.timestampMs / 1000.0, angleDeg, minOf(c1, c2), isUndirectedAxis = true)
            }
            val rate = linearSmoother.fitAngularRate(samples, targetTimeSec)
            if (rate != null) {
                angularVelocities[name] = rate
            }
        }

        // Top-2 selection
        val sortedTrans = translationVelocities.entries
            .filter { it.value.quality >= config.minimumLandmarkConfidence }
            .sortedByDescending { it.value.speed }
        val (eT, tChan1, tChan2) = when {
            sortedTrans.size >= 2 -> Triple((sortedTrans[0].value.speed + sortedTrans[1].value.speed) * 0.5, sortedTrans[0].key, sortedTrans[1].key)
            sortedTrans.size == 1 -> Triple(sortedTrans[0].value.speed, sortedTrans[0].key, null)
            else -> Triple(null, null, null)
        }

        val sortedAng = angularVelocities.entries
            .filter { it.value.quality >= config.minimumLandmarkConfidence }
            .sortedByDescending { it.value.speedDegPerSec }
        val (eA, aChan1, aChan2) = when {
            sortedAng.size >= 2 -> Triple((sortedAng[0].value.speedDegPerSec + sortedAng[1].value.speedDegPerSec) * 0.5, sortedAng[0].key, sortedAng[1].key)
            sortedAng.size == 1 -> Triple(sortedAng[0].value.speedDegPerSec, sortedAng[0].key, null)
            else -> Triple(null, null, null)
        }

        val upperTransSet = setOf(
            PoseLandmarkId.LEFT_SHOULDER.name, PoseLandmarkId.RIGHT_SHOULDER.name,
            PoseLandmarkId.LEFT_ELBOW.name, PoseLandmarkId.RIGHT_ELBOW.name,
            PoseLandmarkId.LEFT_WRIST.name, PoseLandmarkId.RIGHT_WRIST.name,
        )
        val upperAngSet = setOf(
            "LEFT_ELBOW_ANGLE", "RIGHT_ELBOW_ANGLE",
            "LEFT_UPPER_ARM_ORIENTATION", "RIGHT_UPPER_ARM_ORIENTATION",
            "LEFT_FOREARM_ORIENTATION", "RIGHT_FOREARM_ORIENTATION",
            "SHOULDER_AXIS_ORIENTATION",
        )
        val lowerTransSet = setOf(
            PoseLandmarkId.LEFT_HIP.name, PoseLandmarkId.RIGHT_HIP.name,
            PoseLandmarkId.LEFT_KNEE.name, PoseLandmarkId.RIGHT_KNEE.name,
            PoseLandmarkId.LEFT_ANKLE.name, PoseLandmarkId.RIGHT_ANKLE.name,
        )
        val lowerAngSet = setOf(
            "LEFT_KNEE_ANGLE", "RIGHT_KNEE_ANGLE",
            "LEFT_THIGH_ORIENTATION", "RIGHT_THIGH_ORIENTATION",
            "LEFT_SHIN_ORIENTATION", "RIGHT_SHIN_ORIENTATION",
            "HIP_AXIS_ORIENTATION",
        )

        fun computeTop2Speed(entries: List<Map.Entry<String, Velocity2D>>): Double? = when {
            entries.size >= 2 -> (entries[0].value.speed + entries[1].value.speed) * 0.5
            entries.size == 1 -> entries[0].value.speed
            else -> null
        }

        fun computeTop2Angular(entries: List<Map.Entry<String, AngularVelocity>>): Double? = when {
            entries.size >= 2 -> (entries[0].value.speedDegPerSec + entries[1].value.speedDegPerSec) * 0.5
            entries.size == 1 -> entries[0].value.speedDegPerSec
            else -> null
        }

        val eTUpper = computeTop2Speed(sortedTrans.filter { it.key in upperTransSet })
        val eAUpper = computeTop2Angular(sortedAng.filter { it.key in upperAngSet })
        val eTLower = computeTop2Speed(sortedTrans.filter { it.key in lowerTransSet })
        val eALower = computeTop2Angular(sortedAng.filter { it.key in lowerAngSet })

        return KinematicEvidenceTop2(
            translationEvidence = eT,
            translationChannel1 = tChan1,
            translationChannel2 = tChan2,
            translationChannelCount = sortedTrans.size,
            translationVelocities = translationVelocities,
            angularEvidence = eA,
            angularChannel1 = aChan1,
            angularChannel2 = aChan2,
            angularChannelCount = sortedAng.size,
            angularVelocities = angularVelocities,
            referenceScale = lRef,
            cameraNearArmSide = config.cameraNearArmSide,
            upperBodyTranslationEvidence = eTUpper,
            upperBodyAngularEvidence = eAUpper,
            lowerBodyTranslationEvidence = eTLower,
            lowerBodyAngularEvidence = eALower,
        )
    }
}
