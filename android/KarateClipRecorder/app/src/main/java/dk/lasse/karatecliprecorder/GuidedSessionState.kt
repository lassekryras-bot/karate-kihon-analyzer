package dk.lasse.karatecliprecorder

enum class GuidedSessionState {
    IDLE,
    READY,
    YOI,
    PROMPTING_STRIKE,
    RECORDING,
    SAVING,
    COMPLETE,
    FAILED,
    CANCELLED,
}
