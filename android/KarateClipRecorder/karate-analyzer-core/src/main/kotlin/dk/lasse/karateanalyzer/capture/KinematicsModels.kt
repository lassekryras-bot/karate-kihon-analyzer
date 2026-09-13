package dk.lasse.karateanalyzer.capture

import dk.lasse.karateanalyzer.core.Point3
import dk.lasse.karateanalyzer.core.safeClamp
import dk.lasse.karateanalyzer.observation.Evidence
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt

enum class LateralSide { LEFT, RIGHT }

/**
 * Ternary decision state for kinematic channels with hysteresis.
 */
enum class KinematicDecisionState {
    MOVING,
    QUIET,
    MID,
    UNKNOWN,
}

/**
 * Policy for slow-displacement evaluation during continuous segmentation.
 */
enum class SlowDisplacementPolicy {
    TRAILING_WINDOW, // Legacy 300 ms sliding window <= 0.05 torso units
    DISABLED,        // Pure kinematic quiet; displacement gate omitted
    SETTLING_LOCAL,  // Anchor-relative displacement measured strictly from candidate settling onset
}

/**
 * Configuration and classification rules for Top-2 Kinematic decision layer (Phase 4 candidate v1).
 */
data class Top2KinematicsConfig(
    val enabled: Boolean = false,
    val translationMoving: Double = 0.70,   // L_ref / s
    val translationQuiet: Double = 0.45,    // L_ref / s
    val angularMoving: Double = 35.0,       // deg / s
    val angularQuiet: Double = 20.0,        // deg / s
) {
    init {
        require(translationMoving >= translationQuiet && translationQuiet >= 0.0)
        require(angularMoving >= angularQuiet && angularQuiet >= 0.0)
    }

    fun classifyTranslation(value: Double?): KinematicDecisionState {
        if (value == null || !value.isFinite()) return KinematicDecisionState.UNKNOWN
        return when {
            value >= translationMoving -> KinematicDecisionState.MOVING
            value <= translationQuiet -> KinematicDecisionState.QUIET
            else -> KinematicDecisionState.MID
        }
    }

    fun classifyAngular(value: Double?): KinematicDecisionState {
        if (value == null || !value.isFinite()) return KinematicDecisionState.UNKNOWN
        return when {
            value >= angularMoving -> KinematicDecisionState.MOVING
            value <= angularQuiet -> KinematicDecisionState.QUIET
            else -> KinematicDecisionState.MID
        }
    }
}

/**
 * Top-2 Kinematic Evidence contract (Mathematical Contract Sections 14, 15).
 * Holds arithmetic mean of two strongest trustworthy translation and angular channels.
 */
data class KinematicEvidenceTop2(
    val translationEvidence: Double?,               // E_T: mean of top-2 translation speeds (L_ref / s)
    val translationChannel1: String?,               // Primary translation channel
    val translationChannel2: String?,               // Secondary translation channel
    val translationChannelCount: Int,               // N_T: count of usable translation channels
    val translationVelocities: Map<String, Velocity2D> = emptyMap(),
    val angularEvidence: Double?,                   // E_A: mean of top-2 angular rates (deg / s)
    val angularChannel1: String?,                   // Primary angular channel
    val angularChannel2: String?,                   // Secondary angular channel
    val angularChannelCount: Int,                   // N_A: count of usable angular channels
    val angularVelocities: Map<String, AngularVelocity> = emptyMap(),
    val referenceScale: Double? = null,             // L_ref: median camera-near upper-arm length
    val cameraNearArmSide: LateralSide? = null,
    val upperBodyTranslationEvidence: Double? = null,
    val upperBodyAngularEvidence: Double? = null,
    val lowerBodyTranslationEvidence: Double? = null,
    val lowerBodyAngularEvidence: Double? = null,
) {
    fun settlingTranslationEvidence(scope: SettlingEvidenceScope): Double? = when (scope) {
        SettlingEvidenceScope.WHOLE_BODY -> translationEvidence
        SettlingEvidenceScope.UPPER_BODY -> upperBodyTranslationEvidence
        SettlingEvidenceScope.LOWER_BODY -> lowerBodyTranslationEvidence
    }

    fun settlingAngularEvidence(scope: SettlingEvidenceScope): Double? = when (scope) {
        SettlingEvidenceScope.WHOLE_BODY -> angularEvidence
        SettlingEvidenceScope.UPPER_BODY -> upperBodyAngularEvidence
        SettlingEvidenceScope.LOWER_BODY -> lowerBodyAngularEvidence
    }
}

/**
 * Configuration for noise-resilient biomechanical kinematics.
 */
data class KinematicsConfig(
    val enabled: Boolean = true,
    val windowDurationMs: Long = 100L,
    val minimumSamples: Int = 3,
    val minimumWindowSpanRatio: Double = 0.60,
    val minimumLandmarkConfidence: Double = 0.50,
    val cameraNearArmSide: LateralSide? = LateralSide.RIGHT,
    val minSegmentLengthRatio: Double = 0.15,
    val minAxisLengthRatio: Double = 0.25,
    // Candidate replay-validation thresholds
    val endpointSpeedThreshold: Double = 0.35,          // L_ref / second
    val radialSpeedThreshold: Double = 0.25,            // L_ref / second
    val jointAngularVelocityThreshold: Double = 35.0,    // degrees / second
    val segmentOrientationRateThreshold: Double = 35.0,  // degrees / second
    val pelvisYawRateThreshold: Double = 18.0,          // degrees / second
    val pelvisRotationRateThreshold: Double = 30.0,     // legacy alias / 3D threshold
    val pelvis3dRotationInGating: Boolean = false,      // diagnostic only
    val maximumAngularVelocity: Double = 1800.0,        // degrees / second clamp
) {
    init {
        require(windowDurationMs in 40L..500L)
        require(minimumSamples >= 2)
        require(minimumWindowSpanRatio in 0.20..1.0)
        require(minimumLandmarkConfidence in 0.0..1.0)
        require(endpointSpeedThreshold > 0.0 && radialSpeedThreshold > 0.0)
        require(jointAngularVelocityThreshold > 0.0 && segmentOrientationRateThreshold > 0.0)
        require(pelvisYawRateThreshold > 0.0 && maximumAngularVelocity > 0.0)
    }
}

data class ArmKinematics(
    val wristTorsoRelativeSpeed: Double? = null,
    val wristRadialVelocity: Double? = null,
    val elbowAngleDegrees: Double? = null,
    val elbowAngularVelocityDegPerSec: Double? = null,
    val upperArmOrientationChangeDegPerSec: Double? = null,
    val forearmOrientationChangeDegPerSec: Double? = null,
    val rawWristSpeed: Double? = null,
    val rawElbowVelocity: Double? = null,
    val coverage: Double = 0.0,
    val motionEvidence: Evidence = Evidence.UNKNOWN,
    val activeChannels: List<String> = emptyList(),
)

data class LegKinematics(
    val ankleTorsoRelativeSpeed: Double? = null,
    val ankleRadialVelocity: Double? = null,
    val kneeTorsoRelativeSpeed: Double? = null,
    val kneeRadialVelocity: Double? = null,
    val kneeAngleDegrees: Double? = null,
    val kneeAngularVelocityDegPerSec: Double? = null,
    val thighOrientationChangeDegPerSec: Double? = null,
    val shinOrientationChangeDegPerSec: Double? = null,
    val rawAnkleSpeed: Double? = null,
    val rawKneeVelocity: Double? = null,
    val coverage: Double = 0.0,
    val motionEvidence: Evidence = Evidence.UNKNOWN,
    val activeChannels: List<String> = emptyList(),
)

data class PelvisKinematics(
    val rotationRateDegPerSec: Double? = null,
    val yawRotationRateDegPerSec: Double? = null,
    val rawYawRateDegPerSec: Double? = null,
    val coverage: Double = 0.0,
    val motionEvidence: Evidence = Evidence.UNKNOWN,
    val activeChannels: List<String> = emptyList(),
)

data class KinematicPositiveMotionEvidence(
    val motionEvidence: Evidence = Evidence.UNKNOWN,
    val leftArm: Evidence = Evidence.UNKNOWN,
    val rightArm: Evidence = Evidence.UNKNOWN,
    val leftLeg: Evidence = Evidence.UNKNOWN,
    val rightLeg: Evidence = Evidence.UNKNOWN,
    val pelvis: Evidence = Evidence.UNKNOWN,
    val triggeringSide: String? = null,
    val triggeringChain: String? = null,
    val triggeringSignal: String? = null,
    val rawValue: Double? = null,
    val smoothedValue: Double? = null,
    val threshold: Double? = null,
    val confidence: Double = 0.0,
    val windowDurationMs: Long = 100L,
    val triggeringChannels: List<String> = emptyList(),
    val diagnosticCoherence: Double? = null,
    val diagnosticRegressionSlope: Double? = null,
    val channels: List<KinematicChannelEvidence> = emptyList(),
) {
    val anyLimbMotion: Evidence get() = motionEvidence

    constructor(
        leftArm: Evidence = Evidence.UNKNOWN,
        rightArm: Evidence = Evidence.UNKNOWN,
        leftLeg: Evidence = Evidence.UNKNOWN,
        rightLeg: Evidence = Evidence.UNKNOWN,
        pelvis: Evidence = Evidence.UNKNOWN,
        anyLimbMotion: Evidence = Evidence.UNKNOWN,
        triggeringChannels: List<String> = emptyList(),
    ) : this(
        motionEvidence = anyLimbMotion,
        leftArm = leftArm,
        rightArm = rightArm,
        leftLeg = leftLeg,
        rightLeg = rightLeg,
        pelvis = pelvis,
        triggeringChannels = triggeringChannels,
    )
}

data class KinematicChannelEvidence(
    val name: String,
    val rawValue: Double?,
    val smoothedValue: Double?,
    val coherence: Double?,
    val activity: Double?,
    val threshold: Double,
    val confidence: Double?,
    val evidence: Evidence,
    val usedInGating: Boolean = true,
)

typealias PositiveMotionEvidence = KinematicPositiveMotionEvidence

data class BodyKinematics(
    val leftArm: ArmKinematics,
    val rightArm: ArmKinematics,
    val leftLeg: LegKinematics,
    val rightLeg: LegKinematics,
    val pelvis: PelvisKinematics,
    val positiveEvidence: KinematicPositiveMotionEvidence,
    val top2: KinematicEvidenceTop2? = null,
)

internal object KinematicsMath {
    private const val EPSILON = 1e-6

    fun unit(v: Point3): Point3? {
        val mag = sqrt((v.x * v.x + v.y * v.y + v.z * v.z).toDouble())
        if (!mag.isFinite() || mag <= EPSILON) return null
        return Point3((v.x / mag).toFloat(), (v.y / mag).toFloat(), (v.z / mag).toFloat())
    }

    fun angleBetweenVectorsDeg(v1: Point3, v2: Point3): Double? {
        val u1 = unit(v1) ?: return null
        val u2 = unit(v2) ?: return null
        val dot = (u1.x * u2.x + u1.y * u2.y + u1.z * u2.z).toDouble()
        val clamped = safeClamp(dot.toFloat(), -1.0f, 1.0f)?.toDouble() ?: return null
        val degrees = acos(clamped) * 180.0 / Math.PI
        return if (degrees.isFinite()) degrees else null
    }

    fun wrapDegrees(deg: Double): Double {
        var d = deg % 360.0
        if (d > 180.0) d -= 360.0
        if (d < -180.0) d += 360.0
        return d
    }

    fun circularDiffDeg(psi2: Double, psi1: Double): Double {
        return wrapDegrees(psi2 - psi1)
    }

    fun distance(p1: Point3, p2: Point3): Double {
        val dx = (p1.x - p2.x).toDouble()
        val dy = (p1.y - p2.y).toDouble()
        val dz = (p1.z - p2.z).toDouble()
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}
