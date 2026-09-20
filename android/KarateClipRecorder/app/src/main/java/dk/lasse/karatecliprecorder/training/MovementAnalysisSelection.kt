package dk.lasse.karatecliprecorder.training

/** One policy for result, geometry and landmark provenance. Version order is explicit. */
fun selectMovementAnalysis(
    analyses: List<MovementAnalysis>,
    policy: AnalyzerPolicy,
    includeUnavailable: Boolean = false,
): MovementAnalysis? {
    val newestFirst = analyses.sortedWith(
        compareByDescending<MovementAnalysis> { it.createdAtMs }.thenByDescending { it.analysisId }
    )
    fun select(states: Set<AnalysisState>) = policy.approvedVersions.firstNotNullOfOrNull { version ->
        newestFirst.firstOrNull {
            it.analyzerKey == policy.analyzerKey && it.analyzerVersion == version && it.state in states
        }
    }
    return select(setOf(AnalysisState.COMPLETED, AnalysisState.PARTIAL))
        ?: if (includeUnavailable) select(setOf(AnalysisState.ABSTAINED, AnalysisState.FAILED)) else null
}

fun MovementEvidence.presentationAnalysis(): MovementAnalysis? = selectMovementAnalysis(
    analyses, StraightPunchMovementAdapter.policy, includeUnavailable = true
)
