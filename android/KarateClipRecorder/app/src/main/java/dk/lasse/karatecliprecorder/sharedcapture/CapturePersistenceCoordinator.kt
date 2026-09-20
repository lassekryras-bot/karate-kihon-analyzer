package dk.lasse.karatecliprecorder.sharedcapture

import dk.lasse.karatecliprecorder.profile.BodyMeasurementSnapshot
import dk.lasse.karatecliprecorder.training.*
import java.io.File

class PreparedCapture internal constructor(
    val request: SharedCaptureRequest,
    val session: RecordingSession,
    val recording: MasterRecording,
    val file: File,
)

/** Blocking persistence boundary. Callers invoke it through TrainingServices.submit. */
class CapturePersistenceCoordinator(private val services: TrainingServices) {
    fun prepare(request: SharedCaptureRequest, userId: String, cameraProvenance: String?,
                snapshot: BodyMeasurementSnapshot? = null, legacyFile: File? = null): PreparedCapture {
        request.startBlock()?.let { error(it.message) }
        val sessionId = trainingId()
        val captureId = trainingId()
        val file = legacyFile ?: services.storage.resolve(services.storage.capture(captureId, request.captureType))
        val session = RecordingSession(
            sessionId = sessionId,
            userId = userId,
            startedAtMs = System.currentTimeMillis(),
            activityKey = request.activityContextId,
            guided = request.callerId != "record_and_analyze",
            expectedRepetitions = request.plannedRepetitions,
            state = SessionState.PREPARING,
            cadenceUs = request.cadenceMs?.times(1000),
            spokenCounting = request.spokenMovementCues,
            firstCueDelayUs = if (request.cueMode == CaptureCueMode.APP_CUED) 500_000 else null,
            expectedActivity = request.expectedActivity,
            expectedCategory = request.expectedCategory,
            callerId = request.callerId,
            parentId = request.parentId,
            captureTrigger = request.trigger.name,
            cueMode = request.cueMode?.name,
            requestedView = request.requestedView,
            completionPrompt = request.completionPrompt.name,
            audioCuePackageVersionId = if (request.spokenMovementCues) dk.lasse.karateanalyzer.audiocue.JapaneseCountAudioPackage.DEFAULT.packageVersionId else null,
        )
        val recording = MasterRecording(
            recordingId = captureId,
            sessionId = sessionId,
            filePath = if (legacyFile == null) services.storage.reference(file) else file.absolutePath,
            createdAtMs = session.startedAtMs,
            device = "${android.os.Build.MANUFACTURER}/${android.os.Build.MODEL}",
            camera = cameraProvenance,
            captureType = request.captureType,
            mimeType = if (request.captureType == CaptureType.VIDEO) "video/mp4" else "image/jpeg",
        )
        check(file.parentFile?.let { it.isDirectory || it.mkdirs() } != false) { "Capture storage unavailable" }
        check(!file.exists()) { "Capture filename already exists" }
        services.prepareRecording(session, recording, snapshot)
        return PreparedCapture(request, session, recording, file)
    }

    fun finalize(prepared: PreparedCapture, outcome: CaptureOutcome, durationUs: Long? = null,
                 width: Int? = null, height: Int? = null, frameRate: Double? = null,
                 failure: String? = null): PersistedCaptureResult {
        val structurallyUsable = when (prepared.request.captureType) {
            CaptureType.VIDEO -> (durationUs ?: 0L) > 0 && (width ?: 0) > 0 && (height ?: 0) > 0
            CaptureType.PHOTO -> (width ?: 0) > 0 && (height ?: 0) > 0
        }
        val usable = failure == null && prepared.file.isFile && prepared.file.length() > 0 && structurallyUsable
        if (usable && outcome in setOf(CaptureOutcome.INTERRUPTED, CaptureOutcome.FORCE_STOPPED)) {
            services.repository.recordBoundary(SessionEvent(
                sessionId = prepared.session.sessionId,
                type = "CAPTURE_OUTCOME",
                timestampUs = (durationUs ?: 0L).coerceAtLeast(0L),
                data = outcome.name.lowercase(),
                timingSource = "capture_result",
            ), outcome.name.lowercase())
        }
        services.repository.finishCapture(prepared.session.sessionId, durationUs ?: 0L,
            if (usable) null else failure ?: "Finalized media is unavailable", width, height, frameRate,
            if (usable) outcome else CaptureOutcome.FAILED)
        val result = PersistedCaptureResult(
            captureId = prepared.recording.recordingId,
            sessionId = prepared.session.sessionId,
            captureType = prepared.request.captureType,
            outcome = if (usable) outcome else CaptureOutcome.FAILED,
            mediaFinalized = usable,
            durationUs = durationUs,
            width = width,
            height = height,
            failureReason = if (usable) null else failure ?: "Finalized media is unavailable",
        )
        if (usable) CaptureFinalizedEvents.publish(result)
        return result
    }

    fun cancelPrepared(prepared: PreparedCapture, reason: String) {
        services.repository.finishCapture(prepared.session.sessionId, 0L, reason)
        services.repository.setSessionState(prepared.session.sessionId, SessionState.CANCELLED, reason)
    }
}

/** The recorder emits a durable-media event; it has no WorkManager/queue dependency. */
object CaptureFinalizedEvents {
    @Volatile var listener: ((PersistedCaptureResult) -> Unit)? = null
    fun publish(result: PersistedCaptureResult) { listener?.invoke(result) }
}
