package dk.lasse.karatecliprecorder.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArchitectureStabilizationTest {
    private val root = File(System.getProperty("user.dir"))

    @Test fun appIntentionallyDependsOnMediaPipeHandAdapter() {
        val buildFile = root.resolve("app/build.gradle.kts").readText()
        assertTrue(buildFile.contains("implementation(project(\":mediapipe-hand-adapter\"))"))
    }

    @Test fun mediaPipeHandAdapterDoesNotCallTemporalVerifier() {
        val adapterSources = root.resolve("mediapipe-hand-adapter/src/main/kotlin").walkTopDown().filter { it.isFile }.joinToString("\n") { it.readText() }
        assertFalse(adapterSources.contains("FindYourWeaponTemporalVerifier"))
    }

    @Test fun analyzerCoreHasNoAndroidOrMediaPipeImports() {
        val coreSources = root.resolve("karate-analyzer-core/src/main/kotlin").walkTopDown().filter { it.isFile }.joinToString("\n") { it.readText() }
        assertFalse(coreSources.contains("import android."))
        assertFalse(coreSources.contains("import com.google.mediapipe"))
    }
}
