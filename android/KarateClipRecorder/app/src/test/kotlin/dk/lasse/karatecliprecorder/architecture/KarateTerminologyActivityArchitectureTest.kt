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

    @Test fun selfieCameraStaysInReadyOsuRunnerAndOutOfPassiveViewsAndShell() {
        val views = sources.resolve("learningactivity/KarateTerminologyViews.kt").readText()
        val shell = sources.resolve("learningactivity/ActivityShellView.kt").readText()
        val passiveLesson = views.substringAfter("class OsuMeaningUseView").substringBefore("class ReadyOsuView")
        val readyOsu = views.substringAfter("class ReadyOsuView").substringBefore("class StopCountView")
        val stopCount = views.substringAfter("class StopCountView")

        assertTrue(views.contains("OsuMeaningUseView"))
        assertTrue(views.contains("ReadyOsuView"))
        assertTrue(views.contains("StopCountView"))
        assertFalse(passiveLesson.contains("PreviewView"))
        assertTrue(readyOsu.contains("PreviewView"))
        assertFalse(stopCount.contains("PreviewView"))
        assertFalse(views.contains("MediaPipe"))
        assertFalse(shell.contains("PreviewView"))
        assertFalse(shell.contains("ImageCapture"))
        assertFalse(shell.contains("ShortVoiceCommand"))
        assertFalse(shell.contains("JapaneseCountLesson"))
    }

    @Test fun permissionsFollowActivityActionsAndStopCancelsBothModalities() {
        val activity = sources.resolve("MainActivity.kt").readText()
        val openPassive = activity.substringAfter("private fun openOsuMeaningUse(pathPosition").substringBefore("private fun renderOsuMeaningUse")
        val requestReady = activity.substringAfter("private fun requestReadyOsuResponse").substringBefore("private fun playReadyPromptAndListen")
        val requestStop = activity.substringAfter("private fun requestStopCountVoicePractice").substringBefore("private fun beginStopCountPractice")
        val stop = activity.substringAfter("private fun stopStopCountPractice").substringBefore("private fun cancelStopCountPlayback")
        val stopTerminology = activity.substringAfter("private fun stopTerminologyRunners").substringBefore("private fun hasAudioPermission")

        assertFalse(openPassive.contains("audioPermissionLauncher.launch"))
        assertFalse(openPassive.contains("readyOsuPermissionLauncher.launch"))
        assertTrue(requestReady.contains("readyOsuPermissionLauncher.launch"))
        assertTrue(requestReady.contains("Manifest.permission.CAMERA"))
        assertTrue(requestReady.contains("Manifest.permission.RECORD_AUDIO"))
        assertTrue(requestStop.contains("audioPermissionLauncher.launch"))
        assertTrue(stop.contains("stopTerminologyVoiceWork()"))
        assertTrue(stop.contains("cancelStopCountPlayback()"))
        assertTrue(stop.contains("stopCountController.stop(method)"))
        assertTrue(stopTerminology.contains("stopReadyOsuSelfieCamera()"))
    }

    @Test fun recognizedOsuTriggersFrontCameraCaptureAndResultEvidence() {
        val activity = sources.resolve("MainActivity.kt").readText()
        val camera = sources.resolve("learning/ReadyOsuSelfieCamera.kt").readText()
        val presentation = sources.resolve("learningactivity/KarateTerminologyPresentations.kt").readText()
        val views = sources.resolve("learningactivity/KarateTerminologyViews.kt").readText()

        assertTrue(camera.contains("CameraSelector.DEFAULT_FRONT_CAMERA"))
        assertTrue(camera.contains("ImageCapture"))
        assertTrue(camera.contains("generation.incrementAndGet()"))
        assertTrue(activity.contains("ReadyOsuPhase.CAPTURING"))
        assertTrue(activity.contains("readyOsuSelfieCamera?.capture()"))
        assertTrue(activity.contains(".put(\"voiceVerified\""))
        assertTrue(activity.contains(".put(\"selfieCaptured\""))
        assertTrue(activity.contains(".put(\"selfiePersisted\", false)"))
        assertTrue(presentation.contains("ReadyOsuPhase.RESULT -> ActivityShellState.RESULT"))
        assertTrue(views.contains("ActivityShellAction(\"Stop selfie practice\""))
        assertTrue(views.contains("selfieResultCard(requireNotNull(selfieBitmap))"))
    }

    @Test fun osuExamplesUseBothPackagedRecordingsThroughFreshSelection() {
        val activity = sources.resolve("MainActivity.kt").readText()
        val player = sources.resolve("learning/TerminologySpeechPlayer.kt").readText()
        val raw = root.resolve("app/src/main/res/raw")

        assertTrue(raw.resolve("osu_voice_01.mp3").isFile)
        assertTrue(raw.resolve("osu_voice_02.mp3").isFile)
        assertTrue(activity.contains("intArrayOf(R.raw.osu_voice_01, R.raw.osu_voice_02)"))
        assertTrue(activity.contains("resourceId = osuSampleSelector.nextResourceId()"))
        assertTrue(activity.contains("private fun playOsuExample()"))
        assertTrue(activity.contains("private fun playReadyOsuModel()"))
        assertTrue(activity.contains("playRandomOsuSample("))
        assertTrue(player.contains("fun playRecording("))
    }
}
