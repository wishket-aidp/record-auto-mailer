package com.melt.callrecmailer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CallRecordingProcessorTest {
    @get:Rule val tmp = TemporaryFolder()
    private val now = 10_000_000_000L
    private val clock = Clock { now }

    private fun settings(dir: File) = Settings(
        smtp = SmtpConfig("h", 587, "u@x", "pw", "u@x"),
        toAddress = "to@y",
        watchDir = dir.absolutePath,
        extensions = setOf("m4a"),
        maxAttachmentBytes = 20L * 1024 * 1024,
    )

    private fun oldFile(name: String, bytes: Int): File {
        val f = tmp.newFile(name); f.writeBytes(ByteArray(bytes))
        f.setLastModified(now - 120_000)
        return f
    }

    @Test fun sends_new_file_and_marks_sent() {
        oldFile("a.m4a", 10)
        val mailer = FakeMailer(); val repo = InMemorySentRepo()
        val p = CallRecordingProcessor(FolderScanner(), mailer, repo, clock)
        val r = p.processOnce(settings(tmp.root))
        assertEquals(1, r.sent)
        assertEquals("to@y", mailer.sent.single().to)
        assertTrue(repo.keys.isNotEmpty())
    }

    @Test fun does_not_resend_already_sent() {
        oldFile("a.m4a", 10)
        val mailer = FakeMailer(); val repo = InMemorySentRepo()
        val p = CallRecordingProcessor(FolderScanner(), mailer, repo, clock)
        p.processOnce(settings(tmp.root))
        val r2 = p.processOnce(settings(tmp.root))
        assertEquals(0, r2.sent)
        assertEquals(1, mailer.sent.size)
    }

    @Test fun failure_does_not_mark_and_requests_retry() {
        oldFile("a.m4a", 10)
        val mailer = FakeMailer(succeed = false); val repo = InMemorySentRepo()
        val p = CallRecordingProcessor(FolderScanner(), mailer, repo, clock)
        val r = p.processOnce(settings(tmp.root))
        assertEquals(0, r.sent)
        assertEquals(1, r.failed)
        assertTrue(r.shouldRetry)
        assertFalse(repo.keys.iterator().hasNext())
    }

    @Test fun oversize_is_skipped_and_counted() {
        val f = tmp.newFile("big.m4a"); f.writeBytes(ByteArray(1024))
        f.setLastModified(now - 120_000)
        val mailer = FakeMailer(); val repo = InMemorySentRepo()
        val small = settings(tmp.root).copy(maxAttachmentBytes = 100)
        val p = CallRecordingProcessor(FolderScanner(), mailer, repo, clock)
        val r = p.processOnce(small)
        assertEquals(0, r.sent)
        assertEquals(1, r.oversizeSkipped)
        assertTrue(mailer.sent.isEmpty())
    }
}
