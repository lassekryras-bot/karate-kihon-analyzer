package dk.lasse.karatecliprecorder.training

import dk.lasse.karateanalyzer.core.*
import dk.lasse.karateanalyzer.impact.ImpactAnalysisResult
import dk.lasse.karateanalyzer.impact.ImpactAnalysisStatus

object StraightPunchMovementAdapter {
    val policy = AnalyzerPolicy("straight_punch_target", listOf("1"))

    fun analyze(
        movement: SessionMovement,
        trackId: String,
        frames: List<PoseFrame>,
        explicitGedanTarget: TargetId? = null,
        videoWidth: Int? = null,
        videoHeight: Int? = null,
        impactResult: ImpactAnalysisResult? = null,
    ): Pair<MovementAnalysis, List<MeasurementResult>> {
        val impactAbstentionReason = when {
            impactResult == null -> null
            impactResult.status != ImpactAnalysisStatus.COMPLETED ->
                "impact_analysis_${impactResult.abstentionReason?.name ?: "abstained"}"
            impactResult.stableRepresentativeTimestampUs == null ->
                "impact_analysis_representative_unavailable"
            else -> null
        }
        if (impactAbstentionReason != null) {
            val analysis = MovementAnalysis(
                movementId = movement.movementId,
                analyzerKey = policy.analyzerKey,
                analyzerVersion = "1",
                landmarkTrackId = trackId,
                state = AnalysisState.ABSTAINED,
                reason = impactAbstentionReason,
            )
            return analysis to emptyList()
        }
        val impactRepresentativeUs = impactResult?.stableRepresentativeTimestampUs
        val resolved = CanonicalAnalysisFrameSelector.resolve(
            movement.startUs,
            movement.endUs,
            impactRepresentativeUs ?: movement.analysisFrameUs,
            frames,
        )
        val canonicalResult = resolved?.first
        val frame = resolved?.second
        if (canonicalResult == null || frame == null) {
            val analysis = MovementAnalysis(
                movementId = movement.movementId,
                analyzerKey = policy.analyzerKey,
                analyzerVersion = "1",
                landmarkTrackId = trackId,
                state = AnalysisState.ABSTAINED,
                reason = "canonical_analysis_frame_unavailable",
            )
            return analysis to emptyList()
        }

        val punchHeightAnalyzer = PunchHeightAnalyzer(explicitGedanTarget)
        val multiplier = PunchHeightAnalyzer.DEFAULT_CHIN_PROJECTION_MULTIPLIER
        var setupReady = false
        var initialized = false
        frames.asSequence().filter { it.timestampMs <= frame.timestampMs }.forEach { f ->
            if (!setupReady) setupReady = punchHeightAnalyzer.processSetup(f).usable
            else if (!initialized) initialized = punchHeightAnalyzer.processBodyInitialization(f, multiplier).bodyReference != null
        }
        val bodyReference = punchHeightAnalyzer.currentBodyReference()
        if (bodyReference == null) {
            val analysis = MovementAnalysis(
                movementId = movement.movementId,
                analyzerKey = policy.analyzerKey,
                analyzerVersion = "1",
                landmarkTrackId = trackId,
                state = AnalysisState.ABSTAINED,
                reason = "neutral_body_reference_unavailable",
            )
            return analysis to emptyList()
        }

        var activeArm = ActiveArm.NONE
        val targetEval = punchHeightAnalyzer.evaluateTarget(PunchHeightTargetType.CHUDAN, frame, multiplier)
        if (targetEval?.activeArm != null && targetEval.activeArm != ActiveArm.NONE) {
            activeArm = targetEval.activeArm
        }
        if (activeArm == ActiveArm.NONE) {
            activeArm = selectActiveArm(frame)
        }

        val aspectRatio = if (videoWidth != null && videoHeight != null && videoHeight > 0) {
            videoWidth.toFloat() / videoHeight.toFloat()
        } else {
            1.0f
        }

        val stableArmReach = computeStableArmReach(
            frames = frames,
            startUs = movement.startUs,
            impactUs = canonicalResult.timestampUs,
            activeArm = activeArm,
            aspectRatio = aspectRatio,
        )

        val calculator = StraightPunchTargetCalculator(
            explicitGedanTarget = explicitGedanTarget,
            aspectRatio = aspectRatio,
        )
        val eval = calculator.evaluate(
            frame = frame,
            bodyReference = bodyReference,
            activeArm = activeArm,
            chinProjectionMultiplier = multiplier,
            explicitGedanTarget = explicitGedanTarget,
            stableArmReachRadius = stableArmReach,
            stableArmReachProvenance = if (stableArmReach != null) StraightPunchTargetEvaluation.MULTI_FRAME_REACH_PROVENANCE else null,
        )

        val analysisState = when (eval.state) {
            TargetRayState.VALID -> AnalysisState.COMPLETED
            TargetRayState.ABSTAINED -> AnalysisState.ABSTAINED
            TargetRayState.FAILED -> AnalysisState.FAILED
            TargetRayState.UNREACHABLE -> AnalysisState.PARTIAL
        }
        val geometryJson = StraightPunchGeometryCodec.encode(eval, canonicalResult.frameIndex)

        val analysis = MovementAnalysis(
            movementId = movement.movementId,
            analyzerKey = policy.analyzerKey,
            analyzerVersion = "1",
            landmarkTrackId = trackId,
            state = analysisState,
            reason = eval.reason ?: "closest_target=${eval.closestTarget?.name};concrete=${eval.closestConcreteTargetId?.name};margin=${eval.classificationMarginDeg};frameIndex=${canonicalResult.frameIndex};strategy=${canonicalResult.strategy}",
            geometryJson = geometryJson,
        )

        val side = when (eval.activeArm) {
            ActiveArm.LEFT -> BodySide.LEFT
            ActiveArm.RIGHT -> BodySide.RIGHT
            else -> BodySide.UNKNOWN
        }
        val occurrenceUs = canonicalResult.timestampUs
        val frameIndex = canonicalResult.frameIndex
        val confidence = bodyReference.confidence.toDouble()
        val results = mutableListOf<MeasurementResult>()

        val closestTarget = eval.closestTarget
        if (closestTarget != null) {
            results.add(
                MeasurementResult(
                    analysisId = analysis.analysisId,
                    measurementKey = TrainingMeasurements.PUNCH_CLOSEST_TARGET,
                    calculationVersion = "1",
                    valueType = ValueType.CATEGORICAL,
                    categoricalValue = closestTarget.name,
                    side = side,
                    role = "strike",
                    state = ResultState.VALID,
                    reason = eval.closestConcreteTargetId?.name ?: "observed_closest_target",
                    confidence = confidence,
                    occurrenceUs = occurrenceUs,
                    frameIndex = frameIndex,
                )
            )
        } else {
            results.add(
                MeasurementResult(
                    analysisId = analysis.analysisId,
                    measurementKey = TrainingMeasurements.PUNCH_CLOSEST_TARGET,
                    calculationVersion = "1",
                    valueType = ValueType.CATEGORICAL,
                    categoricalValue = null,
                    side = side,
                    role = "strike",
                    state = ResultState.ABSTAINED,
                    reason = eval.reason ?: "closest_target_unavailable",
                    occurrenceUs = occurrenceUs,
                    frameIndex = frameIndex,
                )
            )
        }

        eval.targetAngleErrorDeg?.let { errorDeg ->
            results.add(
                MeasurementResult(
                    analysisId = analysis.analysisId,
                    measurementKey = TrainingMeasurements.PUNCH_TARGET_ANGLE_ERROR_DEG,
                    calculationVersion = "1",
                    valueType = ValueType.NUMERIC,
                    numericValue = errorDeg.toDouble(),
                    side = side,
                    role = "strike",
                    state = ResultState.VALID,
                    reason = "target_angle_error_to_closest",
                    confidence = confidence,
                    occurrenceUs = occurrenceUs,
                    frameIndex = frameIndex,
                )
            )
        }

        eval.targetResults[PunchHeightTargetType.JODAN]?.errorDeg?.let { err ->
            results.add(
                MeasurementResult(
                    analysisId = analysis.analysisId,
                    measurementKey = TrainingMeasurements.PUNCH_JODAN_TARGET_ANGLE_ERROR_DEG,
                    calculationVersion = "1",
                    valueType = ValueType.NUMERIC,
                    numericValue = err.toDouble(),
                    side = side,
                    role = "strike",
                    state = ResultState.VALID,
                    confidence = confidence,
                    occurrenceUs = occurrenceUs,
                    frameIndex = frameIndex,
                )
            )
        }

        eval.targetResults[PunchHeightTargetType.CHUDAN]?.errorDeg?.let { err ->
            results.add(
                MeasurementResult(
                    analysisId = analysis.analysisId,
                    measurementKey = TrainingMeasurements.PUNCH_CHUDAN_TARGET_ANGLE_ERROR_DEG,
                    calculationVersion = "1",
                    valueType = ValueType.NUMERIC,
                    numericValue = err.toDouble(),
                    side = side,
                    role = "strike",
                    state = ResultState.VALID,
                    confidence = confidence,
                    occurrenceUs = occurrenceUs,
                    frameIndex = frameIndex,
                )
            )
        }

        val gedanEval = eval.targetResults[PunchHeightTargetType.GEDAN]
        val gedanError = gedanEval?.errorDeg
        if (gedanEval != null && gedanEval.state == TargetRayState.VALID && gedanError != null) {
            results.add(
                MeasurementResult(
                    analysisId = analysis.analysisId,
                    measurementKey = TrainingMeasurements.PUNCH_GEDAN_TARGET_ANGLE_ERROR_DEG,
                    calculationVersion = "1",
                    valueType = ValueType.NUMERIC,
                    numericValue = gedanError.toDouble(),
                    side = side,
                    role = "strike",
                    state = ResultState.VALID,
                    reason = gedanEval.concreteTargetId?.name ?: "gedan_target",
                    confidence = confidence,
                    occurrenceUs = occurrenceUs,
                    frameIndex = frameIndex,
                )
            )
        } else {
            results.add(
                MeasurementResult(
                    analysisId = analysis.analysisId,
                    measurementKey = TrainingMeasurements.PUNCH_GEDAN_TARGET_ANGLE_ERROR_DEG,
                    calculationVersion = "1",
                    valueType = ValueType.NUMERIC,
                    numericValue = null,
                    side = side,
                    role = "strike",
                    state = ResultState.ABSTAINED,
                    reason = gedanEval?.reason ?: "provisional_gedan_target_unspecified",
                    occurrenceUs = occurrenceUs,
                    frameIndex = frameIndex,
                )
            )
        }

        eval.classificationMarginDeg?.let { marginDeg ->
            results.add(
                MeasurementResult(
                    analysisId = analysis.analysisId,
                    measurementKey = TrainingMeasurements.PUNCH_TARGET_CLASSIFICATION_MARGIN_DEG,
                    calculationVersion = "1",
                    valueType = ValueType.NUMERIC,
                    numericValue = marginDeg.toDouble(),
                    side = side,
                    role = "strike",
                    state = ResultState.VALID,
                    reason = "margin_between_best_and_second_best",
                    confidence = confidence,
                    occurrenceUs = occurrenceUs,
                    frameIndex = frameIndex,
                )
            )
        }

        eval.elbowAngleDeg?.let { elbowAngle ->
            results.add(
                MeasurementResult(
                    analysisId = analysis.analysisId,
                    measurementKey = TrainingMeasurements.PUNCH_ELBOW_ANGLE,
                    calculationVersion = "1",
                    valueType = ValueType.NUMERIC,
                    numericValue = elbowAngle.toDouble(),
                    side = side,
                    role = "strike",
                    state = ResultState.VALID,
                    confidence = confidence,
                    occurrenceUs = occurrenceUs,
                    frameIndex = frameIndex,
                )
            )
        }

        return analysis to results
    }

    private fun estimateBodyReference(frame: PoseFrame): BodyReference? {
        val ls = frame.landmarks[PoseLandmarkId.LEFT_SHOULDER]?.takeIf { it.isObserved() }?.position ?: return null
        val rs = frame.landmarks[PoseLandmarkId.RIGHT_SHOULDER]?.takeIf { it.isObserved() }?.position ?: return null
        val lh = frame.landmarks[PoseLandmarkId.LEFT_HIP]?.takeIf { it.isObserved() }?.position ?: return null
        val rh = frame.landmarks[PoseLandmarkId.RIGHT_HIP]?.takeIf { it.isObserved() }?.position ?: return null
        val shoulder = Point3((ls.x + rs.x) * 0.5f, (ls.y + rs.y) * 0.5f, (ls.z + rs.z) * 0.5f)
        val hip = Point3((lh.x + rh.x) * 0.5f, (lh.y + rh.y) * 0.5f, (lh.z + rh.z) * 0.5f)
        val torsoVector = hip - shoulder
        val torsoLength = kotlin.math.sqrt(torsoVector.x * torsoVector.x + torsoVector.y * torsoVector.y)
        if (torsoLength <= 0f) return null
        val side = if (ls.z < rs.z) VisibleSide.LEFT else VisibleSide.RIGHT
        return BodyReference(
            visibleSide = side,
            shoulderPoint = shoulder,
            hipPoint = hip,
            torsoAxis = torsoVector * (1f / torsoLength),
            torsoLength = torsoLength,
            confidence = 0.8f,
        )
    }

    private fun selectActiveArm(frame: PoseFrame): ActiveArm {
        val ls = frame.landmarks[PoseLandmarkId.LEFT_SHOULDER]?.takeIf { it.isObserved() }?.position
        val lw = frame.landmarks[PoseLandmarkId.LEFT_WRIST]?.takeIf { it.isObserved() }?.position
        val rs = frame.landmarks[PoseLandmarkId.RIGHT_SHOULDER]?.takeIf { it.isObserved() }?.position
        val rw = frame.landmarks[PoseLandmarkId.RIGHT_WRIST]?.takeIf { it.isObserved() }?.position
        val leftReach = if (ls != null && lw != null) {
            val dx = lw.x - ls.x; val dy = lw.y - ls.y; kotlin.math.sqrt(dx * dx + dy * dy)
        } else -1f
        val rightReach = if (rs != null && rw != null) {
            val dx = rw.x - rs.x; val dy = rw.y - rs.y; kotlin.math.sqrt(dx * dx + dy * dy)
        } else -1f
        return when {
            leftReach > rightReach && leftReach > 0f -> ActiveArm.LEFT
            rightReach > leftReach && rightReach > 0f -> ActiveArm.RIGHT
            else -> ActiveArm.NONE
        }
    }

    fun computeStableArmReach(
        frames: List<PoseFrame>,
        startUs: Long,
        impactUs: Long,
        activeArm: ActiveArm,
        aspectRatio: Float,
    ): Float? {
        if (activeArm == ActiveArm.NONE) return null
        val shoulderId = if (activeArm == ActiveArm.LEFT) PoseLandmarkId.LEFT_SHOULDER else PoseLandmarkId.RIGHT_SHOULDER
        val elbowId = if (activeArm == ActiveArm.LEFT) PoseLandmarkId.LEFT_ELBOW else PoseLandmarkId.RIGHT_ELBOW
        val wristId = if (activeArm == ActiveArm.LEFT) PoseLandmarkId.LEFT_WRIST else PoseLandmarkId.RIGHT_WRIST

        val startMs = startUs / 1000L
        val impactMs = impactUs / 1000L

        val upperLengths = mutableListOf<Float>()
        val foreLengths = mutableListOf<Float>()

        frames.asSequence()
            .filter { it.timestampMs in startMs..impactMs }
            .forEach { f ->
                val s = f.landmarks[shoulderId]?.takeIf { it.isObserved() }?.position
                val e = f.landmarks[elbowId]?.takeIf { it.isObserved() }?.position
                val w = f.landmarks[wristId]?.takeIf { it.isObserved() }?.position

                if (s != null && e != null) {
                    val dx = (e.x - s.x) * aspectRatio
                    val dy = e.y - s.y
                    val len = kotlin.math.sqrt(dx * dx + dy * dy)
                    if (len > 0.01f) upperLengths.add(len)
                }
                if (e != null && w != null) {
                    val dx = (w.x - e.x) * aspectRatio
                    val dy = w.y - e.y
                    val len = kotlin.math.sqrt(dx * dx + dy * dy)
                    if (len > 0.01f) foreLengths.add(len)
                }
            }

        if (upperLengths.size < 3 || foreLengths.size < 3) return null

        upperLengths.sort()
        foreLengths.sort()

        fun percentile80(list: List<Float>): Float {
            val idx = ((list.size - 1) * 0.80f).toInt().coerceIn(0, list.size - 1)
            return list[idx]
        }

        val stableUpper = percentile80(upperLengths)
        val stableFore = percentile80(foreLengths)
        val stableReach = stableUpper + stableFore
        return if (stableReach > 0.05f) stableReach else null
    }
}
