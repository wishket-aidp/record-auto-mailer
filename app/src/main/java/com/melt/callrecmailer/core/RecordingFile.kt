package com.melt.callrecmailer.core

data class RecordingFile(
    val path: String,
    val name: String,
    val size: Long,
    val lastModified: Long,
)
