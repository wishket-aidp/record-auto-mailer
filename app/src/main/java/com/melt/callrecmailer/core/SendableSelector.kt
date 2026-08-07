package com.melt.callrecmailer.core

data class SelectionResult(
    val toSend: List<RecordingFile>,
    val oversize: List<RecordingFile>,
)

object SendableSelector {
    const val MIN_AGE_MILLIS = 60_000L

    fun select(
        files: List<RecordingFile>,
        nowMillis: Long,
        maxBytes: Long,
        isSent: (String) -> Boolean,
    ): SelectionResult {
        val toSend = ArrayList<RecordingFile>()
        val oversize = ArrayList<RecordingFile>()
        for (f in files) {
            if (isSent(RecordingKey.of(f))) continue
            if (nowMillis - f.lastModified < MIN_AGE_MILLIS) continue
            if (f.size > maxBytes) oversize.add(f) else toSend.add(f)
        }
        return SelectionResult(toSend, oversize)
    }
}
