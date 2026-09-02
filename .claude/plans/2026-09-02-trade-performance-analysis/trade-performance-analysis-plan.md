---
title: trade-performance-analysis — 운영 매매기록 실측으로 봇 알고리즘 개선 (데이터 먼저, 파라미터는 그 다음)
status: blocked
started: 2026-09-02
updated: 2026-09-02
---

# Goal

운영 봇(Upbit)의 실제 매매기록을 조회해 성과를 실측하고, "청산 사유 대부분이 `DAILY_RESET`
강제청산이라 기대값이 0 에 수렴한다"는 가설을 확정/기각한 뒤, 그 근거 위에서만 리스크
파라미터·전략을 고친다. **데이터 없이 파라미터를 건드리지 않는다**가 이 작업의 전제다.

# Progress

- 2026-09-02 — 사용자 질문("수익률 목표를 1%로 정해두면 안정적인가")에서 출발. 코드 실측으로
  현재 거래 1건의 손익 구조와 승률별 기대값을 계산하고, 목표 기간별로 무엇을 요구하는지 정리
  (아래 `# Decisions`). 운영 DB 조회 스크립트 11종을 준비해 사용자에게 실행을 요청한 상태에서
  세션 종료. **스크립트 원본은 scratchpad 에 있어 소실** → 스키마 근거로 재작성해 이 plan 디렉토리에
  `analyze_trades.sh` 로 보존(tracked).
- 2026-09-02 — 세션 인수인계용으로 worktree `trade-performance-analysis` 생성, 위 내용 기록.
  이전 세션이 보고한 "wiki 문서 불일치"는 재확인 결과 **오탐**(아래 `# Decisions` 마지막 항목).

# Next

1. 사용자가 아래를 직접 실행하고 출력을 붙여넣는다(운영 DB 접근이라 세션이 대신 못 함):
   ```
   bash .claude/plans/2026-09-02-trade-performance-analysis/analyze_trades.sh
   ```
2. 쿼리 `[3]` 청산 사유 분포에서 `DAILY_RESET` 비중과 `[4]` 사유별 승률·기대값을 본다.
   - `DAILY_RESET` 이 압도적 + 그 평균 pnl 이 0 근처 → 가설 확정. **익절/손절선보다 강제청산을 먼저 고친다.**
   - 아니면 → 가설 기각. `[6]` 전략별·`[5]` 티커별 성과로 원인 재탐색.
3. 개선안은 `BacktestEngine` 백테스트로 검증한 뒤에만 파라미터 변경(운영 `.env` 직접 수정 금지 —
   [[feedback_config_defaults_multi_layer]] 대로 코드·compose·.env 3계층 확인 후 재배포·로그 검증).

# Decisions

## 1) "목표 수익률을 1%로 정하면 안정적"은 성립하지 않는다

목표 숫자는 안정성을 만들지 않는다. 결정하는 건 셋이고 1%는 그중 어느 것도 아니다.

```
수익        = 거래당 기대값 × 거래 횟수
거래당 기대값 = (승률 × 평균이익) − (패률 × 평균손실)
변동성      = 손절폭 × 포지션크기 × 동시보유수
```

목표를 1%로 잡으면 "얼마나 자주·얼마나 높은 승률로 이겨야 하는가"라는 **요구조건**이 바뀔 뿐,
손실이 얼마나 크게 튀는지는 전혀 바뀌지 않는다.

## 2) 현재 거래 1건의 구조 (✅ 코드·배포 실측)

| 항목 | gross | 수수료 차감 후(net) |
|---|---|---|
| 익절 `TAKE_PROFIT` | +5.0% | +4.9% |
| 손절 `STOP_LOSS` | −5.0% | −5.1% |
| 트레일링 | 고점 −2% (+3% 도달 시 무장) | 가변 |
| 1회 매수액 | `min(주문가능 KRW × 0.15, 100,000원)` | — |

- 근거: `TradingProperties.kt` 기본값(`takeProfitPct 5.0` / `maxLossPct 5.0` / `trailingStopPct 2.0` /
  `trailingArmPct 3.0` / `maxHoldDays 1` / `roundTripFeeRate 0.001` / `maxInvestAmount 100_000.0`),
  `deploy/vultr/.env`(`TRADING_INVEST_RATIO=0.15`, TP/SL/트레일링은 코드 기본과 동일값을 명시).
- `TRADING_MAX_INVEST_AMOUNT` 는 `.env` 에 **없다**. `docker-compose.prod.yml` 은 `TRADING_*` 를
  이름만 나열(#75)해 `:-기본값` 덮어쓰기가 없으므로 코드 기본 **100,000원이 상한**이다.
  → 잔고 666,667원 이상이면 1회 매수액은 항상 10만원 고정.

## 3) 승률별 기대값 (TP/SL 만 발생한다고 단순화)

`EV(p) = p × 4.9 − (1−p) × 5.1 = 10p − 5.1` (포지션 기준, %)

| 승률 | 포지션 기준 EV | 계좌 기준(잔고 100만·1회 10만 = 10%) |
|---|---|---|
| 50% | −0.10% (수수료만큼 확정손실) | −0.010% |
| 51.0% | 0 (손익분기) | 0 |
| 55% | +0.40% | +0.040% |
| 60% | +0.90% | +0.090% |
| 65% | +1.40% | +0.140% |

R:R 이 1:1 이라 승률이 동전던지기면 수수료만큼 확정 손실이다.

> ⚠️ 직전 세션이 구두로 보고한 "손익분기 승률 50.5%"는 계산 슬립이다. `+4.9 / −5.1` 에서
> 손익분기는 `5.1 / 10 = 51.0%`. (50.5% 는 왕복수수료를 0.05%로 뒀을 때 값.)

## 4) 목표 기간별로 무엇을 요구하는가 (승률 60% = 계좌 +0.09%/거래 가정)

| 목표 | 필요 거래수 | 현재 구조에서 가능한가 |
|---|---|---|
| 연 1% | 연 11회 | ✅ 여유. 다만 코인 변동성 대비 목표가 낮아 봇을 돌릴 이유가 약하다 |
| 월 1% | 월 11회 (주 2.6회) | ✅ 티커 8종이므로 티커당 월 1.4회 — 현실적 |
| 일 1% | 일 11회 | ❌ 구조적으로 불가능. `maxHoldDays=1` + 티커당 하루 사실상 1회전 |
| 거래당 1% (계좌) | 포지션 +10% 필요 | ❌ 익절선 5% 로는 도달 불가 |
| 거래당 1% (포지션) | 익절선 5% → 1.1% | ⚠️ 가능하지만 **손절 5% 를 그대로 두면 손익분기 승률이 83.6% 로 뛴다** |

마지막 줄이 핵심이다. "1%면 작으니 안전하겠지"가 정확히 뒤집히는 지점 — 익절만 1%로 줄이고
손절을 5%로 두면 손익분기 승률 `5.1 / (1.0 + 5.1) = 83.6%`. 작은 이익을 자주 먹다가 한 번에
5번치를 잃는 구조라 **더** 불안정해진다.

## 5) 지금 가장 의심되는 구조적 문제 (⚠️추정 — 미확정 가설)

`TRADING_MAX_HOLD_DAYS=1` + 익절 +5% 조합. 하루 안에 5% 오르는 코인만 익절에 도달하고 나머지는
익일 09:00 `DAILY_RESET` 으로 강제청산된다. 이때 청산가는 사실상 랜덤이라 기대값이 0 에
수렴하는데 왕복 수수료 0.1% 는 확정으로 나간다.

→ 쿼리 `[3]`/`[4]` 로 확정한다. **가설이 맞으면 TP/SL 을 먼저 고쳐도 수익이 안 난다 — 강제청산이 먼저다.**

## 6) 직전 세션의 "wiki 문서 불일치" 보고는 오탐

`wiki/pages/concept/trading-engine-loop.md` 가 `takeProfitPct 2.0` / `trailingArmPct 0.0` 로
어긋나 있다고 보고했으나, 재확인 결과 그 페이지는 **2026-08-23 에 이미 5.0 / 3.0 으로 교정**됐고
같은 페이지 `[!conflict]` 블록이 그 교정 이력을 명시하고 있다. 조치 불필요.

# Key Files

- `.claude/plans/2026-09-02-trade-performance-analysis/analyze_trades.sh` — 운영 DB 읽기전용 SELECT 11종.
  원본 소실로 스키마 근거 **재작성본**(쿼리 문구는 원본과 다를 수 있다).
- `common/src/main/kotlin/com/trading/common/config/TradingProperties.kt` — 리스크 파라미터 기본값 단일 소스.
- `deploy/vultr/.env` — 운영 오버라이드. `TRADING_MAX_INVEST_AMOUNT` 부재(= 코드 기본 10만원).
- `deploy/vultr/docker-compose.prod.yml:59-75` — `TRADING_*` 를 이름만 전달(#75). `:-기본값` 덮어쓰기 없음.
- `bot/src/main/kotlin/com/trading/bot/engine/TradingEngine.kt:328-332` — 매도 사유 판정 순서
  (STOP_LOSS → TRAILING_STOP → TAKE_PROFIT → DAILY_RESET).
- `bot/src/main/kotlin/com/trading/bot/domain/TradeRecord.kt:38-45` — `SellReason` enum 6종.
- `bot/src/main/resources/db/migration/V1__create_trade_records.sql` — `trade_records` 스키마.
- `bot/src/main/resources/db/migration/V21__backfill_sell_strategy_and_pnl_amount.sql` — `strategy`/`pnl_amount`
  귀속 규칙. 엔진 BUY 행은 **증분이 아니라 포지션 누적 스냅샷**이라 합산 금지.
- `bot/src/main/kotlin/com/trading/bot/engine/BacktestEngine.kt` — 개선안 검증 수단.
- `wiki/pages/concept/trading-engine-loop.md` — 리스크 파라미터 표(코드와 일치, 2026-08-23 검증).

# Blockers

- **운영 DB 조회를 세션이 직접 못 한다.** `analyze_trades.sh` 는 `deploy/vultr/.state` 의 `PUBLIC_IP` 와
  `coin-trading-bot-key.pem` 으로 Vultr 인스턴스에 SSH 해 `docker compose exec postgres psql` 을 돈다.
  사용자가 실행하고 출력을 붙여넣어야 진행 가능 → `status: blocked`.
  (이 worktree 에 `.env`/`.state`/`.pem` 을 main 에서 복사해 뒀으므로 worktree 안에서 바로 실행된다.)

# Acceptance

1. `analyze_trades.sh` 11종 출력 확보 — 거래 건수·기간이 통계적으로 의미 있는 수준인지 먼저 판정.
2. 쿼리 `[3]`/`[4]` 로 `DAILY_RESET` 가설이 **확정 또는 기각**되고, 그 근거가 이 plan 에 수치로 기록됨.
3. 실측 승률·평균이익/손실로 현재 구조의 **실제 거래당 기대값**이 계산됨(위 이론값과 대조).
4. 개선안이 있다면 `BacktestEngine` 백테스트 결과로 뒷받침됨 — 추측 기반 파라미터 변경 0건.
5. 파라미터를 실제로 바꿨다면 코드·compose·`.env` 3계층 반영 + 재배포 후 로그로 적용 확인.

# Deferred

- (없음)

# Workflow Findings

- **scratchpad 에 만든 산출물은 세션과 함께 사라진다.** 사용자가 나중에 실행해야 하는 스크립트를
  `/private/tmp/claude-501/<session-id>/scratchpad/` 에 두면 세션 종료 시 소실되고, 다음 세션이
  같은 것을 다시 만들어야 한다(이번에 실제로 발생 — 재작성본은 원본과 쿼리 문구가 다르다).
  → 사용자 실행이 전제인 산출물은 tracked 위치(이 repo 는 `.claude/plans/<dir>/`)에 둔다.
