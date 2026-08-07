package com.melt.callrecmailer.mail

import com.melt.callrecmailer.core.SmtpConfig
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Properties
import javax.mail.Message
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMultipart

class MimeMessageFactoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val session = Session.getInstance(Properties())

    @Test fun builds_message_with_subject_recipient_from_and_attachment() {
        val file = tmp.newFile("call.m4a").apply { writeBytes(ByteArray(3)) }
        val cfg = SmtpConfig("h", 587, "u@x", "pw", "from@x")
        val msg = MimeMessageFactory.build(session, cfg, "to@y", "subj", "hello", file)

        assertEquals("subj", msg.subject)
        assertEquals("from@x", (msg.from[0] as InternetAddress).address)
        assertEquals("to@y", (msg.getRecipients(Message.RecipientType.TO)[0] as InternetAddress).address)

        val mp = msg.content as MimeMultipart
        assertEquals(2, mp.count)
        val hasAttachment = (0 until mp.count)
            .map { mp.getBodyPart(it) }
            .any { it.fileName == "call.m4a" }
        assertEquals(true, hasAttachment)
    }

    @Test fun accepts_korean_attachment_filename() {
        val file = tmp.newFile("통화_테스트.m4a").apply { writeBytes(ByteArray(3)) }
        val cfg = SmtpConfig("h", 587, "u@x", "pw", "from@x")
        val msg = MimeMessageFactory.build(session, cfg, "to@y", "제목", "본문", file)
        val mp = msg.content as MimeMultipart
        val names = (0 until mp.count).map { mp.getBodyPart(it).fileName }
        assertEquals(true, names.contains("통화_테스트.m4a"))
    }
}
