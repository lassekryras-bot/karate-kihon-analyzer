package dk.lasse.karatecliprecorder.training

import java.io.File
import dk.lasse.karatecliprecorder.sharedcapture.CaptureOutcome

/** Blocking repository: invoke on a worker executor. No UI/analyzer depends on Room rows or DAOs. */
class TrainingRepository(private val database: KarateTrainingDatabase, val storage: TrainingStorage? = null) {
    fun file(reference: String): File = storage?.resolve(reference) ?: File(reference)
    fun fileReference(file: File): String = storage?.reference(file) ?: file.absolutePath
    private val dao get() = database.trainingDao()
    private fun <T> atomic(block: () -> T): T = database.runInTransaction(java.util.concurrent.Callable(block))

    fun createUser(user: TrainingUser) = atomic {
        require(runCatching { java.util.UUID.fromString(user.userId) }.isSuccess)
        if (dao.user(user.userId) == null) dao.insert(TrainingUserRow(user))
    }

    fun beginSession(session: RecordingSession, recording: MasterRecording, bodyIds: List<String> = emptyList()) = atomic {
        require(session.sessionId == recording.sessionId)
        require(session.expectedRepetitions == null || session.expectedRepetitions >= 0)
        dao.insert(RecordingSessionRow(session))
        dao.insert(MasterRecordingRow(recording))
        bodyIds.forEach { id ->
            require(dao.bodyMeasurement(id)?.value?.userId == session.userId)
            dao.insert(SessionBodyMeasurementRow(SessionBodyMeasurement(session.sessionId, id)))
        }
    }

    fun finishCapture(sessionId: String, durationUs: Long, failed: String? = null,
                      width: Int? = null, height: Int? = null, frameRate: Double? = null,
                      outcome: CaptureOutcome = if (failed == null) CaptureOutcome.COMPLETED else CaptureOutcome.FAILED) = atomic {
        val recording = requireNotNull(dao.recording(sessionId)).value
        require(durationUs >= 0)
        dao.update(MasterRecordingRow(recording.copy(durationUs = durationUs, width = width, height = height,
            frameRate = frameRate, sourceState = if (failed == null) SourceState.AVAILABLE else SourceState.FAILED)))
        dao.finishRecording(sessionId, System.currentTimeMillis(),
            if (failed == null) SessionState.RECORDED else SessionState.FAILED, failed,
            if (failed == null) outcome.name else CaptureOutcome.FAILED.name)
    }

    fun finishRecording(sessionId: String, durationUs: Long, failed: String? = null,
                        width: Int? = null, height: Int? = null, frameRate: Double? = null) =
        finishCapture(sessionId, durationUs, failed, width, height, frameRate)

    fun enqueue(id: String) {
        val session = session(id)
        val plan = session?.let(RecordingProcessingPlans::forSession) ?: RecordingProcessingPlans.STRAIGHT_PUNCH_SEGMENTS
        dao.insert(RecordingProcessingRow(RecordingProcessing(id, session?.startedAtMs ?: System.currentTimeMillis(),
            planKey = plan.key, planVersion = plan.version)))
    }
    fun jobs() = dao.jobs().map { it.value }
    fun job(id: String) = dao.job(id)?.value
    fun updateJob(value: RecordingProcessing) = dao.update(RecordingProcessingRow(value))
    fun promote(id: String) = atomic {
        require(recording(id)?.sourceState == SourceState.AVAILABLE)
        enqueue(id)
        val job = requireNotNull(job(id))
        require(job.state != QueueState.DELETING)
        if (job.state != QueueState.READY) updateJob(job.copy(
            state = if (job.state == QueueState.PROCESSING) job.state else QueueState.QUEUED,
            phase = if (job.state == QueueState.PROCESSING) job.phase else ProcessingPhase.QUEUED,
            promotedAtMs = System.currentTimeMillis(), manual = true, error = null))
    }
    fun recoverJobs() {
        dao.recoverJobs()
        dao.assistedSessions().forEach {
            val media = recording(it.value.sessionId)
            if (media?.sourceState == SourceState.AVAILABLE &&
                media.captureType == dk.lasse.karatecliprecorder.sharedcapture.CaptureType.VIDEO) enqueue(it.value.sessionId)
        }
    }
    /** Caller holds the publication fence. DELETING survives failure/process loss. */
    fun deleteRecordingAndEvidence(id: String) {
        val source = recording(id) ?: return
        enqueue(id)
        job(id)?.let { updateJob(it.copy(state = QueueState.DELETING)) }
        val files = listOf(file(source.filePath)) + tracks(source.recordingId).flatMap {
            listOf(file(it.filePath), file(it.filePath + ".tmp"), file(it.filePath + ".pending"))
        }
        files.forEach { check(!it.exists() || it.delete()) { "Could not delete recording files; deletion will retry" } }
        deleteSession(id)
    }

    fun session(id: String) = dao.session(id)?.value
    fun sessions(userId: String) = dao.sessions(userId).map { it.value }
    fun recordingSummaries(userId: String): List<RecordingSummary> = atomic {
        sessions(userId).mapNotNull { session ->
            recording(session.sessionId)?.takeIf { it.captureType == dk.lasse.karatecliprecorder.sharedcapture.CaptureType.VIDEO &&
                it.sourceState !in setOf(SourceState.PENDING, SourceState.FAILED) }?.let {
                RecordingSummary(session, it, job(session.sessionId), movementCount(session.sessionId), currentRun(session.sessionId))
            }
        }
    }
    fun recording(sessionId: String) = dao.recording(sessionId)?.value
    fun movements(sessionId: String, runId: String? = null): List<SessionMovement> = atomic {
        val effectiveRunId = runId ?: currentRun(sessionId)?.runId
        if (effectiveRunId != null) {
            return@atomic dao.movementsForRun(effectiveRunId).map { it.value }
        }
        dao.movements(sessionId).map { it.value }
    }
    fun movementsForRun(runId: String): List<SessionMovement> = dao.movementsForRun(runId).map { it.value }
    fun movementCount(sessionId: String, runId: String? = null): Int = atomic {
        val effectiveRunId = runId ?: currentRun(sessionId)?.runId
        if (effectiveRunId != null) {
            return@atomic dao.movementCountForRun(effectiveRunId)
        }
        dao.movementCount(sessionId)
    }
    fun resultsForAnalysis(analysisId: String): List<MeasurementResult> = dao.results(analysisId).map { it.value }
    fun events(sessionId: String) = dao.events(sessionId).map { it.value }
    fun tracks(recordingId: String) = dao.tracks(recordingId).map { it.value }
    fun setSessionState(id: String, state: SessionState, reason: String? = null) = dao.sessionState(id, state, reason)
    fun addEvent(event: SessionEvent) {
        require(event.timestampUs >= 0)
        dao.insert(SessionEventRow(event))
    }
    fun recordBoundary(event: SessionEvent, interruption: String? = null) = atomic {
        addEvent(event)
        if (interruption != null) dao.interruptSession(event.sessionId, interruption)
    }
    fun addTrack(track: LandmarkTrack) = dao.insert(LandmarkTrackRow(track))
    fun updateTrack(track: LandmarkTrack) = dao.update(LandmarkTrackRow(track))

    fun createRun(run: ProcessingRun) = atomic { dao.insert(ProcessingRunRow(run)) }
    fun updateRun(run: ProcessingRun) = atomic { dao.update(ProcessingRunRow(run)) }
    fun run(runId: String): ProcessingRun? = dao.run(runId)?.value
    fun currentRun(sessionId: String): ProcessingRun? = dao.currentRun(sessionId)?.value
    fun runs(sessionId: String): List<ProcessingRun> = dao.runs(sessionId).map { it.value }

    fun publishRun(runId: String) = atomic {
        val run = requireNotNull(dao.run(runId)?.value) { "Processing run not found" }
        dao.clearCurrentRuns(run.sessionId)
        dao.update(ProcessingRunRow(run.copy(
            isCurrent = true,
            state = RunState.COMPLETED,
            completedAtMs = System.currentTimeMillis(),
            error = null,
        )))
        dao.sessionState(run.sessionId, SessionState.COMPLETED, null)
    }

    fun failRun(runId: String, error: String) = atomic {
        val run = requireNotNull(dao.run(runId)?.value) { "Processing run not found" }
        dao.update(ProcessingRunRow(run.copy(
            state = RunState.FAILED,
            completedAtMs = System.currentTimeMillis(),
            error = error,
        )))
    }

    fun canReanalyze(sessionId: String): Boolean {
        val rec = recording(sessionId) ?: return false
        if (rec.sourceState != SourceState.AVAILABLE) return false
        val currentJob = job(sessionId)
        if (currentJob?.state in setOf(QueueState.PROCESSING, QueueState.DELETING)) return false
        val completedTrack = tracks(rec.recordingId).firstOrNull {
            it.state == ProcessingState.COMPLETED && it.sourceState == SourceState.AVAILABLE && file(it.filePath).isFile
        }
        return completedTrack != null
    }

    fun canReprocessLandmarks(sessionId: String): Boolean {
        val rec = recording(sessionId) ?: return false
        if (rec.sourceState != SourceState.AVAILABLE || !file(rec.filePath).isFile) return false
        val currentJob = job(sessionId)
        if (currentJob?.state in setOf(QueueState.PROCESSING, QueueState.DELETING)) return false
        return true
    }

    fun prepareReanalysisRun(sessionId: String): Pair<ProcessingRun, LandmarkTrack> = atomic {
        check(canReanalyze(sessionId)) { "Reanalysis cannot proceed: valid landmark evidence is not available" }
        val rec = requireNotNull(recording(sessionId))
        val track = tracks(rec.recordingId).first {
            it.state == ProcessingState.COMPLETED && it.sourceState == SourceState.AVAILABLE && file(it.filePath).isFile
        }
        val session = requireNotNull(session(sessionId))
        val plan = RecordingProcessingPlans.forSession(session)
        val run = ProcessingRun(
            sessionId = sessionId,
            mode = RunMode.REANALYSIS,
            sourceLandmarkTrackId = track.landmarkTrackId,
            planKey = plan.key,
            planVersion = plan.version,
            segmenterVersion = TrainingSessionProcessor.SEGMENTATION_VERSION,
            analyzerKey = plan.analyzers.firstOrNull(),
            analyzerVersion = "1",
            state = RunState.PROCESSING,
            isCurrent = false,
        )
        dao.insert(ProcessingRunRow(run))
        run to track
    }

    fun prepareLandmarkReprocessRun(sessionId: String): ProcessingRun = atomic {
        check(canReprocessLandmarks(sessionId)) { "Landmark reprocessing cannot proceed: master video is unavailable" }
        val session = requireNotNull(session(sessionId))
        val plan = RecordingProcessingPlans.forSession(session)
        val run = ProcessingRun(
            sessionId = sessionId,
            mode = RunMode.LANDMARK_REPROCESS,
            sourceLandmarkTrackId = null,
            planKey = plan.key,
            planVersion = plan.version,
            segmenterVersion = TrainingSessionProcessor.SEGMENTATION_VERSION,
            analyzerKey = plan.analyzers.firstOrNull(),
            analyzerVersion = "1",
            state = RunState.PROCESSING,
            isCurrent = false,
        )
        dao.insert(ProcessingRunRow(run))
        run
    }

    /** Idempotent checkpoint. Never replace already identified physical movements on retry. */
    fun saveSegmentation(sessionId: String, movements: List<SessionMovement>, observations: List<ObservationContext>,
                         links: List<MovementSessionEvent> = emptyList(), labels: List<Label> = emptyList(),
                         runId: String? = null) = atomic {
        val effectiveRunId = runId ?: movements.firstOrNull()?.runId
        if (effectiveRunId != null) {
            require(dao.movementCountForRun(effectiveRunId) == 0) { "Segmentation already persisted; reuse existing movement identities" }
        } else {
            require(dao.movementCount(sessionId) == 0) { "Segmentation already persisted; reuse existing movement identities" }
        }
        require(observations.map { it.movementId }.toSet() == movements.map { it.movementId }.toSet())
        movements.forEach { movement ->
            require(movement.sessionId == sessionId)
            require(movement.startUs >= 0 && movement.endUs > movement.startUs)
            require(movement.playbackStartUs in 0..movement.startUs && movement.playbackEndUs >= movement.endUs)
            movement.segmentationTrackId?.let { requireTrackForSession(it, sessionId) }
            dao.insert(SessionMovementRow(movement))
        }
        observations.forEach { observation ->
            requireConfidence(observation.confidence)
            require(observation.bodyToCameraDegrees == null || observation.bodyToCameraDegrees.isFinite())
            dao.insert(ObservationContextRow(observation))
        }
        links.forEach { require(it.sessionId == sessionId); dao.insert(MovementSessionEventRow(it)) }
        labels.forEach { label ->
            val persisted = dao.label(label.machineKey)?.value ?: label.also { dao.insert(LabelRow(it)) }
            movements.forEach { dao.insert(MovementLabelRow(MovementLabel(it.movementId, persisted.labelId))) }
        }
        dao.sessionState(sessionId, SessionState.MOVEMENTS_AVAILABLE, null)
    }

    fun addMovementLabel(assignment: MovementLabel) = dao.insert(MovementLabelRow(assignment))
    /** A missed repetition can be inserted without renumbering or replacing any existing movement. */
    fun addMovement(movement: SessionMovement, observation: ObservationContext) = atomic {
        require(observation.movementId == movement.movementId)
        require(movement.startUs >= 0 && movement.endUs > movement.startUs)
        require(movement.playbackStartUs in 0..movement.startUs && movement.playbackEndUs >= movement.endUs)
        movement.segmentationTrackId?.let { requireTrackForSession(it, movement.sessionId) }
        requireConfidence(observation.confidence)
        dao.insert(SessionMovementRow(movement))
        dao.insert(ObservationContextRow(observation))
    }
    fun addLabel(label: Label) = dao.insert(LabelRow(label))
    fun associateEvent(link: MovementSessionEvent) = dao.insert(MovementSessionEventRow(link))

    /** A completed run and all values/references commit together, or none do. Inserts never replace history. */
    fun saveAnalysis(analysis: MovementAnalysis, results: List<MeasurementResult>,
                     bodyIds: List<String> = emptyList(), calibrationIds: List<String> = emptyList()) = atomic {
        val movement = requireNotNull(dao.movement(analysis.movementId)).value
        val userId = requireNotNull(dao.session(movement.sessionId)).value.userId
        requireTrackForSession(analysis.landmarkTrackId, movement.sessionId)
        require(analysis.state != AnalysisState.COMPLETED || results.isNotEmpty())
        dao.insert(MovementAnalysisRow(analysis))
        val identities = mutableSetOf<Triple<String, BodySide, String>>()
        results.forEach { result ->
            require(result.analysisId == analysis.analysisId)
            require(identities.add(Triple(result.measurementKey, result.side, result.role)))
            val definition = requireNotNull(TrainingMeasurements.definitions[result.measurementKey]) { "Unknown measurement key" }
            require(result.calculationVersion == definition.calculationVersion)
            require(result.numericValue == null || result.numericValue.isFinite())
            require(result.uncertainty == null || result.uncertainty.isFinite() && result.uncertainty >= 0)
            requireConfidence(result.confidence)
            require(result.occurrenceUs == null || result.occurrenceUs in movement.startUs..movement.endUs)
            require(result.frameIndex == null || result.frameIndex >= 0)
            require(if (result.valueType == ValueType.NUMERIC) result.categoricalValue == null else result.numericValue == null)
            if (result.state == ResultState.VALID) require(
                if (result.valueType == ValueType.NUMERIC) result.numericValue != null else !result.categoricalValue.isNullOrBlank())
            dao.insert(MeasurementResultRow(result))
        }
        bodyIds.forEach { id ->
            require(dao.bodyMeasurement(id)?.value?.userId == userId)
            dao.insert(AnalysisBodyMeasurementRow(AnalysisBodyMeasurement(analysis.analysisId, id)))
        }
        calibrationIds.forEach { id ->
            require(dao.calibration(id)?.value?.userId == userId)
            dao.insert(AnalysisCalibrationRow(AnalysisCalibration(analysis.analysisId, id)))
        }
        dao.movementState(movement.movementId, when (analysis.state) {
            AnalysisState.COMPLETED -> MovementState.COMPLETED
            AnalysisState.FAILED -> MovementState.FAILED
            else -> MovementState.PARTIAL
        })
    }

    fun preferredAnalysis(movementId: String, policy: AnalyzerPolicy): MovementAnalysis? {
        val candidates = dao.analyses(movementId).map { it.value }
        return selectMovementAnalysis(candidates, policy)
    }

    fun movementEvidence(sessionId: String, displayedNumber: Int): MovementEvidence? = atomic {
        require(displayedNumber > 0)
        val movement = movements(sessionId).getOrNull(displayedNumber - 1) ?: return@atomic null
        val recording = requireNotNull(recording(sessionId))
        val analyses = dao.analyses(movement.movementId).map { it.value }
        MovementEvidence(movement, recording, dao.observation(movement.movementId)?.value,
            dao.labels(movement.movementId).map { it.value }, dao.movementEvents(movement.movementId).map { it.value },
            analyses, analyses.flatMap { dao.results(it.analysisId).map { row -> row.value } }, tracks(recording.recordingId))
    }

    fun movementEvidence(movementId: String): MovementEvidence? = atomic {
        val movement = dao.movement(movementId)?.value ?: return@atomic null
        val recording = requireNotNull(recording(movement.sessionId))
        val analyses = dao.analyses(movement.movementId).map { it.value }
        MovementEvidence(movement, recording, dao.observation(movement.movementId)?.value,
            dao.labels(movement.movementId).map { it.value }, dao.movementEvents(movement.movementId).map { it.value },
            analyses, analyses.flatMap { dao.results(it.analysisId).map { row -> row.value } }, tracks(recording.recordingId))
    }

    fun movementDisplayedNumber(movementId: String): Int = atomic {
        val movement = dao.movement(movementId)?.value ?: return@atomic 1
        val currentRun = currentRun(movement.sessionId)
        val allMovements = if (currentRun != null) movementsForRun(currentRun.runId) else movements(movement.sessionId)
        val idx = allMovements.indexOfFirst { it.movementId == movementId }
        if (idx >= 0) idx + 1 else 1
    }

    /** Policy is applied at read time. Reanalyses cannot count one movement twice in a series. */
    fun history(query: HistoryQuery): List<MeasurementResult> = atomic {
        require(query.limit in 1..1000)
        requireConfidence(query.minimumConfidence)
        val found = mutableListOf<MeasurementResult>()
        val preferred = mutableMapOf<String, String?>()
        var offset = 0
        while (found.size < query.limit) {
            val page = dao.historyCandidates(query.userId, query.measurementKey, query.analyzer.analyzerKey,
                query.analyzer.approvedVersions, 200, offset)
            if (page.isEmpty()) break
            offset += page.size
            for (row in page) {
                val result = row.value
                val analysis = requireNotNull(dao.analysis(result.analysisId)).value
                val preferredId = preferred.getOrPut(analysis.movementId) {
                    preferredAnalysis(analysis.movementId, query.analyzer)?.analysisId
                }
                if (result.analysisId != preferredId || result.state !in query.acceptedStates) continue
                if (query.side != null && result.side != query.side || query.role != null && result.role != query.role) continue
                if (query.minimumConfidence != null && (result.confidence ?: -1.0) < query.minimumConfidence) continue
                if (!dao.labels(analysis.movementId).map { it.value.machineKey }.containsAll(query.labelKeys)) continue
                found += result
                if (found.size == query.limit) break
            }
        }
        found
    }

    fun addBodyMeasurement(value: UserBodyMeasurement) {
        require(value.value.isFinite() && value.value > 0 && value.canonicalUnit == "cm")
        dao.insert(UserBodyMeasurementRow(value))
    }
    fun bodyMeasurements(userId: String) = dao.bodyMeasurements(userId).map { it.value }
    fun addCalibration(value: UserCalibration) = dao.insert(UserCalibrationRow(value))
    fun calibrations(userId: String) = dao.calibrations(userId).map { it.value }
    fun sessionBodyIds(sessionId: String) = dao.sessionBodyIds(sessionId)

    /** Record intent before touching the filesystem. Interrupted deletion is recoverable on next startup. */
    fun deleteVideo(sessionId: String) {
        val recording = requireNotNull(recording(sessionId))
        dao.update(MasterRecordingRow(recording.copy(sourceState = SourceState.DELETING)))
        val file = file(recording.filePath)
        check(!file.exists() || file.delete()) { "Video could not be deleted; deletion remains pending" }
        dao.update(MasterRecordingRow(recording.copy(sourceState = SourceState.DELETED)))
    }

    /** Explicitly deleting a session removes its owned DB rows; file removal uses its separate lifecycle. */
    fun deleteSession(sessionId: String) = atomic { dao.deleteSession(sessionId) }

    fun recoverInterruptedWork(): List<RecordingSession> {
        dao.recordings().forEach { row ->
            val recording = row.value
            // A failed deletion stays explicitly pending without blocking other sessions at startup.
            if (recording.sourceState == SourceState.DELETING) runCatching { deleteVideo(recording.sessionId) }
            else if (recording.sourceState == SourceState.AVAILABLE && !file(recording.filePath).exists())
                dao.update(MasterRecordingRow(recording.copy(sourceState = SourceState.MISSING)))
        }
        dao.recoverable().forEach { row ->
            if (row.value.state in setOf(SessionState.PREPARING, SessionState.RECORDING, SessionState.FINALIZING,
                    SessionState.LANDMARKS_PROCESSING, SessionState.SEGMENTING, SessionState.ANALYZING))
                dao.sessionState(row.value.sessionId, SessionState.PARTIAL, "interrupted; resume from persisted evidence")
        }
        return dao.recoverable().map { it.value }
    }

    private fun requireTrackForSession(trackId: String, sessionId: String) {
        val track = requireNotNull(dao.track(trackId)) { "Landmark track does not exist" }.value
        val recording = requireNotNull(dao.recording(sessionId)) { "Master recording does not exist" }.value
        require(track.recordingId == recording.recordingId) {
            "Landmark track must belong to the movement's master recording"
        }
    }
    private fun requireConfidence(value: Double?) { require(value == null || value.isFinite() && value in 0.0..1.0) }
}
