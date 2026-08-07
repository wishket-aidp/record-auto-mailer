package com.melt.callrecmailer.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MailContent {
    fun subject(f: RecordingFile): String = "[통화녹음] ${f.name}"

    fun body(f: RecordingFile): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(Date(f.lastModified))
        val sizeKb = f.size / 1024
        return buildString {
            appendLine("통화 녹음 파일이 자동 전송되었습니다.")
            appendLine()
            appendLine("파일명: ${f.name}")
            appendLine("녹음시각: $time")
            appendLine("크기: ${sizeKb} KB")
        }
    }
}
