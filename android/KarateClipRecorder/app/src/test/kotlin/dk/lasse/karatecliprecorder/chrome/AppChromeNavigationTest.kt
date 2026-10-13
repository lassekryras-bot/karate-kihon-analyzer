package dk.lasse.karatecliprecorder.chrome

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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

    @Test fun navigationInsetsRemainSafeWhenStateIsAppliedRepeatedly() {
        val nav = AppBottomNavigationView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val navigationBarBottom = 24.dp()
        ViewCompat.dispatchApplyWindowInsets(nav, navigationInsets(navigationBarBottom))

        val expectedBottomPadding = navigationBarBottom
        val expectedHeight = AppBottomNavigationView.BASE_HEIGHT_DP.dp() + navigationBarBottom
        listOf(
            listOf(AppDestination.HOME, AppDestination.LEARNING),
            listOf(AppDestination.HOME),
            listOf(AppDestination.HOME, AppDestination.TRAIN),
        ).forEach { destinations ->
            nav.setNavigationState(AppNavigationState(destinations, destinations.first()))

            assertEquals(expectedBottomPadding, nav.paddingBottom)
            assertEquals(expectedHeight, nav.layoutParams.height)
        }
    }

    @Test fun labelsRemainInsideClickableDestinationRoots() {
        val nav = laidOutNavigation(AppDestination.entries)
        val selected = mutableListOf<AppDestination>()
        nav.onDestinationSelected = { selected += it }

        for (index in 0 until nav.childCount) {
            val root = nav.getChildAt(index) as LinearLayout
            val label = root.getChildAt(1)
            assertTrue(label.left >= root.paddingLeft)
            assertTrue(label.top >= root.paddingTop)
            assertTrue(label.right <= root.width - root.paddingRight)
            assertTrue(label.bottom <= root.height - root.paddingBottom)
            assertTrue(root.performClick())
        }

        assertEquals(AppDestination.entries.toList(), selected)
    }

    @Test fun itemRootsStayAboveReservedNavigationInsetAfterLayout() {
        val nav = AppBottomNavigationView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val navigationBarBottom = 24.dp()
        ViewCompat.dispatchApplyWindowInsets(nav, navigationInsets(navigationBarBottom))
        nav.setNavigationState(AppNavigationState(AppDestination.entries, AppDestination.HOME))
        nav.measure(
            View.MeasureSpec.makeMeasureSpec(1000.dp(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(nav.layoutParams.height, View.MeasureSpec.EXACTLY),
        )
        nav.layout(0, 0, nav.measuredWidth, nav.measuredHeight)

        val reservedNavigationTop = nav.height - nav.paddingBottom
        val density = context.resources.displayMetrics.density
        val min48px = (48 * density).toInt()
        for (index in 0 until nav.childCount) {
            val root = nav.getChildAt(index)
            assertTrue(root.width >= min48px, "Item width should be >= 48dp")
            assertTrue(root.height >= min48px, "Item height should be >= 48dp")
            assertTrue(root.bottom <= reservedNavigationTop, "Item must not extend into navigation inset")
        }
    }

    private fun laidOutNavigation(destinations: List<AppDestination>): AppBottomNavigationView {
        val nav = AppBottomNavigationView(context)
        nav.setNavigationState(AppNavigationState(destinations, destinations.first()))
        nav.measure(
            View.MeasureSpec.makeMeasureSpec(1000.dp(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(AppBottomNavigationView.BASE_HEIGHT_DP.dp(), View.MeasureSpec.EXACTLY),
        )
        nav.layout(0, 0, nav.measuredWidth, nav.measuredHeight)
        return nav
    }

    private fun navigationInsets(bottom: Int): WindowInsetsCompat =
        WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, bottom))
            .build()

    private fun Int.dp(): Int = (this * context.resources.displayMetrics.density).toInt()
}
