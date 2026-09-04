# 백테 fixture — Upbit 일봉

`KneeStrategyComparisonTest` 등이 쓰는 실제 시세 데이터다. 네트워크 없이 재현 가능하게 하려고 고정해 뒀다.

**국면별로 나뉜다** — 하락장 하나만 보면 "이 전략이 원래 나쁜지, 이 장에서만 나쁜지"를 가를 수 없다.

구간 이름 중 `p2024h2`·`p2025h1` 은 **기간**으로 붙였다. 성격은 수집한 뒤에야 알 수 있고, 결과를 보고 "상승장"이라 이름 붙이면 그 라벨이 곧 사후 서사가 된다(`bear`/`bull` 은 그 전에 붙은 이름이라 그대로 둔다).

네 구간 중 `bull`·`p2024h2`·`p2025h1` 셋은 `yearly/`(2025-09-03~2026-09-02) 구간과 **겹치지 않는다** — 시간 독립 holdout 이다. `bear/`(2026-01~08)는 `yearly/` 안에 통째로 들어가므로 robustness 표기 전용이며 독립 증거로 세지 않는다.

| 디렉토리 | 구간 | 마켓 | 성격 |
|---|---|---|---|
| `bear/` | 2026-01-31 ~ 2026-08-18 | 8개 | **8개 전부 마이너스** (−27% ~ −91%) |
| `bull/` | 2023-11-23 ~ 2024-06-09 | 8개 | SOL +196%, POLYX +153%, BTC +96%, MINA +27% / XRP −14%, BLUR −23%, GAS −40%, ARK −43% |
| `p2024h2/` | 2024-06-10 ~ 2024-12-26 | 8개 | **강한 상승** — XRP +366%, DOGE +132%, BTC +48%, SOL +27% / ETH −2%, PYTH −4% |
| `p2025h1/` | 2025-01-01 ~ 2025-07-19 | 8개 | **혼조·약세** — XRP +35%, BTC +14% / 나머지 6종 −2 ~ −71%(AGLD −71%) |

**상승장에서도 거래대금 상위의 절반이 손실**이라는 점에 주목할 것. 예전 `bull/` 은 BTC +96%·ETH +89%·
DOGE +102%·XRP −16% 로 균일하게 오르는 장처럼 보였는데, 그건 **오늘의 승자만 담았기 때문**이었다(#112).

두 국면 상위 8에 모두 든 3마켓(`KRW-XRP`·`KRW-BTC`·`KRW-SOL`)은 **paired 비교**에 쓴다 —
마켓 구성을 고정해야 차이가 국면에서만 온다. 예전 4개에서 줄었는데, 유동 유니버스가 실제로 회전하기
때문이다. 겹침을 늘리려 선정 규칙을 손대면 그게 다시 선택 편향이라 그대로 둔다.

## 출처

- 엔드포인트: `GET https://api.upbit.com/v1/candles/days?market=<market>&count=200` (공개 시세 API, 인증 불필요)
- 수집일: 2026-08-26 (두 국면 모두 재수집 — #112)
- 과거 구간은 `to` 파라미터로 받는다: `&to=2024-06-10T00:00:00Z` (그 시각 **이전** 200봉)
- **마켓 선정 = 구간 시작 시점 기준** (`scripts/collect_backtest_fixtures.py` 가 규칙의 단일 소스):
  구간 시작 **이전** 30일 평균 거래대금 상위 8, 스테이블코인 제외, 순위 근거 30봉 미만(상장 직후) 제외,
  30봉이 달력상 32일을 넘는 창(거래 공백) 제외 — 감사 selector `PointInTimeUniverse` 와 같은 규칙
  - 200봉은 개수가 아니라 **달력 날짜**로 구간 충족을 판정한다(거래 없는 날은 봉이 생략되므로)
  - `bear/`: `KRW-XRP` `KRW-BTC` `KRW-ETH` `KRW-AXS` `KRW-DATA` `KRW-ENSO` `KRW-SOL` `KRW-BERA`
  - `bull/`: `KRW-GAS` `KRW-XRP` `KRW-BTC` `KRW-SOL` `KRW-ARK` `KRW-MINA` `KRW-BLUR` `KRW-POLYX`
  - 순위 창이 구간 시작 **이전**이라 구간 내부·이후 정보를 쓰지 않는다(look-ahead 없음)

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
- **마켓끼리 독립이 아니다.** 일간 로그수익률 상관 평균은 **국면마다 다르다** — bear 0.442 · bull 0.448 ·
  p2024h2 0.577 · p2025h1 0.674 · **`yearly/` 0.796**. 실효 독립 표본 `8/(1+7ρ)` 은 각각 1.95 · 1.93 · 1.59 · 1.40 · **1.22** 다.
  ⚠️ 2026-09-05 이전 이 문단은 국면 fixture 의 0.49 를 `yearly/` 에도 인용했다 — **오기였다**
  (`yearly/` 는 8종이 전부 메이저라 상관이 훨씬 높다). 그 오기 위에 세워진 "양수 마켓 다수결" 게이트 해석은
  wiki `query/exit-resolution-verdict-2026-09` 가 정정한다.
- **유니버스 look-ahead 는 실측한 뒤 제거했다(#112).** 구 로스터("수집 시점 거래대금 상위 + 200봉 확보")는
  구간 끝 정보로 고른 것이었고, 시점 중립으로 다시 고르면 **하락장 3/8 · 상승장 2/4** 만 겹쳤다
  (같은 selector 를 수집일에 돌린 절차 노이즈 바닥은 5/8 · 3/4 — 시점 이동에 귀속되는 몫이 따로 있다).
  상세·재현은 wiki `query/universe-look-ahead-audit`, 하네스는 `PointInTimeUniverseAuditTest`.
  현재 fixture 는 위 "출처"의 시점 중립 규칙으로 재수집한 것이다.
- **생존편향은 남아 있고, 제거도 측정도 불가능하다.** 그 사이 상장폐지된 종목은 Upbit 공개 API 가
  404 를 낸다(`KRW-LUNA`·`BTC-LUNA`·`USDT-LUNA` 실측 — 존재하지 않는 코드와 동일 응답).
  데이터가 아예 없으므로 표본에 넣을 방법이 없고, 그때 무엇이 상장돼 있었는지 알 길이 없어
  **편향의 크기조차 추정할 수 없다**. #112 가 없앤 것은 신규상장 배제 편향과 "오늘의 승자를 과거에
  소급 적용하는" look-ahead 이지 생존편향이 아니다.
- 더 과거·더 많은 국면이 필요하면 `to` 를 옮겨가며 추가 수집한다(구간당 200봉 상한).

## 재수집

```sh
python3 scripts/collect_backtest_fixtures.py            # 유니버스만 미리보기
python3 scripts/collect_backtest_fixtures.py --write     # fixture 파일까지 기록
```

선정 규칙·정규화가 전부 그 스크립트에 있다 — 문서에만 적어두면 어긋난다.
유니버스가 바뀌면 `BacktestFixtures.MARKETS_BY_REGIME`·`PAIRED_MARKETS` 와
`BacktestFixturesTest` 의 핀, 그리고 `legacy-golden.txt` 를 함께 갱신해야 한다.

⚠️ **마지막 봉이 완결됐는지 확인할 것.** 예전 `bear/` 은 2026-08-19 에 수집해 마지막 봉
(2026-08-18 09:00~2026-08-19 09:00)이 형성 중인 미완성 봉이었다. 구간 끝 다음날 09:00 KST 이후에 받는다.

구간이 달라지면 `KneeStrategyComparisonTest` 의 수치도 달라진다 — 결과를 인용할 때는 수집일을 함께 적는다.

## `yearly/` — 운영 티커 8종의 최근 1년 (국면 fixture 와 별개)

| 항목 | 값 |
|---|---|
| 마켓 | `KRW-BTC` `KRW-ETH` `KRW-XRP` `KRW-SOL` `KRW-DOGE` `KRW-ADA` `KRW-AVAX` `KRW-LINK` — 수집일의 `deploy/vultr/.env` `TRADING_TICKERS`(사용자 지정, 시점 중립 선정 아님) |
| 구간 | 2025-09-03 ~ 2026-09-02, 365봉(최신순), 결측 0 |
| 수집 | 2026-09-03, `python3 scripts/collect_yearly_fixtures.py --write` — 요청당 200봉 상한이라 `to` 로 2회 페이징, 종료일은 스크립트 상수 `END_DATE`(재현성) |
| 용량 | ≈540KB(8파일) |
| 로더 | `YearlyFixtures`(테스트 소스). `BacktestFixtures.Regime` 에 얹지 않는다 — 그 enum 을 순회하는 기존 측정의 모집단이 바뀐다 |
| 소비자 | `YearlyStrategyComparisonTest` — `RUN_YEARLY_COMPARE=true` 로 전체 비교, 결과는 `bot/build/reports/yearly-strategy-comparison.md`. wiki `query/yearly-strategy-comparison`<br>`StrategySearchRunTest` — `RUN_STRATEGY_SEARCH=true` 로 파라미터·아이디어 탐색(bull·bear fixture 도 함께 쓴다), 결과는 `bot/build/reports/parameter-search.md`. wiki `query/parameter-search-2026-09` |

한계: 이 8종은 지난 1년을 **살아남아 운용 중인** 종목이라 생존편향이 있고(그해 상장폐지·이탈 종목은 표본 밖),
상관이 매우 높아(평균 ρ **0.796**) **실효 독립 표본은 1.22** 다 — 8마켓을 8관측으로 세면 안 된다.
이 구간은 8종 전부 −36~−72% 인 단일 하락 국면이다.

## `intraday240/` — 240분봉 (시각 축 전용)

| 항목 | 값 |
|---|---|
| 구간·마켓 | 위 5개 fixture(`yearly`·`bear`·`bull`·`p2024h2`·`p2025h1`)와 **동일** — 일봉과 다른 유니버스를 쓰면 나란히 놓을 수 없다 |
| 격자 | KST 01/05/09/13/17/21 (UTC 00/04/08/12/16/20). 09:00 이 격자 위에 있다 |
| 규모 | 55,912봉 / 5창 × 8마켓, ≈12MB |
| 수집 | 2026-09-05, `python3 scripts/collect_intraday_fixtures.py --write` |
| 정규화 | 일봉과 같은 키에 **`candle_date_time_utc` 를 추가**한다 — `M1ReplayEngine` 이 그 필드를 파싱하므로 빼면 전건 예외다 |
| 결측 | `gaps.json` 에 목록. bull 구간 `2023-12-03T16:00:00`(KST 12-04 01:00) 1봉이 **8마켓 전부** 없다 = 거래소 측 공백 |
| 소비자 | `ExitHourSweepTest` — `RUN_EXIT_HOUR=true`. wiki `query/exit-resolution-verdict-2026-09` |

**왜 240분인가**: 청산 시각 H 의 체결가는 그 시각 봉의 `open` 이고 이 값은 240분봉이든 1분봉이든 같다.
granularity 가 바꾸는 것은 후보 시각의 개수(240m → 6개)와 경계 *사이* 게이트 판정 횟수뿐이다.
1분봉은 5창 2.5GB·67,104요청이면서 시각 축에 정보를 추가하지 않는다(#143 은 별개 질문 — 경계 사이 해상도).

**왜 필요한가**: Upbit 일봉 경계가 곧 09:00 이라 **일봉 종가 ≡ 익일 일봉 시가**이고(KRW-BTC 364쌍 평균 |차| 0.012%),
따라서 일봉에는 09:00 이외 시각에 대한 정보가 **0비트**다. 시각 축은 일중봉 없이 원리적으로 측정 불가다.
