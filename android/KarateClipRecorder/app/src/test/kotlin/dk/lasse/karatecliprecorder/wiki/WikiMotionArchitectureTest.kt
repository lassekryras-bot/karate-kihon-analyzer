package dk.lasse.karatecliprecorder.wiki

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WikiMotionArchitectureTest {
    private val appRoot = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { it.resolve("settings.gradle.kts").isFile && it.resolve("app/build.gradle.kts").isFile }
    private val wiki = appRoot.resolve("app/src/main/java/dk/lasse/karatecliprecorder/wiki")

    @Test fun wikiAnimationsShareFamiliarPlayerControls() {
        val controls = wiki.resolve("WikiMotionControls.kt").readText()
        val catalogueView = wiki.resolve("MeasurementWikiView.kt").readText()
        val hikiteView = wiki.resolve("HikiteWikiView.kt").readText()

        listOf("Jump to…", "Start", "Finish", "×1", "×0.75", "×0.50", "×0.25", "×0.10")
            .forEach { assertTrue(controls.contains(it)) }
        listOf("ic_player_play", "ic_player_pause", "ic_player_replay")
            .forEach { assertTrue(controls.contains(it)) }
        assertTrue(catalogueView.contains("WikiMotionControls("))
        assertTrue(hikiteView.contains("WikiMotionControls("))
    }

    @Test fun hikiteUsesLiveMetricListAndStableTorsoCenteredHead() {
        val view = wiki.resolve("HikiteWikiView.kt").readText()
        assertTrue(view.contains("WikiMetricList("))
        assertTrue(view.contains("Wrist to shoulder line"))
        assertTrue(view.contains("Forearm to torso"))
        assertTrue(view.contains("Elbow speed"))
        assertTrue(view.contains("headRadius"))
        assertTrue(view.contains("shoulderMid.x + headLevel * (hipMid.x - shoulderMid.x)"))
        assertFalse(view.contains("r.getDouble(\"radius\").toFloat()*z"))
    }
}
