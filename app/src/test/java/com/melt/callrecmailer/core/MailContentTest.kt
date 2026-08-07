package com.melt.callrecmailer.core

import org.junit.Assert.assertTrue
import org.junit.Test

class MailContentTest {
    private val f = RecordingFile("/d/call.m4a", "call.m4a", 2048L, 1700000000000L)

    @Test fun subject_contains_prefix_and_filename() {
        assertTrue(MailContent.subject(f).contains("call.m4a"))
        assertTrue(MailContent.subject(f).startsWith("[통화녹음]"))
    }

    @Test fun body_contains_filename_and_size() {
        val body = MailContent.body(f)
        assertTrue(body.contains("call.m4a"))
        assertTrue(body.contains("2")) // 2048 bytes -> 2 KB
    }
}
