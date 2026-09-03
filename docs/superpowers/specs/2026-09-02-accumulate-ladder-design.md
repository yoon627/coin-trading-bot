# 적립 프로파일 + 알트 유니버스 자동 선정 — 설계

**Date**: 2026-09-02
**Status**: Implemented (기본 off — `.env` 로 활성화)
**Owner**: yoon627
**Scope**: Upbit 봇의 메이저 코인(BTC·ETH·XRP·SOL) 사다리 매매 프로파일, 알트 스윙 유니버스 자동 선정. KIS 경로는 무관.

## 1. 배경

운영 실측(2026-06-14~09-02, SELL 42건)에서 청산의 59.5% 가 09:00 강제청산(`DAILY_RESET`)이었고 그 25건 평균 −0.73% 가 손실의 원천이었다. 익절·트레일링이 벌어 준 것을 리셋 청산이 소모하는 구조다. 다만 리셋 정책을 바꾸는 반사실 백테(#128, 시점 중립 fixture)는 어느 안도 두 국면에서 일관된 개선을 보이지 못했다 — 스윙 규칙을 손대는 대신, 사용자가 원한 "떨어지면 더 사고 오르면 나눠 파는" 매매를 **메이저 한정 별도 프로파일**로 두고 알트는 스윙 그대로 둔다.

## 2. 결정

### 2.1 사다리 규칙 (`common/strategy/AccumulateLadder.kt`)

| 규칙 | 식 |
|---|---|
| 단당 금액 | `budgetKrw / maxRungs`, 5,000원 미만이면 생성 거부 |
| 첫 진입 (rungs=0) | `price <= flatPeak × (1 − stepDown)` — 무포지션 구간 고점 대비 눌림. 고점은 직전 판정까지의 값 |
| 추가 매수 | `rungs < maxRungs && price <= lastActionPrice × (1 − stepDown) && avg×hold + 단당 <= budget` |
| 부분 매도 | `price >= max(avg, lastActionPrice) × (1 + stepUp)` → `hold / rungs` 매도, 마지막 단은 전량 |
| 최소주문 | 매도 대금 < 5,000원이면 Hold(rung 유지) |
| 청산 게이트 | 손절·익절·트레일링·보유상한 없음 |

- `lastActionPrice` 는 **트리거가**(판정 tick 의 현재가)다. 거래소는 누적 평단만 주고 `Order` 에 VWAP 이 없어 체결가를 쓸 수 없고, 평단을 쓰면 단이 쌓일수록 간격이 압축된다. 백테와 같은 정의.
- 예산 상한은 rung 수가 아니라 실측 원가(`avg × 계좌 총보유(locked 포함)`)로 판정한다. rung 은 매도 분할 단위만 담당한다.
- `flatPeak` 는 전량 매도 후 상승장에서 재진입을 보장한다(직전 매도가 기준이면 눌림이 안 와 영영 못 산다).

### 2.2 라이브 통합

- **dispatch**: `TradingEngine.processTicker` 는 공용 preamble(가격·unsynced·pendingPersist·pendingBuy/Sell reconcile) 뒤 `profileOf(ticker)` 로 `runSwing`/`runAccumulate` 를 가른다. 트레일링 고점 flush 는 SWING 만, 무포지션 고점 flush 는 ACCUMULATE 만.
- **원자성**: 사다리 장부(`rungsFilled`·`lastActionPrice`)는 `commitFillAndApply` 의 전이 람다 안에서만 바뀐다. 커밋 밖에서 바꾸면 크래시 창에서 같은 단을 다시 산다.
- **체결 비율**: 매수는 체결이 있으면 한 단(시장가 매수는 잔량 환불로 종결되고, 미달을 안 세면 다음 tick 의 원가 기반 정합이 다시 한 단으로 복원해 모순), 매도는 `pendingSellVolume` 대비 90% 이상 체결일 때만 한 단 소모. 미달이면 rung 유지, 잔량은 다음 tick 재평가 — 예산 실측 게이트가 과투입을 막는다.
- **주기 재동기화**: 적립 티커는 60초마다 `syncPosition(clearWhenEmpty=true)` 으로 잔고·평단을 다시 읽고 확인된 무잔고는 포지션 해제로 반영한다(수동 매매 반영). 동기화 시각은 성공 시에만 기록. dormant 행 계좌 조회 실패는 매 루프·09:00 갱신 때 재시도(되살리면 즉시 `syncPosition`). 부분 매도 reconcile 의 잔량 하한은 durable `pending_sell_prior_volume` − 체결량, 비최종 단 수량은 절삭된 주문 수량. 추가 단 체결 뒤 계좌를 못 읽으면 체결분 + 주문 전 보유량으로 반영.
- **잔고 복원**: getOrder 장애 시 주문 전 보유량(`pending_buy_prior_volume`)을 넘는 증분만 체결로 인정 — 추가 단은 주문 전부터 코인이 있어 잔고 존재만으로는 판정할 수 없다.
- **재시작·기동 갱신**: auto 면 `start()` 가 durable 행 중 보유·pending 흔적이 있는 것을 활성에 싣고, 흔적 없는 행은 `runLoop` 초입에서 계좌 1회 조회로 실잔고가 있는 것만 되살려 자동 선정 티커의 보유·pending 이 보호 집합에 들어가게 한다(무포지션 잔재는 첫 갱신에서 제거). 기동 시 갱신 실패는 `runLoop` 복구 경계 안에서 흡수, 복원된 상태에는 `resetDaily` 적용.
- **첫 선정 전 진입 없음**: auto 면 `swingUniverse` 는 빈 집합으로 시작 — 선정 실패 중 durable 잔재가 진입하지 못하게. 보호 집합에 `unsynced` 포함(보유 여부 미확인).
- **추가 단 미체결**: `placeBuy` 는 신규 진입일 때만 `clearEntryMeta` — 추가 단이 cancel+0 이어도 `buyDate`·`entryStrategy` 보존.
- **유니버스 잔류 티커**: 보유 때문에 목록에 남은 티커는 청산 뒤 새로 사지 않는다(`swingUniverse` 밖이면 진입 차단) — 잔류의 의미는 "청산될 때까지"다.
- **매도 전이 단일화**: 즉시 done·reconcile 부분·reconcile 전량·잔고 복원 4경로가 `sellTransition()` 하나를 쓴다. 갈라지면 어느 한 경로에서 rung 이 안 줄어 같은 단을 반복 매도한다.
- **진입점 분리**: `buy()`(5중 가드 + investRatio 사이징 − reservedKrw) / `buyRung()`(`position` 가드만 제외, 주문 직전 거래소 재측정으로 예산 판정). 플래그로 가드를 우회하지 않는다.
- **정합**: `LadderStateMapper.reconcile` 를 매 tick(정합 상태에서는 no-op 이라 사람이 고친 장부를 덮지 않는다) — `hold>0 && rungs==0` → 실측 원가로 rung 추정 + `lastActionPrice=avg`(컷오버 편입), `hold<=0 && rungs>0` → 비움 + `flatPeak` 현재가 재앵커(수동 청산 추정), `rungs != ceil(원가/단당)` → 원가가 말하는 단수로 조정(부분 매도 누적·수동 추가 매수), `flatPeak==0` → 현재가. 런타임에 장부와 잔고가 갈라져도(부분체결·수동 매매) 다음 tick 에 스스로 맞춘다.
- **현금 경쟁**: `reservedKrw = Σ max(0, budget − avg×hold)` 를 스윙 사이징에서 뺀다. 단 skip 사유는 `TradingState.accumulateSkipReason` → `/api/bot/status.positions[].accumulate_skip`.
- **기록**: BUY 는 기존 스냅샷 규약, SELL 은 `reason=ACCUMULATE_STEP`·`strategy=accumulate`·`volume=판 수량`. 리더보드 집계는 accumulate 행 제외.
- **durable(V23)**: `rungs_filled`·`last_action_price`·`flat_peak`·`pending_buy_trigger_price`·`pending_buy_prior_volume`·`pending_sell_trigger_price`·`pending_sell_prior_volume`. 잔고·평단은 종전대로 거래소 복원.

### 2.3 알트 유니버스 자동 선정

- `UniverseSelector`(싱글톤 `@Service`, `publicUpbitClient`): `/v1/market/all?is_details=true` 의 `market_event.warning` 제외, `PeggedAssets` 제외, `/v1/ticker` 배치의 `acc_trade_price_24h` 내림차순. 1분 TTL 스냅샷을 유저 엔진들이 공유. 실패 시 null(불완전 순위로 판정하지 않음).
- `TradingEngine.applyTickers(next)` 가 활성 집합 교체의 유일한 경로 — 적립 티커 + 보유/pending 티커 고정, 알트 몫은 20 까지(적립·보유는 예외), 신규 시딩+`syncPosition`(수동 보유·unsynced 발견 시 즉시 영속), 제거분 `states` 정리. 기동 시와 09:00 경계에 `refreshUniverse()`.
- `bot_state.tickers` 는 사용자 의도만 저장한다(파생 집합을 되쓰면 끄고도 되돌릴 수 없다).
- watchlist 밖 티커는 REST 폴백. D1 캔들은 60초 TTL 캐시(ingestion 주기와 동일).

### 2.4 배포·롤백

- 기본값 off. `.env.example`·`docker-compose.prod.yml`·`deploy.sh` 의 `TRADING_OVERRIDE_KEYS` 에 7키 등록.
- 롤백 1차 경로는 **forward-off**(값 비우고 재기동). `deploy.sh` 는 마이그레이션 포함 배포의 자동 롤백을 막고, 구버전 이미지는 V23·`ACCUMULATE_STEP` 을 모른다. 켜기 전 수동 `pg_dump`.

## 3. 백테 (`AccumulateBacktest`)

`BacktestEngine` 은 단일 포지션 구조라 별도 시뮬레이터를 두되 판정은 `AccumulateLadder` 를 호출한다. 봉당 1액션(기본)·한 봉 한 방향(low→high 왕복 look-ahead 금지), 매수 트리거는 low·매도는 high, 체결가는 트리거가(갭이면 시가), 편도 수수료 0.05% 반영. 지표는 예산 대비 순수익률·equity MDD·평균 노출.

사전 등록 채택 규칙(후보 5/3/3 하나만 판정): 상승장 중앙값 > 0, 하락장 중앙값 > B&H 중앙값, worst MDD ≤ 40%. 결과(2026-09-02, bear BTC·ETH·XRP·SOL + bull BTC·XRP·SOL):

| | bear 중앙값 | bear B&H | bull 중앙값 | bull B&H | worst MDD | 평균 노출 |
|---|---|---|---|---|---|---|
| 5/3/3, 봉당 1 | −19.9% | −29.0% | +27.3% | +96.1% | 37.0% | 0.86 |
| 5/3/3, 봉당 다단 | −22.7% | −29.0% | +33.2% | +96.1% | 37.7% | 0.88 |

규칙은 통과했으나 비판별적이었다(27/27 통과 — 부분 노출 전략은 전액 B&H 보다 거의 항상 덜 잃는다). 읽을 것은 **프로파일**이다: 하락장에서 예산이 초반에 소진돼 −13~−33% 를 그대로 맞고, 상승장에서는 오를수록 팔아 B&H 의 1/3~1/4 만 먹는다. 수익성 우월의 근거가 아니다.

## 4. 범위 밖 / 후속

- 알트 스윙의 09:00 청산 정책은 그대로(근거 부재 — `reset-churn-measurement`).
- ingestion 의 동적 구독(자동 유니버스 티커를 WS 로 받기).
- `assembleRoundTrips` 가 사다리 재매수를 별개 그룹으로 표시하고 앞 그룹 잔량이 이중 표시될 수 있는 문제.
- 런타임 수동 매매와 사다리 장부의 주기적 reconcile.
