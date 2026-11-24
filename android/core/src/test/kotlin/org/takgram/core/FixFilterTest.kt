package org.takgram.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FixFilterTest {
    @Test
    fun firstFixIsAlwaysUsed() {
        assertTrue(FixFilter().accept(0, 900.0))
    }

    @Test
    fun coarseNetworkFixIsDroppedWhileGpsIsFresh() {
        val f = FixFilter()
        assertTrue(f.accept(0, 5.0))
        assertFalse(f.accept(5_000, 800.0))
        assertTrue(f.accept(10_000, 6.0))
        assertFalse(f.accept(12_000, 0.0)) // unknown accuracy counts as very coarse
    }

    @Test
    fun coarseFixIsUsedOnceTheGoodOneIsOld() {
        val f = FixFilter(maxAgeMs = 30_000)
        assertTrue(f.accept(0, 5.0))
        assertFalse(f.accept(29_000, 800.0))
        assertTrue(f.accept(31_000, 800.0))
    }

    @Test
    fun droppedFixesDoNotKeepTheOldOneAlive() {
        val f = FixFilter(maxAgeMs = 30_000)
        assertTrue(f.accept(0, 5.0))
        assertFalse(f.accept(20_000, 800.0))
        assertTrue(f.accept(40_000, 800.0)) // 40 s since the last accepted fix, not 20 s
    }

    @Test
    fun normalGpsWobbleIsKept() {
        val f = FixFilter()
        assertTrue(f.accept(0, 4.0))
        assertTrue(f.accept(5_000, 12.0))
        assertTrue(f.accept(10_000, 20.0))
        assertTrue(f.accept(15_000, 38.0)) // within twice the previous accuracy
    }
}
