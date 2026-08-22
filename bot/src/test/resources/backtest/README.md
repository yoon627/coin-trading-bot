# 백테 fixture — Upbit 일봉

`KneeStrategyComparisonTest` 등이 쓰는 실제 시세 데이터다. 네트워크 없이 재현 가능하게 하려고 고정해 뒀다.

**국면별로 나뉜다** — 하락장 하나만 보면 "이 전략이 원래 나쁜지, 이 장에서만 나쁜지"를 가를 수 없다.

| 디렉토리 | 구간 | 마켓 | 성격 |
|---|---|---|---|
| `bear/` | 2026-01-31 ~ 2026-08-18 | 8개 | 8개 중 7개 마이너스 (BTC −23%) |
| `bull/` | 2023-11-23 ~ 2024-06-09 | 4개 | BTC +96%, ETH +89%, DOGE +102%, XRP −16% |

`bull/` 이 4마켓뿐인 이유는 `MMT`·`WLD`·`RVN`·`ONDO` 가 그 시기 **미상장**이기 때문이다. 현재 거래대금
상위 8개 중 절반이 2년 전엔 없었다는 뜻이고, 이것 자체가 아래 look-ahead 편향의 실증이다.
두 국면에 모두 있는 4마켓(`KRW-XRP`·`KRW-BTC`·`KRW-ETH`·`KRW-DOGE`)은 **paired 비교**에 쓴다 —
마켓 구성을 고정해야 차이가 국면에서만 온다.

## 출처

- 엔드포인트: `GET https://api.upbit.com/v1/candles/days?market=<market>&count=200` (공개 시세 API, 인증 불필요)
- 수집일: `bear/` 2026-08-19, `bull/` 2026-08-20
- 과거 구간은 `to` 파라미터로 받는다: `&to=2024-06-10T00:00:00Z` (그 시각 **이전** 200봉)
- 마켓 선정: 수집 시점 24h 누적 거래대금 상위 중 **200봉 확보 가능** + **스테이블코인 제외**
  - 채택: `KRW-XRP` `KRW-BTC` `KRW-MMT` `KRW-ETH` `KRW-WLD` `KRW-RVN` `KRW-ONDO` `KRW-DOGE`
  - 제외: `KRW-USDT`(스테이블코인 — 변동성이 없어 전략 비교에 무의미), `CAP`·`DOS`·`HOME`·`GEOD`·`GRVT`·`EDGE`·`RE`(상장 이력 1~161봉)

## 정규화

원본 응답에서 `Candle` 이 쓰는 7개 키만 남겼다.

- **가격(`opening_price`/`high_price`/`low_price`/`trade_price`)은 값을 바꾸지 않았다.** 수익률에 직결하므로
  `90349000.00000000` → `90349000` 표기 정규화만 했다.
- **`candle_acc_trade_volume` 만 소수 4자리 반올림.** 거래량 조건이 상대 비교(`vol >= avg`)라 4자리로 충분하고,
  파일 크기가 344KB → 284KB 로 준다.
- 정렬은 API 응답 그대로 **최신순**(index 0 = 최신). `BacktestEngine.run` 이 내부에서 뒤집으므로 그대로 넘긴다.

## ⚠️ 이 데이터의 한계

- **국면이 둘뿐이다.** `bear/` 는 8개 중 7개 마이너스(최대낙폭 26~66%), `bull/` 은 BTC +96% 구간이다.
  두 국면으로 늘렸어도 여전히 특정 시기이며, 여기서 나온 수치를 일반화하면 안 된다.
- **마켓끼리 독립이 아니다.** 일간 로그수익률 상관 평균 0.49(BTC/ETH 0.90) → 실효 독립 표본은 2개 남짓이다.
- **유니버스 선정에 look-ahead 가 있다.** "수집 시점 거래대금 상위 + 200봉 확보"는 구간 끝 정보로 고른 것이라
  생존편향과 신규상장 배제 편향이 있다.
- 더 과거·더 많은 국면이 필요하면 `to` 를 옮겨가며 추가 수집한다(구간당 200봉 상한).

## 재수집

같은 조건으로 다시 받으려면 각 마켓에 대해 위 엔드포인트를 호출하고 아래 jq 로 정규화한다.

```sh
jq -c '[.[] | {
    market, candle_date_time_kst,
    opening_price: (.opening_price + 0),
    high_price: (.high_price + 0),
    low_price: (.low_price + 0),
    trade_price: (.trade_price + 0),
    candle_acc_trade_volume: ((.candle_acc_trade_volume * 10000 | round) / 10000)
  }]'
```

구간이 달라지면 `KneeStrategyComparisonTest` 의 수치도 달라진다 — 결과를 인용할 때는 수집일을 함께 적는다.
