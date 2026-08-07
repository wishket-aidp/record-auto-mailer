package com.melt.callrecmailer.core

import java.io.File

interface Mailer {
    fun send(
        config: SmtpConfig,
        to: String,
        subject: String,
        body: String,
        attachment: File,
    ): Result<Unit>
}
