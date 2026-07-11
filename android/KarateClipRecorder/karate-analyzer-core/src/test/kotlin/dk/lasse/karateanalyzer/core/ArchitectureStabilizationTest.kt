package dk.lasse.karateanalyzer.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse

class ArchitectureStabilizationTest {
    private val repoRoot: Path = generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
        .first { Files.exists(it.resolve("settings.gradle.kts")) && it.resolve("settings.gradle.kts").readText().contains("KarateClipRecorder") }

    @Test fun coreHasNoAndroidOrMediaPipeImports() {
        val forbidden = listOf("import android.", "import androidx.", "import com.google.mediapipe")
        kotlinFiles(repoRoot.resolve("karate-analyzer-core/src/main/kotlin")).forEach { file ->
            val text = file.readText()
            forbidden.forEach { token ->
                assertFalse(text.contains(token), "${file.fileName} must not contain $token")
            }
        }
    }

    @Test fun mediaPipeAdapterDoesNotCallTemporalVerifier() {
        kotlinFiles(repoRoot.resolve("mediapipe-hand-adapter/src/main/kotlin")).forEach { file ->
            assertFalse(file.readText().contains("FindYourWeaponTemporalVerifier"), "Adapter must not call temporal verifier: $file")
        }
    }

    @Test fun appDoesNotDependOnMediaPipeHandAdapterYet() {
        val appBuild = repoRoot.resolve("app/build.gradle.kts").readText()
        assertFalse(appBuild.contains("mediapipe-hand-adapter"), "App must not depend on mediapipe-hand-adapter yet")
    }

    @Test fun mediaPipeAdapterDoesNotDownloadRuntimeModelsOrBundleBinaryModels() {
        val adapterRoot = repoRoot.resolve("mediapipe-hand-adapter")
        val forbiddenText = listOf("downloadModel", "model download", "http://", "https://")
        projectFiles(adapterRoot).forEach { file ->
            val text = runCatching { file.readText() }.getOrDefault("")
            forbiddenText.forEach { token ->
                assertFalse(text.contains(token), "Unexpected runtime model-download token '$token' in $file")
            }
        }
        val binaryModel = projectFiles(adapterRoot).any { it.name.endsWith(".task") || it.name.endsWith(".tflite") }
        assertFalse(binaryModel, "No binary MediaPipe model should be committed")
    }


    private fun kotlinFiles(root: Path): List<Path> = projectFiles(root).filter { it.name.endsWith(".kt") || it.name.endsWith(".kts") }

    private fun projectFiles(root: Path): List<Path> = Files.walk(root).use { stream ->
        stream.filter { it.isRegularFile() }
            .filter { !it.toString().contains("/.gradle/") && !it.toString().contains("/build/") }
            .toList()
    }
}
