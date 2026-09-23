---
title: 외부 레짐 게이트 — 김프·펀딩·공포탐욕·ETH/BTC·테이커 13셀은 5분봉에서 전부 양수지만 위상 이동 대조군도 같이 통과한다(N0 20 > 13) → 정보가 아니라 "덜 거래" 효과, 후보 0
category: query
created: 2026-09-10
updated: 2026-09-23
claim_state: current
verified: 2026-09-23 — main(`41f1fd1`, 이후 계기 변경 `EntryFilter`·진입/청산 봉 시각 필드 포함)에 병합한 트리에서 같은 명령으로 재실행: 기준 거래 1,058/1,659/1,767·셀 수치·N0 20·후보 0 이 기록과 동일 · 2026-09-10 — `RUN_EXTERNAL_GATE=true BACKTEST_CACHE_DIR=~/.cache/coin-trading-bot/backtest-cache ./gradlew :bot:test --tests "*ExternalRegimeGateTest*" --rerun-tasks` (JDK jbr-21.0.9, 약 4분). 사전고정 커밋 `ca3242e`(plan + 하네스 + fixture, 15·5분봉 결과 전); 리뷰가 잡은 배관 결함(위상 이동을 행 인덱스→달력 순환으로, 240·15분 rung 거래수 핀·봉/일·첫 봉 시가 단정 복원) 수정 후 같은 규칙으로 재실행 — 셀 수치 불변, N0 21→20. fixture 10창 D1 + `intraday240/` + `backtest-cache/intraday{15,5}/` + `backtest/external/` 5종(수집 2026-09-10)
sources:
  - bot/src/test/kotlin/com/trading/bot/engine/ExternalRegimeGateTest.kt
  - bot/src/test/kotlin/com/trading/bot/engine/ExternalSeries.kt
  - bot/src/test/kotlin/com/trading/bot/engine/DateGatedStrategy.kt
  - scripts/collect_external_series.py
  - bot/src/test/resources/backtest/README.md
---

# 외부 레짐 게이트 (김프 · 펀딩 · 공포탐욕 · ETH/BTC · 테이커 흐름)

**질문**: [[parameter-search-2026-09]] 가 OHLCV 파생 신호 부류(exit 51,480 좌표 + 신규 가격지표 10종 39,600 좌표)를 닫았다. 거래 대상의 Upbit
봉 **밖**에 있는 정보 — 김치프리미엄, Binance BTC 펀딩레이트, 공포·탐욕 지수, ETH/BTC 비율 추세, BTC 현물 테이커 매수 비율 — 가 현행 `combined` 의
진입을 걸러 거래당 손익을 올리는가. 계기는 [[exit-resolution-ladder-2026-09]] 와 동일(`LiveSemanticsArm` 5분봉 주 판정·10창·진입일 기여·paired maxT).

**답**: **후보 0, 계기 무효.** 5분봉에서 13셀 전부 격차가 양수이고 `FNG_FEAR` 는 maxT 를 넘지만(+0.164%p/거래, T 2.80), 같은 규칙을 **날짜를
순환 이동한 시계열**(정보가 0 인 대조군) 20벌에 적용해도 20건이 통과한다 — 사전고정 상한 13 을 넘어 계기 자체가 무효다. 게이트의 이득은 신호의 정보가
아니라 **기준선이 음수인 구간(5분봉 Σpnl −219%p)에서 진입을 줄이면 무엇이든 이긴다**는 구조에서 나온다. 셀·대조군이 같은 크기의 이득을 내는 것이 그 서명이다.

## 사전고정 (15·5분봉 결과 보기 전 커밋 `ca3242e` — 240분봉은 smoke 로 먼저 관측)

- 신호 5종 전부 BTC 기준 시장 전체 스칼라, 값은 거래일 D 의 09:00 KST 경계 이전에 확정된 **D−1 값**만(펀딩은 UTC D−1 정산 3건 평균, D 00:00 정산분 제외).
  롤링 중앙값 90일·SMA 20일은 D−1 을 **제외**한 이전 구간. look-ahead 규약은 fixture README `external/` 절.
- family 13셀(양방향 대칭 + 결합 1): `KIMP_LOW/HIGH/RISING` · `FUND_NEG/LOW/HIGH` · `FNG_FEAR(≤30)/GREED(≥70)` · `ETHBTC_UP/DOWN(vs SMA20)` ·
  `TAKER_HIGH/LOW(vs 중앙값 90)` · `COMBO(KIMP_LOW ∧ FUND_LOW)`. 셀은 조건이 참인 날만 `combined` 의 진입을 허용, 청산은 기준과 동일.
- 게이트는 엔진이 아니라 **전략 데코레이터**(`DateGatedStrategy`, 이름 위임) — 엔진·`BacktestConfig`·라이브 코드 무수정.
- 통계량 = 선행 사다리 그대로(frame 1,500일·블록 5·B 20,000·maxT FWER 5%·family 13). 수렴 = `g5 ≥ 0.8·g15`.
- **null 게이트**: 다섯 시계열을 s×17일(s=1..20) 순환 이동한 대조군 × 13셀 = 260 에 같은 maxT. 통과 `N0 > 13`(5%) 이면 계기 무효 → 후보 0.
- 후보 = 5분 통과 ∧ 수렴 ∧ ≥ 0.10%p/거래 ∧ 마켓 이름 합집합의 6/8 이상 양수 ∧ 노출 정규화 부호 일치 ∧ 차단율 10~70% ∧ null 유효. 통과 0 이면 현행 유지, 사후 재슬라이스 금지.
- 배관 단정: 항상 참인 게이트(`ALL_PASS`)의 거래 = 기준(세 rung 전부 일치), 10창 전 거래일 시계열 완비, 위상 이동은 날짜 집합·값 다중집합 보존, 5분 기준 거래 1,767 = 선행 사다리.

## 결과

### 사다리 — 격차/기준거래 %p (10창 pooled, maxT FWER 5%, 13셀 q 5분 2.471)

| 셀 | 240분 | 15분 | 5분 | 5분 차단율 | 마켓 +/35 | 판정 |
|---|---|---|---|---|---|---|
| KIMP_LOW | −0.044 | +0.028 | +0.082 | 32% | 22 | — |
| KIMP_HIGH | −0.179 | +0.047 | +0.042 | 68% | 22 | — |
| KIMP_RISING | −0.051 | +0.072 | +0.057 | 57% | 23 | — (미수렴) |
| FUND_NEG | −0.211 | +0.046 | +0.104 | 89% | 25 | — |
| FUND_LOW | −0.096 | +0.043 | +0.076 | 49% | 25 | — |
| FUND_HIGH | −0.127 | +0.032 | +0.048 | 51% | 23 | — |
| **FNG_FEAR** | −0.100 | +0.109 | **+0.164 통과** | 82% | 26 | 후보 아님 — 마켓 26/35 < 27, 차단율 82% > 70%, null 무효 |
| FNG_GREED | −0.126 | +0.065 | +0.070 | 70% | 25 | — |
| ETHBTC_UP | −0.137 | +0.053 | +0.080 | 46% | 25 | — |
| ETHBTC_DOWN | −0.086 | +0.022 | +0.044 | 54% | 22 | — |
| TAKER_HIGH | −0.140 | −0.011 | +0.032 | 43% | 19 | — |
| TAKER_LOW | −0.083 | +0.086 | +0.092 | 57% | 23 | — |
| COMBO | −0.104 | +0.056 | +0.104 | 64% | 28 | — |

기준선: 240분 1,058건 +235.9%p / 15분 1,659건 −124.8 / 5분 1,767건 −219.4 (선행 사다리와 동일). 240분봉에서는 13셀 **전부 음수**, 5분봉에서는 **전부 양수** —
셀의 부호가 신호와 무관하게 기준선의 부호를 거꾸로 따라간다.

### null 게이트 — N0 = 20 / 260 (상한 13) → 무효

위상 이동 20 seed 중 10 seed 에서 1~4셀이 통과했다(최대 T 3.33, 최대 격차/거래 +0.161 — 실제 `FNG_FEAR` +0.164 와 같은 크기). 이동한 시계열은 시장 사이클과의
정렬이 깨져 정보가 0 인데도 실제 셀과 같은 분포의 이득을 낸다. 판정은 사전고정대로 **계기 무효 → 후보 0** 이고, 이것이 이 페이지의 주 관측이다.

### 왜 대조군까지 통과하나

`combined` 의 5분봉 기준선은 10창 중 7창에서 음수다(2020h2 −119, 2025h1 −44, 2023h1 −37 …). 그 창에서는 **어떤 규칙으로든 진입을 절반 줄이면** 손실의
절반이 사라져 격차가 양수가 된다 — 창 표에서 2020-08~2021-02 는 13셀 전부 +33~+119. 반대로 기준선이 양수인 창(2021h2 +17, 2024h2 +13)에서는 같은 셀들이
음수다. 즉 측정된 것은 "이 신호가 나쁜 날을 고른다"가 아니라 "이 기준선에서는 덜 거래하는 것이 낫다"이다. 그 사실은 이미 [[exit-resolution-ladder-2026-09]] 가
기준선 부호 반전으로 보고했고, 이번 결과는 그것의 다른 얼굴이다.

## 결론과 다음

- **다섯 정보 부류 모두 이 계기에서는 발견 없음.** 승격·라이브 반영 없음. 사후 재슬라이스(창·마켓·임계·셀 추가) 금지 — 사전고정 6.
- 같은 질문을 다시 하려면 **먼저 기준선을 고쳐야** 한다: 5분봉에서 음수인 `combined` 위에 게이트를 얹어 재는 한, 진입을 줄이는 모든 규칙이 잡음과 구분되지 않는다.
  #189·#190(5분봉 기준선 재판정)이 선행이다. 그 뒤라면 이 하네스·fixture·null 규약은 그대로 재사용된다(`ExternalRegimeGateTest`, 셀 추가는 새 사전고정으로).
- 셀이 기준 조건의 작은 교란일 때는 조건 값을 통째로 이동하는 이 null 이 셀보다 5~8배 큰 교란이 된다 — [[completed-bar-indicators-2026-09]] 는 셀이 조건을 뒤집는 날(전이일)만 이동하는 규모 맞춘 null 을 썼다.
- 위상 이동 null 은 이번에 **계기 고장을 잡아냈다** — 진입 무작위화([[parameter-search-2026-09]] 변종 A)가 아니라 이 동형 대조군이 게이트 검정의 표준이어야 한다.

## 한계

- 게이트가 진입 하나를 막으면 이후 포지션 상태가 달라져 셀 거래는 기준의 부분집합이 아니다. 기여는 진입일 차분이라 상쇄가 크지만 완전하지 않다.
- 신호는 전부 BTC 기준 시장 전체 스칼라 — 마켓별 김프·펀딩은 재지 않았다(Binance 상장일·유동성 편차로 fixture 결측 증가).
- 20 seed 는 분위수가 아니라 통과 건수 상한(13)으로만 쓴다. 이동 방향은 앞(대조군이 s×17일 뒤의 값을 본다) — 뒤로 옮기면 fixture 시작(2019-10) 앞으로 나가 결측이 된다. 관측된 통과 기제(음수 기준선에서 진입 감소)는 방향과 무관하지만, 방향이 N0 를 키웠을 가능성은 0 이 아니다(리뷰 accepted-risk). BTC 도미넌스·스테이블 유입은 무료 이력이 없어 ETH/BTC·테이커 비율로 대리했다(plan Decisions 5).
- 생존편향·단일 포지션·무슬리피지 등은 선행 사다리와 같다.

## 재현

```sh
python3 scripts/collect_external_series.py --write --end 2026-09-09   # fixture 재수집(이미 커밋됨)
RUN_EXTERNAL_GATE=true BACKTEST_CACHE_DIR=~/.cache/coin-trading-bot/backtest-cache \
  JAVA_HOME=~/Library/Java/JavaVirtualMachines/jbr-21.0.9/Contents/Home \
  ./gradlew :bot:test --tests "*ExternalRegimeGateTest*" --rerun-tasks
# 산출물: bot/build/reports/ 의 게이트 리포트 md — GATE_UNITS=240 은 smoke(판정 아님)
```
