---
title: external-regime-gate — 김치프리미엄·펀딩·공포탐욕·ETH/BTC·테이커 흐름을 combined 진입 레짐 게이트로 얹어 5분봉 사전고정 판정
status: done
started: 2026-09-10
updated: 2026-09-10
---

# Goal

거래 대상 코인의 Upbit OHLCV 에서 파생되지 않은 **새 정보 부류** 다섯(김치프리미엄, Binance BTC 펀딩레이트, 공포·탐욕 지수,
ETH/BTC 비율 추세, Binance BTC 테이커 매수 비율)이 현행 `combined` 의 진입을
걸러 거래당 손익을 올리는지, 선행 판정과 같은 계기(`LiveSemanticsArm` 5분봉·10창·paired maxT)로 판정한다.
결과가 "통과 0" 이면 현행 유지이고 그것도 답이다.

# Intent

- **Problem**: [[parameter-search-2026-09]] 가 exit 51,480 좌표·신규 가격지표 10종 39,600 좌표·null 대조군으로 "OHLCV 파생 신호" 부류를
  닫았다. 아직 시험된 적 없는 정보는 Upbit 밖에 있다 — 한국 고유의 김치프리미엄, 해외 파생시장의 펀딩레이트. 사용자 요청
  2026-09-10: "코인 시장을 예측할 지표를 찾아 퀀트를 다시 수행할 수 없을지".
- **Constraints**(사용자 확인 ✅ / 추론 ⚠️):
  - ✅ 실패한 방식(combined 파라미터·가격지표 재탐색)을 반복하지 않는다.
  - ⚠️ 라이브 파라미터·라이브 코드 경로는 바꾸지 않는다(선행 plan 관례).
  - ⚠️ 판정 계기는 5분봉이 주다 — 240분봉 우위는 계기 편향으로 실측됐다([[exit-resolution-ladder-2026-09]]).
  - ⚠️ 외부 데이터는 무인증 공개 API 만(Binance `fundingRate`·`klines`, Frankfurter, alternative.me F&G) — 2026-09-10 이 머신에서 2019~2020 값 실측 확인.
  - ✅ 사용자 2026-09-10: 김프·펀딩 둘 다 유지하고 신호를 더 넣는다("다 고려해"). BTC 도미넌스·스테이블 유입은 원했으나 무료 이력이 없어 대리 신호로 대체(Decisions 5).
- **Out of scope**: 미결제약정(Binance 이력 30일 제한) · BTC 도미넌스(CoinGecko `global/market_cap_chart` PRO 전용) · 스테이블 유입(CoinGecko 공개 API 이력 365일 제한, 온체인은 유료) · 여러 마켓 상대강도(공유잔고 엔진 #180 선행 필요) · 5분봉 장중 전략 · LLM 판단([[lesson-llm-alpha-verification]]) · 라이브 반영(후보가 나와도 승격은 별도 승인).
- **Open questions**: 없음 — 아래 `# Acceptance` 가 사용자 승인 대상이다.

# Progress

- 2026-09-10 조사: 외부 데이터 가용성(researcher)·백테스트 구조(Explore)·API 실측 7건(Binance funding/klines/ETHBTC, Frankfurter, F&G ✅ · CoinGecko 도미넌스·USDT 이력 ❌). worktree `external-regime-gate` 생성(base `origin/main@bd91317`). plan 초안 → 사용자 요청으로 신호 5종·13셀로 확장 → 사용자 승인(13셀).
- 2026-09-10 구현: `scripts/collect_external_series.py`(+단위테스트 8건 통과) → fixture 5종 2,536행(2019-10-01~2026-09-09; Frankfurter 가 Python 기본 UA 를 403 으로 막아 UA 지정) → `ExternalSeries`·`ExternalRegime`·`DateGatedStrategy`·`ExternalRegimeGateTest` → `compileTestKotlin` 통과(JDK 는 `jbr-21.0.9` 지정 필요 — 기본 JDK 25 로는 Gradle 이 안 뜬다). 6(d)·6(f) 문구를 마켓 합집합·(market,day) 기준으로 정확히 고침(결과 보기 전).
- 2026-09-10 smoke(`GATE_UNITS=240`): 배관 단정 8a(ALL_PASS = 기준)·8b(결측 0)·8d(위상 이동 불변) 통과, 240분 기준 거래 1,058 = 선행 사다리. ⚠️ 선행 관례대로 **240분봉 결과는 커밋 전에 관측됐다**(13셀 전부 격차 음수, 통과 0) — blind 인 것은 15·5분봉뿐이다. 마켓 이름 합집합은 35 라 6(d) 는 27/35 이상을 뜻한다.
- **아래 `# Acceptance` 는 이 커밋 시점에 고정된다.**
- 2026-09-10 본 실행(`ca3242e` 뒤, 3분 42초): 후보 0·null 무효(N0 21). wiki `query/external-signal-gate-2026-09` + index + log. 결과는 `# Acceptance` 아래 인용 블록.

# Next

없음 — 판정 종료. 재시도는 5분봉 기준선 재판정(#189·#190) 뒤 새 사전고정으로.

## (완료 전 마지막 단계였던 것)
1. `RUN_EXTERNAL_GATE=true` 15·5분봉 본 실행(사전고정 커밋 뒤) → 결과를 `# Acceptance` 아래 인용 블록으로 → wiki `query/external-signal-gate-2026-09.md` + index → 검증 3종.

# Decisions

## 1) 신호는 BTC 기준 시장 전체 스칼라 다섯, 값은 "어제" 것만

- `KIMP_D` = Upbit `KRW-BTC` 일봉(D−1, KST 09:00 마감) 종가 ÷ (Binance `BTCUSDT` 일봉(UTC D−1) 종가 × USD/KRW(D−1, Frankfurter/ECB, 주말·휴일은 직전 영업일 이월)) − 1, 단위 %.
- `FUND_D` = Binance `BTCUSDT` 무기한 펀딩레이트 중 `fundingTime` 이 UTC D−1 에 속하는 3건(00:00·08:00·16:00) 평균, 단위 %. UTC D 00:00 정산분은 경계와 같은 시각이라 **제외**(look-ahead 브래킷을 안전한 쪽으로).
- `FNG_D` = alternative.me Fear & Greed 지수, 발표일 UTC D−1 값(0~100).
- `ETHBTC_D` = Binance `ETHBTC` 일봉(UTC D−1) 종가. 셀은 종가 vs 그 이전 20일 SMA.
- `TAKER_D` = Binance `BTCUSDT` 현물 일봉(UTC D−1)의 테이커 매수 기준통화량 ÷ 총 기준통화량(klines 필드 9 ÷ 5).
- 다섯 값 모두 거래일 D 의 09:00 KST 경계 이전에 확정된 정보만 쓴다. 조인 키는 `Candle.candleDateTimeKst[0..9]`(= UTC 날짜, `LiveSemanticsArm` 과 동일).
- 알트 마켓에도 BTC 신호를 그대로 적용한다(시장 전체 레짐 가설). 마켓별 김프는 Binance 상장일·유동성이 제각각이라 fixture 결측이 커진다 — 기각.

## 2) 게이트는 엔진이 아니라 전략 데코레이터

`TradingStrategy.shouldBuy(candles, price, config)` 에 외부 컨텍스트 자리가 없으므로 `combined` 를 감싸 `name` 을 위임하고
`shouldBuy` 에 날짜 게이트를 AND 로 얹는 리서치 전용 클래스를 `bot/src/test/` 에 둔다. 엔진·`BacktestConfig`·`SweepPoint`·`StrategyConfig` 는 무수정.
기각: `BacktestConfig` 람다 노브(data class 동등성이 그리드 키라 오염) · `SweepPoint` 축 추가(plateau 이웃 정의가 바뀐다).

## 3) family 는 13셀로 고정, 양방향을 다 넣는다

방향을 미리 고르면 결과를 본 뒤 "반대였다"로 갈아탈 유혹이 생긴다. 신호마다 낮음/높음을 대칭으로 넣고 결합 1셀을 더해 13셀.
선행 사다리(7셀)보다 커서 maxT 임계 q 가 오른다 — 검정력 손실은 감수한다(사용자가 폭을 택했다). 판정표에 7셀(김프·펀딩만) 기준 q 도 병기하되 판정은 13셀 q 로만 한다.

## 4) null 대조군은 위상 이동

진입 무작위화(`RandomEntryStrategy`)는 게이트 검정의 동형 대조군이 아니다. 같은 13셀을 **날짜를 s×17일 순환 이동한 시계열**에
적용한다(s = 1..20, 상태 없는 순수 함수라 셀마다 대조군이 흔들리지 않는다). 20 seed 로는 분위수 추정이 안 되므로(선행 교훈)
분위수가 아니라 **통과 건수 상한**으로 쓴다.

## 5) 기각한 대안

여러 마켓 상대강도(#180 공유잔고 엔진 선행) · 미결제약정(이력 30일) · 유료 김프 API(산출 로직 검증 불가, 직접 계산이 재현 가능) ·
Upbit 파생 데이터(존재하지 않음, 국내 파생 금지) · BTC 도미넌스(CoinGecko PRO 전용 — 실측 error 10005) → ETH/BTC 비율로 대리 ·
스테이블 유입(CoinGecko 공개 이력 365일 — 실측 error 10012, 온체인 유료) → Binance 테이커 매수 비율로 대리(같은 "돈이 들어오나" 질문의 무료 근사).

# Acceptance

**아래 1~12 전부가 15·5분봉 결과를 보기 전에 커밋하는 사전고정이다(240분봉은 smoke 로 먼저 관측 — Progress). 실행 후 문구를 고치지 않는다. 배관 단정(8)이 실패하면 배관을 고쳐
같은 규칙으로 재실행하며 규칙·임계·family 를 조정하지 않는다.**

1. **계기·기준**: `LiveSemanticsArm`, 봉 단위 5(주 판정)·15(수렴 대조). 기준 = `currentLivePoint()`(`combined`, TP 5 / SL 5 / 트레일 1.5 / arm 0 / k 0.5 / h 1), `Arm.PRIMARY` 만(게이트는 진입만 바꾸므로 청산 브래킷 불필요).
   창 = `EXPANSION_2020_2023 + TIME_INDEPENDENT` 10창, 선행과 동일 로스터·frame 1,500일.
2. **family = 13셀**(이후 변경 금지). 롤링 통계는 그 날 이전 90일(경계 미포함), SMA20 은 이전 20일.
   - `KIMP_LOW`: `KIMP_D ≤ median90(KIMP)` · `KIMP_HIGH`: `>`
   - `KIMP_RISING`: `KIMP_D > KIMP_{D−5}`
   - `FUND_NEG`: `FUND_D ≤ 0`
   - `FUND_LOW`: `FUND_D ≤ median90(FUND)` · `FUND_HIGH`: `>`
   - `FNG_FEAR`: `FNG_D ≤ 30` · `FNG_GREED`: `FNG_D ≥ 70`
   - `ETHBTC_UP`: `ETHBTC_D > SMA20(ETHBTC)` · `ETHBTC_DOWN`: `≤`
   - `TAKER_HIGH`: `TAKER_D > median90(TAKER)` · `TAKER_LOW`: `≤`
   - `COMBO`: `KIMP_LOW ∧ FUND_LOW`
   셀은 조건이 참인 날만 `combined` 의 진입을 허용한다. 청산 규칙은 기준과 동일.
3. **기여** = 진입일 단위(그날 진입한 셀 거래 손익 합 − 기준 거래 손익 합), 단위 %p. 격차/기준거래 = Σ기여 ÷ 그 해상도의 기준 거래수. 선행 사다리 정의 그대로.
4. **판정 통계량**: `PairedMaxTBootstrap` — 창별 이동블록 5일, B=20,000, seed 고정, draw 는 셀·rung 공통, 단일단계 maxT FWER 5%, family = 13(rung 별).
5. **null 게이트**: 5분봉에서 s=1..20 위상 이동 시계열 × 13셀 = 260 (seed, cell) 에 같은 maxT 를 적용해 통과 건수 `N0` 를 센다. `N0 > 13`(5%) 이면 계기 무효 → 후보 0 으로 종결(재설계는 별도 plan).
6. **후보 조건**(전부 충족): (a) 5분봉 maxT 통과 (b) 15분봉 같은 부호이고 `g5 ≥ 0.8·g15` (c) 격차/기준거래 ≥ 0.10%p (d) 10창 로스터 이름별로 pooled 기여(셀 − 기준)를 합쳐 양수인 이름이 전체 이름의 6/8 이상(창마다 로스터가 달라 이름 합집합이 8 을 넘는다) (e) 노출 정규화 `Σpnl/Σ보유일` 부호 일치 (f) 차단된 진입 비율 = 차단 (market,day) ÷ (차단 + 허용 (market,day)) 이 10%~70%(같은 날 여러 봉에서 막혀도 하루로 센다) (g) 5 의 null 게이트 유효.
   **통과 0 이면 현행 유지. 사후 재슬라이스(창·마켓·기간 부분집합, 임계 변경, 셀 추가) 금지.**
7. **fixture**: `bot/src/test/resources/backtest/external/{kimp_btc,funding_btcusdt,fng,ethbtc,taker_btcusdt}.json`(날짜 오름차순, 키 `date`·`value`, 2019-10-01 부터 — 롤링 90일 워밍업), `scripts/collect_external_series.py` 가 산출 규칙의 단일 소스(단위테스트 포함), README 에 출처·수집일·look-ahead 규약(Decisions 1) 명시.
8. **배관 단정**(실패 = 배관 수정 후 재실행): (a) 게이트가 항상 참인 셀(`ALL_PASS`)의 거래 목록이 기준과 정확히 일치 (b) 10창 모든 거래일에 다섯 시계열 값 존재(이월 ≤ 3일, 초과면 실패) (c) 셀별 차단 진입 비율을 보고 (d) 위상 이동 시계열이 원본과 날짜 집합 동일·값 다중집합 동일 (e) 선행 리포트(`RUN_EXIT_LADDER` 5분봉 기준 거래수 1,767)와 기준 셀 거래수 일치.
9. **보고**(`bot/build/reports/external-regime-gate.md`): 셀별 240/15/5분 표(격차·격차/기준거래·95% 하한·한계 p·maxT 통과·차단율·마켓별 부호), null 게이트 `N0`, 후보 판정표 (a)~(g).
10. **보고 형식**은 실행 전에 고정하고 실행 후 열을 더하지 않는다.
11. `./gradlew build` 통과, `deploy/` diff 0, 라이브 코드(`bot/src/main`·`common/src/main`) diff 0.
12. wiki `query/external-signal-gate-2026-09.md` + `wiki/index.md` 등재, 검증 3종 통과. 결과는 인용 블록으로 이 섹션 아래에 덧붙이고 원문은 안 고친다.

> 실행 결과(2026-09-10, 사전고정 커밋 `ca3242e`, 문구는 위 그대로): 1·2·3·4 ✅ 계기·family·기여·통계량 그대로 실행(3분 42초). 5 **null N0 = 21 > 13 → 계기 무효**.
> 6 5분봉 maxT 통과 1셀(`FNG_FEAR` +0.164/거래, T 2.80)이나 (d) 26/35 < 27·(f) 차단율 82%·(g) null 무효로 후보 아님 — **후보 0, 현행 유지**. 나머지 12셀 통과 0(전부 양수, 최대 COMBO/FUND_NEG +0.104).
> 7 fixture 5종 2,536행 ✅. 8 (a) 세 rung ALL_PASS = 기준 ✅ (b) 결측 0 ✅ (c) 차단율 표 ✅ (d) 위상 이동 불변 ✅ (e) 5분 기준 1,767 ✅. 9·10 리포트 형식 고정 ✅.
> 11·12 는 아래 Progress 의 검증 줄. 결론 = "외부 정보 5부류 발견 없음. 5분봉 기준선이 음수인 한 진입을 줄이는 모든 규칙이 대조군과 구분되지 않는다 — #189·#190 이 선행".

# Key Files

- `bot/src/test/kotlin/com/trading/bot/engine/ExitResolutionLadderTest.kt` — 하네스 골격(복제 원본)
- `bot/src/test/kotlin/com/trading/bot/engine/LiveSemanticsArm.kt` — 계기(무수정)
- `bot/src/test/kotlin/com/trading/bot/engine/PairedMaxTBootstrap.kt` — 판정 통계량(무수정)
- `bot/src/test/kotlin/com/trading/bot/engine/IntradayCache.kt` — 5·15분봉 캐시(`BACKTEST_CACHE_DIR=~/.cache/coin-trading-bot/backtest-cache`)
- `common/src/main/kotlin/com/trading/common/strategy/CombinedStrategy.kt` — 기준 전략(무수정)
- (신규) `scripts/collect_external_series.py`, `bot/src/test/resources/backtest/external/`, `bot/src/test/kotlin/com/trading/bot/engine/{DateGatedStrategy,ExternalSeries,ExternalRegimeGateTest}.kt`

# Blockers

없음.
