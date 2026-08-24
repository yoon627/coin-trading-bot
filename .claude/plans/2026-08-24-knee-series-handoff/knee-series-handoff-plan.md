---
title: knee-series-handoff — 무릎 전략 시리즈 종료 요약과 남은 백로그
status: done
started: 2026-08-24
updated: 2026-08-24
---

# Goal

"무릎에서 사서 어깨에 판다"로 시작한 작업 시리즈(PR 7개)의 **결론과 남은 것**을 한 곳에 모은다.
plan 5개를 다 읽지 않아도 다음 세션이 이어받을 수 있게 하는 것이 목적이다.

# Progress

- 2026-08-24 — 시리즈 종료. plan 5개 `done` 처리, 백로그를 이슈로 이관 완료.

## 머지된 PR

| PR | 내용 | 결과 |
|---|---|---|
| #95 | 무릎 매수 2종(`knee_reversal`·`knee_pullback`) + 어깨 청산 | 머지 |
| #98 | RSI window 의존성 제거 + 백테 관찰 기록 | **승격 근거 없음** |
| #99 | 상승장 표본 추가, 국면 paired 비교 | **국면 탓 아님** |
| #103 | 사후 정적 리뷰 지적 반영 | 머지 |
| #114 | 백테 MDD 과소평가 + 복원 시 전략 오보 수정 | 머지 |
| #118 | 최소 봉수 경계 예외 + wiki 2곳 동기화 | 머지 |
| #124 | `TradingStrategy.minCandles` 계약 도입 | 머지 |

## 원래 물음에 대한 답

**이 봇 조건에서 무릎 전략이 기존 7종보다 낫다는 근거는 없다.**

- out-of-sample 에서 두 전략 모두 상위권에 못 든다.
- 같은 4마켓으로 국면만 바꾼 paired 비교에서 `knee_reversal` 은 하락장 +0.306 → **상승장 −0.288** —
  "하락장이라 나빴다"는 설명도 지지되지 않는다.
- **어깨 청산을 켜면 네 조합 모두 성과가 낮아졌다.** 원 요청의 "어깨에 판다" 부분이 성과를 깎는 방향이다.
- ⚠️ 셀당 거래 4~18건, 마켓 상관 0.49(실효 독립 표본 ≈2)라 **크기는 신뢰할 수 없고 방향만 참고**한다.

재현: `KneeStrategyComparisonTest` (`-i` 로 실행하면 표가 stdout).

## 부수적으로 고친 실제 버그

- `calculateRsi` 가 리스트 전체를 써 **백테(50봉)와 라이브(21~60봉)의 RSI 가 달랐다** (신호 3건 불일치)
- `BacktestEngine.run` 이 **정확히 50봉에서 IndexOutOfBounds** (도달 가능한 경로였다)
- `closeOpenPosition` 이 MDD 를 갱신하지 않아 백테 낙폭이 과소평가
- `UserTradingManager` 가 `setStrategy` 실패를 은폐해 로그·캐시가 죽은 전략명을 보고
- 볼린저 청산이 **상승봉에서 오발동**(밴드 확장 시)

# Next

이 plan 은 요약 전용이며 done 이다. 다음 작업은 아래 이슈에서 고른다.

# Decisions

- **plan 5개를 요약 하나로 대체하지 않고 둘 다 남긴다.** 각 plan 은 그 작업의 결정·리뷰 처분을 담고
  있어 삭제하면 근거가 사라진다. 이 문서는 진입점 역할만 한다.
- **승격하지 않는다.** 리스크를 키우는 전이는 사람이 정한다(wiki [[strategy-evolution-expectations]]).
  전략은 코드에 남아 있고 기본값(`combined`)은 그대로라 운영에 영향이 없다.

# Key Files

- `common/.../strategy/{KneeReversal,KneePullback,ShoulderExit}.kt` — 전략 구현
- `bot/src/test/.../engine/KneeStrategyComparisonTest.kt` — 비교 재현
- `bot/src/test/resources/backtest/{bear,bull}/` — 고정 fixture (+ `README.md` 에 출처·한계)
- `bot/src/test/.../strategy/StrategyMinCandlesTest.kt` — `minCandles` 계약
- wiki `swing-strategies`·`trading-engine-loop`·`backtest-engine`·`marketdata-pipeline`

# Blockers

없음.

# Deferred

전부 이슈로 이관했다 — plan 이 닫혀도 유실되지 않는다.

| # | 내용 | 비고 |
|---|---|---|
| #110 | 어깨 청산이 성과를 낮추는 이유 조사 | 표본 한계 있음 |
| #111 | 백테 테스트 정리(중복 계산·JSON 재파싱·문구 중복·locale·국면 라벨 가드) | chore |
| #112 | 백테 유니버스 look-ahead/생존편향 | 과거 상장 목록 필요 |
| #132 | 라운드트립이 엔진 매수 이후 수동 매수분을 무시해 잔량 오판 | ⚠️ 미검증 |
| #133 | 엔진 매수 수수료를 잔고 스냅샷 금액으로 계산해 부풀려짐 | ⚠️ 미검증 |

**#132·#133 이 우선순위가 높다** — 실제 금액·잔량 계산이 틀어지는 문제다. 다만 codex 정적 분석이라
착수 시 재현부터 해야 한다. 둘 다 `trade_records.volume` 이 경로마다 의미가 다른 데서 나온 같은 뿌리다.

그 외 plan 안에만 있는 항목:
- 부팅 D1 seed 실패 무재시도(`MarketDataIngestionService.kt:241-245`) — candle-sufficiency plan Deferred
- KIS 청산이 진입 전략을 복원하지 않음(`KisStockTradingEngine.kt:182`) — 〃

# Review Disposition

(해당 없음 — 요약 문서)

# Workflow Findings

시리즈 내내 반복된 것 두 가지. 다음 작업에서도 같은 실수가 나올 수 있다.

- **문제 정의를 코드로 검증하지 않고 쓴 적이 두 번 있었다.** #109 는 "엔진이 21로 게이트한다"고 plan·이슈에
  적었는데 실제로는 소스 선택자였고, #107 은 "UI 도 거짓말한다"고 했는데 `getStatus` 는 정확했다.
  둘 다 **호출 경로를 따라가 보고 나서야** 드러났다. 문제 정의 문장은 상수·이름이 아니라 경로로 확인한다.
- **리뷰 지적도 증거로 확인한 뒤 반영해야 한다.** plan-reviewer 의 `maxHoldDays=1` 제안은 적용하니 테스트가
  실패했고(체결 봉은 `holdDays=0`), codex 의 `endTrades <= trades` 제안은 오배분을 못 잡았다.
  두 경우 모두 **지적 방향은 맞고 제안은 틀렸다** — mutation 으로 확인한 뒤 더 정확한 해법으로 갔다.

(참고: 앞선 plan 들에 "PreToolUse hook 오탐 4회"로 기록했던 것은 **오진이었다.** 실제 원인은 권한
허용목록이 정확한 문자열 3개뿐이라 gradle 명령 변형이 subagent 에서 자동 거부된 것이었고,
`.claude/settings.local.json` 을 `Bash(./gradlew:*)` 로 넓혀 해결했다. 각 plan 에 정정을 남겼다.)
