package dk.lasse.karatecliprecorder.training

import dk.lasse.karateanalyzer.core.*
import dk.lasse.karateanalyzer.impact.ImpactAnalysisResult
import dk.lasse.karateanalyzer.impact.ImpactAnalysisStatus
import dk.lasse.karateanalyzer.impact.ImpactLimbFamily
import dk.lasse.karateanalyzer.impact.WeaponPointDefinition
import dk.lasse.karateanalyzer.geometry.CanonicalGeometryDescriptor
import dk.lasse.karateanalyzer.capture.LateralSide

object StraightPunchMovementAdapter {
    val policy = AnalyzerPolicy("straight_punch_target", listOf("4", "3", "2", "1"))

    fun analyze(
        movement: SessionMovement,
        trackId: String,
        frames: List<PoseFrame>,
        explicitGedanTarget: TargetId? = null,
        videoWidth: Int? = null,
        videoHeight: Int? = null,
        impactResult: ImpactAnalysisResult? = null,
        resolvedSide: dk.lasse.karateanalyzer.capture.LateralSide? = null,
        canonicalGeometry: CanonicalGeometryDescriptor? = null,
        pairedArmEvidence: StraightPunchPairedArmResult? = null,
        declaredTargetId: String? = null,
    ): Pair<MovementAnalysis, List<MeasurementResult>> {
        if (impactResult != null) return analyzeCurrentFrame(
            movement, trackId, frames, impactResult, resolvedSide, canonicalGeometry, pairedArmEvidence, declaredTargetId
        )
        val resolved = CanonicalAnalysisFrameSelector.resolve(
            movement.startUs,
            movement.endUs,
            movement.analysisFrameUs,
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

        var activeArm = when (resolvedSide) {
            dk.lasse.karateanalyzer.capture.LateralSide.LEFT -> ActiveArm.LEFT
            dk.lasse.karateanalyzer.capture.LateralSide.RIGHT -> ActiveArm.RIGHT
            null -> ActiveArm.NONE
        }
        val targetEval = punchHeightAnalyzer.evaluateTarget(PunchHeightTargetType.CHUDAN, frame, multiplier)
        if (resolvedSide == null && targetEval?.activeArm != null && targetEval.activeArm != ActiveArm.NONE) {
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

    private fun analyzeCurrentFrame(
        movement: SessionMovement,
        trackId: String,
        frames: List<PoseFrame>,
        impact: ImpactAnalysisResult,
        resolvedSide: LateralSide?,
        geometry: CanonicalGeometryDescriptor?,
        pairedArmEvidence: StraightPunchPairedArmResult?,
        declaredTargetId: String?,
    ): Pair<MovementAnalysis, List<MeasurementResult>> {
        fun abstain(reason: String) = MovementAnalysis(
            movementId = movement.movementId,
            analyzerKey = policy.analyzerKey,
            analyzerVersion = "4",
            landmarkTrackId = trackId,
            state = AnalysisState.ABSTAINED,
            reason = reason,
        ) to emptyList<MeasurementResult>()

        if (impact.status != ImpactAnalysisStatus.COMPLETED) {
            return abstain("impact_analysis_${impact.abstentionReason?.name ?: "abstained"}")
        }
        if (impact.movementId != movement.movementId) return abstain("impact_movement_identity_mismatch")
        if (impact.provenance.landmarkTrackId != trackId) return abstain("impact_track_identity_mismatch")
        if (geometry == null || impact.provenance.recordingId != geometry.recordingId ||
            impact.provenance.frameGeometryId != geometry.geometryId ||
            impact.provenance.frameGeometryContractVersion != geometry.contractVersion
        ) return abstain("impact_geometry_identity_mismatch")
        if (resolvedSide != null && resolvedSide != impact.side) return abstain("impact_side_identity_mismatch")
        if (impact.limbFamily != ImpactLimbFamily.UPPER_LIMB || impact.weaponId != WeaponPointDefinition.COMPOSITE_HAND) {
            return abstain("impact_weapon_not_composite_hand")
        }
        val timestampUs = impact.stableRepresentativeTimestampUs ?: return abstain("impact_analysis_representative_unavailable")
        val expectedFist = impact.stableRepresentativeWeaponPoint ?: return abstain("impact_representative_weapon_unavailable")
        if (timestampUs !in movement.startUs..movement.endUs) return abstain("impact_representative_outside_movement")
        val matches = frames.withIndex().filter { it.value.timestampMs * 1000L == timestampUs }
        if (matches.size != 1) return abstain("exact_impact_frame_unavailable")
        val indexed = matches.single()
        val facing = pairedArmEvidence?.forwardAxis?.takeIf { it.status == ForwardAxisStatus.RELIABLE }?.axis?.x?.let { x ->
            when { x < 0f -> ImageFacingDirection.IMAGE_LEFT; x > 0f -> ImageFacingDirection.IMAGE_RIGHT; else -> ImageFacingDirection.UNKNOWN }
        } ?: ImageFacingDirection.UNKNOWN
        val facingSource = if (facing == ImageFacingDirection.UNKNOWN) "unresolved" else
            "${pairedArmEvidence?.analyzerVersion}:${pairedArmEvidence?.forwardAxis?.source ?: "torso_face_normal"}"
        val horizontal = StraightPunchHorizontalTargetAnalyzer.analyze(
            StraightPunchHorizontalTargetInput(
                frame = indexed.value,
                frameIndex = indexed.index.toLong(),
                timestampUs = timestampUs,
                recordingId = geometry.recordingId,
                landmarkTrackId = trackId,
                canonicalGeometry = geometry,
                side = impact.side,
                expectedFist = expectedFist,
                minimumConfidence = impact.provenance.calibration.minimumLandmarkConfidence.toFloat(),
                facing = facing,
                facingSource = facingSource,
            )
        ) ?: return abstain("exact_frame_horizontal_evidence_unavailable_or_identity_mismatch")

        val angular = StraightPunchCurrentFrameAngularAnalyzer.analyze(
            StraightPunchCurrentFrameAngularInput(
                movementStartUs = movement.startUs,
                frame = indexed.value,
                frameIndex = indexed.index.toLong(),
                horizontal = horizontal,
                frames = frames,
                canonicalGeometry = geometry,
                declaredTargetId = declaredTargetId,
                minimumConfidence = impact.provenance.calibration.minimumLandmarkConfidence.toFloat(),
            )
        )

        val availableCount = horizontal.guides.values.count { it.state == HorizontalGuideState.AVAILABLE }
        val analysisState = when (angular.state) {
            CurrentFrameAngularState.RESOLVED -> if (angular.comparisonComplete) AnalysisState.COMPLETED else AnalysisState.PARTIAL
            CurrentFrameAngularState.AMBIGUOUS -> AnalysisState.PARTIAL
            CurrentFrameAngularState.UNAVAILABLE -> AnalysisState.ABSTAINED
        }
        val analysis = MovementAnalysis(
            movementId = movement.movementId,
            analyzerKey = policy.analyzerKey,
            analyzerVersion = "4",
            landmarkTrackId = trackId,
            state = analysisState,
            reason = "angular=${angular.state};selected=${angular.selectedTarget};mode=${angular.selectionMode};" +
                "horizontal=${horizontal.closestState};available=$availableCount/3;${angular.selectionReason}",
            geometryJson = StraightPunchCurrentFrameAngularGeometryCodec.encode(angular, horizontal),
        )
        val side = if (impact.side == LateralSide.LEFT) BodySide.LEFT else BodySide.RIGHT
        val resultState = if (analysisState == AnalysisState.COMPLETED) ResultState.VALID else ResultState.PARTIAL
        val results = mutableListOf<MeasurementResult>()
        results += MeasurementResult(
            analysisId = analysis.analysisId,
            measurementKey = TrainingMeasurements.PUNCH_CURRENT_FRAME_ANGULAR_TARGET,
            calculationVersion = "1",
            valueType = ValueType.CATEGORICAL,
            categoricalValue = angular.selectedTarget?.name ?: angular.declaredTargetRaw,
            side = side,
            role = "strike",
            state = if (angular.selectedTarget == null && angular.declaredTargetRaw == null) ResultState.ABSTAINED else resultState,
            reason = angular.selectionReason,
            confidence = impact.confidence,
            occurrenceUs = timestampUs,
            frameIndex = indexed.index.toLong(),
        )
        results += MeasurementResult(
            analysisId = analysis.analysisId,
            measurementKey = TrainingMeasurements.PUNCH_CURRENT_FRAME_ANGULAR_ERROR_DEG,
            calculationVersion = "1",
            numericValue = angular.signedErrorDeg,
            side = side,
            role = "strike",
            state = if (angular.signedErrorDeg == null) ResultState.ABSTAINED else resultState,
            reason = angular.selectionReason,
            confidence = impact.confidence,
            occurrenceUs = timestampUs,
            frameIndex = indexed.index.toLong(),
        )
        results += MeasurementResult(
            analysisId = analysis.analysisId,
            measurementKey = TrainingMeasurements.PUNCH_HORIZONTAL_CLOSEST_TARGET,
            calculationVersion = "1",
            valueType = ValueType.CATEGORICAL,
            categoricalValue = horizontal.closestTarget?.name,
            side = side,
            role = "strike",
            state = if (horizontal.closestTarget == null) ResultState.PARTIAL else resultState,
            reason = horizontal.closestReason,
            confidence = impact.confidence,
            occurrenceUs = timestampUs,
            frameIndex = indexed.index.toLong(),
        )
        val normalizedKeys = mapOf(
            PunchHeightTargetType.JODAN to TrainingMeasurements.PUNCH_JODAN_HEIGHT_ERROR_NORMALIZED,
            PunchHeightTargetType.CHUDAN to TrainingMeasurements.PUNCH_CHUDAN_HEIGHT_ERROR_NORMALIZED,
            PunchHeightTargetType.GEDAN to TrainingMeasurements.PUNCH_GEDAN_HEIGHT_ERROR_NORMALIZED,
        )
        val pixelKeys = mapOf(
            PunchHeightTargetType.JODAN to TrainingMeasurements.PUNCH_JODAN_HEIGHT_ERROR_CANONICAL_PX,
            PunchHeightTargetType.CHUDAN to TrainingMeasurements.PUNCH_CHUDAN_HEIGHT_ERROR_CANONICAL_PX,
            PunchHeightTargetType.GEDAN to TrainingMeasurements.PUNCH_GEDAN_HEIGHT_ERROR_CANONICAL_PX,
        )
        horizontal.guides.values.forEach { guide ->
            fun measurement(key: String, value: Double?) = MeasurementResult(
                analysisId = analysis.analysisId,
                measurementKey = key,
                calculationVersion = "1",
                numericValue = value,
                side = side,
                role = "strike",
                state = if (value == null) ResultState.ABSTAINED else resultState,
                reason = "${guide.estimatorVersion};${guide.reason}",
                confidence = impact.confidence,
                occurrenceUs = timestampUs,
                frameIndex = indexed.index.toLong(),
            )
            results += measurement(normalizedKeys.getValue(guide.target), guide.signedErrorNormalized)
            results += measurement(pixelKeys.getValue(guide.target), guide.signedErrorCanonicalPx)
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
