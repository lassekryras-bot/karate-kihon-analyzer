package dk.lasse.karateanalyzer.core

/**
 * Hand-relative axes derived from stabilized landmarks, independent of camera pixels.
 * [origin] is the average of the four finger MCP knuckles, [palmWidth] is INDEX_MCP to LITTLE_MCP,
 * [yAxis] points from WRIST toward MIDDLE_MCP, [xAxis] points from INDEX_MCP toward LITTLE_MCP, and
 * [zAxis] is their normalized cross product when the palm is non-degenerate.
 */
data class PalmCoordinateSystem(
    val origin: Point3,
    val xAxis: Point3,
    val yAxis: Point3,
    val zAxis: Point3?,
    val palmWidth: Float,
)

/**
 * Analyzer-neutral finger measurements. Distances are normalized by palm width. Joint angles are the
 * geometric angle at the named joint. Curl/extension are continuous 0..1 heuristics from the average
 * PIP/DIP flexion where 180 degrees is straight and smaller angles are more curled.
 */
data class FingerFeatures(
    val mcpAngleDegrees: Float?,
    val pipAngleDegrees: Float?,
    val dipAngleDegrees: Float?,
    val tipToPalmRatio: Float?,
    val tipToMcpRatio: Float?,
    val curlScore: Float?,
    val extensionScore: Float?,
    val quality: Float,
)

/** Camera-normalized segment for the live thumb boundary used by Find Your Weapon debug views. */
data class ThumbBoundaryLine(
    val start: Point3,
    val end: Point3,
)

/** Thumb geometry needed by later step verifiers without deciding whether any step has passed. */
data class ThumbFeatures(
    val cmcAngleDegrees: Float?,
    val mcpAngleDegrees: Float?,
    val ipAngleDegrees: Float?,
    val tipToPalmRatio: Float?,
    val tipToIndexMcpRatio: Float?,
    val tipToMiddleMcpRatio: Float?,
    val tipLateralToPalmRatio: Float?,
    val weightedFingerDistanceRatio: Float?,
    val closedScore: Float?,
    val openScore: Float?,
    val tipInsideIndexBoundaryRatio: Float?,
    val tipInsideIndexBoundary: Boolean?,
    val indexBoundaryLine: ThumbBoundaryLine?,
    val crossesPalmAxis: Boolean?,
    val quality: Float,
)

/**
 * Normalized features extracted from a tracked hand frame. Quality uses configurable LandmarkSource
 * weights (default OBSERVED=1, INTERPOLATED=.75, PREDICTED=.5, MISSING=0) and reports observed versus
 * estimated (predicted or interpolated) provenance separately. Estimated 3D landmarks can stabilize
 * downstream checks, but their geometry should be treated as lower confidence than direct observations.
 */
data class HandFeatures(
    val timestampMs: Long,
    val handedness: Handedness,
    val palmCoordinateSystem: PalmCoordinateSystem?,
    val palmCenter: Point3?,
    val palmWidth: Float?,
    val index: FingerFeatures,
    val middle: FingerFeatures,
    val ring: FingerFeatures,
    val little: FingerFeatures,
    val thumb: ThumbFeatures,
    val openPalmScore: Float?,
    val fourFingerCurlScore: Float?,
    val dataQuality: Float,
    val observedLandmarkRatio: Float,
    val estimatedLandmarkRatio: Float,
)

data class HandFeatureExtractorConfiguration(
    val sourceWeights: Map<LandmarkSource, Float> = mapOf(
        LandmarkSource.OBSERVED to 1f,
        LandmarkSource.INTERPOLATED to 0.75f,
        LandmarkSource.PREDICTED to 0.5f,
        LandmarkSource.MISSING to 0f,
    ),
    val minimumAggregateFingerCount: Int = 3,
    val thumbNearestFingerPointCount: Int = 3,
    val thumbClosedDistanceRatio: Float = 0.45f,
    val thumbOpenDistanceRatio: Float = 1.20f,
)
