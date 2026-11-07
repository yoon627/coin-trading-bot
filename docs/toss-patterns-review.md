# 토스증권 서버 사례 적용 검토

- 기준: main `0f058ef7`(2026-11-07). 코드는 읽기만 했고 고치지 않았다.
- 확신 표기: ✅ 코드·공식 문서로 확인 / ⚠️ 정황으로 추정 / ❌ 확인하지 못함.
- 항목마다 현재 상태 · 빈틈과 사고 시나리오 · 제안 · 작업량(S/M/L) · 우선순위를 적었다. 잘 돼 있는 항목은 "충분함"으로 적고 개선점을 만들지 않았다.

## 0. 요청 배경과 실제 코드가 다른 점

검토 전에 확인한 사실이다. 아래 항목들은 이 사실을 전제로 판단했다.

| 요청 배경 | 실제 | 근거 |
|---|---|---|
| Kafka 사용, 비용 때문에 제거 검토 | **이미 제거됨.** 시세는 프로세스 안에서 전달한다 | 커밋 `77256e71` "replace Kafka bus + collector with in-process ingestion" ✅. 그때 Kafka 가 나르던 것은 시세(`MarketDataConsumer`)와 체결 알림 발행(`TradeEventProducer`)이었고, 거래 기록은 그 전부터 DB 에 있었다 ✅ |
| Redis 사용 | **이미 제거됨** | PR #292 "mvp-trim-redis" ✅ |
| 거래소 Upbit·Binance·KIS | **Upbit 만 엔진에 연결.** KIS 는 클라이언트·키 저장까지 있고 엔진 연결 전(계획 P5~). Binance 는 없다 | `Exchange` enum = `UPBIT`, `KIS_PAPER`, `KIS`, 시험이 `"BINANCE"` 를 거절한다 ✅ |
| EC2 배포 | **Vultr VPS 1대**(Caddy + app + postgres, docker compose) | `deploy/vultr/docker-compose.prod.yml` ✅ |
| Upbit myOrder·Binance user data stream 같은 체결 스트림 | **없음.** 유일한 WebSocket 은 Upbit 공개 시세(ticker)다. 체결은 REST 조회로 확인한다 | `marketdata/UpbitMarketFeed.kt` ✅ |

## 요약

| 항목 | 판정 | 핵심 | 작업량 | 우선순위 |
|---|---|---|---|---|
| A1 주문 타임아웃 = 결과 모름 | 대체로 충분함 | 매도 선기록 실패 직후 비정상 종료 창, KIS 는 연결 전 | M | 중간 (KIS 는 연결 전 필수) |
| A2 체결 먼저 저장·중복 제거 | 충분함 | KIS 연결 전에 중복 방지 키에 거래소를 넣어야 함 | S | 중상 (KIS 전) |
| A3 동시 갱신 | 충분함 | 단일 인스턴스·엔진 루프 직렬화로 충분. 두 번째 프로세스만 위험 | — | 낮음 |
| A4 대사·킬스위치 | **빈틈 있음** | 매도가 실잔고 전량을 팔아 손으로 산 코인까지 판다. 계좌 단위 대사·킬스위치 없음 | S~M | **높음** |
| B1 rate limit | **빈틈 있음** | WS 가 끊기면 종목별 REST 티커 대체가 IP 한도를 넘어 418 차단 → 손절 평가 정지 위험. 418 처리 없음 | S~M | **높음**(대체 호출 묶음) |
| B2 거래소별 장애 격리 | 부분 | 거래소 단위 상태·신규 진입 자동 중단 없음 | M~L | 중간 (KIS 전 높음) |
| C1 이벤트 루프 블로킹 | 충분함 | 로그인 BCrypt 하나만 루프에서 돈다 ⚠️ | S | 낮음 |
| C2 순서·낡은 이벤트 | 대체로 충분함 | 분봉 폴링이 5분 넘게 끊기면 그날 일봉 고가·저가가 틀어진 채 남음 | S~M | 중간 |
| C3 지연 vs 유실 | 충분함 | 시세는 최신값만, 주문·체결은 DB 로 내구성 | — | 낮음 |
| D1 섀도·리플레이 | 부분 | 체결 시각에 벽시계 직접 사용, 리플레이용 시세 저장 없음 | S (Clock) / M | 중간 |
| D2 배포 없는 설정 변경 | **빈틈 있음** | 전 사용자 신규 진입만 막는 킬스위치가 없다 | S~M | **높음** |
| D3 지연 측정·알림 중복 | 부분 | 시세 피드가 끊겨도 Discord 가 조용함, 구간 지연 기록 없음 | S | 중상 (피드 알림) |

## A. 돈 정합성

### A1. 주문 타임아웃 = "결과를 모름"

- **현재 상태**
  - ✅ 주문 ID 선기록: 매수·매도 모두 `PositionManager.placeBuy`·`sell` 이 `newOrderIdentifier()`(`ctb-<UUID>`, `client/UpbitClient.kt`)를 만들어 `TradingState.beginBuyOrder`/`beginSellOrder` 로 DB(`trading_states.pending_*_identifier`, V28)에 쓴 뒤 주문을 보낸다. 기록부터 체결 반영까지는 `withContext(NonCancellable)` 라 정지·재설정이 중간에 끊지 못한다.
  - ✅ 선기록 실패: 매수는 기록에 실패하면 보내지 않는다. 매도는 손절을 막지 않으려고 기록이 실패해도 보내고, `pendingPersistFailed` 로 신규 진입을 막고 매 tick 다시 기록한다(`retryPendingPersistIfNeeded`). 정지·재설정 때 `flushUnpersisted`, 그래도 못 남기면 `alertDroppedSells` 가 ERROR 로 알린다.
  - ✅ 주문 POST 는 재시도하지 않는다(`UpbitClientImpl.placeOrder` 에만 `retryOnRateLimit` 없음, KIS `placeCashOrder` 도 재시도 없음).
  - ✅ 실패 분류: `provesOrderNotPlaced()` 가 "4xx + 거절 목록의 오류 이름"일 때만 미접수로 확정한다. 429·5xx·타임아웃·연결 끊김·응답 해석 실패는 모두 결과 모름이다(`UpbitOrderErrorClassifier`). `duplicated_identifier` 는 "주문이 있다"로 본다.
  - ✅ 결과 모름 해소: 매 tick(장외에도) `TradingEngine.processTicker` 가 `reconcilePendingBuy`/`Sell` 을 먼저 부르고, uuid 가 없으면 `UnknownOrderResolver` 가 identifier 로 조회한다. 404 가 2번이고 60초가 지났고 잔고 흔적이 없을 때만 미접수로 풀고(`UnknownOrderPolicy.UPBIT`), 흔적이 있으면 사람 확인(review)으로 멈춘다. 조회가 연속 20번 실패하면 그 종목을 halt 한다.
  - 토스 방식과 다른 점: 토스는 "같은 멱등키로 재전송"이지만 이 코드는 "재전송하지 않고 조회로 확정"한다. 업비트 identifier 는 계정 안에서 영구히 고유해 재사용하면 400 `duplicated_identifier` 라(공식 문서 ✅, #340 댓글) 같은 키 재전송도 두 번째 주문을 만들 수는 없다. 두 방식 모두 "내부 주문 1건 = 거래소 주문 1건"을 지킨다.
  - KIS: 주문 API 에 클라이언트 주문 ID 가 없다 ✅. 계획(P5)은 송신 전에 `broker_order_attempts` 행을 커밋하고, 결과 모름은 일별 주문 조회로 맞추는 것이다 ✅(KIS 계획). 아직 엔진에 연결되지 않았다.
- **빈틈과 사고 시나리오**
  1. ⚠️ 매도 선기록 실패 직후 비정상 종료: DB 가 잠깐 끊긴 사이 손절 매도를 보냈는데(기록 실패) 프로세스가 OOM·SIGKILL 로 죽으면, 재시작 뒤 pending 이 없다. 잔고로 포지션은 맞춰지지만 그 매도의 거래 기록·손익이 영구히 빠지고, 비정상 종료라 알림도 없다.
  2. ⚠️ 응답 없는 매도의 손절 지연: 실제로는 주문이 안 만들어진 실패(연결 단계 실패 등)여도 미접수 확정까지 약 80초 동안 다시 팔지 않는다(#340).
- **제안**
  1. 기동 복원 때 "진입 메타는 있는데 잔고 0, pending 없음"이면 ERROR 로 알리고, 가능하면 업비트 끝난 주문(`ctb-` identifier)으로 SELL 기록을 백필한다(A4 의 대사와 같은 작업).
  2. #340: 결과 모름 매도를 먼저 identifier 로 조회하고, 없으면 같은 identifier 로 한 번 재전송한다(중복 불가는 문서로 확인됨).
  3. KIS: 계획대로 `broker_order_attempts` 선기록을 엔진 연결 전에 넣는다.
- **작업량**: 1 M(대사와 묶으면 줄어듦), 2 M, 3 M~L(계획 P5)
- **우선순위**: 1·2 중간, 3 KIS 실거래 전 필수

### A2. 체결 이벤트를 먼저 저장하고 처리 (inbox + 중복 제거)

- **현재 상태 — 충분함**
  - ✅ 체결 스트림이 없고 REST 조회로 확인한다(`awaitFill` 10회 × 300ms, 이어서 매 tick reconcile). 주문 의도는 `trading_states` 의 pending 컬럼에 먼저 남으므로 이것이 사실상 내구성 있는 inbox 역할을 한다.
  - ✅ 상태와 거래 기록을 한 트랜잭션으로: `TradeExecutionService.commitFill` 이 pending 해제·포지션 전이와 `trade_records`·`trade_executions` 삽입을 한 트랜잭션으로 묶고, 커밋이 성공한 뒤에만 메모리를 바꾼다(`commitFillAndApply`). 실패하면 pending 이 남아 다음 tick 에 다시 한다.
  - ✅ DB 수준 중복 방지: `uq_trade_executions_order_id ON trade_executions (user_id, exchange_order_id) WHERE exchange_order_id IS NOT NULL`(V14, 이후 변경 없음) + `existsByUserIdAndExchangeOrderId` 사전 확인.
  - ✅ 두 번 반영해도 포지션이 늘지 않음: 보유 수량·평단은 체결 뒤 거래소 실잔고 절대값으로 정한다(`BalanceFillAccounting.balanceBuyFill`, `markBought(replace = true)`).
- **빈틈과 사고 시나리오**
  - ⚠️ 중복 방지 키에 거래소가 없다. 업비트 uuid 는 전역 고유라 지금은 문제없다. KIS 를 연결해 `exchange_order_id` 에 주문번호(ODNO)를 넣는 순간, ODNO 가 날짜·계좌 범위에서만 고유하면(❌ KIS 문서 확인 필요) 다른 날이나 모의·실전 사이에 같은 번호가 나와 **정상 체결이 "이미 기록됨"으로 조용히 빠진다.** 계획의 KIS 주문 ID 형식 `<EXCHANGE>:<yyyyMMdd>:<ODNO>` 를 그대로 쓰면 피할 수 있다.
  - 체결 알림(Discord)은 커밋 뒤 보내므로 직후 크래시면 알림 1건이 빠진다 — DB 기록은 남아 허용 범위다.
- **제안**: KIS 연결 전에 unique 키를 `(user_id, exchange, exchange_order_id)` 로 바꾸고 사전 확인 쿼리에도 거래소를 넣는다. 체결 스트림(myOrder WS)이나 별도 inbox 테이블은 필요 없다 — 시장가라 조회 지연이 수 초이고, 스트림은 재연결·누락 보정 복잡도만 늘린다.
- **작업량**: S (마이그레이션 + 쿼리 + 시험)
- **우선순위**: 중상 (KIS 연결 전 필수)

### A3. 같은 종목·계좌의 동시 갱신

- **현재 상태 — 충분함**
  - ✅ 사용자 × 거래소마다 엔진 하나, 그 루프가 종목을 하나씩 처리한다(`TradingEngine.runLoop`). 종목 처리는 `stateMutex` 안에서 돌고(#343), 체결 확인(`awaitFill`)까지 그 안에서 끝난다. 같은 종목 신호 둘이 동시에 평가될 수 없다.
  - ✅ 이중 진입 가드: `PositionManager.entryBlocked`(보유·pending·unsynced·pendingPersistFailed·halt), pending 이 있는 tick 에는 매매를 평가하지 않는다. 매수 직전에 거래소 잔고를 다시 조회한다.
  - ✅ 엔진 중복 방지: `UserTradingManager` 의 사용자별 Mutex 가 시작·정지·재설정·복원·해제를 직렬화하고, `stop()` 은 루프가 끝날 때까지 기다린다. 다른 writer(halt 해제는 `stateMutex`, pending 해제는 정지 상태에서만, flush 는 정지 뒤)도 모두 직렬화돼 있어 `@Version` 없는 find-then-save 로도 갱신 손실이 없다.
  - ✅ 배포 창: compose 서비스 하나(replicas 없음), 옛 컨테이너를 멈춘 뒤 새 컨테이너를 띄운다. 옛·새 프로세스가 함께 매매하지 않는다(⚠️ compose 표준 동작 기준, 실측은 하지 않음).
- **빈틈과 사고 시나리오**
  - ⚠️ 프로세스 밖 동시 실행만 무방비다: 운영 DB 에 로컬 앱을 붙이거나 수동 `docker compose run app` 으로 두 번째 프로세스가 돌면, 서로의 메모리 가드를 몰라 같은 신호에 이중 매수가 나가고 상대의 pending uuid 를 덮을 수 있다. 정상 배포 경로에서는 일어나지 않는다.
  - ⚠️ 같은 업비트 키를 두 사용자 행에 등록하는 것을 막는 제약이 없다 ✅(키 컬럼 unique 없음). 그러면 두 엔진이 한 계좌로 이중 진입할 수 있다.
- **제안**: 제안된 (a) 종목별 `groupBy`/`concatMap` 직렬화는 불필요하다 — 지금 루프가 종목보다 강한 단위로 이미 직렬화하고, Reactor 로 바꾸면 NonCancellable·정지 의미를 다시 검증해야 한다. (b) 조건부 UPDATE·version 은 주문이 나간 **뒤** 저장 시점에만 충돌을 드러내 이중 주문 자체는 못 막는다. 분산 락은 단일 인스턴스라 불필요하다. 두 번째 프로세스 방어가 필요해지면(수평 확장·무중단 배포 — #328) 그때 엔진 소유권 펜싱(`pg_try_advisory_lock(user, exchange)` 또는 lease)을 넣는다. 지금은 README 운영 절차에 "운영 DB 에 두 번째 앱을 붙이지 않는다"와 키 중복 등록 금지를 적는 정도면 된다.
- **작업량**: 문서 S / 펜싱 M
- **우선순위**: 낮음 (#328 을 진행할 때 필수)

### A4. 대사 (reconcile)

- **현재 상태**
  - ✅ 대조 대상이 사실상 미해소 주문뿐이다: 매 tick `reconcilePendingBuy`/`Sell`. 보유 동기화(`syncPosition`)는 루프 시작 때와 `unsynced` 일 때만 돈다. tick 마다 보유 수량과 실잔고를 비교하지 않는다. 거래소 체결 이력(끝난 주문)과 거래 기록을 대조하는 코드는 없다.
  - ✅ 불일치 처리는 종목 단위다: `unsynced`(매수 차단), `halted`(매수 차단, 매도·reconcile 은 계속), `reviewReason`(사람 확인), 오래 막힌 매도 ERROR 1회. 사용자·거래소·전역 단위 킬스위치는 없다(D2).
  - ✅ 기록 방식: `trade_records`·`trade_executions` 는 추가 전용이다. `trading_states` 는 덮어쓰는 스냅샷이고, 기록 없이 상태만 바꾸는 보정(유령 포지션 `markSold`, `releaseDust`, `syncPosition` 덮어쓰기, 수동 pending 해제)은 DB 감사 기록이 없다(#342).
- **빈틈과 사고 시나리오**
  1. ✅ **매도가 실잔고 전량을 판다.** `PositionManager.sell` 은 주문 수량으로 `state.holdVolume` 이 아니라 거래소 free 잔고(`account.balanceDouble()`)를 쓴다(코드 주석에 명시). 봇이 BTC 를 들고 있는 동안 사용자가 업비트 앱에서 BTC 를 더 사면, 다음 청산 신호에 **사용자가 산 BTC 까지 시장가로 팔리고**, 기록은 봇의 평단 기준으로 남아 손익도 틀어진다. README 에 이 제약을 알리는 문장이 없다 ✅.
  2. ⚠️ 반대로 사용자가 먼저 손으로 산 코인이 있는데 봇이 같은 종목을 매수하면, 체결 정산이 실잔고 전체를 봇 포지션으로 잡는다(`BalanceFillAccounting.balanceBuyFill`, `markBought(replace = true)`). 기동 때 `syncPosition` 도 손으로 산 보유를 봇 포지션으로 편입한다. 두 경우 모두 그 코인이 봇의 손절·익절 대상이 된다.
  3. ✅ 사용자가 앱에서 그 코인을 직접 팔면, 다음 청산 판정에서 `sellable=0` 으로 `markSold` 가 SELL 기록 없이 포지션을 지운다. 청산 신호가 오기 전까지 며칠씩 유령 포지션이 남아 그 종목 매수도 막힌다.
  4. ⚠️ 계좌 단위 불일치(체결 이력에는 있는데 거래 기록에 없는 주문, 모르는 코인 등)를 찾는 경로가 없어 알림도 진입 중단도 없다(#341).
- **제안**
  1. 사람 코인 보호: 매도 수량을 `min(free, 봇이 산 수량)` 으로 묶고, 차이가 나면 unsynced + 알림. 최소한 README 에 "봇이 다루는 코인은 직접 사고팔지 않는다"를 적는다.
  2. 하루 한 번 + 기동 때 대사(#341): 업비트 `GET /v1/orders/uuids?identifiers[]=`(100개씩)와 `GET /v1/orders/closed`(7일 창, limit 1000, `identifier` 포함 ✅ 공식 문서)로 `ctb-` 주문을 `trade_executions` 와 맞추고, 어긋나면 ERROR 요약 + 그 사용자 신규 진입 차단(D2 의 플래그). 보정은 덮어쓰지 않고 정정 기록을 추가한다.
  3. 기록 없는 보정에 감사 행 추가(#342).
- **작업량**: 1 S~M, 2 M, 3 S~M
- **우선순위**: 1 **높음**(실제로 사용자 코인을 팔 수 있음), 2·3 중간

## B. 외부 의존성 보호

### B1. 거래소 API rate limit

공식 한도(2026-11-07 확인):

| 거래소 | 그룹 | 한도 | 단위 | 출처 |
|---|---|---|---|---|
| Upbit | 시세 REST(market·candle·trade·ticker·orderbook 그룹별) | 각 10회/초 | IP | [rate-limits](https://docs.upbit.com/kr/reference/rate-limits) ✅ |
| Upbit | `default`(잔고·주문 조회·취소 등) | 30회/초 | 키 묶음(pocket) | 같은 문서 ✅ |
| Upbit | `order`(주문 생성) | 12회/초 | pocket | 같은 문서 ✅ |
| Upbit | `order-cancel-all` | 2초에 1회 | pocket | 같은 문서 ✅ |
| Upbit | WebSocket 연결 / 메시지 | 5회/초 / 5회/초·100회/분 | IP(인증 없음) / 연결 | 같은 문서 ✅ |
| Upbit | 429 → 418 | 초 한도 초과 429, 반복되면 418 로 일시 차단(차단 시간이 점점 늘어남) | — | 같은 문서 ✅ |
| KIS | 실전·모의 REST 초당 건수 | ❌ 공식 공개 문서에서 숫자를 찾지 못함(포털 공지는 로그인 필요) | 앱키 ⚠️ | — |
| KIS | 초과 오류 / 토큰 발급 | `EGW00201` / 1분에 1회 | 앱키 | [open-trading-api](https://github.com/koreainvestment/open-trading-api) ✅ |

- **현재 상태**
  - ✅ KIS: (환경, 키)마다 `KisRateLimiter` 하나, 요청 간격 모의 667ms·실전 60ms(`KisTradingProperties` 기본값), 우선순위 레인(보유 시세 > 주문 > 대사 > 시세 > 조회). 주문과 조회가 한 예산을 쓰되 주문이 먼저 배정된다. 토큰 발급 간격 60초 이상을 강제한다. — 충분함(실전 한도 숫자만 미확정).
  - ✅ Upbit: 클라이언트 쪽 리미터가 없다. 조회는 429 에서만 1초 backoff 로 2번 재시도, 주문 생성은 재시도하지 않는다. `Remaining-Req` 는 읽지 않고, 418 은 일반 오류로 올라간다. 4xx/5xx 를 `log.error` 로 남겨 재시도로 성공한 429 도 Discord 로 간다.
  - ✅ 지금 부하: 분봉 폴링이 60초마다 13개 마켓을 150ms 간격으로 부른다(최대 약 6.7회/초, 시세 그룹 10회/초 안). 일봉 캐시 TTL 60초, 시세가 30초 넘게 낡을 때만 REST 티커 대체 호출. 사용자 1명 기준으로는 한도에 여유가 있다 ⚠️(사용자·마켓이 늘면 IP 단위 시세 예산을 함께 쓴다).
- **빈틈과 사고 시나리오**
  - ⚠️ **WS 가 끊기면 REST 대체 호출이 한도를 넘는다.** 시세 WS 가 30초 넘게 멈추면 엔진이 종목마다 `getTicker` 를 따로 부른다(`TradingEngine.processTicker`). 13종목을 순서대로 부르면 응답 50ms(⚠️ 추정)만으로도 1초 안에 몰려 사용자 한 명이 ticker 그룹 한도(10회/초)를 넘을 수 있고, 429 마다 두 번 재시도해 부하가 최대 3배가 된다. 429 가 쌓이면 418(IP 차단)이고, IP 단위라 분봉 수집·일봉 캐시까지 막혀 **가격을 못 얻은 tick 은 건너뛰므로 보유 포지션의 손절 평가가 멈춘다.**
  - ⚠️ 418(IP 차단) 중에도 매 tick 호출을 이어 가면 위반이 쌓여 차단이 길어진다.
  - 재시도로 성공한 429 도 ERROR 알림이 되어 알림이 시끄럽다(알림 rate limiter 가 총량은 묶는다).
  - KIS 실전 간격 60ms(약 16회/초)가 실제 한도보다 빠르면 `EGW00201` 이 나고, KIS 계획상 주문 POST 의 유량 초과는 "결과 모름"이라 종목이 사람 확인으로 멈춘다.
- **제안**
  1. REST 티커 대체를 묶음 조회 + 공유 캐시로: `/v1/ticker?markets=A,B,...` 한 번으로 여러 마켓을 받고, TTL 1~2초 캐시 하나를 모든 엔진이 거치게 한다. 사용자 수와 무관하게 초당 1회 이하가 된다.
  2. Upbit 418: 응답의 차단 시간 동안 그 그룹 호출을 멈추고 알림 1회(B2 상태와 연결).
  3. 429 는 WARN 으로 남기고 재시도까지 실패했을 때만 ERROR.
  4. Upbit 시세용 전역 토큰 버킷(그룹당 8회/초 — 한도의 80%)은 사용자·마켓을 늘릴 때 넣는다. 근거: 처리량 = 동시성 ÷ 응답시간, 응답 50~100ms(⚠️ 추정)면 직렬 호출 하나로도 10~20회/초가 나와 동시성 제한만으로는 못 막는다.
  5. KIS 실전 간격은 G1(모의 스모크)과 포털 공지로 확정한 뒤 정한다.
- **작업량**: 1 S, 2 S~M, 3 S, 4 M, 5 S
- **우선순위**: 1 **높음**(WS 장애 때 손절 경로 보호), 2 중간, 3 낮음, 4 사용자·마켓 확대 전, 5 KIS 실전 전

### B2. 거래소별 장애 격리

- **현재 상태**
  - ✅ HTTP: Upbit(`WebClientConfig.upbitWebClient`, 연결 5초·응답 10초)와 KIS(`KisClientRegistry`, 환경별 WebClient)가 각자 WebClient 를 쓰고 reactor-netty 기본 풀·이벤트 루프를 공유한다. 연결 풀은 원격 주소별로 나뉘고, 호출은 모두 suspend 라 한쪽이 느려도 스레드를 잡지 않는다 ⚠️(라이브러리 기본 동작 기준).
  - ✅ 엔진은 사용자 × 거래소마다 따로 돌아 한 거래소의 느린 응답이 다른 거래소 엔진의 루프를 막지 않는다.
  - ✅ 이미 있는 감시: 종목별 reconcile 20회 실패 halt, 시세 WS 워치독(60초 무수신 재연결), 엔진 멈춤 감시(#339), 기동 복원 health.
  - ✅ 거래소 단위 상태(정상/주의/위험)와 신규 진입 자동 중단은 없다.
- **빈틈과 사고 시나리오**
  - ⚠️ 업비트 점검(503)·418 차단 중에는 모든 종목이 매 tick 실패하고, 종목마다 20회 뒤 halt 가 쌓이며, 그 사이 신규 진입 판단은 실패한 잔고·낡은 시세 위에서 계속 돈다.
  - 다른 거래소로 자동 전환은 포지션이 달라 부적절하다(요청 전제에 동의). 반대로 장애 중 청산까지 멈추면 손실이 커지므로 "진입만 중단"이 맞다.
- **제안**: 거래소마다 `ExchangeHealth`(정상/주의/위험)를 두고 최근 오류율(5xx·타임아웃·429)·418·시세 신선도로 판정한다. 주의·위험이면 BUY 만 막고(D2 의 BUY 전용 `OrderGate` 와 같은 자리), 청산·손절은 계속하며, 상태가 바뀔 때만 알린다. 복귀는 연속 성공 기준(히스테리시스). 거래소 전용 `ConnectionProvider` 는 관측을 분리하는 정도의 이득이라 우선순위가 낮다.
- **작업량**: M~L
- **우선순위**: 중간 (KIS 를 엔진에 연결하기 전에 높음)

## C. 시세 처리

### C1. 이벤트 루프 블로킹 금지

- **현재 상태 — 충분함**
  - ✅ WS 프레임은 netty 스레드에서 작은 JSON 파싱과 채널 `trySend`(논블로킹)만 하고, 저장은 `Dispatchers.IO` 코루틴이 맵에 넣는다(`UpbitMarketFeed.tickerFlow`, `MarketDataIngestionService`). 재연결 대기는 별도 데몬 스레드에서 한다.
  - ✅ 엔진은 `Dispatchers.Default`, 주문·잔고·DB 는 모두 suspend(WebClient·R2DBC), 체결 대기는 `delay`.
  - ✅ Discord 전송은 비동기 `subscribe`, 오류 알림 appender 는 lock 안에서 HTTP 를 하지 않는다. JDBC 는 기동 때 Flyway 뿐이다. `runBlocking` 은 종료 경로(`UserTradingManager.stop`) 하나다.
- **빈틈과 사고 시나리오**
  - ⚠️ 로그인·가입의 BCrypt(`AuthController`, 강도 10)가 suspend 핸들러 안에서 바로 돈다. R2DBC 뒤에 재개되는 스레드가 netty 루프라면 요청 하나가 그 루프를 수십~100ms 점유해 시세 프레임·DB 응답이 밀릴 수 있다. 피해는 지연이고 유실은 아니며, IP 당 분당 30회 제한이 있다.
- **제안**: `matches`/`encode` 를 `withContext(Dispatchers.Default)` 로 감싼다(CPU 작업). BlockHound 는 지금 블로킹 호출이 없고 오탐 관리 비용이 커 권하지 않는다 — 넣는다면 시세 피드 스모크 시험 하나로 한정.
- **작업량**: S
- **우선순위**: 낮음

### C2. 종목별 순서와 낡은 이벤트

- **현재 상태**
  - ✅ 티커: WS 연결 하나 → 채널(FIFO) → 수집 코루틴 하나. `flatMap`·`parallel` 이 없어 같은 종목 순서가 바뀌지 않는다. 재연결은 이전 연결이 끝난 뒤에만 시작하고(워치독도 `cancelAndJoin` 뒤), 실시간만 구독해 재연결 때 스냅샷이 다시 오지 않는다.
  - ✅ 엔진이 거래소 timestamp 로 30초 넘게 낡은 시세를 버리고 REST 로 넘어간다(`TradingEngine.getRealtimePrice`).
  - ✅ 분봉은 REST 로만 받아 60초마다 최근 5개를 시간순으로 넣고, `CandleAggregator` 가 이미 확정한 분 이하의 재전송은 무시한다.
- **빈틈과 사고 시나리오**
  - ✅ 분봉 폴링이 5분 넘게 끊기면 그 사이 분봉은 다시 오지 않아 그날 일봉(및 H1·H4)의 고가·저가·거래량에서 그 구간이 빠진다. 일봉은 기동 때 한 번만 REST 로 받고 이후는 집계라, 틀어진 봉이 재시작 전까지 지표 입력(최대 60일 lookback)에 남는다. 예: 429·네트워크 장애가 10분 이어진 날 급락이 있었는데 그날 일봉 저가가 실제보다 높게 남아, 고가·저가를 쓰는 지표가 틀린 매수 신호를 낸다.
  - `MarketDataStore.updateTicker` 는 timestamp 를 비교하지 않고 덮어쓴다 — 지금 구조에서는 역순 메시지가 들어올 경로가 없어 위험은 낮다.
- **제안**: 매일 UTC 00:05 이후(또는 폴링이 오래 끊겼다 복구될 때) 직전 일봉을 REST 로 다시 받아 집계값을 거래소 확정값으로 맞춘다. 방어용으로 `updateTicker` 를 timestamp 가 같거나 새로울 때만 바꾸게 한다(한 줄).
- **작업량**: 일봉 재동기화 S~M, 단조성 검사 S
- **우선순위**: 일봉 재동기화 중간, 단조성 검사 낮음

### C3. 지연 vs 유실, 최신값만 보내기

- **현재 상태 — 충분함.** Kafka·Redis 는 이미 없고, 시세는 프로세스 안, 주문·체결의 내구성은 DB 가 맡는다.

| 역할 | 지금 방식 | 유실 허용 | 판정 |
|---|---|---|---|
| 실시간 시세 | WS → 채널(버퍼 64) → `MarketDataStore` 종목별 최신값 1개 | 허용 | 충분함(종목별 최신값만 둠, 30초 신선도 검사, 워치독 재연결) |
| 분봉·상위봉 | REST 60초 폴링(최근 5개) → store + 집계 | 5분까지만 자동 복구 | 부분(C2) |
| 매수 주문 의도 | 주문 전 DB pending, NonCancellable, 재시작 뒤 reconcile | 불가 | 충분함 |
| 매도 주문 의도 | 선기록 시도, 실패해도 전송(손절 우선), 메모리 플래그로 재기록 | 불가 | 의도된 트레이드오프, 비정상 종료 창(A1) |
| 체결·거래 기록 | 상태 + 기록 한 트랜잭션, 주문 ID 로 중복 방지 | 불가 | 충분함 |
| 체결·오류 알림 | 커밋 뒤 비동기 전송 / rate limiter + digest | 허용(정본은 DB·로그) | 충분함 |
| 봇 시작·정지 | `bot_state` DB | 불가 | 충분함 |

- **빈틈**: 채널이 차서 버려진 tick 은 신호가 없다 — 소비 쪽이 맵에 넣기만 해 실제로 찰 일은 거의 없다. 전역 `conflate()`·`DROP_OLDEST` 는 한 flow 에 여러 종목이 지나 다른 종목 tick 까지 버리므로 쓰지 않는다.
- **제안**: (선택) `trySend` 실패를 세어 일정 비율 이상이면 WARN.
- **작업량**: S
- **우선순위**: 낮음

## D. 검증과 운영

### D1. 섀도 모드와 리플레이

- **현재 상태**
  - ✅ 섀도 관측기(`engine/ShadowExitObserver.kt`, 기본 off): 후보 트레일링 파라미터를 라이브 판정 뒤에 평가해 포지션당 첫 발동을 `shadow_exit_observation` 에 남긴다(#178). 예외는 삼켜 매매에 영향이 없다.
  - ✅ 백테스트·리플레이 코드와 리플레이용 시세 저장은 없다. 시세는 메모리에만 있다.
  - ✅ 판단 로직은 이미 순수 함수에 가깝다(`CombinedStrategy.shouldBuy`, `ExitGates`, `checkStopLoss`·`checkTakeProfit`·`checkTrailingStop`).
  - ✅ Clock 주입: `TradingEngine`·`PositionManager`·`DailyResetManager` 등이 `Clock` 을 받지만, 판단에 쓰이는 몇 곳이 벽시계를 직접 쓴다 — `PositionManager.completeBuy`·`applySellFillOutcome`·`recoverSellWithoutOrder`·`completeSellAtomically` 의 `LocalDateTime.now(KST)`(거래일·buyDate → 당일 매수 여부·보유 상한 청산), `TradingEngine.getRealtimePrice` 의 `System.currentTimeMillis()`(시세 신선도), `MarketDataIngestionService.seedDailyCandles` 의 `Instant.now()`. 기본 Clock 의 zone 도 섞여 있다(`DailyResetManager` 만 KST, 나머지 UTC).
- **빈틈과 사고 시나리오**
  - KST 09:00 경계 근처의 매수 거래일·보유일 계산을 고정 Clock 시험으로 묶을 수 없어, 보유 상한 초과 같은 경계 회귀가 다시 생겨도 시험이 잡지 못한다.
  - 청산 로직을 바꾼 뒤 "지난주 실제 시세 경로에서 손절·트레일링이 어떻게 달라졌나"를 재현할 수 없다. 익절·손절 파라미터 변경은 실매매가 곧 실험이 된다.
- **제안**
  1. 벽시계 직접 호출 4~5곳을 주입된 Clock 으로 바꾸고, 거래일 zone 은 한 곳(`TradingDay.of(clock.withZone(KST))`)에서만 정한다. 09:00 경계 시험 1~2개. 운영 동작은 바뀌지 않는다.
  2. (선택) 보유 중인 종목의 판단 입력(가격, 거래소 timestamp, 수신 시각)만 PostgreSQL 테이블에 추가로 남기고, 순수 함수인 청산 판정을 그 기록에 다시 돌리는 오프라인 시험을 둔다. 새 인프라 없음.
  3. 섀도 범위 확대(손절·익절 후보)는 #178 관측 결과를 본 뒤 정한다.
- **작업량**: 1 S, 2 M, 3 S~M
- **우선순위**: 1 중간, 2 중하, 3 낮음

### D2. 배포 없는 설정 변경

- **현재 상태**
  - ✅ 런타임에 바꿀 수 있는 것: 사용자별 봇 시작·정지(`/api/bot/start|stop`, `bot_state` 에 저장), 종목 halt 해제, 정지 상태의 pending 해제, 웹훅·키 저장 뒤 엔진 재설정.
  - ✅ 배포가 필요한 것: 청산·주문 파라미터 `TRADING_*`, 섀도·watchlist·워치독·KIS 설정. 전략은 `CombinedStrategy` 하나라 고를 개념이 없다.
  - ✅ **전역 킬스위치가 없다.** 모든 사용자의 신규 진입만 막는 수단이 없고, halt 를 손으로 거는 API 도 없다(해제만 있음). `/bot/stop` 은 손절 평가까지 멈춘다.
  - ✅ 끼울 자리는 있다: `exchange/OrderGate.mayPlace(ticker, side, now)` 가 매수·매도 주문 직전에 불리고, 업비트는 지금 `OrderGate.OPEN`(항상 허용)이다.
- **빈틈과 사고 시나리오**: 상장폐지 공지·급락장·업비트 장애 직후처럼 "전 사용자 신규 매수만 즉시 중지, 보유분 손절·청산은 계속"이 필요할 때 수단이 없다. 지금은 각 사용자가 봇을 멈추거나(손절도 멈춤) env 를 바꿔 배포해야 하고(수 분, 재기동 중 손절 공백), 재기동하면 `running=true` 인 봇이 복원된다.
- **제안**: DB 기반 진입 차단 플래그 — `runtime_flags`(범위: 전역·거래소·종목, `entries_enabled`, 사유, 바꾼 사람·시각) 테이블과 5~10초 TTL 로컬 캐시, **BUY 만 막는** `OrderGate` 구현(SELL 은 통과해 손절이 계속 돈다), DB 조회 실패 시 마지막 값 유지 + ERROR, 관리자 엔드포인트로 변경 + 변경 알림. Redis 없이 PostgreSQL 과 메모리 캐시면 된다. A4·B2 의 자동 차단도 이 플래그를 켜는 방식으로 연결한다. 청산 파라미터·rate limit·전략을 런타임 변경으로 옮기는 것은 진입 시점 스냅샷과 preflight 검증을 우회하게 되어 권하지 않는다 — 배포 경로 유지가 맞다(충분함).
- **작업량**: S~M
- **우선순위**: **높음**

### D3. 지연 측정과 알림 중복 억제

- **현재 상태**
  - ✅ 구간 지연 지표가 없다: Micrometer 커스텀 지표 없음, actuator 노출은 health·info 뿐. 측정되는 것은 시세 나이(30초 신선도 판정에만 쓰고 기록하지 않음)와 엔진 멈춤(#339, 5분 임계)뿐이다. 매도는 판단 시각(`pendingSellSince`)을 저장하지만, 주문 전송·접수·체결 시각은 거래 기록에 없다(`TradeRecord.createdAt` 은 커밋 시각).
  - ✅ 알림 중복 억제는 충분함: `ErrorAlertRateLimiter`(같은 지문 5분 1회, 분당 5건 상한, 넘친 것은 60초 digest), `DiscordNotifier` 의 429 Retry-After 처리, 감시기마다 에피소드당 1회(엔진 멈춤, `[HALT]`, `[REVIEW]`, 막힌 매도, 보유 상한 초과, dust).
- **빈틈과 사고 시나리오**
  - ✅ 시세 피드가 끊겨도 Discord 가 조용하다: WS 무수신 재연결, 낡은 시세, 일봉 미갱신이 모두 WARN 이라 Discord 로 가지 않는다. 엔진은 REST 대체로 계속 매매하지만 종목당 REST 호출이 늘고, REST 까지 실패해 ERROR 가 처음 나올 때는 원인이 몇 시간 지난 뒤다.
  - 종목이 늘거나 업비트가 느려 tick 한 바퀴가 길어져도 5분 멈춤 임계 전까지는 아무 신호가 없다.
- **제안**
  1. 시세 피드 무수신이 N분 이어지면 에피소드당 ERROR 1회, 회복하면 INFO 1회(`MarketDataIngestionService.checkTickerHealth`, `EngineLivenessMonitor` 와 같은 모양).
  2. 주문 경로 지연을 구조화 로그 한 줄로: 시세 나이, 판단→전송, 전송→접수, 접수→체결(`PositionManager.placeBuy`·`sell`, D1 의 주입 Clock 으로 시험 가능). 수집 인프라가 없으니 로그로 시작한다.
  3. tick 한 바퀴 소요 시간이 `intervalSeconds × k` 를 넘으면 에피소드당 WARN.
- **작업량**: 각 S
- **우선순위**: 1 중상, 2·3 중간

## 2단계 후보 (추천 순)

| 순서 | 항목 | 이유 | 작업량 | 관련 이슈 |
|---|---|---|---|---|
| 1 | A4-1 매도 수량을 봇 보유분으로 묶기(또는 최소 README 경고) | 사용자 코인을 실제로 팔 수 있는 유일한 경로 | S~M | 새 이슈 |
| 2 | B1-1 REST 티커 대체를 묶음 조회 + 공유 캐시로 | WS 장애가 418 차단으로 번져 손절이 멈추는 경로를 막음, 작음 | S | 새 이슈 |
| 3 | D2 전역·종목 진입 차단 플래그(BUY 전용 `OrderGate`) | 장애 대응 수단이 "손절까지 멈추는 정지"나 "배포"뿐 | S~M | 새 이슈 |
| 4 | D3-1 시세 피드 무수신 알림 | 무알림 장애, 작고 독립적 | S | 새 이슈 |
| 5 | A2 중복 방지 키에 거래소 추가 | KIS 연결 전 필수, 작음 | S | KIS 계획 |
| 6 | D1-1 판단 경로 Clock 주입 | 돈과 직결되는 09:00 경계를 시험으로 묶음 | S | 새 이슈 |
| 7 | C2 일봉 재동기화 | 전략 입력 정확성 | S~M | 새 이슈 |
| 8 | A4-2 하루 한 번 대사 | 기록 누락을 찾는 마지막 안전망 | M | #341 |
| 9 | A1-2 응답 없는 매도 재전송 | 손절 지연 약 80초 단축 | M | #340 |
| 10 | B1-2 418 차단 처리 / B2 거래소 상태 | KIS 연결 전에 함께 | S~M / M~L | 새 이슈 |

"충분함"으로 둔 것: A2(KIS 키 문제 제외), A3, C1(BCrypt 제외), C3, 알림 중복 억제(D3), 청산 파라미터의 배포 경로(D2).

## 확인하지 못한 것

- KIS 실전·모의 초당 한도와 WebSocket 한도(공식 공개 문서에 숫자 없음), KIS ODNO 의 고유 범위.
- 업비트 끝난 주문을 얼마나 오래전까지 조회할 수 있는지(문서에 보존 기간 없음).
- 로그인 BCrypt 가 실제로 netty 루프에서 도는지(코드·라이브러리 기본값으로 추정, 실측하지 않음).
- compose 재생성 때 옛·새 앱 컨테이너가 겹치지 않는다는 것(표준 동작 기준, 이번에 실측하지 않음).
