package com.melt.callrecmailer.core

import java.io.File

data class ProcessResult(
    val sent: Int,
    val failed: Int,
    val oversizeSkipped: Int,
) {
    val shouldRetry: Boolean get() = failed > 0
}

class CallRecordingProcessor(
    private val scanner: FolderScanner,
    private val mailer: Mailer,
    private val sentRepo: SentKeyRepository,
    private val clock: Clock,
) {
    fun processOnce(settings: Settings): ProcessResult {
        val files = scanner.scan(File(settings.watchDir), settings.extensions)
        val selection = SendableSelector.select(
            files = files,
            nowMillis = clock.now(),
            maxBytes = settings.maxAttachmentBytes,
            isSent = { sentRepo.isSent(it) },
        )
        var sent = 0
        var failed = 0
        for (f in selection.toSend) {
            val result = mailer.send(
                config = settings.smtp,
                to = settings.toAddress,
                subject = MailContent.subject(f),
                body = MailContent.body(f),
                attachment = File(f.path),
            )
            if (result.isSuccess) {
                sentRepo.markSent(RecordingKey.of(f))
                sent++
            } else {
                failed++
            }
        }
        return ProcessResult(sent = sent, failed = failed, oversizeSkipped = selection.oversize.size)
    }
}
