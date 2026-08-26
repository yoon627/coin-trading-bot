---
title: 백테 fixture 유니버스 look-ahead 편향 실측 — 상승장 4마켓은 고칠 수 있다
category: query
created: 2026-08-26
updated: 2026-08-26
claim_state: current
verified: 2026-08-26 — `RUN_UNIVERSE_AUDIT=true ./gradlew :bot:test --tests "*PointInTimeUniverseAuditTest*"` (commit 91db3e0, 후보 287 KRW 마켓)
sources:
  - bot/src/test/kotlin/com/trading/bot/engine/PointInTimeUniverse.kt
  - bot/src/test/kotlin/com/trading/bot/engine/PointInTimeUniverseAuditTest.kt
  - bot/src/test/resources/backtest/README.md
  - https://github.com/yoon627/coin-trading-bot/issues/112
---

# 백테 유니버스 look-ahead 편향 실측

백테 fixture 의 마켓은 **수집 시점(2026-08)의 24h 거래대금 상위**로 골랐다. 구간 **끝** 정보로 유니버스를
고른 것이라 look-ahead 다([[backtest-engine]] 의 fixture 절, #112).

이 페이지는 **t0(구간 시작) 이전 정보만** 써서 "그때라면 무엇을 골랐을 것인가"를 재구성한 결과다.

## 선정 규칙 (시점 중립)

`PointInTimeUniverse.select` — 네트워크를 타지 않는 **순수 함수**다(스냅샷만 먹는다).

1. 후보: `GET /v1/market/all` 의 KRW 마켓
2. **t0 직전 30봉 완비** 여야 자격 — 상장 당일 거래대금 스파이크가 상위를 오염시키는 것을 막는다
3. 스테이블코인 제외(코드 상수)
4. 30일 `candle_acc_trade_price` **평균** 내림차순 → 상위 8

**`200봉 확보 가능`은 선정 조건에서 뺐다.** fixture 는 `to=<구간끝>&count=200` 으로 받으므로 그 조건은
"구간 끝까지 살아있었나"를 묻는 것이고, 정확히 없애려던 look-ahead 다. 200봉 충족 여부는 **결과로만** 본다.

## 결과 — 3단 감쇠

| 단계 | 하락장 2026-01~08 | 상승장 2023-11~2024-06 |
|---|---|---|
| ① t0 시점 상장 후보 | 229 | 102 |
| ② 선정조건 통과 | 222 | 102 |
| ③ top-8 중 **구간 200봉 충족** | **8** | **8** |

**③ 이 이 감사의 핵심 숫자다.** 상승장 시점 중립 top-8 은 8개 전부 그 구간 200봉을 채운다:
`KRW-GAS` `KRW-XRP` `KRW-BTC` `KRW-SOL` `KRW-ARK` `KRW-MINA` `KRW-BLUR` `KRW-POLYX`

즉 **상승장이 4마켓뿐인 것은 데이터가 없어서가 아니라 유니버스를 오늘 기준으로 골랐기 때문이다.**
(오늘 상위 8개 중 `MMT`·`WLD`·`RVN`·`ONDO` 가 그 시기 미상장 → 남은 4개만 쓰였다.)

## 현재 로스터와의 대조

| | 하락장 | 상승장 |
|---|---|---|
| overlap | **3/8** | **2/4** |
| placebo 재현(절차 노이즈 바닥) | **5/8** | **3/4** |
| look-ahead horizon | 200일 | **1001일** |

- 빠지는 마켓 — 하락장 `MMT WLD RVN ONDO DOGE`, 상승장 `ETH DOGE`
- 들어오는 마켓 — 하락장 `AXS DATA ENSO SOL BERA`, 상승장 `GAS SOL ARK MINA BLUR POLYX`

**placebo 를 함께 읽어야 한다.** 현행 규칙은 "수집시점 **24h**", 신안은 "t0 직전 **30일 평균**" 이라
시점과 집계창이 **둘 다** 다르다. 같은 30일-평균 selector 를 **수집일**에 돌리면 커밋 로스터를
하락장 5/8 · 상승장 3/4 만 재현한다 — 그게 **추정량 차이만으로 생기는 바닥**이다.
거기서 시점을 t0 로 옮기면 3/8 · 2/4 로 더 떨어진다. **시점 이동에 귀속되는 몫이 따로 있다.**

## 읽을 때의 제약

- **overlap 이 높다는 것을 "편향이 작다"의 근거로 쓰지 않는다.** 폐지 종목은 `/v1/market/all` 에서 사라져
  후보에 오르지 못하고, 그 자리를 살아남은 마켓이 채운다 → overlap 은 낙관 쪽으로 편의된다.
  여기서는 overlap 이 **낮게** 나왔고 그 방향은 편의되지 않은 쪽이라, "편향이 실재한다"까지는 말할 수 있다.
- **두 국면의 overlap 을 같은 잣대로 비교하지 않는다.** horizon 이 200일 대 1001일로 5배 차이다.
- **완전한 시점 중립은 아니다.** t0 직전 30일 안에 상장한 종목은 배제된다(스파이크 오염 방지와 맞바꿨다).
- **닫히지 않는 편향**: 생존편향 중 **폐지 배제분**. 상장 유지 중인 종목은 급락했어도 정상 반영되므로
  남는 건 그 몫뿐이다. 공식 API 에 폐지 종목 열거 경로가 없다(`market/all` 응답은
  `market`·`korean_name`·`english_name`(+`market_event`)뿐, 상장일 필드도 과거 시점 파라미터도 없음).
- **이건 표본력 문제를 풀지 않는다.** #112 는 편향(타당성)이고, [[reset-churn-measurement]] 를 묶은 것은
  표본력(N_eff≈2, 마켓 상관 0.49)이다. 상승장이 4→8 이면 그 국면 N 은 2배지만 N_eff 증가폭은 상관에 제약된다.

## 사전 등록한 판정과 그 결과

데이터를 보기 **전에** 고정한 규칙:

> 상승장 시점중립 top-8 중 구간 200봉을 채우는 마켓이 **6개 이상이면** 상승장 한정 재수집을 후속 이슈로 제안.
> 6 미만이면 "4마켓 제약은 유니버스 선정이 아니라 데이터 가용성 탓"으로 닫는다.

**결과 8 ≥ 6 → 재수집 제안.**

비용 견적(이미 알려진 것): 8마켓 수집 + `legacy-golden.txt`(377줄) 재생성 +
[[reset-churn-measurement]] 재실행 + 그 wiki 페이지 갱신. `BacktestFixtures` 를 참조하는 테스트가 6개다.

## 재현

```sh
RUN_UNIVERSE_AUDIT=true ./gradlew :bot:test --tests "*PointInTimeUniverseAuditTest*"
```

selector 는 순수 함수라 CI 에서도 검증된다(`PointInTimeUniverseTest`, 네트워크 없음).
네트워크 단계는 스냅샷 해시를 리포트에 남긴다 — `/v1/market/all` 은 시간 불변이 아니어서(신규상장·폐지로
후보군이 바뀐다) 그 해시가 있어야 같은 입력이었는지 대조할 수 있다.
