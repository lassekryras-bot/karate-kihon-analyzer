package dk.lasse.karateanalyzer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class StraightPunchTargetCalculatorTest {

    private fun landmarkSample(x: Float, y: Float, z: Float = 0f, confidence: Float = 0.9f) =
        PoseLandmarkSample(Point3(x, y, z), Point3(x, y, z), confidence, confidence, LandmarkSource.OBSERVED)

    private fun testBodyReference(
        shoulder: Point3 = Point3(0.5f, 0.3f, 0f),
        hip: Point3 = Point3(0.5f, 0.7f, 0f),
        visibleSide: VisibleSide = VisibleSide.LEFT,
    ): BodyReference {
        val torsoVec = hip - shoulder
        val len = kotlin.math.sqrt(torsoVec.x * torsoVec.x + torsoVec.y * torsoVec.y)
        return BodyReference(
            visibleSide = visibleSide,
            shoulderPoint = shoulder,
            hipPoint = hip,
            torsoAxis = Point3(torsoVec.x / len, torsoVec.y / len, 0f),
            torsoLength = len, // 0.4f
            confidence = 0.95f,
        )
    }

    /**
     * Builds a PoseFrame with a side view of a practitioner facing left (x decreases forward).
     * Shoulder at (0.5, 0.3).
     * Upper arm length ~ 0.20, forearm length ~ 0.20 -> total reach R ~ 0.40.
     */
    private fun buildFrame(
        fist: Point3,
        elbow: Point3 = Point3(0.3f, 0.3f, 0f),
        shoulder: Point3 = Point3(0.5f, 0.3f, 0f),
        chin: Point3 = Point3(0.45f, 0.22f, 0f),
        nose: Point3 = Point3(0.45f, 0.18f, 0f),
        mouth: Point3 = Point3(0.45f, 0.20f, 0f),
        activeArm: ActiveArm = ActiveArm.LEFT,
        missingLandmark: PoseLandmarkId? = null,
        includeFingers: Boolean = true,
        hip: Point3? = null,
    ): PoseFrame {
        val landmarks = mutableMapOf<PoseLandmarkId, PoseLandmarkSample>()

        val hipPt = hip ?: Point3(shoulder.x, shoulder.y + 0.4f, shoulder.z)
        landmarks[PoseLandmarkId.LEFT_SHOULDER] = landmarkSample(shoulder.x, shoulder.y, shoulder.z)
        landmarks[PoseLandmarkId.RIGHT_SHOULDER] = landmarkSample(shoulder.x, shoulder.y, shoulder.z + 0.1f)
        landmarks[PoseLandmarkId.LEFT_HIP] = landmarkSample(hipPt.x, hipPt.y, hipPt.z)
        landmarks[PoseLandmarkId.RIGHT_HIP] = landmarkSample(hipPt.x, hipPt.y, hipPt.z + 0.1f)

        landmarks[PoseLandmarkId.NOSE] = landmarkSample(nose.x, nose.y)
        landmarks[PoseLandmarkId.MOUTH_LEFT] = landmarkSample(mouth.x, mouth.y)
        landmarks[PoseLandmarkId.MOUTH_RIGHT] = landmarkSample(mouth.x, mouth.y)

        if (activeArm == ActiveArm.LEFT) {
            landmarks[PoseLandmarkId.LEFT_ELBOW] = landmarkSample(elbow.x, elbow.y)
            landmarks[PoseLandmarkId.LEFT_WRIST] = landmarkSample(fist.x, fist.y)
            if (includeFingers) {
                landmarks[PoseLandmarkId.LEFT_INDEX] = landmarkSample(fist.x - 0.02f, fist.y)
                landmarks[PoseLandmarkId.LEFT_PINKY] = landmarkSample(fist.x - 0.02f, fist.y + 0.01f)
            }
        } else {
            landmarks[PoseLandmarkId.RIGHT_ELBOW] = landmarkSample(elbow.x, elbow.y)
            landmarks[PoseLandmarkId.RIGHT_WRIST] = landmarkSample(fist.x, fist.y)
            if (includeFingers) {
                landmarks[PoseLandmarkId.RIGHT_INDEX] = landmarkSample(fist.x - 0.02f, fist.y)
                landmarks[PoseLandmarkId.RIGHT_PINKY] = landmarkSample(fist.x - 0.02f, fist.y + 0.01f)
            }
        }

        if (missingLandmark != null) {
            landmarks.remove(missingLandmark)
        }

        return PoseFrame(timestampMs = 1000L, landmarks = landmarks)
    }

    @Test
    fun exactChudanRaySelectsChudanWithNearZeroError() {
        val calculator = StraightPunchTargetCalculator()
        val body = testBodyReference()

        // Chūdan target point is at shoulder + torsoAxis * (0.45 * torsoLength) = (0.5, 0.3 + 0.45 * 0.4) = (0.5, 0.48)
        // Shoulder S = (0.5, 0.3).
        // Target offset h_T = (0.48 - 0.3) along down = -0.18 along up.
        // Reach R = 0.40.
        // x_forward = sqrt(0.40^2 - 0.18^2) = sqrt(0.16 - 0.0324) = sqrt(0.1276) ≈ 0.3572
        // In side view facing left (x decreases forward), ideal endpoint is (0.5 - 0.3572, 0.48) = (0.1428, 0.48).
        val idealFist = Point3(0.5f - 0.3572f, 0.48f, 0f)
        val elbow = Point3(0.5f - 0.18f, 0.39f, 0f)
        val frame = buildFrame(fist = idealFist, elbow = elbow)

        val result = calculator.evaluate(frame, body, ActiveArm.LEFT)

        assertEquals(TargetRayState.VALID, result.state)
        assertEquals(PunchHeightTargetType.CHUDAN, result.closestTarget)
        assertEquals(TargetId.CHUDAN_SOLAR_PLEXUS, result.closestConcreteTargetId)
        assertNotNull(result.targetAngleErrorDeg)
        assertTrue("Chudan error should be < 1.0 deg, was ${result.targetAngleErrorDeg}", abs(result.targetAngleErrorDeg!!) < 1.0f)
    }

    @Test
    fun exactGedanRaySelectsGedanWithNearZeroError() {
        val calculator = StraightPunchTargetCalculator(explicitGedanTarget = TargetId.GEDAN_LOWER_ABDOMEN)
        val body = testBodyReference()

        // Gedan ratio = 0.80 -> target y = 0.3 + 0.80 * 0.4 = 0.62.
        // h_T = -0.32 along up.
        // Reach R = 0.40.
        // x_forward = sqrt(0.40^2 - 0.32^2) = sqrt(0.16 - 0.1024) = sqrt(0.0576) = 0.24.
        val idealFist = Point3(0.5f - 0.24f, 0.62f, 0f)
        val elbow = Point3(0.5f - 0.12f, 0.46f, 0f)
        val frame = buildFrame(fist = idealFist, elbow = elbow)

        val result = calculator.evaluate(frame, body, ActiveArm.LEFT)

        assertEquals(TargetRayState.VALID, result.state)
        assertEquals(PunchHeightTargetType.GEDAN, result.closestTarget)
        assertEquals(TargetId.GEDAN_LOWER_ABDOMEN, result.closestConcreteTargetId)
        assertEquals(TargetId.GEDAN_LOWER_ABDOMEN, result.targetResults[PunchHeightTargetType.GEDAN]?.concreteTargetId)
        assertNotNull(result.targetAngleErrorDeg)
        assertTrue("Gedan error should be < 1.0 deg, was ${result.targetAngleErrorDeg}", abs(result.targetAngleErrorDeg!!) < 1.0f)
    }

    @Test
    fun unconfiguredGedanDefaultsToProvisionalAbstention() {
        val calculator = StraightPunchTargetCalculator() // unconfigured Gedan
        val body = testBodyReference()

        val idealFist = Point3(0.5f - 0.24f, 0.62f, 0f)
        val elbow = Point3(0.5f - 0.12f, 0.46f, 0f)
        val frame = buildFrame(fist = idealFist, elbow = elbow)

        val result = calculator.evaluate(frame, body, ActiveArm.LEFT)

        val gedanResult = result.targetResults[PunchHeightTargetType.GEDAN]
        assertNotNull(gedanResult)
        assertEquals(TargetRayState.ABSTAINED, gedanResult?.state)
        assertNull(gedanResult?.concreteTargetId)
        assertEquals("provisional_gedan_target_unspecified", gedanResult?.reason)
        // Gedan cannot be selected as closest target when abstained
        assertEquals(PunchHeightTargetType.CHUDAN, result.closestTarget)
        assertEquals(TargetId.CHUDAN_SOLAR_PLEXUS, result.closestConcreteTargetId)
    }

    @Test
    fun exactJodanRaySelectsJodanWithNearZeroError() {
        val calculator = StraightPunchTargetCalculator()
        val body = testBodyReference()

        // Nose at (0.45, 0.18), mouth at (0.45, 0.20)
        // Default chin projection: mouth + (mouth - nose) * 1.10 = 0.20 + 0.02 * 1.10 = 0.222
        // Target y ≈ 0.222. Shoulder y = 0.3.
        // h_T = 0.3 - 0.222 = +0.078 (above shoulder).
        // Reach R = 0.40.
        // x_forward = sqrt(0.40^2 - 0.078^2) = sqrt(0.16 - 0.006084) ≈ 0.3923.
        val idealFist = Point3(0.5f - 0.3923f, 0.222f, 0f)
        val elbow = Point3(0.5f - 0.20f, 0.26f, 0f)
        val frame = buildFrame(fist = idealFist, elbow = elbow)

        val result = calculator.evaluate(frame, body, ActiveArm.LEFT)

        assertEquals(TargetRayState.VALID, result.state)
        assertEquals(PunchHeightTargetType.JODAN, result.closestTarget)
        assertEquals(TargetId.JODAN_CHIN, result.closestConcreteTargetId)
        assertNotNull(result.targetAngleErrorDeg)
        assertTrue("Jodan error should be < 1.0 deg, was ${result.targetAngleErrorDeg}", abs(result.targetAngleErrorDeg!!) < 1.0f)
    }

    @Test
    fun betweenTargetsSelectsSmallerAbsoluteAngularError() {
        val calculator = StraightPunchTargetCalculator()
        val body = testBodyReference()

        // Place punch between Chudan (-0.18 height offset) and Jodan (+0.078 height offset).
        // Chūdan ideal angle is ~ -26.7°. Jōdan ideal angle is ~ +11.2°.
        // Halfway is ~ -7.75°.
        // Place punch at -2.0° (closer to Jōdan than Chūdan).
        val fist = Point3(0.5f - 0.39f, 0.28f, 0f) // slightly above shoulder (y=0.28 < 0.30)
        val elbow = Point3(0.5f - 0.20f, 0.29f, 0f)
        val frame = buildFrame(fist = fist, elbow = elbow)

        val result = calculator.evaluate(frame, body, ActiveArm.LEFT)

        assertEquals(TargetRayState.VALID, result.state)
        assertEquals(PunchHeightTargetType.JODAN, result.closestTarget)
        assertNotNull(result.classificationMarginDeg)
        assertTrue("Margin should be positive", result.classificationMarginDeg!! > 0f)
    }

    @Test
    fun signedDirectionConventionVerified() {
        val calculator = StraightPunchTargetCalculator()
        val body = testBodyReference()

        // Chūdan ideal target is at y = 0.48.
        // Punch A is slightly higher (y = 0.46 < 0.48, so higher in screen and along u_up).
        val highFist = Point3(0.5f - 0.36f, 0.46f, 0f)
        val elbowA = Point3(0.5f - 0.18f, 0.38f, 0f)
        val frameHigh = buildFrame(fist = highFist, elbow = elbowA)

        val resultHigh = calculator.evaluate(frameHigh, body, ActiveArm.LEFT)
        val chudanErrorHigh = resultHigh.targetResults[PunchHeightTargetType.CHUDAN]?.errorDeg
        assertNotNull(chudanErrorHigh)
        assertTrue("Punch higher than Chūdan ray should have positive error, was $chudanErrorHigh", chudanErrorHigh!! > 0f)

        // Punch B is slightly lower (y = 0.52 > 0.48, so lower along u_up).
        val lowFist = Point3(0.5f - 0.36f, 0.52f, 0f)
        val elbowB = Point3(0.5f - 0.18f, 0.41f, 0f)
        val frameLow = buildFrame(fist = lowFist, elbow = elbowB)

        val resultLow = calculator.evaluate(frameLow, body, ActiveArm.LEFT)
        val chudanErrorLow = resultLow.targetResults[PunchHeightTargetType.CHUDAN]?.errorDeg
        assertNotNull(chudanErrorLow)
        assertTrue("Punch lower than Chūdan ray should have negative error, was $chudanErrorLow", chudanErrorLow!! < 0f)
    }

    @Test
    fun bentArmReachPreservesTargetDirectionWithoutInflatingAngularError() {
        val calculator = StraightPunchTargetCalculator()
        val body = testBodyReference()

        // Extended Chūdan punch:
        // Full extension: elbow at (0.32, 0.39), fist at (0.1428, 0.48).
        val fullFist = Point3(0.5f - 0.3572f, 0.48f, 0f)
        val fullElbow = Point3(0.5f - 0.18f, 0.39f, 0f)
        val fullFrame = buildFrame(fist = fullFist, elbow = fullElbow)
        val fullResult = calculator.evaluate(fullFrame, body, ActiveArm.LEFT)

        // Bent Chūdan punch along EXACT SAME direction ray from shoulder (0.5, 0.3):
        // Direction vector is (-0.3572, 0.18). Length is 0.40.
        // Scale by 0.75 (reach = 0.30):
        // fist = (0.5 - 0.75 * 0.3572, 0.3 + 0.75 * 0.18) = (0.2321, 0.435)
        // With triangle sides upperArm = 0.20, forearm = 0.20, chord = 0.30,
        // elbow = (0.4255, 0.4856). Both segment lengths are 0.20, so R = 0.40 is preserved!
        val bentFist = Point3(0.5f - 0.75f * 0.3572f, 0.3f + 0.75f * 0.18f, 0f)
        val bentElbow = Point3(0.4255f, 0.4856f, 0f)
        val bentFrame = buildFrame(fist = bentFist, elbow = bentElbow)
        val bentResult = calculator.evaluate(bentFrame, body, ActiveArm.LEFT)

        assertEquals(PunchHeightTargetType.CHUDAN, fullResult.closestTarget)
        assertEquals(PunchHeightTargetType.CHUDAN, bentResult.closestTarget)

        // The angular errors should be virtually identical because direction is preserved!
        val fullError = fullResult.targetAngleErrorDeg!!
        val bentError = bentResult.targetAngleErrorDeg!!
        assertTrue(
            "Angular error should remain consistent despite arm flexion. Full: $fullError, Bent: $bentError",
            abs(fullError - bentError) < 1.0f
        )
    }

    @Test
    fun unreachableTargetOutsideCircleProducesUnreachableStateWithoutCrashing() {
        val calculator = StraightPunchTargetCalculator(explicitGedanTarget = TargetId.GEDAN_LOWER_ABDOMEN)
        // Create an extremely short torso / reach where Gedan is far beyond arm reach R
        val body = testBodyReference()
        // Reach R = 0.10 (very short arm, upper=0.05, fore=0.05)
        // But Gedan height offset is 0.80 * 0.40 = 0.32 > 0.10!
        val fist = Point3(0.42f, 0.35f, 0f)
        val elbow = Point3(0.46f, 0.32f, 0f)
        val frame = buildFrame(fist = fist, elbow = elbow)

        val result = calculator.evaluate(frame, body, ActiveArm.LEFT)

        val gedanResult = result.targetResults[PunchHeightTargetType.GEDAN]
        assertNotNull(gedanResult)
        assertEquals(TargetRayState.UNREACHABLE, gedanResult?.state)
        assertNull(gedanResult?.idealAngleDeg)
    }

    @Test
    fun missingLandmarksProduceStructuredAbstention() {
        val calculator = StraightPunchTargetCalculator()
        val body = testBodyReference()

        val fist = Point3(0.2f, 0.45f, 0f)
        val frameMissingElbow = buildFrame(fist = fist, missingLandmark = PoseLandmarkId.LEFT_ELBOW)

        val result = calculator.evaluate(frameMissingElbow, body, ActiveArm.LEFT)
        assertEquals(TargetRayState.ABSTAINED, result.state)
        assertEquals("missing_required_arm_landmarks", result.reason)
    }

    @Test
    fun allThreeTargetsEvaluatedEveryTime() {
        val calculator = StraightPunchTargetCalculator()
        val body = testBodyReference()

        val fist = Point3(0.1428f, 0.48f, 0f)
        val elbow = Point3(0.32f, 0.39f, 0f)
        val frame = buildFrame(fist = fist, elbow = elbow)

        val result = calculator.evaluate(frame, body, ActiveArm.LEFT)

        assertEquals(3, result.targetResults.size)
        assertTrue(result.targetResults.containsKey(PunchHeightTargetType.JODAN))
        assertTrue(result.targetResults.containsKey(PunchHeightTargetType.CHUDAN))
        assertTrue(result.targetResults.containsKey(PunchHeightTargetType.GEDAN))
    }

    @Test
    fun aspectRatioCorrectionProducesEquivalentAnglesAcrossDimensions() {
        // Physical coordinates in meters (athlete facing left, punching Chūdan)
        // Torso length = 0.60 m. Shoulder at (0.80, 0.50), Hip at (0.80, 1.10).
        // Chūdan height offset hT = -0.45 * 0.60 = -0.27 m.
        // Arm reach R = 0.60 m. xForward = sqrt(0.60^2 - 0.27^2) = sqrt(0.2871) ≈ 0.5358 m.
        // Fist at (0.80 - 0.5358, 0.77).
        val physShoulder = Point3(0.80f, 0.50f, 0f)
        val physHip = Point3(0.80f, 1.10f, 0f)
        val physFist = Point3(0.80f - 0.5358f, 0.77f, 0f)
        val physElbow = Point3(0.80f - 0.27f, 0.63f, 0f)

        // 1. Portrait camera (1080 x 1920)
        val wPort = 1080f
        val hPort = 1920f
        val aspectPort = wPort / hPort
        fun toPort(p: Point3) = Point3(p.x / wPort * 1000f, p.y / hPort * 1000f, 0f)

        val portShoulder = toPort(physShoulder)
        val portHip = toPort(physHip)
        val portFist = toPort(physFist)
        val portElbow = toPort(physElbow)

        val bodyPort = testBodyReference(shoulder = portShoulder, hip = portHip)
        val framePort = buildFrame(fist = portFist, elbow = portElbow, shoulder = portShoulder, hip = portHip, includeFingers = false)
        val calcPort = StraightPunchTargetCalculator(aspectRatio = aspectPort)
        val resultPort = calcPort.evaluate(framePort, bodyPort, ActiveArm.LEFT)

        // 2. Landscape camera (1920 x 1080)
        val wLand = 1920f
        val hLand = 1080f
        val aspectLand = wLand / hLand
        fun toLand(p: Point3) = Point3(p.x / wLand * 1000f, p.y / hLand * 1000f, 0f)

        val landShoulder = toLand(physShoulder)
        val landHip = toLand(physHip)
        val landFist = toLand(physFist)
        val landElbow = toLand(physElbow)

        val bodyLand = testBodyReference(shoulder = landShoulder, hip = landHip)
        val frameLand = buildFrame(fist = landFist, elbow = landElbow, shoulder = landShoulder, hip = landHip, includeFingers = false)
        val calcLand = StraightPunchTargetCalculator(aspectRatio = aspectLand)
        val resultLand = calcLand.evaluate(frameLand, bodyLand, ActiveArm.LEFT)

        assertEquals(PunchHeightTargetType.CHUDAN, resultPort.closestTarget)
        assertEquals(PunchHeightTargetType.CHUDAN, resultLand.closestTarget)

        assertNotNull(resultPort.actualAngleDeg)
        assertNotNull(resultLand.actualAngleDeg)
        assertTrue(
            "Actual angle in portrait (${resultPort.actualAngleDeg}) should match landscape (${resultLand.actualAngleDeg})",
            abs(resultPort.actualAngleDeg!! - resultLand.actualAngleDeg!!) < 0.1f,
        )

        val idealPort = resultPort.targetResults[PunchHeightTargetType.CHUDAN]?.idealAngleDeg
        val idealLand = resultLand.targetResults[PunchHeightTargetType.CHUDAN]?.idealAngleDeg
        assertNotNull(idealPort)
        assertNotNull(idealLand)
        assertTrue(
            "Ideal angle in portrait ($idealPort) should match landscape ($idealLand)",
            abs(idealPort!! - idealLand!!) < 0.1f,
        )

        assertNotNull(resultPort.targetAngleErrorDeg)
        assertNotNull(resultLand.targetAngleErrorDeg)
        assertTrue(
            "Target angle error in portrait (${resultPort.targetAngleErrorDeg}) should match landscape (${resultLand.targetAngleErrorDeg})",
            abs(resultPort.targetAngleErrorDeg!! - resultLand.targetAngleErrorDeg!!) < 0.1f,
        )
    }

    @Test
    fun bodyTranslationPreservesTargetClassificationAndAngularError() {
        val calculator = StraightPunchTargetCalculator()
        val body = testBodyReference()

        val baseFist = Point3(0.5f - 0.3572f, 0.48f, 0f)
        val baseElbow = Point3(0.5f - 0.18f, 0.39f, 0f)
        val baseFrame = buildFrame(fist = baseFist, elbow = baseElbow)
        val baseResult = calculator.evaluate(baseFrame, body, ActiveArm.LEFT)

        // Translate the entire practitioner by (+0.15, -0.08) in normalized coordinates
        val dx = 0.15f
        val dy = -0.08f
        val shiftedFist = Point3(baseFist.x + dx, baseFist.y + dy, 0f)
        val shiftedElbow = Point3(baseElbow.x + dx, baseElbow.y + dy, 0f)
        val shiftedShoulder = Point3(0.5f + dx, 0.3f + dy, 0f)
        val shiftedFrame = buildFrame(fist = shiftedFist, elbow = shiftedElbow, shoulder = shiftedShoulder)

        val translatedResult = calculator.evaluate(shiftedFrame, body, ActiveArm.LEFT)

        assertEquals(baseResult.closestTarget, translatedResult.closestTarget)
        assertEquals(baseResult.closestConcreteTargetId, translatedResult.closestConcreteTargetId)
        assertNotNull(baseResult.targetAngleErrorDeg)
        assertNotNull(translatedResult.targetAngleErrorDeg)
        assertTrue(
            "Target angle error should remain unchanged under body translation (base=${baseResult.targetAngleErrorDeg}, translated=${translatedResult.targetAngleErrorDeg})",
            abs(baseResult.targetAngleErrorDeg!! - translatedResult.targetAngleErrorDeg!!) < 0.05f,
        )
    }

    @Test
    fun instantaneousTorsoLeanAtImpactDoesNotRotateLockedTargetAxis() {
        val calculator = StraightPunchTargetCalculator()
        val body = testBodyReference() // locked neutral vertical axis along down (0, 1)

        val idealFist = Point3(0.5f - 0.3572f, 0.48f, 0f)
        val elbow = Point3(0.5f - 0.18f, 0.39f, 0f)
        // Impact frame where athlete leans forward (hip shifts backward/forward)
        val leaningFrame = buildFrame(fist = idealFist, elbow = elbow)
        // Lean hip by +0.10 in X in the impact frame
        val mutatedLandmarks = leaningFrame.landmarks.toMutableMap()
        mutatedLandmarks[PoseLandmarkId.LEFT_HIP] = landmarkSample(0.60f, 0.70f, 0f)
        val frameWithLean = PoseFrame(leaningFrame.timestampMs, mutatedLandmarks)

        val resultBaseline = calculator.evaluate(leaningFrame, body, ActiveArm.LEFT)
        val resultWithLean = calculator.evaluate(frameWithLean, body, ActiveArm.LEFT)

        // With locked BodyReference, the neutral vertical axis remains upright; Chūdan ideal ray does not tilt
        assertEquals(PunchHeightTargetType.CHUDAN, resultWithLean.closestTarget)
        val chudanRayBaseline = resultBaseline.targetResults[PunchHeightTargetType.CHUDAN]
        val chudanRayWithLean = resultWithLean.targetResults[PunchHeightTargetType.CHUDAN]
        assertNotNull(chudanRayBaseline?.idealAngleDeg)
        assertNotNull(chudanRayWithLean?.idealAngleDeg)
        assertEquals(chudanRayBaseline!!.idealAngleDeg, chudanRayWithLean!!.idealAngleDeg)
        assertEquals(resultBaseline.actualAngleDeg, resultWithLean.actualAngleDeg)
    }

    @Test
    fun stableArmReachOverridesNoisyImpactFrameReach() {
        val calculator = StraightPunchTargetCalculator()
        val body = testBodyReference()

        val idealFist = Point3(0.5f - 0.3572f, 0.48f, 0f)
        // Noisy impact frame where elbow is misplaced, giving distorted single-frame reach
        val noisyElbow = Point3(0.40f, 0.33f, 0f)
        val noisyFrame = buildFrame(fist = idealFist, elbow = noisyElbow)

        // When stable reach R = 0.40f is provided, ideal endpoint should use R = 0.40f
        val result = calculator.evaluate(
            frame = noisyFrame,
            bodyReference = body,
            activeArm = ActiveArm.LEFT,
            stableArmReachRadius = 0.40f,
            stableArmReachProvenance = StraightPunchTargetEvaluation.MULTI_FRAME_REACH_PROVENANCE,
        )

        assertEquals(0.40f, result.armReachRadius)
        assertEquals(StraightPunchTargetEvaluation.MULTI_FRAME_REACH_PROVENANCE, result.armReachProvenance)
        val chudanRay = result.targetResults[PunchHeightTargetType.CHUDAN]
        assertNotNull(chudanRay?.idealEndpoint)
        // Expected endpoint with S=(0.5, 0.3), R=0.40, hT=-0.18 is (0.5 - 0.3572, 0.48) = (0.1428, 0.48)
        assertTrue(abs(chudanRay!!.idealEndpoint!!.x - 0.1428f) < 0.01f)
        assertTrue(abs(chudanRay.idealEndpoint!!.y - 0.48f) < 0.01f)
    }

    @Test
    fun fixedHipsWithRaisedStrikingShoulderLeavesTransportedChudanTargetHeightUnchanged() {
        val calculator = StraightPunchTargetCalculator()
        val body = testBodyReference() // shoulder at (0.5, 0.3), hip at (0.5, 0.7)

        val neutralFist = Point3(0.5f - 0.3572f, 0.48f, 0f)
        val baselineFrame = buildFrame(
            fist = neutralFist,
            shoulder = Point3(0.5f, 0.3f, 0f),
            hip = Point3(0.5f, 0.7f, 0f),
        )

        // Raised striking shoulder: y = 0.25 (up 0.05 from 0.30), but hips remain fixed at y = 0.70
        val raisedShoulderFrame = buildFrame(
            fist = neutralFist,
            shoulder = Point3(0.5f, 0.25f, 0f),
            hip = Point3(0.5f, 0.7f, 0f),
        )

        val baselineEval = calculator.evaluate(baselineFrame, body, ActiveArm.LEFT)
        val raisedEval = calculator.evaluate(raisedShoulderFrame, body, ActiveArm.LEFT)

        val baselineChudan = baselineEval.targetResults[PunchHeightTargetType.CHUDAN]!!
        val raisedChudan = raisedEval.targetResults[PunchHeightTargetType.CHUDAN]!!

        // The transported Chūdan anatomical target height must be completely unchanged!
        assertEquals(baselineChudan.targetPoint!!.y, raisedChudan.targetPoint!!.y, 1e-4f)

        // But because the shoulder origin is higher (y = 0.25 vs 0.30), the ideal ray angles downward more
        assertTrue(raisedChudan.idealAngleDeg!! < baselineChudan.idealAngleDeg!!)
    }

    @Test
    fun missingHipLandmarksAbstainsFromTargetEvaluation() {
        val calculator = StraightPunchTargetCalculator()
        val body = testBodyReference()

        val frame = buildFrame(fist = Point3(0.14f, 0.48f, 0f))
        // Remove both hips
        val missingHipsFrame = PoseFrame(
            frame.timestampMs,
            frame.landmarks.filterKeys { it != PoseLandmarkId.LEFT_HIP && it != PoseLandmarkId.RIGHT_HIP }
        )

        val eval = calculator.evaluate(missingHipsFrame, body, ActiveArm.LEFT)
        assertEquals(TargetRayState.ABSTAINED, eval.state)
        assertEquals("missing_body_origin_landmarks", eval.reason)
    }

    @Test
    fun bodyTranslationPreservesJodanTargetIdealAngle() {
        val calculator = StraightPunchTargetCalculator()
        val body = testBodyReference()

        val idealFist = Point3(0.5f - 0.3923f, 0.222f, 0f)
        val elbow = Point3(0.5f - 0.20f, 0.26f, 0f)
        val baseFrame = buildFrame(fist = idealFist, elbow = elbow)

        val baseEval = calculator.evaluate(baseFrame, body, ActiveArm.LEFT)
        val baseJodan = baseEval.targetResults[PunchHeightTargetType.JODAN]!!
        assertEquals(TargetRayState.VALID, baseJodan.state)
        val baseIdealAngle = baseJodan.idealAngleDeg!!

        // Translate the whole practitioner by (+0.15, +0.10) in normalized space
        val dx = 0.15f
        val dy = 0.10f
        val shiftedFrame = buildFrame(
            fist = Point3(idealFist.x + dx, idealFist.y + dy, 0f),
            elbow = Point3(elbow.x + dx, elbow.y + dy, 0f),
            shoulder = Point3(0.5f + dx, 0.3f + dy, 0f),
            hip = Point3(0.5f + dx, 0.7f + dy, 0f),
            nose = Point3(0.45f + dx, 0.18f + dy, 0f),
            mouth = Point3(0.45f + dx, 0.20f + dy, 0f),
        )

        val shiftedEval = calculator.evaluate(shiftedFrame, body, ActiveArm.LEFT)
        val shiftedJodan = shiftedEval.targetResults[PunchHeightTargetType.JODAN]!!

        assertEquals(TargetRayState.VALID, shiftedJodan.state)
        // Jōdan anatomical target must be translated by exactly dy (+0.10), NOT +0.20!
        assertEquals(baseJodan.targetPoint!!.y + dy, shiftedJodan.targetPoint!!.y, 1e-4f)
        assertEquals(baseJodan.targetPoint!!.x + dx, shiftedJodan.targetPoint!!.x, 1e-4f)

        // The ideal ray angle from the striking shoulder must be identical under whole-body translation
        assertEquals(baseIdealAngle, shiftedJodan.idealAngleDeg!!, 0.1f)
    }

    @Test
    fun lowConfidenceHipsAbstainsWithMissingBodyOrigin() {
        val calculator = StraightPunchTargetCalculator()
        val body = testBodyReference()

        val baseFrame = buildFrame(fist = Point3(0.14f, 0.48f, 0f))
        val lowConfLandmarks = baseFrame.landmarks.toMutableMap()
        // Set both hips to 0.10f confidence (below minimum 0.55f)
        lowConfLandmarks[PoseLandmarkId.LEFT_HIP] = landmarkSample(0.5f, 0.7f, confidence = 0.10f)
        lowConfLandmarks[PoseLandmarkId.RIGHT_HIP] = landmarkSample(0.5f, 0.7f, confidence = 0.10f)
        val frame = PoseFrame(baseFrame.timestampMs, lowConfLandmarks)

        val eval = calculator.evaluate(frame, body, ActiveArm.LEFT)
        assertEquals(TargetRayState.ABSTAINED, eval.state)
        assertEquals("missing_body_origin_landmarks", eval.reason)
    }
}
