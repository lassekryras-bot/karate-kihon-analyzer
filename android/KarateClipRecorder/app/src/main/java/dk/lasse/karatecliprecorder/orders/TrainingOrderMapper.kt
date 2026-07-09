package dk.lasse.karatecliprecorder.orders

import dk.lasse.karatecliprecorder.GuidedSessionResult
import dk.lasse.karatecliprecorder.GuidedSessionState
import dk.lasse.karatecliprecorder.GuidedStrikePlan

object TrainingOrderMapper {
    fun fromSessionState(state: GuidedSessionState): TrainingOrder? = when (state) {
        GuidedSessionState.YOI -> TrainingOrder.YOI
        GuidedSessionState.CANCELLED -> TrainingOrder.SESSION_CANCELLED
        else -> null
    }

    fun fromStrikePlan(plan: GuidedStrikePlan?): TrainingOrder? = plan?.let { TrainingOrderCatalog.countOrder(it.index) }

    fun fromSessionResult(result: GuidedSessionResult): TrainingOrder =
        if (result.completed) TrainingOrder.SESSION_COMPLETE else TrainingOrder.SESSION_FAILED
}
