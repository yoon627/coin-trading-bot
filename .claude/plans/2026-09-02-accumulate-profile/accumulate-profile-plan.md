---
title: accumulate-profile — 메이저 4종 분할매수·분할매도 적립 프로파일 + 알트 스윙 병존 + 알트 유니버스 자동 선정
status: done
started: 2026-09-02
updated: 2026-09-02
---

# Goal

1. **적립 프로파일**(BTC·ETH·XRP·SOL): 떨어지면 단계적으로 더 사고, 오르면 단계적으로 판다. 코인당 예산 10만원 상한, 손절 없음, 09:00 강제청산 없음.
2. **알트 스윙 프로파일**: 기존 익절·손절·트레일링·보유상한 그대로. 종목은 Upbit 24h 거래대금 상위에서 자동 선정(투자유의·스테이블·적립 종목 제외).
3. 실거래 투입 전 **백테 확장으로 파라미터 근거 확보** — 선택 규칙을 사전 등록하고, 기준 미달이면 어떤 값도 채택하지 않는다.
4. 코드 머지 = 자동 배포(`.github/workflows/deploy.yml`, `paths-ignore` 로 plan/wiki/docs 만의 커밋은 제외)이므로 **기본값은 두 기능 모두 off** — `.env` 를 바꿔야 켜진다.

# Progress

- 2026-09-02: 사용자 요청("9시 청산 이상, 종목 확대, 메이저는 떨어질수록 사고 오를수록 팔기") → 코드·wiki·오늘 오전 실측(SELL 42건, `trade-performance-analysis` plan) 대조 후 4개 결정 수령(범위=메이저 적립+알트 스윙 병존 / 코인당 예산 10만 / 손절 없음·예산 상한만 / 알트는 거래대금 상위 자동). worktree `accumulate-profile` 생성. Explore(subagent) 완료.
- 2026-09-02: architecture-reviewer(planning) REQUEST CHANGES 12건 → 전부 수용(`[arch-N]`). plan-reviewer(+codex medium, 완주) CONDITIONAL — 필수 6·권장 14·누락 시나리오 6 → 전부 수용(`[pr-N]`). 아래 Decisions 가 개정본.

- 2026-09-02: 사용자 plan 승인(A~E 진행). **Phase A 완료**(`ebf87b3`, 17 테스트) · **Phase B 완료**(백테 + 격자, 7 테스트). 격자 결과(fixture 7 = bear BTC·ETH·XRP·SOL + bull BTC·XRP·SOL, 예산 10만, 수수료 편도 0.05%):

  | 후보 5/3/3 | bear 중앙값 | bear B&H | bull 중앙값 | bull B&H | worst MDD | 평균 노출 | 판정 |
  |---|---|---|---|---|---|---|---|
  | 봉당 1액션 | −19.9% | −29.0% | +27.3% | +96.1% | 37.0% | 0.86 | 통과 |
  | 봉당 다단(10) | −22.7% | −29.0% | +33.2% | +96.1% | 37.7% | 0.88 | 통과 |

  per-fixture(봉당 1): bear XRP −33.0(B&H −42.9) · BTC −14.5(−22.8) · ETH −12.6(−26.6) · SOL −25.3(−31.5) / bull XRP −12.7(−15.7) · BTC +27.3(+96.1) · SOL +50.5(+200.9). 전체 격자 표는 `AccumulateBacktestTest` 출력(JUnit XML system-out).
  **해석**: (1) 사전 등록 규칙은 형식상 통과했으나 **비판별적**이었다 — 봉당 1액션에서 27/27 통과. 하락장 비교 대상이 전액 B&H 라 부분 노출 전략은 거의 자동으로 "덜 잃는다". (2) 하락장에서 노출(보유 원가/예산)이 0.91~0.99 로 예산이 초반에 소진되고 반등 없이 끝나 −13~−33% 를 그대로 맞는다(손절 없음의 대가). (3) 상승장에선 "오를수록 판다"가 상승분을 반납해 B&H 의 1/3~1/4 만 먹는다(노출 0.60~0.75 — codex 지적으로 노출을 `budget−cash` 에서 보유 원가로 바꾼 뒤 값, 실현이익이 노출을 음수로 깎던 왜곡 제거). (4) 격자 간 차이는 대부분 노출 차이로 설명된다 — stepDown 5·rungs 8 이 하락 손실을 가장 줄이지만 상승도 가장 덜 먹는다. **결론: 후보 5/3/3 유지(규칙대로), 단 이 백테는 "하락에서 B&H 보다 덜 잃고 상승에서 덜 번다"는 프로파일 확인이지 수익성 우월 근거가 아니다.** 사용자에게 이 트레이드오프를 Report 에서 명시한다.

- 2026-09-02: **Phase C 완료**(`4a952fe`) — `buy()/buyRung()` 진입점 분리, `sellVolume()`, 4경로 공용 `sellTransition()`, rung 체결비율 조건부, `LadderStateMapper`, dispatch, `reservedKrw`, D1 REST 60초 캐시, V23. 신규 25 테스트 + 회귀 191 통과, `scripts/run-db-tests.sh` 3건/skip 0. **Phase D 완료**(`61e7a20`) — `UniverseSelector`(싱글톤, `publicUpbitClient`, `getMarkets` 추가), `applyTickers`/`refreshUniverse`, `bot_state.tickers` 불변 테스트. 신규 10 테스트. **Phase E 완료** — 배포 3계층 7키, README·PROJECT_ANALYSIS(V22·V23 행 추가, 리스크 기본값 오기재 +2%→+5% 교정)·spec·wiki(신규 `accumulate-ladder` + 3페이지) — wiki 3종 검증 통과. wiki 페이지·spec 파일명은 브랜치명 `accumulate-profile` 과 겹쳐 smoke 음성검사에 걸려 `accumulate-ladder` 로 바꿨다.
- 2026-09-02: 전체 스위트 `./gradlew test` 통과 — 846 실행 / skip 9(전부 기존 조건부: DB·네트워크·플래그 게이트 6 스위트, DB 분은 run-db-tests.sh 로 별도 실행).

- 2026-09-02: 구현 리뷰 2건(architecture-reviewer 정밀 + code-reviewer) 처분·수정 완료(`af01873`, `# Review Disposition`). 수정 중 테스트가 추가 버그를 잡음 — `formatVolume` 의 `BigDecimal(double)` 이 0.0003 을 0.00029999 로 깎아 주문 수량이 한 자리 모자랐다(`valueOf` 로 교정, 회귀 테스트). simplify: 프로덕션 미사용 `AccumulateProperties.enabled` 제거(`112708d`). 최종 검증은 격리 runner 로 실행 중.

- 2026-09-02: **최종 검증(격리 runner) 통과** — `./gradlew test --rerun-tasks` 851 실행 / 0 실패 / skip 9(기존 조건부 6 스위트), `scripts/run-db-tests.sh` 3건/skip 0, wiki 3종 clean(35 페이지, smoke 10/10), 배포 키 3계층 등록·`deploy.sh` 문법 OK, working tree clean. Acceptance 전 항목 증거 대조 완료(아래 표 체크).

- 2026-09-02: `/e merge` 첫 push 가 pre-push codex 에 BLOCK(P1 2·P2 2) — 유니버스 잔류 티커 재진입 차단(`swingUniverse`), 추가 단 잔고 복원 오판(`pending_buy_prior_volume`), 매수 90% 규칙↔정합 모순 해소(매수는 체결 시 한 단), 백테 노출을 보유 원가로. 전부 fix.
- 2026-09-03: 2차 push 도 codex BLOCK(P1 3·P2 3): auto 재시작 시 durable 행 미시딩으로 자동 선정 티커의 보유·pending 유실 / 수동 청산 판정 시 flatPeak 미재앵커(같은 tick 재매수) / 기동 시 refreshUniverse 가 루프 복구 경계 밖 / 복원 상태에 resetDaily 미적용 / DailyCandleCache 가 짧은 이력 응답을 miss 로 처리 / 90% 미만 부분 매도 누적 시 rung 미차감. 전부 fix(원가 기반 rung 상한을 매 tick 정합에 추가, 테스트 6건).
- 2026-09-03: 3차 push 도 codex BLOCK(P1 3·P2 1): 첫 선정 실패 시 swingUniverse=null 로 durable 잔재 전부 진입 가능 / 보호 집합에 unsynced 누락 / 예산 실측이 locked 제외 / 추가 단 placeBuy 가 clearEntryMeta 무조건 호출. 전부 fix(테스트 4건).
- 2026-09-03: 4차 push 는 P0/P1 없이 P2 4건(ack 가능) — 축소 수량 최소주문 재검사·재시작 시 durable 행 선별 시딩·캐시 동시 miss 합치기·사다리 파라미터 유한성/100% 미만 검증. 전부 fix(테스트 3건).
- 2026-09-03: 5차 push codex BLOCK(P1 1·P2 2): 메타 없는 실보유 durable 행이 재시작 때 탈락(→ 계좌 1회 조회로 되살림) / 수동 매매 후 사다리 입력 미갱신(→ 60초 주기 syncPosition) / 추가 단 체결 fallback 이 기존 보유를 덮음(→ 체결분+주문 전 보유량). 전부 fix(테스트 3건).
- 2026-09-02: 사용자가 마무리(push·PR·머지) 선택 → `/e merge`. 브랜치 push → PR → 머지 → worktree·브랜치 정리. 머지가 거부되면 `in_progress` 로 복구.

# Next

없음 — 머지로 종결. 켜는 절차는 README 참조. push·PR·머지·worktree 정리는 `/e`. 머지 후 켜는 절차는 README "적립 프로파일·자동 유니버스를 처음 켤 때"(pg_dump → `.env` 7키 → 재배포 → 로그에서 "Ladder reconciled"·"Universe refreshed" 확인).

# Decisions

## 1) 적립 사다리(ladder) 규칙 — `common` 소유 순수 함수 `[arch-1]`

`common/.../strategy/AccumulateLadder.kt` 가 입력·출력 값 타입을 함께 정의한다(`TradingState` 는 `bot` 모듈이라 `common` 이 참조 불가).

```kotlin
data class LadderParams(budgetKrw, maxRungs, stepDownPct, stepUpPct) { val rungAmountKrw = budgetKrw / maxRungs }
data class LadderInput(rungsFilled, lastActionPrice, flatPeak, avgBuyPrice, holdVolume, price)
sealed interface LadderAction { Buy(amountKrw, triggerPrice) ; Sell(volume, triggerPrice, isFinal) ; Hold }
fun decide(input, params): LadderAction
```

| 규칙 | 식 |
|---|---|
| 단당 금액 | `budgetKrw / maxRungs`. 5,000원(Upbit 최소주문) 미만이면 `AccumulateProperties.init` 에서 기동 거부 |
| 첫 진입 (rungs=0) | `price <= flatPeak × (1 − stepDownPct)`. `flatPeak` 는 **직전 판정까지의** 무포지션 고점(look-ahead 방지 `[pr-8]`), 0 이면 현재가로 초기화 |
| 추가 매수 | `rungs < maxRungs && price <= lastActionPrice × (1 − stepDownPct)` **and** `avgBuyPrice × holdVolume + 단당금액 <= budgetKrw` `[arch-4]` — 예산 상한은 rung 카운트가 아니라 실측 원가 |
| 부분 매도 | `rungs > 0 && holdVolume > 0 && price >= max(avgBuyPrice, lastActionPrice) × (1 + stepUpPct)` → `holdVolume / rungs` 매도. `rungs == 1` 이면 `isFinal`(전량, 거래소 잔고 원문 문자열로 주문) |
| 최소주문 게이트 `[pr-3]` | 매도 대금 `price × volume < 5,000` 이면 `Hold`(rung 차감 없음). 수량은 `BigDecimal` 8자리 `DOWN` + `toPlainString()` |
| 청산 게이트 | 손절·트레일링·익절·보유상한 **전부 미적용** |

- **`lastActionPrice` = `decide()` 가 소비한 트리거가(`triggerPrice` = 그 tick 의 `currentPrice`)** `[pr-1]`. 체결가가 아니다 — `completeBuy` 의 `fillPrice` 는 거래소 누적 평단이고 `Order` DTO 엔 VWAP 이 없다. 평단을 쓰면 단이 쌓일수록 스텝 간격이 압축돼 백테(트리거가)와 다른 사다리가 된다. 실체결과의 괴리는 로그로만 남긴다.
- `max(avgBuyPrice, lastActionPrice)` 매도 기준: 물타기 직후엔 평단 위에서만 팔아 손실 매도를 막고, 연속 매도 구간엔 "오를수록 더" 판다.
- `flatPeak`: 전량 매도 후 기준가를 직전 매도가로 두면 상승장에서 영영 재진입 못 한다. 무포지션 고점 대비 눌림 진입이 옵션 없이 재진입을 보장한다. 갱신은 `max(flatPeak, price)` 이며 **0 일 때만** 초기화(재기동마다 깎이지 않게 `[pr-2]`).
- 단당 금액은 **균등**. "떨어질수록 더" 는 누적 투입으로 실현된다.
- 기본 파라미터 후보 `maxRungs=5`, `stepDownPct=3`, `stepUpPct=3` — **Phase B 백테로 확정**(Decision 2 의 사전 등록 규칙).

## 2) 백테 — 별도 `AccumulateBacktest`, `BacktestEngine` 은 손대지 않는다 `[arch-10]`

- **판정은 `AccumulateLadder.decide` 호출**(규칙 이중구현 금지 — `ExitGates` 공유 규약과 동형). `TradingProperties`·`strategies` 를 받지 않는다. **기본값 parity 테스트**(`AccumulateProperties` ↔ 백테 기본 파라미터).
- 입력 fixture 는 최신순이므로 `run()` 안에서 뒤집는다 `[pr-9]`.
- 봉 처리: 진입 판정의 `flatPeak` 은 직전 봉까지(`[pr-8]`), 판정 후 그 봉 `high` 로 갱신. 매수 트리거는 봉 `low`, 매도 트리거는 봉 `high`, 체결가 = 트리거가, **왕복 수수료 0.1% 를 매 액션 반영**. 한 봉에서 둘 다 닿으면 매수 우선. `maxActionsPerBar` 로 봉당 1 액션(기본)과 다단 진행(라이브 10초 tick 근사) 둘 다 돌려 민감도 보고 `[pr-7]`.
- 산출: 예산 대비 순수익률(종료 시 mark-to-market, 수수료 차감), 최대 투입액, **MDD = 현금 포함 equity(예산 − 투입원가 + 실현 + 보유×종가)의 peak-to-trough / 예산** `[codex]`, 거래수, B&H(예산 lump-sum) 대비.
- **사전 등록 선택 규칙 `[pr-10]`** (데이터 보기 전에 고정): 격자 rungs {4,5,8} × down {2,3,5} × up {2,3,5}, 7 fixture(bear BTC·ETH·XRP·SOL + bull BTC·XRP·SOL). 1차 지표 = fixture 별 순수익률의 **중앙값**, 제약 = 어느 fixture 도 MDD > 40% 이면 탈락. tie-break = 적은 rungs → 큰 stepDown. **채택 조건: 상승장 3 fixture 중앙값 > 0 이고 하락장 4 fixture 중앙값이 B&H 중앙값보다 나을 것**(적립의 존재 이유 = 하락에서 B&H 보다 덜 잃고 상승에서 번다). 미달이면 후보 기본값(5/3/3)을 "근거 없음" 표기와 함께 유지하고 사용자에게 보고한다 — 격자 최적값을 기본값으로 올리지 않는다(과적합).
- 한계: 국면 2개·200봉·마켓 상관 0.49·ETH 상승장 없음. 방향 관찰이지 처방이 아니다([[reset-churn-measurement]] 규율).

## 3) 라이브 통합 — 전이는 원자 커밋 안, 진입점 분리, dispatch 한 곳

- `AccumulateProperties`(`trading.accumulate.*`, `common/config`): `tickers`(기본 빈 값 = off), `budgetKrw=100000`, `maxRungs=5`, `stepDownPct=3.0`, `stepUpPct=3.0`. `init` 에서 단당 금액 ≥ 5,000. **전역 env 라 모든 사용자 엔진에 같은 예산으로 적용된다**(`createEngine` 이 per-user) `[pr-18]` — README 에 명시.
- **dispatch `[arch-9]`**: `processTicker` = 공용 preamble(가격·unsynced·pendingPersist·pendingBuy/Sell reconcile) → `when (profileOf(ticker)) { SWING -> runSwing(); ACCUMULATE -> runAccumulate() }`. 기존 peak 갱신·flush 는 `SWING && position` 조건으로 preamble 의 현재 위치에 남긴다. **`flatPeak` flush 는 `runAccumulate` 안**(무포지션 구간에 갱신되므로 `if (position)` 블록을 재사용할 수 없다 `[pr-17]`): `updateFlatPeak()` 이 true 이거나 `peakPersistFailed` 면 `persistPeak`(전체 스냅샷 upsert, 같은 재시도 플래그). `profileOf` 는 `applyTickers` 가 만든 Set — 적립 티커가 사용자 목록에도 있으면 **ACCUMULATE 우선 + distinct**, 그 결과 스윙 대상이 0 이면 WARN `[pr-누락2]`.
- **사다리 durable 필드(`rungsFilled`·`lastActionPrice`)는 `commitFillAndApply` 의 `applyTransition` 안에서만 바뀐다** `[arch-2]`.
- **rung 증감** `[pr-4]` → **변경(codex pre-push P2, 2026-09-02)**: 매수는 체결이 있으면 `rungs++`(시장가 매수는 잔량 환불로 종결, 미달을 안 세면 매 tick 정합이 원가 기반으로 한 단 복원해 모순), 매도만 `executed / pendingSellVolume >= 0.9` 일 때 `rungs--`. 미달 매도는 rung·`lastActionPrice` 유지. **잔고 복원은 주문 전 보유량(`pending_buy_prior_volume`, V23)을 넘는 증분만 체결로 인정**(codex P1 — 추가 단은 주문 전부터 코인이 있어 잔고 존재만으로 확정하면 미체결 주문이 지워진다).
- **매도 전이는 즉시·reconcile 공용 함수 하나** `[arch-3]`: `sellTransition(executed, requested, remaining, reason)`. `reason == ACCUMULATE_STEP` 이면 위 비율 판정으로 rung 차감 + `lastActionPrice = pendingSellTriggerPrice` + 잔량 유지, 아니면 기존 `markSold`/부분유지. `completeSellAtomically`·`applySellFillOutcome`(부분·전량) 3곳이 모두 이 함수를 쓴다. 트리거가는 `pendingSellTriggerPrice`(V23) 로 durable.
- **매수 진입점 분리 `[arch-8]`**: 주문 이후 공용부를 `placeBuy(ticker, state, amountKrw, strategyName, triggerPrice)` 로 추출. `buy()` = 기존 5중 가드 + `calculateInvestAmount`, `buyRung(amount)` = `position` 가드만 제외한 동일 가드(`entryBlocked(state, allowExisting)`). **`buyRung` 은 주문 직전 거래소 계좌를 다시 읽어 예산 게이트를 실측으로 재판정**한다(런타임 수동매매로 state 가 낡아도 상한이 뚫리지 않게 `[pr-누락6]`). 적립 경로는 `buyDate·peakPrice·exitParams·boughtToday` 를 읽지 않는다 — `clearEntryMeta`/`markBought` 가 이 값을 바꾸는 부작용은 프로파일 전환 시에만 드러난다(아래 컷오버).
- **재시작·컷오버 정합 `[arch-4][pr-2]`**: `TradingState → LadderInput` 매퍼가 **프로세스당 티커별 1회**(복원·`applyTickers` 시딩 직후) 적용. `holdVolume > 0 && rungs == 0` → `rungs = ceil(avg×hold / 단당).coerceIn(1, maxRungs)`, **`lastActionPrice = avgBuyPrice`**, WARN("기존 보유를 사다리로 편입"). `holdVolume <= 0 && rungs > 0` → `rungs = 0, lastActionPrice = 0`, WARN("장부와 잔고 불일치 — 수동 청산 추정"). `flatPeak` 는 0 일 때만 초기화. 운영 `.env` 가 BTC·ETH 를 스윙으로 들고 있으므로 **적립을 켜는 순간 이 편입 경로가 실제로 발동**한다 — 의도된 컷오버.
- **역방향 컷오버**(적립 티커를 끄면): rung 상태를 가진 포지션이 즉시 스윙 게이트(손절 −5%·09:00 청산)를 받고 `buyDate` 는 마지막 단 매수일이다. README 운영 절차에 "끄기 전 수동 정리 또는 감수" 명시 `[pr-누락1]`.
- **KRW 경쟁 `[pr-6]`**: 엔진이 `reservedKrw = Σ(적립 티커별 max(0, budget − avg×hold))` 를 계산해 스윙 `buy()` 에 넘기고, `calculateInvestAmount(krw − reservedKrw)` 로 사이징한다(알트가 적립 예산을 침범하지 못한다). 적립 rung 이 KRW 부족으로 skip 되면 **WARN**(기존 `debug` 아님) + `/api/bot/status` 에 `accumulate_skipped` 노출.
- **부분 매도 수량 경로**: `sellVolume(ticker, state, volume, reason, triggerPrice)` 추가. 주문 수량 = `min(volume, sellable)` 을 `BigDecimal(8, DOWN).toPlainString()`, `isFinal` 이면 기존 `sell()` 처럼 거래소 원문 문자열 전량.
- durable(V23, 컬럼 추가만): `rungs_filled INT NOT NULL DEFAULT 0`, `last_action_price DOUBLE PRECISION NOT NULL DEFAULT 0`, `flat_peak DOUBLE PRECISION NOT NULL DEFAULT 0`, `pending_sell_trigger_price DOUBLE PRECISION`. `position/avg/holdVolume` 은 종전대로 거래소 복원.
- 기록: 단 매수 = BUY(엔진 스냅샷 규약 유지), 단 매도 = SELL(`volume` = 판 수량, `reason = ACCUMULATE_STEP`, `pnlPercent` 평단 대비 net, `strategy = "accumulate"`).
- **집계 `[pr-11]`**: `/api/strategies/performance` 는 `strategy="accumulate"` 행이 분리되지만 합산 방식이 부분매도에 맞지 않으므로 README 에 해석 한계 명시. 리더보드 `aggregateSellStatsByUser` 는 strategy 무관 합산이라 **`strategy <> 'accumulate'` 를 제외**한다(스윙 전략 리더보드라는 의미 유지). SPA `SELL_REASON_LABEL` 에 `ACCUMULATE_STEP: '적립 매도'` 추가 `[pr-12]`.
- 엔진 테스트는 relaxed mock 특성상 **`verify(exactly = 0)` 로 호출 부재를 단언** `[arch-11]`. A1 의 mutation 검증은 도구가 없으므로 경계값 테스트로 대체 `[pr-16]`.

## 4) 알트 유니버스 자동 선정 — 싱글톤 서비스, 사용자 의도 컬럼 불변

- **`UniverseSelector` 는 싱글톤 `@Service`** `[arch-7]`, 기존 인증 없는 싱글톤 `publicUpbitClient` 빈(`WebClientConfig.kt:32`)을 주입받는다 `[pr-15]` — raw WebClient 를 직접 쓰면 `retryOnRateLimit`·에러 매핑을 잃는다. 그래서 **`UpbitClient` 에 `getMarkets(): List<MarketInfo>`(`/v1/market/all?is_details=true`)를 추가**한다(relaxed mock 이라 기존 테스트 무영향). 순위는 `getTicker` 배치의 `acc_trade_price_24h`. 순수 선정 함수 `select(markets, tickers, exclude, n)` 와 fetch 분리, 짧은 TTL 캐시로 엔진들이 공유.
- 제외: `market_event.warning == true`(투자유의; `caution` 객체의 "주의" 는 제외하지 않는다 — 사용자 결정은 유의만), 스테이블(`PointInTimeUniverse.STABLECOINS` 를 `common` 상수로 승격), 적립 티커. `altCount` 기본 8, 상한 16.
- **`bot_state.tickers` 는 사용자 의도만 저장** `[arch-6]`. 합집합은 `TradingEngine.applyTickers()` 안에서만, `saveState` 에는 원본 인자.
- **`applyTickers(next)` 한 메서드로 봉인** `[arch-5]`: (1) 보유·pending 티커 강제 포함, (2) **총수 20 상한을 여기서 직접 적용**(순위 낮은 알트부터 제외, 보유 티커는 제외 안 함 `[pr-13]`), (3) 신규분 `states.computeIfAbsent` + `syncPosition` + 사다리 매퍼, (4) 제거분 `states.remove`(미보유·미pending), (5) `activeTickers` 스왑. `start()` 도 경유.
- 갱신: 기동 시 + KST 09:00 경계(`checkAndReset` true tick). 재시작 첫 tick 에도 true 라 배포마다 장중 갱신되는데, 이는 "기동 시 갱신"과 같은 의미라 의도된 동작 `[pr-19]`. 조회 실패 시 직전 목록 유지 + WARN.
- 시세: ingestion 은 `watchlist.tickers` 를 부팅 시 한 번 잡는다. watchlist 밖 티커는 REST 폴백인데 tick 마다 `getTicker` + `getDayCandles(60)` 이라 알트 16 × 2 / 10s ≈ 3 req/s `[pr-14]`. **엔진에 D1 REST 캔들 60초 TTL 캐시**를 둔다(ingestion 의 캔들 주기와 동일) → 티커 1.6/s + 캔들 0.27/s. ingestion 동적 구독은 `# Deferred`.

## 5) 알트 스윙의 09:00 청산은 이번 범위에서 바꾸지 않는다

오늘 오전 실측·[[reset-churn-measurement]] 모두 "리셋 제거가 개선"이라는 근거를 주지 못했고 관망 결정(`trade-performance-analysis` plan Decisions 8)이 살아 있다. 메이저는 적립 프로파일로 리셋 자체를 벗어난다. 알트의 `TRADING_MAX_HOLD_DAYS` 는 `.env` 로 조정 가능.

## 6) 기본값 off · 배포 3계층 · 롤백 `[pr-5]`

- `.env.example`·`docker-compose.prod.yml` 이름 목록·`deploy.sh` `TRADING_OVERRIDE_KEYS` 에 7키 등록(`TRADING_ACCUMULATE_TICKERS`·`_BUDGET_KRW`·`_MAX_RUNGS`·`_STEP_DOWN_PCT`·`_STEP_UP_PCT`, `TRADING_UNIVERSE_AUTO`·`_ALT_COUNT`). 값은 `TRADING_VALUE_PATTERN='^[A-Za-z0-9._, -]+$'` 에 맞는다.
- **롤백 1차 경로 = forward-off**: `TRADING_ACCUMULATE_TICKERS=`·`TRADING_UNIVERSE_AUTO=false` 로 재기동. 이미지를 되돌리지 않는다 — `deploy.sh:491-497` 은 migration 포함 배포의 자동 롤백을 막고(`MIGRATION_GATE=blocked`), 구버전 이미지는 Flyway validate 가 V23 을 모른다고 기동 실패할 가능성이 있으며(⚠️추정, 미실행), 구버전은 `pending_sell_reason=ACCUMULATE_STEP` 을 `MANUAL` 로 읽어 전량 청산으로 확정한다. **머지 전 수동 `pg_dump`**(야간 `backup.sh` 와 별개) 를 운영 절차에 넣는다. V23 컬럼 DROP 은 롤백 절차가 아니다.
- **"기본값 off = 동작 불변" 의 증거**는 기존 테스트 통과가 아니라 `TradingEngineTest` 의 명시 단언: off 상태에서 `getMarkets` 호출 0, `applyTickers` 결과 = 입력 목록, 주문 호출 시퀀스가 기존과 동일.

## 7) 구현 순서 (각 Phase 독립 커밋, TDD)

| Phase | 내용 | 신규/변경 파일 |
|---|---|---|
| A | 사다리 순수 로직 + 설정 | `common/.../config/AccumulateProperties.kt`, `common/.../strategy/AccumulateLadder.kt`, `common/.../domain/Stablecoins.kt`, `AccumulateLadderTest`·`AccumulatePropertiesTest` |
| B | 백테 + 격자 | `bot/.../engine/AccumulateBacktest.kt`, `AccumulateBacktestTest`(선택 규칙 인코딩·parity·결과표 출력) |
| C | 라이브 통합 | `TradingState.kt`(+4 필드, `updateFlatPeak`), `bot/.../engine/LadderStateMapper.kt`, `V23__trading_states_accumulate.sql`, `TradingStateService`, `PositionManager.kt`(`placeBuy`·`buyRung`·`sellVolume`·`sellTransition`·`entryBlocked`·reservedKrw), `TradingEngine.kt`(preamble/`runSwing`/`runAccumulate`/`profileOf`/D1 캐시/reservedKrw), `TradeRecord.kt`(`ACCUMULATE_STEP`), `TradeRecordRepository`(리더보드 제외), `UserTradingManager`(설정 주입·status 노출), SPA 라벨, 테스트 `TradingEngineTest`·`PositionManagerExtendedTest`·`TradingStateRoundTripTest`(DB)·`LadderStateMapperTest` |
| D | 알트 유니버스 | `UpbitClient.getMarkets`(+Impl·`MarketInfo`), `bot/.../engine/UniverseSelector.kt`, `common/.../config/UniverseProperties.kt`, `TradingEngine.applyTickers()`, `UserTradingManager`(selector 주입), `UniverseSelectorTest`·`TradingEngineTest`·`UserTradingManagerTest` |
| E | 배포·문서 | `.env.example`·compose·`deploy.sh`, README(기능·환경변수·운영 절차·집계 한계), `PROJECT_ANALYSIS.md`, spec `docs/superpowers/specs/2026-09-02-accumulate-ladder-design.md`, wiki(`trading-engine-loop`·`exit-gates` 갱신, 신규 `accumulate-profile`, `index.md`) |

# Key Files

- `common/src/main/kotlin/com/trading/common/config/TradingProperties.kt` — 기존 리스크 파라미터 단일 소스(선례)
- `bot/src/main/kotlin/com/trading/bot/engine/TradingEngine.kt` — `processTicker`(:231-308), `start()`(:74-96), REST 폴백(:234,:292)
- `bot/src/main/kotlin/com/trading/bot/engine/PositionManager.kt` — `buy()` 가드(:127-153)·`completeBuy` fillPrice=평단(:332)·`commitFillAndApply`(:367-388)·`sell()`(:527-611)·`applySellFillOutcome`(:619-669)·`completeSellAtomically`(:716)
- `bot/src/main/kotlin/com/trading/bot/domain/TradingState.kt`, `bot/.../persistence/TradingStateService.kt`(:70-97 매핑·`decodeSellReason` MANUAL 폴백), `bot/.../domain/Order.kt`(`price`=요청 KRW, `executedVolume`)
- `bot/src/main/kotlin/com/trading/bot/engine/UserTradingManager.kt` — `startBot`(:206-228)·`createEngine`(:359-384)
- `bot/src/main/kotlin/com/trading/bot/config/WebClientConfig.kt:32` — `publicUpbitClient` 싱글톤
- `bot/src/main/kotlin/com/trading/bot/persistence/TradeRecordRepository.kt:36-67` — 리더보드·전략 집계
- `bot/src/main/resources/static/tide-app/screens.jsx:338-341` — `SELL_REASON_LABEL`
- `bot/src/main/kotlin/com/trading/bot/engine/BacktestEngine.kt` — 손대지 않음
- `bot/src/test/kotlin/com/trading/bot/engine/BacktestFixtures.kt`(최신순), `bot/src/test/resources/backtest/{bear,bull}/`
- `bot/src/test/kotlin/com/trading/bot/engine/PointInTimeUniverse.kt` — 스테이블 목록·"불완전 스냅샷 판정 금지"
- `deploy/vultr/deploy.sh`(:161-185 키·패턴, :491-500 migration gate, :571-579 자동롤백 제외), `deploy/vultr/docker-compose.prod.yml:59-75`

# Blockers

없음.

# Acceptance

| # | 충족 조건 | 검증 | 기준 |
|---|---|---|---|
| ✅ A1 | `decide` 가 Decision 1 표대로(첫 진입·flatPeak 직전값·추가매수·실측 예산 상한·부분매도·rungs=1 전량·5,000 게이트·재진입) | `AccumulateLadderTest`(mock 0, 경계값) | 통과 |
| ✅ A2 | 단당 금액 < 5,000 이면 기동 거부 | `AccumulatePropertiesTest` | 예외 |
| ✅ B1 | 백테가 fixture 를 시간순으로 돌고 7 fixture × 27 격자 × 봉당 1/다단 결과표 산출, **사전 등록 선택 규칙으로 채택/미채택 판정** | `AccumulateBacktestTest` | 판정 결과·근거를 `# Progress` 에 기록 |
| ✅ B2 | 백테 기본 파라미터 = `AccumulateProperties` 기본값 | parity 테스트 | 통과 |
| ✅ C1 | 적립 티커는 손절·익절·트레일링·09:00 청산·boughtToday 게이트 미호출 | `TradingEngineTest` `verify(exactly=0)` | 통과 |
| ✅ C2 | rung 전이가 커밋 람다 안에서, 즉시 done / wait→reconcile / cancel+부분(≥90%·<90%) 경로가 같은 규칙 | `PositionManagerExtendedTest` | rung·lastActionPrice 기대값 일치 |
| ✅ C3 | 실측 예산 상한·주문 직전 재판정·KRW 부족 시 WARN+status | `PositionManagerExtendedTest`·`UserTradingManagerTest` | 통과 |
| ✅ C4 | 재시작·컷오버 정합(rung=0·잔고>0 편입 / rung>0·잔고=0 리셋 / flatPeak 0 초기화만) 1회 적용 | `LadderStateMapperTest` + `TradingStateRoundTripTest`(V23, DB) | 통과, `scripts/run-db-tests.sh` 실행 N건/skip 0 |
| ✅ C5 | 스윙 `buy()` 가 reservedKrw 를 뺀 잔고로 사이징 | `PositionManagerExtendedTest` | 통과 |
| ✅ D1 | 선정이 warning·스테이블·적립 제외 상위 N, 실패 시 직전 유지 | `UniverseSelectorTest` | 통과 |
| ✅ D2 | `applyTickers` 가 보유·pending 유지, 20 상한, 신규 시딩+매퍼, 제거분 정리 | `TradingEngineTest` | 통과 |
| ✅ D3 | `bot_state.tickers` 에 자동 선정 결과가 쓰이지 않음 | `UserTradingManagerTest` | 통과 |
| ✅ E1 | 기본값 off 동작 불변: `getMarkets` 0회·`applyTickers`=입력·주문 시퀀스 동일 + `./gradlew test` 전체 + `legacy-golden`. **예외 1건(의도)**: watchlist 밖 티커의 D1 REST 폴백에 60초 캐시(`DailyCandleCache`)가 붙는다 — 신선도는 store 경로와 같고 레이트리밋 보호 목적 | 명시 단언 + 전체 스위트 | 통과 |
| ✅ E2 | 배포 3계층 7키 등록 | grep | 7키 × 3곳 |
| ✅ E3 | 문서 동기화 + wiki 검증 3종 + plan 커밋(tracked) | 실행 | 통과 |

# Review Disposition

- architecture-reviewer(planning) 12건: 전부 **fix**(`[arch-N]`).
- **구현 리뷰(2026-09-02)** — architecture-reviewer(정밀, Blocker 1·Major 1·Minor 7) + code-reviewer(Claude 단독 — codex 는 repo PreToolUse hook 이 `codex exec` 를 스테이징 diff 6패턴 점검 전용으로 게이트해 브랜치 리뷰 프롬프트를 거부, Major 2·Minor 5·Nit 5). 처분:
  - **fix** 마지막 단 90~99% 부분체결 → rung 0·잔고>0 영구 Hold(양쪽 Blocker/Major): `sellTransition` 잔량 분기에서 ladder 는 rung ≥ 1 + `LadderStateMapper.reconcile` 을 1회가 아니라 매 tick(정합 상태 no-op) 호출. 회귀 테스트 추가.
  - **fix** `applyTickers` 빈 상태 시딩(Major): `TradingStateService.loadState` → `PositionManager.loadState` 로 durable 복원본 시딩. 테스트 추가.
  - **fix** D1 REST 캐시 엔진별(Major): 싱글톤 `DailyCandleCache`(`publicUpbitClient`) 로 이동, 엔진엔 nullable 주입(null 이면 종전대로 직접 호출 — 기존 엔진 테스트 불변).
  - **fix** flatPeak 초기화 미영속 / phantom 경로 flatPeak 미재앵커 / 미체결 매수 분기 trigger 미정리 / `'accumulate'` SQL 리터럴 → `const` 보간 / `UniverseSource.NONE` 이중 스위치 → nullable / `internal enum` 이름이 API 값 → `profileNameOf` / `UniverseSelector` 미사용 `properties` 제거 / `MAX_ACTIVE_TICKERS` → `SWING_UNIVERSE_CAP`(하드 상한 아님을 이름·문서에) / `AccumulateBacktest` → `src/test` / 라이브·백테 전이 차이 KDoc / `AccumulateProperties`·`PeggedAssets` Upbit 전용 KDoc / `getMarkets` MockWebServer 역직렬화 테스트.
  - **false-positive** `action.volume >= sellable` 전량 승격(cr-P2.5): 잠긴 몫은 `heldVolume` 규약상 우리 포지션이 아니라 free 를 다 팔면 장부 청산이 맞다(스윙과 동일 의미). 코드는 `min(volume, sellable)` 로 단순화하고 테스트로 의미를 고정.
  - **wontfix** 백테 평단 수수료 포함(cr-P3.11): 모델링 선택, KDoc 에 차이 명시.
  - **wontfix** `PROJECT_ANALYSIS.md` 리스크 기본값 +2%→+5% 교정이 범위 밖(cr-P3.12): 사실 교정이라 유지, Report 에 명시.
  - **defer** `getTicker` 콤마 인코딩·100종 배치 상한(open question): 2026-09-02 세션에서 같은 엔드포인트를 100종 배치 3회로 실호출해 287 마켓 전부 응답받았다(✅ 실측) — Spring `QUERY_PARAM` 인코딩은 `,` 를 허용한다. 자동화 테스트는 없음 → `# Deferred`.
  - **defer** 제거된 티커의 `trading_states` 행 무정리, 런타임 수동매매 주기 reconcile → `# Deferred`.
- plan-reviewer(+codex) 필수 6·권장 14·누락 6: 전부 **fix**(`[pr-N]`). codex 의 "불일치 시 자동 추정 대신 halt" 는 **wontfix** — 컷오버(스윙 보유 → 사다리 편입)가 의도된 동작이라 halt 면 첫 배포에서 4종 전부 멈춘다. 대신 WARN + 예산 실측 게이트로 상한을 지킨다. `assembleRoundTrips` 는 Deferred 유지하되 서술을 codex 지적대로 정정.

# Deferred

- `UniverseSelector` 의 `/v1/ticker` 100종 배치 호출은 실호출로만 확인(2026-09-02, 287 마켓 3배치) — MockWebServer 로 콤마 인코딩·배치 분할을 고정하는 테스트 없음(낮음, `UniverseSelector.kt`).
- `applyTickers` 로 제거된 티커의 `trading_states` 행이 남는다 — 정리 경로 없음(낮음, 운영 데이터로 판단).
- `assembleRoundTrips`(`api/TradeRoundTrip.kt:163-167`): "SELL 뒤 BUY = 새 그룹" 규칙에서 엔진 BUY 가 전체 잔고 스냅샷이라 **앞 그룹 잔량이 다음 그룹에 이중 표시될 수 있다**(표시 계층, 중). 사다리 도입 후 실데이터로 확인해 별도 작업.
- 자동 유니버스 티커의 시세를 store(WS)가 아니라 REST 폴백으로 받는다 — ingestion 동적 구독은 별도 작업(중, `MarketDataIngestionService.kt`).
- 런타임 수동매매는 `syncPosition` 이 탐지하지 못한다(기동·unsynced 때만). 적립은 주문 직전 실측으로 상한을 지키지만 rung 카운트는 어긋날 수 있다 — 주기적 reconcile 은 별도 작업(중).
- `.env` 의 `TRADING_STRATEGY=volatility_breakout` 과 DB 매도 기록 `combined` 불일치 — 런타임 `setStrategy` 이거나 미배포. 서버 로그로 확인 필요(중, 운영 설정).

# Workflow Findings

- wiki `smoke.sh` 음성검사는 **실재 브랜치명**을 페이지 본문에서 찾는다 — 작업 브랜치와 같은 stem 으로 wiki 페이지·spec 파일을 만들면 오탐(`accumulate-profile` → `accumulate-ladder` 로 개명해 해소). 새 페이지 stem 은 브랜치명과 다르게 짓는다.
- repo PreToolUse hook 이 `codex exec` 를 스테이징 diff 6패턴 점검 전용으로 게이트해 code-reviewer 의 브랜치 diff codex 병행 리뷰가 막혔다(plan-reviewer 단계의 codex 는 통과). 규약(§9)과 hook 의 범위가 어긋난다 — `/improve` 판정 대상.
