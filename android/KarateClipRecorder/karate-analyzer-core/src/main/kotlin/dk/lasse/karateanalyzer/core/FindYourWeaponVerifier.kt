package dk.lasse.karateanalyzer.core

import kotlin.math.abs

/** The five analyzer-neutral Find Your Weapon lesson shapes, evaluated one frame at a time. */
enum class HandLessonStep { OPEN_PALM, BEND_FINGERTIPS, CLOSE_FINGERS, THUMB_ON_TOP, FRONT_TWO_KNUCKLES }

enum class InstantVerificationStatus { INSUFFICIENT_DATA, NOT_MATCHING, PARTIAL_MATCH, MATCHING }

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
data class StepScoreWeights(val primary: Float = 0.55f, val secondary: Float = 0.25f, val consistency: Float = 0.20f)

/**
 * Thresholds for single-frame verification. These defaults are intentionally broad heuristics until
 * tuned with real MediaPipe captures. Quality thresholds describe landmark provenance confidence:
 * OBSERVED contributes fully, INTERPOLATED partially, PREDICTED weakly, and MISSING not at all.
 * Score thresholds map continuous 0..1 component scores to neutral statuses; temporal acceptance,
 * hold timing, automatic progression, guide alignment, and UI strings are deliberately excluded.
 */
data class FindYourWeaponVerifierConfiguration(
    val minimumOverallDataQuality: Float = 0.55f,
    val minimumCriticalLandmarkQuality: Float = 0.70f,
    val minimumMatchingReliableCriticalQuality: Float = 0.70f,
    val matchingScoreThreshold: Float = 0.78f,
    val partialMatchScoreThreshold: Float = 0.45f,
    val openPalmExtensionThreshold: Float = 0.78f,
    val fingertipBendCurlRange: ClosedFloatingPointRange<Float> = 0.30f..0.68f,
    val fingertipBendTipToPalmRatioRange: ClosedFloatingPointRange<Float> = 0.45f..1.55f,
    val closedFingerCurlThreshold: Float = 0.74f,
    val closedTipToPalmRatioThreshold: Float = 0.85f,
    val thumbAcrossMaxKnuckleDistance: Float = 0.90f,
    val thumbFarOutsidePalmRatio: Float = 1.70f,
    val fistOrientationTolerance: Float = 0.55f,
    val minimumVisibleFingerCount: Int = 4,
    val scoreWeights: StepScoreWeights = StepScoreWeights(),
)

interface HandStepVerifier {
    val step: HandLessonStep
    fun verify(frame: TrackedHandFrame, features: HandFeatures): InstantStepResult
}

class FindYourWeaponVerifier(private val configuration: FindYourWeaponVerifierConfiguration = FindYourWeaponVerifierConfiguration()) {
    private val verifiers = listOf(OpenPalmStepVerifier(configuration), BendFingertipsStepVerifier(configuration), CloseFingersStepVerifier(configuration), ThumbOnTopStepVerifier(configuration), FrontTwoKnucklesStepVerifier(configuration)).associateBy { it.step }
    fun verify(step: HandLessonStep, frame: TrackedHandFrame, features: HandFeatures): InstantStepResult = verifiers.getValue(step).verify(frame, features)
}

private abstract class BaseVerifier(protected val c: FindYourWeaponVerifierConfiguration) : HandStepVerifier {
    protected val fingers: List<(HandFeatures) -> FingerFeatures> = listOf({ it.index }, { it.middle }, { it.ring }, { it.little })
    protected fun status(score: Float, critical: Boolean, matchAllowed: Boolean = true) = when {
        !critical -> InstantVerificationStatus.INSUFFICIENT_DATA
        score < c.partialMatchScoreThreshold -> InstantVerificationStatus.NOT_MATCHING
        score >= c.matchingScoreThreshold && matchAllowed -> InstantVerificationStatus.MATCHING
        else -> InstantVerificationStatus.PARTIAL_MATCH
    }
    protected fun result(step: HandLessonStep, score: Float?, quality: Float, critical: Boolean, feedback: FeedbackCode, highlights: Set<HandLandmarkId> = emptySet(), matchAllowed: Boolean = true) =
        InstantStepResult(step, status(safe(score), critical && score != null, matchAllowed), safe(score), safe(quality), feedback, critical, highlights)
    protected fun safe(v: Float?) = v?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    protected fun mean(xs: List<Float>) = if (xs.isEmpty()) null else xs.average().toFloat()
    protected fun usableFingers(f: HandFeatures) = fingers.map { it(f) }.filter { it.quality >= c.minimumCriticalLandmarkQuality && it.curlScore != null }
    protected fun criticalQuality(ids: List<HandLandmarkId>, frame: TrackedHandFrame): Float = ids.map { sourceWeight(frame.landmarks[it]?.source ?: LandmarkSource.MISSING) }.average().toFloat().coerceIn(0f, 1f)
    protected fun sourceWeight(s: LandmarkSource) = when (s) { LandmarkSource.OBSERVED -> 1f; LandmarkSource.INTERPOLATED -> .75f; LandmarkSource.PREDICTED -> .35f; LandmarkSource.MISSING -> 0f }
    protected fun allPresent(ids: List<HandLandmarkId>, frame: TrackedHandFrame) = ids.all { frame.landmarks[it]?.position != null && frame.landmarks[it]?.source != LandmarkSource.MISSING }
    protected fun consistency(values: List<Float>): Float { val m = mean(values) ?: return 0f; return (1f - (values.maxOf { abs(it - m) })).coerceIn(0f, 1f) }
    protected fun weighted(a: Float, b: Float, d: Float): Float { val w=c.scoreWeights; return ((a*w.primary + b*w.secondary + d*w.consistency)/(w.primary+w.secondary+w.consistency)).coerceIn(0f,1f) }
}

private val fourFingerIds = listOf(HandLandmarkId.INDEX_MCP,HandLandmarkId.INDEX_PIP,HandLandmarkId.INDEX_DIP,HandLandmarkId.INDEX_TIP,HandLandmarkId.MIDDLE_MCP,HandLandmarkId.MIDDLE_PIP,HandLandmarkId.MIDDLE_DIP,HandLandmarkId.MIDDLE_TIP,HandLandmarkId.RING_MCP,HandLandmarkId.RING_PIP,HandLandmarkId.RING_DIP,HandLandmarkId.RING_TIP,HandLandmarkId.LITTLE_MCP,HandLandmarkId.LITTLE_PIP,HandLandmarkId.LITTLE_DIP,HandLandmarkId.LITTLE_TIP)

/** Critical landmarks: wrist and four finger chains. Components: extension, low curl, consistency. */
class OpenPalmStepVerifier(c: FindYourWeaponVerifierConfiguration = FindYourWeaponVerifierConfiguration()) : BaseVerifier(c) { override val step=HandLessonStep.OPEN_PALM; override fun verify(frame: TrackedHandFrame, features: HandFeatures): InstantStepResult { val ids=listOf(HandLandmarkId.WRIST)+fourFingerIds; val u=usableFingers(features); val critical=features.palmCoordinateSystem!=null && allPresent(listOf(HandLandmarkId.WRIST),frame) && u.size>=c.minimumVisibleFingerCount && criticalQuality(ids,frame)>=c.minimumCriticalLandmarkQuality; val ex=u.mapNotNull{it.extensionScore}; val score=features.openPalmScore?.let{ weighted(it, 1f-safe(features.fourFingerCurlScore), consistency(ex))}; val fb=when{!critical->FeedbackCode.INSUFFICIENT_VISIBILITY; features.dataQuality<c.minimumOverallDataQuality->FeedbackCode.HOLD_STILL; safe(features.fourFingerCurlScore)>.35f->FeedbackCode.OPEN_FINGERS; else->FeedbackCode.GOOD}; return result(step,score,features.dataQuality,critical,fb, matchAllowed=criticalQuality(ids,frame)>=c.minimumMatchingReliableCriticalQuality) } }

/** Intermediate PIP/DIP flexion; not temporal and not calibrated per user. Components: range fit, tip distance, evenness. */
class BendFingertipsStepVerifier(c: FindYourWeaponVerifierConfiguration = FindYourWeaponVerifierConfiguration()) : BaseVerifier(c) { override val step=HandLessonStep.BEND_FINGERTIPS; override fun verify(frame: TrackedHandFrame, features: HandFeatures): InstantStepResult { val u=usableFingers(features); val critical=u.size>=c.minimumVisibleFingerCount && criticalQuality(fourFingerIds,frame)>=c.minimumCriticalLandmarkQuality; val curls=u.mapNotNull{it.curlScore}; val inRange=curls.map{ if(it in c.fingertipBendCurlRange) 1f else 1f- kotlin.math.min(abs(it-c.fingertipBendCurlRange.start), abs(it-c.fingertipBendCurlRange.endInclusive)).coerceIn(0f,1f)}; val ratios=u.mapNotNull{it.tipToPalmRatio}; val ratioScore=mean(ratios.map{ if(it in c.fingertipBendTipToPalmRatioRange) 1f else .4f }) ?: 0f; val score=weighted(mean(inRange)?:0f, ratioScore, consistency(curls)); val fb=when{!critical->FeedbackCode.INSUFFICIENT_VISIBILITY; features.dataQuality<c.minimumOverallDataQuality->FeedbackCode.HOLD_STILL; consistency(curls)<.75f->FeedbackCode.FINGERS_UNEVEN; (mean(curls)?:0f)<c.fingertipBendCurlRange.start->FeedbackCode.BEND_FINGERTIPS_MORE; (mean(curls)?:1f)>c.fingertipBendCurlRange.endInclusive->FeedbackCode.DO_NOT_CLOSE_YET; else->FeedbackCode.GOOD}; return result(step,score,features.dataQuality,critical,fb) } }

/** Closed fingers; thumb landmarks are deliberately non-critical. Components: curl, low tip-to-palm ratios, evenness. */
class CloseFingersStepVerifier(c: FindYourWeaponVerifierConfiguration = FindYourWeaponVerifierConfiguration()) : BaseVerifier(c) { override val step=HandLessonStep.CLOSE_FINGERS; override fun verify(frame: TrackedHandFrame, features: HandFeatures): InstantStepResult { val u=usableFingers(features); val critical=u.size>=c.minimumVisibleFingerCount; val curls=u.mapNotNull{it.curlScore}; val curlScore=mean(curls.map{ it/c.closedFingerCurlThreshold })?.coerceIn(0f,1f)?:0f; val tip=mean(u.mapNotNull{it.tipToPalmRatio}.map{1f-(it/c.closedTipToPalmRatioThreshold).coerceIn(0f,1f)})?:0f; val score=weighted(curlScore,tip,consistency(curls)); val fb=when{!critical->FeedbackCode.INSUFFICIENT_VISIBILITY; features.dataQuality<c.minimumOverallDataQuality->FeedbackCode.HOLD_STILL; curlScore<c.partialMatchScoreThreshold->FeedbackCode.CLOSE_FINGERS_MORE; consistency(curls)<.75f->FeedbackCode.FINGERS_UNEVEN; else->FeedbackCode.GOOD}; return result(step,score,features.dataQuality,critical,fb) } }

/** Closed fist plus forgiving thumb-across check; predicted thumb can support partial but not matching. */
class ThumbOnTopStepVerifier(c: FindYourWeaponVerifierConfiguration = FindYourWeaponVerifierConfiguration()) : BaseVerifier(c) { override val step=HandLessonStep.THUMB_ON_TOP; override fun verify(frame: TrackedHandFrame, features: HandFeatures): InstantStepResult { val closed=CloseFingersStepVerifier(c).verify(frame,features); val tip=frame.landmarks[HandLandmarkId.THUMB_TIP]; val thumbPresent=tip?.position!=null && tip.source!=LandmarkSource.MISSING; val reliable=features.thumb.quality>=c.minimumCriticalLandmarkQuality && tip?.source!=LandmarkSource.PREDICTED; val critical=closed.criticalLandmarksVisible && thumbPresent; val across=if(features.thumb.crossesPalmAxis==true)1f else 0f; val close=1f-((listOfNotNull(features.thumb.tipToIndexMcpRatio,features.thumb.tipToMiddleMcpRatio).minOrNull() ?: c.thumbFarOutsidePalmRatio)/c.thumbAcrossMaxKnuckleDistance).coerceIn(0f,1f); val inside=1f-((features.thumb.tipToPalmRatio ?: c.thumbFarOutsidePalmRatio)/c.thumbFarOutsidePalmRatio).coerceIn(0f,1f); val score=weighted(safe(closed.score), (across+close)/2f, inside); val fb=when{!closed.criticalLandmarksVisible->FeedbackCode.CLOSE_FINGERS_MORE; !thumbPresent->FeedbackCode.MOVE_THUMB_ACROSS; features.dataQuality<c.minimumOverallDataQuality->FeedbackCode.HOLD_STILL; across<1f||close<.35f->FeedbackCode.MOVE_THUMB_ACROSS; else->FeedbackCode.GOOD}; return result(step,score, kotlin.math.min(features.dataQuality, features.thumb.quality), critical, fb, matchAllowed=reliable) } }

/** Front two knuckles only verifies available frame geometry; no perfect striking-surface claim. */
class FrontTwoKnucklesStepVerifier(c: FindYourWeaponVerifierConfiguration = FindYourWeaponVerifierConfiguration()) : BaseVerifier(c) { override val step=HandLessonStep.FRONT_TWO_KNUCKLES; override fun verify(frame: TrackedHandFrame, features: HandFeatures): InstantStepResult { val hi=setOf(HandLandmarkId.INDEX_MCP,HandLandmarkId.MIDDLE_MCP); val closed=CloseFingersStepVerifier(c).verify(frame,features); val knuckles=allPresent(hi.toList(),frame); val critical=closed.criticalLandmarksVisible && knuckles && features.palmCoordinateSystem!=null; val orientation=features.palmCoordinateSystem?.zAxis?.let{ abs(it.z) } ; val orientQuality=orientation ?: c.fistOrientationTolerance; val score=weighted(safe(closed.score), if(knuckles)1f else 0f, orientQuality.coerceIn(0f,1f)); val quality=if(orientation==null) features.dataQuality*.8f else features.dataQuality; val fb=when{!closed.criticalLandmarksVisible->FeedbackCode.CLOSE_FINGERS_MORE; !knuckles->FeedbackCode.INSUFFICIENT_VISIBILITY; quality<c.minimumOverallDataQuality->FeedbackCode.HOLD_STILL; orientation!=null && orientation<c.fistOrientationTolerance->FeedbackCode.TURN_FIST_TOWARD_CAMERA; else->FeedbackCode.GOOD}; return result(step,score,quality,critical,fb,hi) } }
