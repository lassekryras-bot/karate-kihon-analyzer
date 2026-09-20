package dk.lasse.karateanalyzer.core

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt

enum class TargetRayState {
    VALID,
    UNREACHABLE,
    ABSTAINED,
    FAILED,
}

data class TargetRayEvaluation(
    val targetType: PunchHeightTargetType,
    val state: TargetRayState,
    val concreteTargetId: TargetId? = null,
    val idealAngleDeg: Float? = null,
    val errorDeg: Float? = null,
    val targetPoint: Point3? = null,
    val idealEndpoint: Point3? = null,
    val reason: String? = null,
)

data class StraightPunchTargetEvaluation(
    val state: TargetRayState,
    val activeArm: ActiveArm,
    val analysisFrameTimestampMs: Long,
    val shoulderPoint: Point3? = null,
    val fistPoint: Point3? = null,
    val elbowPoint: Point3? = null,
    val wristPoint: Point3? = null,
    val actualAngleDeg: Float? = null,
    val armReachRadius: Float? = null,
    val armReachProvenance: String = ARM_REACH_PROVENANCE,
    val elbowAngleDeg: Float? = null,
    val closestTarget: PunchHeightTargetType? = null,
    val closestConcreteTargetId: TargetId? = null,
    val targetAngleErrorDeg: Float? = null,
    val classificationMarginDeg: Float? = null,
    val targetResults: Map<PunchHeightTargetType, TargetRayEvaluation> = emptyMap(),
    val reason: String? = null,
) {
    companion object {
        const val ARM_REACH_PROVENANCE = "anatomical_segment_sum_v1"
    }
}

/**
 * Evaluates terminal arm ray direction against ideal Jōdan, Chūdan, and Gedan target rays
 * in a shoulder-centered body-relative coordinate frame.
 */
class StraightPunchTargetCalculator(
    private val chinEstimator: SideViewChinEstimator = SideViewChinEstimator(),
    private val explicitGedanTarget: TargetId? = null,
) {
    private val jodanModel = JodanTargetModel(chinEstimator)
    private val chudanModel = ChudanTargetModel()
    private val gedanModel = GedanTargetModel(explicitGedanTarget)

    fun evaluate(
        frame: PoseFrame,
        bodyReference: BodyReference,
        activeArm: ActiveArm,
        chinProjectionMultiplier: Float = PunchHeightAnalyzer.DEFAULT_CHIN_PROJECTION_MULTIPLIER,
        explicitGedanTarget: TargetId? = this.explicitGedanTarget,
    ): StraightPunchTargetEvaluation {
        val tracked = TrackedPoseFrame(frame.timestampMs, frame.landmarks)
        return evaluate(tracked, bodyReference, activeArm, chinProjectionMultiplier, explicitGedanTarget)
    }

    fun evaluate(
        frame: TrackedPoseFrame,
        bodyReference: BodyReference,
        activeArm: ActiveArm,
        chinProjectionMultiplier: Float = PunchHeightAnalyzer.DEFAULT_CHIN_PROJECTION_MULTIPLIER,
        explicitGedanTarget: TargetId? = this.explicitGedanTarget,
    ): StraightPunchTargetEvaluation {
        val timestampMs = frame.timestampMs

        if (activeArm == ActiveArm.NONE) {
            return StraightPunchTargetEvaluation(
                state = TargetRayState.ABSTAINED,
                activeArm = ActiveArm.NONE,
                analysisFrameTimestampMs = timestampMs,
                reason = "striking_arm_not_determined",
            )
        }

        val armIds = when (activeArm) {
            ActiveArm.LEFT -> ArmLandmarkGroup(
                PoseLandmarkId.LEFT_SHOULDER,
                PoseLandmarkId.LEFT_ELBOW,
                PoseLandmarkId.LEFT_WRIST,
                PoseLandmarkId.LEFT_INDEX,
                PoseLandmarkId.LEFT_PINKY,
            )
            ActiveArm.RIGHT -> ArmLandmarkGroup(
                PoseLandmarkId.RIGHT_SHOULDER,
                PoseLandmarkId.RIGHT_ELBOW,
                PoseLandmarkId.RIGHT_WRIST,
                PoseLandmarkId.RIGHT_INDEX,
                PoseLandmarkId.RIGHT_PINKY,
            )
            ActiveArm.NONE -> return StraightPunchTargetEvaluation(
                state = TargetRayState.ABSTAINED,
                activeArm = ActiveArm.NONE,
                analysisFrameTimestampMs = timestampMs,
                reason = "striking_arm_not_determined",
            )
        }

        val shoulderSample = frame.landmarks[armIds.shoulder]
        val elbowSample = frame.landmarks[armIds.elbow]
        val wristSample = frame.landmarks[armIds.wrist]

        if (shoulderSample?.position == null || elbowSample?.position == null || wristSample?.position == null) {
            return StraightPunchTargetEvaluation(
                state = TargetRayState.ABSTAINED,
                activeArm = activeArm,
                analysisFrameTimestampMs = timestampMs,
                reason = "missing_required_arm_landmarks",
            )
        }

        val shoulder = shoulderSample.position
        val elbow = elbowSample.position
        val wrist = wristSample.position

        val handPoints = listOfNotNull(
            frame.landmarks[armIds.index]?.position,
            frame.landmarks[armIds.pinky]?.position,
        )
        val fist = if (handPoints.isNotEmpty()) {
            average(listOf(wrist) + handPoints)
        } else {
            wrist
        }

        // Full anatomical arm reach radius R: upper arm + forearm/fist segments
        val upperArmLength = (elbow - shoulder).length2d()
        val forearmLength = (fist - elbow).length2d()
        val armReach = upperArmLength + forearmLength

        if (armReach <= 0.001f || upperArmLength <= 0.0005f || forearmLength <= 0.0005f) {
            return StraightPunchTargetEvaluation(
                state = TargetRayState.ABSTAINED,
                activeArm = activeArm,
                analysisFrameTimestampMs = timestampMs,
                shoulderPoint = shoulder,
                fistPoint = fist,
                elbowPoint = elbow,
                wristPoint = wrist,
                armReachRadius = armReach,
                reason = "degenerate_arm_reach_radius",
            )
        }

        val actualRay = fist - shoulder
        val actualDistance = actualRay.length2d()
        if (actualDistance <= 0.0005f) {
            return StraightPunchTargetEvaluation(
                state = TargetRayState.ABSTAINED,
                activeArm = activeArm,
                analysisFrameTimestampMs = timestampMs,
                shoulderPoint = shoulder,
                fistPoint = fist,
                elbowPoint = elbow,
                wristPoint = wrist,
                armReachRadius = armReach,
                reason = "zero_arm_extension_ray",
            )
        }

        // Orthonormal body coordinate frame: (u_forward, u_up)
        // torsoAxis points from shoulder to hip (downwards).
        val uDown = bodyReference.torsoAxis
        val uUp = Point3(-uDown.x, -uDown.y, 0f)

        // The two unit vectors perpendicular to torsoAxis in 2D:
        val p1 = Point3(-uDown.y, uDown.x, 0f)
        val p2 = Point3(uDown.y, -uDown.x, 0f)
        val uForward = if (actualRay.dot2d(p1) >= actualRay.dot2d(p2)) p1 else p2

        val actualForward = actualRay.dot2d(uForward)
        val actualUp = actualRay.dot2d(uUp)
        val actualAngleDeg = Math.toDegrees(atan2(actualUp.toDouble(), actualForward.toDouble())).toFloat()
        val elbowAngle = angleDegrees(shoulder, elbow, wrist)

        val effectiveGedanModel = if (explicitGedanTarget != this.explicitGedanTarget) GedanTargetModel(explicitGedanTarget) else gedanModel
        // Evaluate all three targets: JODAN, CHUDAN, GEDAN
        val targets = listOf(
            PunchHeightTargetType.JODAN to jodanModel.evaluate(frame, bodyReference, chinProjectionMultiplier),
            PunchHeightTargetType.CHUDAN to chudanModel.evaluate(frame, bodyReference, chinProjectionMultiplier),
            PunchHeightTargetType.GEDAN to effectiveGedanModel.evaluate(frame, bodyReference, chinProjectionMultiplier),
        )

        val targetResults = mutableMapOf<PunchHeightTargetType, TargetRayEvaluation>()

        for ((targetType, target) in targets) {
            if (target == null) {
                targetResults[targetType] = TargetRayEvaluation(
                    targetType = targetType,
                    state = TargetRayState.ABSTAINED,
                    concreteTargetId = if (targetType == PunchHeightTargetType.GEDAN) explicitGedanTarget else null,
                    reason = if (targetType == PunchHeightTargetType.GEDAN && explicitGedanTarget == null)
                        "provisional_gedan_target_unspecified"
                    else "target_model_unavailable",
                )
                continue
            }

            val targetPoint = target.targetPoint
            val delta = targetPoint - shoulder
            val hT = delta.dot2d(uUp)

            val xSq = armReach * armReach - hT * hT
            if (xSq < 0f) {
                targetResults[targetType] = TargetRayEvaluation(
                    targetType = targetType,
                    state = TargetRayState.UNREACHABLE,
                    concreteTargetId = target.targetId,
                    targetPoint = targetPoint,
                    reason = "target_outside_reach_circle",
                )
                continue
            }

            val xForward = sqrt(xSq)
            val idealRay = uForward * xForward + uUp * hT
            val idealEndpoint = shoulder + idealRay
            val idealAngleDeg = Math.toDegrees(atan2(hT.toDouble(), xForward.toDouble())).toFloat()

            // Signed error convention: positive = actual direction above ideal target, negative = below
            val errorDeg = actualAngleDeg - idealAngleDeg

            targetResults[targetType] = TargetRayEvaluation(
                targetType = targetType,
                state = TargetRayState.VALID,
                concreteTargetId = target.targetId,
                idealAngleDeg = idealAngleDeg,
                errorDeg = errorDeg,
                targetPoint = targetPoint,
                idealEndpoint = idealEndpoint,
            )
        }

        val validResults = targetResults.values.filter { it.state == TargetRayState.VALID && it.errorDeg != null }

        if (validResults.isEmpty()) {
            return StraightPunchTargetEvaluation(
                state = TargetRayState.ABSTAINED,
                activeArm = activeArm,
                analysisFrameTimestampMs = timestampMs,
                shoulderPoint = shoulder,
                fistPoint = fist,
                elbowPoint = elbow,
                wristPoint = wrist,
                actualAngleDeg = actualAngleDeg,
                armReachRadius = armReach,
                elbowAngleDeg = elbowAngle,
                targetResults = targetResults,
                reason = "no_valid_target_geometry",
            )
        }

        val sorted = validResults.sortedBy { abs(it.errorDeg!!) }
        val closest = sorted.first()
        val margin = if (sorted.size >= 2) {
            abs(sorted[1].errorDeg!!) - abs(sorted[0].errorDeg!!)
        } else {
            null
        }

        return StraightPunchTargetEvaluation(
            state = TargetRayState.VALID,
            activeArm = activeArm,
            analysisFrameTimestampMs = timestampMs,
            shoulderPoint = shoulder,
            fistPoint = fist,
            elbowPoint = elbow,
            wristPoint = wrist,
            actualAngleDeg = actualAngleDeg,
            armReachRadius = armReach,
            elbowAngleDeg = elbowAngle,
            closestTarget = closest.targetType,
            closestConcreteTargetId = closest.concreteTargetId,
            targetAngleErrorDeg = closest.errorDeg,
            classificationMarginDeg = margin,
            targetResults = targetResults,
        )
    }

    private data class ArmLandmarkGroup(
        val shoulder: PoseLandmarkId,
        val elbow: PoseLandmarkId,
        val wrist: PoseLandmarkId,
        val index: PoseLandmarkId,
        val pinky: PoseLandmarkId,
    )

    private fun Point3.length2d(): Float = sqrt(x * x + y * y)
    private fun Point3.dot2d(other: Point3): Float = x * other.x + y * other.y

    private fun average(points: List<Point3>): Point3 {
        if (points.isEmpty()) return Point3(0f, 0f, 0f)
        return points.reduce(Point3::plus) * (1f / points.size)
    }

    private fun angleDegrees(a: Point3, vertex: Point3, c: Point3): Float {
        val first = a - vertex
        val second = c - vertex
        val denominator = first.length2d() * second.length2d()
        if (denominator <= 0f) return 0f
        val cosine = (first.dot2d(second) / denominator).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cosine).toDouble()).toFloat()
    }
}
