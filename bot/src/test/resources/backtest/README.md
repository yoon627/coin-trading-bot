# 백테 fixture — Upbit 일봉

`KneeStrategyComparisonTest` 등이 쓰는 실제 시세 데이터다. 네트워크 없이 재현 가능하게 하려고 고정해 뒀다.

## 출처

- 엔드포인트: `GET https://api.upbit.com/v1/candles/days?market=<market>&count=200` (공개 시세 API, 인증 불필요)
- 수집일: 2026-08-19
- 구간: 2026-01-31 ~ 2026-08-18 (마켓별 200봉)
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

- **하락장 한 국면이다.** 8개 중 7개가 마이너스(BTC −23%, ETH −27%, XRP −43%, DOGE −36%, WLD −26%,
  RVN −64%, MMT −6%, ONDO +9%), 최대낙폭 26~66%. 여기서 나온 수치를 일반화하면 안 된다.
- **마켓끼리 독립이 아니다.** 일간 로그수익률 상관 평균 0.49(BTC/ETH 0.90) → 실효 독립 표본은 2개 남짓이다.
- **유니버스 선정에 look-ahead 가 있다.** "수집 시점 거래대금 상위 + 200봉 확보"는 구간 끝 정보로 고른 것이라
  생존편향과 신규상장 배제 편향이 있다.
- 상승장 표본은 일봉 API 의 `count` 상한(200)으로 확보하지 못했다. 더 과거를 보려면 `to` 파라미터로 별도 수집이 필요하다.

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
