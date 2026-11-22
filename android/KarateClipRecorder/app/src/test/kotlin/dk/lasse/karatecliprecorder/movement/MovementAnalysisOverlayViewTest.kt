package dk.lasse.karatecliprecorder.movement

import android.content.Context
import android.graphics.PointF
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MovementAnalysisOverlayViewTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun overlayDefaultsToSingleClosestRay() {
        val state = MovementTimelineState(0L, 1_000_000L)
        val overlay = MovementAnalysisOverlayView(context, state)

        assertFalse(overlay.showAllRays, "showAllRays must default to false to prevent visual clutter")

        val rays = listOf(
            OverlayRay("JODAN", PointF(0.5f, 0.3f), PointF(0.2f, 0.2f), isClosest = false),
            OverlayRay("CHUDAN", PointF(0.5f, 0.3f), PointF(0.15f, 0.48f), isClosest = true),
            OverlayRay("GEDAN", PointF(0.5f, 0.3f), PointF(0.25f, 0.65f), isClosest = false),
        )

        overlay.overlayDefinition = MovementOverlayDefinition(
            rays = rays,
            arm = null,
            canonicalImpactUs = 500_000L,
        )

        assertEquals(3, overlay.overlayDefinition?.rays?.size)
        // With showAllRays = false, the filtering logic only renders the single closest ray
        val renderedRays = if (overlay.showAllRays) overlay.overlayDefinition!!.rays else overlay.overlayDefinition!!.rays.filter { it.isClosest }
        assertEquals(1, renderedRays.size)
        assertEquals("CHUDAN", renderedRays.first().targetType)
        assertTrue(renderedRays.first().isClosest)
    }

    @Test
    fun overlayCanToggleShowAllRaysForDebug() {
        val state = MovementTimelineState(0L, 1_000_000L)
        val overlay = MovementAnalysisOverlayView(context, state)

        overlay.showAllRays = true
        assertTrue(overlay.showAllRays)

        val rays = listOf(
            OverlayRay("JODAN", PointF(0.5f, 0.3f), PointF(0.2f, 0.2f), isClosest = false),
            OverlayRay("CHUDAN", PointF(0.5f, 0.3f), PointF(0.15f, 0.48f), isClosest = true),
            OverlayRay("GEDAN", PointF(0.5f, 0.3f), PointF(0.25f, 0.65f), isClosest = false),
        )
        overlay.overlayDefinition = MovementOverlayDefinition(rays = rays, arm = null, canonicalImpactUs = 500_000L)

        val renderedRays = if (overlay.showAllRays) overlay.overlayDefinition!!.rays else overlay.overlayDefinition!!.rays.filter { it.isClosest }
        assertEquals(3, renderedRays.size)
    }

    @Test
    fun horizontalEvidenceRendersAllGuidesOnlyAtExactRetainedTimestamp() {
        val state = MovementTimelineState(0L, 2_000_000L)
        val overlay = MovementAnalysisOverlayView(context, state)
        val rays = listOf(
            OverlayRay("JODAN", PointF(.5f, .2f), PointF(0f, .2f), false),
            OverlayRay("CHUDAN", PointF(.5f, .5f), PointF(0f, .5f), true),
            OverlayRay("GEDAN", PointF(.5f, .7f), PointF(0f, .7f), false),
        )
        overlay.overlayDefinition = MovementOverlayDefinition(rays = rays, fist = PointF(.2f, .4f),
            exactFrameOnly = true, canonicalImpactUs = 1_834_000L)

        assertFalse(overlay.shouldRenderAt(1_833_000L))
        assertTrue(overlay.shouldRenderAt(1_834_000L))
        assertFalse(overlay.shouldRenderAt(1_835_000L))
        assertEquals(3, overlay.raysForDisplay().size)
    }

    @Test
    fun mirroredRayLabelsStayInsideDisplayedImageAndZeroAngleIsNeutral() {
        val overlay = MovementAnalysisOverlayView(context, MovementTimelineState(0L, 2_000_000L))
        val imageBounds = RectF(20f, 30f, 420f, 330f)

        val imageLeftActual = overlay.boundedLabelLayout("Actual", PointF(imageBounds.left, 40f), -100f, imageBounds)
        val imageRightActual = overlay.boundedLabelLayout("Actual", PointF(imageBounds.right, 320f), 100f, imageBounds)
        val imageLeftOptimal = overlay.boundedLabelLayout("Optimal", PointF(imageBounds.left, 160f), 15f, imageBounds)
        val imageRightOptimal = overlay.boundedLabelLayout("Optimal", PointF(imageBounds.right, 160f), 15f, imageBounds)

        listOf(imageLeftActual, imageRightActual, imageLeftOptimal, imageRightOptimal).forEach { label ->
            assertTrue(label.bounds.left >= imageBounds.left)
            assertTrue(label.bounds.top >= imageBounds.top)
            assertTrue(label.bounds.right <= imageBounds.right)
            assertTrue(label.bounds.bottom <= imageBounds.bottom)
        }
        assertEquals("0.0°", overlay.angularErrorLabel(0.0))
        assertEquals("0.0°", overlay.angularErrorLabel(-0.01))
        assertEquals("+5.8° high", overlay.angularErrorLabel(5.76))
        assertEquals("-5.8° low", overlay.angularErrorLabel(-5.76))
    }
}
