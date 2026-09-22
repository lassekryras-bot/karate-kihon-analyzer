package dk.lasse.karateanalyzer.geometry

import kotlin.math.abs

/**
 * Supported temporal window modes for landmark evidence selection.
 */
enum class WindowMode {
    /** Exact source sample requested. */
    EXACT_SAMPLE,

    /** Selected sample plus up to N neighbors on either side (offline analysis only). */
    CENTERED_SAMPLES,

    /** Selected sample plus up to N preceding neighbors (live-compatible, strictly causal). */
    TRAILING_SAMPLES,
}

/**
 * Window evidence state describing completeness of contributing observations.
 */
enum class WindowState {
    UNAVAILABLE,
    SINGLE_FRAME_REQUEST,
    SINGLE_FRAME_FALLBACK,
    FULL_REQUESTED_WINDOW,
    REDUCED_WINDOW,
}

/**
 * Coarse measurement outcome states matching the Landmark Geometry Core v2 specification.
 */
enum class MeasurementResultState {
    AVAILABLE,
    PARTIAL,
    UNAVAILABLE,
    INVALID_REQUEST,
}

/**
 * Canonical evidence diagnostics capturing requested vs used timeframes, exclusions, and quality.
 */
data class MeasurementEvidenceDiagnostics(
    val requestedTimestampUs: Long,
    val selectedTimestampUs: Long?,
    val requestedRadius: Int,
    val requestedTimestampsUs: List<Long>,
    val contributingTimestampsUs: List<Long>,
    val usableCount: Int,
    val excludedTimestampsUs: List<Long> = emptyList(),
    val exclusionReasons: Map<Long, String> = emptyMap(),
    val windowState: WindowState,
    val disagreementMetric: Float? = null,
    val timeSpanUs: Long? = null,
)

/**
 * Temporal evidence selection policy declaring sample cadence, gap barriers, span limits, and causality.
 */
data class WindowPolicy(
    val mode: WindowMode = WindowMode.CENTERED_SAMPLES,
    val radius: Int = 1,
    val maxTimestampGapUs: Long = 100_000L,
    val maxTotalTimeSpanUs: Long? = null,
    val maxTimeToleranceUs: Long = 100_000L,
    val minimumUsableSamples: Int = 1,
    val isStrictMonotonicityRequired: Boolean = true,
) {
    init {
        require(radius >= 0) { "Radius must be non-negative, was $radius" }
        require(maxTimestampGapUs > 0) { "maxTimestampGapUs must be strictly positive, was $maxTimestampGapUs" }
        require(maxTimeToleranceUs >= 0) { "maxTimeToleranceUs must be non-negative, was $maxTimeToleranceUs" }
        require(minimumUsableSamples >= 0) { "minimumUsableSamples must be non-negative, was $minimumUsableSamples" }
        if (maxTotalTimeSpanUs != null) {
            require(maxTotalTimeSpanUs > 0) { "maxTotalTimeSpanUs must be strictly positive, was $maxTotalTimeSpanUs" }
        }
    }
}

/**
 * Outcome of window selection across a sequence of samples.
 */
data class WindowSelectionResult<T>(
    val selectedSample: T?,
    val contributingSamples: List<T>,
    val diagnostics: MeasurementEvidenceDiagnostics,
    val state: MeasurementResultState,
    val failureReason: String? = null,
)

/**
 * Pure temporal evidence window utility implementing gap barriers, no-backfill,
 * causality enforcement, tolerance limits, and track boundary gating.
 */
object LandmarkEvidenceWindow {

    fun <T> selectWindow(
        evaluationTimestampUs: Long,
        samples: List<T>,
        timestampExtractor: (T) -> Long,
        trackIdExtractor: (T) -> String? = { null },
        expectedTrackId: String? = null,
        policy: WindowPolicy = WindowPolicy(),
    ): WindowSelectionResult<T> {
        if (samples.isEmpty()) {
            val diag = emptyDiagnostics(evaluationTimestampUs, policy.radius)
            return WindowSelectionResult(
                selectedSample = null,
                contributingSamples = emptyList(),
                diagnostics = diag,
                state = MeasurementResultState.UNAVAILABLE,
                failureReason = "empty_samples",
            )
        }

        // 1. Strict monotonicity validation (unless legacy permissive policy configured)
        if (policy.isStrictMonotonicityRequired) {
            for (i in 1 until samples.size) {
                val prevTs = timestampExtractor(samples[i - 1])
                val currTs = timestampExtractor(samples[i])
                if (currTs <= prevTs) {
                    val diag = emptyDiagnostics(evaluationTimestampUs, policy.radius)
                    return WindowSelectionResult(
                        selectedSample = null,
                        contributingSamples = emptyList(),
                        diagnostics = diag,
                        state = MeasurementResultState.INVALID_REQUEST,
                        failureReason = "non_monotonic_or_duplicate_timestamps: sample at index $i has timestamp $currTs <= previous $prevTs",
                    )
                }
            }
        }

        // 2. Locate closest sample to evaluation timestamp
        val candidateIndices = when (policy.mode) {
            WindowMode.TRAILING_SAMPLES -> {
                // Strict causality: candidate anchor must be at or before evaluation timestamp
                samples.indices.filter { timestampExtractor(samples[it]) <= evaluationTimestampUs }
            }
            WindowMode.EXACT_SAMPLE,
            WindowMode.CENTERED_SAMPLES -> {
                samples.indices
            }
        }

        val selectedIdx = candidateIndices.minByOrNull {
            abs(timestampExtractor(samples[it]) - evaluationTimestampUs)
        } ?: return WindowSelectionResult(
            selectedSample = null,
            contributingSamples = emptyList(),
            diagnostics = emptyDiagnostics(evaluationTimestampUs, policy.radius),
            state = MeasurementResultState.UNAVAILABLE,
            failureReason = "sample_search_failed: no candidate sample at or before evaluation timestamp",
        )

        val selectedSample = samples[selectedIdx]
        val selectedTs = timestampExtractor(selectedSample)
        val distanceToEvaluation = abs(selectedTs - evaluationTimestampUs)

        // Tolerance check on anchor sample
        if (distanceToEvaluation > policy.maxTimeToleranceUs) {
            val diag = emptyDiagnostics(evaluationTimestampUs, policy.radius).copy(
                selectedTimestampUs = selectedTs,
            )
            return WindowSelectionResult(
                selectedSample = null,
                contributingSamples = emptyList(),
                diagnostics = diag,
                state = MeasurementResultState.UNAVAILABLE,
                failureReason = "outside_sample_tolerance: distance ${distanceToEvaluation}us > ${policy.maxTimeToleranceUs}us",
            )
        }

        // 3. Resolve requested index bounds based on WindowMode
        val (minIdx, maxIdx) = when (policy.mode) {
            WindowMode.EXACT_SAMPLE -> {
                Pair(selectedIdx, selectedIdx)
            }
            WindowMode.CENTERED_SAMPLES -> {
                Pair(
                    (selectedIdx - policy.radius).coerceAtLeast(0),
                    (selectedIdx + policy.radius).coerceAtMost(samples.size - 1),
                )
            }
            WindowMode.TRAILING_SAMPLES -> {
                // Strict causality: NEVER consume future samples (index > selectedIdx)
                Pair(
                    (selectedIdx - policy.radius).coerceAtLeast(0),
                    selectedIdx,
                )
            }
        }

        val requestedSamples = (minIdx..maxIdx).map { samples[it] }
        val requestedTimestamps = requestedSamples.map(timestampExtractor)
        val missingBoundary = when (policy.mode) {
            WindowMode.EXACT_SAMPLE -> false
            WindowMode.CENTERED_SAMPLES -> requestedSamples.size < (2 * policy.radius + 1)
            WindowMode.TRAILING_SAMPLES -> requestedSamples.size < (policy.radius + 1)
        }

        // 4. Outward continuity gating starting from selected sample
        val temporallyConnectedIndices = mutableSetOf<Int>()
        val exclusions = mutableMapOf<Long, String>()

        val selectedTrack = if (expectedTrackId != null) trackIdExtractor(selectedSample) else null
        val anchorTrackMismatch = expectedTrackId != null && selectedTrack != null && selectedTrack != expectedTrackId
        if (anchorTrackMismatch) {
            exclusions[selectedTs] = "track_mismatch: sample track '$selectedTrack' != expected '$expectedTrackId'"
            // Anchor is a track mismatch; outward traversal is disconnected
        } else {
            temporallyConnectedIndices.add(selectedIdx)

            // Backward continuity: selected -> prev1 -> prev2...
            var prevTimestamp = selectedTs
            for (i in (selectedIdx - 1) downTo minIdx) {
                val sample = samples[i]
                val currTimestamp = timestampExtractor(sample)
                val gap = prevTimestamp - currTimestamp
                if (gap > policy.maxTimestampGapUs) {
                    for (j in i downTo minIdx) {
                        exclusions[timestampExtractor(samples[j])] =
                            "timestamp_gap_exceeded: ${gap}us > ${policy.maxTimestampGapUs}us"
                    }
                    break
                }
                if (expectedTrackId != null) {
                    val sampleTrack = trackIdExtractor(sample)
                    if (sampleTrack != null && sampleTrack != expectedTrackId) {
                        // Track mismatch acts as an impenetrable boundary barrier
                        for (j in i downTo minIdx) {
                            exclusions[timestampExtractor(samples[j])] =
                                "track_mismatch: sample track '$sampleTrack' != expected '$expectedTrackId'"
                        }
                        break
                    }
                }
                temporallyConnectedIndices.add(i)
                prevTimestamp = currTimestamp
            }

            // Forward continuity: selected -> next1 -> next2... (only relevant if maxIdx > selectedIdx)
            prevTimestamp = selectedTs
            for (i in (selectedIdx + 1)..maxIdx) {
                val sample = samples[i]
                val currTimestamp = timestampExtractor(sample)
                val gap = currTimestamp - prevTimestamp
                if (gap > policy.maxTimestampGapUs) {
                    for (j in i..maxIdx) {
                        exclusions[timestampExtractor(samples[j])] =
                            "timestamp_gap_exceeded: ${gap}us > ${policy.maxTimestampGapUs}us"
                    }
                    break
                }
                if (expectedTrackId != null) {
                    val sampleTrack = trackIdExtractor(sample)
                    if (sampleTrack != null && sampleTrack != expectedTrackId) {
                        // Track mismatch acts as an impenetrable boundary barrier
                        for (j in i..maxIdx) {
                            exclusions[timestampExtractor(samples[j])] =
                                "track_mismatch: sample track '$sampleTrack' != expected '$expectedTrackId'"
                        }
                        break
                    }
                }
                temporallyConnectedIndices.add(i)
                prevTimestamp = currTimestamp
            }
        }

        // 5. Track identity gating & total span filtering
        val resolvedContributing = mutableListOf<T>()
        val contributingTimestamps = mutableListOf<Long>()

        for (idx in minIdx..maxIdx) {
            val sample = samples[idx]
            val ts = timestampExtractor(sample)

            if (idx !in temporallyConnectedIndices) {
                continue
            }

            resolvedContributing.add(sample)
            contributingTimestamps.add(ts)
        }

        // 6. Max total time span check
        val finalContributing = if (policy.maxTotalTimeSpanUs != null && contributingTimestamps.size > 1) {
            val earliest = contributingTimestamps.minOrNull() ?: 0L
            val latest = contributingTimestamps.maxOrNull() ?: 0L
            val totalSpan = latest - earliest
            if (totalSpan > policy.maxTotalTimeSpanUs) {
                val trimmed = mutableListOf<T>()
                contributingTimestamps.clear()
                for (s in resolvedContributing) {
                    val ts = timestampExtractor(s)
                    if (abs(ts - selectedTs) * 2 <= policy.maxTotalTimeSpanUs) {
                        trimmed.add(s)
                        contributingTimestamps.add(ts)
                    } else {
                        exclusions[ts] = "total_window_span_exceeded: ${totalSpan}us > ${policy.maxTotalTimeSpanUs}us"
                    }
                }
                trimmed
            } else {
                resolvedContributing
            }
        } else {
            resolvedContributing
        }

        val excludedTimestamps = requestedTimestamps.filter { it !in contributingTimestamps }
        val usableCount = finalContributing.size

        val windowState = resolveWindowState(
            mode = policy.mode,
            radius = policy.radius,
            usableCount = usableCount,
            fullRequestedCount = requestedSamples.size,
            missingBoundary = missingBoundary,
        )

        val timeSpan = if (contributingTimestamps.isNotEmpty()) {
            (contributingTimestamps.maxOrNull() ?: 0L) - (contributingTimestamps.minOrNull() ?: 0L)
        } else null

        val diagnostics = MeasurementEvidenceDiagnostics(
            requestedTimestampUs = evaluationTimestampUs,
            selectedTimestampUs = selectedTs,
            requestedRadius = policy.radius,
            requestedTimestampsUs = requestedTimestamps,
            contributingTimestampsUs = contributingTimestamps,
            usableCount = usableCount,
            excludedTimestampsUs = excludedTimestamps,
            exclusionReasons = exclusions,
            windowState = windowState,
            timeSpanUs = timeSpan,
        )

        val outcomeState = when {
            usableCount < policy.minimumUsableSamples -> MeasurementResultState.UNAVAILABLE
            windowState == WindowState.FULL_REQUESTED_WINDOW || windowState == WindowState.SINGLE_FRAME_REQUEST -> MeasurementResultState.AVAILABLE
            usableCount > 0 -> MeasurementResultState.PARTIAL
            else -> MeasurementResultState.UNAVAILABLE
        }

        val failureReason = if (usableCount < policy.minimumUsableSamples) {
            "insufficient_usable_samples: $usableCount < ${policy.minimumUsableSamples}"
        } else null

        return WindowSelectionResult(
            selectedSample = selectedSample,
            contributingSamples = finalContributing,
            diagnostics = diagnostics,
            state = outcomeState,
            failureReason = failureReason,
        )
    }

    fun resolveWindowState(
        mode: WindowMode,
        radius: Int,
        usableCount: Int,
        fullRequestedCount: Int,
        missingBoundary: Boolean,
    ): WindowState {
        if (mode == WindowMode.EXACT_SAMPLE) {
            return if (usableCount == 1) WindowState.SINGLE_FRAME_REQUEST else WindowState.UNAVAILABLE
        }
        return when {
            usableCount == 0 -> WindowState.UNAVAILABLE
            radius == 0 && usableCount == 1 -> WindowState.SINGLE_FRAME_REQUEST
            radius > 0 && usableCount == 1 -> WindowState.SINGLE_FRAME_FALLBACK
            usableCount == fullRequestedCount && !missingBoundary -> WindowState.FULL_REQUESTED_WINDOW
            else -> WindowState.REDUCED_WINDOW
        }
    }

    private fun emptyDiagnostics(requestedTimestampUs: Long, radius: Int): MeasurementEvidenceDiagnostics {
        return MeasurementEvidenceDiagnostics(
            requestedTimestampUs = requestedTimestampUs,
            selectedTimestampUs = null,
            requestedRadius = radius,
            requestedTimestampsUs = emptyList(),
            contributingTimestampsUs = emptyList(),
            usableCount = 0,
            excludedTimestampsUs = emptyList(),
            exclusionReasons = emptyMap(),
            windowState = WindowState.UNAVAILABLE,
        )
    }

    /**
     * Factory for legacy BodyHeightModel-compatible window policies.
     * Permissive monotonicity and high tolerance preserves 100% facade compatibility.
     */
    fun legacyBodyHeightModelWindowPolicy(
        radius: Int = 1,
        maxTimestampGapUs: Long = 100_000L,
        maxTimeToleranceUs: Long = Long.MAX_VALUE,
    ): WindowPolicy = WindowPolicy(
        mode = WindowMode.CENTERED_SAMPLES,
        radius = radius,
        maxTimestampGapUs = maxTimestampGapUs,
        maxTotalTimeSpanUs = null,
        maxTimeToleranceUs = maxTimeToleranceUs,
        minimumUsableSamples = 1,
        isStrictMonotonicityRequired = false,
    )

    val LegacyBodyHeightModelWindowPolicy: WindowPolicy = legacyBodyHeightModelWindowPolicy()
}
