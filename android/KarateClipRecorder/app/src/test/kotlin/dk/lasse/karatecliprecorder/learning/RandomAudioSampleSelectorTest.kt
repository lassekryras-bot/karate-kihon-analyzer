package dk.lasse.karatecliprecorder.learning

import kotlin.test.Test
import kotlin.test.assertEquals

class RandomAudioSampleSelectorTest {
    @Test fun choosesAgainForEveryPlaybackRequest() {
        var nextIndex = 0
        val selector = RandomAudioSampleSelector(
            resourceIds = intArrayOf(101, 202),
            pickIndex = { size -> nextIndex++.mod(size) },
        )

        assertEquals(101, selector.nextResourceId())
        assertEquals(202, selector.nextResourceId())
        assertEquals(101, selector.nextResourceId())
    }
}
