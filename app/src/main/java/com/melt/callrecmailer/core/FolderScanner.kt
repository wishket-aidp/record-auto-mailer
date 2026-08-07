package com.melt.callrecmailer.core

import java.io.File

class FolderScanner {
    fun scan(dir: File, extensions: Set<String>): List<RecordingFile> {
        val exts = extensions.map { it.lowercase() }.toSet()
        val files = dir.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && it.extension.lowercase() in exts }
            .map { RecordingFile(it.absolutePath, it.name, it.length(), it.lastModified()) }
            .sortedBy { it.lastModified }
    }
}
