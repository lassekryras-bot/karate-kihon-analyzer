package dk.lasse.karatecliprecorder.movement

import android.graphics.PointF
import dk.lasse.karateanalyzer.core.*
import dk.lasse.karatecliprecorder.training.*
import kotlin.math.abs

data class KeyResultItem(
    val id: String,
    val label: String,
    val formattedValue: String,
    val sublabel: String? = null,
    val preferredMode: PlayerMode,
    val focusTimestampUs: Long,
    val isAvailable: Boolean = true,
    val plotKey: String? = null,
)

data class FindingItem(
    val id: String,
    val title: String,
    val description: String,
    val preferredMode: PlayerMode,
    val focusTimestampUs: Long,
)

data class PlotSample(
    val timestampUs: Long,
    val value: Double,
)

data class MovementPlotDefinition(
    val key: String,
    val label: String,
    val unit: String,
    val samples: List<PlotSample>,
    val peakSample: PlotSample? = null,
    val minValue: Double = 0.0,
    val maxValue: Double = 1.0,
    val includeZero: Boolean = true,
    val accessibleSummary: String = "",
)

data class OverlayRay(
    val targetType: String,
    val origin: PointF,
    val targetPoint: PointF?,
    val isClosest: Boolean,
)

data class OverlayArm(
    val side: String,
    val shoulder: PointF,
    val elbow: PointF,
    val wrist: PointF,
)

data class MovementOverlayDefinition(
    val rays: List<OverlayRay> = emptyList(),
    val arm: OverlayArm? = null,
    val canonicalImpactUs: Long? = null,
)

data class MovementDebugData(
    val movementId: String,
    val sessionId: String,
    val logicalStartUs: Long,
    val logicalEndUs: Long,
    val playbackStartUs: Long,
    val playbackEndUs: Long,
    val durationUs: Long,
    val displayedNumber: Int,
    val activeArm: String = "UNKNOWN",
    val canonicalAnalysisTimestampUs: Long? = null,
    val canonicalFrameIndex: Long? = null,
    val measurements: List<MeasurementResult> = emptyList(),
    val analyzerKey: String? = null,
    val analyzerVersion: String? = null,
    val segmenterVersion: String? = null,
    val landmarkTrackId: String? = null,
    val runId: String? = null,
    val reason: String? = null,
    val analysisId: String? = null,
    val analysisState: AnalysisState? = null,
    val evidenceReason: String? = null,
)

data class MovementPresentationData(
    val displayedNumber: Int,
    val activityTitle: String,
    val contextSubtitle: String,
    val canonicalImpactUs: Long,
    val preferredInitialMode: PlayerMode,
    val keyResults: List<KeyResultItem>,
    val findings: List<FindingItem>,
    val namedEvents: List<NamedEvent>,
    val plotDefinitions: Map<String, MovementPlotDefinition>,
    val overlayDefinition: MovementOverlayDefinition?,
    val debugData: MovementDebugData,
    val knownSampleTimestampsUs: List<Long>,
    val analysisNotice: String? = null,
)

object MovementPresentationMapper {

    fun map(
        evidence: MovementEvidence,
        displayedNumber: Int,
        frames: List<PoseFrame> = emptyList(),
    ): MovementPresentationData {
        val movement = evidence.movement
        val preferredAnalysis = evidence.presentationAnalysis()
        val analysisResults = evidence.measurements.filter { it.analysisId == preferredAnalysis?.analysisId }
        val measurements = analysisResults.filter { it.state == ResultState.VALID || it.state == ResultState.PARTIAL }
        val geometry = preferredAnalysis?.takeIf {
            it.state == AnalysisState.COMPLETED || it.state == AnalysisState.PARTIAL
        }?.geometryJson?.let(StraightPunchGeometryCodec::decode)?.takeIf {
            it.timestampUs in movement.playbackStartUs..movement.playbackEndUs
        }
        val side = geometry?.activeSide
            ?: measurements.firstOrNull { it.side == BodySide.LEFT || it.side == BodySide.RIGHT }?.side
            ?: BodySide.UNKNOWN
        val sideLabel = when (side) {
            BodySide.LEFT -> "Left arm"
            BodySide.RIGHT -> "Right arm"
            BodySide.BILATERAL -> "Bilateral"
            BodySide.WHOLE_BODY -> "Whole body"
            BodySide.UNKNOWN -> "Active arm"
        }

        val activityTitle = "Straight punch"
        val contextSubtitle = "$activityTitle · $sideLabel"

        val canonicalUs = geometry?.timestampUs
            ?: analysisResults.firstNotNullOfOrNull { it.occurrenceUs }
            ?: movement.analysisFrameUs
        val canonicalImpactUs = canonicalUs ?: movement.playbackStartUs

        val knownSampleTimestampsUs = frames.map { it.timestampMs * 1000L }
            .filter { it in movement.playbackStartUs..movement.playbackEndUs }
            .distinct()
            .sorted()

        // Named events
        val namedEvents = mutableListOf<NamedEvent>()
        namedEvents.add(NamedEvent("Start", movement.startUs))
        canonicalUs?.let { namedEvents.add(NamedEvent("Impact", it)) }
        namedEvents.add(NamedEvent("Finish", movement.endUs))

        // Continuous metric plots are not produced by the current recording analyzer.
        val plotDefinitions = emptyMap<String, MovementPlotDefinition>()

        // Key Results
        val keyResults = mutableListOf<KeyResultItem>()

        // 1. Target height
        val closestTarget = measurements.firstOrNull { it.measurementKey == TrainingMeasurements.PUNCH_CLOSEST_TARGET }?.categoricalValue
        val angleError = measurements.firstOrNull { it.measurementKey == TrainingMeasurements.PUNCH_TARGET_ANGLE_ERROR_DEG }?.numericValue
        val targetMargin = measurements.firstOrNull { it.measurementKey == TrainingMeasurements.PUNCH_TARGET_CLASSIFICATION_MARGIN_DEG }?.numericValue

        if (closestTarget != null) {
            val targetName = when (closestTarget) {
                "JODAN" -> "Jōdan"
                "CHUDAN" -> "Chūdan"
                "GEDAN" -> "Gedan"
                else -> closestTarget
            }
            val errorText = if (angleError != null) {
                when {
                    abs(angleError) < 0.05 -> "exact"
                    angleError > 0 -> "+${"%.1f".format(angleError)}° high"
                    else -> "${"%.1f".format(angleError)}° low"
                }
            } else null

            val targetValue = if (errorText != null) "$targetName · $errorText" else targetName
            val sublabel = targetMargin?.let { "Margin: ${"%.1f".format(it)}°" }

            keyResults.add(
                KeyResultItem(
                    id = "target_height",
                    label = "Target height",
                    formattedValue = targetValue,
                    sublabel = sublabel,
                    preferredMode = PlayerMode.ANALYSIS,
                    focusTimestampUs = canonicalImpactUs,
                    isAvailable = true,
                )
            )
        } else {
            val legacyError = measurements.firstOrNull { it.measurementKey == TrainingMeasurements.PUNCH_HEIGHT_ERROR_TORSO_RATIO }?.numericValue
            if (legacyError != null) {
                keyResults.add(
                    KeyResultItem(
                        id = "target_height",
                        label = "Target height",
                        formattedValue = "${"%.2f".format(legacyError)} torso ratio",
                        sublabel = "Legacy Jōdan model",
                        preferredMode = PlayerMode.ANALYSIS,
                        focusTimestampUs = canonicalImpactUs,
                        isAvailable = true,
                    )
                )
            } else {
                keyResults.add(
                    KeyResultItem(
                        id = "target_height",
                        label = "Target height",
                        formattedValue = "Unavailable",
                        preferredMode = PlayerMode.ANALYSIS,
                        focusTimestampUs = canonicalImpactUs,
                        isAvailable = false,
                    )
                )
            }
        }

        // No approved recording-level speed/deviation contract exists yet. Do not infer
        // identity or physical units from arbitrary measurement-key substrings.
        listOf(
            "max_speed" to "Max speed",
            "max_path_deviation" to "Max path deviation",
            "hikite_max_speed" to "Hikite max speed",
        ).forEach { (id, label) ->
            keyResults.add(KeyResultItem(id, label, "Unavailable", preferredMode = PlayerMode.GRAPH,
                focusTimestampUs = canonicalImpactUs, isAvailable = false))
        }

        // Findings / Needs Attention: strictly analyzer-owned qualitative technique findings.
        // In the absence of classified technique faults from the analyzer, findings remains empty.
        val findings = emptyList<FindingItem>()

        // Overlay definition: renders persisted analyzer geometry and active arm
        val overlayDefinition = buildOverlayDefinition(geometry, frames, side, canonicalUs)

        // Debug Data
        val frameIdx = geometry?.frameIndex ?: analysisResults.firstNotNullOfOrNull { it.frameIndex }
        val debugData = MovementDebugData(
            movementId = movement.movementId,
            sessionId = movement.sessionId,
            logicalStartUs = movement.startUs,
            logicalEndUs = movement.endUs,
            playbackStartUs = movement.playbackStartUs,
            playbackEndUs = movement.playbackEndUs,
            durationUs = movement.endUs - movement.startUs,
            displayedNumber = displayedNumber,
            activeArm = side.name,
            canonicalAnalysisTimestampUs = canonicalUs,
            canonicalFrameIndex = frameIdx,
            measurements = analysisResults,
            analyzerKey = preferredAnalysis?.analyzerKey,
            analyzerVersion = preferredAnalysis?.analyzerVersion,
            segmenterVersion = movement.segmentationVersion,
            landmarkTrackId = preferredAnalysis?.landmarkTrackId ?: movement.segmentationTrackId,
            runId = movement.runId,
            reason = preferredAnalysis?.reason,
            analysisId = preferredAnalysis?.analysisId,
            analysisState = preferredAnalysis?.state,
            evidenceReason = listOfNotNull(
                "referenced_landmark_track_unavailable".takeIf { frames.isEmpty() },
                "target_geometry_unavailable_or_unsupported".takeIf { geometry == null },
            ).joinToString("; ").ifEmpty { null },
        )

        val preferredInitialMode = if (overlayDefinition != null) {
            PlayerMode.ANALYSIS
        } else {
            PlayerMode.VIDEO
        }

        return MovementPresentationData(
            displayedNumber = displayedNumber,
            activityTitle = activityTitle,
            contextSubtitle = contextSubtitle,
            canonicalImpactUs = canonicalImpactUs,
            preferredInitialMode = preferredInitialMode,
            keyResults = keyResults,
            findings = findings,
            namedEvents = namedEvents.sortedBy { it.timestampUs },
            plotDefinitions = plotDefinitions,
            overlayDefinition = overlayDefinition,
            debugData = debugData,
            knownSampleTimestampsUs = knownSampleTimestampsUs,
            analysisNotice = when {
                overlayDefinition == null -> "Analysis evidence is unavailable for this movement."
                frames.isEmpty() -> "Landmark playback is unavailable. Showing retained target geometry."
                geometry == null -> "Target geometry is unavailable for this movement."
                else -> null
            },
        )
    }

    private fun buildOverlayDefinition(
        geometry: StraightPunchGeometry?,
        frames: List<PoseFrame>,
        side: BodySide,
        canonicalImpactUs: Long?,
    ): MovementOverlayDefinition? {
        val impactFrame = if (frames.isNotEmpty() && canonicalImpactUs != null && side in setOf(BodySide.LEFT, BodySide.RIGHT)) {
            frames.minByOrNull { abs(it.timestampMs * 1000L - canonicalImpactUs) }
        } else null

        val shoulderId = when (side) {
            BodySide.LEFT -> PoseLandmarkId.LEFT_SHOULDER
            BodySide.RIGHT -> PoseLandmarkId.RIGHT_SHOULDER
            else -> PoseLandmarkId.RIGHT_SHOULDER
        }
        val elbowId = when (side) {
            BodySide.LEFT -> PoseLandmarkId.LEFT_ELBOW
            BodySide.RIGHT -> PoseLandmarkId.RIGHT_ELBOW
            else -> PoseLandmarkId.RIGHT_ELBOW
        }
        val wristId = when (side) {
            BodySide.LEFT -> PoseLandmarkId.LEFT_WRIST
            BodySide.RIGHT -> PoseLandmarkId.RIGHT_WRIST
            else -> PoseLandmarkId.RIGHT_WRIST
        }

        val arm = if (impactFrame != null) {
            val shPos = impactFrame.landmarks[shoulderId]?.position
            val elPos = impactFrame.landmarks[elbowId]?.position
            val wrPos = impactFrame.landmarks[wristId]?.position
            if (shPos != null && elPos != null && wrPos != null) {
                OverlayArm(
                    side = side.name,
                    shoulder = PointF(shPos.x, shPos.y),
                    elbow = PointF(elPos.x, elPos.y),
                    wrist = PointF(wrPos.x, wrPos.y),
                )
            } else null
        } else null

        val rays = geometry?.rays?.map { ray ->
            OverlayRay(ray.targetType.name, PointF(geometry.origin.x, geometry.origin.y),
                PointF(ray.endpoint.x, ray.endpoint.y), ray.isClosest)
        }.orEmpty()

        if (arm == null && rays.isEmpty()) return null

        return MovementOverlayDefinition(
            rays = rays,
            arm = arm,
            canonicalImpactUs = canonicalImpactUs,
        )
    }
}
