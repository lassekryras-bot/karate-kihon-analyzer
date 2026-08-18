package dk.lasse.karatecliprecorder.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LauncherIconArchitectureTest {
    private val appRoot = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { it.resolve("settings.gradle.kts").isFile && it.resolve("app/build.gradle.kts").isFile }
    private val resources = appRoot.resolve("app/src/main/res")

    @Test fun manifestUsesAdaptiveRegularAndRoundLauncherIcons() {
        val manifest = appRoot.resolve("app/src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:icon=\"@mipmap/ic_launcher\""))
        assertTrue(manifest.contains("android:roundIcon=\"@mipmap/ic_launcher_round\""))

        listOf(
            "mipmap-anydpi-v26/ic_launcher.xml",
            "mipmap-anydpi-v26/ic_launcher_round.xml",
            "mipmap-anydpi-v33/ic_launcher.xml",
            "mipmap-anydpi-v33/ic_launcher_round.xml",
        ).forEach { assertTrue(resources.resolve(it).isFile, it) }
    }

    @Test fun adaptiveIconPreservesSuppliedBrandColorsPaddingAndMonochrome() {
        val colors = resources.resolve("values/colors.xml").readText()
        val foreground = resources.resolve("drawable/ic_launcher_foreground.xml").readText()
        val themed = resources.resolve("mipmap-anydpi-v33/ic_launcher.xml").readText()

        assertTrue(colors.contains("<color name=\"launcher_background\">#F5EFE4</color>"))
        assertTrue(foreground.contains("android:src=\"@drawable/app_logo_adaptive_foreground\""))
        assertTrue(foreground.contains("android:gravity=\"fill\""))
        assertTrue(foreground.contains("android:left=\"4dp\""))
        assertTrue(foreground.contains("android:right=\"4dp\""))
        assertTrue(foreground.contains("android:top=\"4dp\""))
        assertTrue(foreground.contains("android:bottom=\"4dp\""))
        assertTrue(resources.resolve("drawable-nodpi/app_logo_adaptive_foreground.png").isFile)
        assertTrue(themed.contains("<monochrome android:drawable=\"@drawable/ic_launcher_foreground\""))
    }

    @Test fun adaptiveSourceUsesTheUntouchedCanonicalLogoPath() {
        val adaptive = appRoot.resolve("artwork/app-logo/app_logo_adaptive_foreground.svg").readText()
        val source = appRoot.resolve("artwork/app-logo/app_logo_source_original.svg").readText()
        val adaptivePath = requireNotNull(Regex("""<path d="([^"]+)"""").find(adaptive)).groupValues[1]
        val sourcePath = requireNotNull(Regex("""<path d="([^"]+)"""").find(source)).groupValues[1]

        assertEquals(sourcePath, adaptivePath)
        assertTrue(adaptive.contains("translate(174.9913 162.0000) scale(1.52838428)"))
        assertTrue(adaptive.contains("fill=\"#0E1C19\""))
    }
}
