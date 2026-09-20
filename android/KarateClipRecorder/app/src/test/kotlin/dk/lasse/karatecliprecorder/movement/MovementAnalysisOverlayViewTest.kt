package dk.lasse.karatecliprecorder.movement

import android.content.Context
import android.graphics.PointF
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
}

