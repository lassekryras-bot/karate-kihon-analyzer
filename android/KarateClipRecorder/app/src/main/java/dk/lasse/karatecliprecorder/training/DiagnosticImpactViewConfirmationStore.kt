package dk.lasse.karatecliprecorder.training

import org.json.JSONObject

data class DiagnosticImpactViewConfirmation(
    val eventId: String,
    val recordingId: String,
    val activityPlanId: String,
    val activityPlanVersion: String,
    val profileId: String,
    val source: String,
    val method: String,
) {
    fun approval(): ImpactViewApproval = ImpactViewApproval(
        activityPlanId = activityPlanId,
        profileId = profileId,
        source = "$source:$eventId",
    )
}

/** Debug-only operator evidence. It is not a validated production camera-view mapping. */
object DiagnosticImpactViewConfirmationStore {
    const val EVENT_TYPE = "operator_post_capture_view_confirmation"
    const val CONTRACT = "diagnostic-impact-view-confirmation"
    const val VERSION = 1
    const val PROFILE_ID = "diagnostic-manual-side-view-v1"
    const val SOURCE = "operator_post_capture_view_confirmation"
    const val METHOD = "manual_operator_side_view_existing_recording"
    const val PRESENTATION_LABEL = "Diagnostic manual side-view result · excluded from history and baselines"

    fun event(sessionId: String, recordingId: String, context: dk.lasse.karateanalyzer.core.ActivitySideContext) =
        SessionEvent(
            sessionId = sessionId,
            type = EVENT_TYPE,
            timestampUs = 0,
            data = JSONObject()
                .put("contract", CONTRACT)
                .put("version", VERSION)
                .put("recordingId", recordingId)
                .put("activityPlanId", context.planId)
                .put("activityPlanVersion", context.planVersion)
                .put("profileId", PROFILE_ID)
                .put("source", SOURCE)
                .put("method", METHOD)
                .toString(),
            timingSource = SOURCE,
        )

    fun current(
        events: List<SessionEvent>,
        recordingId: String,
        activityPlanEvidence: MotionActivityPlans.Evidence?,
        debugBuild: Boolean,
    ): DiagnosticImpactViewConfirmation? {
        if (!debugBuild || activityPlanEvidence == null) return null
        val event = events.filter { it.type == EVENT_TYPE }.singleOrNull() ?: return null
        return runCatching {
            require(event.timingSource == SOURCE)
            val data = JSONObject(requireNotNull(event.data))
            require(data.getString("contract") == CONTRACT)
            require(data.getInt("version") == VERSION)
            require(data.getString("recordingId") == recordingId)
            require(data.getString("activityPlanId") == activityPlanEvidence.context.planId)
            require(data.getString("activityPlanVersion") == activityPlanEvidence.context.planVersion)
            require(data.getString("profileId") == PROFILE_ID)
            require(data.getString("source") == SOURCE)
            require(data.getString("method") == METHOD)
            DiagnosticImpactViewConfirmation(
                eventId = event.sessionEventId,
                recordingId = recordingId,
                activityPlanId = activityPlanEvidence.context.planId,
                activityPlanVersion = activityPlanEvidence.context.planVersion,
                profileId = PROFILE_ID,
                source = SOURCE,
                method = METHOD,
            )
        }.getOrNull()
    }

    fun approvalFromAnalyses(analyses: List<MovementAnalysis>): ImpactViewApproval? {
        val motion = selectMovementAnalysis(analyses, MovementMotionAnalysis.policy, includeUnavailable = true)
            ?: return null
        return runCatching {
            val approval = JSONObject(requireNotNull(motion.geometryJson)).getJSONObject("viewApproval")
            ImpactViewApproval(
                activityPlanId = approval.getString("activityPlanId"),
                profileId = approval.getString("profileId"),
                source = approval.getString("source"),
            ).takeIf {
                it.profileId == PROFILE_ID && it.source.startsWith("$SOURCE:")
            }
        }.getOrNull()
    }

    fun isDiagnostic(analyses: List<MovementAnalysis>): Boolean = approvalFromAnalyses(analyses) != null
}
