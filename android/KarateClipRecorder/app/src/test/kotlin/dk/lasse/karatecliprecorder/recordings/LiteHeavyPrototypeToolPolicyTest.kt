package dk.lasse.karatecliprecorder.recordings

import dk.lasse.karatecliprecorder.training.QueueState
import dk.lasse.karatecliprecorder.training.SourceState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiteHeavyPrototypeToolPolicyTest {
    @Test
    fun `requires debug build developer mode available source and idle queue`() {
        assertTrue(LiteHeavyPrototypeToolPolicy.enabled(true, true, SourceState.AVAILABLE, QueueState.READY))
        assertFalse(LiteHeavyPrototypeToolPolicy.enabled(false, true, SourceState.AVAILABLE, QueueState.READY))
        assertFalse(LiteHeavyPrototypeToolPolicy.enabled(true, false, SourceState.AVAILABLE, QueueState.READY))
        assertFalse(LiteHeavyPrototypeToolPolicy.enabled(true, true, SourceState.FAILED, QueueState.READY))
        assertFalse(LiteHeavyPrototypeToolPolicy.enabled(true, true, SourceState.AVAILABLE, QueueState.PROCESSING))
        assertFalse(LiteHeavyPrototypeToolPolicy.enabled(true, true, SourceState.AVAILABLE, QueueState.DELETING))
    }
}
