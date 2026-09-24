package dk.lasse.karateanalyzer.impact

import dk.lasse.karateanalyzer.capture.LateralSide
import dk.lasse.karateanalyzer.capture.qom.QomFrameEvidence
import dk.lasse.karateanalyzer.core.PoseFrame
import dk.lasse.karateanalyzer.geometry.CanonicalGeometryDescriptor
import dk.lasse.karateanalyzer.geometry.SourceNormalizedPoint

enum class ImpactAnalysisStatus { COMPLETED, ABSTAINED }

enum class ImpactAbstentionReason {
    INSUFFICIENT_MEASURABLE_MOTION,
    INSUFFICIENT_ARTICULATION_CHANGE,
    WEAPON_TRACK_UNAVAILABLE,
    LIMB_GEOMETRY_UNAVAILABLE,
    BODY_SCALE_UNAVAILABLE,
    INVALID_TIMESTAMPS,
    FRAME_GEOMETRY_UNAVAILABLE,
    NO_POST_PEAK_TERMINAL_TRANSITION,
    NO_STABLE_TERMINAL_WINDOW,
    TRACKING_QUALITY_INSUFFICIENT,
    UNSUPPORTED_ANALYSIS_PROFILE,
}

enum class ImpactLimbFamily { UPPER_LIMB, LOWER_LIMB }

enum class WeaponPointDefinition {
    COMPOSITE_HAND,
    WRIST,
    KNEE,
    COMPOSITE_FOOT,
}

/** Provisional v1 calibration. Every field affecting output is retained through [configVersion]. */
data class ImpactAnalysisProfile(
    val activityProfileId: String,
    val side: LateralSide,
    val weapon: WeaponPointDefinition,
    val limbFamily: ImpactLimbFamily,
    val approvedViewProfile: String,
    val configVersion: String = DEFAULT_CONFIG_VERSION,
    val spatialDeadbandBodyHeightRatio: Double = 0.02,
    val angularDeadbandDegPerSample: Double = 3.6,
    val postTransitionConfirmationUs: Long = 100_000L,
    val stableMinimumDurationUs: Long = 100_000L,
    val robustPercentile: Double = 0.95,
    val minimumTransitionScore: Double = 0.02,
    val minimumLandmarkConfidence: Double = 0.55,
    val minimumTrackingCoverage: Double = 0.60,
    val representativeQualityImprovement: Double = 0.10,
    val positionMedianSamples: Int = 3,
    val positionMeanSamples: Int = 3,
) {
    init {
        require(activityProfileId.isNotBlank())
        require(approvedViewProfile.isNotBlank())
        require(configVersion.isNotBlank())
        require(spatialDeadbandBodyHeightRatio > 0.0)
        require(angularDeadbandDegPerSample > 0.0)
        require(postTransitionConfirmationUs > 0L)
        require(stableMinimumDurationUs > 0L)
        require(robustPercentile in 0.5..1.0)
        require(minimumTransitionScore >= 0.0)
        require(minimumLandmarkConfidence in 0.0..1.0)
        require(minimumTrackingCoverage in 0.0..1.0)
        require(representativeQualityImprovement in 0.0..1.0)
        require(positionMedianSamples > 0)
        require(positionMeanSamples > 0)
        val usesDefaultCalibration =
            spatialDeadbandBodyHeightRatio == 0.02 &&
                angularDeadbandDegPerSample == 3.6 &&
                postTransitionConfirmationUs == 100_000L &&
                stableMinimumDurationUs == 100_000L &&
                robustPercentile == 0.95 &&
                minimumTransitionScore == 0.02 &&
                minimumLandmarkConfidence == 0.55 &&
                minimumTrackingCoverage == 0.60 &&
                representativeQualityImprovement == 0.10 &&
                positionMedianSamples == 3 &&
                positionMeanSamples == 3
        require(configVersion != DEFAULT_CONFIG_VERSION || usesDefaultCalibration) {
            "Changing provisional impact calibration requires an explicit configVersion"
        }
    }

    companion object {
        const val DEFAULT_CONFIG_VERSION = "impact_config_v1_provisional"
    }
}

/** Body scale in the same aspect-correct source-frame-height units used by weapon geometry. */
data class BodyScaleEvidence(
    val bodyHeightAspectCorrect: Double,
    val sourceId: String,
    val sourceVersion: String,
) {
    val isUsable: Boolean
        get() = bodyHeightAspectCorrect.isFinite() && bodyHeightAspectCorrect > 0.0 &&
            sourceId.isNotBlank() && sourceVersion.isNotBlank()
}

data class ImpactMovementInput(
    val movementId: String,
    val logicalStartTimestampUs: Long,
    val logicalEndTimestampUs: Long,
    val evidenceStartTimestampUs: Long = logicalStartTimestampUs,
    val evidenceEndTimestampUs: Long = logicalEndTimestampUs,
    val frames: List<PoseFrame>,
    val qomTimeline: List<QomFrameEvidence>,
    val segmenterVersion: String?,
    val landmarkTrackId: String,
    val canonicalGeometry: CanonicalGeometryDescriptor,
    val bodyScale: BodyScaleEvidence?,
    val profile: ImpactAnalysisProfile,
)

data class ImpactArticulationState(
    val proximalAngleDeg: Double,
    val jointAngleDeg: Double,
)

data class ImpactDebugSample(
    val timestampUs: Long,
    val weaponPoint: SourceNormalizedPoint,
    val weaponConfidence: Double,
    val accumulatedTravel: Double,
    val travelProgress: Double,
    val weaponSpeed: Double,
    val proximalAngleDeg: Double,
    val jointAngleDeg: Double,
    val proximalProgress: Double,
    val jointProgress: Double,
    val additiveArticulationProgress: Double,
    val articulationProgress: Double,
    val angularSpeedDegPerSec: Double,
    val motionEnvelope: Double,
    val motionFallPerSec: Double,
    val progressGate: Double,
    val postTransitionStability: Double,
    val terminalTransitionScore: Double,
    val stableState: Boolean,
    val qomRollingArea: Double?,
)

data class ImpactQualityDiagnostics(
    val suppliedFrameCount: Int,
    val usableSampleCount: Int,
    val trackingCoverage: Double,
    val robustLinearSpeedScale: Double?,
    val robustAngularSpeedScale: Double?,
    val robustMotionFallScale: Double?,
    val representativePolicy: String = "earliest_stable_unless_quality_improves_v1",
)

data class ImpactAnalysisProvenance(
    val landmarkTrackId: String,
    val recordingId: String,
    val frameGeometryId: String,
    val frameGeometryContractVersion: String,
    val sourceHash: String?,
    val trackHash: String?,
    val segmenterVersion: String?,
    val bodyScaleSourceId: String?,
    val bodyScaleSourceVersion: String?,
    val analyzerVersion: String,
    val configurationVersion: String,
    val calibration: ImpactCalibrationProvenance = ImpactCalibrationProvenance(),
    val movementLogicalStartTimestampUs: Long? = null,
    val movementLogicalEndTimestampUs: Long? = null,
    val evidenceStartTimestampUs: Long? = null,
    val evidenceEndTimestampUs: Long? = null,
)

data class ImpactCalibrationProvenance(
    val spatialDeadbandBodyHeightRatio: Double = 0.02,
    val angularDeadbandDegPerSample: Double = 3.6,
    val postTransitionConfirmationUs: Long = 100_000L,
    val stableMinimumDurationUs: Long = 100_000L,
    val robustPercentile: Double = 0.95,
    val minimumTransitionScore: Double = 0.02,
    val minimumLandmarkConfidence: Double = 0.55,
    val minimumTrackingCoverage: Double = 0.60,
    val representativeQualityImprovement: Double = 0.10,
    val positionMedianSamples: Int = 3,
    val positionMeanSamples: Int = 3,
)

data class ImpactAnalysisResult(
    val status: ImpactAnalysisStatus,
    val movementId: String,
    val weaponId: WeaponPointDefinition,
    val side: LateralSide,
    val limbFamily: ImpactLimbFamily,
    val viewProfile: String,
    val terminalTransitionTimestampUs: Long? = null,
    val terminalTransitionEstimateTimestampUs: Long? = null,
    val selectedObservedTimestampUs: Long? = null,
    val stableWindowStartTimestampUs: Long? = null,
    val stableWindowEndTimestampUs: Long? = null,
    val stableRepresentativeTimestampUs: Long? = null,
    val stableRepresentativeWeaponPoint: SourceNormalizedPoint? = null,
    val stableRepresentativeArticulation: ImpactArticulationState? = null,
    val travelProgressAtTransition: Double? = null,
    val articulationProgressAtTransition: Double? = null,
    val selectedWeaponSpeedAtTransition: Double? = null,
    val angularSpeedAtTransition: Double? = null,
    val terminalTransitionScore: Double? = null,
    val qomPeakTimestampUs: Long? = null,
    val qomPeakRollingArea: Double? = null,
    val qomRollingAreaAtTransition: Double? = null,
    val confidence: Double? = null,
    val quality: ImpactQualityDiagnostics,
    val provenance: ImpactAnalysisProvenance,
    val debugEvidence: List<ImpactDebugSample> = emptyList(),
    val abstentionReason: ImpactAbstentionReason? = null,
)
