package com.example.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InvalidSeqTrackerTest {
    @Test
    fun firstValueDoesNotClear() {
        assertFalse(InvalidSeqTracker().update(42, 0))
    }

    @Test
    fun clearsOnceWhenSeqAdvances() {
        val t = InvalidSeqTracker()
        t.update(5, 0)
        assertFalse(t.update(5, 20))
        assertTrue(t.update(6, 40))
        assertFalse(t.update(6, 60))
    }

    @Test
    fun severalMissedChangesClearOnce() {
        val t = InvalidSeqTracker()
        t.update(5, 0)
        assertTrue(t.update(9, 5000))
        assertFalse(t.update(9, 5020))
    }

    @Test
    fun wrapAroundCountsAsAdvance() {
        val t = InvalidSeqTracker()
        t.update(255, 0)
        assertTrue(t.update(0, 20))
    }

    @Test
    fun lateOldReplyDoesNotClear() {
        val t = InvalidSeqTracker()
        t.update(5, 0)
        assertTrue(t.update(6, 20))
        // 別の経路の古い返事と新しい返事が混ざって届く
        val clears = listOf(5, 6, 5, 6, 5, 6).withIndex().count { (i, seq) -> t.update(seq, 40L + i * 20) }
        assertEquals(0, clears)
    }

    @Test
    fun oldBurstShorterThanResetIsIgnored() {
        val t = InvalidSeqTracker()
        t.update(6, 0)
        for (ms in 0L..900L step 20) assertFalse(t.update(5, 100 + ms))
        assertFalse(t.update(6, 1020))
        assertTrue(t.update(7, 1040))
    }

    @Test
    fun restartOfConPyRecountsWithoutClearing() {
        val t = InvalidSeqTracker()
        t.update(5, 0)
        // con.py の再起動で 0 に戻った（5 から見て後ろ）
        for (ms in 0L..1000L step 20) assertFalse(t.update(0, 100 + ms))
        // 次の invalid_target は 0 から進む
        assertTrue(t.update(1, 1200))
    }
}
