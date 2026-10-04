package dk.lasse.karatecliprecorder.training

import dk.lasse.karateanalyzer.capture.LateralSide
import dk.lasse.karateanalyzer.geometry.SourceNormalizedPoint
import dk.lasse.karateanalyzer.impact.*
import org.json.JSONArray
import org.json.JSONObject

/** Explicit product evidence that a known activity may use one approved impact-analysis view profile. */
data class ImpactViewApproval(
    val activityPlanId: String,
    val profileId: String,
    val source: String,
) {
    init {
        require(activityPlanId.isNotBlank())
        require(profileId.isNotBlank())
        require(source.isNotBlank())
    }
}

data class PersistedImpactEvidence(
    val profile: ImpactAnalysisProfile,
    val viewApproval: ImpactViewApproval,
    val result: ImpactAnalysisResult,
)

/** Versioned JSON boundary for retaining the complete authoritative impact decision after process death. */
object ImpactAnalysisEvidenceCodec {
    const val CONTRACT = "impact-analysis-evidence"
    const val VERSION = 1

    fun encode(profile: ImpactAnalysisProfile, approval: ImpactViewApproval, result: ImpactAnalysisResult): JSONObject =
        JSONObject().apply {
            put("contract", CONTRACT)
            put("version", VERSION)
            put("viewApproval", approval.toJson())
            put("profile", profile.toJson())
            put("result", result.toJson())
        }

    fun decode(value: JSONObject): PersistedImpactEvidence {
        require(value.getString("contract") == CONTRACT)
        require(value.getInt("version") == VERSION)
        val approval = value.getJSONObject("viewApproval").toViewApproval()
        val profile = value.getJSONObject("profile").toProfile()
        val result = value.getJSONObject("result").toResult()
        require(approval.activityPlanId == profile.activityProfileId)
        require(approval.profileId == profile.approvedViewProfile)
        require(result.viewProfile == profile.approvedViewProfile)
        require(result.side == profile.side && result.weaponId == profile.weapon && result.limbFamily == profile.limbFamily)
        require(result.provenance.configurationVersion == profile.configVersion)
        return PersistedImpactEvidence(profile, approval, result)
    }

    private fun ImpactViewApproval.toJson() = JSONObject()
        .put("activityPlanId", activityPlanId)
        .put("profileId", profileId)
        .put("source", source)

    private fun JSONObject.toViewApproval() = ImpactViewApproval(
        activityPlanId = getString("activityPlanId"),
        profileId = getString("profileId"),
        source = getString("source"),
    )

    private fun ImpactAnalysisProfile.toJson() = JSONObject()
        .put("activityProfileId", activityProfileId)
        .put("side", side.name)
        .put("weapon", weapon.name)
        .put("limbFamily", limbFamily.name)
        .put("approvedViewProfile", approvedViewProfile)
        .put("configVersion", configVersion)
        .put("spatialDeadbandBodyHeightRatio", spatialDeadbandBodyHeightRatio)
        .put("angularDeadbandDegPerSample", angularDeadbandDegPerSample)
        .put("postTransitionConfirmationUs", postTransitionConfirmationUs)
        .put("stableMinimumDurationUs", stableMinimumDurationUs)
        .put("robustPercentile", robustPercentile)
        .put("minimumTransitionScore", minimumTransitionScore)
        .put("minimumLandmarkConfidence", minimumLandmarkConfidence)
        .put("minimumTrackingCoverage", minimumTrackingCoverage)
        .put("representativeQualityImprovement", representativeQualityImprovement)
        .put("positionMedianSamples", positionMedianSamples)
        .put("positionMeanSamples", positionMeanSamples)

    private fun JSONObject.toProfile() = ImpactAnalysisProfile(
        activityProfileId = getString("activityProfileId"),
        side = LateralSide.valueOf(getString("side")),
        weapon = WeaponPointDefinition.valueOf(getString("weapon")),
        limbFamily = ImpactLimbFamily.valueOf(getString("limbFamily")),
        approvedViewProfile = getString("approvedViewProfile"),
        configVersion = getString("configVersion"),
        spatialDeadbandBodyHeightRatio = getDouble("spatialDeadbandBodyHeightRatio"),
        angularDeadbandDegPerSample = getDouble("angularDeadbandDegPerSample"),
        postTransitionConfirmationUs = getLong("postTransitionConfirmationUs"),
        stableMinimumDurationUs = getLong("stableMinimumDurationUs"),
        robustPercentile = getDouble("robustPercentile"),
        minimumTransitionScore = getDouble("minimumTransitionScore"),
        minimumLandmarkConfidence = getDouble("minimumLandmarkConfidence"),
        minimumTrackingCoverage = getDouble("minimumTrackingCoverage"),
        representativeQualityImprovement = getDouble("representativeQualityImprovement"),
        positionMedianSamples = getInt("positionMedianSamples"),
        positionMeanSamples = getInt("positionMeanSamples"),
    )

    private fun ImpactAnalysisResult.toJson() = JSONObject().apply {
        put("status", status.name)
        put("movementId", movementId)
        put("weaponId", weaponId.name)
        put("side", side.name)
        put("limbFamily", limbFamily.name)
        put("viewProfile", viewProfile)
        nullable("terminalTransitionTimestampUs", terminalTransitionTimestampUs)
        nullable("terminalTransitionEstimateTimestampUs", terminalTransitionEstimateTimestampUs)
        nullable("selectedObservedTimestampUs", selectedObservedTimestampUs)
        nullable("stableWindowStartTimestampUs", stableWindowStartTimestampUs)
        nullable("stableWindowEndTimestampUs", stableWindowEndTimestampUs)
        nullable("stableRepresentativeTimestampUs", stableRepresentativeTimestampUs)
        nullable("stableRepresentativeWeaponPoint", stableRepresentativeWeaponPoint?.toJson())
        nullable("stableRepresentativeArticulation", stableRepresentativeArticulation?.toJson())
        nullable("travelProgressAtTransition", travelProgressAtTransition)
        nullable("articulationProgressAtTransition", articulationProgressAtTransition)
        nullable("selectedWeaponSpeedAtTransition", selectedWeaponSpeedAtTransition)
        nullable("angularSpeedAtTransition", angularSpeedAtTransition)
        nullable("terminalTransitionScore", terminalTransitionScore)
        nullable("qomPeakTimestampUs", qomPeakTimestampUs)
        nullable("qomPeakRollingArea", qomPeakRollingArea)
        nullable("qomRollingAreaAtTransition", qomRollingAreaAtTransition)
        nullable("confidence", confidence)
        put("quality", quality.toJson())
        put("provenance", provenance.toJson())
        put("debugEvidence", JSONArray(debugEvidence.map { it.toJson() }))
        nullable("abstentionReason", abstentionReason?.name)
    }

    private fun JSONObject.toResult() = ImpactAnalysisResult(
        status = ImpactAnalysisStatus.valueOf(getString("status")),
        movementId = getString("movementId"),
        weaponId = WeaponPointDefinition.valueOf(getString("weaponId")),
        side = LateralSide.valueOf(getString("side")),
        limbFamily = ImpactLimbFamily.valueOf(getString("limbFamily")),
        viewProfile = getString("viewProfile"),
        terminalTransitionTimestampUs = nullableLong("terminalTransitionTimestampUs"),
        terminalTransitionEstimateTimestampUs = nullableLong("terminalTransitionEstimateTimestampUs"),
        selectedObservedTimestampUs = nullableLong("selectedObservedTimestampUs"),
        stableWindowStartTimestampUs = nullableLong("stableWindowStartTimestampUs"),
        stableWindowEndTimestampUs = nullableLong("stableWindowEndTimestampUs"),
        stableRepresentativeTimestampUs = nullableLong("stableRepresentativeTimestampUs"),
        stableRepresentativeWeaponPoint = nullableObject("stableRepresentativeWeaponPoint")?.toPoint(),
        stableRepresentativeArticulation = nullableObject("stableRepresentativeArticulation")?.toArticulation(),
        travelProgressAtTransition = nullableDouble("travelProgressAtTransition"),
        articulationProgressAtTransition = nullableDouble("articulationProgressAtTransition"),
        selectedWeaponSpeedAtTransition = nullableDouble("selectedWeaponSpeedAtTransition"),
        angularSpeedAtTransition = nullableDouble("angularSpeedAtTransition"),
        terminalTransitionScore = nullableDouble("terminalTransitionScore"),
        qomPeakTimestampUs = nullableLong("qomPeakTimestampUs"),
        qomPeakRollingArea = nullableDouble("qomPeakRollingArea"),
        qomRollingAreaAtTransition = nullableDouble("qomRollingAreaAtTransition"),
        confidence = nullableDouble("confidence"),
        quality = getJSONObject("quality").toQuality(),
        provenance = getJSONObject("provenance").toProvenance(),
        debugEvidence = getJSONArray("debugEvidence").objects().map { it.toDebugSample() },
        abstentionReason = nullableString("abstentionReason")?.let(ImpactAbstentionReason::valueOf),
    )

    private fun SourceNormalizedPoint.toJson() = JSONObject().put("x", x).put("y", y)
    private fun JSONObject.toPoint() = SourceNormalizedPoint(getDouble("x").toFloat(), getDouble("y").toFloat())
    private fun ImpactArticulationState.toJson() = JSONObject().put("proximalAngleDeg", proximalAngleDeg).put("jointAngleDeg", jointAngleDeg)
    private fun JSONObject.toArticulation() = ImpactArticulationState(getDouble("proximalAngleDeg"), getDouble("jointAngleDeg"))

    private fun ImpactQualityDiagnostics.toJson() = JSONObject()
        .put("suppliedFrameCount", suppliedFrameCount)
        .put("usableSampleCount", usableSampleCount)
        .put("trackingCoverage", trackingCoverage)
        .also {
            it.nullable("robustLinearSpeedScale", robustLinearSpeedScale)
            it.nullable("robustAngularSpeedScale", robustAngularSpeedScale)
            it.nullable("robustMotionFallScale", robustMotionFallScale)
        }
        .put("representativePolicy", representativePolicy)

    private fun JSONObject.toQuality() = ImpactQualityDiagnostics(
        suppliedFrameCount = getInt("suppliedFrameCount"),
        usableSampleCount = getInt("usableSampleCount"),
        trackingCoverage = getDouble("trackingCoverage"),
        robustLinearSpeedScale = nullableDouble("robustLinearSpeedScale"),
        robustAngularSpeedScale = nullableDouble("robustAngularSpeedScale"),
        robustMotionFallScale = nullableDouble("robustMotionFallScale"),
        representativePolicy = getString("representativePolicy"),
    )

    private fun ImpactAnalysisProvenance.toJson() = JSONObject().apply {
        put("landmarkTrackId", landmarkTrackId)
        put("recordingId", recordingId)
        put("frameGeometryId", frameGeometryId)
        put("frameGeometryContractVersion", frameGeometryContractVersion)
        nullable("sourceHash", sourceHash)
        nullable("trackHash", trackHash)
        nullable("segmenterVersion", segmenterVersion)
        nullable("bodyScaleSourceId", bodyScaleSourceId)
        nullable("bodyScaleSourceVersion", bodyScaleSourceVersion)
        put("analyzerVersion", analyzerVersion)
        put("configurationVersion", configurationVersion)
        put("calibration", calibration.toJson())
        nullable("movementLogicalStartTimestampUs", movementLogicalStartTimestampUs)
        nullable("movementLogicalEndTimestampUs", movementLogicalEndTimestampUs)
        nullable("evidenceStartTimestampUs", evidenceStartTimestampUs)
        nullable("evidenceEndTimestampUs", evidenceEndTimestampUs)
        nullable("limbAnalyzerVersion", limbAnalyzerVersion)
        nullable("limbAngularPolicyVersion", limbAngularPolicyVersion)
        nullable("limbConfigurationVersion", limbConfigurationVersion)
    }

    private fun JSONObject.toProvenance() = ImpactAnalysisProvenance(
        landmarkTrackId = getString("landmarkTrackId"),
        recordingId = getString("recordingId"),
        frameGeometryId = getString("frameGeometryId"),
        frameGeometryContractVersion = getString("frameGeometryContractVersion"),
        sourceHash = nullableString("sourceHash"),
        trackHash = nullableString("trackHash"),
        segmenterVersion = nullableString("segmenterVersion"),
        bodyScaleSourceId = nullableString("bodyScaleSourceId"),
        bodyScaleSourceVersion = nullableString("bodyScaleSourceVersion"),
        analyzerVersion = getString("analyzerVersion"),
        configurationVersion = getString("configurationVersion"),
        calibration = getJSONObject("calibration").toCalibration(),
        movementLogicalStartTimestampUs = nullableLong("movementLogicalStartTimestampUs"),
        movementLogicalEndTimestampUs = nullableLong("movementLogicalEndTimestampUs"),
        evidenceStartTimestampUs = nullableLong("evidenceStartTimestampUs"),
        evidenceEndTimestampUs = nullableLong("evidenceEndTimestampUs"),
        limbAnalyzerVersion = nullableString("limbAnalyzerVersion"),
        limbAngularPolicyVersion = nullableString("limbAngularPolicyVersion"),
        limbConfigurationVersion = nullableString("limbConfigurationVersion"),
    )

    private fun ImpactCalibrationProvenance.toJson() = JSONObject()
        .put("spatialDeadbandBodyHeightRatio", spatialDeadbandBodyHeightRatio)
        .put("angularDeadbandDegPerSample", angularDeadbandDegPerSample)
        .put("postTransitionConfirmationUs", postTransitionConfirmationUs)
        .put("stableMinimumDurationUs", stableMinimumDurationUs)
        .put("robustPercentile", robustPercentile)
        .put("minimumTransitionScore", minimumTransitionScore)
        .put("minimumLandmarkConfidence", minimumLandmarkConfidence)
        .put("minimumTrackingCoverage", minimumTrackingCoverage)
        .put("representativeQualityImprovement", representativeQualityImprovement)
        .put("positionMedianSamples", positionMedianSamples)
        .put("positionMeanSamples", positionMeanSamples)

    private fun JSONObject.toCalibration() = ImpactCalibrationProvenance(
        spatialDeadbandBodyHeightRatio = getDouble("spatialDeadbandBodyHeightRatio"),
        angularDeadbandDegPerSample = getDouble("angularDeadbandDegPerSample"),
        postTransitionConfirmationUs = getLong("postTransitionConfirmationUs"),
        stableMinimumDurationUs = getLong("stableMinimumDurationUs"),
        robustPercentile = getDouble("robustPercentile"),
        minimumTransitionScore = getDouble("minimumTransitionScore"),
        minimumLandmarkConfidence = getDouble("minimumLandmarkConfidence"),
        minimumTrackingCoverage = getDouble("minimumTrackingCoverage"),
        representativeQualityImprovement = getDouble("representativeQualityImprovement"),
        positionMedianSamples = getInt("positionMedianSamples"),
        positionMeanSamples = getInt("positionMeanSamples"),
    )

    private fun ImpactDebugSample.toJson() = JSONObject().apply {
        put("timestampUs", timestampUs)
        put("weaponPoint", weaponPoint.toJson())
        put("weaponConfidence", weaponConfidence)
        put("accumulatedTravel", accumulatedTravel)
        put("travelProgress", travelProgress)
        put("weaponSpeed", weaponSpeed)
        put("proximalAngleDeg", proximalAngleDeg)
        put("jointAngleDeg", jointAngleDeg)
        put("proximalProgress", proximalProgress)
        put("jointProgress", jointProgress)
        put("additiveArticulationProgress", additiveArticulationProgress)
        put("articulationProgress", articulationProgress)
        put("angularSpeedDegPerSec", angularSpeedDegPerSec)
        put("motionEnvelope", motionEnvelope)
        put("motionFallPerSec", motionFallPerSec)
        put("progressGate", progressGate)
        put("postTransitionStability", postTransitionStability)
        put("terminalTransitionScore", terminalTransitionScore)
        put("stableState", stableState)
        nullable("qomRollingArea", qomRollingArea)
    }

    private fun JSONObject.toDebugSample() = ImpactDebugSample(
        timestampUs = getLong("timestampUs"),
        weaponPoint = getJSONObject("weaponPoint").toPoint(),
        weaponConfidence = getDouble("weaponConfidence"),
        accumulatedTravel = getDouble("accumulatedTravel"),
        travelProgress = getDouble("travelProgress"),
        weaponSpeed = getDouble("weaponSpeed"),
        proximalAngleDeg = getDouble("proximalAngleDeg"),
        jointAngleDeg = getDouble("jointAngleDeg"),
        proximalProgress = getDouble("proximalProgress"),
        jointProgress = getDouble("jointProgress"),
        additiveArticulationProgress = getDouble("additiveArticulationProgress"),
        articulationProgress = getDouble("articulationProgress"),
        angularSpeedDegPerSec = getDouble("angularSpeedDegPerSec"),
        motionEnvelope = getDouble("motionEnvelope"),
        motionFallPerSec = getDouble("motionFallPerSec"),
        progressGate = getDouble("progressGate"),
        postTransitionStability = getDouble("postTransitionStability"),
        terminalTransitionScore = getDouble("terminalTransitionScore"),
        stableState = getBoolean("stableState"),
        qomRollingArea = nullableDouble("qomRollingArea"),
    )

    private fun JSONObject.nullable(name: String, value: Any?) {
        put(name, value ?: JSONObject.NULL)
    }

    private fun JSONObject.nullableString(name: String) = if (isNull(name)) null else getString(name)
    private fun JSONObject.nullableLong(name: String) = if (isNull(name)) null else getLong(name)
    private fun JSONObject.nullableDouble(name: String) = if (isNull(name)) null else getDouble(name)
    private fun JSONObject.nullableObject(name: String) = if (isNull(name)) null else getJSONObject(name)
    private fun JSONArray.objects() = (0 until length()).map(::getJSONObject)
}
