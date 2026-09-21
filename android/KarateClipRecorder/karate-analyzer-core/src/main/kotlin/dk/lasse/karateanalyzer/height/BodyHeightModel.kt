package dk.lasse.karateanalyzer.height

import dk.lasse.karateanalyzer.core.PoseFrame
import dk.lasse.karateanalyzer.core.PoseLandmarkId
import dk.lasse.karateanalyzer.core.PoseLandmarkSample
import dk.lasse.karateanalyzer.geometry.AspectCorrectPoint
import dk.lasse.karateanalyzer.geometry.FrameGeometry
import dk.lasse.karateanalyzer.geometry.FrameGeometryMath
import dk.lasse.karateanalyzer.geometry.SourceNormalizedPoint
import kotlin.math.abs

/**
 * Observed body-height geometry around a selected frame or window.
 * Strictly answers: "What body geometry is currently observed?"
 * Does not score technique or define authoritative target heights.
 */
data class ObservedBodyGeometry(
    val recordingId: String? = null,
    val selectedTimestampUs: Long,
    val requestedRadius: Int,
    val modelVersion: String = "2.1",
    val configId: String,
    val shoulderCenter: SourceNormalizedPoint?,
    val hipCenter: SourceNormalizedPoint?,
    val torsoCenter: SourceNormalizedPoint?,
    val currentBodyUp: AspectCorrectPoint?,
    val currentTorsoLength: Float?,
    val headAnchor: SourceNormalizedPoint?,
    val torsoEvidence: ComponentEvidenceSummary,
    val headEvidence: ComponentEvidenceSummary,
    val perSampleObservations: List<PerSampleAnchors>,
) {
    /**
     * Instantaneous torso-relative height of [point] in aspect-correct space:
     * currentTorsoHeight(P) = dot(P - currentTorsoCenter, currentBodyUp) / currentTorsoLength
     * Yields approx -0.5 at HipCenter, 0 at TorsoCenter, +0.5 at ShoulderCenter.
     */
    fun currentTorsoHeight(
        point: AspectCorrectPoint,
        frameGeometry: FrameGeometry,
    ): Float? {
        val tc = torsoCenter ?: return null
        val up = currentBodyUp ?: return null
        val len = currentTorsoLength ?: return null
        if (len <= 1e-5f) return null

        val torsoCenterA = FrameGeometryMath.sourceToAspectCorrect(tc, frameGeometry)
        val delta = point - torsoCenterA
        return delta.dot(up) / len
    }
}

/**
 * Shared utility to derive observed body-height geometry from canonical pose streams.
 */
object BodyHeightModel {

    const val MODEL_VERSION = "2.1"

    fun evaluate(
        selectedTimestampUs: Long,
        frames: List<PoseFrame>,
        frameGeometry: FrameGeometry,
        radius: Int = 1,
        config: BodyHeightModelConfig = BodyHeightModelConfig(),
        trackId: String? = null,
        trackIdProvider: (PoseFrame) -> String? = { null },
        frameTrackIds: Map<Long, String> = emptyMap(),
        recordingId: String? = null,
    ): ObservedBodyGeometry {
        require(radius >= 0) { "Radius must be non-negative, was $radius" }

        val effectiveConfigId = config.effectiveConfigId

        if (frames.isEmpty()) {
            return emptyGeometry(selectedTimestampUs, radius, effectiveConfigId, recordingId)
        }

        // 1. Locate selected sample closest to evaluation timestamp
        val selectedIdx = frames.indices.minByOrNull {
            abs(frames[it].timestampMs * 1000L - selectedTimestampUs)
        } ?: return emptyGeometry(selectedTimestampUs, radius, effectiveConfigId, recordingId)

        val selectedFrame = frames[selectedIdx]
        val targetTrackId = trackId
            ?: trackIdProvider(selectedFrame)
            ?: frameTrackIds[selectedFrame.timestampMs]
            ?: frameTrackIds[selectedFrame.timestampMs * 1000L]

        // 2. Select requested window before filtering (never backfill from farther away)
        val minIdx = (selectedIdx - radius).coerceAtLeast(0)
        val maxIdx = (selectedIdx + radius).coerceAtMost(frames.size - 1)
        val requestedFrames = (minIdx..maxIdx).map { frames[it] }
        val requestedTimestamps = requestedFrames.map { it.timestampMs * 1000L }
        val missingBoundaryNeighbors = requestedFrames.size < (2 * radius + 1)

        // 3. Temporal continuity validation anchored to selected frame
        val temporallyConnectedIndices = mutableSetOf(selectedIdx)
        val continuityExclusions = mutableMapOf<Long, String>()

        // Backward continuity: selected -> prev1 -> prev2...
        var prevTimestamp = selectedFrame.timestampMs * 1000L
        for (i in (selectedIdx - 1) downTo minIdx) {
            val currTimestamp = frames[i].timestampMs * 1000L
            val gap = prevTimestamp - currTimestamp
            if (gap > config.maxTimestampGapUs) {
                // Exclude this sample and all farther samples on this side
                for (j in i downTo minIdx) {
                    val ts = frames[j].timestampMs * 1000L
                    continuityExclusions[ts] = "timestamp_gap_exceeded: ${gap}us > ${config.maxTimestampGapUs}us"
                }
                break
            } else {
                temporallyConnectedIndices.add(i)
                prevTimestamp = currTimestamp
            }
        }

        // Forward continuity: selected -> next1 -> next2...
        prevTimestamp = selectedFrame.timestampMs * 1000L
        for (i in (selectedIdx + 1)..maxIdx) {
            val currTimestamp = frames[i].timestampMs * 1000L
            val gap = currTimestamp - prevTimestamp
            if (gap > config.maxTimestampGapUs) {
                // Exclude this sample and all farther samples on this side
                for (j in i..maxIdx) {
                    val ts = frames[j].timestampMs * 1000L
                    continuityExclusions[ts] = "timestamp_gap_exceeded: ${gap}us > ${config.maxTimestampGapUs}us"
                }
                break
            } else {
                temporallyConnectedIndices.add(i)
                prevTimestamp = currTimestamp
            }
        }

        // 4. Per-sample anchor extraction
        val perSampleObservations = mutableListOf<PerSampleAnchors>()
        val torsoSampleReasons = mutableMapOf<Long, String>()
        val headSampleReasons = mutableMapOf<Long, String>()

        val distinctKnownTracks = requestedFrames.mapNotNull { f ->
            trackIdProvider(f)
                ?: frameTrackIds[f.timestampMs]
                ?: frameTrackIds[f.timestampMs * 1000L]
                ?: trackId
        }.distinct()

        val hasConflictingKnownTracksWithoutTarget = targetTrackId == null && distinctKnownTracks.size > 1

        for (idx in minIdx..maxIdx) {
            val f = frames[idx]
            val ts = f.timestampMs * 1000L
            val sampleTrackId = trackIdProvider(f)
                ?: frameTrackIds[f.timestampMs]
                ?: frameTrackIds[f.timestampMs * 1000L]
                ?: trackId

            val isTrackMatch = if (hasConflictingKnownTracksWithoutTarget) {
                // Unknown identity must not permit conflicting known identities to mix
                sampleTrackId == null
            } else if (targetTrackId != null) {
                sampleTrackId == null || sampleTrackId == targetTrackId
            } else {
                true
            }

            if (!isTrackMatch) {
                val reason = if (hasConflictingKnownTracksWithoutTarget) {
                    "track_mismatch: conflicting known tracks ${distinctKnownTracks.sorted()} with unidentified selected sample"
                } else {
                    "track_mismatch: sample track '$sampleTrackId' != expected '$targetTrackId'"
                }
                torsoSampleReasons[ts] = reason
                headSampleReasons[ts] = reason
                perSampleObservations.add(
                    PerSampleAnchors(
                        timestampUs = ts,
                        shoulderCenter = null,
                        hipCenter = null,
                        headAnchor = null,
                        isTorsoValid = false,
                        isHeadValid = false,
                        trackId = sampleTrackId,
                    )
                )
                continue
            }

            val isConnected = idx in temporallyConnectedIndices
            if (!isConnected) {
                val reason = continuityExclusions[ts] ?: "disconnected_from_evaluation_sample"
                torsoSampleReasons[ts] = reason
                headSampleReasons[ts] = reason
                perSampleObservations.add(
                    PerSampleAnchors(
                        timestampUs = ts,
                        shoulderCenter = null,
                        hipCenter = null,
                        headAnchor = null,
                        isTorsoValid = false,
                        isHeadValid = false,
                        trackId = sampleTrackId,
                    )
                )
                continue
            }

            // Bilateral shoulders
            val ls = f.landmarks[PoseLandmarkId.LEFT_SHOULDER]
            val rs = f.landmarks[PoseLandmarkId.RIGHT_SHOULDER]
            val hasLs = isLandmarkValid(ls, config)
            val hasRs = isLandmarkValid(rs, config)
            val shoulderCenter = if (hasLs && hasRs) {
                SourceNormalizedPoint(
                    (ls!!.position!!.x + rs!!.position!!.x) * 0.5f,
                    (ls.position!!.y + rs.position!!.y) * 0.5f,
                )
            } else {
                null
            }

            // Bilateral hips
            val lh = f.landmarks[PoseLandmarkId.LEFT_HIP]
            val rh = f.landmarks[PoseLandmarkId.RIGHT_HIP]
            val hasLh = isLandmarkValid(lh, config)
            val hasRh = isLandmarkValid(rh, config)
            val hipCenter = if (hasLh && hasRh) {
                SourceNormalizedPoint(
                    (lh!!.position!!.x + rh!!.position!!.x) * 0.5f,
                    (lh.position!!.y + rh.position!!.y) * 0.5f,
                )
            } else {
                null
            }

            // Per-sample torso length check
            val torsoLengthSample = if (shoulderCenter != null && hipCenter != null) {
                val sA = FrameGeometryMath.sourceToAspectCorrect(shoulderCenter, frameGeometry)
                val hA = FrameGeometryMath.sourceToAspectCorrect(hipCenter, frameGeometry)
                FrameGeometryMath.distance(sA, hA)
            } else null

            val torsoDegenerate = torsoLengthSample != null && torsoLengthSample < config.minimumTorsoLength
            val torsoValid = shoulderCenter != null && hipCenter != null && !torsoDegenerate

            if (!torsoValid) {
                val reason = when {
                    !hasLs || !hasRs -> "missing_shoulder_landmarks"
                    !hasLh || !hasRh -> "missing_hip_landmarks"
                    torsoDegenerate -> "degenerate_torso_length: ${torsoLengthSample} < ${config.minimumTorsoLength}"
                    else -> "insufficient_landmark_confidence"
                }
                torsoSampleReasons[ts] = reason
            }

            // Head anchor based on configured strategy
            val headAnchor = extractHeadAnchor(f, config)
            val headValid = headAnchor != null
            if (!headValid) {
                headSampleReasons[ts] = "missing_head_landmarks"
            }

            perSampleObservations.add(
                PerSampleAnchors(
                    timestampUs = ts,
                    shoulderCenter = shoulderCenter,
                    hipCenter = hipCenter,
                    headAnchor = headAnchor,
                    isTorsoValid = torsoValid,
                    isHeadValid = headValid,
                    trackId = sampleTrackId,
                )
            )
        }

        // 5. Shared evidence subset for Torso
        val torsoContributing = perSampleObservations.filter { it.isTorsoValid }
        val torsoTimestamps = torsoContributing.map { it.timestampUs }

        val torsoExcludedTimestamps = requestedTimestamps.filter { it !in torsoTimestamps }
        val torsoExclusionReasons = torsoExcludedTimestamps.associateWith { ts ->
            torsoSampleReasons[ts] ?: "not_contributing_to_torso"
        }

        val torsoWindowStateInitial = resolveWindowState(
            radius = radius,
            usableCount = torsoContributing.size,
            requestedCount = requestedFrames.size,
            fullWindowCount = 2 * radius + 1,
            missingBoundary = missingBoundaryNeighbors,
        )

        val (aggShoulder, aggHip, torsoCenter, currentBodyUp, torsoLength, torsoDisagreement, torsoWindowState) =
            if (torsoContributing.isNotEmpty()) {
                val sX = median(torsoContributing.map { it.shoulderCenter!!.x })
                val sY = median(torsoContributing.map { it.shoulderCenter!!.y })
                val hX = median(torsoContributing.map { it.hipCenter!!.x })
                val hY = median(torsoContributing.map { it.hipCenter!!.y })

                val shoulder = SourceNormalizedPoint(sX, sY)
                val hip = SourceNormalizedPoint(hX, hY)
                val tc = SourceNormalizedPoint((sX + hX) * 0.5f, (sY + hY) * 0.5f)

                val shoulderA = FrameGeometryMath.sourceToAspectCorrect(shoulder, frameGeometry)
                val hipA = FrameGeometryMath.sourceToAspectCorrect(hip, frameGeometry)
                val torsoVecA = shoulderA - hipA // Direction: Hip -> Shoulder (Upward)
                val len = torsoVecA.length()

                val shoulderDisagreements = torsoContributing.map {
                    FrameGeometryMath.distance(
                        FrameGeometryMath.sourceToAspectCorrect(it.shoulderCenter!!, frameGeometry),
                        shoulderA,
                    )
                }
                val hipDisagreements = torsoContributing.map {
                    FrameGeometryMath.distance(
                        FrameGeometryMath.sourceToAspectCorrect(it.hipCenter!!, frameGeometry),
                        hipA,
                    )
                }
                val maxDisagreement = (shoulderDisagreements + hipDisagreements).maxOrNull() ?: 0f

                if (len < config.minimumTorsoLength) {
                    // Degenerate aggregate torso
                    TorsoAggregateResult(null, null, null, null, null, maxDisagreement, WindowState.UNAVAILABLE)
                } else {
                    val up = torsoVecA.normalized()
                    TorsoAggregateResult(shoulder, hip, tc, up, len, maxDisagreement, torsoWindowStateInitial)
                }
            } else {
                TorsoAggregateResult(null, null, null, null, null, null, WindowState.UNAVAILABLE)
            }

        // 6. Independent evidence subset for Head
        val headContributing = perSampleObservations.filter { it.isHeadValid }
        val headTimestamps = headContributing.map { it.timestampUs }

        val headExcludedTimestamps = requestedTimestamps.filter { it !in headTimestamps }
        val headExclusionReasons = headExcludedTimestamps.associateWith { ts ->
            headSampleReasons[ts] ?: "not_contributing_to_head"
        }

        val headWindowState = resolveWindowState(
            radius = radius,
            usableCount = headContributing.size,
            requestedCount = requestedFrames.size,
            fullWindowCount = 2 * radius + 1,
            missingBoundary = missingBoundaryNeighbors,
        )

        val (aggHead, headDisagreement) = if (headContributing.isNotEmpty()) {
            val headX = median(headContributing.map { it.headAnchor!!.x })
            val headY = median(headContributing.map { it.headAnchor!!.y })
            val headPt = SourceNormalizedPoint(headX, headY)
            val headA = FrameGeometryMath.sourceToAspectCorrect(headPt, frameGeometry)
            val disagreements = headContributing.map {
                FrameGeometryMath.distance(
                    FrameGeometryMath.sourceToAspectCorrect(it.headAnchor!!, frameGeometry),
                    headA,
                )
            }
            Pair(headPt, disagreements.maxOrNull() ?: 0f)
        } else {
            Pair(null, null)
        }

        val torsoEvidence = ComponentEvidenceSummary(
            requestedRadius = radius,
            requestedTimestampsUs = requestedTimestamps,
            contributingTimestampsUs = torsoTimestamps,
            usableCount = torsoContributing.size,
            excludedTimestampsUs = torsoExcludedTimestamps,
            exclusionReasons = torsoExclusionReasons,
            windowState = torsoWindowState,
            disagreementMetric = torsoDisagreement,
        )

        val headEvidence = ComponentEvidenceSummary(
            requestedRadius = radius,
            requestedTimestampsUs = requestedTimestamps,
            contributingTimestampsUs = headTimestamps,
            usableCount = headContributing.size,
            excludedTimestampsUs = headExcludedTimestamps,
            exclusionReasons = headExclusionReasons,
            windowState = headWindowState,
            disagreementMetric = headDisagreement,
        )

        return ObservedBodyGeometry(
            recordingId = recordingId,
            selectedTimestampUs = selectedTimestampUs,
            requestedRadius = radius,
            modelVersion = MODEL_VERSION,
            configId = effectiveConfigId,
            shoulderCenter = aggShoulder,
            hipCenter = aggHip,
            torsoCenter = torsoCenter,
            currentBodyUp = currentBodyUp,
            currentTorsoLength = torsoLength,
            headAnchor = aggHead,
            torsoEvidence = torsoEvidence,
            headEvidence = headEvidence,
            perSampleObservations = perSampleObservations,
        )
    }

    private data class TorsoAggregateResult(
        val shoulderCenter: SourceNormalizedPoint?,
        val hipCenter: SourceNormalizedPoint?,
        val torsoCenter: SourceNormalizedPoint?,
        val currentBodyUp: AspectCorrectPoint?,
        val currentTorsoLength: Float?,
        val disagreementMetric: Float?,
        val windowState: WindowState,
    )

    private fun isLandmarkValid(sample: PoseLandmarkSample?, config: BodyHeightModelConfig): Boolean {
        if (sample == null || sample.position == null) return false
        val p = sample.position
        if (!p.x.isFinite() || !p.y.isFinite()) return false
        return sample.confidence >= config.landmarkValidityThreshold
    }

    private fun extractHeadAnchor(frame: PoseFrame, config: BodyHeightModelConfig): SourceNormalizedPoint? {
        return when (config.headAnchorStrategy) {
            HeadAnchorStrategyId.EAR_MIDPOINT_V1 -> {
                val le = frame.landmarks[PoseLandmarkId.LEFT_EAR]
                val re = frame.landmarks[PoseLandmarkId.RIGHT_EAR]
                if (isLandmarkValid(le, config) && isLandmarkValid(re, config)) {
                    SourceNormalizedPoint(
                        (le!!.position!!.x + re!!.position!!.x) * 0.5f,
                        (le.position!!.y + re.position!!.y) * 0.5f,
                    )
                } else null
            }
            HeadAnchorStrategyId.EYES_EARS_COMPOSITE_V1 -> {
                val le = frame.landmarks[PoseLandmarkId.LEFT_EAR]
                val re = frame.landmarks[PoseLandmarkId.RIGHT_EAR]
                val ley = frame.landmarks[PoseLandmarkId.LEFT_EYE]
                val rey = frame.landmarks[PoseLandmarkId.RIGHT_EYE]
                if (isLandmarkValid(le, config) && isLandmarkValid(re, config) &&
                    isLandmarkValid(ley, config) && isLandmarkValid(rey, config)) {
                    SourceNormalizedPoint(
                        (le!!.position!!.x + re!!.position!!.x + ley!!.position!!.x + rey!!.position!!.x) * 0.25f,
                        (le.position!!.y + re.position!!.y + ley.position!!.y + rey.position!!.y) * 0.25f,
                    )
                } else null
            }
        }
    }

    private fun resolveWindowState(
        radius: Int,
        usableCount: Int,
        requestedCount: Int,
        fullWindowCount: Int,
        missingBoundary: Boolean,
    ): WindowState {
        return when {
            usableCount == 0 -> WindowState.UNAVAILABLE
            radius == 0 && usableCount == 1 -> WindowState.SINGLE_FRAME_REQUEST
            radius > 0 && usableCount == 1 -> WindowState.SINGLE_FRAME_FALLBACK
            usableCount == fullWindowCount && !missingBoundary -> WindowState.FULL_REQUESTED_WINDOW
            else -> WindowState.REDUCED_WINDOW
        }
    }

    private fun median(values: List<Float>): Float {
        require(values.isNotEmpty()) { "Cannot compute median of empty list" }
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) {
            sorted[n / 2]
        } else {
            (sorted[n / 2 - 1] + sorted[n / 2]) * 0.5f
        }
    }

    private fun emptyGeometry(
        selectedTimestampUs: Long,
        radius: Int,
        configId: String,
        recordingId: String?,
    ): ObservedBodyGeometry {
        val emptyEvidence = ComponentEvidenceSummary(
            requestedRadius = radius,
            requestedTimestampsUs = emptyList(),
            contributingTimestampsUs = emptyList(),
            usableCount = 0,
            windowState = WindowState.UNAVAILABLE,
        )
        return ObservedBodyGeometry(
            recordingId = recordingId,
            selectedTimestampUs = selectedTimestampUs,
            requestedRadius = radius,
            modelVersion = MODEL_VERSION,
            configId = configId,
            shoulderCenter = null,
            hipCenter = null,
            torsoCenter = null,
            currentBodyUp = null,
            currentTorsoLength = null,
            headAnchor = null,
            torsoEvidence = emptyEvidence,
            headEvidence = emptyEvidence,
            perSampleObservations = emptyList(),
        )
    }
}
