package dk.lasse.karatecliprecorder.movement

import android.content.DialogInterface
import android.view.View
import android.widget.TextView
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [28])
class ActivityPlanRepairDialogsTest {
    private fun activity() = Robolectric.buildActivity(MovementDetailActivity::class.java).create().get()

    @Test fun bothSupportedChoicesAreAttachedVisibleSelectableAndReachExplicitConfirmation() {
        val activity = activity()
        ActivityPlanRepairDialogs.options.forEachIndexed { index, option ->
            val confirmed = mutableListOf<String>()
            val dialogs = ActivityPlanRepairDialogs(activity, confirmed::add)
            val choice = dialogs.showChoice()
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

            val list = assertNotNull(choice.listView)
            assertNotNull(list.parent, "The choice list must be attached to the displayed dialog")
            assertEquals(View.VISIBLE, list.visibility)
            choice.window!!.decorView.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.AT_MOST),
            )
            choice.window!!.decorView.layout(0, 0, 1080, choice.window!!.decorView.measuredHeight)
            val matchingViews = java.util.ArrayList<View>()
            choice.window!!.decorView.findViewsWithText(matchingViews, option.label, View.FIND_VIEWS_WITH_TEXT)
            assertTrue(matchingViews.filterIsInstance<TextView>().any { it.text.toString() == option.label })

            assertTrue(list.performItemClick(list.adapter.getView(index, null, list), index, list.adapter.getItemId(index)))
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
            val confirmation = assertNotNull(dialogs.confirmationDialog)
            assertNotSame(choice, confirmation)
            assertTrue(confirmation.isShowing)
            assertTrue(assertNotNull(confirmation.findViewById<TextView>(android.R.id.message)).text.contains("entire recording"))
            assertTrue(confirmed.isEmpty(), "Selecting an item must not mutate evidence before confirmation")

            confirmation.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
            assertEquals(listOf(option.planKey), confirmed)
        }
    }

    @Test fun cancellingEitherStepDoesNotInvokeTheMutationCallback() {
        val activity = activity()
        val confirmed = mutableListOf<String>()

        val firstDialogs = ActivityPlanRepairDialogs(activity, confirmed::add)
        val firstChoice = firstDialogs.showChoice()
        firstChoice.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        assertTrue(confirmed.isEmpty())

        val secondDialogs = ActivityPlanRepairDialogs(activity, confirmed::add)
        val secondChoice = secondDialogs.showChoice()
        val list = assertNotNull(secondChoice.listView)
        assertTrue(list.performItemClick(list.adapter.getView(0, null, list), 0, list.adapter.getItemId(0)))
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        val confirmation = assertNotNull(secondDialogs.confirmationDialog)
        confirmation.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        assertTrue(confirmed.isEmpty(), "Cancelling confirmation must not append events or create runs")
    }
}
