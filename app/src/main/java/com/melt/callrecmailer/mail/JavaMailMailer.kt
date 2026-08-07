package com.melt.callrecmailer.mail

import com.melt.callrecmailer.core.Mailer
import com.melt.callrecmailer.core.SmtpConfig
import java.io.File
import java.util.Properties
import javax.mail.Authenticator
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport

class JavaMailMailer : Mailer {
    override fun send(
        config: SmtpConfig,
        to: String,
        subject: String,
        body: String,
        attachment: File,
    ): Result<Unit> = runCatching {
        val props = Properties().apply {
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.host", config.host)
            put("mail.smtp.port", config.port.toString())
        }
        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication() =
                PasswordAuthentication(config.username, config.appPassword)
        })
        val message = MimeMessageFactory.build(session, config, to, subject, body, attachment)
        Transport.send(message)
    }
}
