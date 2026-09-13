package dk.lasse.karatecliprecorder

enum class GuidedSessionState {
    IDLE,
    READY,
    YOI,
    PROMPTING_STRIKE,
    RECORDING,
    SAVING,
    ANALYZING,
    COMPLETE,
    FAILED,
    CANCELLED,
}
