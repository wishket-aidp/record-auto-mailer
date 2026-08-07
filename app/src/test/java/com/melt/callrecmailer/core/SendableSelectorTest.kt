package com.melt.callrecmailer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SendableSelectorTest {
    private val now = 1_000_000_000L
    private fun file(name: String, size: Long, age: Long) =
        RecordingFile("/d/$name", name, size, now - age)

    @Test fun new_completed_within_size_goes_to_toSend() {
        val f = file("a.m4a", 1000, age = 120_000)
        val r = SendableSelector.select(listOf(f), now, 20_000_000) { false }
        assertEquals(listOf(f), r.toSend)
        assertTrue(r.oversize.isEmpty())
    }

    @Test fun already_sent_is_skipped() {
        val f = file("a.m4a", 1000, age = 120_000)
        val r = SendableSelector.select(listOf(f), now, 20_000_000) { true }
        assertTrue(r.toSend.isEmpty())
    }

    @Test fun too_recent_is_skipped() {
        val f = file("a.m4a", 1000, age = 10_000) // < 60s
        val r = SendableSelector.select(listOf(f), now, 20_000_000) { false }
        assertTrue(r.toSend.isEmpty())
    }

    @Test fun oversize_goes_to_oversize_bucket() {
        val f = file("big.m4a", 30_000_000, age = 120_000)
        val r = SendableSelector.select(listOf(f), now, 20_000_000) { false }
        assertTrue(r.toSend.isEmpty())
        assertEquals(listOf(f), r.oversize)
    }
}
