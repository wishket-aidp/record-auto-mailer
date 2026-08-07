package com.melt.callrecmailer.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.melt.callrecmailer.R
import com.melt.callrecmailer.core.FolderScanner
import com.melt.callrecmailer.core.RecordingKey
import com.melt.callrecmailer.data.EncryptedSentKeyRepository
import com.melt.callrecmailer.data.EncryptedSettingsStore
import com.melt.callrecmailer.mail.JavaMailMailer
import com.melt.callrecmailer.work.Scheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var store: EncryptedSettingsStore
    private lateinit var status: TextView

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = EncryptedSettingsStore(this)
        status = findViewById(R.id.status)

        // 저장된 값으로 편집 필드 프리필(최초엔 기본 상수)
        val current = store.load()
        findViewById<EditText>(R.id.fromAddress).setText(current.smtp.fromAddress)
        findViewById<EditText>(R.id.toAddress).setText(current.toAddress)
        findViewById<EditText>(R.id.watchDir).setText(current.watchDir)

        findViewById<Button>(R.id.savePassword).setOnClickListener {
            val pw = findViewById<EditText>(R.id.appPassword).text.toString()
            val from = findViewById<EditText>(R.id.fromAddress).text.toString().trim()
            val to = findViewById<EditText>(R.id.toAddress).text.toString().trim()
            val dir = findViewById<EditText>(R.id.watchDir).text.toString().trim()
            if (pw.isNotBlank()) store.saveAppPassword(pw)
            store.saveAddresses(from, to, dir)
            Scheduler.schedule(this)
            Toast.makeText(this, "저장됨", Toast.LENGTH_SHORT).show()
            refreshStatus()
        }
        findViewById<Button>(R.id.grantAllFiles).setOnClickListener {
            startActivity(PermissionHelper.allFilesAccessIntent(this))
        }
        findViewById<Button>(R.id.grantNotifications).setOnClickListener {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        findViewById<Button>(R.id.batteryException).setOnClickListener {
            startActivity(PermissionHelper.ignoreBatteryOptimizationIntent(this))
        }
        findViewById<Button>(R.id.scanNow).setOnClickListener {
            Scheduler.runNow(this)
            Toast.makeText(this, "스캔 예약됨", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.testSend).setOnClickListener { testSend() }
        findViewById<Button>(R.id.skipExisting).setOnClickListener { skipExisting() }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val allFiles = PermissionHelper.hasAllFilesAccess()
        val notif = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        val s = store.load()
        status.text = buildString {
            appendLine("${if (allFiles) "✓" else "✗"}  모든 파일 접근")
            appendLine("${if (notif) "✓" else "✗"}  알림 권한")
            appendLine("${if (store.isConfigured()) "✓" else "✗"}  앱 비밀번호")
            appendLine()
            appendLine("발신  ${s.smtp.fromAddress}")
            appendLine("수신  ${s.toAddress}")
            append("폴더  ${s.watchDir}")
        }
    }

    private fun testSend() {
        if (!store.isConfigured()) {
            Toast.makeText(this, "앱 비밀번호 먼저 저장", Toast.LENGTH_SHORT).show()
            return
        }
        val settings = store.load()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val tmp = File.createTempFile("test", ".txt", cacheDir).apply {
                    writeText("테스트 발송입니다.")
                }
                JavaMailMailer().send(
                    config = settings.smtp,
                    to = settings.toAddress,
                    subject = "[통화녹음] 테스트 발송",
                    body = "설정 검증용 테스트 메일입니다.",
                    attachment = tmp,
                )
            }
            val msg = if (result.isSuccess) {
                "테스트 발송 성공"
            } else {
                "실패: ${result.exceptionOrNull()?.message}"
            }
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
        }
    }

    /** 현재 폴더의 모든 녹음을 '전송완료'로 표시해, 이후 새로 생기는 파일만 전송되게 한다. */
    private fun skipExisting() {
        if (!PermissionHelper.hasAllFilesAccess()) {
            Toast.makeText(this, "먼저 '모든 파일 접근'을 허용하세요", Toast.LENGTH_SHORT).show()
            return
        }
        val s = store.load()
        val files = FolderScanner().scan(File(s.watchDir), s.extensions)
        EncryptedSentKeyRepository(this).markAllSent(files.map { RecordingKey.of(it) })
        Toast.makeText(this, "${files.size}개 기존 파일 전송 제외 처리됨", Toast.LENGTH_LONG).show()
        refreshStatus()
    }
}
