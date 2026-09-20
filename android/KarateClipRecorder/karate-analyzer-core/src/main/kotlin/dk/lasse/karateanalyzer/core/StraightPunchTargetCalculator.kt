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
        const val MULTI_FRAME_REACH_PROVENANCE = "multi_frame_stable_segment_sum_v1"
    }
}

/**
 * Evaluates terminal arm ray direction against ideal Jōdan, Chūdan, and Gedan target rays
 * in a shoulder-centered body-relative coordinate frame using aspect-correct Euclidean geometry.
 */
class StraightPunchTargetCalculator(
    private val chinEstimator: SideViewChinEstimator = SideViewChinEstimator(),
    private val explicitGedanTarget: TargetId? = null,
    val aspectRatio: Float = 1.0f,
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
        stableArmReachRadius: Float? = null,
        stableArmReachProvenance: String? = null,
    ): StraightPunchTargetEvaluation {
        val tracked = TrackedPoseFrame(frame.timestampMs, frame.landmarks)
        return evaluate(
            tracked,
            bodyReference,
            activeArm,
            chinProjectionMultiplier,
            explicitGedanTarget,
            stableArmReachRadius,
            stableArmReachProvenance,
        )
    }

    fun evaluate(
        frame: TrackedPoseFrame,
        bodyReference: BodyReference,
        activeArm: ActiveArm,
        chinProjectionMultiplier: Float = PunchHeightAnalyzer.DEFAULT_CHIN_PROJECTION_MULTIPLIER,
        explicitGedanTarget: TargetId? = this.explicitGedanTarget,
        stableArmReachRadius: Float? = null,
        stableArmReachProvenance: String? = null,
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

        fun toAspect(pt: Point3): Point3 = Point3(pt.x * aspectRatio, pt.y, pt.z)
        fun toNorm(pt: Point3): Point3 = Point3(pt.x / aspectRatio, pt.y, pt.z)

        val shoulderA = toAspect(shoulder)
        val elbowA = toAspect(elbow)
        val wristA = toAspect(wrist)
        val fistA = toAspect(fist)

        val upperArmLengthA = (elbowA - shoulderA).length2d()
        val forearmLengthA = (fistA - elbowA).length2d()
        val armReach = if (stableArmReachRadius != null && stableArmReachRadius > 0.001f) {
            stableArmReachRadius
        } else {
            upperArmLengthA + forearmLengthA
        }
        val reachProvenance = if (stableArmReachRadius != null && stableArmReachRadius > 0.001f) {
            stableArmReachProvenance ?: "stable_arm_reach_v1"
        } else {
            StraightPunchTargetEvaluation.ARM_REACH_PROVENANCE
        }

        if (armReach <= 0.001f || upperArmLengthA <= 0.0005f || forearmLengthA <= 0.0005f) {
            return StraightPunchTargetEvaluation(
                state = TargetRayState.ABSTAINED,
                activeArm = activeArm,
                analysisFrameTimestampMs = timestampMs,
                shoulderPoint = shoulder,
                fistPoint = fist,
                elbowPoint = elbow,
                wristPoint = wrist,
                armReachRadius = armReach,
                armReachProvenance = reachProvenance,
                reason = "degenerate_arm_reach_radius",
            )
        }

        val actualRayA = fistA - shoulderA
        val actualDistanceA = actualRayA.length2d()
        if (actualDistanceA <= 0.0005f) {
            return StraightPunchTargetEvaluation(
                state = TargetRayState.ABSTAINED,
                activeArm = activeArm,
                analysisFrameTimestampMs = timestampMs,
                shoulderPoint = shoulder,
                fistPoint = fist,
                elbowPoint = elbow,
                wristPoint = wrist,
                armReachRadius = armReach,
                armReachProvenance = reachProvenance,
                reason = "zero_arm_extension_ray",
            )
        }

        // Orthonormal body coordinate frame in aspect-corrected space: (uForward, uUp)
        // Locked neutral vertical axis derived from BodyReference torsoAxis (pointing downwards).
        val refTorsoA = Point3(bodyReference.torsoAxis.x * aspectRatio, bodyReference.torsoAxis.y, 0f)
        val refTorsoLenA = refTorsoA.length2d()
        val uDown = if (refTorsoLenA > 0.0001f) refTorsoA * (1f / refTorsoLenA) else Point3(0f, 1f, 0f)
        val uUp = Point3(-uDown.x, -uDown.y, 0f)

        val p1 = Point3(-uDown.y, uDown.x, 0f)
        val p2 = Point3(uDown.y, -uDown.x, 0f)
        val uForward = if (actualRayA.dot2d(p1) >= actualRayA.dot2d(p2)) p1 else p2

        val actualForward = actualRayA.dot2d(uForward)
        val actualUp = actualRayA.dot2d(uUp)
        val actualAngleDeg = Math.toDegrees(atan2(actualUp.toDouble(), actualForward.toDouble())).toFloat()
        val elbowAngle = angleDegrees(shoulderA, elbowA, wristA)

        // Retrieve stable current body origin from impact frame (bilateral hip midpoint)
        val leftHipSample = frame.landmarks[PoseLandmarkId.LEFT_HIP]
        val rightHipSample = frame.landmarks[PoseLandmarkId.RIGHT_HIP]
        val leftHip = leftHipSample?.takeIf { it.isObserved() }?.position ?: leftHipSample?.position
        val rightHip = rightHipSample?.takeIf { it.isObserved() }?.position ?: rightHipSample?.position

        val currentBodyOrigin = when {
            leftHip != null && rightHip != null -> Point3((leftHip.x + rightHip.x) * 0.5f, (leftHip.y + rightHip.y) * 0.5f, (leftHip.z + rightHip.z) * 0.5f)
            leftHip != null -> leftHip
            rightHip != null -> rightHip
            else -> null
        }

        if (currentBodyOrigin == null) {
            return StraightPunchTargetEvaluation(
                state = TargetRayState.ABSTAINED,
                activeArm = activeArm,
                analysisFrameTimestampMs = timestampMs,
                shoulderPoint = shoulder,
                fistPoint = fist,
                elbowPoint = elbow,
                wristPoint = wrist,
                armReachRadius = armReach,
                armReachProvenance = reachProvenance,
                reason = "missing_body_origin_landmarks",
            )
        }

        val currentBodyOriginA = toAspect(currentBodyOrigin)
        val neutralBodyOriginA = toAspect(bodyReference.hipPoint)
        val bodyTranslationA = currentBodyOriginA - neutralBodyOriginA

        val effectiveGedanModel = if (explicitGedanTarget != this.explicitGedanTarget) GedanTargetModel(explicitGedanTarget) else gedanModel
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

            // Transport neutral anatomical target point with stable body origin translation
            val neutralTargetPointA = toAspect(target.targetPoint)
            val currentAnatomicalTargetA = neutralTargetPointA + bodyTranslationA
            val currentAnatomicalTargetNorm = toNorm(currentAnatomicalTargetA)

            // Signed vertical offset along locked neutral vertical axis uUp from striking shoulder
            val hT = (currentAnatomicalTargetA - shoulderA).dot2d(uUp)

            val xSq = armReach * armReach - hT * hT
            if (xSq < 0f) {
                targetResults[targetType] = TargetRayEvaluation(
                    targetType = targetType,
                    state = TargetRayState.UNREACHABLE,
                    concreteTargetId = target.targetId,
                    targetPoint = currentAnatomicalTargetNorm,
                    reason = "target_outside_reach_circle",
                )
                continue
            }

            val xForward = sqrt(xSq)
            val idealRayA = uForward * xForward + uUp * hT
            val idealEndpointA = shoulderA + idealRayA
            val idealEndpointNorm = toNorm(idealEndpointA)
            val idealAngleDeg = Math.toDegrees(atan2(hT.toDouble(), xForward.toDouble())).toFloat()

            val errorDeg = actualAngleDeg - idealAngleDeg

            targetResults[targetType] = TargetRayEvaluation(
                targetType = targetType,
                state = TargetRayState.VALID,
                concreteTargetId = target.targetId,
                idealAngleDeg = idealAngleDeg,
                errorDeg = errorDeg,
                targetPoint = currentAnatomicalTargetNorm,
                idealEndpoint = idealEndpointNorm,
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
                armReachProvenance = reachProvenance,
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
            armReachProvenance = reachProvenance,
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
