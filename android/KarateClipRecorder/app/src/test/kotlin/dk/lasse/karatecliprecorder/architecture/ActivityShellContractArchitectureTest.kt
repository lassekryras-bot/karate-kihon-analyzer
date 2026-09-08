package dk.lasse.karatecliprecorder.architecture

import dk.lasse.karatecliprecorder.learningactivity.ActivityShellState
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActivityShellContractArchitectureTest {
    private val root: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { it.resolve("settings.gradle.kts").isFile && it.resolve("app/build.gradle.kts").isFile }
    private val sources = root.resolve("app/src/main/java/dk/lasse/karatecliprecorder")

    @Test fun macroStatesStayCoarseAndTechnologyNeutral() {
        assertEquals(
            listOf("READY", "ACTIVE", "COMPLETE", "RESULT", "ERROR"),
            ActivityShellState.entries.map { it.name },
        )

        val shell = sources.resolve("learningactivity/ActivityShellView.kt").readText().lowercase()
        listOf(
            "speechrecognizer",
            "previewview",
            "mediapipe",
            "japanesecount",
            "counttraining",
            "punchheight",
        ).forEach { forbidden ->
            assertFalse(shell.contains(forbidden), "Shared shell must not know $forbidden")
        }
    }

    @Test fun shellActionBoundaryRequiresOnePrimaryAndAllowsOnlyOneSecondary() {
        val shell = sources.resolve("learningactivity/ActivityShellView.kt").readText()
        val setActions = shell.substringAfter("fun setActions(").substringBefore("private fun actionButton")

        assertTrue(setActions.contains("secondary: ActivityShellAction?"))
        assertTrue(setActions.contains("primary: ActivityShellAction"))
        assertEquals(2, Regex("actionButton\\(").findAll(setActions).count())
        assertTrue(setActions.contains("secondary?.let"))
        assertFalse(setActions.contains("List<ActivityShellAction>"))
    }

    @Test fun draftPlaceholderIsExplicitlyDevelopmentOnlyRatherThanTheProductContract() {
        val placeholder = sources.resolve("learningactivity/DraftPlaceholderActivityView.kt").readText()

        assertTrue(placeholder.contains("Temporary developer shell"))
        assertTrue(placeholder.contains("Draft / Placeholder"))
        assertTrue(placeholder.contains("Development controls"))
        assertTrue(placeholder.contains("Mark complete"))
        assertTrue(placeholder.contains("Reset activity"))
    }

    @Test fun passiveCountingPracticeHasNoMicrophoneCameraOrAnalysisDependency() {
        val practice = listOf(
            "learningactivity/JapaneseCountingPracticeView.kt",
            "learningactivity/JapaneseCountingPracticePresentation.kt",
        ).joinToString("\n") { sources.resolve(it).readText() }

        listOf(
            "SpeechRecognizer",
            "RecognizerIntent",
            "PreviewView",
            "cameraPermissionLauncher",
            "audioPermissionLauncher",
            "MediaPipe",
        ).forEach { forbidden ->
            assertFalse(practice.contains(forbidden), "Passive practice must not depend on $forbidden")
        }
        assertTrue(practice.contains("cameraRequired: Boolean = false"))
        assertTrue(practice.contains("microphoneRequired: Boolean = false"))
    }

    @Test fun hostStopsActiveModalitiesWhenItLeavesTheForegroundAndReleasesOnDestroy() {
        val activity = sources.resolve("MainActivity.kt").readText()
        val onStop = activity.substringAfter("override fun onStop()").substringBefore("override fun onDestroy()")
        val onDestroy = activity.substringAfter("override fun onDestroy()").substringBefore("private fun updateRecordingState")

        assertTrue(onStop.contains("closeCameraSetupSession()"))
        assertTrue(onStop.contains("cancelPunchHeightSession()"))
        assertTrue(onStop.contains("cancelJapaneseCountRecognitionRestart()"))
        assertTrue(onStop.contains("japaneseCountFullExamplePlayer.stop()"))
        assertTrue(onStop.contains("japaneseCountLiveRecognizer.cancel()"))

        assertTrue(onDestroy.contains("japaneseCountLiveRecognizer.release()"))
        assertTrue(onDestroy.contains("japaneseCountFullExamplePlayer.release()"))
        assertTrue(onDestroy.contains("recognizerRunner?.close()"))
        assertTrue(onDestroy.contains("poseRecognizerRunner?.close()"))
        assertTrue(onDestroy.contains("recordingAdapter?.close()"))
    }
}
