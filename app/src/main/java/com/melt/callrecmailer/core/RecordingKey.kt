package com.melt.callrecmailer.core

object RecordingKey {
    fun of(file: RecordingFile): String = "${file.name}:${file.size}:${file.lastModified}"
}
