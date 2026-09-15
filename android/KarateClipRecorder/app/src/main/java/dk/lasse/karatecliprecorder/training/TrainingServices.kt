package dk.lasse.karatecliprecorder.training

import android.content.Context
import android.os.Handler
import android.os.Looper
import dk.lasse.karatecliprecorder.profile.BodyMeasurementSnapshot
import dk.lasse.karatecliprecorder.profile.Profile
import dk.lasse.karatecliprecorder.profile.Calibration
import dk.lasse.karatecliprecorder.mediapipeposeadapter.SequentialVideoPoseDecoder
import java.io.File
import java.util.concurrent.Executors
import dk.lasse.karatecliprecorder.sharedcapture.CaptureOutcome
import dk.lasse.karatecliprecorder.sharedcapture.CaptureType

/** Application-owned serial storage queue survives Activity disposal; hardware never starts from recovery. */
class TrainingServices private constructor(context: Context) {
    private val app = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val heavyExecutor = Executors.newSingleThreadExecutor()
    val processingExecutor = java.util.concurrent.Executor { command -> heavyExecutor.execute {
        ProcessingCoordinator.heavy.lock()
        try { command.run() } finally { ProcessingCoordinator.heavy.unlock() }
    } }
    private val main = Handler(Looper.getMainLooper())
    val storage = TrainingStorage(File(app.filesDir, "training"))
    val repository by lazy { TrainingRepository(KarateTrainingDatabase.get(app), storage) }
    private var recovered = false

    fun <T> submit(work: (TrainingRepository) -> T, completed: (Result<T>) -> Unit = {}) {
        executor.execute {
            val result = runCatching {
                if (!recovered) { recover(); recovered = true }
                work(repository)
            }
            main.post { completed(result) }
        }
    }

    private fun recover() {
        repository.recoverInterruptedWork().forEach { session ->
            val recording = repository.recording(session.sessionId) ?: return@forEach
            if (recording.sourceState != SourceState.PENDING) return@forEach
            try {
                recoverPendingCapture(repository, session, recording)
            } catch (error: Exception) {
                repository.finishCapture(session.sessionId, 0,
                    "interrupted ${recording.captureType.name.lowercase()} unavailable: ${error.message}")
            }
        }
    }

    fun prepareRecording(session: RecordingSession, recording: MasterRecording, snapshot: BodyMeasurementSnapshot?) {
        repository.createUser(TrainingUser(session.userId, session.startedAtMs))
        val ids = snapshot?.let { saveBodySnapshot(it) }.orEmpty()
        repository.beginSession(session, recording, ids)
    }

    fun saveBodySnapshot(snapshot: BodyMeasurementSnapshot): List<String> {
        repository.createUser(TrainingUser(snapshot.profileId, snapshot.capturedAtMs))
        val previous = repository.bodyMeasurements(snapshot.profileId)
        return listOf("height" to snapshot.heightCm, "forearm_length" to snapshot.forearmLengthCm,
            "lower_leg_length" to snapshot.lowerLegLengthCm).mapNotNull { (type, value) ->
            value ?: return@mapNotNull null
            previous.firstOrNull { it.type == type }?.takeIf { it.value == value.toDouble() }?.bodyMeasurementId
                ?: UserBodyMeasurement(userId = snapshot.profileId, type = type, value = value.toDouble(),
                    measuredAtMs = snapshot.capturedAtMs, source = "profile_manual_measurement").also(repository::addBodyMeasurement).bodyMeasurementId
        }
    }

    fun archiveProfile(profile: Profile, calibrations: List<Calibration> = emptyList()) {
        submit({ repository ->
            repository.createUser(TrainingUser(profile.id, profile.createdAt))
            saveBodySnapshot(BodyMeasurementSnapshot.from(profile, profile.updatedAt))
            val existing = repository.calibrations(profile.id).map { it.calibrationId }.toSet()
            calibrations.forEach { calibration ->
                val identity = "${calibration.id}:${calibration.updatedAt}:${calibration.payload}"
                val id = java.util.UUID.nameUUIDFromBytes(identity.toByteArray(Charsets.UTF_8)).toString()
                if (id !in existing) repository.addCalibration(UserCalibration(id, profile.id,
                    calibration.calibrationType, calibration.updatedAt, "legacy_payload_v1", "profile_calibration",
                    "legacy_quality_unspecified", calibration.payload))
            }
        }) { result -> result.exceptionOrNull()?.let { android.util.Log.e("TrainingStorage", "Profile evidence archive failed", it) } }
    }

    /** Call on a worker. Completed MediaPipe files can be reused even if the MP4 has been removed. */
    fun processor(checkActive: () -> Unit = {}, publication: (() -> Unit) -> Unit = { it() }): TrainingSessionProcessor {
        val hash = app.assets.open("mediapipe/pose_landmarker_full.task").use(LandmarkFiles::sha256)
        return TrainingSessionProcessor(repository, File(app.filesDir, "training/landmarks"),
            SequentialVideoPoseDecoder(app), "pose_full_sha256:$hash;decoder=1;tasks=0.10.26;CPU;settings=1", checkActive, publication)
    }

    companion object {
        @Volatile private var instance: TrainingServices? = null
        internal fun closeForTests() = synchronized(this) {
            instance?.heavyExecutor?.shutdown()
            instance?.executor?.shutdown()
            check(instance?.executor?.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS) != false)
            instance = null
            KarateTrainingDatabase.closeForTests()
        }
        fun get(context: Context): TrainingServices = instance ?: synchronized(this) {
            instance ?: TrainingServices(context).also { instance = it }
        }
    }
}

internal fun recoverPendingCapture(repository: TrainingRepository, session: RecordingSession,
                                   recording: MasterRecording) {
    val file = repository.file(recording.filePath)
    val interruption = "process_recovered_after_capture_interruption"
    when (recording.captureType) {
        CaptureType.VIDEO -> {
            val metadata = RecordedVideoMetadata.read(file)
            repository.recordBoundary(SessionEvent(sessionId = session.sessionId,
                type = "INTERRUPTION_DETECTED", timestampUs = metadata.durationUs,
                data = interruption, timingSource = "recovered_media_duration"), interruption)
            repository.finishCapture(session.sessionId, metadata.durationUs,
                width = metadata.width, height = metadata.height, frameRate = metadata.frameRate,
                outcome = CaptureOutcome.INTERRUPTED)
        }
        CaptureType.PHOTO -> {
            check(file.isFile && file.length() > 0) { "photo unavailable" }
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
            check(bounds.outWidth > 0 && bounds.outHeight > 0) { "photo unreadable" }
            repository.recordBoundary(SessionEvent(sessionId = session.sessionId,
                type = "INTERRUPTION_DETECTED", timestampUs = 0,
                data = interruption, timingSource = "capture_recovery"), interruption)
            repository.finishCapture(session.sessionId, 0, width = bounds.outWidth,
                height = bounds.outHeight, outcome = CaptureOutcome.INTERRUPTED)
        }
    }
}
