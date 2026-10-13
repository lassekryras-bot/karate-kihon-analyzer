package dk.lasse.karatecliprecorder.training

import dk.lasse.karateanalyzer.capture.qom.MotionBodyProfile
import dk.lasse.karateanalyzer.core.ActivitySideContext
import dk.lasse.karateanalyzer.core.LimbInterpretationActivity

/** Product-owned, versioned activity semantics. Display labels are never parsed as a plan. */
object MotionActivityPlans {
    const val ALTERNATING_PUNCH = "alternating-straight-punch-v1"
    const val FRONT_KICK = "front-kick-v1"
    const val EVENT_TYPE = "analysis_activity_plan"
    const val CAPTURE_ORIGIN = "capture_plan"
    const val OPERATOR_POST_CAPTURE_ORIGIN = "operator_post_capture_confirmation"

    data class Evidence(
        val context: ActivitySideContext,
        val eventId: String,
        val origin: String,
    )

    enum class EventState { MISSING, ASSIGNED, CONFLICT }

    fun context(key: String?): ActivitySideContext? = when (key) {
        ALTERNATING_PUNCH -> ActivitySideContext(ALTERNATING_PUNCH, "1", LimbInterpretationActivity.STRAIGHT_PUNCH, true)
        FRONT_KICK -> ActivitySideContext(FRONT_KICK, "1", LimbInterpretationActivity.CHAMBER_EXTENSION_KICK, false)
        else -> null
    }

    fun eventState(events: List<SessionEvent>): EventState {
        val plans = events.filter { it.type == EVENT_TYPE }
        return when {
            plans.isEmpty() -> EventState.MISSING
            plans.size == 1 && context(plans.single().data) != null -> EventState.ASSIGNED
            else -> EventState.CONFLICT
        }
    }

    fun evidenceFromEvents(events: List<SessionEvent>): Evidence? {
        val event = events.filter { it.type == EVENT_TYPE }.singleOrNull() ?: return null
        val context = context(event.data) ?: return null
        return Evidence(context, event.sessionEventId, event.timingSource)
    }

    fun fromEvents(events: List<SessionEvent>): ActivitySideContext? =
        evidenceFromEvents(events)?.context
    fun profile(context: ActivitySideContext?): MotionBodyProfile = when (context?.activity) {
        LimbInterpretationActivity.CHAMBER_EXTENSION_KICK -> MotionBodyProfile.KICK
        else -> MotionBodyProfile.PUNCH // Existing segmentation fallback; this does not establish activity semantics.
    }
}
