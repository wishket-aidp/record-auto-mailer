# 갤럭시 통화녹음 자동 이메일 전송 앱 — 설계서 (Spec)

- **작성일:** 2026-08-07
- **상태:** 승인됨 (구현 계획 단계로 진행 예정)
- **대상 기기:** 삼성 갤럭시 (One UI 5+ / Android 13+ 기준), 개인 소유 1대
- **저장소:** `call-recording-mailer/`

---

## 1. 목적 / 배경

삼성 갤럭시 기본 통화 녹음 기능이 생성하는 녹음 파일(`Recordings/Call` 폴더)을 자동으로 감지하여, 해당 파일을 첨부한 이메일을 지정된 수신자에게 자동 발송한다. 사용자가 매번 수동으로 파일을 찾아 전달하는 수고를 없앤다.

- 발신: `melt.road@gmail.com` (개인 Gmail, 앱 비밀번호 사용)
- 수신: `yonggill@wishket.com` (업무용, 고정)

## 2. 범위 (Scope)

### In scope
- 지정 폴더의 신규 녹음 파일 주기 감지
- 신규 파일을 첨부한 Gmail SMTP 이메일 자동 발송
- 중복/누락 없이 정확히 1회 발송
- 최초 1회 권한 설정을 돕는 단일 화면 UI

### Out of scope (비범위)
- 통화 녹음 자체(삼성 기본 기능이 담당)
- 실시간 즉시 발송(포그라운드 서비스) — 배터리 우선으로 주기 스캔 채택. §14에 향후 옵션으로만 기록
- 다중 기기/다중 사용자 배포, Play 스토어 배포
- 클라우드 백엔드, OAuth
- 녹음 파일 편집/전사/요약

## 3. 확정된 결정사항 (Decisions Log)

| # | 결정 | 이유 |
|---|---|---|
| D1 | 개인용 사이드로드 APK | 나 혼자, 1대. 정책·OAuth·백엔드 불필요 |
| D2 | 경량 네이티브 Kotlin 앱 | (MacroDroid는 비공식 서드파티라 제외) 직접 제어 + 공식 SDK만 사용 |
| D3 | 주기 스캔(WorkManager ~15분) | 배터리 우선. 실시간성 대비 절전 유리 |
| D4 | 모든 파일 접근(`MANAGE_EXTERNAL_STORAGE`) | 삼성 통화녹음의 MediaStore 색인 타이밍 불확실 → 직접 File 읽기가 가장 확실. 개인 사이드로드라 정책 제약 없음 |
| D5 | Gmail SMTP(587 STARTTLS) + 앱 비밀번호 | 완전 자동 발송, OAuth 불필요 |
| D6 | 앱 비밀번호는 설정화면 입력 → 암호화 저장 | 소스/깃 노출 방지, 교체 시 재빌드 불필요 |
| D7 | 발송기록 영속 저장으로 중복/누락 방지 | 주기 스캔 특성상 상태 추적 필수 |

## 4. 아키텍처

### 4.1 컴포넌트 개요
```
[MainActivity] ── 최초 1회 권한/설정, 상태 표시, 테스트 발송 (화면 1개)
      │
      ▼ (앱을 안 열어도 백그라운드 동작)
[ScanWorker] ── WorkManager 15분 주기 (NetworkType.CONNECTED)
      │  scan → 신규 필터 → 발송 → 기록 → 알림
      ├──▶ [ConfigStore]    설정값(앱비밀번호/주소/폴더) 로드
      ├──▶ [FolderScanner]  폴더 내 오디오 파일 + 메타 반환
      ├──▶ [SentStore]      발송 완료 키 저장/조회 (중복방지)
      ├──▶ [MailSender]     Gmail SMTP + 첨부 발송
      └──▶ [Notifier]       성공/실패 알림
```

### 4.2 컴포넌트별 계약 (책임 / 인터페이스 / 의존)

**FolderScanner**
- 책임: 지정 폴더에서 대상 확장자 오디오 파일 목록과 메타를 반환. 그 외 로직 없음.
- 인터페이스: `fun scan(dir: File, extensions: Set<String>): List<RecordingFile>`
- 데이터: `RecordingFile(path: String, name: String, size: Long, lastModified: Long)`
- 의존: `java.io.File` (권한: `MANAGE_EXTERNAL_STORAGE`)

**SentStore**
- 책임: "이미 보낸 파일"을 영속 기록/조회. 발송 로직은 모름.
- 인터페이스: `keyOf(f: RecordingFile): String`, `isSent(key: String): Boolean`, `markSent(key: String)`
- 키 규칙: `"$name:$size:$lastModified"` (파일명+크기+수정시각 조합)
- 구현: `EncryptedSharedPreferences`의 `StringSet`
- 의존: `androidx.security:security-crypto`

**MailSender**
- 책임: 주어진 설정으로 첨부 이메일 1건 발송. 무엇을/언제 보낼지는 모름.
- 인터페이스: `fun send(cfg: SmtpConfig, to: String, subject: String, body: String, attachment: File): Result<Unit>`
- 데이터: `SmtpConfig(host, port, username, appPassword, fromAddress)`
- 의존: `com.sun.mail:android-mail`, `com.sun.mail:android-activation` (권한: `INTERNET`)
- 스레드: Worker(백그라운드) 컨텍스트에서만 호출

**ConfigStore**
- 책임: 사용자 설정값 저장/로드.
- 필드/기본값:
  - `appPassword`: (비어있음 — 설정화면에서 입력)
  - `fromAddress` = `melt.road@gmail.com`
  - `toAddress` = `yonggill@wishket.com`
  - `watchDir` = `/storage/emulated/0/Recordings/Call`
  - `extensions` = `{ "m4a", "amr", "mp3" }`
  - `maxAttachmentBytes` = `20 * 1024 * 1024` (Gmail 25MB는 base64 인코딩 포함 메시지 전체 한도 → 안전 여유)
- 구현: `EncryptedSharedPreferences`

**ScanWorker (CoroutineWorker)**
- 책임: 한 주기의 오케스트레이션(스캔→필터→발송→기록→알림).
- 필터 조건(모두 만족해야 발송):
  1. `SentStore.isSent(key)` == false (신규)
  2. `now - lastModified >= 60_000ms` (쓰기 완료 추정)
  3. `size <= maxAttachmentBytes` (초과 시 스킵 + 알림, 발송기록에는 남기지 않음)
- 성공 시 `SentStore.markSent(key)` + 성공 알림. 실패 시 기록하지 않음 → 다음 주기 재시도.
- 반환: 개별 발송 실패가 네트워크성이면 `Result.retry()`(백오프), 그 외 `Result.success()`.

**Notifier**
- 책임: 발송 성공/실패/스킵 알림 표시. (권한: `POST_NOTIFICATIONS`)

**MainActivity + PermissionHelper**
- 책임: 권한 상태 점검·요청, 설정 입력, 수동 트리거.
- §5, §9 참조.

### 4.3 데이터 흐름
```
WorkManager(15분) → ScanWorker.doWork()
  → ConfigStore.load()
  → FolderScanner.scan(watchDir, extensions)
  → files.filter { 신규 & 쓰기완료 & 크기OK }
  → forEach: MailSender.send(...)
        성공 → SentStore.markSent(key) + Notifier.success()
        실패(네트워크) → retry 표시
        스킵(크기초과) → Notifier.skip()
  → Result.success()/retry()
```

## 5. 권한 모델

| 권한 | 종류 | 용도 | 요청 방식 |
|---|---|---|---|
| `INTERNET` | 일반(manifest) | SMTP 발송 | 자동 |
| `MANAGE_EXTERNAL_STORAGE` | 특수 접근 | `Recordings/Call` 직접 읽기 | `Environment.isExternalStorageManager()` 확인 → `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` 인텐트 |
| `POST_NOTIFICATIONS` | 런타임(API 33+) | 발송 결과 알림 | `requestPermissions` |
| 배터리 최적화 예외 | 설정 유도 | 15분 주기 유지(삼성 절전 완화) | `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 안내 |

- **요청하지 않는 민감 권한:** `RECORD_AUDIO`, `READ_CALL_LOG`, `READ_PHONE_STATE`.
  근거: 앱은 녹음/통화기록 접근을 하지 않고, 삼성이 이미 생성한 파일을 **읽기만** 한다.
- **대안 검토:** Android 13+의 `READ_MEDIA_AUDIO`만으로 가능할 수 있으나 삼성 통화녹음의 MediaStore 색인 신뢰성이 불확실하여 `MANAGE_EXTERNAL_STORAGE`를 기본 채택(D4).
- **부팅 후 재스케줄:** WorkManager가 주기작업을 자체 복원하므로 `RECEIVE_BOOT_COMPLETED` 불필요.

## 6. 감지 로직 상세

- 폴더 스캔 주기: `PeriodicWorkRequest(15, MINUTES)`, `ExistingPeriodicWorkPolicy.KEEP`.
- 파일 식별 키: `name:size:lastModified` — 파일명이 같아도 크기/수정시각이 다르면 다른 파일로 취급.
- 쓰기 완료 판정: `lastModified`가 60초 이상 과거인 파일만 발송(녹음 진행 중 파일 오발송 방지). 주기 스캔 특성상 실제로는 대부분 완료 상태.
- 중복 방지: 발송 성공 후에만 키를 `SentStore`에 저장. 실패는 저장하지 않아 다음 주기 자동 재시도.
- 누락 방지: 폴더 전체를 매 주기 스캔하므로, 특정 주기를 놓쳐도 다음 주기에 미발송 신규 파일을 다시 포착.

## 7. 이메일 발송 상세

- 서버: `smtp.gmail.com`, 포트 `587`, STARTTLS.
- 인증: `melt.road@gmail.com` + **앱 비밀번호**(2단계 인증 활성화 필요).
- 제목: `[통화녹음] <파일명>` (예: `[통화녹음] Call recording ...m4a`)
- 본문(텍스트): 파일명, 녹음 시각(파일 lastModified 기준), 파일 크기.
- 첨부: 해당 녹음 파일 원본. **원본은 삭제하지 않음**(보관).
- 크기 제한: 20MB 초과 시 발송 스킵 + 알림. (Gmail 한도는 메시지 전체 25MB이나 base64 인코딩으로 ~33% 증가하므로 원본 기준 20MB로 안전 컷) 발송기록에 남기지 않음(추후 수동 대응 가능하도록).

## 8. 설정 / 보안

- **앱 비밀번호**: 소스 하드코딩 금지. 설정 화면에서 1회 입력 → `EncryptedSharedPreferences`에 암호화 저장(D6).
- 발신/수신 주소·폴더 경로: 기본값은 코드 상수로 두되, 설정 화면에서 열람/수정 가능.
- 로그: 앱 비밀번호/이메일 본문을 로그캣에 출력하지 않음.

## 9. UI 명세 (화면 1개)

- **상태 영역:** 권한 3종 체크리스트(모든파일접근 / 알림 / 배터리예외), 마지막 발송 시각, 누적 발송 개수.
- **설정 영역:** 앱 비밀번호 입력 필드(마스킹), 발신/수신/폴더 경로(수정 가능).
- **버튼:** `[모든 파일 접근 허용]` `[알림 허용]` `[배터리 예외 설정]` `[지금 스캔]`(OneTimeWork) `[테스트 발송]`(설정 검증용 더미 메일).
- 최초 세팅 후에는 앱을 열지 않아도 백그라운드로 동작.

## 10. 엣지케이스 / 에러 처리

| 상황 | 처리 |
|---|---|
| 네트워크 없음 | `NetworkType.CONNECTED` 제약으로 실행 지연 + 실패 시 백오프 재시도 |
| 발송 실패 | `SentStore`에 기록 안 함 → 다음 주기 재시도 + 실패 알림 |
| 녹음 진행 중 파일 | `lastModified` 60초 룰로 제외 |
| 25MB 초과 | 스킵 + 알림 |
| 재부팅 | WorkManager 자동 복원 |
| 폴더 경로 상이(기기별) | 설정 화면에서 경로 수정 가능 |
| 권한 미허용 | 스캔 중단 + 알림/화면 안내 |
| 앱 비밀번호 오류 | 테스트 발송으로 사전 검증, 실패 알림 |

## 11. 기술 스택 / 빌드

- 언어/프레임워크: Kotlin, Coroutines, WorkManager.
- 라이브러리: `com.sun.mail:android-mail` + `android-activation`, `androidx.security:security-crypto`.
- SDK: minSdk 33(Android 13, One UI 5+ 대상과 일치) ~ targetSdk 34+. (`MANAGE_EXTERNAL_STORAGE`·`POST_NOTIFICATIONS`가 모두 정식 지원되는 최소 버전)
- 빌드/설치: Android Studio → 디버그 서명 APK → adb(USB) 또는 파일 전송 설치.

## 12. 리스크 / 기기에서 확인 필요

1. **정확한 폴더 경로/확장자**: `Recordings/Call` 및 `.m4a` 가정. 실제 기기에서 1회 확인(다르면 설정에서 수정).
2. **주기 지연**: 삼성 절전으로 15분 주기가 늘어질 수 있음(배터리 예외로 완화). 즉시성은 의도적으로 포기.
3. **앱 비밀번호 발급**: `melt.road@gmail.com` 2단계 인증 활성화 후 앱 비밀번호 생성 가능해야 함.
4. **통화 녹음 활성화/지역 제약**: 삼성 통화녹음이 켜져 있고 파일이 실제로 생성되어야 함.

## 13. 성공 기준 (Acceptance Criteria)

- [ ] 통화 종료 후(절전 상태에 따라) 대체로 15분 내 수신함에 첨부 이메일 도착.
- [ ] 동일 파일이 두 번 발송되지 않음.
- [ ] 네트워크 없을 때 발송이 유실되지 않고 연결 복구 후 발송됨.
- [ ] 앱을 열지 않아도, 재부팅 후에도 동작 지속.
- [ ] `RECORD_AUDIO`/`READ_CALL_LOG` 등 통화 민감 권한을 요청하지 않음.
- [ ] 테스트 발송 버튼으로 SMTP 설정을 사전 검증할 수 있음.

## 14. 향후 확장 (옵션, 현재 비범위)

- 실시간 감지가 필요해지면 `FileObserver` + 포그라운드 서비스로 전환(상시 알림·배터리 비용 발생).
- 발송 이력 화면, 다중 수신자, 파일명 규칙 기반 필터(특정 번호만) 등.
