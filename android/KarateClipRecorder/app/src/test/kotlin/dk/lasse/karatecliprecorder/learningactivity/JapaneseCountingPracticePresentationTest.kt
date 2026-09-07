package dk.lasse.karatecliprecorder.learningactivity

import dk.lasse.karatecliprecorder.learning.JapaneseCountLevel1Controller
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JapaneseCountingPracticePresentationTest {
    @Test fun openingPracticeIsReadyWithoutStartingLevelOne() {
        val controllerStates = mutableListOf<Any>()
        JapaneseCountLevel1Controller(controllerStates::add)

        val presentation = JapaneseCountingPracticePresentation.ready()

        assertEquals(ActivityShellState.READY, presentation.shellState)
        assertEquals("1 / 2", presentation.pathPosition)
        assertNull(presentation.item)
        assertTrue(controllerStates.isEmpty())
        assertFalse(presentation.cameraRequired)
        assertFalse(presentation.microphoneRequired)
    }

    @Test fun startMapsToFirstActiveItemWithPreviousDisabled() {
        var presentation = JapaneseCountingPracticePresentation.ready()
        val controller = JapaneseCountLevel1Controller {
            presentation = JapaneseCountingPracticePresentation.fromLevel1(it)
        }

        controller.start()

        assertEquals(ActivityShellState.ACTIVE, presentation.shellState)
        assertEquals("1", presentation.item?.number)
        assertEquals("一", presentation.item?.displayKanji)
        assertFalse(presentation.previousEnabled)
        assertEquals("Next  →", presentation.nextLabel)
        assertEquals("1 / 2", presentation.pathPosition)
    }

    @Test fun tenthItemFinishesIntoCompleteStateAndCanRestart() {
        var presentation = JapaneseCountingPracticePresentation.ready()
        val controller = JapaneseCountLevel1Controller {
            presentation = JapaneseCountingPracticePresentation.fromLevel1(it)
        }

        controller.start()
        repeat(9) { controller.next() }

        assertEquals(ActivityShellState.ACTIVE, presentation.shellState)
        assertEquals("10", presentation.item?.number)
        assertEquals("Finish  →", presentation.nextLabel)
        assertTrue(presentation.previousEnabled)
        assertEquals("1 / 2", presentation.pathPosition)

        controller.next()
        assertEquals(ActivityShellState.COMPLETE, presentation.shellState)
        assertEquals("1 / 2", presentation.pathPosition)

        controller.start()
        assertEquals(ActivityShellState.ACTIVE, presentation.shellState)
        assertEquals("1", presentation.item?.number)
    }
}
