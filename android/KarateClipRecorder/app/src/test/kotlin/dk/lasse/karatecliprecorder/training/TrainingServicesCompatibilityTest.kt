package dk.lasse.karatecliprecorder.training

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrainingServicesCompatibilityTest {
    @Test
    fun `thermal status is unavailable before API 29 without invoking platform read`() {
        var invoked = false
        val status = prototypeThermalStatus(26) {
            invoked = true
            3
        }

        assertEquals(-1, status)
        assertFalse(invoked)
    }

    @Test
    fun `thermal status is read on API 29 and newer`() {
        var invoked = false
        val status = prototypeThermalStatus(29) {
            invoked = true
            3
        }

        assertEquals(3, status)
        assertTrue(invoked)
    }
}
