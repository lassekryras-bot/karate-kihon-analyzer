package dk.lasse.karateanalyzer.geometry

import dk.lasse.karateanalyzer.core.PoseLandmarkId

/**
 * Identity of a canonical source frame used in analysis and overlay presentation.
 */
data class OverlayFrameIdentity(
    val recordingId: String,
    val movementId: String? = null,
    val actualPresentationTimestampUs: Long,
    val frameIndex: Long? = null,
)

/**
 * Spatial and temporal provenance of a derived still, thumbnail, or crop.
 */
data class DerivedImageProvenance(
    val sourceRecordingId: String,
    val requestedExtractionTimestampUs: Long? = null,
    val actualPresentationTimestampUs: Long,
    val requestedNormalizedCrop: NormalizedCrop? = null,
    val appliedPixelCrop: AppliedPixelCrop,
    val bitmapWidth: Int,
    val bitmapHeight: Int,
    val canonicalOrientation: CanonicalOrientation = CanonicalOrientation.UPRIGHT_UNMIRRORED,
)

/**
 * Presentation-independent semantic overlay data expressed in canonical source coordinates.
 */
data class OverlayTargetData(
    val targetType: String,
    val targetPoint: SourceNormalizedPoint,
    val isClosest: Boolean = false,
)

data class OverlayRayData(
    val origin: SourceNormalizedPoint,
    val endpoint: SourceNormalizedPoint,
    val label: String,
    val isClosest: Boolean = false,
)

data class SemanticOverlayData(
    val frameIdentity: OverlayFrameIdentity,
    val strikingSide: String? = null,
    val shoulderPoint: SourceNormalizedPoint? = null,
    val elbowPoint: SourceNormalizedPoint? = null,
    val wristPoint: SourceNormalizedPoint? = null,
    val fistPoint: SourceNormalizedPoint? = null,
    val targets: List<OverlayTargetData> = emptyList(),
    val idealEndpoint: SourceNormalizedPoint? = null,
    val rays: List<OverlayRayData> = emptyList(),
    val reachRadiusAspectCorrect: Float? = null,
    val angleResultDeg: Float? = null,
    val skeletonLandmarks: Map<PoseLandmarkId, SourceNormalizedPoint> = emptyMap(),
)

