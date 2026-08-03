---
title: stock-bot-kis — KIS(한국투자) 주식 자동매매 봇 기반 + 주문유실 방지(WAL reconcile)
status: done
started: 2026-06-14
updated: 2026-07-29
---

# Goal

기존 Upbit 크립토 봇과 같은 인프라(보안/영속화/config/WebClient) 위에 **한국투자증권 KIS OpenAPI 기반 주식 자동매매 봇의 기반(foundation)** 을 별도 worktree(`stock-bot-kis`)에 추가한다. 이번 Phase 1 범위는 "주식 주문을 안전하게 내고 결정론적으로 reconcile" 까지 — 전략 루프/시세수집/UI 는 Phase 2+ 로 분리.

핵심 요구: **주문누락/유실 방지** — 크립토 봇은 `pendingBuyUuid` 가 메모리 전용이라 "주문 직후 프로세스 사망 → 미체결 주문 orphan" 갭이 있다(TradingState.kt:19, migration V1~V13 에 컬럼 없음 확인). 주식은 지정가가 장중 내내 미체결로 남을 수 있어 갭이 더 크다. 이를 **DB write-ahead 주문로그(WAL) + reconcile 상태기계** 로 결정론적으로 막는다.

결정사항(사용자): 브로커=**KIS**, 범위=**기반부터 단계적(Phase 1)**, 시작모드=**실거래 직행**(→ dry-run/notional 가드를 안전망으로 강화).

# Progress

- 2026-06-14: worktree `stock-bot-kis` 생성(.env 2개 복사). 브로커 비교 리서치(KIS 1순위 확정), 기존 아키텍처 6영역 Explore, 크립토 reconcile 한계 코드 확인. draft plan 작성.
- 2026-06-14: plan-reviewer(Claude)+codex 병행 리뷰 → CRITICAL 2 / HIGH 6 / MEDIUM 5. 전부 수용해 plan 전면 보강(D4 트랜잭션 3분리, D5 폐기·dry-run 신설, D7~D13 추가, Phase 1a~1e 재분할). `# Review Disposition` 기록.
- 2026-06-14: 사용자 승인("진행해"). **1a [researcher] KIS 스펙 확정 완료** → D14 추가. D11 해소(`user_exchange_keys` 미배선 잔재 확인 grep 0건 → `users.kis_*` 컬럼). 구현(1b~) 착수.
- 2026-06-14: **1b~1e 구현 완료**. migration V14(WAL)/V15(키), KisProperties/Config, KisDtos, KisClient/Impl/TokenProvider/Factory, StockOrderStatus/Service/Reconciler, StockOrderIntent 엔티티/repo, UserEntity+UserSecretsService KIS 확장. application.yml/.env.example×2/README/PROJECT_ANALYSIS 동기화. **테스트 26개(KIS) / 전체 387개 통과, 0 실패** (JDK 21 로 빌드 — 로컬 default JDK25 가 Gradle8.12 Kotlin DSL 비호환이라 `JAVA_HOME=jbr-21.0.9` 지정 필요).
- 2026-06-14: **code-reviewer(Claude) 완료 — Critical 1 + Major 5 + minor. codex 병행은 환경(MCP/sandbox) 오류로 결론 미산출.** 수정: C1 연속조회 페이징, M1/M4 시장가 price 가드(0/실패 시 예외+버퍼), M-a 미사용 프로퍼티 제거, getHoldings 테스트 추가. 범위조정: M2 positions 반영 Phase2 이관(D6/D9 정정). 후속 defer: M3/M5/M-b/M-c/M-f. (feat 0d9e409, chore(plan) e1bdf24 — 미push)
- 2026-06-14: 사용자 결정 — **실계정 모의투자 스모크부터**. env-gated `KisPaperSmokeTest`(KIS_SMOKE=true 일 때만) 추가: read 경로(token/현재가/잔고/당일조회) + 선택 주문경로(KIS_SMOKE_PLACE, 1주 지정가). 사용자가 본인 모의 자격증명으로 직접 실행 → 결과(필드/파라미터/tr_id 적합성) 회신 대기.
- 2026-06-14: 사용자 질문 반영 — (1) **해외주식**: KIS 는 `/uapi/overseas-stock/...`(별도 tr_id/필드, 같은 키)로 지원하나 현재 구현은 국내 전용 → 해외는 별도 작업(D15). (2) **.env 전역 자격증명 자동사용**: Upbit 패턴 미러로 `KisProperties`(appKey/appSecret/cano/acntPrdtCd/paper) + `KisClientFactory.defaultClient()` + application.yml + .env.example×2 + compose×2 + deploy.sh render_server_env 전달(PR#9 footgun 방지) 추가. 스모크도 동일 env(KIS_APP_KEY…)로 통일 — .env 한 곳에 넣으면 앱·스모크 공용. 전역 .env 평문은 self-host 표준(보안 무이슈), DB 경로(멀티유저)는 암호화 유지. **391 테스트 0 실패.**
- 2026-06-14: 사용자 방향전환 — **유저별 입력이 주 경로**(.env 전역은 operator/dev fallback 으로 유지, Upbit 패턴 동일). 구현: `POST /api/user/kis-keys`(인증·본인만) + getMe `has_kis_keys`/`kis_paper`, `RequestValidators` KIS 검증(ASCII printable·길이·CANO 8/PRDT 2), `UserSecretsService.encryptKisKeys`(**appKey/appSecret 둘 다 AES-GCM**), `KisClientFactory.invalidate`, SPA Settings 폼(api.js/screens.jsx). **code-reviewer(Claude)+codex 병행** → HIGH 1(sanitizer KIS 키 유출)·Major 2(검증 byte/ASCII, stale client race)·minor 수정: LogMessageSanitizer 에 appkey/appsecret/CANO 마스킹, validator ASCII 제약, KisClientFactory fingerprint 캐시, has_kis_keys 4필드. **398 테스트 0 실패.** (미push)
- 2026-06-14: 사용자 "이어서 진행해" → **vertical slice 2a: 수동 주식주문 엔드포인트**. `KisTradeController`(POST /api/kis/order, GET /api/kis/orders) — 등록된 유저 KIS 키(forUser)로 `StockOrderService`(WAL) 경유 주문 + 주문조회. RequestValidators.normalizeKisSymbol(6자리), StockOrderIntentRepository.findByUserId. live-enabled=false 면 DRY_RUN, keys 4필드 가드(forUser 500 방지). **403 테스트 0 실패.** code-reviewer subagent 도구오류로 미산출 → 메인 자체 보안점검(인가·실주문가드·검증·유출·WAL정합) 통과. (미push)

- 2026-06-19: **2c 자율엔진 + 2b SPA 구현 완료(MVP, 기본 dry-run)**. design 워크플로(KIS캔들 스펙+크립토매핑+설계+적대적비판) → 안전핵심 메인 직접 작성. marketdata(KisMarketCalendar 장시간게이트·StockCandleAdapter·KisMarketDataService 폴링), engine(StockPosition·StockPositionManager WAL경유 C1/M2/M3·KisStockTradingEngine runLoop·decideSell·StockUserTradingManager 부팅reconcile-후-기동), StockBotController(/api/stock/*), SPA 주식화면. 캔들 API(getDailyCandles FHKST03010100)+getBalance 추가. **425 테스트 0 실패(신규 22).** **code-reviewer(Claude+codex 병행)** → Critical 3/Major 다수. 즉시수정: C-A getBalance rt_cd 검증, C-B inquireDailyConclusions rt_cd 검증, 엔진 잔고조회 실패 시 패스 skip, M-A boughtToday 거래일 리셋, M-C startBot reconcileNow 선행+전략검증. **실거래 전 필수**(Blockers 로 이관): C-C 계좌단위 현금예약(다종목 미수), M-B 수동주문 시장시간 게이트, M-D REST폴백 캐시, M-E 일봉 장중 whipsaw. (미push)
- 2026-07-28: **동결 → 재조사 → 재개**. (1) #49 큐에서 ❄️동결 결정(존속/폐기 보류). (2) 같은 날 브로커 API 재조사(#59) — 토스증권 Open API 를 후보로 평가하고 Claude 1차 조사 → Codex 교차검증(사실오류 8건) → 스펙 원본 재검증. **결론: KIS 유지 확정**(D23). (3) 사용자 지시로 동결 해제·작업 재개. 코드 변경 없음 — 브랜치는 `2be1fe3` 그대로(clean, origin 과 동일, main 대비 **behind 17 / ahead 10**).
- 2026-07-28: **rebase onto origin/main 완료**. behind 17 → 0. 충돌 3건 해소 — `PROJECT_ANALYSIS.md`/`README.md`(문서, main 재구성분 유지 + KIS 항목 이식), `UserTradingManager.kt`(main 의 `restoreAllRunningBots()` 추출 + SmartLifecycle 구조를 살리고 D22 의 `EXCHANGE="UPBIT"` 스코프 좁히기 4곳을 재적용 — 기존 `companion object` 에 상수 편입). migration **V14~V16 → V15~V17 renumber**(main 이 V14 선점) + 참조 동반 수정(README·PROJECT_ANALYSIS 표에 V15~V17 행 추가·UserEntity/BotStateEntity 주석·WAL 주석의 폐기된 `positions` 참조). rebase 로 합류한 main 테스트가 옛 `findByRunningTrue()` 를 mock 해 컴파일 실패 → `findByRunningTrueAndExchange("UPBIT")` 6곳 정합. **535 테스트 0 실패**(rebase 전 baseline 425 → main 합류 110 증가).

- 2026-07-28: **실거래 블로커 해소(D24) 구현 완료 — 543 테스트 0 실패**(직전 535 + 신규 8). dlc structural. plan-review 는 **codex 단독**(Claude subagent 는 이 세션 정책상 미사용 — CLAUDE.md §9 의 "미가용 시 사유 명시" 준용) → Critical 6/Major 3 지적을 코드로 검증해 **범위를 재조정**(사용자 결정 "안전한 것부터"). 구현: ①C-C 축소 — `getBuyableQty`(inquire-psbl-order, TTTC8908R/ORD_DVSN=01) 신규 + 매수수량 최종 상한 + 조회실패 fail-closed ②`FAILED`/`REJECTED` 는 `boughtToday` 미소모(codex C3) ③M-B `KisTradeController` 시장시간 게이트(422, WAL 앞단) ④M-D 엔진 로컬 TTL 캐시(price 5s/candle 300s) + 지수 backoff + store ticker 신선도 판정 ⑤**기존 Critical 정렬 버그** — `getDailyCandles` 가 ascending 이라 store 미스 시 지표가 뒤집혀 계산되던 것을 descending 으로 근본 수정 ⑥`notional` Long 오버플로로 `maxOrderAmount` 우회되던 것을 `multiplyExact` 로 차단(codex C6). **M-E 와 durable 현금예약·재시작 안전성은 `# Deferred`** — 전략 의미 변경/스키마 변경이라 별도 작업.
- 2026-07-29: **#64 재시작 안전성 구현 완료 — 560 테스트 0 실패**(직전 552 + 신규 8). migration **V18 `stock_position_state`** + `StockPositionStateEntity/Repository/Service` + 엔진 배선. 설계는 크립토 `trading_states`(V14) 이식 — **보유수량·평단은 저장하지 않는다**(거래소 잔고가 진실, 어긋나면 유령 포지션). 저장 대상은 거래소가 모르는 3가지: `peak_price`(트레일링 고점)·`bought_date`(당일 게이트 근거일)·`entry_strategy`. **추가 발견**: 거래일 판정이 엔진 메모리 `lastTradingDay` 라 재시작 시 무조건 리셋 → 당일 재진입이 뚫렸다. 판정 근거를 포지션별 `boughtDate` 로 옮기고 복원 시 `boughtDate == 오늘` 로 `boughtToday` 를 **재계산**한다. 쓰기는 `durableDirty` 플래그로 변경 시점에만(고점은 tick 마다 안 오른다). 복원은 엔진 기동 **전**에 수행하고 실패 시 throw(fail-closed). 문서 동기화: README·PROJECT_ANALYSIS V18 행.
- 2026-07-29: **code-review(codex) fix loop 1회차 완료 — 552 테스트 0 실패**(직전 543 + 신규 9). Critical 1/Major 5/Minor 3 전건 반영. 핵심: ①매수가능수량 상한을 엔진 sizing 에서 **공용 경계 `StockOrderService.validate`** 로 옮겨 수동 REST 우회를 차단(C1) ②`FAILED` 즉시 재시도가 tick 마다 재전송되던 **직전 커밋의 회귀**를 `StockPosition` 지수 backoff 로 수정(M3) ③시장시간을 송신 직전 재검증(M4) ④캐시·backoff 를 `FallbackCache` 로 추출해 시간원 주입 → **테스트 불가였던 M-D 를 실제로 검증**(M5 — 이전 Acceptance 체크가 근거 없었음을 정정) ⑤price/candle backoff 분리(M1) ⑥dead `REJECTED` 분기 제거(M2) ⑦DRY_RUN 장외 허용·`CancellationException` re-throw·단조시계 TTL(Minor 3건).

# Next

**이 plan 은 done** — PR #63 이 2026-07-29 main 에 squash merge(`49573b3`) 되고 worktree·브랜치를 정리했다. 후속은 전부 GitHub 이슈로 이관됐다: 실거래 선행 **#67**(reconcile 실패 은폐)·통합 테스트·실계정 스모크, 그 외 **#65 #66 #68 #69 #70**. 새 작업은 이 plan 을 잇지 말고 해당 이슈에서 새 worktree 로 시작할 것.

<details><summary>머지 시점 상태(참고)</summary>

**완료: 실거래 블로커 해소(D24) + code-review fix loop 1회차** — 552 테스트 0 실패, `# Acceptance` 전 항목 증거 확보. 다음 후보:

- **(b) 실계정 모의투자 스모크** — `KisPaperSmokeTest`(env-gated). 사용자 KIS 모의 자격증명 필요. 미확정 스펙(필수 query 파라미터·tr_cont·ODNO 자릿수·rate limit 실값) 실측 경로.
- ~~**(c) force push + PR**~~ — **완료**(2026-07-29). `--force-with-lease` 로 push(구 tip `2be1fe3` → `700d69a`), **PR #63** 생성. pre-push codex 리뷰는 워크스페이스 크레딧 소진으로 `CODEX_SKIP=1` 우회(같은 diff 의 code-review 는 이미 반영).
- **(d) 데이터 계층 분리 설계**(D23) — KRX 원천 append-only 적재. `stock-quant-strategy` Phase 0 의 생존편향 해결 경로.

</details>

**Phase 2 백로그 (후속 GitHub Issue 로 분리)**

- **(진행중) 실계정 모의투자 스모크**: 사용자가 `KisPaperSmokeTest`(또는 `/api/kis/order`, 주식화면) 로 KIS 모의 read/주문 응답 적합성 확인. 실패 항목(필드/파라미터/tr_id) 나오면 KisClientImpl 수정.
- **SPA 수동 UI 검증**: 주식화면(babel-standalone, 빌드검증 없음) 브라우저 동작 확인.
- **D15 해외주식(미국 등)**: `/uapi/overseas-stock/...` 클라이언트 메서드(주문/조회/시세, tr_id·OVRS_EXCG_CD·통화 다름) 추가. 같은 appkey/계좌. 정확 스펙은 공식 문서 대조 후(추측 금지).
- **체결→positions 반영**(M2): 엔진이 포지션을 소비할 때 idempotent upsert.
- **주문취소 WAL**(D6): CANCEL_REQUESTED/CANCEL_UNKNOWN 상태기계.
- **NEEDS_REVIEW 수동 해소 API/runbook**(M-c): partial unique index 가 활성 슬롯을 점유하므로 미해결 시 종목 잠김.
- **KisClientFactory.invalidate 배선**(M-b): 키 등록/변경 UI 시 stale client 무효화.
- **멀티인스턴스 reconcile lease**(D10): `FOR UPDATE SKIP LOCKED` — 현재 in-process AtomicBoolean 가드는 단일 인스턴스 한정.
- **통합 테스트**(M5): Testcontainers-Postgres 로 tx 원자성 + partial unique index 실DB 검증.
- **실계정 스모크**: KIS inquiry/balance 필수 query 파라미터 집합, tr_cont 연속조회 값, ODNO/org_no 자릿수, sll_buy_dvsn 매핑, 토큰 재발급/ rate limit 실값.

# Decisions

## D1. 모듈 배치 = 기존 `bot` 모듈에 `kis` 하위 패키지 신설 (별도 gradle 모듈 X)
- 이유: 보안(`SecretsCrypto`/`SecretKeyMaterialProvider`), 영속화(R2DBC+Flyway), config, `WebClientConfig`, `UserEntity` 재사용. 별도 모듈은 Spring 배선 중복만 늘린다.
- 패키지: `com.trading.bot.kis.{client,order,domain,config}`. Upbit 코드와 분리. "새로 하나"=논리적 분리(독립 client·order service·후속 engine), 인프라 공유. 물리적 분리 원하면 재논의.

## D2. KIS 인증 = OAuth2 access_token 24h 캐싱 (요청마다 발급 금지)
- KIS 는 appkey+appsecret→access_token, 만료 24h, **연속 재발급 시 차단**. Upbit(요청마다 JWT)와 구조 다름.
- `KisTokenProvider`: 사용자별 토큰 캐시 + 만료 임박 시에만 재발급, **thread-safe**(Mutex). **단일점 방어**: 토큰 발급 실패 시 backoff + 해당 유저 주문/reconcile 차단(전역 정지 아님) + ERROR 알림.

## D3. 주문은 비멱등 — 자동 재시도 금지 (Upbit placeOrder 와 동일 원칙)
- idempotency key 없음(3사 공통). `placeOrder` 는 429/타임아웃 재시도 안 함 → WAL reconcile 책임. KIS 응답 `ODNO`+`KRX_FWDG_ORD_ORGNO`로 이후 조회/취소.

## D4. 주문유실 방지 = WAL 주문로그 + reconcile 상태기계 [리뷰 반영 — CRITICAL 1·3 수정]
- 테이블 `stock_order_intent`(V14): `id, user_id, exchange, account_no, symbol, side, order_type, qty(정수), price(원화 DECIMAL/정수), status, odno, org_no, order_date(KST), executed_qty(정수), fail_reason, created_at, updated_at`.
- **트랜잭션 경계(핵심)**: ❗한 트랜잭션 금지(롤백 시 WAL 소멸 = 갭 재현). **`tx1: INSERT(SUBMITTING) commit` → (트랜잭션 밖) `placeOrder` → `tx2: 조건부 UPDATE`** 3단 분리. 모든 전이는 `UPDATE ... WHERE id=? AND status=<expected>` 로 하고 **rows-affected 검증**(낙관락 대용 — 코드베이스 `@Version`/`Persistable` 미사용 확인).
- **불변식: (user_id, exchange, account_no, symbol) 당 비terminal 1건** → 앱 Mutex 아닌 **DB partial unique index**(`WHERE status IN (비terminal)`)로 강제. @Scheduled/수동API/멀티인스턴스 우회 방지. 코드베이스 선례: positions(V11:37)·bot_configs(V12:25) partial unique.

## D5. [폐기 → 재설계] dry-run/notional 안전망 (실거래 직행 보완) [리뷰 반영 — CRITICAL 2]
- ❌ 기존 D5 "V13 trade_mode 재사용" 폐기 — `trade_mode` 는 SWING/SCALP(전략종류, `BotConfigEntity.kt:14`)이지 dry-run 아님. 재사용 시 Upbit 봇 회귀.
- ✅ 신규 플래그 `kis.trading.live-enabled`(기본 false=dry-run): false 면 WAL 에 의도만 기록(`DRY_RUN`)하고 placeOrder 미호출. + `kis.trading.max-order-amount` notional 상한 초과 거부. 기본값=안전쪽 → 운영 롤백 스위치 겸용.

## D6. Phase 1 범위 경계 [리뷰 반영 — HIGH 8: 취소 Phase2 이동, code-review M2: positions Phase2 이동]
- 포함: KIS client(token/잔고/주문/주문조회/시세) + 도메인 + KisProperties + KIS 키 암호화 + WAL OrderService/Reconciler + **체결→trade_executions audit idempotent 기록** + 테스트.
- 제외(Phase2): **positions 반영**(M2 — 엔진이 포지션 소비할 때), 주문취소 WAL, 시세수집/전략/UI/멀티심볼.
- 제외(후속 Issue): **주문취소 WAL 흐름**(CANCEL_REQUESTED/CANCEL_UNKNOWN 상태기계 별도 — KisClient.cancelOrder 저수준 호출만 두고 WAL 추적은 Phase2), 주식 시세수집 이식, 전략 루프/엔진 배선, UI, 멀티심볼 스케줄러.

## D7. 상태기계 전이 [리뷰 반영 — HIGH 4·5]
- 상태: `SUBMITTING → {PLACED | FAILED | UNKNOWN}`, `PLACED/PARTIAL → {FILLED | PARTIAL | CANCELLED | REJECTED}`, `UNKNOWN/SUBMITTING(stale) → {PLACED | NEEDS_REVIEW}`. terminal: `FILLED/CANCELLED/REJECTED/FAILED`. **non-terminal**: SUBMITTING/PLACED/PARTIAL/UNKNOWN/NEEDS_REVIEW.
- **SUBMITTING stale 복구**: INSERT 직후~placeOrder 응답 전 사망 행 → 부팅/주기 reconcile 이 UNKNOWN 과 동일 경로(당일조회 매칭)로 판정.
- **FAILED 한정**: 브로커 4xx 미접수/로컬검증 실패가 **확정**된 경우만. 당일조회 0건/모호는 절대 FAILED 금지 → `NEEDS_REVIEW` + durable 알림(미인지 보유 방지).

## D8. UNKNOWN 매칭 안전성 [리뷰 반영 — HIGH 6]
- 당일주문조회 매칭 키 = `account_no + symbol + side + order_type + price + qty + KST order_date`(+ broker raw status). **정확히 1건만 PLACED 링크**. 0건 또는 2건+ = `NEEDS_REVIEW`(terminal 금지). D4 불변식이 1차 방어.

## D9. 체결→기존 도메인 반영 idempotency [리뷰 반영 — HIGH 7; code-review M2 로 범위 축소]
- Phase1 구현: reconcile 이 터미널(FILLED/CANCELLED) + 체결>0 발견 시 `trade_executions`(audit)에 1회 기록. **중복 방지**: 터미널 전이를 조건부 UPDATE(`WHERE status=expected`)로 단일화 + `audit_recorded` 플래그 `claimAudit`(`WHERE audit_recorded=false`) — 둘을 한 트랜잭션으로(`finalizeTerminal`). exchange_order_id=ODNO 기록. (기존 `trade_executions` 에 unique 제약 추가는 Upbit audit 회귀 위험이라 미적용 — 플래그로 멱등 달성.)
- `positions` 반영은 **Phase2**(M2) — 현재 stock 엔진이 포지션을 소비하지 않아 미작성(unread 상태 방지). 엔진 도입 시 부팅 getHoldings 동기화 + 체결 upsert.

## D10. reconcile 동시성·순서 [리뷰 반영 — 누락 시나리오]
- 부팅 reconcile 은 **stock engine 신규주문 시작 전 완료** 보장(UserTradingManager.restoreOnStartup 의 `scope.launch` 비동기 선례 = 겹침 위험). Phase1 은 엔진 미배선이라 순서 제약은 Phase2 게이트로 명시.
- reconcile 중복 실행(여러 @Scheduled 틱 / 부팅+주기 겹침) → `FOR UPDATE SKIP LOCKED` 또는 행 lease.
- 부분체결 행이 장 마감 자동만료 시 잔여수량 처리 경로 명시.

## D11. [해소] KIS 키 저장 = `users.kis_*` 컬럼 (후보 A)
- `user_exchange_keys`(V12) 는 **entity/repo 미배선 잔재**(grep 0건 확인) + KIS 필수 계좌식별자(CANO/ACNT_PRDT_CD) 컬럼 없음. → wired 패턴인 `users.upbit_*` 미러가 일관·저비용.
- V15: `users` 에 `kis_app_key`, `kis_app_secret`, `kis_account_no`(CANO-ACNT_PRDT_CD 형식 or 분리 2컬럼), `kis_paper`(모의 여부) 추가. app_secret 만 암호화(appkey 는 식별자라 평문도 무방하나 일관성 위해 동일 암호화). 멀티거래소 통합 리팩터는 후속.

## D14. [1a 확정] KIS API 스펙 (추측 금지 — 공식 GitHub examples_llm 대조)
- base URL: 실전 `https://openapi.koreainvestment.com:9443` / 모의 `https://openapivts.koreainvestment.com:29443`.
- 토큰: POST `/oauth2/tokenP` body `{grant_type:"client_credentials", appkey, appsecret}` → `access_token`, `expires_in`, `access_token_token_expired`. 24h 캐싱(요청마다 발급 금지 — 재발급 제한). 재발급 정확정책/rate limit 수치는 ❌미확정(계정별 확인) — 24h 캐싱+429 backoff 로 방어.
- 현금주문 POST `/uapi/domestic-stock/v1/trading/order-cash`, **tr_id 신버전**: 실전 매수 `TTTC0012U`/매도 `TTTC0011U`, 모의 `VTTC0012U`/`VTTC0011U`. body: `CANO, ACNT_PRDT_CD, PDNO, ORD_DVSN(00지정가/01시장가), ORD_QTY, ORD_UNPR(시장가 "0"), EXCG_ID_DVSN_CD="KRX"(신규 필수)`. 응답 output: `ODNO, KRX_FWDG_ORD_ORGNO, ORD_TMD`. 판정 `rt_cd=="0"`, `msg_cd/msg1`. hashkey 선택(미사용).
- 정정/취소 POST `/uapi/domestic-stock/v1/trading/order-rvsecncl` tr_id 실전 `TTTC0013U`. body: `KRX_FWDG_ORD_ORGNO + ORGN_ODNO + RVSE_CNCL_DVSN_CD(01정정/02취소) + QTY_ALL_ORD_YN ...`. (Phase2)
- 미체결/체결 조회: `inquire-psbl-rvsecncl`(GET tr_id `TTTC0084R`, 정정취소가능=살아있는 미체결만, `psbl_qty`) + `inquire-daily-ccld`(GET tr_id `TTTC0081R` 3개월내/`CTSC9215R` 이전; 기간 `INQR_STRT_DT/END_DT`, `CCLD_DVSN=02` 미체결; output1 `odno/tot_ccld_qty/rmn_qty/cncl_yn`). **부팅 reconcile 1차=psbl-rvsecncl, 보조=daily-ccld(ODNO 매칭)**.
- 잔고 GET `/uapi/domestic-stock/v1/trading/inquire-balance` tr_id `TTTC8434R`. output1 보유종목(`pdno/hldg_qty/ord_psbl_qty/pchs_avg_pric`), output2 계좌(`dnca_tot_amt/nxdy_excc_amt/prvs_rcdl_excc_amt`). **주문가능현금은 별도** `inquire-psbl-order`(tr_id `TTTC8908R`, `ord_psbl_cash`).
- 현재가 GET `/uapi/domestic-stock/v1/quotations/inquire-price` tr_id `FHKST01010100` query `FID_COND_MRKT_DIV_CODE="J", FID_INPUT_ISCD=종목6자리` → `stck_prpr`.
- 공통 헤더: `authorization: Bearer <token>, appkey, appsecret, tr_id, custtype="P", content-type: application/json`. 연속조회 `tr_cont` + `CTX_AREA_FK100/NK100`.
- ❗**KIS 국내 일반주문은 당일 한정(GTC 미지원)** — 전일 미체결은 통상 장마감 자동실효 → 부팅 reconcile 의 "전일 주문" 케이스 거의 없음(D10 단순화). 단 당일 재시작 갭은 여전히 reconcile 필요.

## D12. 타입 [리뷰 반영 — MEDIUM]
- 주식 수량=정수, 금액=원화 DECIMAL/정수. 크립토(Double 소수수량)와 다름 — `qty`/`executed_qty`=Long, `price`/notional=BigDecimal(or Long 원).

## (정정) MEDIUM: `Exchange.kt:3` 엔 `KIS`만 존재, `STOCK`은 `AssetType.STOCK`(별도 enum). 잔재 표현 정정.

## D16~D22 — 2c 자율엔진/2b SPA 설계 (design 워크플로 + 적대적 비판 반영, 2026-06-14)
- **D16 구조**: 크립토 engine/* 상태기계·매도우선순위 재사용하되 "pending 진실원천 = WAL 행"(메모리 아님). 신규 `kis/{marketdata,engine}` 패키지. 전략 7종 무변경(shouldBuyNormalized/Sell on NormalizedCandle). 시세는 `MarketDataStore`(key `KIS:005930:1d`) 재사용. **일봉 only MVP**(분봉은 KIS 당일·30건/호출 한계로 후속).
- **D17 KIS 캔들 스펙**: 일/주/월 `inquire-daily-itemchartprice`(tr_id FHKST03010100, FID_PERIOD_DIV_CODE D/W/M, output2 OHLCV), 분봉 FHKST03010200(당일한정). 휴장 `chk-holiday`(CTCA0903R, opnd_yn). 정규장 09:00–15:30 KST 하드코딩 게이트. 시세는 실전도메인 권장(모의 가능여부 스모크 미확인).
- **D18 [C1 fix]** dry-run 에서 DRY_RUN=terminal→활성슬롯 미점유→무한 INSERT 위험. → 엔진/`StockPositionManager.submitBuy` 가 매수 제출 성공 시 **메모리 `StockPosition.boughtToday=true`(+dry-run 은 position=true 시뮬) 즉시 마킹**해 재진입 차단.
- **D19 [C2 fix]** WAL 활성 불변식에 **`side` 추가**(V16: `uq_stock_order_intent_active` 를 (user,exchange,account,symbol,side) 로 재정의) → 미체결 매수가 손절매도 차단 안 함. `findActiveByKey` 에 side 인자. **MVP 매수=시장가**(미체결창 최소화).
- **D20 [C3 fix]** reconcile UNKNOWN 매칭에서 **이미 다른 WAL 행에 링크된 ODNO 는 candidates 에서 제외**(자율 반복매매 동일수량 오매칭/종목잠금 방지). ODNO-우선 유지.
- **D21 [M1/M2/M3 fix]** 부팅순서: reconciler `running` AtomicBoolean→**Mutex** + `reconcileNow()`(완료 await), 매니저가 엔진기동 전 호출(M1). 매도수량=**`ord_psbl_qty`**(KisHolding.orderableQty, 0이면 보류 — locked 미러)(M2). 매수금액=**min(예수금, D+2정산)×보수버퍼**(미수방지)(M3).
- **D22 [M5/M6 fix]** `bot_state` 에 `exchange` 컬럼(V16) + Upbit `UserTradingManager` 호출부 전수(`findByUserId`×2, `findByRunningTrue`×1)를 `…AndExchange("UPBIT")` 로 좁힘. A묶음(KisClient 캔들 + DTO: KisCandle/KisCandlePeriod, KisHolding.orderableQty) 계약을 **병렬 구현 전 메인이 선확정**.
- **구현 분담**: 안전핵심(메인 직접) = A(client/DTO 캔들·헬퍼)+V16+StockOrderService side가드+Reconciler mutex/odno제외+BotState/Upbit+KisMarketCalendar+StockPositionManager+KisStockTradingEngine+StockUserTradingManager. 워크플로 병렬 = KisMarketDataService·StockCandleAdapter·properties·StockBotController·SPA·각 테스트.
- **code-reviewer 위임 예정(비판 지적)**: tick(호가단위) 보정, 상하한가/거래정지/VI 주문거부 분류, 매수 시장가 슬리피지 버퍼 충분성.

## D23. [2026-07-28] 브로커 재검토 — KIS 유지 확정 + 데이터 계층 분리
동결 해제에 앞서 토스증권 Open API 를 후보로 재조사(#59). Claude 1차 → Codex 교차검증(사실오류 8건) → 스펙 원본 재검증.
- **KIS 유지 확정.** `stock-quant-strategy` Phase 0 요구 7건 중 KIS 우위 ④업종지수·⑥모의투자·⑦종목마스터(`.mst`: 시총·상장주수·**업종 대/중/소분류**·KOSPI200섹터) + 약우위 ②수정주가(`ksdinfo_*` 코퍼레이트 액션), 동률 ①⑤, 둘 다 탈락 ③생존편향. **토스가 앞서는 항목 없음.**
- **토스 탈락 사유(주문 실행은 토스가 우수함에도)**: `clientOrderId` 멱등키가 **10분 TTL** 이라 "장 마감 후 신호 → 익일 개장 집행" 모델에서 WAL/reconcile 을 대체 못 함(스펙 명시). 모의투자 부재로 `stock-quant-strategy` Phase 3(모의 4주 운영) 자체가 불가 → "백테스트 입증 전 live 금지" 철칙과 충돌. 그 외 예약주문·신용주문·시간외·WebSocket·국내 금액주문 부재.
- **채택한 방향 전환**: 증권사 API 하나로 Phase 0 데이터 요구를 다 풀려는 전제가 잘못됐다 → **KIS 는 주문·잔고·체결 전용, 연구 데이터는 KRX 원천 기반 독립 point-in-time 저장소**(매일 append-only 적재)로 분리. 생존편향(③)은 어느 브로커 API 로도 못 풀고 이 방식으로만 해결된다.
- ⚠️ 이 결정은 `stock-quant-strategy` plan `# Decisions` 의 **"데이터 소스는 KIS API 한정"** 과 충돌 → 그 plan 재개 시 완화 필요(KRX 공공데이터는 무료라 "유료 외부 데이터 없음" 원칙엔 위배 아님).
- 확정 사실(부수 수확): 2026년 상장주식 매도세 **KOSPI 0.05%+농특세 0.15% / KOSDAQ 0.20% = 총 0.20%** — `stock-quant-strategy` 미확정 ⑤ 해소(⚠️Codex 제공, 법령 원문 미검증). KIS 일봉 **1회 최대 100건** + 일/주/월/년봉 + 시작~종료일 직접 지정.

## D24. [2026-07-28] 실거래 블로커 4건 해소 설계 (사용자 결정 반영)

> **[2026-07-28 plan-review(codex) 반영 — 범위 축소]** 아래 설계는 codex 리뷰(Critical 6/Major 3)와 코드 검증을 거쳐 **"안전한 것부터"** 로 재조정됐다(사용자 결정). C-C 의 durable 현금예약과 M-E 전체는 `# Deferred` 로 분리한다. 처분 근거는 `# Review Disposition` 참조.

- **C-C(축소) = 종목별 매수가능수량 상한**. 원안(패스 잔고 스냅샷 차감)의 전제가 틀렸다 — `sizeFromBalance` 가 쓰는 `getBalance()` 의 예수금·D+2 는 **주문가능금액이 아니다**. KIS 는 미수 없는 매수가능수량을 `inquire-psbl-order`(tr_id `TTTC8908R`, `nrcvb_buy_qty`)로 제공하며 종목 증거금률이 반영된다(plan D14 에 이미 적혀 있었으나 미구현 — `KisClient` grep 0건). → **`getBuyableQty(symbol, price)` 를 신규 구현해 매수 수량의 최종 상한으로 삼는다.** 조회 실패·0·업무오류는 **fail-closed**(매수 skip).
  - 패스 내 로컬 차감은 넣지 않는다 — 수동 주문(`KisTradeController`)·재시작·다중 사용자가 같은 현금을 공유하므로 엔진 지역 변수로는 계좌 단위 불변식이 성립하지 않는다(codex C2). **미수의 최종 방어선은 브로커의 `nrcvb_buy_qty`** 이고, 이것만으로 "예수금 초과 주문 접수"는 막힌다. 완전한 계좌 단위 예약은 `# Deferred`.
  - 부수 수정: `submitBuy` 가 `DRY_RUN` 이 아닌 **모든** 반환을 접수로 간주해 `FAILED`(브로커 명시 거부)에도 `boughtToday=true` 가 된다(codex C3) → terminal 실패 상태는 `boughtToday` 를 세우지 않는다.
- **M-B 수동주문 시장시간 게이트**: 엔진은 `marketCalendar.isTradingNow()` 로 막지만 `KisTradeController.placeOrder` 에는 게이트가 없다 → 동일 캘린더를 주입해 **장외 주문은 거부**. ⚠️ 현 캘린더는 평일 09:00~15:30 하드코딩이라 **공휴일·임시휴장을 모른다**(codex M1) — 게이트는 넣되 이 한계를 코드 주석·Report 에 명시하고, `chk-holiday`(CTCA0903R) 연동은 `# Deferred`. (예약주문 `order_resv` 도 범위 밖.)
- **M-D REST 폴백 캐시·backoff = 엔진 로컬 캐시**(원안의 store 위임 철회). store 에 엔진이 쓰면 `@Scheduled` 폴러와 **writer 가 둘**이 되는데, `addCandle` 의 `put + size + trim` 은 비원자적이고 store 주석 자체가 단일 writer 를 전제한다(codex M2). → 엔진 내부에 per-symbol TTL 캐시를 두고 **store 에는 쓰지 않는다**(단일 writer 유지). 연속 실패 심볼은 지수 backoff.
  - `MarketDataStore.getLatestTicker` 는 stale 판정이 없어 폴링이 죽으면 낡은 가격으로 매매한다. 단 **store 의 공개 계약을 바꾸면 기존 소비자(SSE·API)까지 영향**받으므로(codex M3), TTL 판정은 store 가 아니라 **엔진이 `ticker.timestamp` 를 보고** 수행한다.
- **[Critical] 수동주문 `qty` overflow 로 상한 우회**(codex C6): `StockOrderService.validate` 의 `val notional = cmd.qty * buffered` 는 Long×Long 이라 오버플로 시 음수가 되어 `notional > cap` 검사를 통과한다. 검증은 `qty <= 0` 뿐이라 상한이 없다. → `Math.multiplyExact`(또는 `BigInteger`)로 계산하고 오버플로는 검증 실패로 처리 + 수동 주문 수량 상한 추가.
- **[Explore 발견 · 기존 Critical] REST 폴백 캔들 정렬이 전략 규약과 반대**: `KisClientImpl.getDailyCandles` 는 `.sortedBy { it.date }` 로 **ascending** 반환하는데, 전략·`Indicators` 는 `candles[0]`=최신(**descending**)을 가정한다(`Indicators.kt:11`, `.take(period)`). `MarketDataStore.getCandles` 는 `descendingMap()` 이라 정상이므로 **store 미스 시에만** 지표가 뒤집혀 계산되는 간헐 오류다. M-E 의 "확정봉을 어느 끝에서 제외하나"가 이 순서에 달려 있어 **M-E 의 직접 전제** → 이번 범위에 포함(§3-4 "빌드/테스트를 깨는 직접 원인" 준용). M-D 설계(폴백분을 store 에 적재 후 재조회)를 택하면 정렬이 store 로 일원화돼 근본 해소된다.

# Acceptance

- [x] **C-C(축소)**: 매수 수량이 `inquire-psbl-order` 의 미수 없는 매수가능수량을 넘지 않는다. 검증 — `getBuyableQty` 가 10주를 반환할 때 잔고상 20주가 계산돼도 주문 qty=10. 조회 실패·0 이면 매수 skip(fail-closed).
- [x] **C-C 부수**: 브로커 명시 거부(`FAILED`)면 `boughtToday` 가 서지 않아 다음 패스에 재시도 가능. 검증 — `submitBuy` 가 FAILED 반환 시 `pos.boughtToday == false`.
- [x] **M-B**: 장외 시각에 `POST /api/kis/order` 가 주문을 접수하지 않는다. 검증 — `KisMarketCalendar` stub(isTradingNow=false)로 컨트롤러 테스트, WAL INSERT 없음.
- [x] **M-D**: 엔진 REST 폴백 결과가 TTL 내 재사용된다. 검증 — client mock 호출횟수로 2패스에 1회. store ticker 가 TTL 초과면 stale 로 보고 폴백한다. **store 에는 쓰지 않는다**(단일 writer 유지) — `store.addCandle`/`updateTicker` 가 엔진 경로에서 호출되지 않음을 mock 으로 확인.
- [x] **정렬 버그**: 폴백 경로가 전략에 넘기는 캔들이 descending(`candles[0]`=최신)이다. 검증 — 폴백 단위테스트에서 첫 원소가 가장 최근 봉.
- [x] **qty overflow**: 오버플로를 유발하는 qty 가 `maxOrderAmount` 검증을 통과하지 못한다. 검증 — `Long.MAX_VALUE` 근처 qty 로 `StockOrderValidationException`.
- [x] 전체 `./gradlew test` green — **552 tests / 0 failures / 4 skipped**(baseline 535 → D24 543 → fix loop 552) + 문서 동기화(README/PROJECT_ANALYSIS 의 KIS 동작 서술이 바뀌면 갱신)

# Deferred

범위 밖으로 분리한 항목. **2026-07-29 전건 GitHub 이슈로 등록 완료** — 상세는 각 이슈가 단일 소스이며 여기서는 중복 서술하지 않는다.

| 이슈 | 항목 | 심각도 | 실거래 선행? |
|---|---|---|---|
| #64 | 재시작 안전성 — `peakPrice`·`boughtToday`·`entryStrategy` 메모리 전용 | 중~높 | **예** |
| #67 | `reconcileNow()` 실패 은폐 — reconcile 없이 엔진 기동 | 중~높 | **예** |
| #65 | 일봉 whipsaw — 확정이력/현재세션 입력 계약(`DailySignalContext`) 분리 | 중 | 아니오 |
| #66 | 계좌 단위 durable 현금예약 | 중 | 아니오 |
| #69 | `NEEDS_REVIEW` 활성 슬롯 점유 — 수동 해소 API/runbook | 중 | 아니오 |
| #68 | `chk-holiday` 연동 — 공휴일·임시휴장 미인지 | 낮~중 | 아니오 |
| #70 | dry-run 예산 시뮬 — 백테–라이브 정합 | 낮~중 | 아니오 |

# Key Files

## 재사용/참조 (Upbit 패턴 템플릿)
- `bot/.../client/{UpbitClient,UpbitClientImpl}.kt` — KisClient/impl 구조 템플릿(suspend+WebClient+onStatus, placeOrder 비멱등 L68-107).
- `bot/.../client/UpbitAuthProvider.kt` — KisTokenProvider 가 대체(구조 다름, D2).
- `bot/.../engine/PositionManager.kt` L82-156 / `domain/TradingState.kt` L19 — 메모리 전용 pending 한계 근거(WAL 로 일반화).
- `bot/.../engine/TradeExecutionService.kt` L189-201 — R2DBC `TransactionalOperator` suspend 패턴 = **D4 트랜잭션 경계 위험의 직접 근거**(이대로 묶으면 안 됨).
- `bot/.../engine/UserTradingManager.kt` L43-67 — 멀티유저 Mutex + `@PostConstruct scope.launch`(부팅순서 선례, D10).
- `bot/.../security/{UserSecretsService,SecretKeyMaterialProvider}.kt` — KIS 키 암호화(users.upbit_* 컬럼만 사용 중).
- `bot/.../persistence/entity/{BotConfigEntity,TradeExecutionEntity,PositionEntity,UserEntity}.kt` — tradeMode=SWING/SCALP, exchange_order_id 기존 컬럼, @Version 미사용.
- `db/migration/V11,V12,V13` — positions/trade_executions/bot_configs unique, user_exchange_keys, trade_mode=SWING. 최신 V13 → 다음 V14.
- `bot/src/main/resources/application.yml` — config 매핑(upbit/trading/app 블록).

## 신규 (Phase 1)
- `bot/.../kis/client/{KisClient,KisClientImpl,KisTokenProvider}.kt`
- `bot/.../kis/domain/{KisOrderRequest,KisOrder,KisBalance,...}.kt`
- `bot/.../kis/config/KisProperties.kt`
- `bot/.../kis/order/{StockOrderService,StockOrderReconciler}.kt`
- `bot/.../persistence/entity/StockOrderIntentEntity.kt` + repository
- `db/migration/V15__create_stock_order_intent.sql`, `V16__add_kis_keys.sql`(D11), `V17__bot_state_exchange_and_wal_side.sql` — **2026-07-28 rebase 시 V14~V16 → V15~V17 renumber**(main 이 V14 를 `trading_states` 로 선점)
- 테스트: `bot/src/test/.../kis/` (token 캐싱, client 매핑, WAL 상태전이/reconcile 실패모드, 체결 idempotency)

# Review Disposition

| # | 심각도 | 항목 | 처리 |
|---|---|---|---|
| 1 | CRITICAL | WAL 단일 트랜잭션 → 롤백 시 소멸 | **fix** D4 (tx 3분리 + 조건부 UPDATE) |
| 2 | CRITICAL | trade_mode 는 SWING/SCALP, dry-run 아님 | **fix** D5 폐기·신규 플래그 |
| 3 | CRITICAL | 불변식 앱Mutex 불충분 | **fix** D4 (DB partial unique) |
| 4 | HIGH | SUBMITTING stale 복구 없음 | **fix** D7 |
| 5 | HIGH | 0건=FAILED 오판→미인지 보유 | **fix** D7 (NEEDS_REVIEW) |
| 6 | HIGH | UNKNOWN 오매칭 | **fix** D8 (매칭키 보강, 0/2+→REVIEW) |
| 7 | HIGH | 체결→positions/trade_executions idempotency 없음 | **fix** D9 |
| 8 | HIGH | cancel 상태기계 없음 | **fix** D6 (취소 WAL Phase2 분리) |
| 9 | MEDIUM | STOCK enum 부정확 | **fix** 정정 |
| 10 | MEDIUM | user_exchange_keys 중복 | **defer→1a** D11 |
| 11 | MEDIUM | 계좌식별자 컬럼 누락 | **fix** D4 스키마 (1a 스펙 확정) |
| 12 | MEDIUM | qty/price 타입 | **fix** D12 |
| 13 | LOW | migration 분리 | **fix** V14/V15 |

## code-review (구현 후 — Claude; codex 환경오류 미산출)
| # | 심각도 | 항목 | 처리 |
|---|---|---|---|
| C1 | CRITICAL | inquireDailyConclusions 연속조회 미처리 → 거짓 NEEDS_REVIEW | **fix** 페이징 루프(tr_cont/CTX_AREA, MAX 20p) |
| M1 | MAJOR | 시장가 notional 가드 0가격 우회 | **fix** price>0 검증 + 시장가 1.1 버퍼 |
| M4 | MAJOR | getCurrentPrice silent 0 반환 | **fix** rt_cd/price 검증 후 예외 |
| M2 | MAJOR | positions 반영 누락(D9 약속) | **scope→Phase2** (D6/D9 정정, 사유 명시) |
| M3 | MAJOR | 4xx 일괄 ambiguous(UNKNOWN) | **wontfix(의도)** 안전쪽(접수 가능성) — FAILED 오판 방지 우선. reconcile 이 grace 후 NEEDS_REVIEW |
| M5 | MAJOR | tx 원자성/partial-index 통합테스트 부재 | **defer→Phase2** Testcontainers 필요(현 harness 단위전용) + 수동 Postgres 스모크 |
| M-a | MINOR | 미사용 reconcileIntervalMs | **fix** 제거 |
| M-b | MINOR | invalidate 미배선 | **defer→Phase2** (키 등록 UI 시) |
| M-c | MINOR | NEEDS_REVIEW 활성슬롯 잠금 | **defer→Phase2** 수동해소 API/runbook |
| M-f | MINOR | 토큰 backoff 미구현 | **defer→Phase2** 현재 예외전파+24h 캐싱으로 완화 |

## 2026-07-28 codex plan-review (D24 범위) — Critical 6 / Major 3

| # | 심각도 | 지적 | 처분 |
|---|---|---|---|
| C1 | CRITICAL | C-C 의 현금 원천이 주문가능금액이 아님(예수금≠증거금률 반영) | **fix** `getBuyableQty`(inquire-psbl-order) 신규 + 최종 상한 + fail-closed |
| C2 | CRITICAL | 패스 내 로컬 차감은 계좌 단위 불변식이 아님(수동주문·재시작·다중사용자) | **defer** → `# Deferred` durable 현금예약. 브로커 `nrcvb_buy_qty` 를 1차 방어선으로 채택 |
| C3 | CRITICAL | 주문 상태별 차감 규칙 부재 — FAILED 인데 `boughtToday=true` | **fix** `MISSED_BUY_STATUSES`(FAILED/REJECTED)는 진입 슬롯 미소모 |
| C4 | CRITICAL | M-E 확정봉 일괄 필터가 전략 정의를 바꿈(VolatilityBreakout·CombinedStrategy·MeanReversion) | **defer** → `# Deferred`. 코드 실측으로 확인(`Indicators.calculateTargetPrice` 의 today/yesterday) — 원안 철회 |
| C5 | CRITICAL | 재시작 안전성이 이미 깨져 있음(peakPrice·boughtToday·entryStrategy 메모리 전용, `reconcileNow` 실패 은폐) | **defer** → `# Deferred` 2건. 이번 범위(블로커 4건) 밖 |
| C6 | CRITICAL | 수동주문 `qty` 오버플로로 `maxOrderAmount` 우회 | **fix** `Math.multiplyExact` |
| M1 | MAJOR | 캘린더가 공휴일·임시휴장을 모름 → 게이트 착시 | **부분 fix** 게이트는 넣되 한계를 코드 주석·Report 에 명시, `chk-holiday` 연동은 defer |
| M2 | MAJOR | store 다중 writer race(`addCandle` put+trim 비원자) | **fix(설계 변경)** 원안(store 위임) 철회 → 엔진 로컬 캐시로 단일 writer 유지 |
| M3 | MAJOR | `getLatestTicker` 에 TTL 을 넣으면 store 계약·기존 소비자 변경 | **fix(설계 변경)** TTL 판정을 store 가 아니라 엔진에서 수행 |

## 2026-07-29 codex code-review (D24 구현 diff) — Critical 1 / Major 5 / Minor 3

| # | 심각도 | 지적 | 처분 |
|---|---|---|---|
| C1 | CRITICAL | 수동 매수(REST)가 `getBuyableQty` 상한을 우회 — 상한을 엔진 sizing 에만 넣었다 | **fix** 공용 경계 `StockOrderService.validate` 로 이동(자동·수동 공통). 엔진 sizing 은 수량 결정용으로 유지 |
| M1 | MAJOR | price/candle 이 backoff 상태를 공유 — 캔들 실패가 가격 조회를 막고 가격 성공이 캔들 실패를 리셋 | **fix** `FallbackCache` 인스턴스를 종류별로 분리 |
| M2 | MAJOR | `REJECTED` 미소모 분기가 도달 불가(생성하는 코드 없음) | **fix** dead 분기 제거, `FAILED` 만 처리 |
| M3 | MAJOR | `FAILED` 를 즉시 재시도 가능하게 만들어 cooldown 없이 tick 마다 재전송 — **직전 커밋이 만든 회귀** | **fix** `StockPosition.recordBuyRejection()` 지수 backoff(1분~30분) |
| M4 | MAJOR | 시장시간 게이트가 컨트롤러에만 있어 송신 시점엔 미보장(15:29:59 통과 → 15:30 송신) | **fix** `StockOrderService.validate` 에서 송신 직전 재검증 |
| M5 | MAJOR | 신규 캐시·TTL·backoff 에 테스트가 전혀 없음 — **Acceptance 를 잘못 체크했다** | **fix** private 이라 테스트 불가였던 것이 원인 → `FallbackCache` 로 추출(시간원 주입) + 테스트 5개 |
| m1 | MINOR | 장외 게이트가 DRY_RUN 까지 차단해 시뮬레이션 계약 파손 | **fix** `liveEnabled` 일 때만 게이트 |
| m2 | MINOR | 광범위 `catch (Exception)` 이 `CancellationException` 까지 삼킴 | **fix** 먼저 re-throw |
| m3 | MINOR | wall clock TTL 은 시계 역행 시 만료값이 fresh 로 보임 | **fix** 로컬 TTL 은 `nanoTime`, 외부 timestamp 는 `age in 0 until ttl` |

# Blockers

- ~~**동결**(2026-07-28, #49 큐 3번)~~ — **해소** (2026-07-28 사용자 지시로 재개, D23 에서 KIS 유지 확정).
- ~~**머지 선행: rebase onto origin/main**~~ — **해소**(2026-07-28). behind 17 → 0. V14~V16 → V15~V17 renumber 완료, 충돌 3건(`PROJECT_ANALYSIS.md`·`README.md`·`UserTradingManager.kt`) 해소, **535 테스트 0 실패**.
- (Phase1 없음 — 머지 가능) 아래는 **실거래 활성화(KIS_LIVE_ENABLED=true) 전 필수 선행**:
  - **실계정 스모크**: KIS inquiry/balance 필수 query 파라미터 집합·tr_cont 연속조회 값·ODNO/org_no 자릿수·토큰 재발급/ rate limit 실값(코드/테스트로 검증 불가 — 실계정 필요).
  - **통합 테스트(M5)**: WAL tx 원자성 + partial unique index 동시성은 단위테스트(mockk passthrough)로 미검증 — Testcontainers-Postgres 또는 수동 Postgres 검증 필요.
  - ~~**자율엔진 code-review 잔여(2c)**~~ — **2026-07-28 해소**(D24): C-C 는 `getBuyableQty` 상한+fail-closed 로, M-B 는 컨트롤러 게이트로, M-D 는 엔진 로컬 TTL 캐시+backoff 로 처리. 함께 발견된 정렬 버그·`notional` 오버플로도 수정. **M-E 만 `# Deferred`** — 오늘 봉 일괄 제거는 VolatilityBreakout·CombinedStrategy·MeanReversion 의 정의를 바꾸므로 입력 계약 분리가 선행돼야 한다.
  - ⚠️ 남은 실거래 선행조건 4건: **실계정 스모크**·**통합 테스트(M5)**(위 2개) + **#64 재시작 안전성**·**#67 reconcileNow 실패 은폐**. D24 는 "블로커 4건"을 닫았을 뿐 `KIS_LIVE_ENABLED=true` 를 승인하지 않는다.
