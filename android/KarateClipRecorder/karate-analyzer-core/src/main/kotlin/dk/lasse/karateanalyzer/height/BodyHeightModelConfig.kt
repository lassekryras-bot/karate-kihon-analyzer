package dk.lasse.karateanalyzer.height

/**
 * Versioned HeadAnchor extraction strategies.
 */
enum class HeadAnchorStrategyId {
    EAR_MIDPOINT_V1,
    EYES_EARS_COMPOSITE_V1,
}

/**
 * Window evidence state describing completeness of contributing observations.
 */
enum class WindowState {
    UNAVAILABLE,
    SINGLE_FRAME_REQUEST,
    SINGLE_FRAME_FALLBACK,
    FULL_REQUESTED_WINDOW,
    REDUCED_WINDOW,
}

/**
 * Immutable configuration for BodyHeightModel.
 */
data class BodyHeightModelConfig(
    val landmarkValidityThreshold: Float = 0.55f,
    val minimumTorsoLength: Float = 0.05f,
    val headAnchorStrategy: HeadAnchorStrategyId = HeadAnchorStrategyId.EAR_MIDPOINT_V1,
    val maxTimestampGapUs: Long = 100_000L,
    val landmarkStreamCadenceSource: String = "mls_30fps_contract",
    val aggregationMethod: String = "component_wise_median_v1",
    val configId: String = DEFAULT_CONFIG_ID,
) {
    companion object {
        const val DEFAULT_CONFIG_ID = "default_body_height_v2.1"
    }

    val isDefaultPolicy: Boolean =
        landmarkValidityThreshold == 0.55f &&
        minimumTorsoLength == 0.05f &&
        headAnchorStrategy == HeadAnchorStrategyId.EAR_MIDPOINT_V1 &&
        maxTimestampGapUs == 100_000L &&
        landmarkStreamCadenceSource == "mls_30fps_contract" &&
        aggregationMethod == "component_wise_median_v1"

    val effectiveConfigId: String = if (configId != DEFAULT_CONFIG_ID) {
        configId
    } else if (!isDefaultPolicy) {
        "${DEFAULT_CONFIG_ID}_th${landmarkValidityThreshold}_gap${maxTimestampGapUs}_torso${minimumTorsoLength}_${headAnchorStrategy.name}_cadence${landmarkStreamCadenceSource}_agg${aggregationMethod}"
    } else {
        DEFAULT_CONFIG_ID
    }

    init {
        require(landmarkValidityThreshold in 0f..1f) {
            "landmarkValidityThreshold must be in [0, 1], was $landmarkValidityThreshold"
        }
        require(minimumTorsoLength.isFinite() && minimumTorsoLength > 0f) {
            "minimumTorsoLength must be finite and strictly positive, was $minimumTorsoLength"
        }
        require(maxTimestampGapUs > 0L) {
            "maxTimestampGapUs must be strictly positive, was $maxTimestampGapUs"
        }
    }
}

/**
 * Evidence and provenance summary for an aggregated component (Torso or Head).
 */
data class ComponentEvidenceSummary(
    val requestedRadius: Int,
    val requestedTimestampsUs: List<Long>,
    val contributingTimestampsUs: List<Long>,
    val usableCount: Int,
    val excludedTimestampsUs: List<Long> = emptyList(),
    val exclusionReasons: Map<Long, String> = emptyMap(),
    val windowState: WindowState,
    val disagreementMetric: Float? = null,
)

/**
 * Per-sample observed anchors retained for diagnostics.
 */
data class PerSampleAnchors(
    val timestampUs: Long,
    val shoulderCenter: dk.lasse.karateanalyzer.geometry.SourceNormalizedPoint?,
    val hipCenter: dk.lasse.karateanalyzer.geometry.SourceNormalizedPoint?,
    val headAnchor: dk.lasse.karateanalyzer.geometry.SourceNormalizedPoint?,
    val isTorsoValid: Boolean,
    val isHeadValid: Boolean,
    val trackId: String? = null,
)
