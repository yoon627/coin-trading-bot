---
title: hold-limit-overrun-warn — 보유상한 초과 청산을 WARN 으로 드러낸다 (#131)
status: done
started: 2026-09-14
updated: 2026-09-14
---

# Goal

`maxHoldDays` 보다 늦게 `DAILY_RESET` 이 걸린 포지션(2026-07 에 3건, 원인 미확정)이 재발하면 로그만으로
알 수 있게 한다. 원인 조사(봇 중지·배포 공백)는 로그 보존 기간 밖이라 이 작업 범위 밖.

# Progress

- 2026-09-14 — worktree 생성(base `main@bd91317`). 이슈 판정 세션에서 첫 착수 항목으로 선택.
- 2026-09-14 — TDD Red→Green, code-reviewer(+codex high) Major 1·Minor 5 전부 반영(아래 Disposition), `exit-gates` wiki 동기화 + 검증 3종 통과.
- 2026-09-14 — 최종 검증 `:bot:test` 1047건 중 실패 1 = baseline(#193, 줄끝) 분리. 커밋 후 main 에 ff-merge.

# Next

없음 — 로컬 ff-merge 로 닫혔다. #131 은 감지 추가 코멘트 후 열어 둔다(원인 조사 미실시).

# Decisions

## 1) WARN 은 (ticker, buyDate) 당 1회

`decideSell` 은 포지션 보유 중 매 tick 호출되고, 매도 주문이 나갈 때까지(앞선 게이트가 먼저 걸리지 않는 동안)
`shouldSellForDailyReset` 이 계속 true 를 돌려준다. 매번 찍으면 5초마다 같은 줄이 쌓여 신호가 묻힌다.
같은 포지션(buyDate 동일)에 프로세스 수명 동안 한 번만 남긴다(맵은 인메모리 — 재시작·엔진 교체 시 한 번 더 남을 수 있다).

## 2) 경계는 `> holdLimit` (정확히 상한 = 정상)

`>= holdLimit` 에서 청산하는 것이 설계라 경과일 == 상한은 정상 발동이다. 초과분만 이상 신호.

# Review Disposition (code-reviewer + codex, 2026-09-14)

- **fix** 테스트가 초과 첫날(경과 2일)·새 buyDate 재경고·다른 ticker 독립을 안 봄 → 3 케이스 추가.
- **fix** 로그가 원인(다운타임)을 단정 → 관측값(held/limit/buyDate/tradingDate/user)만.
- **fix** userId 부재 → 생성자 `userId: Long? = null`, `createEngine` 에서 주입.
- **fix** 주석의 반복 이유가 부정확("체결까지" → "매도 주문이 나갈 때까지") → 수정.
- **fix** wiki 원인 단정·dedup 수명 과대 진술 → "원인 미확정"·"프로세스 수명 동안" 으로.
- **defer** 앞선 게이트(손절 등)가 같은 tick 에 먼저 걸리면 이 WARN 이 안 남는 탐지 구멍 → 관측을 `runSwing` 진입부로 옮기는 건 범위 밖. wiki 에 한계로 명시. ACCUMULATE 경로가 `decideSell` 을 안 거치는 것도 같은 줄에 기록.
- **fix** nit — 헬퍼 미사용 인자 제거·`appender.stop()`·라벨 `limit=`.

# Acceptance

1. 경과일 > 상한인 포지션에 `shouldSellForDailyReset` 이 true 를 돌려주며 WARN 을 1건 남긴다 — `DailyResetManagerTest` 통과.
2. 같은 포지션으로 두 번 호출해도 WARN 은 1건 — 같은 테스트.
3. 경과일 == 상한(정상 발동)엔 WARN 0건 — 같은 테스트.
4. `./gradlew :bot:test` 및 `compileKotlin` 통과.
5. `wiki/pages/concept/exit-gates.md` 에 WARN·한계 기술 + wiki 검증 3종(check_links·verify.sh·smoke.sh) 통과.

# Key Files

- `bot/src/main/kotlin/com/trading/bot/engine/DailyResetManager.kt` — 판정 + WARN
- `bot/src/test/kotlin/com/trading/bot/engine/DailyResetManagerTest.kt`
- `bot/src/main/kotlin/com/trading/bot/engine/UserTradingManager.kt` — `createEngine` 에서 userId 주입 1줄
- `wiki/pages/concept/exit-gates.md` — WARN·한계 기술

# Deferred

- `BacktestLegacyGoldenTest` 가 Windows fresh checkout(`core.autocrlf=true`)에서 CRLF/LF 차이로 실패 — baseline(골든 LF 정규화 시 통과 재현). 이슈 #193. 심각도 낮음(CI Linux 무관).

# Blockers

없음.
