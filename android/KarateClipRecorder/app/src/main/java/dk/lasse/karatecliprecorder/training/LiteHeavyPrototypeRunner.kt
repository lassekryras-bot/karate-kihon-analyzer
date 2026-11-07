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

data class FullHeavyComparatorRunResult(
    val prototypeId: String,
    val artifactDirectory: File,
    val manifestFile: File,
    val heavyTrack: PrototypeTrackProvenance,
    val finalMovements: List<PrototypeFinalMovementInterval>,
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
    private val appBuildIdentity: String,
    private val sourceRevision: String = "unknown",
    private val sourceDirtyState: String = "unknown",
    private val apkSha256: String = "unknown",
    private val deviceConditions: String = "unknown",
    private val serializedPrototypeEntry: Boolean,
    private val paddingBeforeMs: Long = 1_000,
    private val paddingAfterMs: Long = 1_000,
    private val checkActive: () -> Unit = {},
    private val publication: (() -> Unit) -> Unit = { it() },
    private val elapsedRealtimeMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val wallClockMs: () -> Long = { System.currentTimeMillis() },
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
        require(appBuildIdentity.isNotBlank())
        require(sourceRevision.isNotBlank() && sourceDirtyState.isNotBlank())
        require(apkSha256.isNotBlank() && deviceConditions.isNotBlank())
        require(serializedPrototypeEntry) { "Prototype staging cleanup requires serialized entry" }
        checkActive()

        val prototypeId = idFactory()
        require(prototypeId.isNotBlank())
        val recordingRoot = File(outputRoot, recordingId)
        val runSequenceIndex = completedRunCount(recordingRoot) + 1
        val startedAtEpochMs = wallClockMs()
        val staging = File(recordingRoot, "$prototypeId.pending")
        val published = File(recordingRoot, prototypeId)
        check(!staging.exists() && !published.exists()) { "Prototype ID cannot be reused" }
        check(staging.mkdirs()) { "Could not create prototype staging directory" }
        discardAbandonedStaging(recordingRoot, staging)

        val totalStarted = elapsedRealtimeMs()
        try {
            val sourceContentHash = videoFile.inputStream().use(LandmarkFiles::sha256)
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
                sourceContentHash = sourceContentHash,
            )
            val liteSegmentation = segment(recordingId, videoFile.absolutePath, liteFrames)
            val candidates = movementCandidates(liteSegmentation) + unknownCandidates(
                liteSegmentation,
                liteFrames,
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
            val outcomes = plan.executionWindows.map { request ->
                checkActive()
                val heavyStarted = elapsedRealtimeMs()
                val extracted = heavyProcessor.processWindow(
                    videoFile,
                    VideoTimeWindow(request.requestedStartTimestampMs, request.requestedEndTimestampMs),
                ) { _, _ -> checkActive() }
                val elapsed = (elapsedRealtimeMs() - heavyStarted).coerceAtLeast(0)
                heavyElapsedMs += elapsed
                require(extracted.frames.isNotEmpty()) {
                    "Heavy ${request.executionWindowId} produced no frames in requested source-time window"
                }

                val heavyTrackId = "heavy-$prototypeId-${request.executionWindowId}"
                val heavyFile = File(staging, "${request.executionWindowId}.mls")
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
                    sourceContentHash = sourceContentHash,
                )
                val segmentation = segment(
                    "${recordingId}:${request.executionWindowId}",
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
                sourceContentHash,
                runSequenceIndex,
                startedAtEpochMs,
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

    fun runFullHeavyComparator(
        recordingId: String,
        videoFile: File,
        sourceDurationMs: Long,
    ): FullHeavyComparatorRunResult {
        require(recordingId.isNotBlank())
        require(videoFile.isFile) { "Comparator source video is unavailable: ${videoFile.absolutePath}" }
        require(sourceDurationMs > 0)
        require(canonicalGeometry.isAvailable && canonicalGeometry.recordingId == recordingId)
        checkActive()

        val prototypeId = "full-heavy-${idFactory()}"
        val recordingRoot = File(outputRoot, recordingId)
        val runSequenceIndex = completedRunCount(recordingRoot) + 1
        val startedAtEpochMs = wallClockMs()
        val staging = File(recordingRoot, "$prototypeId.pending")
        val published = File(recordingRoot, prototypeId)
        check(!staging.exists() && !published.exists())
        check(staging.mkdirs())
        discardAbandonedStaging(recordingRoot, staging)
        val totalStarted = elapsedRealtimeMs()
        try {
            val sourceContentHash = videoFile.inputStream().use(LandmarkFiles::sha256)
            val heavyStarted = elapsedRealtimeMs()
            val frames = heavyProcessor.processVideo(videoFile) { _, _ -> checkActive() }
            val heavyElapsedMs = (elapsedRealtimeMs() - heavyStarted).coerceAtLeast(0)
            require(frames.isNotEmpty()) { "Full-Heavy comparator produced no landmark frames" }
            require(frames.last().timestampMs <= sourceDurationMs)
            val trackId = "heavy-full-$prototypeId"
            val trackFile = File(staging, "heavy-full.mls")
            val contentHash = LandmarkFiles.write(trackFile, frames, checkActive, publication)
            val track = trackProvenance(
                recordingId = recordingId,
                trackId = trackId,
                role = PrototypeTrackRole.FULL_HEAVY_COMPARATOR,
                model = heavyModel,
                file = trackFile,
                contentHash = contentHash,
                requestedStartMs = 0,
                requestedEndMs = sourceDurationMs,
                frames = frames,
                elapsedMs = heavyElapsedMs,
                geometryCompatibilityKey = geometryCompatibilityKey(canonicalGeometry),
                sourceContentHash = sourceContentHash,
            )
            val segmentation = segment("$recordingId:full-heavy", videoFile.absolutePath, frames)
            val movements = segmentation.movements.map { movement ->
                PrototypeFinalMovementInterval(
                    movement.logicalStartTimestampMs,
                    movement.logicalEndTimestampMs,
                    movement.endBoundaryKind,
                    movement.unknownEvidenceIntervals,
                )
            }
            val totalElapsedMs = (elapsedRealtimeMs() - totalStarted).coerceAtLeast(0)
            val manifest = fullHeavyManifestJson(
                prototypeId,
                sourceDurationMs,
                sourceContentHash,
                track,
                movements,
                heavyElapsedMs,
                totalElapsedMs,
                runSequenceIndex,
                startedAtEpochMs,
            )
            val manifestFile = File(staging, "manifest.json")
            writeDurably(manifestFile, manifest.toString(2))
            publication {
                checkActive()
                check(recordingRoot.isDirectory || recordingRoot.mkdirs())
                check(!published.exists() && staging.renameTo(published))
            }
            return FullHeavyComparatorRunResult(
                prototypeId,
                published,
                File(published, manifestFile.name),
                track,
                movements,
                heavyElapsedMs,
                totalElapsedMs,
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
        frames: List<PoseFrame>,
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
        val maximumGapMs = segmenterConfig.qomConfig.maximumFrameGapMs
        val firstTimestamp = frames.first().timestampMs
        if (firstTimestamp > maximumGapMs) ranges += 0L to firstTimestamp
        frames.zipWithNext().forEach { (previous, next) ->
            if (next.timestampMs - previous.timestampMs > maximumGapMs) {
                ranges += previous.timestampMs to next.timestampMs
            }
        }
        val lastTimestamp = frames.last().timestampMs
        if (sourceDurationMs - lastTimestamp > maximumGapMs) ranges += lastTimestamp to sourceDurationMs

        val merged = ranges
            .filter { (rangeStart, rangeEnd) -> rangeStart < rangeEnd }
            .sortedBy { it.first }
            .fold(mutableListOf<Pair<Long, Long>>()) { resultRanges, range ->
                val last = resultRanges.lastOrNull()
                if (last == null || range.first > last.second) {
                    resultRanges += range
                } else if (range.second > last.second) {
                    resultRanges[resultRanges.lastIndex] = last.first to range.second
                }
                resultRanges
            }
        return merged.mapIndexed { index, (rangeStart, rangeEnd) ->
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
        sourceContentHash: String,
    ) = PrototypeTrackProvenance(
        recordingId = recordingId,
        landmarkTrackId = trackId,
        role = role,
        modelKey = model.modelKey,
        modelSha256 = model.sha256,
        pipelineConfiguration = model.pipelineConfiguration,
        geometryBinding = canonicalGeometry.copy(
            landmarkTrackId = trackId,
            sourceHash = sourceContentHash,
            trackHash = contentHash,
        ),
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
        sourceContentHash: String,
        runSequenceIndex: Int,
        startedAtEpochMs: Long,
    ) = JSONObject().apply {
        put("schema", "lite-heavy-prototype-manifest-v2")
        put("prototypeId", prototypeId)
        put("recordingId", plan.recordingId)
        put("sourceContentSha256", sourceContentHash)
        put("sourceDurationMs", plan.sourceDurationMs)
        put("appBuildIdentity", appBuildIdentity)
        put("sourceRevision", sourceRevision)
        put("sourceDirtyState", sourceDirtyState)
        put("apkSha256", apkSha256)
        put("deviceConditions", deviceConditions)
        put("runSequenceIndex", runSequenceIndex)
        put("startedAtEpochMs", startedAtEpochMs)
        put("productionActivated", false)
        put("calibrationApplied", false)
        put("publicationSemantics", "all-or-nothing-no-resume-v1")
        put("abandonedStagingPolicy", "discard-prior-pending-under-serialized-entry-v2")
        put("canonicalGeometry", JSONObject(CanonicalGeometryCodec.encode(geometry.copy(landmarkTrackId = null))))
        put("prototypeConfigKey", plan.prototypeConfigKey)
        put("paddingPolicyKey", plan.paddingPolicyKey)
        put("paddingBeforeMs", plan.paddingBeforeMs)
        put("paddingAfterMs", plan.paddingAfterMs)
        put("requestedCoverageSumMs", plan.requestedCoverageSumMs)
        put("executionCoverageUnionMs", plan.executionCoverageUnionMs)
        put("segmenterConfig", segmenterConfigJson(segmenterConfig))
        put("discoveryDisposition", plan.discoveryDisposition.name)
        put("liteElapsedMs", liteElapsedMs)
        put("heavyElapsedMs", heavyElapsedMs)
        put("totalElapsedMs", totalElapsedMs)
        put("liteTrack", trackJson(plan.liteTrack))
        put("candidateWindows", JSONArray(plan.windows.map { candidateWindowJson(it) }))
        put("executionWindows", JSONArray(outcomes.map { outcomeJson(it) }))
    }

    private fun candidateWindowJson(window: PrototypeHeavyWindowRequest) = JSONObject().apply {
        put("windowId", window.windowId)
        put("candidateId", window.candidate.candidateId)
        put("candidateKind", window.candidate.kind.name)
        put("candidateStartTimestampMs", window.candidate.startTimestampMs)
        put("candidateEndTimestampMs", window.candidate.endTimestampMs)
        put("requestedStartTimestampMs", window.requestedStartTimestampMs)
        put("requestedEndTimestampMs", window.requestedEndTimestampMs)
        put("truncatedAtRecordingStart", window.truncatedAtRecordingStart)
        put("truncatedAtRecordingEnd", window.truncatedAtRecordingEnd)
        put("overlappingWindowIds", JSONArray(window.overlappingWindowIds))
    }

    private fun outcomeJson(outcome: PrototypeHeavyWindowOutcome) = JSONObject().apply {
        val request = outcome.request
        put("executionWindowId", request.executionWindowId)
        put("candidateWindowIds", JSONArray(request.candidateWindowIds))
        put("requestedStartTimestampMs", request.requestedStartTimestampMs)
        put("requestedEndTimestampMs", request.requestedEndTimestampMs)
        put("truncatedAtRecordingStart", request.truncatedAtRecordingStart)
        put("truncatedAtRecordingEnd", request.truncatedAtRecordingEnd)
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

    private fun fullHeavyManifestJson(
        prototypeId: String,
        sourceDurationMs: Long,
        sourceContentHash: String,
        heavyTrack: PrototypeTrackProvenance,
        movements: List<PrototypeFinalMovementInterval>,
        heavyElapsedMs: Long,
        totalElapsedMs: Long,
        runSequenceIndex: Int,
        startedAtEpochMs: Long,
    ) = JSONObject().apply {
        put("schema", "full-heavy-comparator-manifest-v1")
        put("prototypeId", prototypeId)
        put("recordingId", heavyTrack.recordingId)
        put("sourceDurationMs", sourceDurationMs)
        put("sourceContentSha256", sourceContentHash)
        put("appBuildIdentity", appBuildIdentity)
        put("sourceRevision", sourceRevision)
        put("sourceDirtyState", sourceDirtyState)
        put("apkSha256", apkSha256)
        put("deviceConditions", deviceConditions)
        put("runSequenceIndex", runSequenceIndex)
        put("startedAtEpochMs", startedAtEpochMs)
        put("productionActivated", false)
        put("calibrationApplied", false)
        put("publicationSemantics", "all-or-nothing-no-resume-v1")
        put("abandonedStagingPolicy", "discard-prior-pending-under-serialized-entry-v2")
        put("runKind", "FULL_HEAVY_COMPARATOR")
        put("runOrder", "recording-prototype-sequence-$runSequenceIndex")
        put("windowCount", 1)
        put("trackerResetCount", 1)
        put("heavyElapsedMs", heavyElapsedMs)
        put("totalElapsedMs", totalElapsedMs)
        put("segmenterConfig", segmenterConfigJson(segmenterConfig))
        put("canonicalGeometry", JSONObject(CanonicalGeometryCodec.encode(canonicalGeometry.copy(landmarkTrackId = null))))
        put("heavyTrack", trackJson(heavyTrack))
        put("finalMovements", JSONArray(movements.map { movement ->
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
        put("geometryBinding", JSONObject(CanonicalGeometryCodec.encode(track.geometryBinding)))
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

    private fun segmenterConfigJson(config: RetrospectiveSegmenterConfig) = JSONObject().apply {
        put("profile", config.profile.name)
        put("cadence", config.cadence.name)
        put("cadenceMinimumInterMovementPauseMs", config.cadence.minInterMovementPauseMs)
        put("minMovementDurationMs", config.minMovementDurationMs)
        put("maxMovementDurationMs", config.maxMovementDurationMs)
        put("preRollMs", config.preRollMs)
        put("postRollMs", config.postRollMs)
        put("halfWindowDurationMs", config.halfWindowDurationMs)
        put("minimumSamplesInWindow", config.minimumSamplesInWindow)
        put("minimumWindowSpanMs", config.minimumWindowSpanMs)
        put("startThresholdNormalized", config.startThresholdNormalized)
        put("quietThresholdNormalized", config.quietThresholdNormalized)
        put("minQuietDwellMs", config.minQuietDwellMs)
        put("q20Percentile", config.q20Percentile)
        put("q90Percentile", config.q90Percentile)
        put("qom", JSONObject().apply {
            val qom = config.qomConfig
            put("detectorVersion", qom.detectorVersion)
            put("evidencePolicy", qom.evidencePolicy.name)
            put("evidencePolicyVersion", qom.evidencePolicy.policyVersion)
            put("minimumObservedConfidence", qom.minimumObservedConfidence)
            put("minimumCompositeSupport", qom.minimumCompositeSupport)
            put("positionMedianSamples", qom.positionMedianSamples)
            put("positionMeanSamples", qom.positionMeanSamples)
            put("rollingAreaDurationMs", qom.rollingAreaDurationMs)
            put("startGateMeters", qom.startGateMeters)
            put("stopGateMeters", qom.stopGateMeters)
            put("maximumFrameGapMs", qom.maximumFrameGapMs)
            put("preRollMs", qom.preRollMs)
            put("postRollMs", qom.postRollMs)
            put("minimumValidBlocks", qom.minimumValidBlocks)
        })
    }

    private fun writeDurably(file: File, content: String) {
        check(!file.exists())
        FileOutputStream(file).use { stream ->
            stream.write(content.toByteArray(Charsets.UTF_8))
            stream.flush()
            stream.fd.sync()
        }
    }

    private fun discardAbandonedStaging(recordingRoot: File, activeStaging: File) {
        check(serializedPrototypeEntry)
        val canonicalRoot = recordingRoot.canonicalFile
        val canonicalActive = activeStaging.canonicalFile
        recordingRoot.listFiles()
            .orEmpty()
            .filter { it.name.endsWith(".pending") }
            .forEach { candidate ->
                val canonicalCandidate = candidate.canonicalFile
                check(canonicalCandidate.parentFile == canonicalRoot) {
                    "Prototype staging escapes recording root: ${candidate.absolutePath}"
                }
                if (canonicalCandidate == canonicalActive) return@forEach
                check(candidate.isDirectory) {
                    "Prototype pending path is not a directory: ${candidate.absolutePath}"
                }
                check(canonicalCandidate.deleteRecursively()) {
                    "Could not discard abandoned prototype staging: ${candidate.absolutePath}"
                }
            }
    }

    private fun completedRunCount(recordingRoot: File): Int = recordingRoot.listFiles()
        .orEmpty()
        .count { it.isDirectory && !it.name.endsWith(".pending") && File(it, "manifest.json").isFile }
}
