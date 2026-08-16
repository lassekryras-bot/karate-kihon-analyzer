package dk.lasse.karatecliprecorder.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JapaneseCountingActivityShellArchitectureTest {
    private val root: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { it.resolve("settings.gradle.kts").isFile && it.resolve("app/build.gradle.kts").isFile }
    private val sources = root.resolve("app/src/main/java/dk/lasse/karatecliprecorder")

    @Test fun shellOwnsGenericRegionsWithoutKnowingJapaneseLessonData() {
        val shell = sources.resolve("learningactivity/ActivityShellView.kt").readText()

        assertTrue(shell.contains("class ActivityShellView"))
        assertTrue(shell.contains("runnerSlot"))
        assertTrue(shell.contains("progressSlot"))
        assertTrue(shell.contains("actionBar"))
        assertTrue(shell.contains("WindowInsetsCompat.Type.systemBars()"))
        assertFalse(shell.contains("JapaneseCount"))
        assertFalse(shell.contains("CAMERA"))
        assertFalse(shell.contains("MICROPHONE"))
        assertFalse(shell.contains("AppBottomNavigationView"))
    }

    @Test fun practiceRouteOpensReadyShellWithoutTrainingCameraOrPermissionRequests() {
        val activity = sources.resolve("MainActivity.kt").readText()
        val route = activity.substringAfter("private fun openLearningActivity").substringBefore("private fun showTrainingUi")
        val openPractice = activity.substringAfter("private fun openJapaneseCountingPractice").substringBefore("private fun beginJapaneseCountingPractice")

        assertTrue(route.contains("JAPANESE_COUNTING_PRACTICE -> openJapaneseCountingPractice()"))
        assertFalse(route.substringAfter("JAPANESE_COUNTING_PRACTICE").substringBefore("JAPANESE_COUNTING_TEST").contains("showTrainingUi"))
        assertTrue(openPractice.contains("JapaneseCountingPracticeView"))
        assertFalse(openPractice.contains("japaneseCountLevel1Controller.start()"))
        assertFalse(openPractice.contains("cameraPermissionLauncher"))
        assertFalse(openPractice.contains("audioPermissionLauncher"))
        assertTrue(activity.contains("onContinueToTest = ::continueFromJapaneseCountingPracticeToTest"))
        assertTrue(activity.contains("showSkillProgression(requireLearningPath(LearningPathId.JAPANESE_COUNTING))"))
    }

    @Test fun completionCharacterKeepsSeparateSvgLayersAndFiveToneBelt() {
        val raw = root.resolve("app/src/main/res/raw")
        val character = sources.resolve("learningactivity/KarateCharacterView.kt").readText()
        val expectedToneCounts = mapOf(
            "karate_character_body_base.svg" to 5,
            "karate_character_face_male.svg" to 10,
            "karate_character_face_female.svg" to 10,
            "karate_character_belt_template.svg" to 5,
        )

        expectedToneCounts.forEach { (name, toneCount) ->
            val source = raw.resolve(name).readText()
            assertEquals(toneCount, Regex("data-tone=\"\\d+\"").findAll(source).count(), name)
        }
        assertTrue(character.contains("KarateFaceVariant.MALE"))
        assertTrue(character.contains("KarateFaceVariant.FEMALE"))
        assertTrue(character.contains("KarateBeltRank.WHITE"))
        assertTrue(character.contains("faceMale: RectF"))
        assertTrue(character.contains("faceFemale: RectF"))
        assertTrue(character.contains("belt: RectF"))
        assertTrue(character.contains("beltOpticalScale: Float = 0.929575f"))
        assertTrue(character.contains("tonePalette = beltRank.tonesDarkToLight"))
    }
}
