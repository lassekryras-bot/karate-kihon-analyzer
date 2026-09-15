package dk.lasse.karatecliprecorder.training

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
    private val segment: (String, String, List<PoseFrame>, CueTimeline) -> RetrospectiveSessionResult =
        { recordingId, path, frames, cues ->
            RetrospectiveSessionSegmenter(RetrospectiveSegmenterConfig(cadence = RetrospectiveCadence.REPETITIONS))
                .segment(recordingId, path, frames, cues)
        },
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
                              requiredTrackId: String? = null): Pair<LandmarkTrack, List<PoseFrame>> {
        checkActive()
        val recording = requireNotNull(repository.recording(sessionId))
        val tracks = repository.tracks(recording.recordingId)
        val existing = if (requiredTrackId != null) tracks.firstOrNull { it.landmarkTrackId == requiredTrackId }
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
            "tasks-vision=0.10.26;CPU;VIDEO;numPoses=1;detection=0.5;presence=0.5;tracking=0.5;decoder=sequential-v1;timestamps=source-PTS-ms",
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
        val session = requireNotNull(repository.session(sessionId))
        val recording = requireNotNull(repository.recording(sessionId))
        val plan = RecordingProcessingPlans.forSession(session)
        try {
            val existingMovements = repository.movements(sessionId)
            updateJob(sessionId) { it.copy(phase = ProcessingPhase.LANDMARKS, planKey = plan.key, planVersion = plan.version) }
            repository.setSessionState(sessionId, SessionState.LANDMARKS_PROCESSING)
            val landmarkStarted = elapsedRealtimeMs()
            val (source, frames) = loadLandmarks(sessionId, onProgress,
                existingMovements.firstOrNull()?.segmentationTrackId)
            val landmarkDuration = (elapsedRealtimeMs() - landmarkStarted).coerceAtLeast(0)
            android.util.Log.i("RecordingProcessing", "session=$sessionId phase=landmarks durationMs=$landmarkDuration track=${source.landmarkTrackId}")
            updateJob(sessionId) { it.copy(landmarkDurationMs = landmarkDuration, sourceLandmarkTrackId = source.landmarkTrackId) }
            repository.setSessionState(sessionId, SessionState.LANDMARKS_READY)

            check(plan.requiresSegmentation) { "Selected plan does not configure segmentation" }
            updateJob(sessionId) { it.copy(phase = ProcessingPhase.SEGMENTATION) }
            repository.setSessionState(sessionId, SessionState.SEGMENTING)
            val segmentationStarted = elapsedRealtimeMs()
            if (existingMovements.isEmpty() && session.state !in setOf(SessionState.MOVEMENTS_AVAILABLE, SessionState.COMPLETED)) {
                val events = repository.events(sessionId).filter(SessionCueEvents::isCue)
                val cues = events.map { CueEvent(it.sessionEventId, it.data ?: it.type, 0, it.timestampUs / 1000) }
                val retro = segment(recording.recordingId, recording.filePath, frames, CueTimeline(recording.recordingId, cues))
                val movements = retro.movements.map { m ->
                    SessionMovement(sessionId = sessionId, startUs = m.logicalStartTimestampMs * 1000,
                        endUs = m.logicalEndTimestampMs * 1000, playbackStartUs = m.retainedStartTimestampMs * 1000,
                        playbackEndUs = m.retainedEndTimestampMs * 1000, segmentationSource = "retrospective_session_segmenter",
                        segmentationVersion = "1", segmentationTrackId = source.landmarkTrackId,
                        // Current segmenter emits constant 1.0, not a calibrated confidence estimate.
                        segmentationConfidence = null)
                }
                val links = retro.movements.zip(movements).mapNotNull { (m, stored) ->
                    m.associatedCue?.let { MovementSessionEvent(stored.movementId, it.id, sessionId) }
                }
                val labels = if (session.activityKey == "guided_jodan_session") listOf(
                    Label(machineKey = "straight_punch", displayText = "Straight Punch", category = "technique"),
                    Label(machineKey = "jodan", displayText = "Jōdan", category = "target"),
                ) else emptyList()
                repository.saveSegmentation(sessionId, movements, movements.map { MovementObservation.estimate(it, frames) }, links, labels)
            }
            val segmentationDuration = (elapsedRealtimeMs() - segmentationStarted).coerceAtLeast(0)
            android.util.Log.i("RecordingProcessing", "session=$sessionId phase=segmentation durationMs=$segmentationDuration movements=${repository.movementCount(sessionId)}")
            updateJob(sessionId) { it.copy(segmentationDurationMs = segmentationDuration,
                segmentationVersion = SEGMENTATION_VERSION) }

            if (plan.analyzers.isEmpty()) {
                repository.setSessionState(sessionId, SessionState.COMPLETED)
                updateJob(sessionId) { it.copy(phase = ProcessingPhase.READY) }
                return repository.movementCount(sessionId)
            }
            repository.setSessionState(sessionId, SessionState.ANALYZING)
            updateJob(sessionId) { it.copy(phase = ProcessingPhase.ANALYSIS) }
            var hadFailure = false
            repository.movements(sessionId).forEach { movement ->
                if (repository.preferredAnalysis(movement.movementId, AndroidPunchMovementAnalyzer.policy) == null) {
                    val output = runCatching {
                        AndroidPunchMovementAnalyzer.analyze(movement, source.landmarkTrackId, frames,
                            session.activityKey == "guided_jodan_session")
                    }.getOrElse { error ->
                        hadFailure = true
                        MovementAnalysis(movementId = movement.movementId,
                            analyzerKey = AndroidPunchMovementAnalyzer.policy.analyzerKey, analyzerVersion = "1",
                            landmarkTrackId = source.landmarkTrackId, state = AnalysisState.FAILED,
                            reason = error.message ?: "Analyzer execution failed") to emptyList()
                    }
                    repository.saveAnalysis(output.first, output.second)
                }
            }
            // Completion means processing finished. Individual result quality remains independent.
            repository.setSessionState(sessionId, if (hadFailure) SessionState.PARTIAL else SessionState.COMPLETED,
                if (hadFailure) "One or more analyzer executions failed" else null)
            return repository.movementCount(sessionId)
        } catch (error: Exception) {
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

    companion object { const val SEGMENTATION_VERSION = "1" }
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
        val analyzer = PunchHeightAnalyzer()
        val multiplier = PunchHeightAnalyzer.DEFAULT_CHIN_PROJECTION_MULTIPLIER
        var setupReady = false
        var initialized = false
        val evaluations = mutableListOf<PunchHeightEvaluation>()
        if (requestedJodan) frames.asSequence().filter { it.timestampMs * 1000 <= movement.endUs }.forEach { frame ->
            if (!setupReady) setupReady = analyzer.processSetup(frame).usable
            else if (!initialized) initialized = analyzer.processBodyInitialization(frame, multiplier).bodyReference != null
            else if (frame.timestampMs * 1000 >= movement.startUs) analyzer.evaluateTarget(PunchHeightTargetType.JODAN, frame, multiplier)?.let(evaluations::add)
        }
        val best = evaluations.filter { it.elbowAngleDegrees?.isFinite() == true && it.signedHeightErrorTorsoRatio?.isFinite() == true }
            .maxByOrNull { it.elbowAngleDegrees!! }
        val reason = when {
            !requestedJodan -> "technique_and_target_not_identified"
            best == null -> "insufficient_side_view_setup_or_landmarks"
            else -> "static_analyzer_applied_to_movement; dynamic_validation_pending; ${best.guidance.name}"
        }
        val analysis = MovementAnalysis(movementId = movement.movementId, analyzerKey = policy.analyzerKey,
            analyzerVersion = "1", landmarkTrackId = trackId,
            state = if (best == null) AnalysisState.ABSTAINED else AnalysisState.PARTIAL, reason = reason)
        val side = when (best?.activeArm) { ActiveArm.LEFT -> BodySide.LEFT; ActiveArm.RIGHT -> BodySide.RIGHT; else -> BodySide.UNKNOWN }
        val results = listOf("PUNCH_HEIGHT_ERROR_TORSO_RATIO" to best?.signedHeightErrorTorsoRatio,
            "PUNCH_ELBOW_ANGLE" to best?.elbowAngleDegrees).map { (key, value) ->
            MeasurementResult(analysisId = analysis.analysisId, measurementKey = key, calculationVersion = "1",
                numericValue = value?.toDouble(), side = side, role = "strike", reason = reason,
                state = if (value == null) ResultState.ABSTAINED else ResultState.PARTIAL,
                confidence = best?.bodyReference?.confidence?.toDouble(), occurrenceUs = best?.timestampMs?.times(1000))
        }
        return analysis to results
    }
}
