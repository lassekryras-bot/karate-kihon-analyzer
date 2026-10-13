package dk.lasse.karatecliprecorder.movement

import dk.lasse.karatecliprecorder.training.*
import org.json.JSONObject

/** Maps retained/ephemeral diagnostic state into one immutable presentation snapshot. */
object MovementDebugReportFactory {
    fun create(
        evidence: MovementEvidence,
        sessionEvents: List<SessionEvent>,
        presentation: MovementPresentationData,
        overrideOutcome: MovementDebugOverrideOutcome? = null,
    ): MovementDebugReport {
        val retainedMotion = MovementMotionInspection.read(evidence)
        val overrideResult = (overrideOutcome as? MovementDebugOverrideOutcome.Evaluated)?.result
        val effectiveMotion = overrideResult?.evaluated?.motion?.analysis?.geometryJson
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: retainedMotion
        val selectedAnalysis = overrideResult?.evaluated?.downstream
            ?.firstOrNull { it.analysis.analyzerKey == StraightPunchMovementAdapter.policy.analyzerKey }
            ?.analysis
            ?: evidence.presentationAnalysis()
        val selectedMeasurements = overrideResult?.evaluated?.downstream
            ?.firstOrNull { it.analysis.analysisId == selectedAnalysis?.analysisId }
            ?.measurements
            ?: evidence.measurements.filter { it.analysisId == selectedAnalysis?.analysisId }
        val currentPlan = MotionActivityPlans.evidenceFromEvents(sessionEvents)
        val debug = presentation.debugData
        val retainedMotionAnalysis = selectMovementAnalysis(evidence.analyses, MovementMotionAnalysis.policy, true)
        val selectedTrackId = overrideResult?.originalMotionAnalysis?.landmarkTrackId ?: debug.landmarkTrackId
        val selectedTrack = evidence.landmarkTracks.firstOrNull { it.landmarkTrackId == selectedTrackId }
        val effectiveSource = if (overrideResult == null) "PERSISTED_SELECTED_ANALYSIS" else "EPHEMERAL_DEBUG_OVERRIDE"

        return MovementDebugReport(
            identity = evidence.movement.movementId,
            sections = listOf(
                section("movement", "Movement",
                    text("movement_id", "Movement ID", evidence.movement.movementId),
                    text("session_id", "Session ID", evidence.movement.sessionId),
                    integer("logical_start_us", "Logical start (us)", evidence.movement.startUs),
                    integer("logical_end_us", "Logical end (us)", evidence.movement.endUs),
                    integer("retained_playback_start_us", "Retained playback start (us)", retainedMotion.longOrNull("playbackStartUs")),
                    integer("retained_playback_end_us", "Retained playback end (us)", retainedMotion.longOrNull("playbackEndUs")),
                    integer("playback_start_us", "Presentation playback start (us)", debug.playbackStartUs),
                    integer("playback_end_us", "Presentation playback end (us)", debug.playbackEndUs),
                    integer("duration_us", "Duration (us)", debug.durationUs),
                ),
                section("analysis", "Analysis",
                    text("effective_analysis_source", "Effective analysis source", effectiveSource),
                    text("analysis_id", "Analysis ID", selectedAnalysis?.analysisId),
                    text("analyzer_key", "Analyzer", selectedAnalysis?.analyzerKey),
                    text("analyzer_version", "Analyzer version", selectedAnalysis?.analyzerVersion),
                    text("analysis_state", "Analysis state", selectedAnalysis?.state?.name),
                    text("analysis_reason", "Analysis reason", selectedAnalysis?.reason),
                    text("active_arm", "Effective active arm", effectiveMotion.stringOrNull("side")),
                    integer("effective_representative_timestamp_us", "Effective retained representative timestamp (us)", effectiveMotion.longOrNull("representativeUs")),
                    integer("original_representative_timestamp_us", "Original retained representative timestamp (us)", retainedMotion.longOrNull("representativeUs")),
                    text("presentation_source", "Presentation diagnostic source", "PRESENTATION_RECOMPUTED_SHARED_MODEL"),
                    text("presentation_analysis_id", "Presentation selected analysis ID", debug.analysisId),
                    text("presentation_track_id", "Presentation selected track ID", debug.landmarkTrackId),
                    integer("presentation_canonical_timestamp_us", "Presentation canonical timestamp (us)", debug.canonicalAnalysisTimestampUs),
                    integer("presentation_canonical_frame_index", "Presentation canonical frame index", debug.canonicalFrameIndex),
                ),
                section("debug_overrides", "Debug Overrides",
                    text("activity_original", "Activity original", retainedMotion.stringOrNull("activity")),
                    text("activity_original_reason", "Activity original reason", retainedMotion.stringOrNull("impactReason")),
                    text("activity_original_plan_id", "Activity original plan ID", retainedMotion.stringOrNull("planId")),
                    text("activity_original_plan_version", "Activity original plan version", retainedMotion.stringOrNull("planVersion")),
                    text("activity_original_event_id", "Activity original event ID", retainedMotion.stringOrNull("activityPlanEventId")),
                    text("activity_original_origin", "Activity original source", retainedMotion.stringOrNull("activityPlanOrigin")),
                    text("activity_override", "Activity override", overrideResult?.override?.name),
                    text("activity_effective", "Activity effective", effectiveMotion.stringOrNull("activity")),
                    text("activity_source", "Activity source", if (overrideResult != null) MovementDebugOverrideEvaluator.DEBUG_SOURCE else retainedMotion.stringOrNull("activityPlanOrigin")),
                    text("override_status", "Override status", when (overrideOutcome) {
                        is MovementDebugOverrideOutcome.Evaluated -> "EVALUATED"
                        is MovementDebugOverrideOutcome.Unavailable -> "UNAVAILABLE"
                        null -> "NOT_REQUESTED"
                    }),
                    text("override_reason", "Override reason", (overrideOutcome as? MovementDebugOverrideOutcome.Unavailable)?.reason),
                    text("override_persistence", "Override persistence", if (overrideResult == null) null else "EPHEMERAL_NOT_PERSISTED_OR_PUBLISHED"),
                    text("current_session_plan_state", "Current session declaration state", MotionActivityPlans.eventState(sessionEvents).name),
                    text("current_session_activity", "Current session declared activity", currentPlan?.context?.activity?.name),
                    text("current_session_plan_id", "Current session plan ID", currentPlan?.context?.planId),
                    text("current_session_plan_version", "Current session plan version", currentPlan?.context?.planVersion),
                    text("current_session_plan_event_id", "Current session plan event ID", currentPlan?.eventId),
                    text("current_session_plan_origin", "Current session plan source", currentPlan?.origin),
                ),
                limbSection(effectiveMotion),
                terminalImpactSection(effectiveMotion, effectiveSource),
                bodyHeightSection(debug.bodyHeightDebug, debug.analysisId, debug.landmarkTrackId),
                targetGeometrySection(debug.bodyHeightDebug, debug.analysisId, debug.landmarkTrackId),
                measurementSection(selectedMeasurements, presentation.keyResults, selectedAnalysis?.analysisId, debug.analysisId, effectiveSource),
                provenanceSection(evidence, effectiveMotion, retainedMotionAnalysis, selectedTrack, debug, overrideResult),
            ),
        )
    }

    private fun limbSection(motion: JSONObject?): MovementDebugSection {
        val entries = mutableListOf<MovementDebugEntry>()
        val profiles = motion?.optJSONArray("profiles")
        if (profiles == null || profiles.length() == 0) {
            entries += text("profiles", "Limb profiles", null)
        } else {
            for (index in 0 until profiles.length()) {
                val profile = profiles.getJSONObject(index)
                val prefix = profile.optString("limb", "LIMB_$index")
                entries += text("${prefix}_pattern", "$prefix pattern", profile.stringOrNull("pattern"))
                entries += decimal("${prefix}_coverage", "$prefix coverage", profile.doubleOrNull("coverage"))
                entries += decimal("${prefix}_minimum_confidence", "$prefix minimum confidence", profile.doubleOrNull("minimumConfidence"))
                entries += text("${prefix}_abstention", "$prefix abstention", profile.stringOrNull("abstention"))
                entries += text("${prefix}_quality_flags", "$prefix quality flags", profile.optJSONArray("qualityFlags")?.toString())
                entries += decimal("${prefix}_travel_deg", "$prefix travel (deg)", profile.doubleOrNull("travelDeg"))
                entries += decimal("${prefix}_net_deg", "$prefix net (deg)", profile.doubleOrNull("netDeg"))
                entries += integer("${prefix}_onset_us", "$prefix onset (us)", profile.longOrNull("onsetUs"))
                entries += integer("${prefix}_peak_us", "$prefix peak (us)", profile.longOrNull("peakUs"))
                entries += integer("${prefix}_settling_us", "$prefix settling (us)", profile.longOrNull("settlingUs"))
            }
        }
        return MovementDebugSection("limb_motion", "Limb Motion", entries)
    }

    private fun terminalImpactSection(motion: JSONObject?, source: String): MovementDebugSection {
        val entries = mutableListOf(
            text("effective_source", "Effective terminal evidence source", source),
            text("impact_status", "Impact status", motion.stringOrNull("impactStatus")),
            text("impact_reason", "Impact reason", motion.stringOrNull("impactReason")),
            text("weapon", "Weapon", motion.stringOrNull("weapon")),
            integer("terminal_us", "Terminal transition (us)", motion.longOrNull("terminalUs")),
            integer("stable_start_us", "Stable start (us)", motion.longOrNull("stableStartUs")),
            integer("stable_end_us", "Stable end (us)", motion.longOrNull("stableEndUs")),
            integer("representative_us", "Representative sample (us)", motion.longOrNull("representativeUs")),
        )
        val impactEvidence = motion?.optJSONObject("impactEvidence")
        if (impactEvidence == null) {
            entries += text("impact_evidence_contract", "Retained impact evidence contract", null)
        } else {
            appendJsonEntries(entries, "impact_evidence", "Retained impact evidence", impactEvidence, setOf("result.debugEvidence"))
        }
        return MovementDebugSection("terminal_impact", "Terminal / Impact Evidence", entries)
    }

    private fun bodyHeightSection(
        body: BodyHeightDebugData?,
        presentationAnalysisId: String?,
        presentationTrackId: String?,
    ): MovementDebugSection = section("body_height", "Body Height Model",
        text("source", "Source", "PRESENTATION_RECOMPUTED_SHARED_MODEL"),
        text("presentation_analysis_id", "Presentation selected analysis ID", presentationAnalysisId),
        text("presentation_track_id", "Presentation selected track ID", presentationTrackId),
        integer("selected_timestamp_us", "Selected timestamp (us)", body?.selectedTimestampUs),
        integer("requested_radius", "Requested radius", body?.requestedRadius?.toLong()),
        text("config_id", "Configuration ID", body?.configId),
        text("torso_window_state", "Torso window state", body?.torsoWindowState),
        integer("torso_usable_count", "Torso usable count", body?.torsoUsableCount?.toLong()),
        text("torso_contributing_timestamps_us", "Torso contributing timestamps (us)", body?.torsoContributingTimestampsUs?.joinToString(",")),
        text("torso_exclusions", "Torso exclusions", body?.torsoExclusionReasons?.entries?.joinToString(",") { "${it.key}:${it.value}" }),
        decimal("torso_disagreement", "Torso disagreement", body?.torsoDisagreementMetric?.toDouble()),
        text("head_window_state", "Head window state", body?.headWindowState),
        integer("head_usable_count", "Head usable count", body?.headUsableCount?.toLong()),
        text("head_contributing_timestamps_us", "Head contributing timestamps (us)", body?.headContributingTimestampsUs?.joinToString(",")),
        text("head_exclusions", "Head exclusions", body?.headExclusionReasons?.entries?.joinToString(",") { "${it.key}:${it.value}" }),
        decimal("head_disagreement", "Head disagreement", body?.headDisagreementMetric?.toDouble()),
        point("shoulder_center", "Shoulder center", body?.shoulderCenter?.x, body?.shoulderCenter?.y),
        point("hip_center", "Hip center", body?.hipCenter?.x, body?.hipCenter?.y),
        point("torso_center", "Torso center", body?.torsoCenter?.x, body?.torsoCenter?.y),
        point("head_anchor", "Head anchor", body?.headAnchor?.x, body?.headAnchor?.y),
        decimal("torso_length", "Torso length", body?.currentTorsoLength?.toDouble()),
        point("body_up", "Body up", body?.currentBodyUp?.x, body?.currentBodyUp?.y),
    )

    private fun targetGeometrySection(
        body: BodyHeightDebugData?,
        presentationAnalysisId: String?,
        presentationTrackId: String?,
    ): MovementDebugSection = section("target_geometry", "Target Geometry",
        text("source", "Source", "PRESENTATION_RECOMPUTED_SHARED_MODEL"),
        text("presentation_analysis_id", "Presentation selected analysis ID", presentationAnalysisId),
        text("presentation_track_id", "Presentation selected track ID", presentationTrackId),
        integer("presentation_selected_timestamp_us", "Presentation selected timestamp (us)", body?.selectedTimestampUs),
        text("chudan_estimator_id", "Chudan estimator ID", body?.chudanEstimatorId),
        text("chudan_status", "Chudan status", body?.chudanStatus),
        point("chudan_target", "Chudan target", body?.chudanTarget?.x, body?.chudanTarget?.y),
        text("gedan_estimator_id", "Gedan estimator ID", body?.gedanEstimatorId),
        text("gedan_status", "Gedan status", body?.gedanStatus),
        point("gedan_target", "Gedan target", body?.gedanTarget?.x, body?.gedanTarget?.y),
        text("jodan_estimator_id", "Jodan estimator ID", body?.jodanEstimatorId),
        text("jodan_status", "Jodan status", body?.jodanStatus),
        point("jodan_target", "Jodan target", body?.jodanTarget?.x, body?.jodanTarget?.y),
    )

    private fun measurementSection(
        measurements: List<MeasurementResult>,
        keyResults: List<KeyResultItem>,
        effectiveAnalysisId: String?,
        presentationAnalysisId: String?,
        effectiveSource: String,
    ): MovementDebugSection {
        val entries = mutableListOf(
            text("effective_measurement_source", "Effective measurement source", effectiveSource),
            text("effective_analysis_id", "Effective analysis ID", effectiveAnalysisId),
            text("presentation_summary_source", "Key-result summary source", "PRESENTATION_FORMATTED_SELECTED_ANALYSIS"),
            text("presentation_analysis_id", "Presentation selected analysis ID", presentationAnalysisId),
        )
        if (measurements.isEmpty()) entries += text("measurements", "Measurements", null)
        measurements.forEachIndexed { index, measurement ->
            val prefix = "measurement_${index}_${measurement.measurementKey}"
            entries += text("${prefix}_key", "Measurement ${index + 1} key", measurement.measurementKey)
            entries += text("${prefix}_calculation_version", "Measurement ${index + 1} calculation version", measurement.calculationVersion)
            entries += text("${prefix}_state", "Measurement ${index + 1} state", measurement.state.name)
            entries += decimal("${prefix}_numeric_value", "Measurement ${index + 1} numeric value", measurement.numericValue)
            entries += text("${prefix}_categorical_value", "Measurement ${index + 1} categorical value", measurement.categoricalValue)
            entries += decimal("${prefix}_confidence", "Measurement ${index + 1} confidence", measurement.confidence)
            entries += decimal("${prefix}_uncertainty", "Measurement ${index + 1} uncertainty", measurement.uncertainty)
            entries += integer("${prefix}_occurrence_us", "Measurement ${index + 1} occurrence (us)", measurement.occurrenceUs)
            entries += integer("${prefix}_frame_index", "Measurement ${index + 1} frame index", measurement.frameIndex)
            entries += text("${prefix}_reason", "Measurement ${index + 1} reason", measurement.reason)
        }
        keyResults.forEachIndexed { index, result ->
            entries += text("presentation_key_result_${index}_${result.id}", "Presentation key result ${result.label}", result.formattedValue)
        }
        return MovementDebugSection("measurements", "Measurements / Key Results", entries)
    }

    private fun provenanceSection(
        evidence: MovementEvidence,
        motion: JSONObject?,
        retainedMotionAnalysis: MovementAnalysis?,
        track: LandmarkTrack?,
        debug: MovementDebugData,
        overrideResult: MovementDebugOverrideResult?,
    ): MovementDebugSection {
        val entries = mutableListOf(
            text("landmark_track_id", "Landmark track ID", track?.landmarkTrackId),
            text("track_pipeline_key", "Track pipeline key", track?.pipelineKey),
            text("track_pipeline_version", "Track pipeline version", track?.pipelineVersion),
            text("track_configuration", "Track configuration", track?.configuration),
            text("track_format_id", "Track format ID", track?.formatId),
            integer("track_format_version", "Track format version", track?.formatVersion?.toLong()),
            text("track_sha256", "Track SHA-256", track?.sha256),
            text("motion_analysis_id", "Retained motion analysis ID", retainedMotionAnalysis?.analysisId),
            text("motion_contract", "Motion contract", motion.stringOrNull("contract")),
            text("canonical_geometry_id", "Canonical geometry ID", motion.stringOrNull("geometryId")),
            text("motion_analyzer_version", "Motion analyzer version", motion.stringOrNull("analyzerVersion")),
            text("angular_policy_version", "Angular policy version", motion.stringOrNull("angularPolicyVersion")),
            text("segmenter_version", "Segmenter version", evidence.movement.segmentationVersion),
            text("analysis_run_id", "Analysis run ID", if (overrideResult == null) debug.runId else null),
            text("publication_state", "Publication state", if (overrideResult == null) "PERSISTED_SELECTION" else "EPHEMERAL_NOT_PUBLISHED"),
        )
        val config = motion?.optJSONObject("config")
        if (config == null) entries += text("motion_config", "Motion configuration", null)
        else appendJsonEntries(entries, "motion_config", "Motion configuration", config)
        val bodyScale = motion?.optJSONObject("bodyScale")
        if (bodyScale == null) entries += text("body_scale", "Body scale provenance", null)
        else appendJsonEntries(entries, "body_scale", "Body scale provenance", bodyScale)
        val calibration = motion?.optJSONObject("calibration")
        if (calibration == null) entries += text("image_height_calibration", "Image-height calibration", null)
        else appendJsonEntries(entries, "image_height_calibration", "Image-height calibration", calibration)
        return MovementDebugSection("provenance", "Provenance", entries)
    }

    private fun appendJsonEntries(
        target: MutableList<MovementDebugEntry>,
        keyPrefix: String,
        labelPrefix: String,
        value: JSONObject,
        skippedPaths: Set<String> = emptySet(),
        relativePath: String = "",
    ) {
        value.keys().asSequence().toList().sorted().forEach { key ->
            val childPath = if (relativePath.isEmpty()) key else "$relativePath.$key"
            if (childPath in skippedPaths) return@forEach
            val child = value.get(key)
            val outputKey = "${keyPrefix}_${childPath.replace('.', '_')}"
            val label = "$labelPrefix $childPath"
            when {
                child === JSONObject.NULL -> target += text(outputKey, label, null)
                child is JSONObject -> appendJsonEntries(target, keyPrefix, labelPrefix, child, skippedPaths, childPath)
                child is Boolean -> target += MovementDebugEntry(outputKey, label, MovementDebugValue.BooleanValue(child))
                child is Byte || child is Short || child is Int || child is Long ->
                    target += integer(outputKey, label, (child as Number).toLong())
                child is Number -> target += decimal(outputKey, label, child.toDouble())
                else -> target += text(outputKey, label, child.toString())
            }
        }
    }

    private fun section(id: String, title: String, vararg entries: MovementDebugEntry) =
        MovementDebugSection(id, title, entries.toList())

    private fun text(key: String, label: String, value: String?) =
        MovementDebugEntry(key, label, value?.let(MovementDebugValue::Text) ?: MovementDebugValue.Unavailable())

    private fun integer(key: String, label: String, value: Long?) =
        MovementDebugEntry(key, label, value?.let(MovementDebugValue::Integer) ?: MovementDebugValue.Unavailable())

    private fun decimal(key: String, label: String, value: Double?) =
        MovementDebugEntry(key, label, value?.let(MovementDebugValue::Decimal) ?: MovementDebugValue.Unavailable())

    private fun point(key: String, label: String, x: Float?, y: Float?) =
        text(key, label, if (x == null || y == null) null else "x=${x.toString()},y=${y.toString()}")

    private fun JSONObject?.stringOrNull(key: String): String? =
        this?.takeUnless { it.isNull(key) }?.get(key)?.toString()

    private fun JSONObject?.longOrNull(key: String): Long? =
        this?.takeUnless { it.isNull(key) }?.getLong(key)

    private fun JSONObject?.doubleOrNull(key: String): Double? =
        this?.takeUnless { it.isNull(key) }?.getDouble(key)
}
