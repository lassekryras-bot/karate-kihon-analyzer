package dk.lasse.karatecliprecorder.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompletionMarkerAnimationArchitectureTest {
    private val appRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { it.resolve("settings.gradle.kts").isFile && it.resolve("app/build.gradle.kts").isFile }
    private val sourceRoot = appRoot.resolve("app/src/main")

    @Test fun allSuppliedFramesRemainNormalizedTintableVectors() {
        (1..12).forEach { index ->
            val svg = sourceRoot.resolve("res/raw/completion_marker_step_${index.toString().padStart(2, '0')}.svg").readText()
            assertTrue(svg.contains("viewBox=\"0 0 136 136\""))
            assertTrue(svg.contains("fill=\"currentColor\""))
            assertFalse(svg.contains("<image"))
        }
    }

    @Test fun onlyTheJustCompletedActivityAnimatesAfterNextStageReturnsToThePath() {
        val marker = sourceRoot.resolve("java/dk/lasse/karatecliprecorder/learningpath/ProgressMarkerView.kt").readText()
        val path = sourceRoot.resolve("java/dk/lasse/karatecliprecorder/learningpath/KarateBasicsPathView.kt").readText()
        val shell = sourceRoot.resolve("java/dk/lasse/karatecliprecorder/learningactivity/DraftPlaceholderActivityView.kt").readText()
        val activity = sourceRoot.resolve("java/dk/lasse/karatecliprecorder/MainActivity.kt").readText()

        assertTrue(marker.contains("ValueAnimator"))
        assertTrue(marker.contains("animateCompletion: Boolean = false"))
        assertFalse(marker.contains("BitmapFactory"))
        assertTrue(path.contains("activity.definition.id == completionAnimationActivityId"))
        assertTrue(shell.contains("ActivityShellAction(\"Next stage\", onClick = onCompletedAndReturn)"))
        assertTrue(activity.contains("pendingKarateBasicsCompletionAnimationId = activity.definition.id"))
    }
}
