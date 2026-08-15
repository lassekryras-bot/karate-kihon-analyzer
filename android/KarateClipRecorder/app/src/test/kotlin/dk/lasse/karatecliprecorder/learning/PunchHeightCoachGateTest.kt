package dk.lasse.karatecliprecorder.learning

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PunchHeightCoachGateTest {
    @Test fun identicalPromptIsSuppressedForTwoAndAHalfSeconds() {
        val gate = PunchHeightCoachGate()
        assertTrue(gate.shouldSpeak("Raise your fist.", 1_000L))
        assertFalse(gate.shouldSpeak("Raise your fist.", 3_499L))
        assertTrue(gate.shouldSpeak("Raise your fist.", 3_500L))
    }

    @Test fun meaningfulChangeSpeaksImmediately() {
        val gate = PunchHeightCoachGate()
        assertTrue(gate.shouldSpeak("Raise your fist.", 1_000L))
        assertTrue(gate.shouldSpeak("Hold still.", 1_050L))
    }
}
