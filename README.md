# call-recording-mailer

갤럭시 통화녹음(`Recordings/Call`) 파일이 생성되면 자동 감지하여, 해당 파일을 첨부한 이메일로 전송하는 **개인용 경량 안드로이드 앱**.

- **발신:** melt.road@gmail.com (Gmail SMTP · 앱 비밀번호) → **수신:** yonggill@wishket.com
- **감지:** WorkManager 15분 주기 스캔 · **중복/누락 방지:** 발송기록 저장
- **권한:** 모든 파일 접근 · 알림 · 배터리 최적화 예외 (통화 민감 권한은 요청하지 않음)

## 문서
- 설계서: [`docs/superpowers/specs/2026-08-07-galaxy-call-recording-emailer-design.md`](docs/superpowers/specs/2026-08-07-galaxy-call-recording-emailer-design.md)

## 상태
설계 완료 → 구현 계획 작성 예정.
