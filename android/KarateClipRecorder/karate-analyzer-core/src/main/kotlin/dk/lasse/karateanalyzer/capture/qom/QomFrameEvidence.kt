package dk.lasse.karateanalyzer.capture.qom

data class QomCompositeSupport(
    val support: Double,
    val acceptedConstituentCount: Int,
    val totalConstituentCount: Int,
)

/**
 * Extracted Quantity-of-Motion evidence for a single frame.
 */
data class QomFrameEvidence(
    val timestampMs: Long,
    val profile: MotionBodyProfile,
    val blockQom: Map<MotionBlockId, Double>,
    val aggregateQom: Double?,
    val rollingArea: Double,
    val availableBlockCount: Int,
    val totalActiveBlockCount: Int,
    val isAvailable: Boolean,
    val reason: String? = null,
    val pointConfidences: Map<String, Double> = emptyMap(),
    val compositeSupports: Map<String, QomCompositeSupport> = emptyMap(),
)

