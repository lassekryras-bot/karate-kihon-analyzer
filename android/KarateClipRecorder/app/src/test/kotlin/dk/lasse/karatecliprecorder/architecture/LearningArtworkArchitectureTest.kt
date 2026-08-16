package dk.lasse.karatecliprecorder.architecture

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LearningArtworkArchitectureTest {
    private val workingDirectory = requireNotNull(System.getProperty("user.dir"))
    private val projectRoot = generateSequence(File(workingDirectory)) { it.parentFile }
        .first { it.resolve("settings.gradle.kts").isFile && it.resolve("app/build.gradle.kts").isFile }
    private val sources = projectRoot.resolve("app/src/main/java/dk/lasse/karatecliprecorder")

    @Test fun suppliedJapaneseCountingAssetRemainsOneBlackTextFreeVector() {
        val asset = projectRoot.resolve("app/src/main/res/raw/japanese_counting.svg")
        val source = asset.readText()

        assertEquals(SUPPLIED_ASSET_SHA256, asset.sha256())
        assertTrue(source.contains("viewBox=\"0 0 1024 1024\""))
        assertEquals(1, Regex("<path\\b").findAll(source).count())
        assertTrue(source.contains("fill=\"rgba(0,0,0,1)\""))
        assertFalse(Regex("<text\\b", RegexOption.IGNORE_CASE).containsMatchIn(source))
        listOf("Practice", "Test", "Level 1", "Level 2", "Japanese Counting").forEach { copy ->
            assertFalse(source.contains(copy, ignoreCase = true))
        }
    }

    @Test fun learningArtworkKeepsStableIndependentLayersAndSemanticPaletteResolution() {
        val component = sources.resolve("learningartwork/LearningPathArtworkView.kt").readText()
        val model = sources.resolve("learningartwork/LearningArtwork.kt").readText()

        assertTrue(component.indexOf("addView(ensoView") < component.indexOf("addView(foregroundView"))
        assertTrue(component.contains("private var selectedEnso"))
        assertTrue(component.contains("if (selectedEnso == null)"))
        assertFalse(component.substringAfter("override fun onDraw").contains("EnsoLibrary()"))
        assertTrue(component.contains("FOREGROUND_SCALE = 0.54f"))
        assertTrue(model.contains("PRACTICE"))
        assertTrue(model.contains("TEST"))
        assertTrue(model.contains("ensoPracticeBaseColor"))
        assertTrue(model.contains("ensoTestBaseColor"))
        assertFalse(model.contains("activity.name"))
    }

    @Test fun continueCardUsesOneStableEnsoAndResponsiveProgressLayout() {
        val home = sources.resolve("HomeScreenView.kt").readText()
        val continueCard = home.substringAfter("private fun continueCard").substringBefore("private fun progressCopy")

        assertFalse(home.contains("CONTINUE WHERE YOU LEFT OFF"))
        assertTrue(continueCard.contains("Continue learning"))
        assertTrue(continueCard.contains("EnsoBackgroundView"))
        assertTrue(continueCard.contains("EnsoThemeTokens.ensoBaseColor"))
        assertTrue(home.contains("private val continueEnso = EnsoLibrary().createInstance()"))
        assertFalse(continueCard.contains("EnsoLibrary()"))
        assertTrue(continueCard.contains("0.38f"))
        assertTrue(continueCard.contains("0.62f"))
        assertTrue(continueCard.contains("ProgressBar"))
        assertTrue(continueCard.contains("Continue ›"))
        listOf("🥋", "R.drawable.gi", "karate figure").forEach { forbidden ->
            assertFalse(continueCard.contains(forbidden, ignoreCase = true))
        }
    }

    @Test fun japaneseCountingEntriesUseSharedForegroundAndExplicitPracticeTestTypes() {
        val activity = sources.resolve("MainActivity.kt").readText()
        val entryView = sources.resolve("learningartwork/LearningActivityEntryView.kt").readText()
        val gallery = sources.resolve("enso/EnsoDebugGalleryView.kt").readText()

        assertTrue(activity.contains("activityType = LearningActivityType.PRACTICE"))
        assertTrue(activity.contains("activityType = LearningActivityType.TEST"))
        assertTrue(activity.contains("LearningActivityType.PRACTICE"))
        assertTrue(activity.contains("LearningActivityType.TEST"))
        assertFalse(activity.contains("Japanese Count - Level 1"))
        assertFalse(activity.contains("Japanese Count - Level 2"))
        assertTrue(entryView.contains("LearningArtworkForeground.JAPANESE_COUNTING"))
        assertEquals(1, entryView.split("LearningArtworkForeground.JAPANESE_COUNTING").size - 1)
        assertTrue(gallery.contains("LearningActivityType.PRACTICE"))
        assertTrue(gallery.contains("LearningActivityType.TEST"))
        assertTrue(gallery.contains("EnsoVariant.all"))
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val normalizedBytes = readText(Charsets.UTF_8)
            .replace("\r\n", "\n")
            .toByteArray(Charsets.UTF_8)
        digest.update(normalizedBytes)
        return digest.digest().joinToString("") { "%02X".format(it) }
    }

    companion object {
        private const val SUPPLIED_ASSET_SHA256 = "71597240E9FD16E8FA53EEDCFD71E22B26C6A1ABFFF17552AF55DB5E85D2F102"
    }
}
