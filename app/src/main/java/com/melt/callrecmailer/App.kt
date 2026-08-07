package com.melt.callrecmailer

import android.app.Application
import com.melt.callrecmailer.notify.Notifier

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannel(this)
        // 주기작업 예약은 Task 10(Scheduler) 구현 후 추가
    }
}
