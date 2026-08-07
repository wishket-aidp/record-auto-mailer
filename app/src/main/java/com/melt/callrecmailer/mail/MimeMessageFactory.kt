package com.melt.callrecmailer.mail

import com.melt.callrecmailer.core.SmtpConfig
import java.io.File
import javax.activation.DataHandler
import javax.activation.FileDataSource
import javax.mail.Message
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

object MimeMessageFactory {
    fun build(
        session: Session,
        config: SmtpConfig,
        to: String,
        subject: String,
        body: String,
        attachment: File,
    ): MimeMessage {
        val message = MimeMessage(session)
        message.setFrom(InternetAddress(config.fromAddress))
        message.setRecipient(Message.RecipientType.TO, InternetAddress(to))
        message.setSubject(subject, "UTF-8")

        val textPart = MimeBodyPart().apply { setText(body, "UTF-8") }
        val filePart = MimeBodyPart().apply {
            dataHandler = DataHandler(FileDataSource(attachment))
            fileName = attachment.name
        }
        message.setContent(MimeMultipart().apply {
            addBodyPart(textPart)
            addBodyPart(filePart)
        })
        message.saveChanges()
        return message
    }
}
