---
title: 적립 프로파일 — 메이저 코인 사다리 매매와 알트 유니버스 자동 선정
category: concept
created: 2026-09-02
updated: 2026-09-02
claim_state: current
verified: 2026-09-02 — AccumulateLadder.kt·AccumulateBacktest.kt·TradingEngine.kt(runAccumulate/applyTickers)·PositionManager.kt(buyRung/sellVolume/sellTransition)·LadderStateMapper.kt·UniverseSelector.kt 전문, V23 을 실제 Postgres 에 적용(scripts/run-db-tests.sh 3건/skip 0), AccumulateBacktestTest 격자 출력
sources:
  - common/src/main/kotlin/com/trading/common/strategy/AccumulateLadder.kt
  - common/src/main/kotlin/com/trading/common/config/AccumulateProperties.kt
  - common/src/main/kotlin/com/trading/common/config/UniverseProperties.kt
  - bot/src/test/kotlin/com/trading/bot/engine/AccumulateBacktest.kt
  - bot/src/main/kotlin/com/trading/bot/engine/LadderStateMapper.kt
  - bot/src/main/kotlin/com/trading/bot/engine/UniverseSelector.kt
  - bot/src/main/kotlin/com/trading/bot/engine/TradingEngine.kt
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

- **`lastActionPrice` 는 체결가가 아니라 트리거가**(판정 tick 의 현재가)다. 거래소는 누적 평단만 주고 `Order` DTO 에 VWAP 이 없다. 평단을 기준으로 쓰면 단이 쌓일수록 간격이 압축돼 백테(트리거가)와 다른 사다리가 된다.
- **예산 상한은 rung 수가 아니라 실측 원가**(`avg × hold`)다. `buyRung` 은 주문 직전 거래소 계좌를 다시 읽어 판정한다 — 수동 매매로 장부가 낡아도 상한이 뚫리지 않는다. 이때 수량은 매도 가능분이 아니라 **계좌 총보유(locked 포함)** 다 — 수동 지정가·출금 대기로 잠긴 코인도 이 예산으로 산 것이고, 빼고 재면 손절 없는 프로파일의 유일한 상한이 뚫린다. rung 은 매도 분할 단위만 담당한다.
- **`flatPeak`** 이 없으면 전량 매도 후 상승장에서 영영 재진입 못 한다(직전 매도가 대비 눌림이 안 온다). 0 일 때만 현재가로 초기화한다 — 재기동마다 깎이면 첫 진입이 계속 미뤄진다.
- **장부와 잔고가 어긋나면 거래하지 않는다**(`hasBalance != hasRungs` → Hold). 정합은 아래 매퍼의 몫이다.

## 라이브 통합

- **dispatch**: `processTicker` 는 공용 preamble(가격·unsynced·pendingPersist·pendingBuy/Sell reconcile) 뒤 `profileOf(ticker)` 로 `runSwing`/`runAccumulate` 를 가른다. 트레일링 고점 flush 는 SWING 만, 무포지션 고점(`flatPeak`) flush 는 ACCUMULATE 만 — 둘 다 "갱신 tick 만 + 실패 시 `peakPersistFailed` 재시도" 규약.
- **사다리 장부는 체결 커밋 트랜잭션 안에서만 바뀐다.** `rungsFilled`·`lastActionPrice` 는 `commitFillAndApply` 의 전이 람다에서만 갱신된다. 밖에서 올리면 "매수 기록됐는데 rung 그대로" 크래시 창에서 같은 단을 다시 산다.
- **매수는 체결이 조금이라도 있으면 한 단, 매도는 요청 대비 ≥ 90% 체결일 때만 한 단 소모.** 매수를 비율로 걸지 않는 이유: 시장가 매수(ord_type=price)는 잔량 환불로 종결돼 미달이 드물고, "미달이면 안 센다"는 다음 tick 의 장부 정합(원가 기반 rung 추정)이 어차피 한 단으로 복원해 규칙이 서로 모순됐다 — 총 투입은 실측 원가 예산 게이트가 막는다. 매도는 `pendingSellVolume` 대비 체결량으로 판정하며 미달이면 rung·기준가 유지, 잔량은 다음 tick 재평가(10% 체결로 한 단을 지우면 사다리가 어긋난다).
- **getOrder 장애 시 잔고 복원은 주문 전 보유량(`pending_buy_prior_volume`)을 넘는 증분만 이 주문의 체결로 본다.** 추가 단은 주문 전부터 코인이 있으므로 잔고 존재만으로 "체결"로 확정하면 미체결 주문이 사라지고 rung 이 헛되이 오른다.
- **매도 전이는 `sellTransition()` 하나** — 즉시 done·reconcile 부분·reconcile 전량·잔고 복원 4경로가 공유한다. 사유·요청수량·트리거가가 durable pending(`pending_sell_reason`·`pending_sell_volume`·`pending_sell_trigger_price`)에 있어 재시작 뒤 reconcile 도 같은 판정이 난다. 이전엔 부분체결 분기가 rung 을 몰라 같은 단을 반복 매도할 수 있었다(플랜 리뷰 blocker).
- **진입점 분리**: `buy()` 는 기존 5중 가드(`entryBlocked`) + `investRatio` 사이징, `buyRung()` 은 `position` 가드만 제외한 같은 가드 + 단당 금액. 플래그로 가드를 우회하지 않는다. 주문 이후 공용부는 `placeBuy` — 진입 메타(`buyDate`·`entryStrategy`·`exitParams`)는 **신규 진입일 때만** 지운다. 추가 단에서 지우면 미체결(cancel+0)로 끝났을 때 영구 유실돼, 프로파일을 끈 뒤 보유상한 청산이 날짜를 잃는다.
- **정합(`LadderStateMapper.reconcile`)은 매 tick 돈다 — 정합 상태에서는 no-op 이라 사람이 고친 장부를 덮지 않는다.** `hold>0 && rungs==0` → 실측 원가로 rung 추정(`ceil(원가/단당)`, 상한 max) + `lastActionPrice = avg` + WARN("편입"). 운영 `.env` 가 BTC·ETH 를 스윙으로 들고 있어 **적립을 켜는 순간 이 경로가 실제로 발동**한다 — 의도된 컷오버. `hold<=0 && rungs>0` → 비움 + `flatPeak` 를 현재가로 재앵커 + WARN(수동 청산 추정 — 옛 고점을 남기면 같은 tick 에 첫 단이 들어가 청산을 되돌린다). `rungs > ceil(원가/단당)` → 원가가 감당하는 단수로 하향(90% 미만 부분 매도가 반복되면 잔고는 줄어도 rung 이 안 줄어 단당 매도 대금이 최소주문 아래로 내려간다). 런타임에 장부와 잔고가 갈라져도(부분체결·수동 매매) 다음 tick 에 스스로 맞춘다 — 적립엔 다른 청산 게이트가 없어 여기 말고는 풀 곳이 없다. 마지막 단이 90~99% 체결돼 잔량이 남으면 `sellTransition` 이 rung 을 1 로 유지한다.
- **현금 경쟁**: 적립이 아직 투입하지 않은 예산 `Σ max(0, budget − avg×hold)` 를 스윙 `buy()` 사이징에서 뺀다(`reservedKrw`). 단이 예산·KRW 부족으로 건너뛰어지면 사유가 바뀔 때만 WARN 하고 `/api/bot/status.positions[].accumulate_skip` 에 노출한다.
- **역방향 컷오버**: 적립 티커를 끄면 남은 포지션이 즉시 스윙 게이트(손절 −5%·09:00 청산)를 받는다. `buyDate` 는 마지막 단 매수일이다.
- **기록**: 단 매수는 기존 BUY 스냅샷 규약([[trade-record-volume-semantics]]), 단 매도는 `reason=ACCUMULATE_STEP`·`strategy=accumulate`·`volume=판 수량`. 편입된 스윙 포지션이어도 적립 규칙으로 팔았으면 `accumulate` 몫이다. 리더보드 `aggregateSellStatsByUser` 는 accumulate 행을 제외한다 — `/api/strategies/performance` 는 SELL 행 `pnl_percent` 단순 합산이라 부분 매도가 잦은 이 프로파일에서 과대계상된다.
- **durable(V23)**: `rungs_filled`·`last_action_price`·`flat_peak`·`pending_buy_trigger_price`·`pending_buy_prior_volume`·`pending_sell_trigger_price`([[persistence-schema]]). 잔고·평단은 종전대로 거래소 복원.

## 알트 유니버스 자동 선정 (`trading.universe.auto`, 기본 off)

- `UniverseSelector` 는 싱글톤 `@Service` 로 인증 없는 `publicUpbitClient` 를 쓴다 — 유저 엔진 수만큼 같은 공개 조회를 반복하지 않게 1분 TTL 스냅샷을 공유하고, 사용자 키 장애와 결합되지 않는다. `getMarkets()`(`/v1/market/all?is_details=true`)의 `market_event.warning`(투자유의) 과 `PeggedAssets`(스테이블·EURC·XAUT), 적립 티커를 제외하고 `acc_trade_price_24h` 내림차순. **조회 실패는 null** — 불완전한 순위로 판정하지 않는다(`PointInTimeUniverse` 와 같은 원칙).
- `TradingEngine.applyTickers(next)` 가 활성 집합 교체의 유일한 경로다. 목록만 갈아끼우면 새 티커는 `states` 에 없어 매 tick 조용히 skip 되고 빠진 티커의 상태는 리셋·status 에 계속 섞인다. 적립 티커 + 보유/pending/`unsynced`(보유 여부 미확인 — 실제 포지션일 수 있다) 티커를 고정하고 알트 몫을 20(`RequestValidators` 의 API 상한과 동일)까지만 채운다 — 적립·보유 티커는 자르지 않으므로 활성 총수는 이를 넘을 수 있다. 기동 시와 09:00 경계(`checkAndReset` true tick — 재시작 첫 tick 도 포함)에 `refreshUniverse()`.
- **첫 선정 전에는 진입 없음**: auto 면 `swingUniverse` 를 빈 집합으로 시작한다. 선정 API 가 죽은 채 재시작하면 durable 잔재 전부가 활성인데 "제한 없음"으로 두면 그들이 전부 신호에 따라 진입한다 — 청산·reconcile 만 돌고 첫 선정이 성공해야 진입이 열린다.
- **재시작**: 자동 선정 티커는 `bot_state.tickers` 에 없으므로 `start()` 는 auto 일 때 durable 행 전부를 활성에 싣는다 — 안 그러면 그 보유·pending 은 `applyTickers` 의 보호 집합에 들어갈 기회가 없어 아무도 reconcile 하지 않는다. 무포지션 잔재는 첫 갱신에서 빠진다. 기동 시 갱신은 `runLoop` 의 복구 경계 안에서 실패를 흡수한다(직전 목록 유지). 갱신으로 복원된 durable 상태에는 현재 거래일 기준 `resetDaily` 를 적용한다(옛 `boughtDate` 로 하루 종일 진입이 막히지 않게).
- **`bot_state.tickers` 는 사용자 의도만 저장한다.** 파생 집합을 되쓰면 auto 를 꺼도 그날의 알트가 남아 되돌릴 수 없다. `startBot` 은 받은 목록을 그대로 저장한다.
- watchlist 밖 티커의 시세는 REST 폴백이다([[marketdata-pipeline]] 은 부팅 시 `watchlist.tickers` 를 한 번 잡는다). D1 캔들 폴백은 싱글톤 `DailyCandleCache`(60초 TTL) — ingestion 의 캔들 주기와 같아 신선도는 store 경로와 동일하다. 거래소가 요청보다 적게 준 응답(상장 60일 미만)도 TTL 동안 재사용한다 — miss 로 보면 신규 상장 종목이 매 tick REST 를 다시 친다.

## 백테 (`AccumulateBacktest`)

[[backtest-engine]] 은 단일 포지션 구조라 별도 D1 시뮬레이터를 두되 판정은 `AccumulateLadder` 를 호출한다(규칙 이중구현 금지 — `ExitGates` 공유 규약과 동형). 봉당 1액션이 기본이고 **한 봉 안에서는 한 방향만** 진행한다 — low 에서 사고 high 에서 파는 왕복은 순서를 알 수 없는 look-ahead 다. 매수 트리거는 low, 매도는 high, 체결가는 트리거가(시가가 이미 넘었으면 시가), 편도 수수료 0.05%. 지표는 예산 대비 순수익률·현금 포함 equity 의 MDD·평균 노출.

**2026-09-02 결과**(bear BTC·ETH·XRP·SOL + bull BTC·XRP·SOL, 후보 5/3/3): 하락장 중앙값 −19.9%(B&H −29.0%), 상승장 +27.3%(B&H +96.1%), worst MDD 37%, 평균 노출 0.86(보유 원가/예산). 사전 등록 규칙(상승장 > 0, 하락장 > B&H, MDD ≤ 40%)은 통과했으나 **비판별적**이었다(27/27 격자 통과 — 부분 노출 전략은 전액 B&H 보다 거의 항상 덜 잃는다). 읽을 것은 프로파일이다: 하락장에서 예산이 초반에 소진돼 손실을 그대로 맞고, 상승장에서 오를수록 팔아 B&H 의 1/3~1/4 만 먹는다. **수익성 우월의 근거가 아니다.** 격자 최적값을 기본값으로 올리지 않았다(과적합).

## 롤백

1차 경로는 **forward-off** — `TRADING_ACCUMULATE_TICKERS` 를 비우고 `TRADING_UNIVERSE_AUTO=false` 로 재기동. 이미지를 되돌리지 않는다: `deploy.sh` 는 마이그레이션 포함 배포의 자동 롤백을 막고(`MIGRATION_GATE=blocked`), 구버전은 V23 을 모르며 `pending_sell_reason=ACCUMULATE_STEP` 을 `MANUAL` 로 읽어 전량 청산으로 확정한다([[deployment-stack]]). 켜기 전 수동 `pg_dump`.
