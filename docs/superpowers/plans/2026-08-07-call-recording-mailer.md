# 갤럭시 통화녹음 자동 이메일 전송 앱 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 갤럭시 `Recordings/Call` 폴더에 생성된 통화녹음 파일을 주기적으로 감지해 Gmail SMTP로 자동 발송하는 개인용 경량 안드로이드 앱을 만든다.

**Architecture:** 로직을 두 층으로 분리한다 — (1) 순수 Kotlin **코어**(스캔·중복판정·메일본문·오케스트레이션; JVM 단위테스트로 TDD), (2) 얇은 **안드로이드 글루**(WorkManager·EncryptedSharedPreferences·JavaMail·권한·UI; 구현 후 기기에서 수동 검증). 코어는 인터페이스(`Mailer`, `SentKeyRepository`, `SettingsStore`, `Clock`)로 글루에 의존하지 않는다.

**Tech Stack:** Kotlin, WorkManager, JavaMail(android-mail, `javax.mail`), AndroidX Security(EncryptedSharedPreferences), Coroutines. 테스트: JUnit4 + TemporaryFolder.

## Global Constraints

- 플랫폼: 삼성 갤럭시, One UI 5+/**Android 13+**.
- SDK: **minSdk 33**, targetSdk 35, compileSdk 35. Java/Kotlin JVM target **17**.
- 패키지: `com.melt.callrecmailer`.
- 기본값(코어 상수): 발신 `melt.road@gmail.com` · 수신 `yonggill@wishket.com` · 감시폴더 `/storage/emulated/0/Recordings/Call` · 확장자 `{m4a, amr, mp3}` · 최대첨부 **20MB**(`20*1024*1024`) · 쓰기완료 대기 **60초** · SMTP `smtp.gmail.com:587` STARTTLS.
- 권한: `INTERNET`, `MANAGE_EXTERNAL_STORAGE`, `POST_NOTIFICATIONS`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. **`RECORD_AUDIO`/`READ_CALL_LOG`/`READ_PHONE_STATE`는 절대 선언하지 않음.**
- 앱 비밀번호는 소스 하드코딩·로그 출력 금지. `EncryptedSharedPreferences`에만 저장. 저장 시 공백 제거.
- WorkManager: `PeriodicWorkRequest` 15분, `enqueueUniquePeriodicWork(KEEP)`, `NetworkType.CONNECTED`.
- 파일 식별 키: `"$name:$size:$lastModified"`.
- 커밋 메시지 말미에 다음 트레일러 추가:
  `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`
- 테스트 실행: `./gradlew :app:testDebugUnitTest` (단일 클래스: `--tests "FQCN"`).

## File Structure

코어(순수, `app/src/main/java/com/melt/callrecmailer/core/`):
- `RecordingFile.kt` — 파일 메타 데이터 클래스
- `RecordingKey.kt` — 중복판정 키
- `SmtpConfig.kt` / `Settings.kt` — 설정 값/기본값
- `Clock.kt` — 시간 추상화
- `Mailer.kt` / `SentKeyRepository.kt` / `SettingsStore.kt` — 글루 경계 인터페이스
- `FolderScanner.kt` — 폴더 → RecordingFile 목록
- `SendableSelector.kt` — 신규·완료·크기 필터
- `MailContent.kt` — 제목/본문 생성
- `CallRecordingProcessor.kt` — 한 주기 오케스트레이션

안드로이드 글루:
- `mail/MimeMessageFactory.kt` — MimeMessage 조립(네트워크 없음)
- `mail/JavaMailMailer.kt` — 세션 + 팩토리 + `Transport.send`
- `data/EncryptedSettingsStore.kt` / `data/EncryptedSentKeyRepository.kt`
- `notify/Notifier.kt` — 알림 채널/발송
- `work/ScanWorker.kt` / `work/Scheduler.kt`
- `ui/PermissionHelper.kt` / `ui/MainActivity.kt`
- `App.kt` — Application(알림 채널 생성, 주기작업 예약)
- `res/layout/activity_main.xml`, `AndroidManifest.xml`

테스트(`app/src/test/java/com/melt/callrecmailer/`):
- `core/RecordingKeyTest.kt`, `core/FolderScannerTest.kt`, `core/SendableSelectorTest.kt`, `core/MailContentTest.kt`, `core/CallRecordingProcessorTest.kt`
- `mail/MimeMessageFactoryTest.kt`
- 테스트용 페이크: `core/Fakes.kt`

---

### Task 1: 프로젝트 스캐폴딩 + 의존성 + 매니페스트

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/` wrapper, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`
- (Android Studio 마법사로 생성 후 아래 값으로 수정)

**Interfaces:**
- Produces: 빌드 가능한 빈 앱 모듈, 패키지 `com.melt.callrecmailer`, minSdk 33.

- [ ] **Step 1: Android Studio로 프로젝트 생성**

Android Studio → New Project → **Empty Views Activity** → 설정:
Name `CallRecordingMailer`, Package `com.melt.callrecmailer`, Language **Kotlin**, Minimum SDK **API 33**, Build config language **Kotlin DSL**. 위치는 이 저장소 루트(`call-recording-mailer/`)로 지정(기존 `docs/`, `README.md`, `.gitignore` 유지).

- [ ] **Step 2: `app/build.gradle.kts` 의존성/SDK 설정**

`android { }` 블록에 다음이 반영되도록 수정:

```kotlin
android {
    namespace = "com.melt.callrecmailer"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.melt.callrecmailer"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // android-mail 리소스 충돌 시 주석 해제:
    // packaging { resources { excludes += setOf("META-INF/NOTICE.md", "META-INF/LICENSE.md") } }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")

    testImplementation("junit:junit:4.13.2")
}
```

- [ ] **Step 3: `AndroidManifest.xml` 권한/Application 선언**

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET"/>
    <uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE"/>
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"/>

    <application
        android:name=".App"
        android:allowBackup="false"
        android:label="통화녹음 메일러"
        android:theme="@style/Theme.CallRecordingMailer">
        <activity android:name=".ui.MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
        </activity>
    </application>
</manifest>
```

> `@style/Theme.CallRecordingMailer`는 마법사가 앱 이름으로 생성하는 테마명(`res/values/themes.xml`)이다. 실제 생성된 테마명과 다르면 그 이름으로 맞춘다.

- [ ] **Step 4: 빌드 확인**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. (`.App`/`MainActivity` 미존재로 실패하면 Task 9/11에서 생성 예정이므로, 이 단계에서는 마법사가 만든 기본 `MainActivity`를 임시로 두고 빌드만 통과시킨다. 매니페스트의 `.App`/`.ui.MainActivity` 참조는 Task 9/11 완료 후 최종 일치시킨다.)

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "chore: scaffold Android app module with deps and permissions"
```

---

### Task 2: RecordingFile + RecordingKey (중복판정 키)

**Files:**
- Create: `app/src/main/java/com/melt/callrecmailer/core/RecordingFile.kt`
- Create: `app/src/main/java/com/melt/callrecmailer/core/RecordingKey.kt`
- Test: `app/src/test/java/com/melt/callrecmailer/core/RecordingKeyTest.kt`

**Interfaces:**
- Produces: `data class RecordingFile(path: String, name: String, size: Long, lastModified: Long)`; `object RecordingKey { fun of(file: RecordingFile): String }`.

- [ ] **Step 1: 실패 테스트 작성**

```kotlin
package com.melt.callrecmailer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RecordingKeyTest {
    @Test fun key_combines_name_size_and_lastModified() {
        val f = RecordingFile("/x/a.m4a", "a.m4a", 100L, 1700000000000L)
        assertEquals("a.m4a:100:1700000000000", RecordingKey.of(f))
    }

    @Test fun different_size_yields_different_key() {
        val a = RecordingFile("/x/a.m4a", "a.m4a", 100L, 10L)
        val b = RecordingFile("/x/a.m4a", "a.m4a", 200L, 10L)
        assertNotEquals(RecordingKey.of(a), RecordingKey.of(b))
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :app:testDebugUnitTest --tests "com.melt.callrecmailer.core.RecordingKeyTest"`
Expected: FAIL — `RecordingFile`/`RecordingKey` 미해결.

- [ ] **Step 3: 구현**

```kotlin
// RecordingFile.kt
package com.melt.callrecmailer.core

data class RecordingFile(
    val path: String,
    val name: String,
    val size: Long,
    val lastModified: Long,
)
```

```kotlin
// RecordingKey.kt
package com.melt.callrecmailer.core

object RecordingKey {
    fun of(file: RecordingFile): String = "${file.name}:${file.size}:${file.lastModified}"
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :app:testDebugUnitTest --tests "com.melt.callrecmailer.core.RecordingKeyTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/melt/callrecmailer/core/RecordingFile.kt \
        app/src/main/java/com/melt/callrecmailer/core/RecordingKey.kt \
        app/src/test/java/com/melt/callrecmailer/core/RecordingKeyTest.kt
git commit -m "feat(core): add RecordingFile and RecordingKey"
```

---

### Task 3: FolderScanner (폴더 → 파일 목록)

**Files:**
- Create: `app/src/main/java/com/melt/callrecmailer/core/FolderScanner.kt`
- Test: `app/src/test/java/com/melt/callrecmailer/core/FolderScannerTest.kt`

**Interfaces:**
- Consumes: `RecordingFile`.
- Produces: `class FolderScanner { fun scan(dir: java.io.File, extensions: Set<String>): List<RecordingFile> }` — 확장자 대소문자 무시, 없는 폴더는 빈 목록, `lastModified` 오름차순.

- [ ] **Step 1: 실패 테스트 작성**

```kotlin
package com.melt.callrecmailer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FolderScannerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scanner = FolderScanner()

    @Test fun returns_only_matching_extensions_case_insensitive() {
        tmp.newFile("a.m4a"); tmp.newFile("b.txt"); tmp.newFile("c.AMR")
        val names = scanner.scan(tmp.root, setOf("m4a", "amr")).map { it.name }.toSet()
        assertEquals(setOf("a.m4a", "c.AMR"), names)
    }

    @Test fun returns_empty_when_dir_missing() {
        val res = scanner.scan(File(tmp.root, "nope"), setOf("m4a"))
        assertTrue(res.isEmpty())
    }

    @Test fun captures_size_and_lastModified() {
        val f = tmp.newFile("a.m4a"); f.writeBytes(ByteArray(5))
        val r = scanner.scan(tmp.root, setOf("m4a")).single()
        assertEquals(5L, r.size)
        assertEquals(f.lastModified(), r.lastModified)
        assertEquals(f.absolutePath, r.path)
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :app:testDebugUnitTest --tests "com.melt.callrecmailer.core.FolderScannerTest"`
Expected: FAIL — `FolderScanner` 미해결.

- [ ] **Step 3: 구현**

```kotlin
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
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :app:testDebugUnitTest --tests "com.melt.callrecmailer.core.FolderScannerTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/melt/callrecmailer/core/FolderScanner.kt \
        app/src/test/java/com/melt/callrecmailer/core/FolderScannerTest.kt
git commit -m "feat(core): add FolderScanner"
```

---

### Task 4: SendableSelector (신규·완료·크기 필터)

**Files:**
- Create: `app/src/main/java/com/melt/callrecmailer/core/SendableSelector.kt`
- Test: `app/src/test/java/com/melt/callrecmailer/core/SendableSelectorTest.kt`

**Interfaces:**
- Consumes: `RecordingFile`, `RecordingKey`.
- Produces: `data class SelectionResult(toSend: List<RecordingFile>, oversize: List<RecordingFile>)`; `object SendableSelector { const val MIN_AGE_MILLIS = 60_000L; fun select(files: List<RecordingFile>, nowMillis: Long, maxBytes: Long, isSent: (String) -> Boolean): SelectionResult }`.

- [ ] **Step 1: 실패 테스트 작성**

```kotlin
package com.melt.callrecmailer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SendableSelectorTest {
    private val now = 1_000_000_000L
    private fun file(name: String, size: Long, age: Long) =
        RecordingFile("/d/$name", name, size, now - age)

    @Test fun new_completed_within_size_goes_to_toSend() {
        val f = file("a.m4a", 1000, age = 120_000)
        val r = SendableSelector.select(listOf(f), now, 20_000_000) { false }
        assertEquals(listOf(f), r.toSend)
        assertTrue(r.oversize.isEmpty())
    }

    @Test fun already_sent_is_skipped() {
        val f = file("a.m4a", 1000, age = 120_000)
        val r = SendableSelector.select(listOf(f), now, 20_000_000) { true }
        assertTrue(r.toSend.isEmpty())
    }

    @Test fun too_recent_is_skipped() {
        val f = file("a.m4a", 1000, age = 10_000) // < 60s
        val r = SendableSelector.select(listOf(f), now, 20_000_000) { false }
        assertTrue(r.toSend.isEmpty())
    }

    @Test fun oversize_goes_to_oversize_bucket() {
        val f = file("big.m4a", 30_000_000, age = 120_000)
        val r = SendableSelector.select(listOf(f), now, 20_000_000) { false }
        assertTrue(r.toSend.isEmpty())
        assertEquals(listOf(f), r.oversize)
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :app:testDebugUnitTest --tests "com.melt.callrecmailer.core.SendableSelectorTest"`
Expected: FAIL — `SendableSelector`/`SelectionResult` 미해결.

- [ ] **Step 3: 구현**

```kotlin
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
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :app:testDebugUnitTest --tests "com.melt.callrecmailer.core.SendableSelectorTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/melt/callrecmailer/core/SendableSelector.kt \
        app/src/test/java/com/melt/callrecmailer/core/SendableSelectorTest.kt
git commit -m "feat(core): add SendableSelector"
```

---

### Task 5: 설정 값/인터페이스 + MailContent

**Files:**
- Create: `app/src/main/java/com/melt/callrecmailer/core/SmtpConfig.kt`
- Create: `app/src/main/java/com/melt/callrecmailer/core/Settings.kt`
- Create: `app/src/main/java/com/melt/callrecmailer/core/Clock.kt`
- Create: `app/src/main/java/com/melt/callrecmailer/core/Mailer.kt`
- Create: `app/src/main/java/com/melt/callrecmailer/core/SentKeyRepository.kt`
- Create: `app/src/main/java/com/melt/callrecmailer/core/SettingsStore.kt`
- Create: `app/src/main/java/com/melt/callrecmailer/core/MailContent.kt`
- Test: `app/src/test/java/com/melt/callrecmailer/core/MailContentTest.kt`

**Interfaces:**
- Produces:
  - `data class SmtpConfig(host, port: Int, username, appPassword, fromAddress)`
  - `data class Settings(smtp: SmtpConfig, toAddress, watchDir, extensions: Set<String>, maxAttachmentBytes: Long)` + `companion` 기본값 상수
  - `fun interface Clock { fun now(): Long }`
  - `interface Mailer { fun send(config: SmtpConfig, to: String, subject: String, body: String, attachment: File): Result<Unit> }`
  - `interface SentKeyRepository { fun isSent(key: String): Boolean; fun markSent(key: String) }`
  - `interface SettingsStore { fun load(): Settings; fun saveAppPassword(appPassword: String); fun isConfigured(): Boolean }`
  - `object MailContent { fun subject(f: RecordingFile): String; fun body(f: RecordingFile): String }`

- [ ] **Step 1: 실패 테스트 작성 (MailContent)**

```kotlin
package com.melt.callrecmailer.core

import org.junit.Assert.assertTrue
import org.junit.Test

class MailContentTest {
    private val f = RecordingFile("/d/call.m4a", "call.m4a", 2048L, 1700000000000L)

    @Test fun subject_contains_prefix_and_filename() {
        assertTrue(MailContent.subject(f).contains("call.m4a"))
        assertTrue(MailContent.subject(f).startsWith("[통화녹음]"))
    }

    @Test fun body_contains_filename_and_size() {
        val body = MailContent.body(f)
        assertTrue(body.contains("call.m4a"))
        assertTrue(body.contains("2")) // 2048 bytes -> 2 KB
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :app:testDebugUnitTest --tests "com.melt.callrecmailer.core.MailContentTest"`
Expected: FAIL — 미해결 심볼.

- [ ] **Step 3: 구현 (인터페이스/값 + MailContent)**

```kotlin
// SmtpConfig.kt
package com.melt.callrecmailer.core

data class SmtpConfig(
    val host: String,
    val port: Int,
    val username: String,
    val appPassword: String,
    val fromAddress: String,
)
```

```kotlin
// Settings.kt
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
```

```kotlin
// Clock.kt
package com.melt.callrecmailer.core

fun interface Clock { fun now(): Long }
```

```kotlin
// Mailer.kt
package com.melt.callrecmailer.core

import java.io.File

interface Mailer {
    fun send(
        config: SmtpConfig,
        to: String,
        subject: String,
        body: String,
        attachment: File,
    ): Result<Unit>
}
```

```kotlin
// SentKeyRepository.kt
package com.melt.callrecmailer.core

interface SentKeyRepository {
    fun isSent(key: String): Boolean
    fun markSent(key: String)
}
```

```kotlin
// SettingsStore.kt
package com.melt.callrecmailer.core

interface SettingsStore {
    fun load(): Settings
    fun saveAppPassword(appPassword: String)
    fun isConfigured(): Boolean
}
```

```kotlin
// MailContent.kt
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
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :app:testDebugUnitTest --tests "com.melt.callrecmailer.core.MailContentTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/melt/callrecmailer/core/
git add app/src/test/java/com/melt/callrecmailer/core/MailContentTest.kt
git commit -m "feat(core): add settings types, boundary interfaces, MailContent"
```

---

### Task 6: CallRecordingProcessor (한 주기 오케스트레이션)

**Files:**
- Create: `app/src/main/java/com/melt/callrecmailer/core/CallRecordingProcessor.kt`
- Create: `app/src/test/java/com/melt/callrecmailer/core/Fakes.kt`
- Test: `app/src/test/java/com/melt/callrecmailer/core/CallRecordingProcessorTest.kt`

**Interfaces:**
- Consumes: `FolderScanner`, `SendableSelector`, `MailContent`, `Mailer`, `SentKeyRepository`, `Clock`, `Settings`, `RecordingKey`.
- Produces: `data class ProcessResult(sent: Int, failed: Int, oversizeSkipped: Int) { val shouldRetry: Boolean }`; `class CallRecordingProcessor(scanner, mailer, sentRepo, clock) { fun processOnce(settings: Settings): ProcessResult }`.

- [ ] **Step 1: 페이크 작성**

```kotlin
package com.melt.callrecmailer.core

import java.io.File

class FakeMailer(var succeed: Boolean = true) : Mailer {
    data class Sent(val to: String, val subject: String, val attachmentName: String)
    val sent = mutableListOf<Sent>()
    override fun send(
        config: SmtpConfig, to: String, subject: String, body: String, attachment: File,
    ): Result<Unit> {
        return if (succeed) {
            sent.add(Sent(to, subject, attachment.name))
            Result.success(Unit)
        } else {
            Result.failure(RuntimeException("smtp down"))
        }
    }
}

class InMemorySentRepo : SentKeyRepository {
    val keys = mutableSetOf<String>()
    override fun isSent(key: String) = keys.contains(key)
    override fun markSent(key: String) { keys.add(key) }
}
```

- [ ] **Step 2: 실패 테스트 작성**

```kotlin
package com.melt.callrecmailer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CallRecordingProcessorTest {
    @get:Rule val tmp = TemporaryFolder()
    private val now = 10_000_000_000L
    private val clock = Clock { now }

    private fun settings(dir: File) = Settings(
        smtp = SmtpConfig("h", 587, "u@x", "pw", "u@x"),
        toAddress = "to@y",
        watchDir = dir.absolutePath,
        extensions = setOf("m4a"),
        maxAttachmentBytes = 20L * 1024 * 1024,
    )

    private fun oldFile(name: String, bytes: Int): File {
        val f = tmp.newFile(name); f.writeBytes(ByteArray(bytes))
        f.setLastModified(now - 120_000)
        return f
    }

    @Test fun sends_new_file_and_marks_sent() {
        oldFile("a.m4a", 10)
        val mailer = FakeMailer(); val repo = InMemorySentRepo()
        val p = CallRecordingProcessor(FolderScanner(), mailer, repo, clock)
        val r = p.processOnce(settings(tmp.root))
        assertEquals(1, r.sent)
        assertEquals("to@y", mailer.sent.single().to)
        assertTrue(repo.keys.isNotEmpty())
    }

    @Test fun does_not_resend_already_sent() {
        oldFile("a.m4a", 10)
        val mailer = FakeMailer(); val repo = InMemorySentRepo()
        val p = CallRecordingProcessor(FolderScanner(), mailer, repo, clock)
        p.processOnce(settings(tmp.root))
        val r2 = p.processOnce(settings(tmp.root))
        assertEquals(0, r2.sent)
        assertEquals(1, mailer.sent.size)
    }

    @Test fun failure_does_not_mark_and_requests_retry() {
        oldFile("a.m4a", 10)
        val mailer = FakeMailer(succeed = false); val repo = InMemorySentRepo()
        val p = CallRecordingProcessor(FolderScanner(), mailer, repo, clock)
        val r = p.processOnce(settings(tmp.root))
        assertEquals(0, r.sent)
        assertEquals(1, r.failed)
        assertTrue(r.shouldRetry)
        assertFalse(repo.keys.iterator().hasNext())
    }

    @Test fun oversize_is_skipped_and_counted() {
        val f = tmp.newFile("big.m4a"); f.writeBytes(ByteArray(1024))
        f.setLastModified(now - 120_000)
        val mailer = FakeMailer(); val repo = InMemorySentRepo()
        val small = settings(tmp.root).copy(maxAttachmentBytes = 100)
        val p = CallRecordingProcessor(FolderScanner(), mailer, repo, clock)
        val r = p.processOnce(small)
        assertEquals(0, r.sent)
        assertEquals(1, r.oversizeSkipped)
        assertTrue(mailer.sent.isEmpty())
    }
}
```

- [ ] **Step 3: 실패 확인**

Run: `./gradlew :app:testDebugUnitTest --tests "com.melt.callrecmailer.core.CallRecordingProcessorTest"`
Expected: FAIL — `CallRecordingProcessor`/`ProcessResult` 미해결.

- [ ] **Step 4: 구현**

```kotlin
package com.melt.callrecmailer.core

import java.io.File

data class ProcessResult(
    val sent: Int,
    val failed: Int,
    val oversizeSkipped: Int,
) {
    val shouldRetry: Boolean get() = failed > 0
}

class CallRecordingProcessor(
    private val scanner: FolderScanner,
    private val mailer: Mailer,
    private val sentRepo: SentKeyRepository,
    private val clock: Clock,
) {
    fun processOnce(settings: Settings): ProcessResult {
        val files = scanner.scan(File(settings.watchDir), settings.extensions)
        val selection = SendableSelector.select(
            files = files,
            nowMillis = clock.now(),
            maxBytes = settings.maxAttachmentBytes,
            isSent = { sentRepo.isSent(it) },
        )
        var sent = 0
        var failed = 0
        for (f in selection.toSend) {
            val result = mailer.send(
                config = settings.smtp,
                to = settings.toAddress,
                subject = MailContent.subject(f),
                body = MailContent.body(f),
                attachment = File(f.path),
            )
            if (result.isSuccess) {
                sentRepo.markSent(RecordingKey.of(f))
                sent++
            } else {
                failed++
            }
        }
        return ProcessResult(sent = sent, failed = failed, oversizeSkipped = selection.oversize.size)
    }
}
```

- [ ] **Step 5: 통과 확인 + 전체 코어 회귀**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (Task 2~6 전체).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/melt/callrecmailer/core/CallRecordingProcessor.kt \
        app/src/test/java/com/melt/callrecmailer/core/Fakes.kt \
        app/src/test/java/com/melt/callrecmailer/core/CallRecordingProcessorTest.kt
git commit -m "feat(core): add CallRecordingProcessor orchestration with tests"
```

---

### Task 7: MimeMessageFactory + JavaMailMailer (실제 SMTP)

**Files:**
- Create: `app/src/main/java/com/melt/callrecmailer/mail/MimeMessageFactory.kt`
- Create: `app/src/main/java/com/melt/callrecmailer/mail/JavaMailMailer.kt`
- Test: `app/src/test/java/com/melt/callrecmailer/mail/MimeMessageFactoryTest.kt`

**Interfaces:**
- Consumes: `SmtpConfig`, `Mailer`.
- Produces: `object MimeMessageFactory { fun build(session: Session, config: SmtpConfig, to: String, subject: String, body: String, attachment: File): MimeMessage }`; `class JavaMailMailer : Mailer`.

- [ ] **Step 1: 실패 테스트 작성 (네트워크 없이 MimeMessage 조립 검증)**

```kotlin
package com.melt.callrecmailer.mail

import com.melt.callrecmailer.core.SmtpConfig
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Properties
import javax.mail.Message
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMultipart

class MimeMessageFactoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val session = Session.getInstance(Properties())

    @Test fun builds_message_with_subject_recipient_from_and_attachment() {
        val file = tmp.newFile("call.m4a").apply { writeBytes(ByteArray(3)) }
        val cfg = SmtpConfig("h", 587, "u@x", "pw", "from@x")
        val msg = MimeMessageFactory.build(session, cfg, "to@y", "subj", "hello", file)

        assertEquals("subj", msg.subject)
        assertEquals("from@x", (msg.from[0] as InternetAddress).address)
        assertEquals("to@y", (msg.getRecipients(Message.RecipientType.TO)[0] as InternetAddress).address)

        val mp = msg.content as MimeMultipart
        assertEquals(2, mp.count)
        val hasAttachment = (0 until mp.count)
            .map { mp.getBodyPart(it) }
            .any { it.fileName == "call.m4a" }
        assertEquals(true, hasAttachment)
    }
}
```

> 주의: `com.sun.mail:android-mail`은 Android 대상 빌드라 JVM 단위테스트에서 `javax.mail` 조립이 정상 동작하지 않을 수 있다. 이 테스트가 클래스로딩/런타임 문제로 실패하면, 파일을 `app/src/androidTest/...`(instrumented)로 옮겨 기기에서 실행하거나, 이 자동테스트를 제거하고 Task 11의 "테스트 발송" 버튼으로 수동 검증한다. **로직(팩토리) 구현 자체는 유지한다.**

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :app:testDebugUnitTest --tests "com.melt.callrecmailer.mail.MimeMessageFactoryTest"`
Expected: FAIL — `MimeMessageFactory` 미해결.

- [ ] **Step 3: 구현**

```kotlin
// MimeMessageFactory.kt
package com.melt.callrecmailer.mail

import com.melt.callrecmailer.core.SmtpConfig
import java.io.File
import javax.activation.DataHandler
import javax.activation.FileDataSource
import javax.mail.Message
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

object MimeMessageFactory {
    fun build(
        session: Session,
        config: SmtpConfig,
        to: String,
        subject: String,
        body: String,
        attachment: File,
    ): MimeMessage {
        val message = MimeMessage(session)
        message.setFrom(InternetAddress(config.fromAddress))
        message.setRecipient(Message.RecipientType.TO, InternetAddress(to))
        message.setSubject(subject, "UTF-8")

        val textPart = MimeBodyPart().apply { setText(body, "UTF-8") }
        val filePart = MimeBodyPart().apply {
            dataHandler = DataHandler(FileDataSource(attachment))
            fileName = attachment.name
        }
        message.setContent(MimeMultipart().apply {
            addBodyPart(textPart)
            addBodyPart(filePart)
        })
        message.saveChanges()
        return message
    }
}
```

```kotlin
// JavaMailMailer.kt
package com.melt.callrecmailer.mail

import com.melt.callrecmailer.core.Mailer
import com.melt.callrecmailer.core.SmtpConfig
import java.io.File
import java.util.Properties
import javax.mail.Authenticator
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport

class JavaMailMailer : Mailer {
    override fun send(
        config: SmtpConfig,
        to: String,
        subject: String,
        body: String,
        attachment: File,
    ): Result<Unit> = runCatching {
        val props = Properties().apply {
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.host", config.host)
            put("mail.smtp.port", config.port.toString())
        }
        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication() =
                PasswordAuthentication(config.username, config.appPassword)
        })
        val message = MimeMessageFactory.build(session, config, to, subject, body, attachment)
        Transport.send(message)
    }
}
```

- [ ] **Step 4: 통과 확인 (또는 위 주의사항대로 처리)**

Run: `./gradlew :app:testDebugUnitTest --tests "com.melt.callrecmailer.mail.MimeMessageFactoryTest"`
Expected: PASS. (실패 시 Step 1 주의사항 적용.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/melt/callrecmailer/mail/ \
        app/src/test/java/com/melt/callrecmailer/mail/MimeMessageFactoryTest.kt
git commit -m "feat(mail): add MimeMessageFactory and JavaMailMailer"
```

---

### Task 8: 암호화 저장소 구현 (Settings/SentKey)

**Files:**
- Create: `app/src/main/java/com/melt/callrecmailer/data/EncryptedSettingsStore.kt`
- Create: `app/src/main/java/com/melt/callrecmailer/data/EncryptedSentKeyRepository.kt`

**Interfaces:**
- Consumes: `SettingsStore`, `SentKeyRepository`, `Settings`, `SmtpConfig`.
- Produces: `class EncryptedSettingsStore(context: Context) : SettingsStore`; `class EncryptedSentKeyRepository(context: Context) : SentKeyRepository`.

> 이 태스크는 Android 프레임워크(EncryptedSharedPreferences)에 의존하므로 JVM 단위테스트 대상이 아니다. 구현 후 **Task 12 기기 검증**에서 함께 확인한다.

- [ ] **Step 1: EncryptedSettingsStore 구현**

```kotlin
package com.melt.callrecmailer.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.melt.callrecmailer.core.Settings
import com.melt.callrecmailer.core.SettingsStore
import com.melt.callrecmailer.core.SmtpConfig

class EncryptedSettingsStore(context: Context) : SettingsStore {
    private val prefs = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "settings",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun load(): Settings {
        val from = prefs.getString(KEY_FROM, Settings.DEFAULT_FROM)!!
        val pw = prefs.getString(KEY_APP_PW, "")!!
        val to = prefs.getString(KEY_TO, Settings.DEFAULT_TO)!!
        val dir = prefs.getString(KEY_DIR, Settings.DEFAULT_WATCH_DIR)!!
        return Settings(
            smtp = SmtpConfig(Settings.SMTP_HOST, Settings.SMTP_PORT, from, pw, from),
            toAddress = to,
            watchDir = dir,
            extensions = Settings.DEFAULT_EXTENSIONS,
            maxAttachmentBytes = Settings.DEFAULT_MAX_BYTES,
        )
    }

    override fun saveAppPassword(appPassword: String) {
        prefs.edit().putString(KEY_APP_PW, appPassword.replace(" ", "")).apply()
    }

    override fun isConfigured(): Boolean = !prefs.getString(KEY_APP_PW, "").isNullOrBlank()

    fun saveAddresses(from: String, to: String, dir: String) {
        prefs.edit()
            .putString(KEY_FROM, from)
            .putString(KEY_TO, to)
            .putString(KEY_DIR, dir)
            .apply()
    }

    companion object {
        private const val KEY_FROM = "from"
        private const val KEY_APP_PW = "app_pw"
        private const val KEY_TO = "to"
        private const val KEY_DIR = "dir"
    }
}
```

- [ ] **Step 2: EncryptedSentKeyRepository 구현**

```kotlin
package com.melt.callrecmailer.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.melt.callrecmailer.core.SentKeyRepository

class EncryptedSentKeyRepository(context: Context) : SentKeyRepository {
    private val prefs = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "sent_keys",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun isSent(key: String): Boolean =
        prefs.getStringSet(KEY_SET, emptySet())!!.contains(key)

    override fun markSent(key: String) {
        val updated = HashSet(prefs.getStringSet(KEY_SET, emptySet())!!)
        updated.add(key)
        prefs.edit().putStringSet(KEY_SET, updated).apply()
    }

    companion object {
        private const val KEY_SET = "sent_keys"
    }
}
```

- [ ] **Step 3: 컴파일 확인**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/melt/callrecmailer/data/
git commit -m "feat(data): add encrypted settings and sent-key stores"
```

---

### Task 9: Notifier + App(Application) — 알림 채널/주기작업 예약

**Files:**
- Create: `app/src/main/java/com/melt/callrecmailer/notify/Notifier.kt`
- Create: `app/src/main/java/com/melt/callrecmailer/App.kt`

**Interfaces:**
- Produces: `object Notifier { const val CHANNEL_ID; fun ensureChannel(context); fun notify(context, id: Int, title: String, text: String) }`; `class App : Application`.
- Consumes(예약): `Scheduler.schedule` (Task 10에서 정의 — 이 태스크에서는 호출부만 두고 Task 10 완료 시 연결).

- [ ] **Step 1: Notifier 구현**

```kotlin
package com.melt.callrecmailer.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notifier {
    const val CHANNEL_ID = "send_status"

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID, "발송 상태", NotificationManager.IMPORTANCE_LOW,
        )
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    fun notify(context: Context, id: Int, title: String, text: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        try {
            if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                NotificationManagerCompat.from(context).notify(id, notification)
            }
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS 미허용 — 무시
        }
    }
}
```

- [ ] **Step 2: App(Application) 구현 — 채널 생성 + 주기작업 예약**

```kotlin
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
```

> `Scheduler`는 Task 10에서 생성한다. 이 태스크를 Task 10보다 먼저 구현하는 경우, 잠시 `Scheduler.schedule(this)` 줄을 주석 처리했다가 Task 10 완료 후 해제한다.

- [ ] **Step 3: 컴파일 확인**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL (Scheduler 미존재 시 위 주석 안내 적용).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/melt/callrecmailer/notify/Notifier.kt \
        app/src/main/java/com/melt/callrecmailer/App.kt
git commit -m "feat(notify): add Notifier and Application with channel + scheduling"
```

---

### Task 10: ScanWorker + Scheduler (WorkManager)

**Files:**
- Create: `app/src/main/java/com/melt/callrecmailer/work/ScanWorker.kt`
- Create: `app/src/main/java/com/melt/callrecmailer/work/Scheduler.kt`

**Interfaces:**
- Consumes: `EncryptedSettingsStore`, `EncryptedSentKeyRepository`, `FolderScanner`, `JavaMailMailer`, `CallRecordingProcessor`, `Notifier`.
- Produces: `class ScanWorker(context, params) : CoroutineWorker`; `object Scheduler { fun schedule(context); fun runNow(context) }`.

- [ ] **Step 1: ScanWorker 구현**

```kotlin
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
```

- [ ] **Step 2: Scheduler 구현**

```kotlin
package com.melt.callrecmailer.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object Scheduler {
    private const val WORK_NAME = "call_recording_scan"

    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<ScanWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkConstraint)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<ScanWorker>()
            .setConstraints(networkConstraint)
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }
}
```

- [ ] **Step 3: App.kt의 `Scheduler.schedule` 주석 해제 확인 (Task 9에서 주석 처리했다면)**

- [ ] **Step 4: 컴파일 확인**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/melt/callrecmailer/work/ app/src/main/java/com/melt/callrecmailer/App.kt
git commit -m "feat(work): add ScanWorker and WorkManager scheduler"
```

---

### Task 11: PermissionHelper + MainActivity + 레이아웃

**Files:**
- Create: `app/src/main/java/com/melt/callrecmailer/ui/PermissionHelper.kt`
- Create: `app/src/main/java/com/melt/callrecmailer/ui/MainActivity.kt`
- Create: `app/src/main/res/layout/activity_main.xml`

**Interfaces:**
- Consumes: `EncryptedSettingsStore`, `JavaMailMailer`, `Scheduler`, `PermissionHelper`, `Settings`, `RecordingFile`.
- Produces: `object PermissionHelper { fun hasAllFilesAccess(): Boolean; fun allFilesAccessIntent(context): Intent; fun ignoreBatteryOptimizationIntent(context): Intent }`; `class MainActivity : AppCompatActivity`.

- [ ] **Step 1: PermissionHelper 구현**

```kotlin
package com.melt.callrecmailer.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings

object PermissionHelper {
    fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

    fun allFilesAccessIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )

    fun ignoreBatteryOptimizationIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        )
}
```

- [ ] **Step 2: 레이아웃 작성**

```xml
<?xml version="1.0" encoding="utf-8"?>
<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent">
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="20dp">

        <TextView android:id="@+id/status"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:textSize="14sp"
            android:paddingBottom="16dp"/>

        <EditText android:id="@+id/fromAddress"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:hint="발신 Gmail 주소"
            android:inputType="textEmailAddress"/>

        <EditText android:id="@+id/toAddress"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:hint="수신 주소"
            android:inputType="textEmailAddress"/>

        <EditText android:id="@+id/watchDir"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:hint="감시 폴더 경로"
            android:inputType="textUri"/>

        <EditText android:id="@+id/appPassword"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:hint="Gmail 앱 비밀번호(16자리)"
            android:inputType="textPassword"/>

        <Button android:id="@+id/savePassword"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="설정 저장"/>

        <Button android:id="@+id/grantAllFiles"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="모든 파일 접근 허용"/>

        <Button android:id="@+id/grantNotifications"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="알림 허용"/>

        <Button android:id="@+id/batteryException"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="배터리 최적화 예외 설정"/>

        <Button android:id="@+id/scanNow"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="지금 스캔"/>

        <Button android:id="@+id/testSend"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="테스트 발송"/>
    </LinearLayout>
</ScrollView>
```

- [ ] **Step 3: MainActivity 구현**

```kotlin
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
import com.melt.callrecmailer.R
import com.melt.callrecmailer.data.EncryptedSettingsStore
import com.melt.callrecmailer.mail.JavaMailMailer
import com.melt.callrecmailer.work.Scheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
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
            appendLine("모든 파일 접근: ${if (allFiles) "허용됨" else "필요"}")
            appendLine("알림 권한: ${if (notif) "허용됨" else "필요"}")
            appendLine("앱 비밀번호: ${if (store.isConfigured()) "설정됨" else "미설정"}")
            appendLine("발신: ${s.smtp.fromAddress}")
            appendLine("수신: ${s.toAddress}")
            appendLine("감시 폴더: ${s.watchDir}")
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
            val msg = if (result.isSuccess) "테스트 발송 성공"
                      else "실패: ${result.exceptionOrNull()?.message}"
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
        }
    }
}
```

- [ ] **Step 4: 매니페스트 참조 최종 확인**

`AndroidManifest.xml`의 `android:name=".App"`와 `.ui.MainActivity`가 실제 클래스 경로와 일치하는지 확인. Task 1에서 마법사가 만든 기본 `MainActivity`(다른 패키지/경로)가 남아있다면 제거.

- [ ] **Step 5: 빌드 + 회귀 테스트**

Run: `./gradlew :app:assembleDebug && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 모든 단위테스트 PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/melt/callrecmailer/ui/ \
        app/src/main/res/layout/activity_main.xml \
        app/src/main/AndroidManifest.xml
git commit -m "feat(ui): add permission helper, main activity, layout"
```

---

### Task 12: 기기 End-to-End 수동 검증

**Files:** (없음 — 실기기 검증. 발견된 버그는 해당 태스크로 돌아가 수정)

**Interfaces:** 없음.

- [ ] **Step 1: 사전 준비 — Gmail 앱 비밀번호 발급**

`melt.road@gmail.com` 계정에서 2단계 인증 활성화 → Google 계정 → 보안 → 앱 비밀번호 → 새 앱 비밀번호 16자리 생성. (Workspace가 아닌 개인 Gmail이면 대부분 가능.)

- [ ] **Step 2: 설치**

Run: `./gradlew :app:installDebug` (USB 디버깅 켠 갤럭시 연결) 또는 `app/build/outputs/apk/debug/app-debug.apk`를 폰으로 전송해 설치.

- [ ] **Step 3: 권한/설정 부여**

앱 실행 → [앱 비밀번호 저장](Step1 값) → [모든 파일 접근 허용](설정에서 토글) → [알림 허용] → [배터리 최적화 예외 설정](제한 없음). 상태 화면 3종이 모두 "허용됨/설정됨"인지 확인.

- [ ] **Step 4: 테스트 발송 검증 (SMTP 경로)**

[테스트 발송] 탭 → "테스트 발송 성공" 토스트 확인 → `yonggill@wishket.com` 수신함에 `[통화녹음] 테스트 발송`(첨부 test.txt) 도착 확인. 실패 시: 앱 비밀번호(공백 제거)·2단계 인증·네트워크 점검.

- [ ] **Step 5: 실제 감지 검증 (폴더 경로/스캔)**

옵션 A(권장): 실제 통화 후 녹음이 `/storage/emulated/0/Recordings/Call/`에 생성되게 함.
옵션 B: 파일 매니저로 해당 폴더에 임의 `.m4a` 파일 1개 복사(수정시각이 현재-60초 이상이 되도록 잠시 대기).
그 후 [지금 스캔] 탭 → 몇 초 뒤 수신함에 해당 파일 첨부 메일 도착 + "1건 전송 완료" 알림 확인.
※ 폴더가 다르면(`Call`/`Sounds/Call` 등) 설정 화면의 '감시 폴더 경로' 필드에 실제 경로를 입력하고 [설정 저장] → [지금 스캔]으로 재확인(재빌드 불필요).

- [ ] **Step 6: 중복 방지 검증**

[지금 스캔]을 한 번 더 탭 → 같은 파일이 **재발송되지 않음** 확인(추가 메일/알림 없음).

- [ ] **Step 7: 주기 동작 검증**

앱을 닫고 대기 → 새 파일 생성 후 최대 ~15분(절전 상태 따라 그 이상) 내 자동 발송 확인.
빠른 확인용: `adb shell cmd jobscheduler run -f com.melt.callrecmailer <jobId>` 또는 개발자옵션에서 대기 작업 강제 실행.

- [ ] **Step 8: 인수 기준 대조 (spec §13)**

아래를 모두 만족하는지 확인:
- [ ] 새 파일이 자동 발송됨
- [ ] 동일 파일 2회 발송 없음
- [ ] 네트워크 없을 때 유실 없이 복구 후 발송(비행기모드로 스캔→해제 후 도착 확인)
- [ ] 앱 미실행/재부팅 후에도 동작(재부팅 후 새 파일 테스트)
- [ ] 앱 정보 → 권한에 통화/마이크 권한이 없음(민감권한 미요청 확인)
- [ ] 테스트 발송 버튼 동작

- [ ] **Step 9: 검증 로그 커밋(선택)**

검증 결과를 `docs/superpowers/verification-2026-08-07.md`로 남기고 커밋(선택).

```bash
git add -A
git commit -m "docs: device verification notes"
```

---

## 완료 정의 (Definition of Done)

- Task 2~7 단위테스트 전부 통과(`./gradlew :app:testDebugUnitTest`).
- Task 12 인수 기준(spec §13) 전부 체크.
- 앱 비밀번호가 소스/깃/로그 어디에도 없음.
- 통화 민감 권한 미선언.
