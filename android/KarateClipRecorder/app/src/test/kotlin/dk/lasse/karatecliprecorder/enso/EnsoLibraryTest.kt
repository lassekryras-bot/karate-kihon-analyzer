package dk.lasse.karatecliprecorder.enso

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

class EnsoLibraryTest {
    @Test fun libraryExposesAllTwentyVariantsInCanonicalOrder() {
        assertEquals((1..20).toList(), EnsoVariant.all.map(EnsoVariant::number))
        assertEquals(20, EnsoVariant.all.map(EnsoVariant::rawResourceId).toSet().size)
        assertEquals("01", EnsoVariant.ENSO_01.debugLabel)
        assertEquals("20", EnsoVariant.ENSO_20.debugLabel)
    }

    @Test fun oneInstanceKeepsOneStableSelection() {
        val instance = EnsoLibrary(Random(7)).createInstance()

        repeat(20) { assertSame(instance.variant, instance.variant) }
    }

    @Test fun newInstancesCanAvoidAnImmediateRepeat() {
        val library = EnsoLibrary(Random(11))
        var previous = library.createInstance()

        repeat(200) {
            val next = library.createInstance(previous)
            assertNotEquals(previous.variant, next.variant)
            previous = next
        }
    }

    @Test fun shuffleBagUsesEveryVariantBeforeRepeating() {
        val bag = EnsoLibrary(Random(19)).newShuffleBag()
        val firstCycle = List(20) { bag.next() }
        val secondCycle = List(20) { bag.next() }

        assertEquals(EnsoVariant.all.toSet(), firstCycle.toSet())
        assertEquals(EnsoVariant.all.toSet(), secondCycle.toSet())
        assertNotEquals(firstCycle.last(), secondCycle.first())
    }

    @Test fun blackBaseReproducesTheCanonicalFifteenGrayTones() {
        val palette = EnsoTonePalette.fromBaseColor(0xFF000000.toInt())
        val expected = EnsoTonePalette.canonicalGray.map { gray ->
            (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
        }

        assertEquals(15, palette.asList().size)
        assertEquals(expected, palette.asList())
    }

    @Test fun changingTheBaseColorRetunesEveryTone() {
        val black = EnsoTonePalette.fromBaseColor(0xFF000000.toInt()).asList()
        val red = EnsoTonePalette.fromBaseColor(0xFFBE000C.toInt()).asList()

        assertEquals(15, red.size)
        black.zip(red).forEach { (blackTone, redTone) -> assertNotEquals(blackTone, redTone) }
        assertNotEquals(red.first(), red.last())
    }
}
