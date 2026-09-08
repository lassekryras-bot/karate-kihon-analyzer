package dk.lasse.karatecliprecorder.learning

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShortVoiceCommandMatcherTest {
    @Test fun osuAcceptsApprovedSpokenAndJapaneseRecognizerVariants() {
        listOf("Osu", "OSS", "おす", "おっす", "オス", "オッス", "押忍").forEach { variant ->
            assertTrue(ShortVoiceCommandMatcher.matches(ShortVoiceCommand.OSU, listOf(variant)), variant)
        }
    }

    @Test fun osuDoesNotUseLooseSubstringMatching() {
        assertFalse(ShortVoiceCommandMatcher.matches(ShortVoiceCommand.OSU, listOf("possible")))
        assertFalse(ShortVoiceCommandMatcher.matches(ShortVoiceCommand.OSU, listOf("busy")))
    }

    @Test fun stopRequiresTheWholeCommandToken() {
        assertTrue(ShortVoiceCommandMatcher.matches(ShortVoiceCommand.STOP, listOf("stop")))
        assertTrue(ShortVoiceCommandMatcher.matches(ShortVoiceCommand.STOP, listOf("please stop now")))
        assertFalse(ShortVoiceCommandMatcher.matches(ShortVoiceCommand.STOP, listOf("stopping")))
        assertFalse(ShortVoiceCommandMatcher.matches(ShortVoiceCommand.STOP, listOf("ten")))
    }
}
