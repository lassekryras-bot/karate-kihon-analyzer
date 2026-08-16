package dk.lasse.karatecliprecorder.architecture

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnsoAssetArchitectureTest {
    private val workingDirectory = requireNotNull(System.getProperty("user.dir"))
    private val projectRoot = generateSequence(File(workingDirectory)) { it.parentFile }
        .first { it.resolve("settings.gradle.kts").isFile && it.resolve("app/build.gradle.kts").isFile }
    private val runtimeDirectory = projectRoot.resolve("app/src/main/res/raw")
    private val artworkDirectory = projectRoot.resolve("artwork/enso")

    @Test fun completeRuntimeAndMasterLibrariesArePresent() {
        val expectedNames = (1..20).map { "enso_${it.toString().padStart(2, '0')}.svg" }
        val runtime = runtimeDirectory.listFiles().orEmpty()
            .filter { it.name.matches(Regex("enso_\\d{2}\\.svg")) }
            .map(File::getName)
            .sorted()
        val masters = artworkDirectory.resolve("masters").listFiles().orEmpty()
            .filter { it.name.matches(Regex("enso_\\d{2}\\.svg")) }
            .map(File::getName)
            .sorted()

        assertEquals(expectedNames, runtime)
        assertEquals(expectedNames, masters)
        assertTrue(artworkDirectory.resolve("README.md").isFile)
        assertTrue(artworkDirectory.resolve("manifest.json").isFile)
        assertTrue(artworkDirectory.resolve("tone_levels.json").isFile)
    }

    @Test fun runtimeSvgsKeepTheCanonicalGeometryAndToneVocabulary() {
        val allowedFills = CANONICAL_GRAYS.map { gray -> "#%02X%02X%02X".format(gray, gray, gray) }.toSet() + "#FFFFFF"
        val allFills = mutableSetOf<String>()
        val hashes = mutableSetOf<String>()

        (1..20).forEach { number ->
            val svg = runtimeDirectory.resolve("enso_${number.toString().padStart(2, '0')}.svg")
            val source = svg.readText()
            val fills = FILL_PATTERN.findAll(source).map { it.groupValues[1].uppercase() }.toSet()
            val drawableCount = DRAWABLE_PATTERN.findAll(source).count()
            val filledDrawableCount = FILLED_DRAWABLE_PATTERN.findAll(source).count()

            assertTrue(source.contains("viewBox=\"0 0 1024 1024\""), svg.name)
            assertTrue(fills.isNotEmpty(), svg.name)
            assertTrue(fills.all(allowedFills::contains), svg.name)
            assertTrue("#FFFFFF" in fills, svg.name)
            assertEquals(drawableCount, filledDrawableCount, svg.name)
            assertFalse(UNSUPPORTED_PATTERN.containsMatchIn(source), svg.name)
            allFills += fills
            hashes += svg.sha256()
        }

        assertEquals(allowedFills, allFills)
        assertEquals(20, hashes.size)
    }

    @Test fun backgroundRenderingDoesNotOwnSelectionOrForegroundContent() {
        val source = projectRoot
            .resolve("app/src/main/java/dk/lasse/karatecliprecorder/enso/EnsoBackgroundView.kt")
            .readText()

        assertFalse(source.contains(".random()"))
        assertFalse(source.contains("EnsoLibrary("))
        assertTrue(source.contains("fun setArtwork("))
        assertTrue(source.contains("DEFAULT_ARTWORK_SCALE = 0.90f"))
        assertTrue(source.contains("PathParser.createPathFromPathData"))
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val CANONICAL_GRAYS = listOf(14, 30, 52, 71, 90, 107, 121, 134, 147, 160, 175, 188, 205, 225, 246)
        private val FILL_PATTERN = Regex("fill=[\\\"'](#[0-9A-Fa-f]{6})")
        private val DRAWABLE_PATTERN = Regex("<(?:path|rect)\\b")
        private val FILLED_DRAWABLE_PATTERN = Regex("<(?:path|rect)\\b[^>]*\\bfill=")
        private val UNSUPPORTED_PATTERN = Regex(
            "<(?:circle|ellipse|line|polyline|polygon|image|use|text)\\b|\\b(?:transform|opacity|style)=",
        )
    }
}
