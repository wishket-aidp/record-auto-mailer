package com.melt.callrecmailer.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.melt.callrecmailer.core.CallRecordingProcessor
import com.melt.callrecmailer.core.FolderScanner
import com.melt.callrecmailer.data.EncryptedSentKeyRepository
import com.melt.callrecmailer.data.EncryptedSettingsStore
import com.melt.callrecmailer.mail.JavaMailMailer
import com.melt.callrecmailer.notify.Notifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ScanWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val store = EncryptedSettingsStore(applicationContext)
        if (!store.isConfigured()) return@withContext Result.success()

        val settings = store.load()
        val processor = CallRecordingProcessor(
            scanner = FolderScanner(),
            mailer = JavaMailMailer(),
            sentRepo = EncryptedSentKeyRepository(applicationContext),
            clock = { System.currentTimeMillis() },
        )

        val result = runCatching { processor.processOnce(settings) }
            .getOrElse { return@withContext Result.retry() }

        if (result.sent > 0) {
            Notifier.notify(applicationContext, 1001, "통화녹음 전송", "${result.sent}건 전송 완료")
        }
        if (result.oversizeSkipped > 0) {
            Notifier.notify(applicationContext, 1002, "전송 스킵", "${result.oversizeSkipped}건 20MB 초과")
        }

        if (result.shouldRetry) Result.retry() else Result.success()
    }
}
