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
        val navigation = appSources.resolve("AppBottomNavigationView.kt").readText()
        val icons = appSources.resolve("AppIconView.kt").readText()
        val navigationSources = home + navigation + icons

        listOf("⌂", "◆", "▥", "⚙").forEach { placeholder ->
            assertFalse(navigationSources.contains(placeholder))
        }
        listOf(
            "R.drawable.ic_nav_home",
            "R.drawable.ic_tabler_karate",
            "R.drawable.ic_nav_progress",
            "R.drawable.ic_nav_settings",
        ).forEach { drawable -> assertTrue(navigationSources.contains(drawable)) }
        assertTrue(navigation.contains("R.color.nav_icon_tint"))
        assertTrue(navigation.contains("isSelected = selected"))
        assertTrue(home.contains("onProgress: () -> Unit"))
        assertTrue(home.contains("onSettings: () -> Unit"))

        val resources = appRoot.resolve("app/src/main/res")
        listOf(
            "drawable/ic_nav_home.xml",
            "drawable/ic_tabler_karate.xml",
            "drawable/ic_nav_progress.xml",
            "drawable/ic_nav_settings.xml",
            "color/nav_icon_tint.xml",
        ).forEach { resource -> assertTrue(resources.resolve(resource).isFile) }

        val tint = resources.resolve("color/nav_icon_tint.xml").readText()
        assertTrue(tint.contains("@color/app_accent"))
        assertTrue(tint.contains("@color/app_nav_inactive"))
        assertTrue(tint.contains("android:state_selected=\"true\""))
        assertTrue(resources.resolve("values/colors.xml").readText().contains("#BE000C"))
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
        assertTrue(contentTint.contains("@color/app_accent"))
        assertTrue(resources.resolve("values/colors.xml").readText().contains("#BE000C"))

        val karate = resources.resolve("drawable/ic_tabler_karate.xml").readText()
        assertTrue(karate.contains("android:viewportWidth=\"24\""))
        assertTrue(karate.contains("android:pathData=\"M3 9l4.5 1l3 2.5\""))
        assertTrue(karate.contains("android:strokeWidth=\"2\""))
        assertFalse(resources.resolve("drawable/ic_nav_train_belt.xml").exists())
    }

    @Test fun passiveNavigationDestinationsAvoidTrainingStartup() {
        val activity = appSources.resolve("MainActivity.kt").readText()
        val homeConstruction = activity.substringAfter("homeScreen = HomeScreenView").substringBefore("setContentView")
        val placeholder = activity.substringAfter("private fun showHomeDestinationPlaceholder").substringBefore("private fun openTrainingHub")

        assertFalse(homeConstruction.contains("onProgress = { showHomeDestinationPlaceholder"))
        assertTrue(homeConstruction.windowed("onProgress = ::showProgressUi".length)
            .count { it == "onProgress = ::showProgressUi" } >= 3)
        assertTrue(homeConstruction.contains("onSettings = ::showSettingsUi"))
        assertFalse(placeholder.contains("showTrainingUi"))
        assertFalse(placeholder.contains("requestCameraPermissionIfNeeded"))

        val settings = appSources.resolve("SettingsScreenView.kt").readText()
        assertFalse(settings.contains("CameraXRecordingAdapter"))
        assertFalse(settings.contains("LiveGestureRecognizerRunner"))
        assertFalse(settings.contains("LivePoseLandmarkerRunner"))
    }

    @Test fun bottomNavigationStaysAboveSystemNavigation() {
        val home = appSources.resolve("HomeScreenView.kt").readText()
        val settings = appSources.resolve("SettingsScreenView.kt").readText()
        val learn = appSources.resolve("learningpath/LearnScreenView.kt").readText()
        val progression = appSources.resolve("learningpath/SkillProgressionView.kt").readText()
        val progress = appSources.resolve("profile/ProgressScreenView.kt").readText()
        val navigation = appSources.resolve("AppBottomNavigationView.kt").readText()

        assertTrue(navigation.contains("WindowInsetsCompat.Type.navigationBars()"))
        assertTrue(navigation.contains("BASE_HEIGHT_DP.dp() + navigationBarBottom"))
        assertTrue(navigation.contains("BOTTOM_PADDING_DP.dp() + navigationBarBottom"))
        assertTrue(navigation.contains("ViewCompat.requestApplyInsets(this)"))
        listOf(home, settings, learn, progress).forEach { screen ->
            assertTrue(screen.contains("AppBottomNavigationView.BASE_HEIGHT_DP.dp()"))
            assertFalse(screen.contains("bottomMargin = navigationBars.bottom"))
        }
        assertFalse(progression.contains("AppBottomNavigationView"))

        val lightColors = appRoot.resolve("app/src/main/res/values/colors.xml").readText()
        val darkColors = appRoot.resolve("app/src/main/res/values-night/colors.xml").readText()
        val lightStyle = appRoot.resolve("app/src/main/res/values/styles.xml").readText()
        val darkStyle = appRoot.resolve("app/src/main/res/values-night/styles.xml").readText()
        assertTrue(lightColors.contains("<color name=\"app_system_navigation\">#FFFFFF</color>"))
        assertTrue(darkColors.contains("<color name=\"app_system_navigation\">#1E1E1E</color>"))
        assertTrue(lightStyle.contains("<item name=\"android:windowLightNavigationBar\">true</item>"))
        assertTrue(darkStyle.contains("<item name=\"android:windowLightNavigationBar\">false</item>"))
        assertTrue(lightStyle.contains("<item name=\"android:enforceNavigationBarContrast\">false</item>"))
        assertTrue(darkStyle.contains("<item name=\"android:enforceNavigationBarContrast\">false</item>"))
    }

    @Test fun sharedStickyHeadersOwnSystemBarInsetsAndScrolling() {
        val headerSource = appSources.resolve("PageHeaders.kt").readText()
        val mainScreens = listOf(
            "HomeScreenView.kt",
            "SettingsScreenView.kt",
            "learningpath/LearnScreenView.kt",
            "profile/ProgressScreenView.kt",
        )
        val subScreens = listOf(
            "learningpath/SkillProgressionView.kt",
            "learningactivity/ActivityShellView.kt",
            "profile/ProfileScreenView.kt",
            "profile/ProfileEditorView.kt",
            "profile/ManageProfilesView.kt",
            "enso/EnsoDebugGalleryView.kt",
        )

        assertTrue(headerSource.contains("class MainPageHeader"))
        assertTrue(headerSource.contains("class SubPageHeader"))
        assertTrue(headerSource.contains("class StickyHeaderPageLayout"))
        assertTrue(headerSource.contains("WindowInsetsCompat.Type.statusBars()"))
        assertTrue(headerSource.contains("WindowInsetsCompat.Type.displayCutout()"))
        assertTrue(headerSource.indexOf("addView(header") < headerSource.indexOf("addView(scroller"))
        assertTrue(headerSource.contains("trailingSlot: View? = null"))
        assertTrue(headerSource.contains("SURFACE_COLOR_RES = R.color.app_card_surface"))
        assertTrue(headerSource.contains("ELEVATION_DP = 4"))

        mainScreens.forEach { relativePath ->
            val source = appSources.resolve(relativePath).readText()
            assertTrue(source.contains("MainPageHeader"), relativePath)
            assertTrue(source.contains("StickyHeaderPageLayout"), relativePath)
            assertFalse(source.contains("WindowInsetsCompat.Type.systemBars()"), relativePath)
        }
        subScreens.forEach { relativePath ->
            val source = appSources.resolve(relativePath).readText()
            assertTrue(source.contains("SubPageHeader"), relativePath)
            assertTrue(source.contains("StickyHeaderPageLayout"), relativePath)
            assertFalse(source.contains("WindowInsetsCompat.Type.systemBars()"), relativePath)
        }
    }

    @Test fun homeGreetingAndBottomChromeStayCompactAndAccessible() {
        val home = appSources.resolve("HomeScreenView.kt").readText()
        val navigation = appSources.resolve("AppBottomNavigationView.kt").readText()

        assertTrue(home.contains("title = \"Karate Kihon Analyzer\""))
        assertTrue(home.contains("subtitle = \"Welcome \${profileRepository.activeProfile().name}\""))
        assertTrue(home.contains("mainHeader.setSubtitle(\"Welcome \${it.name}\")"))
        assertTrue(navigation.contains("BASE_HEIGHT_DP = 68"))
        assertTrue(navigation.contains("BOTTOM_PADDING_DP = 0"))
        assertTrue(navigation.contains("minimumWidth = 48.dp()"))
        assertTrue(navigation.contains("minimumHeight = 48.dp()"))
        assertTrue(navigation.contains("AppChromeStyle.ELEVATION_DP"))
        assertTrue(navigation.contains("AppChromeStyle.SURFACE_COLOR_RES"))
        assertTrue(navigation.contains("AppIcon.KARATE"))
    }

    @Test fun cameraStartupRequiresExplicitTrainingNavigation() {
        val activity = appSources.resolve("MainActivity.kt").readText()
        val onCreate = activity.substringAfter("override fun onCreate").substringBefore("private fun openTrainingHub")
        val openTrainingHub = activity.substringAfter("private fun openTrainingHub").substringBefore("private fun showTrainingUi")

        assertFalse(onCreate.contains("requestCameraPermissionIfNeeded()"))
        assertTrue(openTrainingHub.contains("requestCameraPermissionIfNeeded()"))
    }
}
