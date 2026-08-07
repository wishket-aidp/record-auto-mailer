package com.melt.callrecmailer.core

import java.io.File

class FakeMailer(var succeed: Boolean = true) : Mailer {
    data class Sent(val to: String, val subject: String, val attachmentName: String)

    val sent = mutableListOf<Sent>()

    override fun send(
        config: SmtpConfig,
        to: String,
        subject: String,
        body: String,
        attachment: File,
    ): Result<Unit> {
        return if (succeed) {
            sent.add(Sent(to, subject, attachment.name))
            Result.success(Unit)
        } else {
            Result.failure(RuntimeException("smtp down"))
        }
    }
}

class InMemorySentRepo : SentKeyRepository {
    val keys = mutableSetOf<String>()
    override fun isSent(key: String) = keys.contains(key)
    override fun markSent(key: String) {
        keys.add(key)
    }
}
