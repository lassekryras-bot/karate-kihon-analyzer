package dk.lasse.karateanalyzer.capture

import dk.lasse.karateanalyzer.observation.Evidence

enum class EndPoseRelationship { SAME_AS_START, MIRRORED_START, DIFFERENT_STABLE_POSE, ANY_STABLE_POSE_AFTER_MOVEMENT }

data class MotionObservation(
    val timestampMs: Long,
    val articulatedMotion: Double?,
    val imageSpaceMotion: Double?,
    val coverage: Double,
    val sameAsStartSimilarity: Double? = null,
    val mirroredStartSimilarity: Double? = null,
    val accumulatedDisplacement: Double? = null,
    val diagnostics: MotionObservationDiagnostics? = null,
    val kinematics: BodyKinematics? = null,
    val relativePose: RelativePose? = null,
) {
    init {
        require(coverage.isFinite() && coverage in 0.0..1.0)
        listOf(articulatedMotion, imageSpaceMotion, sameAsStartSimilarity, mirroredStartSimilarity, accumulatedDisplacement)
            .filterNotNull().forEach { require(it.isFinite()) }
    }
}

enum class MotionChannelStatus { VALID, FIRST_FRAME, INSUFFICIENT_COVERAGE, INVALID_BODY_SCALE, TIMESTAMP_GAP }

data class RegionMotionDiagnostics(
    val expectedLandmarkCount: Int,
    val usableLandmarkCount: Int,
    val coverage: Double,
    val rawMotion: Double?,
    val robustMotion: Double?,
    val clampedLandmarkCount: Int,
)

data class MotionObservationDiagnostics(
    val articulatedStatus: MotionChannelStatus,
    val imageSpaceStatus: MotionChannelStatus,
    val elapsedMs: Long?,
    val bodyScaleWorld: Double?,
    val bodyScaleImage: Double?,
    val imageCenterTranslation: Double?,
    val imageScaleChange: Double?,
    val minimumRegionCoverage: Double,
    val regionBalancedCoverage: Double,
    val baselineSampleCount: Int,
    val baselineReady: Boolean,
    val regions: Map<AnatomicalRegion, RegionMotionDiagnostics>,
)
