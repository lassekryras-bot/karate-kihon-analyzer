package dk.lasse.karatecliprecorder.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeStartupArchitectureTest {
    private val appRoot = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { it.resolve("settings.gradle.kts").isFile && it.resolve("app/build.gradle.kts").isFile }
    private val appSources = appRoot
        .resolve("app/src/main/java/dk/lasse/karatecliprecorder")

    @Test fun homeScreenConstructionIsPassive() {
        val home = appSources.resolve("HomeScreenView.kt").readText()

        assertFalse(home.contains("CameraXRecordingAdapter"))
        assertFalse(home.contains("LiveGestureRecognizerRunner"))
        assertFalse(home.contains("LivePoseLandmarkerRunner"))
        assertFalse(home.contains("RequestPermission"))
    }

    @Test fun bottomNavigationUsesStatefulVectorResources() {
        val home = appSources.resolve("HomeScreenView.kt").readText()

        listOf("⌂", "◆", "▥", "⚙").forEach { placeholder ->
            assertFalse(home.contains(placeholder))
        }
        listOf(
            "R.drawable.ic_nav_home",
            "R.drawable.ic_nav_train_belt",
            "R.drawable.ic_nav_progress",
            "R.drawable.ic_nav_settings",
        ).forEach { drawable -> assertTrue(home.contains(drawable)) }
        assertTrue(home.contains("R.color.nav_icon_tint"))
        assertTrue(home.contains("isSelected = selected"))
        assertTrue(home.contains("onProgress: () -> Unit"))
        assertTrue(home.contains("onSettings: () -> Unit"))

        val resources = appRoot.resolve("app/src/main/res")
        listOf(
            "drawable/ic_nav_home.xml",
            "drawable/ic_nav_train_belt.xml",
            "drawable/ic_nav_progress.xml",
            "drawable/ic_nav_settings.xml",
            "color/nav_icon_tint.xml",
        ).forEach { resource -> assertTrue(resources.resolve(resource).isFile) }

        val tint = resources.resolve("color/nav_icon_tint.xml").readText()
        assertTrue(tint.contains("#BE000C"))
        assertTrue(tint.contains("#181818"))
        assertTrue(tint.contains("android:state_selected=\"true\""))
    }

    @Test fun quickActionsUseSuppliedContentVectors() {
        val home = appSources.resolve("HomeScreenView.kt").readText()
        val resources = appRoot.resolve("app/src/main/res")

        listOf(
            "R.drawable.ic_learn_torii",
            "R.drawable.ic_practice",
            "R.drawable.ic_skill_coach_target",
        ).forEach { drawable -> assertTrue(home.contains(drawable)) }
        assertTrue(home.contains("R.color.content_icon_tint"))

        listOf(
            "drawable/ic_learn_torii.xml",
            "drawable/ic_practice.xml",
            "drawable/ic_skill_coach_target.xml",
            "color/content_icon_tint.xml",
        ).forEach { resource -> assertTrue(resources.resolve(resource).isFile) }

        val contentTint = resources.resolve("color/content_icon_tint.xml").readText()
        assertTrue(contentTint.contains("#BE000C"))

        val belt = resources.resolve("drawable/ic_nav_train_belt.xml").readText()
        assertTrue(belt.contains("android:viewportWidth=\"640\""))
        assertTrue(belt.contains("android:translateY=\"42\""))
    }

    @Test fun passiveNavigationDestinationsAvoidTrainingStartup() {
        val activity = appSources.resolve("MainActivity.kt").readText()
        val homeConstruction = activity.substringAfter("homeScreen = HomeScreenView").substringBefore("setContentView")
        val placeholder = activity.substringAfter("private fun showHomeDestinationPlaceholder").substringBefore("private fun openTrainingHub")

        assertTrue(homeConstruction.contains("onProgress = { showHomeDestinationPlaceholder"))
        assertTrue(homeConstruction.contains("onSettings = { showHomeDestinationPlaceholder"))
        assertFalse(placeholder.contains("showTrainingUi"))
        assertFalse(placeholder.contains("requestCameraPermissionIfNeeded"))
    }

    @Test fun bottomNavigationStaysAboveSystemNavigation() {
        val home = appSources.resolve("HomeScreenView.kt").readText()

        assertTrue(home.contains("WindowInsetsCompat.Type.navigationBars()"))
        assertTrue(home.contains("bottomMargin = navigationBars.bottom"))
        assertFalse(home.contains("navigation.layoutParams.height = 82.dp() +"))
    }

    @Test fun cameraStartupRequiresExplicitTrainingNavigation() {
        val activity = appSources.resolve("MainActivity.kt").readText()
        val onCreate = activity.substringAfter("override fun onCreate").substringBefore("private fun openTrainingHub")
        val openTrainingHub = activity.substringAfter("private fun openTrainingHub").substringBefore("private fun showTrainingUi")

        assertFalse(onCreate.contains("requestCameraPermissionIfNeeded()"))
        assertTrue(openTrainingHub.contains("requestCameraPermissionIfNeeded()"))
    }
}
