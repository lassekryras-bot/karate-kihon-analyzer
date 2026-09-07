package dk.lasse.karatecliprecorder.profile

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileContractTest {
    @Test
    fun beltRankOrderMatchesKyokushinMvpContract() {
        assertEquals(
            listOf("White", "Orange", "Blue", "Yellow", "Green", "Brown", "Black"),
            BeltRank.entries.map(BeltRank::displayName),
        )
    }

    @Test
    fun carouselShowsFiveLargerChoicesAndWrapsWithSelectedExactlyCentered() {
        val first = AvatarCarouselModel.visibleBaseIds(0)
        assertEquals(5, first.size)
        assertEquals("avatar_01", first[2])
        assertEquals(listOf("avatar_11", "avatar_12"), first.take(2))
        assertEquals(listOf("avatar_02", "avatar_03"), first.takeLast(2))

        val last = AvatarCarouselModel.visibleBaseIds(11)
        assertEquals("avatar_12", last[2])
        assertEquals("avatar_01", last[3])
    }

    @Test
    fun genderAndAgeDoNotParticipateInCarouselSelection() {
        val expected = AvatarCarouselModel.visibleBaseIds(5)
        Gender.entries.forEach { _ -> AgeGroup.entries.forEach { _ ->
            assertEquals(expected, AvatarCarouselModel.visibleBaseIds(5))
        } }
    }

    @Test
    fun avatarShortcutAndNavigationStayAtTheCorrectDepth() {
        val sourceRoot = locateSourceRoot()
        val topLevel = listOf(
            "HomeScreenView.kt",
            "learningpath/LearnScreenView.kt",
            "profile/ProgressScreenView.kt",
            "SettingsScreenView.kt",
        ).map { File(sourceRoot, it).readText() }
        assertTrue(topLevel.all { "ProfileAvatarButton" in it })
        assertTrue("onProfile" in File(sourceRoot, "HomeScreenView.kt").readText())

        val secondary = listOf(
            "profile/ProfileScreenView.kt",
            "profile/ProfileEditorView.kt",
            "profile/ManageProfilesView.kt",
            "learningpath/SkillProgressionView.kt",
        ).map { File(sourceRoot, it).readText() }
        assertTrue(secondary.none { "AppBottomNavigationView" in it })
        assertTrue(secondary.take(3).none { it.contains("CameraX") || it.contains("MediaPipe") || it.contains("SpeechRecognizer") })
    }

    @Test
    fun rendererUsesSemanticBeltRoleForBeltAndFollowingAccessories() {
        val renderer = File(locateSourceRoot(), "profile/AvatarView.kt").readText()
        assertTrue("\"belt\" -> AvatarPalette.shade(AvatarPalette.belt(beltRank), path.tone)" in renderer)
        assertFalse("hairColorPosition), path.tone" in renderer.substringAfter("\"belt\" ->").substringBefore("else ->"))
    }

    @Test
    fun profileEditorIsUnnumberedAndFitsAllBeltsWithoutHorizontalScrolling() {
        val editor = File(locateSourceRoot(), "profile/ProfileEditorView.kt").readText()
        listOf("1. Name", "2. Gender", "3. Age group", "4. Choose character",
            "5. Skin tone", "6. Hair color", "7. Current belt").forEach {
            assertFalse(it in editor)
        }
        assertFalse("HorizontalScrollView" in editor)
        assertTrue("BeltRank.entries.forEachIndexed" in editor)
        assertTrue("LinearLayout.LayoutParams(0, 56.dp(), 1f)" in editor)
        assertTrue("AppIcon.KARATE_BELT" in editor)
        assertTrue("visibleIndex == 2" in editor)
    }

    private fun locateSourceRoot(): File {
        val candidates = listOf(
            File("src/main/java/dk/lasse/karatecliprecorder"),
            File("app/src/main/java/dk/lasse/karatecliprecorder"),
            File("android/KarateClipRecorder/app/src/main/java/dk/lasse/karatecliprecorder"),
        )
        return candidates.firstOrNull(File::isDirectory)
            ?: error("Could not locate app source root from ${File(".").absolutePath}")
    }
}
