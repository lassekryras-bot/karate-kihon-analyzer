package dk.lasse.karateanalyzer.height

import dk.lasse.karateanalyzer.core.PoseFrame
import dk.lasse.karateanalyzer.core.PoseLandmarkId
import dk.lasse.karateanalyzer.core.PoseLandmarkSample
import dk.lasse.karateanalyzer.geometry.*
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

        val policy = LandmarkEvidenceWindow.legacyBodyHeightModelWindowPolicy(
            radius = radius,
            maxTimestampGapUs = config.maxTimestampGapUs,
        )

        fun resolveTrackId(f: PoseFrame): String? =
            trackIdProvider(f)
                ?: frameTrackIds[f.timestampMs]
                ?: frameTrackIds[f.timestampMs * 1000L]
                ?: trackId

        val initialAnchor = frames.indices.minByOrNull {
            abs(frames[it].timestampMs * 1000L - selectedTimestampUs)
        }?.let { frames[it] } ?: return emptyGeometry(selectedTimestampUs, radius, effectiveConfigId, recordingId)

        val targetTrackId = trackId ?: resolveTrackId(initialAnchor)

        // Delegate temporal window selection to LandmarkEvidenceWindow with legacy window policy
        val windowResult = LandmarkEvidenceWindow.selectWindow(
            evaluationTimestampUs = selectedTimestampUs,
            samples = frames,
            timestampExtractor = { it.timestampMs * 1000L },
            trackIdExtractor = { resolveTrackId(it) },
            expectedTrackId = targetTrackId,
            policy = policy,
        )

        val selectedFrame = windowResult.selectedSample ?: return emptyGeometry(selectedTimestampUs, radius, effectiveConfigId, recordingId)
        val selectedIdx = frames.indexOf(selectedFrame)
        val minIdx = (selectedIdx - radius).coerceAtLeast(0)
        val maxIdx = (selectedIdx + radius).coerceAtMost(frames.size - 1)
        val requestedFrames = (minIdx..maxIdx).map { frames[it] }
        val requestedTimestamps = requestedFrames.map { it.timestampMs * 1000L }
        val missingBoundaryNeighbors = requestedFrames.size < (2 * radius + 1)

        // 4. Per-sample anchor extraction
        val perSampleObservations = mutableListOf<PerSampleAnchors>()
        val torsoSampleReasons = mutableMapOf<Long, String>()
        val headSampleReasons = mutableMapOf<Long, String>()

        val distinctKnownTracks = requestedFrames.mapNotNull { resolveTrackId(it) }.distinct()
        val hasConflictingKnownTracksWithoutTarget = targetTrackId == null && distinctKnownTracks.size > 1

        val windowContributingSet = windowResult.contributingSamples.toSet()

        for (idx in minIdx..maxIdx) {
            val f = frames[idx]
            val ts = f.timestampMs * 1000L
            val sampleTrackId = resolveTrackId(f)

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

            val isConnected = f in windowContributingSet
            if (!isConnected) {
                val reason = windowResult.diagnostics.exclusionReasons[ts] ?: "disconnected_from_evaluation_sample"
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

            // Bilateral shoulders & hips via shared LandmarkAnchors
            val torsoSample = LandmarkAnchors.extractTorsoAnchors(f, config.landmarkValidityThreshold)
            val shoulderCenter = torsoSample.shoulderCenter
            val hipCenter = torsoSample.hipCenter

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
                    torsoSample.failureReason != null -> torsoSample.failureReason
                    torsoDegenerate -> "degenerate_torso_length: ${torsoLengthSample} < ${config.minimumTorsoLength}"
                    else -> "insufficient_landmark_confidence"
                }
                torsoSampleReasons[ts] = reason
            }

            // Head anchor based on configured strategy via shared LandmarkAnchors
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

        val torsoWindowStateInitial = LandmarkEvidenceWindow.resolveWindowState(
            mode = WindowMode.CENTERED_SAMPLES,
            radius = radius,
            usableCount = torsoContributing.size,
            fullRequestedCount = 2 * radius + 1,
            missingBoundary = missingBoundaryNeighbors,
        )

        val (aggShoulder, aggHip, torsoCenter, currentBodyUp, torsoLength, torsoDisagreement, torsoWindowState) =
            if (torsoContributing.isNotEmpty()) {
                val shoulder = LandmarkAnchors.aggregateTemporalMedian(torsoContributing.map { it.shoulderCenter!! })!!
                val hip = LandmarkAnchors.aggregateTemporalMedian(torsoContributing.map { it.hipCenter!! })!!

                val frameResult = BodyFrameGeometry.constructFromAnchors(
                    shoulderCenter = shoulder,
                    hipCenter = hip,
                    frameGeometry = frameGeometry,
                    timestampUs = selectedTimestampUs,
                    minimumTorsoLength = config.minimumTorsoLength,
                )

                val shoulderA = FrameGeometryMath.sourceToAspectCorrect(shoulder, frameGeometry)
                val hipA = FrameGeometryMath.sourceToAspectCorrect(hip, frameGeometry)

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

                val snapshot = frameResult.snapshot
                if (snapshot == null) {
                    // Degenerate aggregate torso
                    TorsoAggregateResult(null, null, null, null, null, maxDisagreement, WindowState.UNAVAILABLE)
                } else {
                    val tc = snapshot.torsoCenterSource ?: SourceNormalizedPoint(
                        (shoulder.x + hip.x) * 0.5f,
                        (shoulder.y + hip.y) * 0.5f,
                    )
                    val up = snapshot.upAxis.toAspectCorrectPoint()
                    val len = snapshot.torsoLength
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

        val headWindowState = LandmarkEvidenceWindow.resolveWindowState(
            mode = WindowMode.CENTERED_SAMPLES,
            radius = radius,
            usableCount = headContributing.size,
            fullRequestedCount = 2 * radius + 1,
            missingBoundary = missingBoundaryNeighbors,
        )

        val (aggHead, headDisagreement) = if (headContributing.isNotEmpty()) {
            val headPt = LandmarkAnchors.aggregateTemporalMedian(headContributing.map { it.headAnchor!! })!!
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
                LandmarkAnchors.extractHeadEarMidpointV1(frame, config.landmarkValidityThreshold)
            }
            HeadAnchorStrategyId.EYES_EARS_COMPOSITE_V1 -> {
                LandmarkAnchors.extractHeadEyesEarsCompositeV1(frame, config.landmarkValidityThreshold)
            }
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
