package dk.lasse.karatecliprecorder.training

import dk.lasse.karateanalyzer.capture.qom.QomFrameEvidence
import dk.lasse.karateanalyzer.core.*
import dk.lasse.karateanalyzer.geometry.CanonicalGeometryDescriptor
import dk.lasse.karateanalyzer.impact.*
import dk.lasse.karateanalyzer.motion.*
import org.json.JSONArray
import org.json.JSONObject

data class MovementMotionOutput(
    val analysis: MovementAnalysis,
    val limbs: FourLimbMotionResult,
    val side: ActivitySideResolution?,
    val impact: ImpactAnalysisResult?,
)

/** Product adapter: all motion mathematics stays in the shared analytical core. */
object MovementMotionAnalysis {
    val policy = AnalyzerPolicy("four_limb_activity_motion", listOf("1"))

    fun analyze(
        movement: SessionMovement, trackId: String, frames: List<PoseFrame>, geometry: CanonicalGeometryDescriptor,
        context: ActivitySideContext?, qom: List<QomFrameEvidence>,
        limbs: FourLimbMotionResult = characterize(movement, trackId, frames, geometry),
        sequence: RepetitionSideValidation? = null,
        bodyScale: BodyScaleEvidence? = null,
        calibration: dk.lasse.karateanalyzer.geometry.ImageBodyScaleCalibration? = null,
    ): MovementMotionOutput {
        val side = context?.let { ActivityStartingSideResolver.resolve(limbs, it) }
        val resolvedSide = side?.side
        val impact = if (resolvedSide != null) {
            val punch = context!!.activity == LimbInterpretationActivity.STRAIGHT_PUNCH
            ImpactAnalyzer.analyze(ImpactMovementInput(
                movementId = movement.movementId, logicalStartTimestampUs = movement.startUs, logicalEndTimestampUs = movement.endUs,
                // Keep logical, evidence and playback intervals distinct. No new padding heuristic.
                evidenceStartTimestampUs = movement.startUs, evidenceEndTimestampUs = movement.endUs,
                frames = frames, qomTimeline = qom, segmenterVersion = movement.segmentationVersion,
                landmarkTrackId = trackId, canonicalGeometry = geometry, bodyScale = bodyScale,
                profile = ImpactAnalysisProfile(context.planId, resolvedSide,
                    if (punch) WeaponPointDefinition.COMPOSITE_HAND else WeaponPointDefinition.COMPOSITE_FOOT,
                    if (punch) ImpactLimbFamily.UPPER_LIMB else ImpactLimbFamily.LOWER_LIMB,
                    approvedViewProfile = "operator_selected_provisional"),
                limbEvidence = limbs,
            ))
        } else null
        val reason = impact?.abstentionReason?.name ?: if (impact?.status == ImpactAnalysisStatus.COMPLETED) null
            else if (context == null) "ACTIVITY_PLAN_UNAVAILABLE" else "SIDE_AMBIGUOUS"
        val payload = encode(movement, limbs, side, sequence, impact, qom, reason, bodyScale, calibration)
        val analysis = MovementAnalysis(movementId = movement.movementId, analyzerKey = policy.analyzerKey,
            analyzerVersion = "1", landmarkTrackId = trackId,
            state = if (limbs.profiles.values.any { it.metrics != null }) AnalysisState.PARTIAL else AnalysisState.ABSTAINED,
            reason = reason ?: "Provisional descriptive motion evidence", geometryJson = payload)
        return MovementMotionOutput(analysis, limbs, side, impact)
    }

    fun characterize(movement: SessionMovement, trackId: String, frames: List<PoseFrame>, geometry: CanonicalGeometryDescriptor) =
        FourLimbMotionCharacterizer.characterize(FourLimbMotionInput(movement.movementId, trackId,
            movement.startUs, movement.endUs, frames, geometry, segmenterVersion = movement.segmentationVersion))

    private fun encode(movement: SessionMovement, limbs: FourLimbMotionResult, side: ActivitySideResolution?,
                       sequence: RepetitionSideValidation?, impact: ImpactAnalysisResult?, qom: List<QomFrameEvidence>, reason: String?, bodyScale: BodyScaleEvidence?,
                       calibration: dk.lasse.karateanalyzer.geometry.ImageBodyScaleCalibration?): String {
        fun obj(vararg values: Pair<String, Any?>) = JSONObject().apply { values.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) } }
        val profiles = JSONArray()
        limbs.profiles.values.forEach { p ->
            val m = p.metrics
            profiles.put(obj("limb" to p.limbId.name, "joint" to p.limbId.joint.name, "pattern" to p.motionPattern.name,
                "coverage" to p.validSampleCoverage, "minimumConfidence" to p.minimumObservedConfidence,
                "abstention" to p.abstentionReason?.name, "qualityFlags" to JSONArray(p.qualityFlags.map { it.name }),
                "startAngleDeg" to m?.referenceStartAngleDeg, "endAngleDeg" to m?.referenceEndAngleDeg,
                "netDeg" to m?.netAngleChangeDeg, "travelDeg" to m?.meaningfulAngularTravelDeg,
                "openingDeg" to m?.positiveAngularTravelDeg, "closingDeg" to m?.negativeAngularTravelDeg,
                "minimumAngleDeg" to m?.minimumAngleDeg, "maximumAngleDeg" to m?.maximumAngleDeg,
                "excursionDeg" to m?.maxExcursionDeg, "excursionUs" to m?.maxExcursionTimestampUs,
                "onsetUs" to m?.motionStartTimestampUs, "peakSpeedDegPerSec" to m?.peakAngularSpeedDegPerSec,
                "peakUs" to m?.peakAngularSpeedTimestampUs, "settlingUs" to m?.motionEndTimestampUs,
                "settlingConfirmedUs" to m?.settlingConfirmedAtTimestampUs,
                "movingDurationUs" to m?.movingDurationUs, "stableDurationUs" to m?.stableDurationUs,
                "unknownDurationUs" to m?.unknownDurationUs,
                "reversals" to JSONArray(m?.reversals.orEmpty().map { obj("timeUs" to it.timestampUs,
                    "angleDeg" to it.angleDeg, "before" to it.beforeDirection, "after" to it.afterDirection, "magnitudeDeg" to it.magnitudeDeg) }),
                "samples" to JSONArray(p.samples.map { obj("timeUs" to it.timestampUs, "rawAngleDeg" to it.rawAngleDeg,
                    "angleDeg" to it.angleDeg, "confidence" to it.confidence, "block" to it.blockId,
                    "deltaDeg" to it.meaningfulDeltaDeg, "speedDegPerSec" to it.angularSpeedDegPerSec) })))
        }
        return obj("contract" to "movement-motion-v1", "movementId" to movement.movementId,
            "trackId" to limbs.landmarkTrackId, "geometryId" to limbs.canonicalGeometry.geometryId,
            "geometry" to dk.lasse.karateanalyzer.geometry.CanonicalGeometryCodec.encode(limbs.canonicalGeometry),
            "logicalStartUs" to movement.startUs, "logicalEndUs" to movement.endUs,
            "playbackStartUs" to movement.playbackStartUs, "playbackEndUs" to movement.playbackEndUs,
            "analyzerVersion" to limbs.analyzerVersion, "angularPolicyVersion" to limbs.angularPolicyVersion,
            "config" to obj("version" to limbs.config.version, "deadbandDeg" to limbs.config.angularDeadbandDeg,
                "minimumConfidence" to limbs.config.minimumConfidence, "minimumCoverage" to limbs.config.minimumCoverage,
                "maximumGapUs" to limbs.config.maximumGapUs, "onsetConfirmationUs" to limbs.config.onsetConfirmationUs,
                "quietConfirmationUs" to limbs.config.quietConfirmationUs, "medianSamples" to limbs.config.medianSamples,
                "meanSamples" to limbs.config.meanSamples, "dominantTravelRatio" to limbs.config.dominantTravelRatio),
            "planId" to side?.context?.planId, "planVersion" to side?.context?.planVersion,
            "activity" to side?.context?.activity?.name, "targetId" to side?.context?.targetId,
            "alternating" to side?.context?.alternating, "side" to side?.side?.name,
            "sideReason" to (side?.reason?.name ?: "ACTIVITY_PLAN_UNAVAILABLE"),
            "sideConfig" to side?.config?.toString(), "sideResolverVersion" to side?.resolverVersion,
            "repetitionIndex" to sequence?.repetitionIndex, "expectedSide" to sequence?.expectedSide?.name,
            "alternationStatus" to (sequence?.alternationStatus?.name ?: "UNVERIFIABLE"),
            "profiles" to profiles, "impactStatus" to (impact?.status?.name ?: "NOT_EVALUATED"),
            "impactReason" to reason, "weapon" to impact?.weaponId?.name,
            "terminalUs" to impact?.terminalTransitionTimestampUs, "stableStartUs" to impact?.stableWindowStartTimestampUs,
            "stableEndUs" to impact?.stableWindowEndTimestampUs, "representativeUs" to impact?.stableRepresentativeTimestampUs,
            "impactProvenance" to impact?.provenance?.toString(),
            "calibration" to calibration?.let { JSONObject(ImageBodyScaleStore.encode(it)) },
            "bodyScale" to bodyScale?.toString(),
            "impactSamples" to JSONArray(impact?.debugEvidence.orEmpty().map { obj("timeUs" to it.timestampUs,
                "weaponX" to it.weaponPoint.x, "weaponY" to it.weaponPoint.y, "speed" to it.weaponSpeed,
                "motionEnvelope" to it.motionEnvelope, "transitionScore" to it.terminalTransitionScore,
                "stable" to it.stableState) }),
            "qom" to JSONArray(qom.filter { it.timestampMs * 1000 in movement.startUs..movement.endUs }
                .map { obj("timeUs" to it.timestampMs * 1000, "rollingArea" to it.rollingArea) })).toString()
    }
}
