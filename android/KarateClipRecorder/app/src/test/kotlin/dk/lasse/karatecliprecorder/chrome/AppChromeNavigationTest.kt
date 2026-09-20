package dk.lasse.karatecliprecorder.chrome

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import dk.lasse.karatecliprecorder.AppBottomNavigationView
import dk.lasse.karatecliprecorder.AppDestination
import dk.lasse.karatecliprecorder.AppNavigationState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppChromeNavigationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun acceptsAndRenders1To5Destinations() {
        val nav = AppBottomNavigationView(context)

        (1..5).forEach { count ->
            val destinations = AppDestination.entries.take(count)
            nav.setNavigationState(AppNavigationState(
                visibleDestinations = destinations,
                selectedDestination = destinations.first(),
            ))
            assertEquals(count, nav.childCount)
        }

        assertFailsWith<IllegalArgumentException> {
            AppNavigationState(
                visibleDestinations = emptyList(),
                selectedDestination = AppDestination.HOME,
            )
        }
    }

    @Test fun singleDestinationIsCenteredHorizontally() {
        val nav = AppBottomNavigationView(context)
        nav.setNavigationState(AppNavigationState(
            visibleDestinations = listOf(AppDestination.HOME),
            selectedDestination = AppDestination.HOME,
        ))

        assertEquals(1, nav.childCount)
        assertEquals(Gravity.CENTER, nav.gravity)

        val child = nav.getChildAt(0)
        val params = child.layoutParams as LinearLayout.LayoutParams
        assertEquals(LinearLayout.LayoutParams.WRAP_CONTENT, params.width)
    }

    @Test fun multipleDestinationsDistributeWidthEvenly() {
        val nav = AppBottomNavigationView(context)
        nav.setNavigationState(AppNavigationState(
            visibleDestinations = listOf(AppDestination.HOME, AppDestination.LEARNING),
            selectedDestination = AppDestination.HOME,
        ))

        assertEquals(2, nav.childCount)
        assertEquals(Gravity.CENTER, nav.gravity)

        for (i in 0 until nav.childCount) {
            val child = nav.getChildAt(i)
            val params = child.layoutParams as LinearLayout.LayoutParams
            assertEquals(0, params.width)
            assertEquals(1f, params.weight)
        }
    }

    @Test fun canonicalOrderingPreservedRegardlessOfInputOrder() {
        val nav = AppBottomNavigationView(context)
        // Pass in shuffled order
        nav.setNavigationState(AppNavigationState(
            visibleDestinations = listOf(AppDestination.SETTINGS, AppDestination.HOME, AppDestination.LEARNING),
            selectedDestination = AppDestination.HOME,
        ))

        assertEquals(3, nav.childCount)
        assertEquals(AppDestination.HOME.label(context), nav.getChildAt(0).contentDescription)
        assertEquals(AppDestination.LEARNING.label(context), nav.getChildAt(1).contentDescription)
        assertEquals(AppDestination.SETTINGS.label(context), nav.getChildAt(2).contentDescription)
    }

    @Test fun touchTargetsEnforceMinimum48Dp() {
        val nav = AppBottomNavigationView(context)
        nav.setNavigationState(AppNavigationState(
            visibleDestinations = listOf(AppDestination.HOME, AppDestination.LEARNING),
            selectedDestination = AppDestination.HOME,
        ))

        val density = context.resources.displayMetrics.density
        val min48px = (48 * density).toInt()

        for (i in 0 until nav.childCount) {
            val child = nav.getChildAt(i)
            assertTrue(child.minimumWidth >= min48px, "Item width should be >= 48dp")
            assertTrue(child.minimumHeight >= min48px, "Item height should be >= 48dp")
            assertTrue(child.isClickable)
            assertTrue(child.isFocusable)
        }
    }

    @Test fun safeFallbackToHomeWhenSelectedNotVisible() {
        val state = AppNavigationState(
            visibleDestinations = listOf(AppDestination.HOME, AppDestination.LEARNING),
            selectedDestination = AppDestination.SETTINGS,
        )
        assertEquals(AppDestination.HOME, state.resolvedSelectedDestination())

        val stateWithoutHome = AppNavigationState(
            visibleDestinations = listOf(AppDestination.LEARNING, AppDestination.TRAIN),
            selectedDestination = AppDestination.SETTINGS,
        )
        assertEquals(AppDestination.LEARNING, stateWithoutHome.resolvedSelectedDestination())
    }

    @Test fun selectedStateIsSeparateFromAttentionAndNewlyUnlocked() {
        val nav = AppBottomNavigationView(context)
        nav.setNavigationState(AppNavigationState(
            visibleDestinations = listOf(AppDestination.HOME, AppDestination.LEARNING),
            selectedDestination = AppDestination.HOME,
            newlyUnlockedDestinations = setOf(AppDestination.LEARNING),
            attentionDestination = AppDestination.LEARNING,
        ))

        val homeView = nav.getChildAt(0)
        val learningView = nav.getChildAt(1)

        assertTrue(homeView.isSelected)
        assertFalse(learningView.isSelected)
    }

    @Test fun destinationSelectionCallbackFires() {
        val nav = AppBottomNavigationView(context)
        var selectedDest: AppDestination? = null
        nav.onDestinationSelected = { selectedDest = it }

        nav.setNavigationState(AppNavigationState(
            visibleDestinations = listOf(AppDestination.HOME, AppDestination.LEARNING),
            selectedDestination = AppDestination.HOME,
        ))

        nav.getChildAt(1).performClick()
        assertEquals(AppDestination.LEARNING, selectedDest)
    }
}
