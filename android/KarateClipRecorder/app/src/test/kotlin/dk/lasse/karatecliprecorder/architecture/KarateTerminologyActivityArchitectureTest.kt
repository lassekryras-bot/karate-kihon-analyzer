package dk.lasse.karatecliprecorder.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KarateTerminologyActivityArchitectureTest {
    private val root: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { it.resolve("settings.gradle.kts").isFile && it.resolve("app/build.gradle.kts").isFile }
    private val sources = root.resolve("app/src/main/java/dk/lasse/karatecliprecorder")

    @Test fun terminologyActivitiesHaveConcreteRoutesAndStopPrecedesHandsFreeTraining() {
        val models = sources.resolve("learningpath/DraftLearningPathModels.kt").readText()
        val activity = sources.resolve("MainActivity.kt").readText()
        val config = root.resolve("app/src/main/res/raw/karate_basics_path.json").readText()

        listOf("OSU_MEANING_USE", "READY_OSU", "STOP_COUNT").forEach {
            assertTrue(models.contains(it), it)
            assertTrue(activity.contains("DraftActivityType.$it"), it)
        }
        assertTrue(config.contains("\"type\": \"osu-meaning-use\""))
        assertTrue(config.contains("\"type\": \"ready-osu\""))
        assertTrue(config.contains("\"id\": \"stop-session\""))
        assertTrue(config.contains("\"stop-session\", \"camera-check\", \"short-set\""))
    }

    @Test fun passiveLessonAndVoiceOnlyViewsDoNotOwnCameraOrAnalysis() {
        val views = sources.resolve("learningactivity/KarateTerminologyViews.kt").readText()
        val shell = sources.resolve("learningactivity/ActivityShellView.kt").readText()

        assertTrue(views.contains("OsuMeaningUseView"))
        assertTrue(views.contains("ReadyOsuView"))
        assertTrue(views.contains("StopCountView"))
        assertFalse(views.contains("PreviewView"))
        assertFalse(views.contains("MediaPipe"))
        assertFalse(views.contains("CameraX"))
        assertFalse(shell.contains("ShortVoiceCommand"))
        assertFalse(shell.contains("JapaneseCountLesson"))
    }

    @Test fun permissionsFollowActivityActionsAndStopCancelsBothModalities() {
        val activity = sources.resolve("MainActivity.kt").readText()
        val openPassive = activity.substringAfter("private fun openOsuMeaningUse(pathPosition").substringBefore("private fun renderOsuMeaningUse")
        val requestReady = activity.substringAfter("private fun requestReadyOsuResponse").substringBefore("private fun playReadyPromptAndListen")
        val requestStop = activity.substringAfter("private fun requestStopCountVoicePractice").substringBefore("private fun beginStopCountPractice")
        val stop = activity.substringAfter("private fun stopStopCountPractice").substringBefore("private fun cancelStopCountPlayback")

        assertFalse(openPassive.contains("audioPermissionLauncher.launch"))
        assertTrue(requestReady.contains("audioPermissionLauncher.launch"))
        assertTrue(requestStop.contains("audioPermissionLauncher.launch"))
        assertTrue(stop.contains("stopTerminologyVoiceWork()"))
        assertTrue(stop.contains("cancelStopCountPlayback()"))
        assertTrue(stop.contains("stopCountController.stop(method)"))
    }
}
