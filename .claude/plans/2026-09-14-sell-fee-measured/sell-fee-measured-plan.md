---
title: sell-fee-measured — 엔진 매도 수수료를 paid_fee 실측으로 기록한다 (#148)
status: done
started: 2026-09-14
updated: 2026-09-14
---

# Goal

`trade_executions.fee` 가 엔진 매수는 실측(#133)인데 매도는 설정값 추정이라 같은 라운드트립 안에서 기준이 섞인다(#148).
엔진 매도가 이미 쥐고 있는 `awaitFill`/`getOrder` 응답의 `paid_fee` 를 **실측 우선**으로 기록한다(불가 시 종전 추정 유지 — 행 단위로 실측/추정을
구분하는 마커는 DB 에 없다).
`pnl_amount` 는 백테와 맞춘 수수료율 기준을 **유지**한다(게이트·백테 정합이 목적이라 실측으로 바꾸지 않는다).

# Progress

- 2026-09-14 — worktree 생성(base 로컬 `main@2cfbf44`). 이슈 판정 세션 2번째 착수 항목.
- 2026-09-14 — TDD Red(실측 2건 실패)→Green(110/110). `sellFeeBasis` 헬퍼로 즉시·reconcile 경로 실측화, TradePnl KDoc·wiki 3페이지 갱신, wiki 검증 3종 통과.
- 2026-09-14 — code-reviewer(+codex high) Minor 7 → fix 5·defer 1·wontfix(범위) 1(Disposition). 테스트 5건(112/112), 최종 `:bot:test` 1051건 중 실패 1 = baseline #193. 커밋 후 로컬 ff-merge.

# Next

없음 — 로컬 ff-merge 로 닫혔다. #148 은 엔진 경로 실측화 완료 + 남은 한계(basis 마커 부재·수동 경로) 코멘트 후 close.

# Intent

- Problem: 매수=실측·매도=추정 혼합이라 `SUM(fee)` 로 라운드트립 수수료를 집계하면 기준이 섞인다. 소비자는 아직 코드 밖(수기 조회)뿐이라 지금은 조용하다.
- Constraints: 청산 게이트·`pnl_percent`/`pnl_amount` 의 net 기준(설정 수수료율)은 바꾸지 않는다 — 백테 `feeRate×2` 와 맞춘 값이라 실측으로 바꾸면 백테–라이브 정합이 깨진다. 수동 경로는 범위 밖(사용자: 수동매매 미사용 전제).
- Out of scope: `pnl_amount` 를 실측 수수료로 재계산 · 수동 주문의 fee · `trade_executions.fee` 과거 행 소급.
- Open questions: 없음.

# Decisions

## 1) 매도에서 `paid_fee` 가 없으면 Unrecorded 가 아니라 Estimate 로 떨어뜨린다

매수가 Unrecorded 로 두는 이유는 `totalAmount` 가 포지션 전체 원가라 추정하면 과대계상되기 때문이다(#133).
매도의 `totalAmount` 는 이 체결의 대금(가격×수량)이라 추정 기준이 맞다 — 기존 동작이 그것이고, 실측이 없을 때 그보다 나쁜
값(0)으로 바꿀 이유가 없다. 실측이 있으면 실측, 없으면 종전 추정.

## 2) 잔고복원(`recoverSellFromBalance`) 경로는 Estimate 그대로

주문 응답이 없어 실측할 수 없다. 이 경로는 getOrder 장애 시 예외 경로다.

# Review Disposition (code-reviewer + codex, 2026-09-14)

- **fix** `saveAudit` 주석 "매수=실측·매도=추정 혼합" 이 거짓이 됨 → 현재 출처 규칙으로 갱신.
- **fix** wiki `upbit-api` 근거절이 매수 전용(포지션 원가)인데 주어만 매도로 넓힘 → 매도 이유(기준 혼재 제거) 분리, cancel 캐비앳에 매도 reconcile 포함.
- **fix** wiki `trade-record-volume-semantics` "잔고복원만 추정" 자기모순 → 경로별로 정확히 서술 + 마커 부재 명시.
- **fix** reconcile 테스트가 부분체결 cancel 을 안 탐 → cancel+executed 실측 테스트 추가.
- **fix** `buildSellRecord`/`completeSellAtomically` 의 `feeBasis` 기본값(TradeRecord KDoc 의 기본값 금지 규율 위반) → 제거, 잔고복원에 명시 + 고정 테스트.
- **defer** reconcile 의 `getOrder` 성공 후 후처리 실패가 광범위 catch 로 잔고복원(추정)에 떨어져 실측을 버림 → 변경 전에도 같은 값이라 회귀 아님. `# Deferred`.
- **wontfix(범위)** DB 에 fee basis 마커가 없어 매도 실측/추정 구분 불가 → Goal 문구를 "실측 우선"으로 축소, #148 코멘트에 한계 명시.
- 검토 후 유지 — `paid_fee` 없으면 Estimate(Unrecorded 아님): 리뷰어·codex 합의.

# Acceptance

1. 즉시 done 매도(`sell`)가 `getOrder` 응답의 `paid_fee` 를 `FeeBasis.Measured` 로 기록 — `PositionManagerExtendedTest` 통과.
2. reconcile 경로(`reconcilePendingSell`, terminal+executed>0)도 Measured — 같은 파일.
3. `paid_fee` 가 없거나 파싱 불가면 Estimate(종전 동작) — 같은 파일(기존 테스트 갱신). 잔고복원 경로도 Estimate 고정 테스트.
4. `./gradlew :bot:test` 및 `compileKotlin` 통과(#193 baseline 제외).
5. wiki `trade-record-volume-semantics`·`persistence-schema`·`upbit-api` 의 "매도는 추정" 서술 갱신 + 검증 3종 통과.

# Key Files

- `bot/src/main/kotlin/com/trading/bot/engine/PositionManager.kt` — `buildSellRecord`·`completeSellAtomically`·`applySellFillOutcome`
- `bot/src/main/kotlin/com/trading/bot/domain/TradePnl.kt` — `estimatedFee` KDoc(매도 서술)
- `bot/src/test/kotlin/com/trading/bot/engine/PositionManagerExtendedTest.kt`
- `bot/src/main/kotlin/com/trading/bot/engine/TradeExecutionService.kt` — `saveAudit` fee 주석
- `wiki/pages/concept/trade-record-volume-semantics.md` · `wiki/pages/concept/persistence-schema.md` · `wiki/pages/entity/upbit-api.md`

# Deferred

- `reconcilePendingSell` 의 catch 범위: `getOrder` 성공 뒤 `findAccount` 등 후처리 예외가 `recoverSellFromBalance` 로 떨어져 실측 fee 를 추정으로 확정한다(pending 도 해소). 금전·포지션 손상 없음, 한 행의 fee 정확도. 심각도 낮음. `PositionManager.kt` reconcilePendingSell.

# Blockers

없음.
