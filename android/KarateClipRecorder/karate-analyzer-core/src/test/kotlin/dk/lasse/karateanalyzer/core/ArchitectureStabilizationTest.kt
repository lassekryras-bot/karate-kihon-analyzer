package dk.lasse.karateanalyzer.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArchitectureStabilizationTest {
    private val repoRoot: File = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { it.resolve("settings.gradle.kts").isFile && it.resolve("app/build.gradle.kts").isFile }

    @Test fun appDependsOnMediaPipeHandAdapter() {
        val appBuild = repoRoot.resolve("app/build.gradle.kts").readText()

        assertTrue(
            appBuild.contains("""implementation(project(":mediapipe-hand-adapter"))"""),
            "App must depend on mediapipe-hand-adapter for live hand analysis",
        )
    }

    @Test fun analyzerCoreHasNoAndroidOrMediaPipeImports() {
        val coreSources = repoRoot.resolve("karate-analyzer-core/src/main/kotlin")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }

        assertFalse(coreSources.contains("import android."), "Analyzer core must remain Android-free")
        assertFalse(coreSources.contains("import com.google.mediapipe"), "Analyzer core must remain MediaPipe-free")
    }

    @Test fun adapterDoesNotCallTemporalVerifier() {
        val adapterSources = repoRoot.resolve("mediapipe-hand-adapter/src/main/kotlin")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }

        assertFalse(
            adapterSources.contains("FindYourWeaponTemporalVerifier"),
            "MediaPipe adapter must not call temporal verifier",
        )
    }

    @Test fun adapterDoesNotDownloadRuntimeModels() {
        val adapterSources = repoRoot.resolve("mediapipe-hand-adapter/src/main/kotlin")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }

        assertFalse(adapterSources.contains("http://"), "Adapter must not download models at runtime")
        assertFalse(adapterSources.contains("https://"), "Adapter must not download models at runtime")
    }

    @Test fun binaryModelsAreNotCommitted() {
        val committedModelAssets = repoRoot.walkTopDown()
            .filter { it.isFile && it.extension in setOf("task", "tflite") }
            .toList()

        assertTrue(
            committedModelAssets.isEmpty(),
            "Binary .task and .tflite models must not be committed without explicit review: $committedModelAssets",
        )
    }
}
