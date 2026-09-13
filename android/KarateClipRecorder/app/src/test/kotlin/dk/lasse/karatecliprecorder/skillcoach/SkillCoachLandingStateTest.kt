package dk.lasse.karatecliprecorder.skillcoach

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkillCoachLandingStateTest {
    @Test fun initialGuidanceHasReplaceableCopyActionAndIllustration() {
        val guidance = SkillCoachLandingState.initial(includeDemoRecent = false).guidance

        assertEquals("Record a few movements to find what to improve next.", guidance.recommendation)
        assertEquals("Show me a few movements and I’ll know where to start.", guidance.senseiSpeech)
        assertEquals("Record movements", guidance.ctaLabel)
        assertEquals(SkillCoachAction.RECORD_MOVEMENTS, guidance.ctaAction)
        assertEquals(SkillCoachIllustration.SENSEI_SPEAKING, guidance.illustration)
    }

    @Test fun firstDraftToolsHaveDistinctPlaceholderActions() {
        val tools = SkillCoachLandingState.initial(includeDemoRecent = false).tools

        assertEquals(listOf("Technique Review", "Focus on One Thing", "Record & Analyze"), tools.map { it.title })
        assertEquals(3, tools.map { it.action }.distinct().size)
    }

    @Test fun sampleHistoryIsExplicitlyDemoOnly() {
        val production = SkillCoachLandingState.initial(includeDemoRecent = false)
        val demo = SkillCoachLandingState.initial(includeDemoRecent = true)

        assertTrue(production.recentAnalyses.isEmpty())
        assertEquals(1, demo.recentAnalyses.size)
        assertEquals("Chūdan punches", demo.recentAnalyses.single().title)
        assertEquals("8 movements · Today", demo.recentAnalyses.single().detail)
        assertTrue(demo.recentAnalyses.single().isDemo)
        assertFalse(production.recentAnalyses.any { it.isDemo })
    }
}
