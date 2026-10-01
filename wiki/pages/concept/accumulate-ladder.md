---
title: 적립 프로파일 — 메이저 코인 사다리 매매
category: concept
created: 2026-09-02
updated: 2026-10-01
claim_state: current
verified: 2026-10-01 — 알트 유니버스 자동 선정·dormant 되살리기 제거(MVP 2단계): 유니버스 절을 걷고 남는 규칙(`bot_state.tickers` 저장·watchlist 밖 REST 폴백)은 trading-engine-loop 로 옮김 · 2026-10-01 — 백테 절·`AccumulateBacktest` sources 삭제(백테스트 코드 제거), `buyTriggerPrice` 는 이제 `AccumulateLadder` 안에서만 쓰여 private · 2026-09-30 — 계좌 조회가 실패하는 동안 선정이 시딩한 dormant 티커는 조회가 회복돼도 도는 state(pending 포함)가 남고, 선정 반영이 중간에 멈춰 활성 밖에 남은 보유 dormant 는 활성에 붙으며, 선정되지 않은 보유 dormant 는 되살아남을 `TradingEngineUniverseTest` 로 확인(#260, 수정 전 Red), 변형 3종(수정 전·건너뛰기만·전부 거름) 검출 · 2026-09-29 — 멈춘 엔진의 flush 가 `peakPersistFailed` 인 고점을 pending 재기록 뒤에 다시 씀을 `TradingEngineTest` 로 확인(#244 후속), 변이 4종 전부 검출 · 2026-09-29 — 실행 중 `startBot` 이 저장된 행과 같으면 쓰지 않음을 `UserTradingManagerTest` 로 확인(#228) · 2026-09-28 — reconcile 로 늦게 확정된 매도(uuid·identifier·적립 단·부분 체결 뒤 2차 매도)의 기록 price·pnl·totalAmount 가 판단 tick 가격(`pending_sell_trigger_price`)이고 옛 pending 은 확정 tick 가격으로 떨어짐을 `PositionManagerExtendedTest`·`PositionManagerUnknownOrderTest`·`PositionManagerAccumulateTest` 로 확인, 변이 2종 검출 · 2026-09-27 — 목록 밖 사다리 행 제외·WARN(첫 단 pending·적립 설정 안 티커 비대상 포함)과 흔적 판정의 auto 무관 적용은 `TradingEngineUniverseTest`(#226)와 뮤테이션으로 확인, 실행 중 start 409·reload 입력은 `UserTradingManagerTest` · 2026-09-16 — 귀속 불명 락 시 `releaseHoldings` 가 사다리 장부를 남기는 것은 `PositionManagerExtendedTest`(#122)로, `unsynced` 중 `runAccumulate` skip 은 `TradingEngine.kt:528` 코드로 확인 · 2026-09-02 — AccumulateLadder.kt·AccumulateBacktest.kt·TradingEngine.kt(runAccumulate/applyTickers)·PositionManager.kt(buyRung/sellVolume/sellTransition)·LadderStateMapper.kt·UniverseSelector.kt 전문, V23 을 실제 Postgres 에 적용(scripts/run-db-tests.sh 3건/skip 0), AccumulateBacktestTest 격자 출력
sources:
  - common/src/main/kotlin/com/trading/common/strategy/AccumulateLadder.kt
  - common/src/main/kotlin/com/trading/common/config/AccumulateProperties.kt
  - bot/src/main/kotlin/com/trading/bot/engine/LadderStateMapper.kt
  - bot/src/main/kotlin/com/trading/bot/engine/TradingEngine.kt
  - bot/src/main/kotlin/com/trading/bot/engine/UserTradingManager.kt
  - bot/src/main/kotlin/com/trading/bot/engine/PositionManager.kt
  - bot/src/main/resources/db/migration/V23__trading_states_accumulate_ladder.sql
  - docs/superpowers/specs/2026-09-02-accumulate-ladder-design.md
---

# 적립 프로파일

티커별로 [[trading-engine-loop]] 의 스윙 규칙 대신 **사다리**로 매매하는 두 번째 프로파일이다. `trading.accumulate.tickers` 에 적은 티커만 해당하고 기본은 비어 있다(off). 메이저(BTC·ETH·XRP·SOL)를 전제로 설계했다 — 상장폐지·−90% 가 실제로 일어나는 알트에 물타기는 예산을 다 태운다.

## 규칙 (`AccumulateLadder.decide`, `common`)

| 규칙 | 식 | 기본값 |
|---|---|---|
| 단당 금액 | `budgetKrw / maxRungs` — 5,000원 미만이면 `LadderParams` 생성 거부 | 100,000 / 5 |
| 첫 진입 | `price <= flatPeak × (1 − stepDown)` — 무포지션 구간 고점(직전 판정까지) 대비 눌림 | 3% |
| 추가 매수 | `rungs < max && price <= lastActionPrice × (1 − stepDown) && avg×hold + 단당 <= budget` | 3% |
| 부분 매도 | `price >= max(avg, lastActionPrice) × (1 + stepUp)` → `hold / rungs`, 마지막 단은 전량 | 3% |
| 최소주문 | 매도 대금 < 5,000 이면 Hold(rung 유지) | — |
| 청산 게이트 | [[exit-gates]] 전부 미적용 — 상한은 예산 하나 | — |

비자명한 지점:

- **`lastActionPrice` 는 체결가가 아니라 트리거가**(판정 tick 의 현재가)다. 거래소는 누적 평단만 주고 `Order` DTO 에 VWAP 이 없다. 평단을 기준으로 쓰면 단이 쌓일수록 간격이 압축된다.
- **예산 상한은 rung 수가 아니라 실측 원가**(`avg × hold`)다. `buyRung` 은 주문 직전 거래소 계좌를 다시 읽어 판정한다 — 수동 매매로 장부가 낡아도 상한이 뚫리지 않는다. 이때 수량은 매도 가능분이 아니라 **계좌 총보유(locked 포함)** 다 — 수동 지정가·출금 대기로 잠긴 코인도 이 예산으로 산 것이고, 빼고 재면 손절 없는 프로파일의 유일한 상한이 뚫린다. rung 은 매도 분할 단위만 담당한다.
- **`flatPeak`** 이 없으면 전량 매도 후 상승장에서 영영 재진입 못 한다(직전 매도가 대비 눌림이 안 온다). 0 일 때만 현재가로 초기화한다 — 재기동마다 깎이면 첫 진입이 계속 미뤄진다.
- **귀속 불명 락(출금 대기·사용자 직접 주문)으로 free 가 0 이 되면** 단 매도의 phantom 경로가 보유만 내리고(`releaseHoldings`, 사다리 장부·진입 메타 유지) `unsynced` 를 켠다(#122, 2026-09-16). `unsynced` 동안 `runAccumulate` 는 통째로 쉬므로 락이 걸린 채로는 추가 단이 들어가지 않고, 락이 풀려 코인이 돌아오면 재동기화가 재편입한다 — 장부가 남아 있어 기준가(`lastActionPrice`)도 그대로다.
- **장부와 잔고가 어긋나면 거래하지 않는다**(`hasBalance != hasRungs` → Hold). 정합은 아래 매퍼의 몫이다. 잔고 입력 자체는 **60초마다 `syncPosition(clearWhenEmpty = true)` 으로 다시 읽는다** — 거래소(앱·웹)에서 직접 한 매매는 `TradingState` 를 건드리지 않아 그 사이 장부가 낡는다. 확인된 무잔고는 포지션 해제로 반영한다. 동기화 시각은 성공했을 때만 기록한다 — 실패를 완료로 적으면 preamble 의 일반 재시도(clearWhenEmpty=false)가 차단만 풀고 옛 보유가 남는다(스윙 기본 경로는 감사 기록 없는 청산을 피하려 phantom 정리를 `sell()` 에 맡기지만, 적립은 "보유"로 남으면 다음 하락에 단을 사 수동 청산을 되돌린다).
- **추가 단 체결 뒤 계좌를 못 읽으면** 체결분에 주문 전 보유량(`pending_buy_prior_volume`)을 더해 반영한다 — 체결분만으로 `replace` 하면 기존 단이 장부에서 사라진다.

## 라이브 통합

- **dispatch**: `processTicker` 는 공용 preamble(가격·unsynced·pendingPersist·pendingBuy/Sell reconcile) 뒤 `profileOf(ticker)` 로 `runSwing`/`runAccumulate` 를 가른다. 트레일링 고점 flush 는 SWING 만, 무포지션 고점(`flatPeak`) flush 는 ACCUMULATE 만 — 둘 다 "갱신 tick 만 + 실패 시 `peakPersistFailed` 재시도" 규약. 재시도는 도는 엔진이면 다음 tick 에서, 멈춘 엔진이면 그 states 를 버리거나 DB 값으로 덮기 전의 flush(`flushUnpersisted`, pending 재기록 뒤)에서 한다([[trading-engine-loop]] #244).
- **사다리 장부는 체결 커밋 트랜잭션 안에서만 바뀐다.** `rungsFilled`·`lastActionPrice` 는 `commitFillAndApply` 의 전이 람다에서만 갱신된다. 밖에서 올리면 "매수 기록됐는데 rung 그대로" 크래시 창에서 같은 단을 다시 산다.
- **매수는 체결이 조금이라도 있으면 한 단, 매도는 요청 대비 ≥ 90% 체결일 때만 한 단 소모.** 매수를 비율로 걸지 않는 이유: 시장가 매수(ord_type=price)는 잔량 환불로 종결돼 미달이 드물고, "미달이면 안 센다"는 다음 tick 의 장부 정합(원가 기반 rung 추정)이 어차피 한 단으로 복원해 규칙이 서로 모순됐다 — 총 투입은 실측 원가 예산 게이트가 막는다. 매도는 `pendingSellVolume` 대비 체결량으로 판정하며 미달이면 rung·기준가 유지, 잔량은 다음 tick 재평가(10% 체결로 한 단을 지우면 사다리가 어긋난다).
- **getOrder 장애 시 잔고 복원은 주문 전 보유량(`pending_buy_prior_volume`)을 넘는 증분만 이 주문의 체결로 본다.** 추가 단은 주문 전부터 코인이 있으므로 잔고 존재만으로 "체결"로 확정하면 미체결 주문이 사라지고 rung 이 헛되이 오른다.
- **매도 전이는 `sellTransition()` 하나** — 즉시 done·reconcile 부분·reconcile 전량·잔고 복원 4경로가 공유한다. 사유·요청수량·트리거가가 durable pending(`pending_sell_reason`·`pending_sell_volume`·`pending_sell_trigger_price`)에 있어 재시작 뒤 reconcile 도 같은 판정이 난다. `pending_sell_trigger_price` 는 스윙 매도도 판단가로 채운다(2026-09-28, #235 — 매도 기록가) — 사다리 전이는 사유(`ACCUMULATE_STEP`)로 가르고, "값이 있으면 사다리" 표식은 매수 쪽 `pending_buy_trigger_price` 뿐이다. 이전엔 부분체결 분기가 rung 을 몰라 같은 단을 반복 매도할 수 있었다(플랜 리뷰 blocker).
- **진입점 분리**: `buy()` 는 기존 5중 가드(`entryBlocked`) + `investRatio` 사이징, `buyRung()` 은 `position` 가드만 제외한 같은 가드 + 단당 금액. 플래그로 가드를 우회하지 않는다. 주문 이후 공용부는 `placeBuy` — 진입 메타(`buyDate`·`entryStrategy`·`exitParams`)는 **신규 진입일 때만** 지운다. 추가 단에서 지우면 미체결(cancel+0)로 끝났을 때 영구 유실돼, 프로파일을 끈 뒤 보유상한 청산이 날짜를 잃는다.
- **정합(`LadderStateMapper.reconcile`)은 매 tick 돈다 — 정합 상태에서는 no-op 이라 사람이 고친 장부를 덮지 않는다.** `hold>0 && rungs==0` → 실측 원가로 rung 추정(`ceil(원가/단당)`, 상한 max) + `lastActionPrice = avg` + WARN("편입"). 운영 `.env` 가 BTC·ETH 를 스윙으로 들고 있어 **적립을 켜는 순간 이 경로가 실제로 발동**한다 — 의도된 컷오버. `hold<=0 && rungs>0` → 비움 + `flatPeak` 를 현재가로 재앵커 + WARN(수동 청산 추정 — 옛 고점을 남기면 같은 tick 에 첫 단이 들어가 청산을 되돌린다). `rungs != ceil(원가/단당)` → 원가가 말하는 단수로 조정 — 아래로는 90% 미만 부분 매도가 반복돼 잔고는 줄어도 rung 이 안 줄어 단당 매도 대금이 최소주문 아래로 내려가는 것을, 위로는 수동 추가 매수로 원가가 늘었는데 rung 이 모자라 다음 상승에 전량(isFinal)이 나가는 것을 막는다(단 원가가 예산을 넘는 편입 포지션은 상향하지 않는다 — 원가 기준이 늘 maxRungs 라 매도로 줄인 rung 을 매번 되돌린다). 장부는 원가의 함수다(단 매수는 매번 단당 KRW, 분할 매도는 원가를 1/n 씩 줄이므로 정상 경로에서는 늘 일치). 올림 허용치는 매도 rung 소모 기준(`SELL_FILL_RATIO` 0.9)과 짝(1 − 0.9)이다 — 90% 체결로 지운 단(원가 x.1)이 되살아나지 않고, 89% 체결로 남긴 단(x.11)은 남는다. 부분 매도 reconcile 에서 취소 잔량의 unlock 이 늦어 거래소 기준 잔량이 과소면 주문 전 보유(`pending_sell_prior_volume`, durable — 재시작 뒤 `holdVolume` 은 이미 과소 동기화돼 있다) − 체결량을 하한으로 쓴다(60초 동기화가 이후 실측으로 맞춘다). 비최종 단의 매도 수량은 주문 문자열로 절삭된 값(8자리)을 장부·기록에도 쓴다. 런타임에 장부와 잔고가 갈라져도(부분체결·수동 매매) 다음 tick 에 스스로 맞춘다 — 적립엔 다른 청산 게이트가 없어 여기 말고는 풀 곳이 없다. 마지막 단이 90~99% 체결돼 잔량이 남으면 `sellTransition` 이 rung 을 1 로 유지한다.
- **현금 경쟁**: 적립이 아직 투입하지 않은 예산 `Σ max(0, budget − avg×hold)` 를 스윙 `buy()` 사이징에서 뺀다(`reservedKrw`). 단이 예산·KRW 부족으로 건너뛰어지면 사유가 바뀔 때만 WARN 하고 `/api/bot/status.positions[].accumulate_skip` 에 노출한다.
- **역방향 컷오버**: 적립 티커를 끄면 남은 포지션이 즉시 스윙 게이트(손절 −5%·09:00 청산)를 받는다 — 그 티커가 사용자 목록에 있을 때다. `buyDate` 는 마지막 단 매수일이다. 사용자 목록에도 없으면 `start()` 가 그 사다리 행(`rungsFilled>0`·단 매수 `pendingBuyTriggerPrice`·`entryStrategy=accumulate`)을 잔류로 싣지 않고 WARN 으로 알린다(#226 — 목록 밖 잔류 규칙이 쌓아 온 단을 시장가로 팔지 않게).
- **기록**: 단 매수는 기존 BUY 스냅샷 규약([[trade-record-volume-semantics]]), 단 매도는 `reason=ACCUMULATE_STEP`·`strategy=accumulate`·`volume=판 수량`. 편입된 스윙 포지션이어도 적립 규칙으로 팔았으면 `accumulate` 몫이다. `/api/strategies/performance` 는 SELL 행 `pnl_percent` 단순 합산이라 부분 매도가 잦은 이 프로파일에서 과대계상된다.
- **durable(V23)**: `rungs_filled`·`last_action_price`·`flat_peak`·`pending_buy_trigger_price`·`pending_buy_prior_volume`·`pending_sell_trigger_price`·`pending_sell_prior_volume`([[persistence-schema]]). 잔고·평단은 종전대로 거래소 복원.

## 롤백

1차 경로는 **forward-off** — `TRADING_ACCUMULATE_TICKERS` 를 비우고 재기동. 이미지를 되돌리지 않는다: `deploy.sh` 는 마이그레이션 포함 배포의 자동 롤백을 막고(`MIGRATION_GATE=blocked`), 구버전은 V23 을 모르며 `pending_sell_reason=ACCUMULATE_STEP` 을 `MANUAL` 로 읽어 전량 청산으로 확정한다([[deployment-stack]]). 켜기 전 수동 `pg_dump`.
