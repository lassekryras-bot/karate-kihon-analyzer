package dk.lasse.karatecliprecorder.training

import dk.lasse.karateanalyzer.capture.qom.MotionBodyProfile
import dk.lasse.karateanalyzer.capture.retrospective.*
import dk.lasse.karateanalyzer.core.*
import java.io.File

/** Reuses persisted landmarks and movement identities after process loss or when rerunning an analyzer. */
class TrainingSessionProcessor(
    private val repository: TrainingRepository,
    private val landmarkDirectory: File,
    private val poseProcessor: VideoPoseProcessor,
    private val modelVersion: String,
    private val checkActive: () -> Unit = {},
    private val publication: (() -> Unit) -> Unit = { it() },
    private val elapsedRealtimeMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val segment: ((String, String, List<PoseFrame>, CueTimeline) -> RetrospectiveSessionResult)? = null,
    private val segmentWithProfile: (String, String, List<PoseFrame>, CueTimeline, MotionBodyProfile) -> RetrospectiveSessionResult =
        { recordingId, path, frames, cues, profile ->
            if (segment != null) {
                segment(recordingId, path, frames, cues)
            } else {
                RetrospectiveSessionSegmenter(
                    RetrospectiveSegmenterConfig(
                        profile = profile,
                        cadence = RetrospectiveCadence.REPETITIONS,
                    )
                ).segment(recordingId, path, frames, cues)
            }
        },
    private val trackConfiguration: String = "tasks-vision=0.10.26;CPU;VIDEO;numPoses=1;detection=0.5;presence=0.5;tracking=0.5;decoder=sequential-v1;timestamps=source-PTS-ms",
) {
    /** Landmark-only entry point retained for diagnostics/tests; queued recordings call [process]. */
    fun ensureLandmarks(sessionId: String, onProgress: (Float, Long) -> Unit = { _, _ -> }): LandmarkTrack {
        try {
            repository.setSessionState(sessionId, SessionState.LANDMARKS_PROCESSING)
            val track = loadLandmarks(sessionId, onProgress).first
            repository.setSessionState(sessionId, SessionState.LANDMARKS_READY)
            return track
        } catch (error: Exception) {
            repository.setSessionState(sessionId, SessionState.PARTIAL, error.message ?: "Movement data failed")
            throw error
        }
    }

    private fun loadLandmarks(sessionId: String, onProgress: (Float, Long) -> Unit,
                              requiredTrackId: String? = null, forceNew: Boolean = false): Pair<LandmarkTrack, List<PoseFrame>> {
        checkActive()
        val recording = requireNotNull(repository.recording(sessionId))
        val tracks = repository.tracks(recording.recordingId)
        val existing = if (forceNew) null
            else if (requiredTrackId != null) tracks.firstOrNull { it.landmarkTrackId == requiredTrackId }
            else tracks.firstOrNull { it.pipelineVersion == modelVersion && it.state == ProcessingState.COMPLETED && it.sourceState == SourceState.AVAILABLE }
                ?: tracks.firstOrNull { it.pipelineVersion == modelVersion && it.state == ProcessingState.PROCESSING && repository.file(it.filePath).isFile }
        if (existing != null) {
            try {
                val file = repository.file(existing.filePath)
                check(existing.formatVersion == null || existing.formatVersion == LandmarkFiles.VERSION)
                val frames = LandmarkFiles.read(file, existing.sha256, existing.formatId)
                val recovered = existing.copy(state = ProcessingState.COMPLETED, sourceState = SourceState.AVAILABLE,
                    sha256 = existing.sha256 ?: LandmarkFiles.sha256(file))
                publication { checkActive(); repository.updateTrack(recovered) }
                return recovered to frames
            } catch (error: Exception) {
                if (error is java.util.concurrent.CancellationException) throw error
                repository.updateTrack(existing.copy(state = ProcessingState.FAILED,
                    sourceState = SourceState.FAILED, quality = "Invalid landmark evidence: ${error.message}"))
                throw error // Explicit Retry can create a new UUID; never conceal corrupt evidence.
            }
        }
        check(requiredTrackId == null) { "Segmentation source track unavailable" }
        check(recording.sourceState == SourceState.AVAILABLE && repository.file(recording.filePath).isFile) {
            "Original video is unavailable"
        }
        // An interrupted temporary file is never read or promoted. Keep it as failed evidence.
        tracks.filter { it.state == ProcessingState.PROCESSING || it.state == ProcessingState.PENDING }.forEach {
            repository.updateTrack(it.copy(state = ProcessingState.FAILED, sourceState = SourceState.FAILED,
                quality = "Interrupted unpublished stream; retry uses a new UUID"))
        }
        val id = trainingId()
        var track = LandmarkTrack(id, recording.recordingId, "mediapipe_pose_video", modelVersion,
            trackConfiguration,
            repository.fileReference(File(landmarkDirectory, "$id.mls")), state = ProcessingState.PROCESSING,
            formatId = LandmarkFiles.FORMAT_ID, formatVersion = LandmarkFiles.VERSION)
        repository.addTrack(track)
        try {
            val frames = poseProcessor.processVideo(repository.file(recording.filePath)) { progress, time ->
                checkActive(); onProgress(progress, time)
            }
            val hash = LandmarkFiles.write(repository.file(track.filePath), frames, checkActive, publication)
            track = track.copy(state = ProcessingState.COMPLETED, sourceState = SourceState.AVAILABLE,
                quality = "Raw landmark evidence; no technique validity inferred", sha256 = hash)
            publication { checkActive(); repository.updateTrack(track) }
            return track to frames
        } catch (error: Exception) {
            if (error is java.util.concurrent.CancellationException) {
                // UUID staging paths are never reused. Cleanup cannot create or publish evidence.
                java.io.File(repository.file(track.filePath).path + ".tmp").delete()
                throw error
            }
            repository.updateTrack(track.copy(state = ProcessingState.FAILED, sourceState = SourceState.FAILED, quality = error.message))
            throw error
        }
    }

    fun process(sessionId: String, onProgress: (Float, Long) -> Unit = { _, _ -> }): Int {
        return processInternal(sessionId, reanalysisRunId = null, forceNewLandmarks = false, onProgress = onProgress)
    }

    fun processReanalysis(sessionId: String, runId: String, onProgress: (Float, Long) -> Unit = { _, _ -> }): Int {
        return processInternal(sessionId, reanalysisRunId = runId, forceNewLandmarks = false, onProgress = onProgress)
    }

    fun processLandmarkReprocess(sessionId: String, runId: String, onProgress: (Float, Long) -> Unit = { _, _ -> }): Int {
        return processInternal(sessionId, reanalysisRunId = runId, forceNewLandmarks = true, onProgress = onProgress)
    }

    private fun processInternal(
        sessionId: String,
        reanalysisRunId: String?,
        forceNewLandmarks: Boolean = false,
        onProgress: (Float, Long) -> Unit,
    ): Int {
        val isReanalysis = reanalysisRunId != null
        val session = requireNotNull(repository.session(sessionId))
        val recording = requireNotNull(repository.recording(sessionId))
        val plan = RecordingProcessingPlans.forSession(session)

        var currentRun = if (isReanalysis) {
            requireNotNull(repository.run(reanalysisRunId))
        } else {
            repository.currentRun(sessionId)
        }

        try {
            val (source, frames) = if (forceNewLandmarks) {
                updateJob(sessionId) { it.copy(phase = ProcessingPhase.LANDMARKS, planKey = plan.key, planVersion = plan.version) }
                repository.setSessionState(sessionId, SessionState.LANDMARKS_PROCESSING)
                val landmarkStarted = elapsedRealtimeMs()
                val pair = loadLandmarks(sessionId, onProgress, forceNew = true)
                val landmarkDuration = (elapsedRealtimeMs() - landmarkStarted).coerceAtLeast(0)
                android.util.Log.i("RecordingProcessing", "session=$sessionId phase=landmarks durationMs=$landmarkDuration track=${pair.first.landmarkTrackId} (force-new)")
                updateJob(sessionId) { it.copy(landmarkDurationMs = landmarkDuration, sourceLandmarkTrackId = pair.first.landmarkTrackId) }
                repository.setSessionState(sessionId, SessionState.LANDMARKS_READY)
                if (currentRun != null) {
                    val updated = currentRun.copy(sourceLandmarkTrackId = pair.first.landmarkTrackId, landmarkDurationMs = landmarkDuration)
                    repository.updateRun(updated)
                    currentRun = updated
                }
                pair
            } else if (isReanalysis) {
                checkNotNull(currentRun?.sourceLandmarkTrackId) { "Source landmark track ID missing for reanalysis" }
                updateJob(sessionId) { it.copy(phase = ProcessingPhase.SEGMENTATION, planKey = plan.key, planVersion = plan.version) }
                repository.setSessionState(sessionId, SessionState.SEGMENTING)
                val pair = loadLandmarks(sessionId, onProgress, currentRun.sourceLandmarkTrackId)
                android.util.Log.i("RecordingProcessing", "session=$sessionId phase=landmarks reused=true track=${pair.first.landmarkTrackId}")
                pair
            } else {
                val existingMovements = repository.movements(sessionId)
                updateJob(sessionId) { it.copy(phase = ProcessingPhase.LANDMARKS, planKey = plan.key, planVersion = plan.version) }
                repository.setSessionState(sessionId, SessionState.LANDMARKS_PROCESSING)
                val landmarkStarted = elapsedRealtimeMs()
                val pair = loadLandmarks(sessionId, onProgress, existingMovements.firstOrNull()?.segmentationTrackId)
                val landmarkDuration = (elapsedRealtimeMs() - landmarkStarted).coerceAtLeast(0)
                android.util.Log.i("RecordingProcessing", "session=$sessionId phase=landmarks durationMs=$landmarkDuration track=${pair.first.landmarkTrackId}")
                updateJob(sessionId) { it.copy(landmarkDurationMs = landmarkDuration, sourceLandmarkTrackId = pair.first.landmarkTrackId) }
                repository.setSessionState(sessionId, SessionState.LANDMARKS_READY)
                pair
            }

            if (currentRun == null) {
                val initialRun = ProcessingRun(
                    sessionId = sessionId,
                    mode = RunMode.INITIAL,
                    sourceLandmarkTrackId = source.landmarkTrackId,
                    planKey = plan.key,
                    planVersion = plan.version,
                    segmenterVersion = SEGMENTATION_VERSION,
                    analyzerKey = plan.analyzers.firstOrNull(),
                    analyzerVersion = "1",
                    state = RunState.PROCESSING,
                    isCurrent = true,
                )
                repository.createRun(initialRun)
                currentRun = initialRun
            }

            check(plan.requiresSegmentation) { "Selected plan does not configure segmentation" }
            updateJob(sessionId) { it.copy(phase = ProcessingPhase.SEGMENTATION) }
            repository.setSessionState(sessionId, SessionState.SEGMENTING)
            val segmentationStarted = elapsedRealtimeMs()

            val existingMovements = if (isReanalysis) emptyList() else repository.movements(sessionId)
            if (existingMovements.isEmpty() && (isReanalysis || session.state !in setOf(SessionState.MOVEMENTS_AVAILABLE, SessionState.COMPLETED))) {
                val events = repository.events(sessionId).filter(SessionCueEvents::isCue)
                val cues = events.map { CueEvent(it.sessionEventId, it.data ?: it.type, 0, it.timestampUs / 1000) }
                val retro = segmentWithProfile(recording.recordingId, recording.filePath, frames, CueTimeline(recording.recordingId, cues), plan.movementProfile)
                val movements = retro.movements.map { m ->
                    val canonical = CanonicalAnalysisFrameSelector.select(
                        m.logicalStartTimestampMs * 1000L,
                        m.logicalEndTimestampMs * 1000L,
                        frames,
                    )
                    SessionMovement(
                        sessionId = sessionId,
                        startUs = m.logicalStartTimestampMs * 1000L,
                        endUs = m.logicalEndTimestampMs * 1000L,
                        playbackStartUs = m.retainedStartTimestampMs * 1000L,
                        playbackEndUs = m.retainedEndTimestampMs * 1000L,
                        segmentationSource = "retrospective_session_segmenter",
                        segmentationVersion = SEGMENTATION_VERSION,
                        segmentationTrackId = source.landmarkTrackId,
                        segmentationConfidence = null,
                        runId = currentRun.runId,
                        analysisFrameUs = canonical?.timestampUs,
                    )
                }
                val links = retro.movements.zip(movements).mapNotNull { (m, stored) ->
                    m.associatedCue?.let { MovementSessionEvent(stored.movementId, it.id, sessionId) }
                }
                val labels = if (session.activityKey == "guided_jodan_session") listOf(
                    Label(machineKey = "straight_punch", displayText = "Straight Punch", category = "technique"),
                    Label(machineKey = "jodan", displayText = "Jōdan", category = "target"),
                ) else emptyList()
                repository.saveSegmentation(
                    sessionId,
                    movements,
                    movements.map { MovementObservation.estimate(it, frames) },
                    links,
                    labels,
                    runId = currentRun.runId,
                )
            }
            val segmentationDuration = (elapsedRealtimeMs() - segmentationStarted).coerceAtLeast(0)
            android.util.Log.i("RecordingProcessing", "session=$sessionId phase=segmentation durationMs=$segmentationDuration movements=${repository.movementCount(sessionId, currentRun.runId)}")
            updateJob(sessionId) { it.copy(segmentationDurationMs = segmentationDuration, segmentationVersion = SEGMENTATION_VERSION) }

            val runMovements = repository.movements(sessionId, runId = currentRun.runId)
            if (plan.analyzers.isEmpty() || runMovements.isEmpty()) {
                repository.publishRun(currentRun.runId)
                repository.setSessionState(sessionId, SessionState.COMPLETED)
                updateJob(sessionId) { it.copy(phase = ProcessingPhase.READY) }
                return runMovements.size
            }

            repository.setSessionState(sessionId, SessionState.ANALYZING)
            updateJob(sessionId) { it.copy(phase = ProcessingPhase.ANALYSIS) }
            var hadFailure = false
            runMovements.forEach { movement ->
                for (analyzerKey in plan.analyzers) {
                    when (analyzerKey) {
                        StraightPunchMovementAdapter.policy.analyzerKey -> {
                            if (repository.preferredAnalysis(movement.movementId, StraightPunchMovementAdapter.policy) == null) {
                                val output = runCatching {
                                    StraightPunchMovementAdapter.analyze(
                                        movement = movement,
                                        trackId = source.landmarkTrackId,
                                        frames = frames,
                                        videoWidth = recording.width,
                                        videoHeight = recording.height,
                                    )
                                }.getOrElse { error ->
                                    hadFailure = true
                                    MovementAnalysis(
                                        movementId = movement.movementId,
                                        analyzerKey = StraightPunchMovementAdapter.policy.analyzerKey,
                                        analyzerVersion = "1",
                                        landmarkTrackId = source.landmarkTrackId,
                                        state = AnalysisState.FAILED,
                                        reason = error.message ?: "Analyzer execution failed",
                                    ) to emptyList()
                                }
                                repository.saveAnalysis(output.first, output.second)
                            }
                        }
                        AndroidPunchMovementAnalyzer.policy.analyzerKey -> {
                            if (repository.preferredAnalysis(movement.movementId, AndroidPunchMovementAnalyzer.policy) == null) {
                                val output = runCatching {
                                    AndroidPunchMovementAnalyzer.analyze(movement, source.landmarkTrackId, frames,
                                        session.activityKey == "guided_jodan_session")
                                }.getOrElse { error ->
                                    hadFailure = true
                                    MovementAnalysis(
                                        movementId = movement.movementId,
                                        analyzerKey = AndroidPunchMovementAnalyzer.policy.analyzerKey,
                                        analyzerVersion = "1",
                                        landmarkTrackId = source.landmarkTrackId,
                                        state = AnalysisState.FAILED,
                                        reason = error.message ?: "Analyzer execution failed",
                                    ) to emptyList()
                                }
                                repository.saveAnalysis(output.first, output.second)
                            }
                        }
                    }
                }
            }

            if (hadFailure) {
                repository.failRun(currentRun.runId, "One or more analyzer executions failed")
                repository.setSessionState(sessionId, SessionState.PARTIAL, "One or more analyzer executions failed")
            } else {
                repository.publishRun(currentRun.runId)
                repository.setSessionState(sessionId, SessionState.COMPLETED)
                updateJob(sessionId) { it.copy(phase = ProcessingPhase.READY) }
            }
            return runMovements.size
        } catch (error: Exception) {
            if (reanalysisRunId != null) {
                repository.failRun(reanalysisRunId, error.message ?: "Reanalysis interrupted")
            } else if (currentRun != null) {
                repository.failRun(currentRun.runId, error.message ?: "Processing interrupted")
            }
            repository.setSessionState(sessionId, SessionState.PARTIAL, error.message ?: "Processing interrupted")
            if (error !is java.util.concurrent.CancellationException) updateJob(sessionId) {
                it.copy(phase = ProcessingPhase.FAILED, error = error.message ?: "Processing interrupted")
            }
            throw error
        }
    }

    private fun updateJob(sessionId: String, transform: (RecordingProcessing) -> RecordingProcessing) {
        repository.job(sessionId)?.let { repository.updateJob(transform(it)) }
    }

    companion object { const val SEGMENTATION_VERSION = "activity_qom_hysteresis_v1" }
}

/** Persisted session lifecycle events are not movement cues. Keep this allow-list deliberately narrow. */
object SessionCueEvents {
    private val TYPES = setOf("spoken_count", "cue")
    fun isCue(event: SessionEvent): Boolean = event.type.lowercase() in TYPES
}

/** Adapter for the existing static punch-height analyzer; it does not invent a dynamic wrist-path analyzer. */
object AndroidPunchMovementAnalyzer {
    val policy = AnalyzerPolicy("android_punch_height", listOf("1"))
    fun analyze(movement: SessionMovement, trackId: String, frames: List<PoseFrame>, requestedJodan: Boolean):
        Pair<MovementAnalysis, List<MeasurementResult>> {
        val resolved = CanonicalAnalysisFrameSelector.resolve(movement.startUs, movement.endUs, movement.analysisFrameUs, frames)
        val canonicalResult = resolved?.first
        val canonicalFrame = resolved?.second
        if (canonicalResult == null || canonicalFrame == null) {
            val analysis = MovementAnalysis(
                movementId = movement.movementId,
                analyzerKey = policy.analyzerKey,
                analyzerVersion = "1",
                landmarkTrackId = trackId,
                state = AnalysisState.ABSTAINED,
                reason = "canonical_analysis_frame_unavailable",
            )
            return analysis to emptyList()
        }

        val analyzer = PunchHeightAnalyzer()
        val multiplier = PunchHeightAnalyzer.DEFAULT_CHIN_PROJECTION_MULTIPLIER
        var setupReady = false
        var initialized = false
        if (requestedJodan) {
            frames.asSequence().filter { it.timestampMs <= canonicalFrame.timestampMs }.forEach { frame ->
                if (!setupReady) setupReady = analyzer.processSetup(frame).usable
                else if (!initialized) initialized = analyzer.processBodyInitialization(frame, multiplier).bodyReference != null
            }
        }
        val best = if (requestedJodan && initialized) {
            analyzer.evaluateTarget(PunchHeightTargetType.JODAN, canonicalFrame, multiplier)
                ?.takeIf { it.elbowAngleDegrees?.isFinite() == true && it.signedHeightErrorTorsoRatio?.isFinite() == true }
        } else null

        val reason = when {
            !requestedJodan -> "technique_and_target_not_identified"
            best == null -> "insufficient_side_view_setup_or_landmarks"
            else -> "static_analyzer_applied_to_movement; dynamic_validation_pending; ${best.guidance.name}; frameIndex=${canonicalResult.frameIndex}; strategy=${canonicalResult.strategy}"
        }
        val analysis = MovementAnalysis(movementId = movement.movementId, analyzerKey = policy.analyzerKey,
            analyzerVersion = "1", landmarkTrackId = trackId,
            state = if (best == null) AnalysisState.ABSTAINED else AnalysisState.PARTIAL, reason = reason)
        val side = when (best?.activeArm) { ActiveArm.LEFT -> BodySide.LEFT; ActiveArm.RIGHT -> BodySide.RIGHT; else -> BodySide.UNKNOWN }
        val occurrenceUs = canonicalResult.timestampUs
        val frameIndex = canonicalResult.frameIndex
        val results = listOf("PUNCH_HEIGHT_ERROR_TORSO_RATIO" to best?.signedHeightErrorTorsoRatio,
            "PUNCH_ELBOW_ANGLE" to best?.elbowAngleDegrees).map { (key, value) ->
            MeasurementResult(analysisId = analysis.analysisId, measurementKey = key, calculationVersion = "1",
                numericValue = value?.toDouble(), side = side, role = "strike", reason = reason,
                state = if (value == null) ResultState.ABSTAINED else ResultState.PARTIAL,
                confidence = best?.bodyReference?.confidence?.toDouble(),
                occurrenceUs = occurrenceUs,
                frameIndex = frameIndex)
        }
        return analysis to results
    }
}
