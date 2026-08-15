package dk.lasse.karatecliprecorder.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeStartupArchitectureTest {
    private val appSources = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { it.resolve("settings.gradle.kts").isFile && it.resolve("app/build.gradle.kts").isFile }
        .resolve("app/src/main/java/dk/lasse/karatecliprecorder")

    @Test fun homeScreenConstructionIsPassive() {
        val home = appSources.resolve("HomeScreenView.kt").readText()

        assertFalse(home.contains("CameraXRecordingAdapter"))
        assertFalse(home.contains("LiveGestureRecognizerRunner"))
        assertFalse(home.contains("LivePoseLandmarkerRunner"))
        assertFalse(home.contains("RequestPermission"))
    }

    @Test fun cameraStartupRequiresExplicitTrainingNavigation() {
        val activity = appSources.resolve("MainActivity.kt").readText()
        val onCreate = activity.substringAfter("override fun onCreate").substringBefore("private fun openTrainingHub")
        val openTrainingHub = activity.substringAfter("private fun openTrainingHub").substringBefore("private fun showTrainingUi")

        assertFalse(onCreate.contains("requestCameraPermissionIfNeeded()"))
        assertTrue(openTrainingHub.contains("requestCameraPermissionIfNeeded()"))
    }
}
