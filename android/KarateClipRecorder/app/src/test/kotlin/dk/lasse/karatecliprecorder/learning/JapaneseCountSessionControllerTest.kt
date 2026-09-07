package dk.lasse.karatecliprecorder.learning

import dk.lasse.karatecliprecorder.orders.TrainingOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JapaneseCountSessionControllerTest {
    @Test fun lessonUsesCanonicalNumericStringsAndKaratePronunciations() {
        assertEquals(JapaneseCountSequence.expected, JapaneseCountLesson.items.map { it.number })
        assertEquals(
            listOf("ich", "ni", "san", "shi", "go", "rok", "shich", "hach", "kyu", "ju"),
            JapaneseCountLesson.items.map { it.japanese },
        )
        assertEquals(
            listOf("ichi", "ni", "san", "shi", "go", "roku", "shichi", "hachi", "kyu", "ju"),
            JapaneseCountLesson.items.map { it.standardJapanese },
        )
        assertEquals(
            listOf("一", "二", "三", "四", "五", "六", "七", "八", "九", "十"),
            JapaneseCountLesson.items.map { it.displayKanji },
        )
        assertEquals(TrainingOrder.COUNT_1, JapaneseCountLesson.items.first().order)
        assertEquals("order_kyu", JapaneseCountLesson.items[8].order.soundResourceName)
        assertEquals(TrainingOrder.COUNT_10, JapaneseCountLesson.items.last().order)
    }

    @Test fun startShowsFirstCount() {
        val states = mutableListOf<JapaneseCountLevel1State>()
        val controller = JapaneseCountLevel1Controller(states::add)

        controller.start()

        assertTrue(states.last().isActive)
        assertEquals(0, states.last().itemIndex)
        assertEquals("1", states.last().item?.number)
        assertEquals("ich", states.last().item?.japanese)
    }

    @Test fun nextMovesThroughCountsAndCompletesAfterTen() {
        val states = mutableListOf<JapaneseCountLevel1State>()
        val controller = JapaneseCountLevel1Controller(states::add)

        controller.start()
        repeat(9) { controller.next() }

        assertEquals("10", states.last().item?.number)
        assertEquals("ju", states.last().item?.japanese)

        controller.next()

        assertFalse(states.last().isActive)
        assertTrue(states.last().isComplete)
    }

    @Test fun backStopsAtFirstCount() {
        val states = mutableListOf<JapaneseCountLevel1State>()
        val controller = JapaneseCountLevel1Controller(states::add)

        controller.start()
        controller.next()
        controller.back()
        controller.back()

        assertTrue(states.last().isActive)
        assertEquals(0, states.last().itemIndex)
        assertEquals("1", states.last().item?.number)
        assertEquals("ich", states.last().item?.japanese)
    }

    @Test fun cancelReturnsToInactiveState() {
        val states = mutableListOf<JapaneseCountLevel1State>()
        val controller = JapaneseCountLevel1Controller(states::add)

        controller.start()
        controller.cancel()

        assertFalse(states.last().isActive)
        assertFalse(states.last().isComplete)
    }
}
