package dk.lasse.karatecliprecorder.training

import dk.lasse.karateanalyzer.capture.qom.MotionBodyProfile
import dk.lasse.karateanalyzer.core.ActivitySideContext
import dk.lasse.karateanalyzer.core.LimbInterpretationActivity

/** Product-owned, versioned activity semantics. Display labels are never parsed as a plan. */
object MotionActivityPlans {
    const val ALTERNATING_PUNCH = "alternating-straight-punch-v1"
    const val FRONT_KICK = "front-kick-v1"
    const val EVENT_TYPE = "analysis_activity_plan"
    fun context(key: String?): ActivitySideContext? = when (key) {
        ALTERNATING_PUNCH -> ActivitySideContext(ALTERNATING_PUNCH, "1", LimbInterpretationActivity.STRAIGHT_PUNCH, true)
        FRONT_KICK -> ActivitySideContext(FRONT_KICK, "1", LimbInterpretationActivity.CHAMBER_EXTENSION_KICK, false)
        else -> null
    }
    fun fromEvents(events: List<SessionEvent>): ActivitySideContext? =
        events.filter { it.type == EVENT_TYPE }.singleOrNull()?.data?.let(::context)
    fun profile(context: ActivitySideContext?): MotionBodyProfile = when (context?.activity) {
        LimbInterpretationActivity.CHAMBER_EXTENSION_KICK -> MotionBodyProfile.KICK
        else -> MotionBodyProfile.PUNCH // Existing segmentation fallback; this does not establish activity semantics.
    }
}
