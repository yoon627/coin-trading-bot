---
title: shadow-startup-log — 관측 활성 여부를 로그로 드러낸다
status: in_progress
started: 2026-09-06
updated: 2026-09-06
---

# Goal

그림자 관측이 실제로 켜졌는지를 **로그만으로** 알 수 있게 한다. 지금은 SSH 로 컨테이너 `printenv` 를
봐야만 확인되고, 첫 `[shadow-exit]` 발동 로그는 몇 주 뒤일 수 있다.

# Progress

- 2026-09-06 — worktree 생성(base `main@64f3f58`). `UserTradingManager.shadowExitObserver()` 에
  on/off 를 둘 다 INFO 로 남기고, on 이면 후보 파라미터도 함께 찍는다. `.env.example`·wiki 동기화.

# Next

PR #175 머지·배포 후 운영 로그에서 `[shadow-exit] 관측 on` 을 실제로 확인한다.

# Decisions

## 1) off 도 로그로 남긴다

"안 찍혔다"가 "꺼졌다"인지 "코드가 그 지점을 안 지났다"인지 구분되어야 한다. 둘 다 남기면 침묵 자체가 이상 신호가 된다.

## 2) 전달 계층이 넷이라 테스트만으로는 부족하다

`TradingEnvPassthroughTest` 는 **목록 누락**만 잡는다. 시크릿 값 자체가 안 들어갔거나 배포가 옛 이미지면
테스트는 통과하고 관측만 조용히 꺼진다 — 그건 런타임 신호로만 잡힌다.

# Key Files

- `bot/src/main/kotlin/com/trading/bot/engine/UserTradingManager.kt` — 관측기 생성부
- `deploy/vultr/.env.example` · `wiki/pages/entity/deployment-stack.md` — 확인 방법 기술

# Blockers

없음.

# Acceptance

1. ✅ 기동 시 on/off 가 INFO 로 남고, on 이면 후보 파라미터가 함께 찍힌다.
2. ✅ `./gradlew build` 통과(실행 984 / skip 19 / 실패 0), wiki 검증 3종 통과.
3. ⏳ 운영 배포 후 실제 로그에서 `[shadow-exit] 관측 on` 확인.
4. ✅ 라이브 매매 무영향 — 로그만 추가.

# Deferred
