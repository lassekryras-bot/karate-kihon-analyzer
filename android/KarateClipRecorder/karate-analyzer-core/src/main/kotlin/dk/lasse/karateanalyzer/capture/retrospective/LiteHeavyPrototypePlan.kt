package dk.lasse.karateanalyzer.capture.retrospective

import dk.lasse.karateanalyzer.capture.qom.QomEndBoundaryKind
import dk.lasse.karateanalyzer.capture.qom.QomUnknownEvidenceInterval
import dk.lasse.karateanalyzer.geometry.CanonicalGeometryDescriptor

/** Isolated prototype roles. Production recording processing does not consume these values. */
enum class PrototypeTrackRole { LITE_DISCOVERY, HEAVY_WINDOW, FULL_HEAVY_COMPARATOR }

/**
 * Track identity and source-time coverage retained by the Lite/Heavy prototype.
 * Requested bounds describe intended processing; actual bounds describe frames that were produced.
 */
data class PrototypeTrackProvenance(
    val recordingId: String,
    val landmarkTrackId: String,
    val role: PrototypeTrackRole,
    val modelKey: String,
    val modelSha256: String,
    val pipelineConfiguration: String,
    val geometryBinding: CanonicalGeometryDescriptor,
    val geometryCompatibilityKey: String,
    val fileReference: String,
    val formatId: String,
    val formatVersion: Int,
    val contentSha256: String,
    val requestedStartTimestampMs: Long,
    val requestedEndTimestampMs: Long,
    val actualStartTimestampMs: Long?,
    val actualEndTimestampMs: Long?,
    val frameCount: Int,
    val extractionElapsedMs: Long,
    val calibrationApplied: Boolean = false,
) {
    init {
        require(recordingId.isNotBlank())
        require(landmarkTrackId.isNotBlank())
        require(modelKey.isNotBlank())
        require(modelSha256.isNotBlank())
        require(pipelineConfiguration.isNotBlank())
        require(geometryCompatibilityKey.isNotBlank())
        require(fileReference.isNotBlank())
        require(formatId.isNotBlank())
        require(formatVersion > 0)
        require(contentSha256.isNotBlank())
        require(requestedStartTimestampMs >= 0)
        require(requestedEndTimestampMs >= requestedStartTimestampMs)
        require((actualStartTimestampMs == null) == (actualEndTimestampMs == null))
        require(frameCount >= 0)
        require(extractionElapsedMs >= 0)
        require(!calibrationApplied) { "Prototype tracks cannot inherit calibration across model tracks" }
        require(geometryBinding.isAvailable)
        require(geometryBinding.recordingId == recordingId)
        require(geometryBinding.landmarkTrackId == landmarkTrackId)
        require(geometryBinding.trackHash == contentSha256)
        if (actualStartTimestampMs != null && actualEndTimestampMs != null) {
            require(frameCount > 0)
            require(actualStartTimestampMs >= requestedStartTimestampMs)
            require(actualEndTimestampMs <= requestedEndTimestampMs)
            require(actualEndTimestampMs >= actualStartTimestampMs)
        }
        if (actualStartTimestampMs == null) require(frameCount == 0)
    }
}

enum class PrototypeCandidateKind { DETECTED_MOVEMENT, UNKNOWN_EVIDENCE }

data class PrototypeCandidateInterval(
    val candidateId: String,
    val startTimestampMs: Long,
    val endTimestampMs: Long,
    val kind: PrototypeCandidateKind,
) {
    init {
        require(candidateId.isNotBlank())
        require(startTimestampMs >= 0)
        require(endTimestampMs > startTimestampMs)
    }
}

/** One independent Heavy request. Overlap is disclosed rather than silently merged. */
data class PrototypeHeavyWindowRequest(
    val windowId: String,
    val recordingId: String,
    val discoveryTrackId: String,
    val geometryCompatibilityKey: String,
    val candidate: PrototypeCandidateInterval,
    val requestedStartTimestampMs: Long,
    val requestedEndTimestampMs: Long,
    val truncatedAtRecordingStart: Boolean,
    val truncatedAtRecordingEnd: Boolean,
    val overlappingWindowIds: List<String>,
)

/** Disjoint Heavy inference request produced by unioning overlapping or touching candidate windows. */
data class PrototypeHeavyExecutionWindow(
    val executionWindowId: String,
    val recordingId: String,
    val discoveryTrackId: String,
    val geometryCompatibilityKey: String,
    val candidateWindowIds: List<String>,
    val requestedStartTimestampMs: Long,
    val requestedEndTimestampMs: Long,
    val truncatedAtRecordingStart: Boolean,
    val truncatedAtRecordingEnd: Boolean,
) {
    init {
        require(executionWindowId.isNotBlank())
        require(candidateWindowIds.isNotEmpty())
        require(candidateWindowIds.distinct().size == candidateWindowIds.size)
        require(requestedStartTimestampMs >= 0)
        require(requestedEndTimestampMs > requestedStartTimestampMs)
    }
}

data class LiteHeavyPrototypePlan(
    val recordingId: String,
    val sourceDurationMs: Long,
    val liteTrack: PrototypeTrackProvenance,
    val paddingBeforeMs: Long,
    val paddingAfterMs: Long,
    val prototypeConfigKey: String,
    val paddingPolicyKey: String,
    val discoveryDisposition: PrototypeDiscoveryDisposition,
    val windows: List<PrototypeHeavyWindowRequest>,
    val executionWindows: List<PrototypeHeavyExecutionWindow>,
    val requestedCoverageSumMs: Long,
    val executionCoverageUnionMs: Long,
)

enum class PrototypeDiscoveryDisposition { WINDOWS_PLANNED, FULL_HEAVY_FALLBACK_REQUIRED }

/**
 * Produces source-time Heavy requests without interpreting artificial window edges as stillness.
 * Candidate thresholds and final movement de-duplication intentionally remain outside this planner.
 */
object LiteHeavyPrototypePlanner {
    fun plan(
        recordingId: String,
        sourceDurationMs: Long,
        liteTrack: PrototypeTrackProvenance,
        candidates: List<PrototypeCandidateInterval>,
        paddingBeforeMs: Long,
        paddingAfterMs: Long,
        prototypeConfigKey: String = "lite-heavy-prototype-v2",
        paddingPolicyKey: String = "provisional-fixed-padding-v1",
    ): LiteHeavyPrototypePlan {
        require(recordingId.isNotBlank())
        require(sourceDurationMs >= 0)
        require(liteTrack.recordingId == recordingId)
        require(liteTrack.role == PrototypeTrackRole.LITE_DISCOVERY)
        require(liteTrack.requestedStartTimestampMs == 0L)
        require(liteTrack.requestedEndTimestampMs == sourceDurationMs)
        require(paddingBeforeMs >= 0 && paddingAfterMs >= 0)
        require(candidates.map { it.candidateId }.distinct().size == candidates.size)
        require(candidates.all { it.endTimestampMs <= sourceDurationMs })
        require(prototypeConfigKey.isNotBlank() && paddingPolicyKey.isNotBlank())

        val unlinked = candidates.sortedWith(
            compareBy<PrototypeCandidateInterval> { it.startTimestampMs }.thenBy { it.candidateId },
        ).mapIndexed { index, candidate ->
            val clippedStart = if (candidate.startTimestampMs < paddingBeforeMs) 0L
                else candidate.startTimestampMs - paddingBeforeMs
            val clippedEnd = if (paddingAfterMs > sourceDurationMs - candidate.endTimestampMs) sourceDurationMs
                else candidate.endTimestampMs + paddingAfterMs
            PrototypeHeavyWindowRequest(
                windowId = "heavy-window-${index + 1}",
                recordingId = recordingId,
                discoveryTrackId = liteTrack.landmarkTrackId,
                geometryCompatibilityKey = liteTrack.geometryCompatibilityKey,
                candidate = candidate,
                requestedStartTimestampMs = clippedStart,
                requestedEndTimestampMs = clippedEnd,
                truncatedAtRecordingStart = candidate.startTimestampMs < paddingBeforeMs,
                truncatedAtRecordingEnd = paddingAfterMs > sourceDurationMs - candidate.endTimestampMs,
                overlappingWindowIds = emptyList(),
            )
        }
        val linked = unlinked.map { window ->
            window.copy(
                overlappingWindowIds = unlinked.asSequence()
                    .filter { other ->
                        other.windowId != window.windowId &&
                            window.requestedStartTimestampMs < other.requestedEndTimestampMs &&
                            other.requestedStartTimestampMs < window.requestedEndTimestampMs
                    }
                    .map { it.windowId }
                    .toList(),
            )
        }
        val executionGroups = linked.fold(mutableListOf<MutableList<PrototypeHeavyWindowRequest>>()) { groups, window ->
            val last = groups.lastOrNull()
            if (last == null || window.requestedStartTimestampMs > last.maxOf { it.requestedEndTimestampMs }) {
                groups += mutableListOf(window)
            } else {
                last += window
            }
            groups
        }
        val executionWindows = executionGroups.mapIndexed { index, group ->
            PrototypeHeavyExecutionWindow(
                executionWindowId = "heavy-execution-${index + 1}",
                recordingId = recordingId,
                discoveryTrackId = liteTrack.landmarkTrackId,
                geometryCompatibilityKey = liteTrack.geometryCompatibilityKey,
                candidateWindowIds = group.map { it.windowId },
                requestedStartTimestampMs = group.minOf { it.requestedStartTimestampMs },
                requestedEndTimestampMs = group.maxOf { it.requestedEndTimestampMs },
                truncatedAtRecordingStart = group.any { it.truncatedAtRecordingStart },
                truncatedAtRecordingEnd = group.any { it.truncatedAtRecordingEnd },
            )
        }
        return LiteHeavyPrototypePlan(
            recordingId = recordingId,
            sourceDurationMs = sourceDurationMs,
            liteTrack = liteTrack,
            paddingBeforeMs = paddingBeforeMs,
            paddingAfterMs = paddingAfterMs,
            prototypeConfigKey = prototypeConfigKey,
            paddingPolicyKey = paddingPolicyKey,
            discoveryDisposition = if (linked.isEmpty()) PrototypeDiscoveryDisposition.FULL_HEAVY_FALLBACK_REQUIRED
                else PrototypeDiscoveryDisposition.WINDOWS_PLANNED,
            windows = linked,
            executionWindows = executionWindows,
            requestedCoverageSumMs = linked.sumOf {
                it.requestedEndTimestampMs - it.requestedStartTimestampMs
            },
            executionCoverageUnionMs = executionWindows.sumOf {
                it.requestedEndTimestampMs - it.requestedStartTimestampMs
            },
        )
    }
}

enum class PrototypeWindowState { COMPLETED, FAILED, CANCELLED }

data class PrototypeFinalMovementInterval(
    val startTimestampMs: Long,
    val endTimestampMs: Long,
    val endBoundaryKind: QomEndBoundaryKind,
    val unknownEvidenceIntervals: List<QomUnknownEvidenceInterval> = emptyList(),
) {
    init {
        require(startTimestampMs >= 0)
        require(endTimestampMs >= startTimestampMs)
    }
}

enum class PrototypeWindowUncertainty {
    TRACKING_RESET_AT_WINDOW_START,
    START_PADDING_CLIPPED,
    END_PADDING_CLIPPED,
    ACTUAL_COVERAGE_NARROWER,
    SEGMENT_TOUCHES_ACTUAL_START,
    SEGMENT_TOUCHES_ACTUAL_END,
    RECORDING_END_WITHOUT_STOP_EVIDENCE,
    UNKNOWN_EVIDENCE,
}

/** Heavy evidence outcome. Zero, one, or multiple final movements are all valid outcomes. */
data class PrototypeHeavyWindowOutcome(
    val request: PrototypeHeavyExecutionWindow,
    val state: PrototypeWindowState,
    val heavyTrack: PrototypeTrackProvenance?,
    val finalMovements: List<PrototypeFinalMovementInterval>,
    val uncertainty: Set<PrototypeWindowUncertainty>,
    val failureReason: String? = null,
) {
    init {
        if (state == PrototypeWindowState.COMPLETED) {
            requireNotNull(heavyTrack)
            require(heavyTrack.role == PrototypeTrackRole.HEAVY_WINDOW)
            require(heavyTrack.recordingId == request.recordingId)
            require(heavyTrack.landmarkTrackId != request.discoveryTrackId)
            require(heavyTrack.geometryCompatibilityKey == request.geometryCompatibilityKey)
            require(heavyTrack.requestedStartTimestampMs == request.requestedStartTimestampMs)
            require(heavyTrack.requestedEndTimestampMs == request.requestedEndTimestampMs)
            val actualStart = requireNotNull(heavyTrack.actualStartTimestampMs)
            val actualEnd = requireNotNull(heavyTrack.actualEndTimestampMs)
            require(finalMovements.all {
                it.startTimestampMs >= actualStart && it.endTimestampMs <= actualEnd
            })
            require(PrototypeWindowUncertainty.TRACKING_RESET_AT_WINDOW_START in uncertainty)
            if (request.truncatedAtRecordingStart) {
                require(PrototypeWindowUncertainty.START_PADDING_CLIPPED in uncertainty)
            }
            if (request.truncatedAtRecordingEnd) {
                require(PrototypeWindowUncertainty.END_PADDING_CLIPPED in uncertainty)
            }
            if (actualStart > request.requestedStartTimestampMs || actualEnd < request.requestedEndTimestampMs) {
                require(PrototypeWindowUncertainty.ACTUAL_COVERAGE_NARROWER in uncertainty)
            }
            if (finalMovements.any { it.startTimestampMs == actualStart }) {
                require(PrototypeWindowUncertainty.SEGMENT_TOUCHES_ACTUAL_START in uncertainty)
            }
            if (finalMovements.any { it.endTimestampMs == actualEnd }) {
                require(PrototypeWindowUncertainty.SEGMENT_TOUCHES_ACTUAL_END in uncertainty)
            }
            if (finalMovements.any { it.endBoundaryKind == QomEndBoundaryKind.RECORDING_END_WITHOUT_STOP_EVIDENCE }) {
                require(PrototypeWindowUncertainty.RECORDING_END_WITHOUT_STOP_EVIDENCE in uncertainty)
            }
            require(failureReason == null)
        } else {
            require(heavyTrack == null)
            require(finalMovements.isEmpty())
            require(!failureReason.isNullOrBlank())
        }
    }
}
