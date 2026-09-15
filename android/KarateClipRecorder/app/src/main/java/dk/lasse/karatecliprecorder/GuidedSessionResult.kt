package dk.lasse.karatecliprecorder

import dk.lasse.karateanalyzer.capture.retrospective.RetrospectiveSessionResult

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
    val masterVideoPath: String? = null,
    val retrospectiveResult: RetrospectiveSessionResult? = null,
    val sessionId: String? = null,
    val userId: String? = null,
)
