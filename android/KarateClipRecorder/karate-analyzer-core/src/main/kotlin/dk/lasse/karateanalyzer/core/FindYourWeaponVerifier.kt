package dk.lasse.karateanalyzer.core

import kotlin.math.abs
import kotlin.math.min

/** The five analyzer-neutral Find Your Weapon lesson shapes, evaluated one frame at a time. */
enum class HandLessonStep {
    OPEN_PALM,
    BEND_FINGERTIPS,
    CLOSE_FINGERS,
    THUMB_ON_TOP,
    FRONT_TWO_KNUCKLES,
}

/** Neutral single-frame status. Temporal acceptance and step progression are handled by callers. */
enum class InstantVerificationStatus {
    INSUFFICIENT_DATA,
    NOT_MATCHING,
    PARTIAL_MATCH,
    MATCHING,
}

/** Result for one verifier pass over one [TrackedHandFrame] and its extracted [HandFeatures]. */
data class InstantStepResult(
    val step: HandLessonStep,
    val status: InstantVerificationStatus,
    val score: Float,
    val quality: Float,
    val feedbackCode: FeedbackCode,
    val criticalLandmarksVisible: Boolean,
    val highlightLandmarks: Set<HandLandmarkId> = emptySet(),
)

/** Named score weights so verifier methods do not hide scoring policy in unexplained literals. */
data class StepScoreWeights(
    val primary: Float = 0.55f,
    val secondary: Float = 0.25f,
    val consistency: Float = 0.20f,
)

/**
 * Thresholds for single-frame Find Your Weapon verification.
 *
 * Defaults are broad heuristics until tuned with real capture data. Quality thresholds describe
 * landmark provenance confidence: OBSERVED contributes fully, INTERPOLATED partially, PREDICTED
 * weakly, and MISSING not at all. Score thresholds map continuous 0..1 components to neutral
 * statuses. [fingertipBendCurlFalloff] controls how quickly bend scores decay outside the target
 * curl range, preventing straight or fully closed fingers from matching through consistency alone.
 *
 * Guide alignment, hold timing, temporal smoothing, automatic progression, UI strings, MediaPipe,
 * CameraX, and Android dependencies are deliberately excluded from these pure Kotlin/JVM rules.
 */
data class FindYourWeaponVerifierConfiguration(
    val minimumOverallDataQuality: Float = 0.55f,
    val minimumCriticalLandmarkQuality: Float = 0.70f,
    val minimumMatchingReliableCriticalQuality: Float = 0.70f,
    val matchingScoreThreshold: Float = 0.78f,
    val partialMatchScoreThreshold: Float = 0.45f,
    val openPalmExtensionThreshold: Float = 0.78f,
    val openPalmFacingCameraTolerance: Float = 0.20f,
    val fingertipBendCurlRange: ClosedFloatingPointRange<Float> = 0.30f..0.68f,
    val fingertipBendCurlFalloff: Float = 0.18f,
    val fingertipBendTipToPalmRatioRange: ClosedFloatingPointRange<Float> = 0.45f..1.55f,
    val fingertipBendMinimumMcpAngleDegrees: Float = 125f,
    val fingerConsistencyThreshold: Float = 0.75f,
    val closedFingerCurlThreshold: Float = 0.74f,
    val closedTipToPalmRatioThreshold: Float = 0.85f,
    val thumbAcrossMaxKnuckleDistance: Float = 0.90f,
    val thumbAcrossMinimumClosenessScore: Float = 0.35f,
    val thumbAcrossMinimumInsideFistScore: Float = 0.20f,
    val thumbAcrossMinimumLateralRatio: Float = -0.55f,
    val thumbFarOutsidePalmRatio: Float = 1.70f,
    val thumbOpenScoreThreshold: Float = 0.55f,
    val thumbClosedScoreThreshold: Float = 0.45f,
    val thumbInsideHandBoundaryThreshold: Float = 0.02f,
    val thumbOutsideHandBoundaryTolerance: Float = 0.00f,
    val fistOrientationTolerance: Float = 0.55f,
    val minimumVisibleFingerCount: Int = 4,
    val scoreWeights: StepScoreWeights = StepScoreWeights(),
)

interface HandStepVerifier {
    val step: HandLessonStep

    fun verify(
        frame: TrackedHandFrame,
        features: HandFeatures,
    ): InstantStepResult
}

/** Dispatches to the private verifier implementation for the requested Find Your Weapon step. */
class FindYourWeaponVerifier(
    configuration: FindYourWeaponVerifierConfiguration = FindYourWeaponVerifierConfiguration(),
) {
    private val verifiers: Map<HandLessonStep, HandStepVerifier> = listOf(
        OpenPalmStepVerifier(configuration),
        BendFingertipsStepVerifier(configuration),
        CloseFingersStepVerifier(configuration),
        ThumbOnTopStepVerifier(configuration),
        FrontTwoKnucklesStepVerifier(configuration),
    ).associateBy { it.step }

    fun verify(
        step: HandLessonStep,
        frame: TrackedHandFrame,
        features: HandFeatures,
    ): InstantStepResult = verifiers.getValue(step).verify(frame, features)
}

private abstract class BaseVerifier(
    protected val configuration: FindYourWeaponVerifierConfiguration,
) : HandStepVerifier {
    protected val fingerFeatures: List<(HandFeatures) -> FingerFeatures> = listOf(
        { it.index },
        { it.middle },
        { it.ring },
        { it.little },
    )

    protected fun result(
        step: HandLessonStep,
        score: Float?,
        quality: Float,
        criticalLandmarksVisible: Boolean,
        criticalQuality: Float,
        requiredMeasurementsPresent: Boolean,
        stepAllowsMatching: Boolean,
        feedbackCode: FeedbackCode,
        highlightLandmarks: Set<HandLandmarkId> = emptySet(),
    ): InstantStepResult {
        val clampedScore = clamp(score)
        val clampedQuality = clamp(quality)
        val hasData = criticalLandmarksVisible && requiredMeasurementsPresent && score != null
        val matchingAllowed = hasData && feedbackCode == FeedbackCode.GOOD && canMatch(clampedQuality, criticalQuality, stepAllowsMatching)
        val status = when {
            !hasData -> InstantVerificationStatus.INSUFFICIENT_DATA
            clampedScore < configuration.partialMatchScoreThreshold -> InstantVerificationStatus.NOT_MATCHING
            clampedScore >= configuration.matchingScoreThreshold && matchingAllowed -> InstantVerificationStatus.MATCHING
            else -> InstantVerificationStatus.PARTIAL_MATCH
        }
        return InstantStepResult(
            step = step,
            status = status,
            score = clampedScore,
            quality = clampedQuality,
            feedbackCode = feedbackCode,
            criticalLandmarksVisible = criticalLandmarksVisible,
            highlightLandmarks = highlightLandmarks,
        )
    }

    protected fun canMatch(
        quality: Float,
        criticalQuality: Float,
        stepAllowsMatching: Boolean,
    ): Boolean = stepAllowsMatching &&
        quality >= configuration.minimumOverallDataQuality &&
        criticalQuality >= configuration.minimumMatchingReliableCriticalQuality

    protected fun usableFingers(features: HandFeatures): List<FingerFeatures> = fingerFeatures
        .map { it(features) }
        .filter { it.quality >= configuration.minimumCriticalLandmarkQuality && it.curlScore != null }

    protected fun criticalQuality(ids: List<HandLandmarkId>, frame: TrackedHandFrame): Float = ids
        .map { sourceWeight(frame.landmarks[it]?.source ?: LandmarkSource.MISSING) }
        .average()
        .toFloat()
        .coerceIn(0f, 1f)

    protected fun sourceWeight(source: LandmarkSource): Float = when (source) {
        LandmarkSource.OBSERVED -> 1f
        LandmarkSource.INTERPOLATED -> 0.75f
        LandmarkSource.PREDICTED -> 0.35f
        LandmarkSource.MISSING -> 0f
    }

    protected fun allPresent(ids: Iterable<HandLandmarkId>, frame: TrackedHandFrame): Boolean = ids.all { id ->
        val sample = frame.landmarks[id]
        sample?.position != null && sample.source != LandmarkSource.MISSING
    }

    protected fun presentLandmarks(ids: Iterable<HandLandmarkId>, frame: TrackedHandFrame): Set<HandLandmarkId> = ids
        .filterTo(mutableSetOf()) { id ->
            val sample = frame.landmarks[id]
            sample?.position != null && sample.source != LandmarkSource.MISSING
        }

    protected fun mean(values: List<Float>): Float? = if (values.isEmpty()) null else values.average().toFloat()

    protected fun consistency(values: List<Float>): Float {
        val average = mean(values) ?: return 0f
        return (1f - values.maxOf { abs(it - average) }).coerceIn(0f, 1f)
    }

    protected fun weighted(primary: Float, secondary: Float, consistency: Float): Float {
        val weights = configuration.scoreWeights
        val total = weights.primary + weights.secondary + weights.consistency
        return ((primary * weights.primary + secondary * weights.secondary + consistency * weights.consistency) / total)
            .coerceIn(0f, 1f)
    }

    protected fun thumbAcrossAssessment(features: HandFeatures): ThumbAcrossAssessment {
        val nearestKnuckleDistance = listOfNotNull(
            features.thumb.tipToIndexMcpRatio,
            features.thumb.tipToMiddleMcpRatio,
        ).minOrNull()
        val closeToKnuckle = nearestKnuckleDistance?.let {
            1f - (it / configuration.thumbAcrossMaxKnuckleDistance).coerceIn(0f, 1f)
        }
        val insideFist = features.thumb.tipToPalmRatio?.let {
            1f - (it / configuration.thumbFarOutsidePalmRatio).coerceIn(0f, 1f)
        }
        val lateralAcross = features.thumb.tipLateralToPalmRatio?.let {
            ((it - configuration.thumbAcrossMinimumLateralRatio) / -configuration.thumbAcrossMinimumLateralRatio)
                .coerceIn(0f, 1f)
        }
        val scoreComponent = listOfNotNull(closeToKnuckle, insideFist, lateralAcross)
            .takeIf { it.size == 3 }
            ?.average()
            ?.toFloat()
        val closedEnough = features.thumb.closedScore?.let { it >= configuration.thumbClosedScoreThreshold } == true
        val insideIndexBoundary = thumbInsideHandBoundary(features)
        return ThumbAcrossAssessment(
            closeToKnuckle = closeToKnuckle,
            insideFist = insideFist,
            lateralAcross = lateralAcross,
            closedScore = features.thumb.closedScore,
            insideIndexBoundary = insideIndexBoundary,
            scoreComponent = scoreComponent,
            isAcross = closeToKnuckle != null &&
                closeToKnuckle >= configuration.thumbAcrossMinimumClosenessScore &&
                insideFist != null &&
                insideFist >= configuration.thumbAcrossMinimumInsideFistScore &&
                features.thumb.tipLateralToPalmRatio != null &&
                features.thumb.tipLateralToPalmRatio >= configuration.thumbAcrossMinimumLateralRatio &&
                insideIndexBoundary &&
                closedEnough,
        )
    }

    protected fun thumbOpenEnough(features: HandFeatures): Boolean =
        features.thumb.openScore?.let { it >= configuration.thumbOpenScoreThreshold } == true &&
            thumbOutsideHandBoundary(features)

    protected fun thumbClosedEnough(features: HandFeatures): Boolean =
        features.thumb.closedScore?.let { it >= configuration.thumbClosedScoreThreshold } == true &&
            thumbInsideHandBoundary(features)

    protected fun thumbInsideHandBoundary(features: HandFeatures): Boolean =
        features.thumb.tipInsideIndexBoundaryRatio?.let { it >= configuration.thumbInsideHandBoundaryThreshold } == true

    protected fun thumbOutsideHandBoundary(features: HandFeatures): Boolean =
        features.thumb.tipInsideIndexBoundaryRatio?.let { it <= configuration.thumbOutsideHandBoundaryTolerance } == true

    protected fun clamp(value: Float?): Float = value?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
}

private data class ThumbAcrossAssessment(
    val closeToKnuckle: Float?,
    val insideFist: Float?,
    val lateralAcross: Float?,
    val closedScore: Float?,
    val insideIndexBoundary: Boolean,
    val scoreComponent: Float?,
    val isAcross: Boolean,
)

private val fourFingerIds = listOf(
    HandLandmarkId.INDEX_MCP,
    HandLandmarkId.INDEX_PIP,
    HandLandmarkId.INDEX_DIP,
    HandLandmarkId.INDEX_TIP,
    HandLandmarkId.MIDDLE_MCP,
    HandLandmarkId.MIDDLE_PIP,
    HandLandmarkId.MIDDLE_DIP,
    HandLandmarkId.MIDDLE_TIP,
    HandLandmarkId.RING_MCP,
    HandLandmarkId.RING_PIP,
    HandLandmarkId.RING_DIP,
    HandLandmarkId.RING_TIP,
    HandLandmarkId.LITTLE_MCP,
    HandLandmarkId.LITTLE_PIP,
    HandLandmarkId.LITTLE_DIP,
    HandLandmarkId.LITTLE_TIP,
)

private val openPalmCriticalIds = listOf(HandLandmarkId.WRIST) + fourFingerIds
private val thumbCriticalIds = listOf(HandLandmarkId.THUMB_CMC, HandLandmarkId.THUMB_MCP, HandLandmarkId.THUMB_IP, HandLandmarkId.THUMB_TIP)
private val frontKnuckleIds = setOf(HandLandmarkId.INDEX_MCP, HandLandmarkId.MIDDLE_MCP)

/** Critical landmarks: wrist and four finger chains. Components: extension, low curl, consistency. */
private class OpenPalmStepVerifier(
    configuration: FindYourWeaponVerifierConfiguration,
) : BaseVerifier(configuration) {
    override val step: HandLessonStep = HandLessonStep.OPEN_PALM

    override fun verify(frame: TrackedHandFrame, features: HandFeatures): InstantStepResult {
        val usable = usableFingers(features)
        val extensions = usable.mapNotNull { it.extensionScore }
        val averageExtension = mean(extensions)
        val palmFacingCamera = features.palmCoordinateSystem?.zAxis?.let { zAxis ->
            when (features.handedness) {
                Handedness.RIGHT -> zAxis.z
                Handedness.LEFT -> -zAxis.z
                Handedness.UNKNOWN -> 0f
            }
        }
        val palmFacingComponent = palmFacingCamera?.let {
            ((it - configuration.openPalmFacingCameraTolerance) / (1f - configuration.openPalmFacingCameraTolerance))
                .coerceIn(0f, 1f)
        }
        val criticalQuality = criticalQuality(openPalmCriticalIds, frame)
        val thumbOpen = thumbOpenEnough(features)
        val criticalVisible = features.palmCoordinateSystem != null &&
            allPresent(listOf(HandLandmarkId.WRIST), frame) &&
            usable.size >= configuration.minimumVisibleFingerCount &&
            criticalQuality >= configuration.minimumCriticalLandmarkQuality
        val score = features.openPalmScore?.let { openPalmScore ->
            val extensionComponent = (openPalmScore / configuration.openPalmExtensionThreshold).coerceIn(0f, 1f)
            weighted(extensionComponent, palmFacingComponent ?: 0f, consistency(extensions))
        }
        val stepAllowsMatching = averageExtension != null &&
            averageExtension >= configuration.openPalmExtensionThreshold &&
            palmFacingCamera != null &&
            palmFacingCamera >= configuration.openPalmFacingCameraTolerance &&
            thumbOpen
        val feedback = when {
            !criticalVisible -> FeedbackCode.INSUFFICIENT_VISIBILITY
            features.dataQuality < configuration.minimumOverallDataQuality -> FeedbackCode.HOLD_STILL
            !thumbOpen -> FeedbackCode.OPEN_THUMB
            clamp(features.fourFingerCurlScore) > 1f - configuration.openPalmExtensionThreshold -> FeedbackCode.OPEN_FINGERS
            palmFacingCamera == null || palmFacingCamera < configuration.openPalmFacingCameraTolerance -> FeedbackCode.TURN_FIST_TOWARD_CAMERA
            else -> FeedbackCode.GOOD
        }
        return result(
            step = step,
            score = score,
            quality = features.dataQuality,
            criticalLandmarksVisible = criticalVisible,
            criticalQuality = criticalQuality,
            requiredMeasurementsPresent = features.openPalmScore != null && features.fourFingerCurlScore != null && features.thumb.openScore != null && features.thumb.tipInsideIndexBoundaryRatio != null,
            stepAllowsMatching = stepAllowsMatching,
            feedbackCode = feedback,
        )
    }
}

/** Intermediate PIP/DIP flexion with MCP joints still open; no temporal or user-calibrated logic. */
private class BendFingertipsStepVerifier(
    configuration: FindYourWeaponVerifierConfiguration,
) : BaseVerifier(configuration) {
    override val step: HandLessonStep = HandLessonStep.BEND_FINGERTIPS

    override fun verify(frame: TrackedHandFrame, features: HandFeatures): InstantStepResult {
        val usable = usableFingers(features)
        val curls = usable.mapNotNull { it.curlScore }
        val mcpAngles = usable.mapNotNull { it.mcpAngleDegrees }
        val ratios = usable.mapNotNull { it.tipToPalmRatio }
        val averageCurl = mean(curls)
        val averageMcpAngle = mean(mcpAngles)
        val criticalQuality = criticalQuality(fourFingerIds, frame)
        val thumbOpen = thumbOpenEnough(features)
        val criticalVisible = usable.size >= configuration.minimumVisibleFingerCount &&
            criticalQuality >= configuration.minimumCriticalLandmarkQuality
        val curlComponent = averageCurl?.let { bendRangeComponent(it) }
        val ratioComponent = mean(ratios.map { rangeComponent(it, configuration.fingertipBendTipToPalmRatioRange, 0.60f) })
        val mcpComponent = averageMcpAngle?.let { mcpOpenComponent(it) }
        val score = if (curlComponent != null && ratioComponent != null && mcpComponent != null) {
            weighted(curlComponent, ratioComponent, consistency(curls))
        } else {
            null
        }
        val curlInRange = averageCurl != null && averageCurl in configuration.fingertipBendCurlRange
        val mcpOpen = averageMcpAngle != null && averageMcpAngle >= configuration.fingertipBendMinimumMcpAngleDegrees
        val fingersConsistent = consistency(curls) >= configuration.fingerConsistencyThreshold
        val stepAllowsMatching = curlInRange && mcpOpen && fingersConsistent && thumbOpen
        val feedback = when {
            !criticalVisible -> FeedbackCode.INSUFFICIENT_VISIBILITY
            features.dataQuality < configuration.minimumOverallDataQuality -> FeedbackCode.HOLD_STILL
            !thumbOpen -> FeedbackCode.OPEN_THUMB
            consistency(curls) < configuration.fingerConsistencyThreshold -> FeedbackCode.FINGERS_UNEVEN
            averageMcpAngle != null && averageMcpAngle < configuration.fingertipBendMinimumMcpAngleDegrees -> FeedbackCode.DO_NOT_CLOSE_YET
            averageCurl == null || averageCurl < configuration.fingertipBendCurlRange.start -> FeedbackCode.BEND_FINGERTIPS_MORE
            averageCurl > configuration.fingertipBendCurlRange.endInclusive -> FeedbackCode.DO_NOT_CLOSE_YET
            else -> FeedbackCode.GOOD
        }
        return result(
            step = step,
            score = score,
            quality = features.dataQuality,
            criticalLandmarksVisible = criticalVisible,
            criticalQuality = criticalQuality,
            requiredMeasurementsPresent = averageCurl != null && averageMcpAngle != null && ratioComponent != null && features.thumb.openScore != null && features.thumb.tipInsideIndexBoundaryRatio != null,
            stepAllowsMatching = stepAllowsMatching,
            feedbackCode = feedback,
        )
    }

    private fun bendRangeComponent(value: Float): Float = rangeComponent(
        value = value,
        range = configuration.fingertipBendCurlRange,
        falloff = configuration.fingertipBendCurlFalloff,
    )

    private fun rangeComponent(
        value: Float,
        range: ClosedFloatingPointRange<Float>,
        falloff: Float,
    ): Float = when {
        value in range -> 1f
        falloff <= 0f -> 0f
        value < range.start -> (1f - ((range.start - value) / falloff)).coerceIn(0f, 1f)
        else -> (1f - ((value - range.endInclusive) / falloff)).coerceIn(0f, 1f)
    }

    private fun mcpOpenComponent(angle: Float): Float {
        val minimum = configuration.fingertipBendMinimumMcpAngleDegrees
        return ((angle - minimum) / (180f - minimum)).coerceIn(0f, 1f)
    }
}

/** Closed fingers; step 3 keeps the thumb open while later fist checks can reuse only the finger shape. */
private class CloseFingersStepVerifier(
    configuration: FindYourWeaponVerifierConfiguration,
    private val requireOpenThumb: Boolean = true,
) : BaseVerifier(configuration) {
    override val step: HandLessonStep = HandLessonStep.CLOSE_FINGERS

    override fun verify(frame: TrackedHandFrame, features: HandFeatures): InstantStepResult {
        val usable = usableFingers(features)
        val curls = usable.mapNotNull { it.curlScore }
        val ratios = usable.mapNotNull { it.tipToPalmRatio }
        val criticalQuality = criticalQuality(fourFingerIds, frame)
        val thumbOpen = !requireOpenThumb || thumbOpenEnough(features)
        val criticalVisible = usable.size >= configuration.minimumVisibleFingerCount &&
            criticalQuality >= configuration.minimumCriticalLandmarkQuality
        val curlComponent = mean(curls.map { it / configuration.closedFingerCurlThreshold })?.coerceIn(0f, 1f)
        val tipComponent = mean(ratios.map { 1f - (it / configuration.closedTipToPalmRatioThreshold).coerceIn(0f, 1f) })
        val score = if (curlComponent != null && tipComponent != null) {
            weighted(curlComponent, tipComponent, consistency(curls))
        } else {
            null
        }
        val stepAllowsMatching = mean(curls)?.let { it >= configuration.closedFingerCurlThreshold } == true &&
            consistency(curls) >= configuration.fingerConsistencyThreshold &&
            thumbOpen
        val feedback = when {
            !criticalVisible -> FeedbackCode.INSUFFICIENT_VISIBILITY
            features.dataQuality < configuration.minimumOverallDataQuality -> FeedbackCode.HOLD_STILL
            !thumbOpen -> FeedbackCode.OPEN_THUMB
            curlComponent == null || curlComponent < configuration.partialMatchScoreThreshold -> FeedbackCode.CLOSE_FINGERS_MORE
            consistency(curls) < configuration.fingerConsistencyThreshold -> FeedbackCode.FINGERS_UNEVEN
            else -> FeedbackCode.GOOD
        }
        return result(
            step = step,
            score = score,
            quality = features.dataQuality,
            criticalLandmarksVisible = criticalVisible,
            criticalQuality = criticalQuality,
            requiredMeasurementsPresent = curlComponent != null && tipComponent != null && (!requireOpenThumb || (features.thumb.openScore != null && features.thumb.tipInsideIndexBoundaryRatio != null)),
            stepAllowsMatching = stepAllowsMatching,
            feedbackCode = feedback,
        )
    }
}

/** Closed fist plus forgiving thumb-across check; predicted thumb can support partial but not matching. */
private class ThumbOnTopStepVerifier(
    configuration: FindYourWeaponVerifierConfiguration,
) : BaseVerifier(configuration) {
    override val step: HandLessonStep = HandLessonStep.THUMB_ON_TOP
    private val closedVerifier = CloseFingersStepVerifier(configuration, requireOpenThumb = false)

    override fun verify(frame: TrackedHandFrame, features: HandFeatures): InstantStepResult {
        val closed = closedVerifier.verify(frame, features)
        val thumbTip = frame.landmarks[HandLandmarkId.THUMB_TIP]
        val thumbTipPresent = thumbTip?.position != null && thumbTip.source != LandmarkSource.MISSING
        val criticalQuality = min(criticalQuality(fourFingerIds, frame), criticalQuality(thumbCriticalIds, frame))
        val criticalVisible = closed.criticalLandmarksVisible && thumbTipPresent
        val thumbAcross = thumbAcrossAssessment(features)
        val score = if (thumbAcross.scoreComponent != null && thumbAcross.lateralAcross != null && thumbAcross.closedScore != null) {
            weighted(closed.score, thumbAcross.lateralAcross, (thumbAcross.scoreComponent + thumbAcross.closedScore) / 2f)
        } else {
            null
        }
        val thumbReliablyObserved = features.thumb.quality >= configuration.minimumCriticalLandmarkQuality &&
            thumbTip?.source != LandmarkSource.PREDICTED
        val stepAllowsMatching = closed.status == InstantVerificationStatus.MATCHING &&
            thumbReliablyObserved &&
            thumbAcross.isAcross &&
            thumbClosedEnough(features)
        val feedback = when {
            !closed.criticalLandmarksVisible -> FeedbackCode.CLOSE_FINGERS_MORE
            !thumbTipPresent -> FeedbackCode.INSUFFICIENT_VISIBILITY
            features.dataQuality < configuration.minimumOverallDataQuality -> FeedbackCode.HOLD_STILL
            !thumbAcross.isAcross -> FeedbackCode.MOVE_THUMB_ACROSS
            else -> FeedbackCode.GOOD
        }
        return result(
            step = step,
            score = score,
            quality = min(features.dataQuality, features.thumb.quality),
            criticalLandmarksVisible = criticalVisible,
            criticalQuality = criticalQuality,
            requiredMeasurementsPresent = thumbTipPresent && thumbAcross.scoreComponent != null && features.thumb.closedScore != null && features.thumb.tipInsideIndexBoundaryRatio != null,
            stepAllowsMatching = stepAllowsMatching,
            feedbackCode = feedback,
        )
    }
}

/** Front two knuckles are highlighted when usable; orientation uncertainty lowers quality, not safety. */
private class FrontTwoKnucklesStepVerifier(
    configuration: FindYourWeaponVerifierConfiguration,
) : BaseVerifier(configuration) {
    override val step: HandLessonStep = HandLessonStep.FRONT_TWO_KNUCKLES
    private val thumbVerifier = ThumbOnTopStepVerifier(configuration)

    override fun verify(frame: TrackedHandFrame, features: HandFeatures): InstantStepResult {
        val thumb = thumbVerifier.verify(frame, features)
        val usableHighlights = presentLandmarks(frontKnuckleIds, frame)
        val allKnucklesPresent = usableHighlights.containsAll(frontKnuckleIds)
        val criticalQuality = min(criticalQuality(fourFingerIds + thumbCriticalIds, frame), criticalQuality(frontKnuckleIds.toList(), frame))
        val criticalVisible = thumb.criticalLandmarksVisible && allKnucklesPresent && features.palmCoordinateSystem != null
        val orientation = features.palmCoordinateSystem?.zAxis?.let { abs(it.z) }
        val orientationComponent = orientation ?: configuration.fistOrientationTolerance
        val score = weighted(thumb.score, if (allKnucklesPresent) 1f else 0f, orientationComponent.coerceIn(0f, 1f))
        val orientationQuality = if (orientation == null) features.dataQuality * 0.8f else features.dataQuality
        val quality = min(thumb.quality, orientationQuality)
        val orientationAcceptable = orientation == null || orientation >= configuration.fistOrientationTolerance
        val stepAllowsMatching = thumb.status == InstantVerificationStatus.MATCHING && allKnucklesPresent && orientationAcceptable
        val feedback = when {
            !allKnucklesPresent -> FeedbackCode.INSUFFICIENT_VISIBILITY
            quality < configuration.minimumOverallDataQuality -> FeedbackCode.HOLD_STILL
            orientation != null && orientation < configuration.fistOrientationTolerance -> FeedbackCode.TURN_FIST_TOWARD_CAMERA
            thumb.feedbackCode != FeedbackCode.GOOD -> thumb.feedbackCode
            else -> FeedbackCode.GOOD
        }
        return result(
            step = step,
            score = score,
            quality = quality,
            criticalLandmarksVisible = criticalVisible,
            criticalQuality = criticalQuality,
            requiredMeasurementsPresent = features.palmCoordinateSystem != null && thumb.status != InstantVerificationStatus.INSUFFICIENT_DATA,
            stepAllowsMatching = stepAllowsMatching,
            feedbackCode = feedback,
            highlightLandmarks = usableHighlights,
        )
    }
}
