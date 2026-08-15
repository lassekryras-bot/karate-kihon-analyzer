package dk.lasse.karatecliprecorder.learning

import kotlin.test.Test
import kotlin.test.assertEquals

class FindYourWeaponCoachTextGateTest {
    @Test fun firstTextDisplaysImmediately() {
        val gate = FindYourWeaponCoachTextGate(minimumDisplayMs = 2_000)

        assertEquals("Show hand", gate.displayText("Show hand", nowMs = 100))
    }

    @Test fun newTextWaitsUntilCurrentLineHasBeenReadableLongEnough() {
        val gate = FindYourWeaponCoachTextGate(minimumDisplayMs = 2_000)

        assertEquals("Show hand", gate.displayText("Show hand", nowMs = 100))
        assertEquals("Show hand", gate.displayText("Move thumb", nowMs = 1_900))
        assertEquals("Move thumb", gate.displayText("Move thumb", nowMs = 2_100))
    }

    @Test fun forceDisplaysNewTextImmediately() {
        val gate = FindYourWeaponCoachTextGate(minimumDisplayMs = 2_000)

        assertEquals("Show hand", gate.displayText("Show hand", nowMs = 100))
        assertEquals("Next step", gate.displayText("Next step", nowMs = 200, force = true))
    }

    @Test fun resetClearsThePauseWindow() {
        val gate = FindYourWeaponCoachTextGate(minimumDisplayMs = 2_000)

        assertEquals("Show hand", gate.displayText("Show hand", nowMs = 100))
        gate.reset()

        assertEquals("Move thumb", gate.displayText("Move thumb", nowMs = 200))
    }
}
