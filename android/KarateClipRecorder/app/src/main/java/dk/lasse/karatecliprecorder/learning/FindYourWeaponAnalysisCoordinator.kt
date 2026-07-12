package dk.lasse.karatecliprecorder.learning

import dk.lasse.karateanalyzer.core.FeedbackCode
import dk.lasse.karateanalyzer.core.FindYourWeaponTemporalVerifier
import dk.lasse.karateanalyzer.core.FindYourWeaponVerifier
import dk.lasse.karateanalyzer.core.HandFeatureExtractor
import dk.lasse.karateanalyzer.core.HandFrame
import dk.lasse.karateanalyzer.core.HandLandmarkId
import dk.lasse.karateanalyzer.core.HandLessonStep
import dk.lasse.karateanalyzer.core.Handedness
import dk.lasse.karateanalyzer.core.InstantStepResult
import dk.lasse.karateanalyzer.core.LandmarkSample
import dk.lasse.karateanalyzer.core.LandmarkSource
import dk.lasse.karateanalyzer.core.Point3
import dk.lasse.karateanalyzer.core.TemporalHandTracker
import dk.lasse.karateanalyzer.core.TemporalStepResult
import dk.lasse.karateanalyzer.core.TrackedHandFrame
import dk.lasse.karatecliprecorder.mediapipehandadapter.HighestConfidenceActiveHandSelector
import dk.lasse.karatecliprecorder.mediapipehandadapter.LiveGestureRecognizerOutput
import dk.lasse.karatecliprecorder.mediapipehandadapter.MediaPipeHandFrameMapper
import dk.lasse.karatecliprecorder.mediapipehandadapter.RecognizerLifecycleState


data class FindYourWeaponDebugOverlay(
    val inputWidth: Int,
    val inputHeight: Int,
    val boundaryStartX: Float,
    val boundaryStartY: Float,
    val boundaryEndX: Float,
    val boundaryEndY: Float,
    val thumbTipX: Float?,
    val thumbTipY: Float?,
    val thumbInsideBoundary: Boolean?,
)

data class FindYourWeaponAnalysisState(
    val activeStep: FindYourWeaponStep?,
    val timestampMs: Long?,
    val handDetected: Boolean,
    val handedness: Handedness,
    val instantResult: InstantStepResult?,
    val temporalResult: TemporalStepResult?,
    val openPalmGestureScore: Float?,
    val closedFistGestureScore: Float?,
    val inferenceLatencyMs: Long?,
    val thumbOpenScore: Float? = null,
    val thumbClosedScore: Float? = null,
    val thumbFingerDistanceRatio: Float? = null,
    val thumbInsideBoundaryRatio: Float? = null,
    val thumbInsideBoundary: Boolean? = null,
    val debugOverlay: FindYourWeaponDebugOverlay? = null,
    val errorMessage: String? = null,
    val recognizerState: RecognizerLifecycleState = RecognizerLifecycleState.INACTIVE,
)

class FindYourWeaponAnalysisCoordinator(
    private val onStateChanged: (FindYourWeaponAnalysisState) -> Unit,
) {
    private val mapper = MediaPipeHandFrameMapper()
    private val selector = HighestConfidenceActiveHandSelector()
    private val tracker = TemporalHandTracker()
    private val extractor = HandFeatureExtractor()
    private val verifier = FindYourWeaponVerifier()
    private val temporalVerifier = FindYourWeaponTemporalVerifier()
    private var activeStep: FindYourWeaponStep? = null
    private var retainedHandedness = Handedness.UNKNOWN
    private var latestTimestampMs: Long? = null
    private val lock = Any()
    private var generationToken: Long = 0L

    fun currentGenerationToken(): Long = synchronized(lock) { generationToken }

    fun setActiveStep(step: FindYourWeaponStep?) = synchronized(lock) {
        if (activeStep != step) {
            generationToken++
            activeStep = step
            tracker.reset()
            temporalVerifier.reset()
            retainedHandedness = Handedness.UNKNOWN
        }
    }

    fun process(output: LiveGestureRecognizerOutput) = synchronized(lock) {
        if (output.generationToken != generationToken) return
        val previous = latestTimestampMs
        if (previous != null && output.timestampMs <= previous) return
        latestTimestampMs = output.timestampMs
        val step = activeStep ?: return emit(output, false, retainedHandedness, null, null, null, null)
        val lessonStep = step.toHandLessonStep()
        val hands = mapper.toDetectedHands(output.observations)
        val selected = selector.select(hands)
        val observedFrame = selected?.let { mapper.toHandFrame(it) }
        if (observedFrame != null && observedFrame.handedness != Handedness.UNKNOWN) {
            retainedHandedness = observedFrame.handedness
        }
        val frame = observedFrame ?: missingFrame(output.timestampMs, retainedHandedness)
        val tracked = tracker.track(frame)
        val features = extractor.extract(tracked)
        val instant = verifier.verify(lessonStep, tracked, features).finite()
        val temporal = temporalVerifier.update(tracked, instant).finite()
        emit(
            output = output,
            handDetected = observedFrame != null,
            handedness = tracked.handedness,
            instant = instant,
            temporal = temporal,
            openPalm = selected?.openPalmScore,
            closedFist = selected?.closedFistScore,
            thumbOpen = features.thumb.openScore,
            thumbClosed = features.thumb.closedScore,
            thumbDistance = features.thumb.weightedFingerDistanceRatio,
            thumbInsideBoundaryRatio = features.thumb.tipInsideIndexBoundaryRatio,
            thumbInsideBoundary = features.thumb.tipInsideIndexBoundary,
            debugOverlay = debugOverlay(output, tracked, features.thumb.indexBoundaryLine, features.thumb.tipInsideIndexBoundary),
        )
    }

    fun reset() = synchronized(lock) {
        generationToken++
        activeStep = null
        retainedHandedness = Handedness.UNKNOWN
        latestTimestampMs = null
        tracker.reset()
        temporalVerifier.reset()
        onStateChanged(FindYourWeaponAnalysisState(null, null, false, Handedness.UNKNOWN, null, null, null, null, null))
    }

    fun reportError(message: String, recognizerState: RecognizerLifecycleState = RecognizerLifecycleState.FAILED) = synchronized(lock) {
        onStateChanged(
            FindYourWeaponAnalysisState(
                activeStep = activeStep,
                timestampMs = latestTimestampMs,
                handDetected = false,
                handedness = retainedHandedness,
                instantResult = null,
                temporalResult = null,
                openPalmGestureScore = null,
                closedFistGestureScore = null,
                inferenceLatencyMs = null,
                errorMessage = message,
                recognizerState = recognizerState,
            ),
        )
    }

    private fun emit(
        output: LiveGestureRecognizerOutput,
        handDetected: Boolean,
        handedness: Handedness,
        instant: InstantStepResult?,
        temporal: TemporalStepResult?,
        openPalm: Float?,
        closedFist: Float?,
        thumbOpen: Float? = null,
        thumbClosed: Float? = null,
        thumbDistance: Float? = null,
        thumbInsideBoundaryRatio: Float? = null,
        thumbInsideBoundary: Boolean? = null,
        debugOverlay: FindYourWeaponDebugOverlay? = null,
    ) {
        onStateChanged(
            FindYourWeaponAnalysisState(
                activeStep,
                output.timestampMs,
                handDetected,
                handedness,
                instant,
                temporal,
                openPalm?.finiteUnit(),
                closedFist?.finiteUnit(),
                output.inferenceLatencyMs,
                thumbOpen?.finiteUnit(),
                thumbClosed?.finiteUnit(),
                thumbDistance?.takeIf { it.isFinite() },
                thumbInsideBoundaryRatio?.takeIf { it.isFinite() },
                thumbInsideBoundary,
                debugOverlay,
            ),
        )
    }

    private fun debugOverlay(
        output: LiveGestureRecognizerOutput,
        frame: TrackedHandFrame,
        boundaryLine: dk.lasse.karateanalyzer.core.ThumbBoundaryLine?,
        thumbInsideBoundary: Boolean?,
    ): FindYourWeaponDebugOverlay? {
        if (output.inputWidth <= 0 || output.inputHeight <= 0) return null
        val line = boundaryLine ?: return null
        val start = line.start.takeIf { it.hasFiniteImageCoordinates() } ?: return null
        val end = line.end.takeIf { it.hasFiniteImageCoordinates() } ?: return null
        val thumbTip = frame.landmarks[HandLandmarkId.THUMB_TIP]?.position?.takeIf { it.hasFiniteImageCoordinates() }
        return FindYourWeaponDebugOverlay(
            inputWidth = output.inputWidth,
            inputHeight = output.inputHeight,
            boundaryStartX = start.x,
            boundaryStartY = start.y,
            boundaryEndX = end.x,
            boundaryEndY = end.y,
            thumbTipX = thumbTip?.x,
            thumbTipY = thumbTip?.y,
            thumbInsideBoundary = thumbInsideBoundary,
        )
    }

    private fun missingFrame(timestampMs: Long, handedness: Handedness) = HandFrame(
        timestampMs = timestampMs,
        handedness = handedness,
        landmarks = HandLandmarkId.entries.associateWith { LandmarkSample(null, 0f, LandmarkSource.MISSING) },
    )

    companion object {
        fun FindYourWeaponStep.toHandLessonStep(): HandLessonStep = when (this) {
            FindYourWeaponStep.OPEN_PALM -> HandLessonStep.OPEN_PALM
            FindYourWeaponStep.BEND_FINGERTIPS -> HandLessonStep.BEND_FINGERTIPS
            FindYourWeaponStep.CLOSE_FINGERS -> HandLessonStep.CLOSE_FINGERS
            FindYourWeaponStep.THUMB_ON_TOP -> HandLessonStep.THUMB_ON_TOP
            FindYourWeaponStep.FRONT_TWO_KNUCKLES -> HandLessonStep.FRONT_TWO_KNUCKLES
        }
    }
}

private fun Float.finiteUnit(): Float? = takeIf { it.isFinite() }?.coerceIn(0f, 1f)
private fun Point3.hasFiniteImageCoordinates(): Boolean = x.isFinite() && y.isFinite()
private fun InstantStepResult.finite() = copy(score = score.finiteUnit() ?: 0f, quality = quality.finiteUnit() ?: 0f)
private fun TemporalStepResult.finite() = copy(
    progress = progress.finiteUnit() ?: 0f,
    accumulatedMatchingMs = accumulatedMatchingMs.takeIf { it.isFinite() } ?: 0.0,
    reliableMatchingMs = reliableMatchingMs.takeIf { it.isFinite() } ?: 0.0,
    reliableHoldCreditMs = reliableHoldCreditMs.takeIf { it.isFinite() } ?: 0.0,
    weightedReliableRatio = weightedReliableRatio.takeIf { it.isFinite() } ?: 0.0,
)
