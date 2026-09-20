package dk.lasse.karatecliprecorder.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkillCoachLandingArchitectureTest {
    private val appRoot = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { it.resolve("settings.gradle.kts").isFile && it.resolve("app/build.gradle.kts").isFile }
    private val sources = appRoot.resolve("app/src/main/java/dk/lasse/karatecliprecorder")
    private val resources = appRoot.resolve("app/src/main/res")

    @Test fun skillCoachUsesSharedTopLevelChromeAndTrainingNavigation() {
        val screen = sources.resolve("skillcoach/SkillCoachScreenView.kt").readText()

        assertTrue(screen.contains("MainPageHeader"))
        assertTrue(screen.contains("StickyHeaderPageLayout"))
        assertTrue(screen.contains("ProfileAvatarButton"))
        assertTrue(screen.contains("title = \"Skill Coach\""))
        assertTrue(screen.contains("val destination: AppDestination = AppDestination.TRAIN"))
        assertFalse(screen.contains("AppBottomNavigationView("))
        assertTrue(screen.contains("AppBottomNavigationView.CONTENT_CLEARANCE_DP"))
    }

    @Test fun homeAndTrainingEntriesOpenThePassiveCoachWorkspace() {
        val activity = sources.resolve("MainActivity.kt").readText()
        val construction = activity.substringAfter("homeScreen = HomeScreenView").substringBefore("setContentView")
        val route = activity.substringAfter("private fun showSkillCoachUi()").substringBefore("private fun showHomeUi()")

        assertTrue(construction.windowed("onSkillCoach = ::showSkillCoachUi".length)
            .count { it == "onSkillCoach = ::showSkillCoachUi" } == 2)
        assertTrue(construction.contains("SkillCoachScreenView"))
        assertTrue(construction.contains("includeDemoRecent = isDebuggable"))
        assertTrue(route.contains("currentAppDestination = AppDestination.TRAIN"))
        assertTrue(route.contains("skillCoachScreen.visibility = View.VISIBLE"))
        listOf("CameraX", "MediaPipe", "permissionLauncher", "requestCameraPermission").forEach {
            assertFalse(route.contains(it))
        }
    }

    @Test fun landingAndArtworkRemainHardwareFreeAndLocalizationSafe() {
        val screen = sources.resolve("skillcoach/SkillCoachScreenView.kt").readText()
        val guide = sources.resolve("skillcoach/SenseiGuideView.kt").readText()
        val svg = resources.resolve("raw/sensei_speaking_blank_bubble.svg").readText()
        val combined = screen + guide

        listOf("CameraX", "MediaPipe", "RequestPermission", "LivePoseLandmarkerRunner").forEach {
            assertFalse(combined.contains(it))
        }
        assertTrue(guide.contains("Sensei says: \$speech"))
        assertTrue(guide.contains("canvas.scale(-scale, scale)"))
        assertFalse(svg.contains("Show me a few movements"))
        assertTrue(svg.contains("<path"))
    }

    @Test fun cardContentComesFromAReplaceableStateModel() {
        val state = sources.resolve("skillcoach/SkillCoachLandingState.kt").readText()
        val screen = sources.resolve("skillcoach/SkillCoachScreenView.kt").readText()

        listOf("PersonalizedGuidanceState", "recommendation", "senseiSpeech", "ctaLabel", "ctaAction", "illustration")
            .forEach { assertTrue(state.contains(it)) }
        assertTrue(state.contains("if (includeDemoRecent)"))
        assertTrue(state.contains("isDemo = true"))
        assertTrue(screen.contains("SettingsRowView"))
        assertTrue(screen.contains("configureAsNavigation"))
        assertTrue(screen.contains("R.color.skill_coach_guidance_surface"))
        assertTrue(resources.resolve("values/colors.xml").readText().contains("skill_coach_guidance_surface"))
        assertTrue(resources.resolve("values-night/colors.xml").readText().contains("skill_coach_guidance_surface"))
    }

    @Test fun guidanceCardKeepsSpeechInTheBubbleAndUsesTheCompactArtworkSlot() {
        val screen = sources.resolve("skillcoach/SkillCoachScreenView.kt").readText()
        val guide = sources.resolve("skillcoach/SenseiGuideView.kt").readText()
        val guidanceCard = screen.substringAfter("private fun guidanceCard").substringBefore("private fun toolSection")

        assertTrue(screen.contains("addView(sectionLabel(\"PERSONALIZED GUIDANCE\", first = true))"))
        assertFalse(guidanceCard.contains("PERSONALIZED GUIDANCE"))
        assertTrue(screen.contains("SenseiGuideView.STANDARD_HEIGHT_DP.dp()"))
        assertFalse(screen.contains("270.dp()"))
        assertTrue(guide.contains("SenseiArtworkGeometry.speechBounds"))
        assertTrue(guide.contains("STANDARD_HEIGHT_DP = 200"))
        assertTrue(guide.contains("ARTWORK_SIZE_DP = 240"))
        assertTrue(guide.contains("ARTWORK_GRAVITY = Gravity.TOP or Gravity.CENTER_HORIZONTAL"))
        assertTrue(guide.contains("SPEECH_TEXT_SIZE_SP = 10.5f"))
        assertTrue(guide.contains("SPEECH_MAX_LINES = 3"))
        assertTrue(guide.contains("BUBBLE_LEFT"))
        assertTrue(guide.contains("BUBBLE_TOP"))
        assertTrue(guide.contains("maxLines = SPEECH_MAX_LINES"))
        assertTrue(guide.contains("gravity = Gravity.CENTER"))
        assertFalse(guide.contains("height * 0.37f"))
        assertFalse(guide.contains("ARTWORK_WIDTH_FRACTION"))
    }
}
