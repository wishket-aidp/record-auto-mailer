package com.melt.callrecmailer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RecordingKeyTest {
    @Test fun key_combines_name_size_and_lastModified() {
        val f = RecordingFile("/x/a.m4a", "a.m4a", 100L, 1700000000000L)
        assertEquals("a.m4a:100:1700000000000", RecordingKey.of(f))
    }

    @Test fun different_size_yields_different_key() {
        val a = RecordingFile("/x/a.m4a", "a.m4a", 100L, 10L)
        val b = RecordingFile("/x/a.m4a", "a.m4a", 200L, 10L)
        assertNotEquals(RecordingKey.of(a), RecordingKey.of(b))
    }
}
