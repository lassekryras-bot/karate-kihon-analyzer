package dk.lasse.karatecliprecorder.training

import dk.lasse.karateanalyzer.capture.qom.QomEndBoundaryKind
import dk.lasse.karateanalyzer.capture.retrospective.*
import dk.lasse.karateanalyzer.core.PoseFrame
import dk.lasse.karateanalyzer.geometry.CanonicalGeometryCodec
import dk.lasse.karateanalyzer.geometry.CanonicalGeometryDescriptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class PrototypeModelAssetIdentity(
    val modelKey: String,
    val sha256: String,
    val pipelineConfiguration: String,
)

data class LiteHeavyPrototypeRunResult(
    val prototypeId: String,
    val artifactDirectory: File,
    val manifestFile: File,
    val plan: LiteHeavyPrototypePlan,
    val outcomes: List<PrototypeHeavyWindowOutcome>,
    val liteElapsedMs: Long,
    val heavyElapsedMs: Long,
    val totalElapsedMs: Long,
)

/**
 * Isolated developer prototype. It publishes files only below [outputRoot] and never writes Room,
 * recording state, production landmark tracks, movements, runs, calibration, or queue state.
 */
class LiteHeavyPrototypeRunner(
    private val outputRoot: File,
    private val liteProcessor: VideoPoseProcessor,
    private val heavyProcessor: WindowedVideoPoseProcessor,
    private val liteModel: PrototypeModelAssetIdentity,
    private val heavyModel: PrototypeModelAssetIdentity,
    private val segmenterConfig: RetrospectiveSegmenterConfig,
    private val canonicalGeometry: CanonicalGeometryDescriptor,
    private val paddingBeforeMs: Long = 1_000,
    private val paddingAfterMs: Long = 1_000,
    private val checkActive: () -> Unit = {},
    private val publication: (() -> Unit) -> Unit = { it() },
    private val elapsedRealtimeMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val segment: (String, String, List<PoseFrame>) -> RetrospectiveSessionResult =
        { sequenceId, path, frames ->
            RetrospectiveSessionSegmenter(segmenterConfig).segment(sequenceId, path, frames)
        },
) {
    fun run(recordingId: String, videoFile: File, sourceDurationMs: Long): LiteHeavyPrototypeRunResult {
        require(recordingId.isNotBlank())
        require(videoFile.isFile) { "Prototype source video is unavailable: ${videoFile.absolutePath}" }
        require(sourceDurationMs > 0)
        require(canonicalGeometry.isAvailable && canonicalGeometry.recordingId == recordingId)
        checkActive()

        val prototypeId = idFactory()
        require(prototypeId.isNotBlank())
        val recordingRoot = File(outputRoot, recordingId)
        val staging = File(recordingRoot, "$prototypeId.pending")
        val published = File(recordingRoot, prototypeId)
        check(!staging.exists() && !published.exists()) { "Prototype ID cannot be reused" }
        check(staging.mkdirs()) { "Could not create prototype staging directory" }

        val totalStarted = elapsedRealtimeMs()
        try {
            val liteStarted = elapsedRealtimeMs()
            val liteFrames = liteProcessor.processVideo(videoFile) { _, _ -> checkActive() }
            val liteElapsedMs = (elapsedRealtimeMs() - liteStarted).coerceAtLeast(0)
            require(liteFrames.isNotEmpty()) { "Lite discovery produced no landmark frames" }
            require(liteFrames.last().timestampMs <= sourceDurationMs) {
                "Lite source timestamps exceed recording duration"
            }

            val liteTrackId = "lite-$prototypeId"
            val liteFile = File(staging, "lite.mls")
            val liteContentHash = LandmarkFiles.write(liteFile, liteFrames, checkActive, publication)
            val geometryCompatibilityKey = geometryCompatibilityKey(canonicalGeometry)
            val liteTrack = trackProvenance(
                recordingId = recordingId,
                trackId = liteTrackId,
                role = PrototypeTrackRole.LITE_DISCOVERY,
                model = liteModel,
                file = liteFile,
                contentHash = liteContentHash,
                requestedStartMs = 0,
                requestedEndMs = sourceDurationMs,
                frames = liteFrames,
                elapsedMs = liteElapsedMs,
                geometryCompatibilityKey = geometryCompatibilityKey,
            )
            val liteSegmentation = segment(recordingId, videoFile.absolutePath, liteFrames)
            val candidates = movementCandidates(liteSegmentation) + unknownCandidates(
                liteSegmentation,
                sourceDurationMs,
            )
            val plan = LiteHeavyPrototypePlanner.plan(
                recordingId = recordingId,
                sourceDurationMs = sourceDurationMs,
                liteTrack = liteTrack,
                candidates = candidates,
                paddingBeforeMs = paddingBeforeMs,
                paddingAfterMs = paddingAfterMs,
            )

            var heavyElapsedMs = 0L
            val outcomes = plan.windows.map { request ->
                checkActive()
                val heavyStarted = elapsedRealtimeMs()
                val extracted = heavyProcessor.processWindow(
                    videoFile,
                    VideoTimeWindow(request.requestedStartTimestampMs, request.requestedEndTimestampMs),
                ) { _, _ -> checkActive() }
                val elapsed = (elapsedRealtimeMs() - heavyStarted).coerceAtLeast(0)
                heavyElapsedMs += elapsed
                require(extracted.frames.isNotEmpty()) {
                    "Heavy ${request.windowId} produced no frames in requested source-time window"
                }

                val heavyTrackId = "heavy-$prototypeId-${request.windowId}"
                val heavyFile = File(staging, "${request.windowId}.mls")
                val heavyContentHash = LandmarkFiles.write(
                    heavyFile,
                    extracted.frames,
                    checkActive,
                    publication,
                )
                val heavyTrack = trackProvenance(
                    recordingId = recordingId,
                    trackId = heavyTrackId,
                    role = PrototypeTrackRole.HEAVY_WINDOW,
                    model = heavyModel,
                    file = heavyFile,
                    contentHash = heavyContentHash,
                    requestedStartMs = request.requestedStartTimestampMs,
                    requestedEndMs = request.requestedEndTimestampMs,
                    frames = extracted.frames,
                    elapsedMs = elapsed,
                    geometryCompatibilityKey = geometryCompatibilityKey,
                )
                val segmentation = segment(
                    "${recordingId}:${request.windowId}",
                    videoFile.absolutePath,
                    extracted.frames,
                )
                val movements = segmentation.movements.map {
                    PrototypeFinalMovementInterval(
                        startTimestampMs = it.logicalStartTimestampMs,
                        endTimestampMs = it.logicalEndTimestampMs,
                        endBoundaryKind = it.endBoundaryKind,
                        unknownEvidenceIntervals = it.unknownEvidenceIntervals,
                    )
                }
                val actualStart = requireNotNull(heavyTrack.actualStartTimestampMs)
                val actualEnd = requireNotNull(heavyTrack.actualEndTimestampMs)
                val uncertainty = buildSet {
                    add(PrototypeWindowUncertainty.TRACKING_RESET_AT_WINDOW_START)
                    if (request.truncatedAtRecordingStart) add(PrototypeWindowUncertainty.START_PADDING_CLIPPED)
                    if (request.truncatedAtRecordingEnd) add(PrototypeWindowUncertainty.END_PADDING_CLIPPED)
                    if (actualStart > request.requestedStartTimestampMs || actualEnd < request.requestedEndTimestampMs) {
                        add(PrototypeWindowUncertainty.ACTUAL_COVERAGE_NARROWER)
                    }
                    if (movements.any { it.startTimestampMs == actualStart }) {
                        add(PrototypeWindowUncertainty.SEGMENT_TOUCHES_ACTUAL_START)
                    }
                    if (movements.any { it.endTimestampMs == actualEnd }) {
                        add(PrototypeWindowUncertainty.SEGMENT_TOUCHES_ACTUAL_END)
                    }
                    if (movements.any { it.endBoundaryKind == QomEndBoundaryKind.RECORDING_END_WITHOUT_STOP_EVIDENCE }) {
                        add(PrototypeWindowUncertainty.RECORDING_END_WITHOUT_STOP_EVIDENCE)
                    }
                    if (movements.any { it.unknownEvidenceIntervals.isNotEmpty() }) {
                        add(PrototypeWindowUncertainty.UNKNOWN_EVIDENCE)
                    }
                }
                PrototypeHeavyWindowOutcome(
                    request = request,
                    state = PrototypeWindowState.COMPLETED,
                    heavyTrack = heavyTrack,
                    finalMovements = movements,
                    uncertainty = uncertainty,
                )
            }

            val totalElapsedMs = (elapsedRealtimeMs() - totalStarted).coerceAtLeast(0)
            val manifest = manifestJson(
                prototypeId,
                canonicalGeometry,
                plan,
                outcomes,
                liteElapsedMs,
                heavyElapsedMs,
                totalElapsedMs,
            )
            val manifestFile = File(staging, "manifest.json")
            writeDurably(manifestFile, manifest.toString(2))
            publication {
                checkActive()
                check(recordingRoot.isDirectory || recordingRoot.mkdirs())
                check(!published.exists() && staging.renameTo(published)) {
                    "Could not atomically publish prototype artifacts"
                }
            }
            return LiteHeavyPrototypeRunResult(
                prototypeId = prototypeId,
                artifactDirectory = published,
                manifestFile = File(published, manifestFile.name),
                plan = plan,
                outcomes = outcomes,
                liteElapsedMs = liteElapsedMs,
                heavyElapsedMs = heavyElapsedMs,
                totalElapsedMs = totalElapsedMs,
            )
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw error
        }
    }

    private fun movementCandidates(result: RetrospectiveSessionResult): List<PrototypeCandidateInterval> =
        result.movements.mapIndexed { index, movement ->
            PrototypeCandidateInterval(
                candidateId = "movement-${index + 1}",
                startTimestampMs = movement.logicalStartTimestampMs,
                endTimestampMs = movement.logicalEndTimestampMs,
                kind = PrototypeCandidateKind.DETECTED_MOVEMENT,
            )
        }

    private fun unknownCandidates(
        result: RetrospectiveSessionResult,
        sourceDurationMs: Long,
    ): List<PrototypeCandidateInterval> {
        val ranges = mutableListOf<Pair<Long, Long>>()
        var start: Long? = null
        result.qomTimeline.forEach { evidence ->
            if (!evidence.isAvailable && start == null) start = evidence.timestampMs
            if (evidence.isAvailable && start != null) {
                if (evidence.timestampMs > start!!) ranges += start!! to evidence.timestampMs
                start = null
            }
        }
        start?.let { unknownStart ->
            if (sourceDurationMs > unknownStart) ranges += unknownStart to sourceDurationMs
        }
        return ranges.mapIndexed { index, (rangeStart, rangeEnd) ->
            PrototypeCandidateInterval(
                candidateId = "unknown-${index + 1}",
                startTimestampMs = rangeStart,
                endTimestampMs = rangeEnd,
                kind = PrototypeCandidateKind.UNKNOWN_EVIDENCE,
            )
        }
    }

    private fun trackProvenance(
        recordingId: String,
        trackId: String,
        role: PrototypeTrackRole,
        model: PrototypeModelAssetIdentity,
        file: File,
        contentHash: String,
        requestedStartMs: Long,
        requestedEndMs: Long,
        frames: List<PoseFrame>,
        elapsedMs: Long,
        geometryCompatibilityKey: String,
    ) = PrototypeTrackProvenance(
        recordingId = recordingId,
        landmarkTrackId = trackId,
        role = role,
        modelKey = model.modelKey,
        modelSha256 = model.sha256,
        pipelineConfiguration = model.pipelineConfiguration,
        geometryId = "${canonicalGeometry.geometryId}:$trackId",
        geometryCompatibilityKey = geometryCompatibilityKey,
        fileReference = file.name,
        formatId = LandmarkFiles.FORMAT_ID,
        formatVersion = LandmarkFiles.VERSION,
        contentSha256 = contentHash,
        requestedStartTimestampMs = requestedStartMs,
        requestedEndTimestampMs = requestedEndMs,
        actualStartTimestampMs = frames.firstOrNull()?.timestampMs,
        actualEndTimestampMs = frames.lastOrNull()?.timestampMs,
        frameCount = frames.size,
        extractionElapsedMs = elapsedMs,
        calibrationApplied = false,
    )

    private fun geometryCompatibilityKey(geometry: CanonicalGeometryDescriptor): String {
        val recordingGeometry = geometry.copy(landmarkTrackId = null, trackHash = null)
        return LandmarkFiles.sha256(
            ByteArrayInputStream(CanonicalGeometryCodec.encode(recordingGeometry).toByteArray(Charsets.UTF_8)),
        )
    }

    private fun manifestJson(
        prototypeId: String,
        geometry: CanonicalGeometryDescriptor,
        plan: LiteHeavyPrototypePlan,
        outcomes: List<PrototypeHeavyWindowOutcome>,
        liteElapsedMs: Long,
        heavyElapsedMs: Long,
        totalElapsedMs: Long,
    ) = JSONObject().apply {
        put("schema", "lite-heavy-prototype-manifest-v1")
        put("prototypeId", prototypeId)
        put("recordingId", plan.recordingId)
        put("sourceDurationMs", plan.sourceDurationMs)
        put("productionActivated", false)
        put("calibrationApplied", false)
        put("canonicalGeometry", JSONObject(CanonicalGeometryCodec.encode(geometry.copy(landmarkTrackId = null))))
        put("prototypeConfigKey", plan.prototypeConfigKey)
        put("paddingPolicyKey", plan.paddingPolicyKey)
        put("paddingBeforeMs", plan.paddingBeforeMs)
        put("paddingAfterMs", plan.paddingAfterMs)
        put("discoveryDisposition", plan.discoveryDisposition.name)
        put("liteElapsedMs", liteElapsedMs)
        put("heavyElapsedMs", heavyElapsedMs)
        put("totalElapsedMs", totalElapsedMs)
        put("liteTrack", trackJson(plan.liteTrack))
        put("windows", JSONArray(outcomes.map { outcomeJson(it) }))
    }

    private fun outcomeJson(outcome: PrototypeHeavyWindowOutcome) = JSONObject().apply {
        val request = outcome.request
        put("windowId", request.windowId)
        put("candidateId", request.candidate.candidateId)
        put("candidateKind", request.candidate.kind.name)
        put("candidateStartTimestampMs", request.candidate.startTimestampMs)
        put("candidateEndTimestampMs", request.candidate.endTimestampMs)
        put("requestedStartTimestampMs", request.requestedStartTimestampMs)
        put("requestedEndTimestampMs", request.requestedEndTimestampMs)
        put("truncatedAtRecordingStart", request.truncatedAtRecordingStart)
        put("truncatedAtRecordingEnd", request.truncatedAtRecordingEnd)
        put("overlappingWindowIds", JSONArray(request.overlappingWindowIds))
        put("state", outcome.state.name)
        put("heavyTrack", outcome.heavyTrack?.let(::trackJson) ?: JSONObject.NULL)
        put("uncertainty", JSONArray(outcome.uncertainty.map { it.name }.sorted()))
        put("failureReason", outcome.failureReason ?: JSONObject.NULL)
        put("finalMovements", JSONArray(outcome.finalMovements.map { movement ->
            JSONObject().apply {
                put("startTimestampMs", movement.startTimestampMs)
                put("endTimestampMs", movement.endTimestampMs)
                put("endBoundaryKind", movement.endBoundaryKind.name)
                put("unknownEvidenceIntervals", JSONArray(movement.unknownEvidenceIntervals.map { unknown ->
                    JSONObject().put("startTimestampMs", unknown.startTimestampMs)
                        .put("endTimestampMs", unknown.endTimestampMs)
                }))
            }
        }))
    }

    private fun trackJson(track: PrototypeTrackProvenance) = JSONObject().apply {
        put("recordingId", track.recordingId)
        put("landmarkTrackId", track.landmarkTrackId)
        put("role", track.role.name)
        put("modelKey", track.modelKey)
        put("modelSha256", track.modelSha256)
        put("pipelineConfiguration", track.pipelineConfiguration)
        put("geometryId", track.geometryId)
        put("geometryCompatibilityKey", track.geometryCompatibilityKey)
        put("fileReference", track.fileReference)
        put("formatId", track.formatId)
        put("formatVersion", track.formatVersion)
        put("contentSha256", track.contentSha256)
        put("requestedStartTimestampMs", track.requestedStartTimestampMs)
        put("requestedEndTimestampMs", track.requestedEndTimestampMs)
        put("actualStartTimestampMs", track.actualStartTimestampMs ?: JSONObject.NULL)
        put("actualEndTimestampMs", track.actualEndTimestampMs ?: JSONObject.NULL)
        put("frameCount", track.frameCount)
        put("extractionElapsedMs", track.extractionElapsedMs)
        put("calibrationApplied", track.calibrationApplied)
    }

    private fun writeDurably(file: File, content: String) {
        check(!file.exists())
        FileOutputStream(file).use { stream ->
            stream.write(content.toByteArray(Charsets.UTF_8))
            stream.flush()
            stream.fd.sync()
        }
    }
}
