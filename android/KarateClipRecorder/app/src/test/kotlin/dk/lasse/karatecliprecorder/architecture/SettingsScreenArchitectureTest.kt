package dk.lasse.karatecliprecorder.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsScreenArchitectureTest {
    private val appRoot = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { it.resolve("settings.gradle.kts").isFile && it.resolve("app/build.gradle.kts").isFile }
    private val sources = appRoot.resolve("app/src/main/java/dk/lasse/karatecliprecorder")
    private val resources = appRoot.resolve("app/src/main/res")

    @Test fun settingsUsesReusableSectionsCardsAndRows() {
        val screen = sources.resolve("SettingsScreenView.kt").readText()
        val components = sources.resolve("SettingsComponents.kt").readText()

        listOf(
            "CAMERA & ANALYSIS",
            "SOUND & VOICE",
            "TRAINING PREFERENCES",
            "APPEARANCE",
            "DATA & PRIVACY",
            "DEVELOPER & DEBUG",
            "ABOUT",
        ).forEach { heading -> assertTrue(screen.contains(heading)) }
        assertTrue(components.contains("class SettingsSectionView"))
        assertTrue(components.contains("class SettingsCardView"))
        assertTrue(components.contains("class SettingsRowView"))
        assertTrue(screen.contains("ScrollView"))
    }

    @Test fun requestedGenericIconsRouteThroughSharedTablerAbstraction() {
        val icons = sources.resolve("AppIconView.kt").readText()
        listOf(
            "CAMERA", "SHIELD_CHECK", "VOLUME", "MICROPHONE", "CLOCK", "PALETTE",
            "DATABASE", "TRASH", "CODE", "BUG", "INFO_CIRCLE", "HELP_CIRCLE",
            "SHIELD", "CHEVRON_RIGHT", "HOME", "CHART_BAR", "SETTINGS",
        ).forEach { icon -> assertTrue(icons.contains("$icon(")) }
        assertTrue(icons.contains("KARATE_BELT(R.drawable.ic_nav_train_belt)"))
        assertTrue(appRoot.resolve("THIRD_PARTY_NOTICES.md").readText().contains("Tabler Icons 3.46.0"))
    }

    @Test fun settingsPersistAndDestructiveClearRequiresConfirmation() {
        val preferences = sources.resolve("AppPreferences.kt").readText()
        val activity = sources.resolve("MainActivity.kt").readText()
        val screen = sources.resolve("SettingsScreenView.kt").readText()

        assertTrue(preferences.contains("getSharedPreferences"))
        assertTrue(preferences.contains("KEY_TRAINING_SOUNDS"))
        assertTrue(preferences.contains("KEY_VOICE_GUIDANCE"))
        assertTrue(preferences.contains("KEY_COUNTDOWN_SECONDS"))
        assertTrue(preferences.contains("KEY_DEVELOPER_MODE"))
        assertTrue(activity.contains("confirmClearTrainingHistory"))
        assertTrue(activity.contains(".setPositiveButton(\"Clear\")"))
        assertFalse(screen.contains("deleteRecursively"))
    }

    @Test fun selectedNavigationAndSafeAreaStayResponsive() {
        val screen = sources.resolve("SettingsScreenView.kt").readText()
        assertTrue(screen.contains("selectedDestination = AppDestination.SETTINGS"))
        assertTrue(screen.contains("WindowInsetsCompat.Type.systemBars()"))
        assertTrue(screen.contains("WindowInsetsCompat.Type.navigationBars()"))
        assertTrue(screen.contains("bottomMargin = navigationBars.bottom"))
        assertTrue(resources.resolve("drawable/ic_nav_train_belt.xml").readText().contains("viewportWidth=\"640\""))
    }
}
