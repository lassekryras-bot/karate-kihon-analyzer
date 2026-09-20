package dk.lasse.karatecliprecorder.artwork

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dk.lasse.karatecliprecorder.R
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmlpull.v1.XmlPullParser
import android.util.Xml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class FullColorSvgViewTest {

    @Test
    fun senseiHomeWelcomeSvgPathsStartWithMoveCommandAndHaveNoSpikes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val stream = context.resources.openRawResource(R.raw.sensei_home_welcome)
        val parser = Xml.newPullParser().apply { setInput(stream.reader()) }

        val pathDataList = mutableListOf<Pair<String, String>>() // (fill, d)

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name.substringAfter(':') == "path") {
                val fill = parser.getAttributeValue(null, "fill") ?: ""
                val d = parser.getAttributeValue(null, "d") ?: ""
                pathDataList.add(fill to d.trim())
            }
            parser.next()
        }

        assertTrue(pathDataList.isNotEmpty(), "sensei_home_welcome.svg should contain paths")

        for ((fill, d) in pathDataList) {
            assertTrue(
                d.startsWith("M") || d.startsWith("m"),
                "SVG path with fill '$fill' must start with 'M' or 'm' to avoid unanchored curve spikes: ${d.take(30)}",
            )
            // Path must not start with a bare number/coordinate without command
            assertFalse(
                d.first().isDigit(),
                "SVG path with fill '$fill' must not start with a digit: ${d.take(30)}",
            )
            // Path must end with Z without malformed trailing ZM
            assertTrue(
                d.endsWith("Z") || d.endsWith("z"),
                "SVG path with fill '$fill' should end cleanly with Z: ${d.takeLast(20)}",
            )
        }

        // Specifically check the white gi path (Path 7 in original)
        val whiteGiPath = pathDataList.firstOrNull { it.first.contains("248,248,248") }
        assertNotNull(whiteGiPath, "Should contain white gi path with fill rgba(248,248,248,1)")
        assertTrue(whiteGiPath.second.startsWith("M 84.87"), "White gi path must start with 'M 84.87', got: ${whiteGiPath.second.take(20)}")
    }

    @Test
    fun skillCoachSvgAlsoRendersWithoutRegression() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val stream = context.resources.openRawResource(R.raw.sensei_speaking_blank_bubble)
        val parser = Xml.newPullParser().apply { setInput(stream.reader()) }

        var pathCount = 0
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name.substringAfter(':') == "path") {
                val d = parser.getAttributeValue(null, "d") ?: ""
                assertTrue(d.trim().startsWith("M") || d.trim().startsWith("m"))
                pathCount++
            }
            parser.next()
        }
        assertTrue(pathCount > 0, "sensei_speaking_blank_bubble should contain valid paths")
    }
}

