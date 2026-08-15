package dk.lasse.karatecliprecorder.learning

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JapaneseCountListeningPolicyTest {
    @Test fun incompleteCountDoesNotAutoStop() {
        assertFalse(JapaneseCountListeningPolicy.shouldAutoStop(0))
        assertFalse(JapaneseCountListeningPolicy.shouldAutoStop(9))
    }

    @Test fun tenRecognizedCountInputsAutoStop() {
        assertTrue(JapaneseCountListeningPolicy.shouldAutoStop(10))
        assertTrue(JapaneseCountListeningPolicy.shouldAutoStop(11))
    }
}
