package dk.lasse.karatecliprecorder.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KarateBasicsDraftArchitectureTest {
    private val projectRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { it.resolve("settings.gradle.kts").isFile && it.resolve("app/build.gradle.kts").isFile }

    @Test fun curriculumLivesInStructuredConfigAndContainsTheRequiredCrossBranchDependencies() {
        val config = source("src/main/res/raw/karate_basics_path.json")

        assertTrue(config.contains("\"title\": \"Karate Basics\""))
        assertTrue(config.contains("\"follow-count-1-10\", \"one-straight-punch\""))
        assertTrue(config.contains("\"ready-osu\", \"camera-check\", \"short-set\""))
        assertTrue(config.contains("\"karate-basics-challenge\""))
    }

    @Test fun everyPlaceholderUsesOneSharedFourStageShellWithTemporaryDeveloperControls() {
        val shell = source("src/main/java/dk/lasse/karatecliprecorder/learningactivity/DraftPlaceholderActivityView.kt")
        val main = source("src/main/java/dk/lasse/karatecliprecorder/MainActivity.kt")

        assertTrue(listOf("INTRO", "ACTIVITY", "RESULT", "COMPLETE").all(shell::contains))
        assertTrue(listOf("Previous stage", "Next stage", "Mark complete", "Reset activity").all(shell::contains))
        assertTrue(main.contains("DraftPlaceholderActivityView("))
        assertFalse(main.contains("when (activity.definition.id)"))
    }

    @Test fun learningAreaExposesThePathAndCompletedNodesRemainClickable() {
        val learning = source("src/main/java/dk/lasse/karatecliprecorder/learningpath/LearnScreenView.kt")
        val path = source("src/main/java/dk/lasse/karatecliprecorder/learningpath/KarateBasicsPathView.kt")

        assertTrue(learning.contains("draftPathCard()"))
        assertTrue(path.contains("activity.progressState != DraftActivityProgressState.LOCKED"))
        assertTrue(path.contains("onScrollPositionChanged"))
    }

    private fun source(relative: String): String = projectRoot.resolve("app/$relative").readText()
}
