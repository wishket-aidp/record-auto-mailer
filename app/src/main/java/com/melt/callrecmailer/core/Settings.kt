package com.melt.callrecmailer.core

data class Settings(
    val smtp: SmtpConfig,
    val toAddress: String,
    val watchDir: String,
    val extensions: Set<String>,
    val maxAttachmentBytes: Long,
) {
    companion object {
        const val DEFAULT_FROM = "melt.road@gmail.com"
        const val DEFAULT_TO = "yonggill@wishket.com"
        const val DEFAULT_WATCH_DIR = "/storage/emulated/0/Recordings/Call"
        val DEFAULT_EXTENSIONS = setOf("m4a", "amr", "mp3")
        const val DEFAULT_MAX_BYTES = 20L * 1024 * 1024
        const val SMTP_HOST = "smtp.gmail.com"
        const val SMTP_PORT = 587
    }
}
