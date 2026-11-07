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

    fun offlinePoseConfig(): dk.lasse.karatecliprecorder.mediapipeposeadapter.PoseLandmarkerConfig {
        return dk.lasse.karatecliprecorder.mediapipeposeadapter.PoseLandmarkerConfig.offlineDefault { path ->
            runCatching { app.assets.open(path).use { }; true }.getOrDefault(false)
        }
    }

    /** Call on a worker. Completed MediaPipe files can be reused even if the MP4 has been removed. */
    fun processor(checkActive: () -> Unit = {}, publication: (() -> Unit) -> Unit = { it() }): TrainingSessionProcessor {
        val config = offlinePoseConfig()
        val hash = app.assets.open(config.assetPath).use(LandmarkFiles::sha256)
        val pipelineIdentity = config.pipelineIdentity(hash)
        val trackConfig = config.trackConfiguration()
        return TrainingSessionProcessor(
            repository,
            File(app.filesDir, "training/landmarks"),
            SequentialVideoPoseDecoder(app, config.delegate, config.assetPath),
            pipelineIdentity,
            checkActive,
            publication,
            trackConfiguration = trackConfig,
        )
    }

    fun reanalyze(sessionId: String, completed: (Result<ProcessingRun>) -> Unit = {}) {
        processingExecutor.execute {
            val result = runCatching {
                val (run, _) = repository.prepareReanalysisRun(sessionId)
                val proc = processor(
                    checkActive = {
                        val currentJob = repository.job(sessionId)
                        check(currentJob == null || currentJob.state != QueueState.DELETING) { "Cancelled" }
                    },
                    publication = { op ->
                        synchronized(ProcessingCoordinator.publication) { op() }
                    }
                )
                proc.processReanalysis(sessionId, run.runId)
                requireNotNull(repository.run(run.runId))
            }
            main.post { completed(result) }
        }
    }

    fun confirmActivityPlanAndReanalyze(
        sessionId: String,
        planKey: String,
        completed: (Result<ProcessingRun>) -> Unit = {},
    ) {
        processingExecutor.execute {
            val result = runCatching {
                val (run, _) = repository.confirmActivityPlanAndPrepareReanalysis(sessionId, planKey)
                val proc = processor(
                    checkActive = {
                        val currentJob = repository.job(sessionId)
                        check(currentJob == null || currentJob.state != QueueState.DELETING) { "Cancelled" }
                    },
                    publication = { op -> synchronized(ProcessingCoordinator.publication) { op() } },
                )
                proc.processReanalysis(sessionId, run.runId)
                requireNotNull(repository.run(run.runId))
            }
            main.post { completed(result) }
        }
    }

    fun reprocessLandmarksAndReanalyze(sessionId: String, completed: (Result<ProcessingRun>) -> Unit = {}) {
        processingExecutor.execute {
            val result = runCatching {
                val run = repository.prepareLandmarkReprocessRun(sessionId)
                val proc = processor(
                    checkActive = {
                        val currentJob = repository.job(sessionId)
                        check(currentJob == null || currentJob.state != QueueState.DELETING) { "Cancelled" }
                    },
                    publication = { op ->
                        synchronized(ProcessingCoordinator.publication) { op() }
                    }
                )
                proc.processLandmarkReprocess(sessionId, run.runId)
                requireNotNull(repository.run(run.runId))
            }
            main.post { completed(result) }
        }
    }

    /** Developer-only isolated prototype. Does not create or update production Room records. */
    fun runLiteHeavyPrototype(
        sessionId: String,
        completed: (Result<LiteHeavyPrototypeRunResult>) -> Unit = {},
    ) {
        processingExecutor.execute {
            val result = runCatching {
                val invocation = prototypeInvocation(sessionId)
                invocation.runner.run(invocation.recordingId, invocation.video, invocation.sourceDurationMs)
            }
            main.post { completed(result) }
        }
    }

    fun runFullHeavyComparator(
        sessionId: String,
        completed: (Result<FullHeavyComparatorRunResult>) -> Unit = {},
    ) {
        processingExecutor.execute {
            val result = runCatching {
                val invocation = prototypeInvocation(sessionId)
                invocation.runner.runFullHeavyComparator(
                    invocation.recordingId,
                    invocation.video,
                    invocation.sourceDurationMs,
                )
            }
            main.post { completed(result) }
        }
    }

    private data class PrototypeInvocation(
        val runner: LiteHeavyPrototypeRunner,
        val recordingId: String,
        val video: File,
        val sourceDurationMs: Long,
    )

    private fun prototypeInvocation(sessionId: String): PrototypeInvocation {
        val session = requireNotNull(repository.session(sessionId))
        val recording = requireNotNull(repository.recording(sessionId))
        check(recording.sourceState == SourceState.AVAILABLE) { "Original video is unavailable" }
        val video = repository.file(recording.filePath)
        check(video.isFile) { "Original video is unavailable" }
        val sourceDurationMs = requireNotNull(recording.durationUs) { "Recording duration is unavailable" } / 1_000L
        check(sourceDurationMs > 0) { "Recording duration is unavailable" }
        val liteConfig = dk.lasse.karatecliprecorder.mediapipeposeadapter.PoseLandmarkerConfig.litePrototype()
        val heavyConfig = offlinePoseConfig()
        check(heavyConfig.variant == dk.lasse.karatecliprecorder.mediapipeposeadapter.PoseLandmarkerVariant.HEAVY)
        fun identity(config: dk.lasse.karatecliprecorder.mediapipeposeadapter.PoseLandmarkerConfig) =
            PrototypeModelAssetIdentity(
                modelKey = config.variant.variantKey,
                sha256 = app.assets.open(config.assetPath).use(LandmarkFiles::sha256),
                pipelineConfiguration = config.trackConfiguration(),
            )
        val geometry = CanonicalGeometryStorageAdapter.resolveTrackGeometry(
            recording = recording,
            track = null,
            fileResolver = { repository.file(it) },
        )
        check(geometry.isAvailable) { "Canonical recording geometry is unavailable" }
        val activityPlanEvidence = MotionActivityPlans.evidenceFromEvents(repository.events(sessionId))
        val processingPlan = RecordingProcessingPlans.forSession(session, activityPlanEvidence?.context)
        val battery = app.registerReceiver(
            null,
            android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED),
        )
        val level = battery?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
        val batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val status = battery?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
            status == android.os.BatteryManager.BATTERY_STATUS_FULL
        val thermal = prototypeThermalStatus(android.os.Build.VERSION.SDK_INT) {
            app.getSystemService(android.os.PowerManager::class.java)?.currentThermalStatus ?: -1
        }
        val runner = LiteHeavyPrototypeRunner(
            outputRoot = File(app.filesDir, "training/prototypes"),
            liteProcessor = SequentialVideoPoseDecoder(app, liteConfig.delegate, liteConfig.assetPath),
            heavyProcessor = SequentialVideoPoseDecoder(app, heavyConfig.delegate, heavyConfig.assetPath),
            liteModel = identity(liteConfig),
            heavyModel = identity(heavyConfig),
            segmenterConfig = dk.lasse.karateanalyzer.capture.retrospective.RetrospectiveSegmenterConfig(
                profile = processingPlan.movementProfile,
                qomConfig = processingPlan.qomConfig,
                cadence = dk.lasse.karateanalyzer.capture.retrospective.RetrospectiveCadence.REPETITIONS,
            ),
            canonicalGeometry = geometry,
            appBuildIdentity = "app-version=${dk.lasse.karatecliprecorder.BuildConfig.VERSION_NAME};" +
                "version-code=${dk.lasse.karatecliprecorder.BuildConfig.VERSION_CODE};" +
                "debug=${dk.lasse.karatecliprecorder.BuildConfig.DEBUG}",
            sourceRevision = dk.lasse.karatecliprecorder.BuildConfig.SOURCE_REVISION,
            sourceDirtyState = dk.lasse.karatecliprecorder.BuildConfig.SOURCE_DIRTY_STATE,
            apkSha256 = LandmarkFiles.sha256(File(app.applicationInfo.sourceDir)),
            deviceConditions = "manufacturer=${android.os.Build.MANUFACTURER};model=${android.os.Build.MODEL};" +
                "android=${android.os.Build.VERSION.RELEASE};sdk=${android.os.Build.VERSION.SDK_INT};" +
                "battery=$batteryPercent;charging=$charging;thermal=$thermal",
            checkActive = {
                val job = repository.job(sessionId)
                check(job == null || job.state != QueueState.DELETING) { "Cancelled" }
            },
            publication = { operation -> synchronized(ProcessingCoordinator.publication) { operation() } },
            serializedPrototypeEntry = true,
        )
        return PrototypeInvocation(runner, recording.recordingId, video, sourceDurationMs)
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

internal fun prototypeThermalStatus(sdkInt: Int, readThermalStatus: () -> Int): Int =
    if (sdkInt >= android.os.Build.VERSION_CODES.Q) readThermalStatus() else -1

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
