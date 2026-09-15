package dk.lasse.karatecliprecorder.sharedcapture

/** Media and control are orthogonal so a caller can combine, for example, PHOTO + VOICE. */
enum class CaptureType { VIDEO, PHOTO }
enum class CaptureTrigger { STANDARD_TOUCH, VOICE, AUTOMATIC_EXTERNAL, CALLER_CONTROLLED }
enum class CaptureCueMode { APP_CUED, SELF_CUED, FREE_AUTO_COUNT }
enum class CaptureCamera { REAR, FRONT }
enum class CaptureCountdown { NONE, VISUAL_THREE_SECONDS }
enum class CaptureAutoStop { AFTER_FINAL_CUE, MANUAL }
enum class CapturePrompt {
    READY, START, FINISHED, SET_COMPLETE, ACTIVITY_COMPLETE, RECORDING_COMPLETE,
    FINISHING, STOPPED, RECORDING_INTERRUPTED, TRY_AGAIN,
}
enum class CaptureOutcome { COMPLETED, INTERRUPTED, FORCE_STOPPED, FAILED }
enum class CapabilityAccess { EDITABLE, LOCKED, HIDDEN }

data class CaptureQualityRequest(val resolution: String, val framesPerSecond: Int) {
    init { require(resolution.isNotBlank() && framesPerSecond > 0) }
}

sealed interface CaptureQualityPolicy {
    data object AutomaticFastMovement : CaptureQualityPolicy
    data class Exact(val value: CaptureQualityRequest) : CaptureQualityPolicy
}

/** What the host lets a user edit is deliberately separate from the configured values. */
data class CaptureUiCapabilityPolicy(
    val activityContext: CapabilityAccess = CapabilityAccess.EDITABLE,
    val plannedCount: CapabilityAccess = CapabilityAccess.EDITABLE,
    val cueMode: CapabilityAccess = CapabilityAccess.EDITABLE,
    val cadence: CapabilityAccess = CapabilityAccess.EDITABLE,
    val cameraAndLens: CapabilityAccess = CapabilityAccess.EDITABLE,
    val quality: CapabilityAccess = CapabilityAccess.EDITABLE,
    val audioRoute: CapabilityAccess = CapabilityAccess.EDITABLE,
    val defaultTrigger: CapabilityAccess = CapabilityAccess.EDITABLE,
)

data class SharedCaptureRequest(
    val captureType: CaptureType,
    val trigger: CaptureTrigger,
    val callerId: String,
    val parentId: String? = null,
    val activityContextId: String? = null,
    val expectedActivity: String? = null,
    val expectedCategory: String? = null,
    val plannedRepetitions: Int? = null,
    val cueMode: CaptureCueMode? = null,
    val cadenceMs: Long? = null,
    val requestedView: String? = null,
    val camera: CaptureCamera,
    val preferredLensId: String? = null,
    val preferredZoom: Float = 1f,
    val quality: CaptureQualityPolicy = CaptureQualityPolicy.AutomaticFastMovement,
    val countdown: CaptureCountdown = CaptureCountdown.VISUAL_THREE_SECONDS,
    val autoStop: CaptureAutoStop = CaptureAutoStop.AFTER_FINAL_CUE,
    val completionPrompt: CapturePrompt = CapturePrompt.FINISHED,
    val spokenMovementCues: Boolean = false,
    val operationalVoicePrompts: Boolean = true,
) {
    init {
        require(callerId.isNotBlank())
        require(plannedRepetitions == null || plannedRepetitions > 0)
        require(preferredZoom > 0f && preferredZoom.isFinite())
        require(cadenceMs == null || cadenceMs > 0)
        if (captureType == CaptureType.PHOTO) require(cueMode == null && plannedRepetitions == null && cadenceMs == null)
    }

    /** Reserved modes can be represented and persisted, but cannot begin hardware capture yet. */
    fun startBlock(): CaptureStartBlock? = when {
        cueMode == CaptureCueMode.SELF_CUED -> CaptureStartBlock.UNSUPPORTED_SELF_CUED
        cueMode == CaptureCueMode.FREE_AUTO_COUNT -> CaptureStartBlock.UNSUPPORTED_FREE_AUTO_COUNT
        captureType == CaptureType.VIDEO && cueMode == CaptureCueMode.APP_CUED && plannedRepetitions == null ->
            CaptureStartBlock.MISSING_PLANNED_COUNT
        captureType == CaptureType.VIDEO && cueMode == CaptureCueMode.APP_CUED && cadenceMs == null ->
            CaptureStartBlock.MISSING_CADENCE
        else -> null
    }
}

enum class CaptureStartBlock(val message: String) {
    UNSUPPORTED_SELF_CUED("Self-cued capture is not available yet."),
    UNSUPPORTED_FREE_AUTO_COUNT("Automatic movement counting is not available yet."),
    MISSING_PLANNED_COUNT("Select a planned repetition count."),
    MISSING_CADENCE("Select a cue cadence."),
}

data class PersistedCaptureResult(
    val captureId: String,
    val sessionId: String,
    val captureType: CaptureType,
    val outcome: CaptureOutcome,
    val mediaFinalized: Boolean,
    val durationUs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val failureReason: String? = null,
)

object SharedCaptureRequests {
    fun recordAndAnalyze(activity: String, category: String, repetitions: Int, cadenceMs: Long,
                         spokenCues: Boolean): SharedCaptureRequest = SharedCaptureRequest(
        captureType = CaptureType.VIDEO,
        trigger = CaptureTrigger.STANDARD_TOUCH,
        callerId = "record_and_analyze",
        activityContextId = "record_and_analyze_assisted_v1",
        expectedActivity = activity,
        expectedCategory = category,
        plannedRepetitions = repetitions,
        cueMode = CaptureCueMode.APP_CUED,
        cadenceMs = cadenceMs,
        requestedView = "operator_selected",
        camera = CaptureCamera.REAR,
        autoStop = CaptureAutoStop.AFTER_FINAL_CUE,
        spokenMovementCues = spokenCues,
        operationalVoicePrompts = true,
    )

    fun readyOsuSelfie(parentId: String = "karate_basics"): SharedCaptureRequest = SharedCaptureRequest(
        captureType = CaptureType.PHOTO,
        trigger = CaptureTrigger.VOICE,
        callerId = "learning_path",
        parentId = parentId,
        activityContextId = "ready-osu",
        expectedActivity = "Ready response selfie",
        expectedCategory = "Learning evidence",
        camera = CaptureCamera.FRONT,
        countdown = CaptureCountdown.NONE,
        autoStop = CaptureAutoStop.MANUAL,
        operationalVoicePrompts = true,
    )
}

object SharedCapturePolicies {
    val recordAndAnalyze = CaptureUiCapabilityPolicy()
    val structuredSelfie = CaptureUiCapabilityPolicy(
        activityContext = CapabilityAccess.HIDDEN,
        plannedCount = CapabilityAccess.HIDDEN,
        cueMode = CapabilityAccess.HIDDEN,
        cadence = CapabilityAccess.HIDDEN,
        cameraAndLens = CapabilityAccess.LOCKED,
        quality = CapabilityAccess.HIDDEN,
        audioRoute = CapabilityAccess.HIDDEN,
        defaultTrigger = CapabilityAccess.HIDDEN,
    )
}
