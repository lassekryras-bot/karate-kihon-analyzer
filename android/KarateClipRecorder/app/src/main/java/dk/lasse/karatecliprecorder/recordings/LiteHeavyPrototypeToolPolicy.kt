package dk.lasse.karatecliprecorder.recordings

import dk.lasse.karatecliprecorder.training.QueueState
import dk.lasse.karatecliprecorder.training.SourceState

object LiteHeavyPrototypeToolPolicy {
    fun enabled(
        debugBuild: Boolean,
        developerMode: Boolean,
        sourceState: SourceState,
        queueState: QueueState?,
    ): Boolean = debugBuild && developerMode && sourceState == SourceState.AVAILABLE &&
        queueState !in setOf(QueueState.PROCESSING, QueueState.DELETING)
}
