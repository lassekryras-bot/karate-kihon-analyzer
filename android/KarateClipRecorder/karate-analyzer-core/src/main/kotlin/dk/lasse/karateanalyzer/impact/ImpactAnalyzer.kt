package dk.lasse.karateanalyzer.impact

import dk.lasse.karateanalyzer.capture.LateralSide
import dk.lasse.karateanalyzer.capture.qom.ExtremityPointComposer
import dk.lasse.karateanalyzer.capture.qom.MotionBodyProfile
import dk.lasse.karateanalyzer.core.PoseFrame
import dk.lasse.karateanalyzer.core.PoseLandmarkId
import dk.lasse.karateanalyzer.geometry.AspectCorrectPoint
import dk.lasse.karateanalyzer.geometry.FrameGeometryMath
import dk.lasse.karateanalyzer.geometry.LandmarkAnchors
import dk.lasse.karateanalyzer.geometry.SourceNormalizedPoint
import dk.lasse.karateanalyzer.motion.CausalCoordinateMotionFilter
import kotlin.math.ceil
import kotlin.math.sqrt

/** Offline terminal-event analyzer for one known, pre-segmented movement. */
object ImpactAnalyzer {
    const val ANALYZER_VERSION = "impact_analyzer_v1"

    private data class ObservedSample(
        val timestampUs: Long,
        val weaponPoint: SourceNormalizedPoint,
        val weaponConfidence: Double,
        val proximalAngleDeg: Double,
        val jointAngleDeg: Double,
    )

    private data class CalculatedSample(
        val observed: ObservedSample,
        val filteredWeaponPoint: SourceNormalizedPoint,
        val effectiveStep: Double,
        val accumulatedTravel: Double,
        val weaponSpeed: Double,
        val proximalDelta: Double,
        val jointDelta: Double,
        val proximalAngularVelocity: Double,
        val jointAngularVelocity: Double,
        val angularSpeed: Double,
        var travelProgress: Double = 0.0,
        var proximalProgress: Double = 0.0,
        var jointProgress: Double = 0.0,
        var articulationProgress: Double = 0.0,
        var motionEnvelope: Double = 0.0,
        var motionFall: Double = 0.0,
        var progressGate: Double = 0.0,
        var postTransitionStability: Double = 0.0,
        var transitionScore: Double = 0.0,
        var stableState: Boolean = false,
    )

    fun analyze(input: ImpactMovementInput): ImpactAnalysisResult {
        val provenance = provenance(input)
        fun abstain(
            reason: ImpactAbstentionReason,
            usableSamples: Int = 0,
            debug: List<ImpactDebugSample> = emptyList(),
            linearScale: Double? = null,
            angularScale: Double? = null,
            fallScale: Double? = null,
        ) = ImpactAnalysisResult(
            status = ImpactAnalysisStatus.ABSTAINED,
            movementId = input.movementId,
            weaponId = input.profile.weapon,
            side = input.profile.side,
            limbFamily = input.profile.limbFamily,
            viewProfile = input.profile.approvedViewProfile,
            quality = quality(input, usableSamples, linearScale, angularScale, fallScale),
            provenance = provenance,
            debugEvidence = debug,
            abstentionReason = reason,
        )

        if (!input.canonicalGeometry.isAvailable) return abstain(ImpactAbstentionReason.FRAME_GEOMETRY_UNAVAILABLE)
        val bodyScale = input.bodyScale?.takeIf(BodyScaleEvidence::isUsable)
            ?: return abstain(ImpactAbstentionReason.BODY_SCALE_UNAVAILABLE)
        if (!profileIsSupported(input.profile)) return abstain(ImpactAbstentionReason.UNSUPPORTED_ANALYSIS_PROFILE)
        if (input.logicalEndTimestampUs <= input.logicalStartTimestampUs ||
            input.evidenceEndTimestampUs <= input.evidenceStartTimestampUs ||
            input.evidenceStartTimestampUs > input.logicalStartTimestampUs ||
            input.evidenceEndTimestampUs < input.logicalEndTimestampUs ||
            input.frames.isEmpty()
        ) {
            return abstain(ImpactAbstentionReason.INVALID_TIMESTAMPS)
        }
        if (input.frames.zipWithNext().any { (a, b) -> b.timestampMs <= a.timestampMs }) {
            return abstain(ImpactAbstentionReason.INVALID_TIMESTAMPS)
        }

        val frameGeometry = input.canonicalGeometry.toFrameGeometry()
        val boundedFrames = input.frames.filter {
            it.timestampMs * 1000L in input.evidenceStartTimestampUs..input.evidenceEndTimestampUs
        }
        if (boundedFrames.size < 3) return abstain(ImpactAbstentionReason.TRACKING_QUALITY_INSUFFICIENT)

        var weaponAvailableCount = 0
        var limbAvailableCount = 0
        val observations = boundedFrames.mapNotNull { frame ->
            val weapon = weaponPoint(frame, input.profile)
            if (weapon != null) weaponAvailableCount++
            val articulation = articulation(frame, input.profile, frameGeometry)
            if (articulation != null) limbAvailableCount++
            if (weapon == null || articulation == null) return@mapNotNull null
            ObservedSample(
                timestampUs = frame.timestampMs * 1000L,
                weaponPoint = weapon.first,
                weaponConfidence = weapon.second,
                proximalAngleDeg = articulation.proximalAngleDeg,
                jointAngleDeg = articulation.jointAngleDeg,
            )
        }
        if (weaponAvailableCount == 0) return abstain(ImpactAbstentionReason.WEAPON_TRACK_UNAVAILABLE)
        if (limbAvailableCount == 0) return abstain(ImpactAbstentionReason.LIMB_GEOMETRY_UNAVAILABLE)
        val coverage = observations.size.toDouble() / boundedFrames.size.toDouble()
        if (observations.size < 3 || coverage < input.profile.minimumTrackingCoverage) {
            return abstain(ImpactAbstentionReason.TRACKING_QUALITY_INSUFFICIENT, observations.size)
        }

        val spatialDeadband = input.profile.spatialDeadbandBodyHeightRatio * bodyScale.bodyHeightAspectCorrect
        val filter = CausalCoordinateMotionFilter(
            coordinateCount = 2,
            medianSamples = input.profile.positionMedianSamples,
            meanSamples = input.profile.positionMeanSamples,
        )
        var accumulatedTravel = 0.0
        val calculated = observations.mapIndexed { index, sample ->
            val analysisPoint = FrameGeometryMath.sourceToAspectCorrect(sample.weaponPoint, frameGeometry)
            val filtered = filter.accept(
                sample.timestampUs,
                listOf(analysisPoint.x.toDouble(), analysisPoint.y.toDouble()),
                sample.weaponConfidence,
            )
            val previous = filtered.previousCoordinates
            val rawStep = if (previous == null) 0.0 else FrameGeometryMath.distance(
                AspectCorrectPoint(previous[0].toFloat(), previous[1].toFloat()),
                AspectCorrectPoint(filtered.coordinates[0].toFloat(), filtered.coordinates[1].toFloat()),
            ).toDouble()
            val effectiveStep = if (rawStep < spatialDeadband) 0.0 else rawStep
            accumulatedTravel += effectiveStep
            val dt = filtered.deltaSeconds?.takeIf { it > 0.0 }
            val previousObserved = observations.getOrNull(index - 1)
            val proximalDelta = previousObserved?.let {
                FrameGeometryMath.wrappedAngleDifferenceDeg(sample.proximalAngleDeg, it.proximalAngleDeg)
            } ?: 0.0
            val jointDelta = previousObserved?.let {
                FrameGeometryMath.wrappedAngleDifferenceDeg(sample.jointAngleDeg, it.jointAngleDeg)
            } ?: 0.0
            val effectiveProximalDelta = if (kotlin.math.abs(proximalDelta) < input.profile.angularDeadbandDegPerSample) 0.0 else proximalDelta
            val effectiveJointDelta = if (kotlin.math.abs(jointDelta) < input.profile.angularDeadbandDegPerSample) 0.0 else jointDelta
            val proximalVelocity = if (dt == null) 0.0 else kotlin.math.abs(effectiveProximalDelta) / dt
            val jointVelocity = if (dt == null) 0.0 else kotlin.math.abs(effectiveJointDelta) / dt
            CalculatedSample(
                observed = sample,
                filteredWeaponPoint = FrameGeometryMath.aspectCorrectToSource(
                    AspectCorrectPoint(filtered.coordinates[0].toFloat(), filtered.coordinates[1].toFloat()),
                    frameGeometry,
                ),
                effectiveStep = effectiveStep,
                accumulatedTravel = accumulatedTravel,
                weaponSpeed = if (dt == null) 0.0 else effectiveStep / dt,
                proximalDelta = effectiveProximalDelta,
                jointDelta = effectiveJointDelta,
                proximalAngularVelocity = proximalVelocity,
                jointAngularVelocity = jointVelocity,
                angularSpeed = sqrt(proximalVelocity * proximalVelocity + jointVelocity * jointVelocity),
            )
        }

        val totalTravel = calculated.last().accumulatedTravel
        if (totalTravel <= spatialDeadband) {
            return abstain(ImpactAbstentionReason.INSUFFICIENT_MEASURABLE_MOTION, calculated.size)
        }

        val startProximal = calculated.first().observed.proximalAngleDeg
        val startJoint = calculated.first().observed.jointAngleDeg
        val proximalExcursions = calculated.map {
            FrameGeometryMath.wrappedAngleDifferenceDeg(it.observed.proximalAngleDeg, startProximal)
        }
        val jointExcursions = calculated.map {
            FrameGeometryMath.wrappedAngleDifferenceDeg(it.observed.jointAngleDeg, startJoint)
        }
        val proximalExtreme = proximalExcursions.maxByOrNull { kotlin.math.abs(it) } ?: 0.0
        val jointExtreme = jointExcursions.maxByOrNull { kotlin.math.abs(it) } ?: 0.0
        if (maxOf(kotlin.math.abs(proximalExtreme), kotlin.math.abs(jointExtreme)) < input.profile.angularDeadbandDegPerSample) {
            return abstain(ImpactAbstentionReason.INSUFFICIENT_ARTICULATION_CHANGE, calculated.size)
        }

        calculated.forEachIndexed { index, sample ->
            sample.travelProgress = (sample.accumulatedTravel / totalTravel).coerceIn(0.0, 1.0)
            sample.proximalProgress = directionalProgress(proximalExcursions[index], proximalExtreme)
            sample.jointProgress = directionalProgress(jointExcursions[index], jointExtreme)
            sample.articulationProgress = ((sample.proximalProgress + sample.jointProgress) * 0.5).coerceIn(0.0, 1.0)
            sample.progressGate = sqrt(sample.travelProgress * sample.articulationProgress)
            sample.stableState = sample.effectiveStep == 0.0 && sample.proximalDelta == 0.0 && sample.jointDelta == 0.0
        }

        val linearScale = percentile(calculated.map { it.weaponSpeed }.filter { it > 0.0 }, input.profile.robustPercentile)
        val angularScale = percentile(calculated.map { it.angularSpeed }.filter { it > 0.0 }, input.profile.robustPercentile)
        if (linearScale == null || angularScale == null) {
            return abstain(ImpactAbstentionReason.INSUFFICIENT_MEASURABLE_MOTION, calculated.size)
        }
        calculated.forEach { sample ->
            val linearMotion = (sample.weaponSpeed / linearScale).coerceIn(0.0, 1.0)
            val angularMotion = (sample.angularSpeed / angularScale).coerceIn(0.0, 1.0)
            sample.motionEnvelope = 1.0 - (1.0 - linearMotion) * (1.0 - angularMotion)
        }
        calculated.forEachIndexed { index, sample ->
            if (index == 0) return@forEachIndexed
            val dt = (sample.observed.timestampUs - calculated[index - 1].observed.timestampUs) / 1_000_000.0
            sample.motionFall = if (dt > 0.0) {
                ((calculated[index - 1].motionEnvelope - sample.motionEnvelope) / dt).coerceAtLeast(0.0)
            } else 0.0
        }
        val fallScale = percentile(calculated.map { it.motionFall }.filter { it > 0.0 }, input.profile.robustPercentile)
            ?: return abstain(
                ImpactAbstentionReason.NO_POST_PEAK_TERMINAL_TRANSITION,
                calculated.size,
                linearScale = linearScale,
                angularScale = angularScale,
            )

        val qomPeak = input.qomTimeline
            .asSequence()
            .filter { it.timestampMs * 1000L in input.logicalStartTimestampUs..input.logicalEndTimestampUs }
            .maxWithOrNull(compareBy<dk.lasse.karateanalyzer.capture.qom.QomFrameEvidence> { it.rollingArea }.thenBy { -it.timestampMs })
        val searchStartUs = qomPeak?.timestampMs?.times(1000L) ?: input.logicalStartTimestampUs
        calculated.forEachIndexed { index, sample ->
            sample.postTransitionStability = postTransitionStability(calculated, index, input.profile.postTransitionConfirmationUs)
            val normalizedFall = (sample.motionFall / fallScale).coerceIn(0.0, 1.0)
            sample.transitionScore = sample.progressGate * normalizedFall * sample.postTransitionStability
        }

        val transitionIndex = calculated.indices
            .filter { calculated[it].observed.timestampUs >= searchStartUs }
            .maxWithOrNull(compareBy<Int> { calculated[it].transitionScore }.thenBy { -calculated[it].observed.timestampUs })
            ?.takeIf { calculated[it].transitionScore >= input.profile.minimumTransitionScore }

        val qomByTimestamp = input.qomTimeline.associateBy { it.timestampMs * 1000L }
        fun debugEvidence(): List<ImpactDebugSample> = calculated.map { sample ->
            ImpactDebugSample(
                timestampUs = sample.observed.timestampUs,
                weaponPoint = sample.filteredWeaponPoint,
                weaponConfidence = sample.observed.weaponConfidence,
                accumulatedTravel = sample.accumulatedTravel,
                travelProgress = sample.travelProgress,
                weaponSpeed = sample.weaponSpeed,
                proximalAngleDeg = sample.observed.proximalAngleDeg,
                jointAngleDeg = sample.observed.jointAngleDeg,
                proximalProgress = sample.proximalProgress,
                jointProgress = sample.jointProgress,
                additiveArticulationProgress = sample.proximalProgress + sample.jointProgress,
                articulationProgress = sample.articulationProgress,
                angularSpeedDegPerSec = sample.angularSpeed,
                motionEnvelope = sample.motionEnvelope,
                motionFallPerSec = sample.motionFall,
                progressGate = sample.progressGate,
                postTransitionStability = sample.postTransitionStability,
                terminalTransitionScore = sample.transitionScore,
                stableState = sample.stableState,
                qomRollingArea = qomByTimestamp[sample.observed.timestampUs]?.rollingArea,
            )
        }

        if (transitionIndex == null) {
            return abstain(
                ImpactAbstentionReason.NO_POST_PEAK_TERMINAL_TRANSITION,
                calculated.size,
                debugEvidence(),
                linearScale,
                angularScale,
                fallScale,
            )
        }
        val stableRange = stableRange(calculated, transitionIndex, input.profile.stableMinimumDurationUs)
            ?: return abstain(
                ImpactAbstentionReason.NO_STABLE_TERMINAL_WINDOW,
                calculated.size,
                debugEvidence(),
                linearScale,
                angularScale,
                fallScale,
            )
        val representativeIndex = representativeIndex(calculated, stableRange, input.profile.representativeQualityImprovement)
        val transition = calculated[transitionIndex]
        val representative = calculated[representativeIndex]
        val qomAtTransition = input.qomTimeline.minByOrNull {
            kotlin.math.abs(it.timestampMs * 1000L - transition.observed.timestampUs)
        }

        return ImpactAnalysisResult(
            status = ImpactAnalysisStatus.COMPLETED,
            movementId = input.movementId,
            weaponId = input.profile.weapon,
            side = input.profile.side,
            limbFamily = input.profile.limbFamily,
            viewProfile = input.profile.approvedViewProfile,
            terminalTransitionTimestampUs = transition.observed.timestampUs,
            terminalTransitionEstimateTimestampUs = null,
            selectedObservedTimestampUs = transition.observed.timestampUs,
            stableWindowStartTimestampUs = calculated[stableRange.first].observed.timestampUs,
            stableWindowEndTimestampUs = calculated[stableRange.last].observed.timestampUs,
            stableRepresentativeTimestampUs = representative.observed.timestampUs,
            stableRepresentativeWeaponPoint = representative.observed.weaponPoint,
            stableRepresentativeArticulation = ImpactArticulationState(
                representative.observed.proximalAngleDeg,
                representative.observed.jointAngleDeg,
            ),
            travelProgressAtTransition = transition.travelProgress,
            articulationProgressAtTransition = transition.articulationProgress,
            selectedWeaponSpeedAtTransition = transition.weaponSpeed,
            angularSpeedAtTransition = transition.angularSpeed,
            terminalTransitionScore = transition.transitionScore,
            qomPeakTimestampUs = qomPeak?.timestampMs?.times(1000L),
            qomPeakRollingArea = qomPeak?.rollingArea,
            qomRollingAreaAtTransition = qomAtTransition?.rollingArea,
            confidence = transition.transitionScore.coerceIn(0.0, 1.0),
            quality = quality(input, calculated.size, linearScale, angularScale, fallScale),
            provenance = provenance,
            debugEvidence = debugEvidence(),
        )
    }

    private fun profileIsSupported(profile: ImpactAnalysisProfile): Boolean = when (profile.limbFamily) {
        ImpactLimbFamily.UPPER_LIMB -> profile.weapon in setOf(WeaponPointDefinition.COMPOSITE_HAND, WeaponPointDefinition.WRIST)
        ImpactLimbFamily.LOWER_LIMB -> profile.weapon in setOf(WeaponPointDefinition.KNEE, WeaponPointDefinition.COMPOSITE_FOOT)
    }

    private fun weaponPoint(frame: PoseFrame, profile: ImpactAnalysisProfile): Pair<SourceNormalizedPoint, Double>? {
        val ids = when (profile.side) {
            LateralSide.LEFT -> when (profile.weapon) {
                WeaponPointDefinition.COMPOSITE_HAND -> MotionBodyProfile.PUNCH_HAND_LANDMARKS_LEFT
                WeaponPointDefinition.WRIST -> listOf(PoseLandmarkId.LEFT_WRIST)
                WeaponPointDefinition.KNEE -> listOf(PoseLandmarkId.LEFT_KNEE)
                WeaponPointDefinition.COMPOSITE_FOOT -> MotionBodyProfile.KICK_FOOT_LANDMARKS_LEFT
            }
            LateralSide.RIGHT -> when (profile.weapon) {
                WeaponPointDefinition.COMPOSITE_HAND -> MotionBodyProfile.PUNCH_HAND_LANDMARKS_RIGHT
                WeaponPointDefinition.WRIST -> listOf(PoseLandmarkId.RIGHT_WRIST)
                WeaponPointDefinition.KNEE -> listOf(PoseLandmarkId.RIGHT_KNEE)
                WeaponPointDefinition.COMPOSITE_FOOT -> MotionBodyProfile.KICK_FOOT_LANDMARKS_RIGHT
            }
        }
        val composite = ExtremityPointComposer.compositeSourcePoint(frame, ids) ?: return null
        if (composite.confidence < profile.minimumLandmarkConfidence) return null
        return composite.point to composite.confidence
    }

    private fun articulation(
        frame: PoseFrame,
        profile: ImpactAnalysisProfile,
        frameGeometry: dk.lasse.karateanalyzer.geometry.FrameGeometry,
    ): ImpactArticulationState? {
        val torso = LandmarkAnchors.extractTorsoAnchors(frame, profile.minimumLandmarkConfidence.toFloat())
        val shoulderCenter = torso.shoulderCenter ?: return null
        val hipCenter = torso.hipCenter ?: return null
        val bodyUp = FrameGeometryMath.sourceToAspectCorrect(shoulderCenter, frameGeometry) -
            FrameGeometryMath.sourceToAspectCorrect(hipCenter, frameGeometry)

        val (aId, bId, cId) = when (profile.limbFamily to profile.side) {
            ImpactLimbFamily.UPPER_LIMB to LateralSide.LEFT -> Triple(PoseLandmarkId.LEFT_SHOULDER, PoseLandmarkId.LEFT_ELBOW, PoseLandmarkId.LEFT_WRIST)
            ImpactLimbFamily.UPPER_LIMB to LateralSide.RIGHT -> Triple(PoseLandmarkId.RIGHT_SHOULDER, PoseLandmarkId.RIGHT_ELBOW, PoseLandmarkId.RIGHT_WRIST)
            ImpactLimbFamily.LOWER_LIMB to LateralSide.LEFT -> Triple(PoseLandmarkId.LEFT_HIP, PoseLandmarkId.LEFT_KNEE, PoseLandmarkId.LEFT_ANKLE)
            ImpactLimbFamily.LOWER_LIMB to LateralSide.RIGHT -> Triple(PoseLandmarkId.RIGHT_HIP, PoseLandmarkId.RIGHT_KNEE, PoseLandmarkId.RIGHT_ANKLE)
            else -> return null
        }
        fun point(id: PoseLandmarkId): SourceNormalizedPoint? {
            val sample = frame.landmarks[id]?.takeIf { it.isObserved(profile.minimumLandmarkConfidence.toFloat()) } ?: return null
            val p = sample.position ?: return null
            return SourceNormalizedPoint(p.x, p.y)
        }
        val a = FrameGeometryMath.sourceToAspectCorrect(point(aId) ?: return null, frameGeometry)
        val b = FrameGeometryMath.sourceToAspectCorrect(point(bId) ?: return null, frameGeometry)
        val c = FrameGeometryMath.sourceToAspectCorrect(point(cId) ?: return null, frameGeometry)
        val proximal = b - a
        val distal = c - b
        return runCatching {
            ImpactArticulationState(
                proximalAngleDeg = FrameGeometryMath.signedAngleDeg(bodyUp, proximal).toDouble(),
                jointAngleDeg = FrameGeometryMath.absoluteAngleDeg(proximal, distal).toDouble(),
            )
        }.getOrNull()
    }

    private fun directionalProgress(excursion: Double, extreme: Double): Double {
        if (kotlin.math.abs(extreme) <= 1e-9) return 0.0
        val direction = if (extreme >= 0.0) 1.0 else -1.0
        return (excursion * direction / kotlin.math.abs(extreme)).coerceIn(0.0, 1.0)
    }

    private fun percentile(values: List<Double>, percentile: Double): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val index = (ceil(percentile * sorted.size).toInt() - 1).coerceIn(sorted.indices)
        return sorted[index].takeIf { it > 1e-12 }
    }

    private fun postTransitionStability(samples: List<CalculatedSample>, index: Int, durationUs: Long): Double {
        val startUs = samples[index].observed.timestampUs
        val confirmation = mutableListOf<CalculatedSample>()
        for (candidate in samples.drop(index + 1)) {
            confirmation += candidate
            if (candidate.observed.timestampUs - startUs >= durationUs) break
        }
        if (confirmation.isEmpty() || confirmation.last().observed.timestampUs - startUs < durationUs) return 0.0
        return confirmation.map { 1.0 - it.motionEnvelope }.average().coerceIn(0.0, 1.0)
    }

    private fun stableRange(samples: List<CalculatedSample>, transitionIndex: Int, durationUs: Long): IntRange? {
        var index = transitionIndex + 1
        while (index < samples.size) {
            if (!samples[index].stableState) {
                index++
                continue
            }
            val start = index
            while (index + 1 < samples.size && samples[index + 1].stableState) index++
            val end = index
            if (samples[end].observed.timestampUs - samples[start].observed.timestampUs >= durationUs) {
                return start..end
            }
            index++
        }
        return null
    }

    private fun representativeIndex(samples: List<CalculatedSample>, range: IntRange, improvement: Double): Int {
        val onsetQuality = samples[range.first].observed.weaponConfidence
        val bestQuality = range.maxOf { samples[it].observed.weaponConfidence }
        if (bestQuality < onsetQuality + improvement) return range.first
        return range.first { samples[it].observed.weaponConfidence == bestQuality }
    }

    private fun quality(
        input: ImpactMovementInput,
        usableSamples: Int,
        linearScale: Double? = null,
        angularScale: Double? = null,
        fallScale: Double? = null,
    ): ImpactQualityDiagnostics {
        val supplied = input.frames.count { it.timestampMs * 1000L in input.evidenceStartTimestampUs..input.evidenceEndTimestampUs }
        return ImpactQualityDiagnostics(
            suppliedFrameCount = supplied,
            usableSampleCount = usableSamples,
            trackingCoverage = if (supplied == 0) 0.0 else usableSamples.toDouble() / supplied.toDouble(),
            robustLinearSpeedScale = linearScale,
            robustAngularSpeedScale = angularScale,
            robustMotionFallScale = fallScale,
        )
    }

    private fun provenance(input: ImpactMovementInput) = ImpactAnalysisProvenance(
        landmarkTrackId = input.landmarkTrackId,
        recordingId = input.canonicalGeometry.recordingId,
        frameGeometryId = input.canonicalGeometry.geometryId,
        frameGeometryContractVersion = input.canonicalGeometry.contractVersion,
        sourceHash = input.canonicalGeometry.sourceHash,
        trackHash = input.canonicalGeometry.trackHash,
        segmenterVersion = input.segmenterVersion,
        bodyScaleSourceId = input.bodyScale?.sourceId,
        bodyScaleSourceVersion = input.bodyScale?.sourceVersion,
        analyzerVersion = ANALYZER_VERSION,
        configurationVersion = input.profile.configVersion,
        calibration = ImpactCalibrationProvenance(
            spatialDeadbandBodyHeightRatio = input.profile.spatialDeadbandBodyHeightRatio,
            angularDeadbandDegPerSample = input.profile.angularDeadbandDegPerSample,
            postTransitionConfirmationUs = input.profile.postTransitionConfirmationUs,
            stableMinimumDurationUs = input.profile.stableMinimumDurationUs,
            robustPercentile = input.profile.robustPercentile,
            minimumTransitionScore = input.profile.minimumTransitionScore,
            minimumLandmarkConfidence = input.profile.minimumLandmarkConfidence,
            minimumTrackingCoverage = input.profile.minimumTrackingCoverage,
            representativeQualityImprovement = input.profile.representativeQualityImprovement,
            positionMedianSamples = input.profile.positionMedianSamples,
            positionMeanSamples = input.profile.positionMeanSamples,
        ),
        movementLogicalStartTimestampUs = input.logicalStartTimestampUs,
        movementLogicalEndTimestampUs = input.logicalEndTimestampUs,
        evidenceStartTimestampUs = input.evidenceStartTimestampUs,
        evidenceEndTimestampUs = input.evidenceEndTimestampUs,
    )
}
