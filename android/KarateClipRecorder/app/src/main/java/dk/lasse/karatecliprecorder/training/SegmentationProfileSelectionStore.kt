package dk.lasse.karatecliprecorder.training

/** Persisted, operator-selected segmentation profile. It does not approve camera view for analysis. */
object SegmentationProfileSelectionStore {
    const val EVENT_TYPE = "segmentation_profile_selection"
    const val EXPERIMENTAL_SIDE_VIEW_ALTERNATING_PUNCH_V1 = "experimental-side-view-alternating-punch-v1"
    const val EXPERIMENTAL_SIDE_VIEW_ALTERNATING_PUNCH_V2 = "experimental-side-view-alternating-punch-v2"
    const val EXPERIMENTAL_SIDE_VIEW_ALTERNATING_PUNCH = EXPERIMENTAL_SIDE_VIEW_ALTERNATING_PUNCH_V2
    const val OPERATOR_ORIGIN = "operator_post_capture_selection"

    data class Evidence(
        val profileId: String,
        val eventId: String,
        val origin: String,
    ) {
        val detectorVersion: String get() = when (profileId) {
            EXPERIMENTAL_SIDE_VIEW_ALTERNATING_PUNCH_V1 ->
                dk.lasse.karateanalyzer.capture.qom.QomDetectorConfig.SIDE_VIEW_ALTERNATING_PUNCH_V1
            EXPERIMENTAL_SIDE_VIEW_ALTERNATING_PUNCH_V2 ->
                dk.lasse.karateanalyzer.capture.qom.QomDetectorConfig.SIDE_VIEW_ALTERNATING_PUNCH_V2
            else -> error("Unsupported segmentation profile: $profileId")
        }
    }

    fun event(sessionId: String) = SessionEvent(
        sessionId = sessionId,
        type = EVENT_TYPE,
        timestampUs = 0L,
        data = EXPERIMENTAL_SIDE_VIEW_ALTERNATING_PUNCH,
        timingSource = OPERATOR_ORIGIN,
    )

    fun current(events: List<SessionEvent>): Evidence? {
        val selections = events.filter { it.type == EVENT_TYPE }
        if (selections.any { it.timingSource != OPERATOR_ORIGIN || it.data !in SUPPORTED_PROFILES }) return null
        if (selections.groupBy { it.data }.any { it.value.size > 1 }) return null
        val selected = selections.firstOrNull { it.data == EXPERIMENTAL_SIDE_VIEW_ALTERNATING_PUNCH_V2 }
            ?: selections.firstOrNull { it.data == EXPERIMENTAL_SIDE_VIEW_ALTERNATING_PUNCH_V1 }
            ?: return null
        return Evidence(requireNotNull(selected.data), selected.sessionEventId, selected.timingSource)
    }

    private val SUPPORTED_PROFILES = setOf(
        EXPERIMENTAL_SIDE_VIEW_ALTERNATING_PUNCH_V1,
        EXPERIMENTAL_SIDE_VIEW_ALTERNATING_PUNCH_V2,
    )
}
