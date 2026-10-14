package dk.lasse.karateanalyzer.capture.qom

enum class QomEvidencePolicy(val policyVersion: String) {
    LEGACY_FINITE_WEIGHTED_V1("qom-evidence-finite-weighted-v1"),
    OBSERVED_WORLD_V2("qom-evidence-observed-world-v2"),
}

/**
 * Configuration parameters for the activity-aware Quantity of Motion detector.
 *
 * Defaults match the normative values established in docs/motion-detector-math.md.
 */
data class QomDetectorConfig(
    val detectorVersion: String = "activity_qom_hysteresis_v1",
    val evidencePolicy: QomEvidencePolicy = QomEvidencePolicy.LEGACY_FINITE_WEIGHTED_V1,
    val minimumObservedConfidence: Double = 0.55,
    val minimumCompositeSupport: Double = 0.55,
    val positionMedianSamples: Int = 3,
    val positionMeanSamples: Int = 3,
    val rollingAreaDurationMs: Long = 150L,
    val startGateMeters: Double = 0.08,
    val stopGateMeters: Double = 0.04,
    val maximumFrameGapMs: Long = 500L,
    val preRollMs: Long = 150L,
    val postRollMs: Long = 200L,
    val minimumValidBlocks: Int = 1,
) {
    init {
        require((evidencePolicy == QomEvidencePolicy.OBSERVED_WORLD_V2) == (detectorVersion == SUPPORTED_WORLD_V2)) {
            "Reserved detector version $SUPPORTED_WORLD_V2 must be paired only with OBSERVED_WORLD_V2"
        }
        require(positionMedianSamples >= 1) { "positionMedianSamples must be >= 1" }
        require(positionMeanSamples >= 1) { "positionMeanSamples must be >= 1" }
        require(rollingAreaDurationMs > 0L) { "rollingAreaDurationMs must be > 0" }
        require(startGateMeters > stopGateMeters) { "startGateMeters ($startGateMeters) must be > stopGateMeters ($stopGateMeters)" }
        require(stopGateMeters >= 0.0) { "stopGateMeters must be >= 0.0" }
        require(maximumFrameGapMs > 0L) { "maximumFrameGapMs must be > 0" }
        require(preRollMs >= 0L) { "preRollMs must be >= 0" }
        require(postRollMs >= 0L) { "postRollMs must be >= 0" }
        require(minimumValidBlocks >= 1) { "minimumValidBlocks must be >= 1" }
        require(minimumObservedConfidence in 0.0..1.0) { "minimumObservedConfidence must be in 0..1" }
        require(minimumCompositeSupport in 0.0..1.0) { "minimumCompositeSupport must be in 0..1" }
    }

    companion object {
        const val SUPPORTED_WORLD_V2 = "activity_qom_supported_world_v2"

        fun supportedWorldV2(): QomDetectorConfig = QomDetectorConfig(
            detectorVersion = SUPPORTED_WORLD_V2,
            evidencePolicy = QomEvidencePolicy.OBSERVED_WORLD_V2,
        )
    }
}

