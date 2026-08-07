package com.melt.callrecmailer

import android.app.Application
import com.melt.callrecmailer.notify.Notifier
import com.melt.callrecmailer.work.Scheduler

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannel(this)
        Scheduler.schedule(this) // 앱 프로세스 생성 시 주기작업 보장(KEEP)
    }
}
