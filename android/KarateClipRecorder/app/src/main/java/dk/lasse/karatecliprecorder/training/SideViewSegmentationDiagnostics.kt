package dk.lasse.karatecliprecorder.training

import dk.lasse.karateanalyzer.capture.qom.QomDetectorConfig
import dk.lasse.karateanalyzer.capture.qom.SideViewAlternatingPunchConfig
import dk.lasse.karateanalyzer.capture.qom.SideViewAlternatingPunchResult
import dk.lasse.karateanalyzer.capture.qom.SideViewAlternatingPunchSegmenter

/**
 * Deterministic diagnostic replay tied to a persisted run and its checksum-verified immutable MLS.
 * Derived evidence is reproduced on demand rather than copied into a competing persistence format.
 */
object SideViewSegmentationDiagnostics {
    enum class EvidenceSource { RECOMPUTED_FROM_RETAINED_RUN }

    data class Snapshot(
        val runId: String,
        val landmarkTrackId: String,
        val landmarkSha256: String,
        val segmenterVersion: String,
        val evidenceSource: EvidenceSource,
        val result: SideViewAlternatingPunchResult,
    )

    fun replay(repository: TrainingRepository, runId: String): Snapshot {
        val run = requireNotNull(repository.run(runId)) { "Processing run is unavailable" }
        val segmenterVersion = requireNotNull(run.segmenterVersion) { "Processing run has no segmenter version" }
        require(segmenterVersion in setOf(
            QomDetectorConfig.SIDE_VIEW_ALTERNATING_PUNCH_V1,
            QomDetectorConfig.SIDE_VIEW_ALTERNATING_PUNCH_V2,
        )) {
            "Processing run does not use the side-view alternating-punch segmenter"
        }
        val trackId = requireNotNull(run.sourceLandmarkTrackId) { "Processing run has no landmark track" }
        val recording = requireNotNull(repository.recording(run.sessionId)) { "Master recording is unavailable" }
        val track = repository.tracks(recording.recordingId).singleOrNull { it.landmarkTrackId == trackId }
            ?: error("Processing run landmark track is unavailable")
        val sha256 = requireNotNull(track.sha256) { "Landmark track has no content hash" }
        val frames = LandmarkFiles.read(
            repository.file(track.filePath),
            expectedHash = sha256,
            expectedFormatId = track.formatId,
        )
        val qomConfig = RecordingProcessingPlans.qomConfigForVersion(segmenterVersion)
        val result = SideViewAlternatingPunchSegmenter(
            SideViewAlternatingPunchConfig.from(qomConfig),
        ).segment(frames)
        return Snapshot(
            run.runId,
            trackId,
            sha256,
            segmenterVersion,
            EvidenceSource.RECOMPUTED_FROM_RETAINED_RUN,
            result,
        )
    }
}
