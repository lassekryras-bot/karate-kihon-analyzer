package dk.lasse.karatecliprecorder.sharedcapture

import dk.lasse.karatecliprecorder.orders.TrainingOrder

/** Stable semantic prompt IDs mapped centrally to the app's fixed voice catalog. */
object CapturePromptCatalog {
    fun trainingOrder(prompt: CapturePrompt): TrainingOrder? = when (prompt) {
        CapturePrompt.READY -> TrainingOrder.READY
        CapturePrompt.START -> TrainingOrder.YOI
        CapturePrompt.FINISHED,
        CapturePrompt.SET_COMPLETE,
        CapturePrompt.ACTIVITY_COMPLETE,
        CapturePrompt.RECORDING_COMPLETE -> TrainingOrder.SESSION_COMPLETE
        CapturePrompt.STOPPED,
        CapturePrompt.RECORDING_INTERRUPTED -> TrainingOrder.SESSION_CANCELLED
        CapturePrompt.TRY_AGAIN -> TrainingOrder.SESSION_FAILED
        CapturePrompt.FINISHING -> null
    }
}
