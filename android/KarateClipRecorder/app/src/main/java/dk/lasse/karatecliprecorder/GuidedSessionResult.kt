package dk.lasse.karatecliprecorder

data class GuidedClipResult(
    val plan: GuidedStrikePlan,
    val recordingResult: RecordingResult?,
) {
    val saved: Boolean = recordingResult != null
}

data class GuidedSessionResult(
    val expectedClipCount: Int,
    val savedClipCount: Int,
    val metadataPath: String,
    val completed: Boolean,
)
