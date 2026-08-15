package dk.lasse.karatecliprecorder.learning

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FindYourWeaponSessionControllerTest {
    @Test fun nextFromFinalStepCompletesTutorial() {
        val states = mutableListOf<FindYourWeaponState>()
        val controller = FindYourWeaponSessionController(states::add)

        controller.start()
        repeat(FindYourWeaponStep.entries.lastIndex) {
            controller.next()
        }

        assertEquals(FindYourWeaponStep.FRONT_TWO_KNUCKLES, controller.state.step)
        assertTrue(controller.state.isActive)

        controller.next()

        assertTrue(controller.state.isComplete)
        assertFalse(controller.state.isActive)
        assertEquals(null, controller.state.step)
    }
}
