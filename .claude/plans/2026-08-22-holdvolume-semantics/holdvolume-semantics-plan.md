---
title: holdvolume-semantics — holdVolume 의 의미를 하나로 못박고 유령 포지션 제거 (#56)
status: in_progress
started: 2026-08-22
updated: 2026-08-22
---

# Goal

`TradingState.holdVolume` 이 채워지는 경로마다 `free` / `free+locked` 로 갈리는 문제를 단일 정의로 정렬하고,
매도 부분체결 분기가 **우리 주문이 아닌 lock**(출금 대기·수동 주문)까지 잔여 포지션으로 세어 만드는
유령 포지션을 제거한다. GitHub #56 (출처: PR #50 3차 code-review).

# Progress

- 2026-08-22 Explore 완료. 쓰기 4경로·읽기 3소비자 확인. Upbit `locked` 정의를 공식 문서로 확정.
  귀속 불명 lock 처리 방침을 사용자와 확정(WARN + 매수 차단). 규모 medium 판정.
- 2026-08-22 baseline `./gradlew :bot:test` BUILD SUCCESSFUL — 사전 실패 없음.
- 2026-08-22 plan-review(Claude subagent + codex 0.147.0 medium 병행) → CONDITIONAL, P1 5건.
  D1~D4 를 **상한 규칙(D1')** 으로 전면 개정. 상세는 아래 `# Review Disposition`.
- 2026-08-22 TDD Red 확인 — 신규/보정 테스트 6개가 의도한 값으로 실패(`0.0014↔0.0004`, `0.003↔0.001`,
  `0.0024↔0.0004`, `position true↔false`). 가드 테스트 2개(Acceptance 1·7)는 예상대로 Red 아님.
- 2026-08-22 구현 → Green. `./gradlew :bot:test` 94 tests 전부 통과, `compileKotlin` 통과.
  wiki 3종(check_links / verify / smoke) 통과, `upbit-api`·`trading-engine-loop`·`index.md` 갱신.
- 2026-08-22 code-review(Claude subagent + codex 0.147.0 high 병행) → REQUEST CHANGES.
  CONFIRMED Major 1 + Minor 8 + PLAUSIBLE 1. 처분은 아래 표. 수정 후 재검증 통과.
- 2026-08-22 simplify 체크(메인 직접) — `unattributableLockWarned` 중복 대입 1건 제거,
  syncPosition 주석 7줄 → 4줄 축약. 동작 불변, 재검증 통과.

# Next

최종 검증 → 커밋 → PR (`Closes #56`).

# Decisions

## D1'. holdVolume 의 단일 정의 — locked 상한 규칙 (개정, 구 D1/D2/D4 대체)

> `holdVolume` = **봇이 자기 포지션으로 통제하는 수량**
> = `free + min(locked, 우리 매도 주문이 아직 잠그고 있을 수 있는 최대 수량)`

`우리 몫 상한`(이하 `ourLockedCap`)은 **우리 주문의 미체결 잔량 중 아직 free 에 안 나타난 부분**이다:

```
ourRemainder = 우리 매도 주문의 미체결 잔량   (주문 없으면 0, 수량 미상이면 +∞)
ourLockedCap = max(0, ourRemainder - free)
held         = free + min(locked, ourLockedCap)
```

**왜 상한인가.** Upbit 공식 문서상 `locked` = "출금이나 주문 등에 잠겨 있는 잔액"(✅ docs.upbit.com,
2026-08-22)이라 우리 주문 외 사유가 섞인다. 양극단이 모두 틀린다:

- `locked` 를 통째로 **더하면** — `sell()` 은 `free` 만 주문하므로(`PositionManager.kt:480`,`:503`)
  팔 수 없는 수량을 보유로 세게 되어 매 tick "Sell deferred" 만 반복하는 유령 포지션이 남는다.
- `locked` 를 통째로 **버리면** — 우리 주문이 방금 terminal 이 됐지만 거래소가 아직 locked→free 를
  반영하지 않은 순간에 잔여 포지션을 잃고 `markSold` 로 오판한다(손절·익절이 꺼진다).

상한을 두면 양쪽을 다 피한다. `- free` 항이 핵심이다: 우리 몫이 **이미 free 에 반영됐으면** 상한이 0 이
되어 타 사유 locked 가 새어 들어오지 않는다.

**이 규칙은 거래소의 read-after-write 정합을 가정하지 않는다** — 반영이 빠르든 늦든 결과가 같다.
plan-review 가 요구한 "Upbit order-state↔account 반영 순서 확인"이 불필요해지는 이유다(D7 참조).

## D2'. 경로별 `ourRemainder`

| 경로 | `ourRemainder` | 근거 |
|---|---|---|
| `syncPosition` | `pendingSellUuid != null` 이면 `pendingSellVolume ?: +∞`, 아니면 `0.0` | uuid 는 durable 이고 `syncPosition` 보다 먼저 복원된다(✅ `TradingEngine.kt:78` seed → `:175` sync). `pendingSellVolume` 은 주문 수량 그대로(`PositionManager.kt:520`) |
| `applySellFillOutcome` 부분체결 | `(pendingSellVolume ?: +∞) - executed` | 우리 주문이 못 판 몫 |
| `completeBuy` | `0.0` (매수 직후 우리 매도주문 없음) | 현행 `free` 와 동일 — **코드 변경 없음** |
| `recoverSellFromBalance` | 변경 없음 (`totalBalance()` 로 "다 나갔나" 판정) | D7-3 |

`+∞`(`Double.POSITIVE_INFINITY`)는 "우리 주문 수량을 모른다" = 상한 없음 = **현행 동작 유지**를 뜻한다
(레거시 durable row 에 `pendingSellVolume` 이 null 인 경우). 즉 이 변경은 정보가 있을 때만 조인다.

**P1-1 이 이 규칙으로 소멸한다.** `applySellFillOutcome` 의 cancel+0 분기(`:596`)는 `clearPendingSell()`
**이전에** `syncPosition` 을 부르므로 죽은 주문의 uuid 가 살아 있다 — 구 D2(uuid 유무 boolean)에서는
타 사유 locked 를 보유로 세는 구멍이었다. 상한 규칙에서는 그 uuid 가 주는 게 boolean 이 아니라
`ourRemainder = pendingSellVolume` 이고, 취소분이 이미 free 로 돌아왔으면 `max(0, remainder - free) = 0`
이 되어 자동으로 닫힌다. **`syncPosition` 에 파라미터를 추가하지 않는다.**

## D3'. 귀속 불명 lock — `syncPosition` 은 position 을 내리지 않는다 (개정)

`held == 0 && locked > 0 && pendingSellUuid == null` 이면:
`unsynced = true` (신규 매수 차단) + **WARN 1회** (전이 시점에만). `position` 은 **건드리지 않는다**.

`syncPosition` 은 오늘도 보유가 없을 때 state 를 수정하지 않는다(else 분기 자체가 없다,
`PositionManager.kt:63-70`). 그 불변식을 유지한다 — plan-review P1-3 지적대로, 런타임 `unsynced` 재시도
경로(`TradingEngine.kt:235-237`)는 `position=true` 인 채로도 돌기 때문에 여기서 position 을 내리면
`markSold()` 를 우회한 청산이 되어 **TradeRecord·알림 없이** 포지션이 사라진다. 포지션 정리는 지금처럼
`sell()` 의 phantom 경로(`:490-493`)가 담당한다. 따라서 `free==0 && locked==0` 4분면도 **오늘과 동일하게
무변경**이다.

기동 시에는 `position` 이 durable 이 아니라 항상 `false` 로 시작하므로(`TradingStateService.kt:70`)
결과적으로 "보유 없음 + 매수 차단"이 된다 — 사용자가 확정한 방침 그대로다.

**WARN 1회.** `unsynced` 가 이미 true 면 로그를 남기지 않는다(전이 시점에만 발화). 매 tick 재시도라
무조건 찍으면 10s 간격 × 티커당 8,640줄/일이 된다. 별도 타임스탬프 상태 없이 `unsynced` 자체를
dedup 키로 쓴다. WARN 은 `DiscordErrorLogAppender` 대상이 아니라 알림 스팸은 아니다.

**받아들이는 비용**: 이 상태에서 `position=false` 라 `TradingEngine.kt:277`
(`if (state.position || state.boughtToday) return`)의 조기 종료가 사라져, 매 tick 캔들 로딩까지 간 뒤
`buy()` 최종 게이트에서 막힌다(store 가 비면 `getDayCandles` REST 1회). 차단 결과는 같고 비용만 다르다.
티커당 0.1 req/s 라 쿼터에는 무의미하며, lock 이 풀리면 해소된다. 이를 피하려고 position 을 세우면
그게 곧 유령 포지션이라 본말전도.

## D4'. 헬퍼를 둔다 (구 D4 반전)

구 D4 는 "`balanceDouble()` 한 줄을 감싸는 건 과한 추상화"라 했으나, D1' 의 규칙은 한 줄이 아니다
(`free + min(locked, max(0, remainder - free))`). 3개 호출부가 각자 구현하면 반드시 어긋난다.
`PositionManager` 안에 private 헬퍼 하나를 두고 KDoc 에 정의를 싣는다. `TradingState.holdVolume` KDoc 은
그 헬퍼를 가리킨다.

## D5. PR #50 회귀 테스트를 두 갈래로 나눈다 (약함 아님, 유지)

`PositionManagerExtendedTest:895 syncPosition counts coins locked in an open order as held` 는
`pendingSellUuid` 를 세우지 않은 채 locked 만으로 보유 판정을 단언한다. 그 테스트 주석이 서술하는
시나리오는 "매도 주문이 떠 있는 채로 재시작" = durable 에 `pendingSellUuid`·`pendingSellVolume` 이
**남아 있는** 경우다. 즉 uuid 미설정은 **setup 의 불완전함**이지 의도가 아니다. 원 시나리오는 uuid 를
세워 그대로 보존하고, uuid 없는 경우는 D3' 동작을 단언하는 별도 테스트로 분리한다.

## D6. rollback (신설 — plan-review 지적)

- **코드 롤백은 깨끗하다**: migration 없음. `position`/`avgBuyPrice`/`holdVolume` 은 durable 스키마에
  아예 없고(`V14__...sql:2`, `TradingStateService.kt:70`) 재기동 시 `syncPosition` 이 거래소에서
  재구성한다. `git revert <sha>` + 재배포로 끝.
- **데이터 롤백은 불가능하다**: 오판 `markSold` 로 포지션이 사라지면 되돌릴 durable 상태가 없고 감사에는
  실제와 다른 SELL 이 남는다. D1' 의 상한 규칙이 바로 이 실패를 막는 장치다(양극단 중 위험한 쪽 회피).
- **실패 감지 신호**: (a) `/api/bot/status` 의 `position=false` 인데 Upbit 잔고 > 0,
  (b) "Sell deferred" WARN 이 사라졌는데 거래소에 코인이 남아 있음,
  (c) `SELL ... partial via reconcile` 의 `remaining` 이 거래소 실잔고와 불일치.
- **감지 후 절차**: 거래소 잔고 ↔ audit 대조 → 필요 시 재기동으로 `syncPosition` 강제 → 불일치 지속이면 revert.
- **배포 시점 in-flight**: `pendingSellUuid` 가 살아 있는 채 새 코드가 떠도 재시작과 동일 경로다
  (position/holdVolume 이 비영속이라 구 코드가 만든 메모리 상태를 물려받지 않는다). 별도 전제조건 없음.
  `UserTradingManager.kt:299-301` 의 reload 도 `loadStates` → `start()` → `syncPosition` 으로 동일.

## D7. plan-review 제안 중 채택하지 않은 것

1. **"applySellFillOutcome 을 free 로 바꾸는 건 보류하고 Upbit 반영 순서를 researcher 로 확인"** — 불채택.
   D1' 은 `free` 로 바꾸는 안이 아니다. 상한 규칙은 반영이 빠르든 늦든 같은 답을 내므로 그 외부 사실이
   결과를 바꾸지 않는다. (확인해도 공식 보장이 없을 가능성이 높아, 있어도 의존하지 않는 설계가 낫다.)
2. **"1단계는 WARN 관측만, 전환은 후속 PR"** — 불채택. 유령 포지션은 이미 관측된 결함(#56)이고,
   상한 규칙은 정보가 없을 때(`pendingSellVolume == null`) 현행 동작으로 수렴하므로 단계 배포의
   안전 이득이 작다. 대신 D6 의 감지 신호를 남긴다.
3. **`recoverSellFromBalance` 도 상한 규칙 적용** — 불채택(범위 밖). 이 경로는 `total <= 0` 으로
   "다 나갔나"만 판정하고 `holdVolume` 을 쓰지 않는다. `total` 유지가 보수적(잔고가 남아 보이면 pending
   유지)이라 오작동이 아니다. 다만 구 plan 의 "D1 정렬만으로 과대계상이 **해소**된다"는 과한 단정이었다 →
   "완화된다"로 정정하고 남는 케이스는 `# Deferred` 에.
4. **`/api/bot/status` 계약 테스트 추가** — `# Deferred`. 응답 shape 불변이고 소비자가 없다(실증: SPA
   `screens.jsx:33,128` 은 `botStatus()` 를 부르되 `positions[]` 를 읽지 않음, README·docs·wiki 언급 0건).
   키 존재만 확인하는 테스트는 의미가 얕다.

# Key Files

- `bot/src/main/kotlin/com/trading/bot/engine/PositionManager.kt` — 헬퍼 신설, `syncPosition`(L57~),
  `applySellFillOutcome`(L563~583)
- `bot/src/main/kotlin/com/trading/bot/domain/TradingState.kt` — `holdVolume` KDoc(정의 포인터),
  `unsynced` KDoc(D3' 로 의미 확장), `markBought` averaging KDoc(D1' 밖임을 명시)
- `bot/src/test/kotlin/com/trading/bot/engine/PositionManagerExtendedTest.kt` — L895 분리 + 신규
- `wiki/pages/entity/upbit-api.md` — L36 "free + locked 합을 봐야 한다" 가 D1' 과 정면 충돌 → 교정
- `wiki/pages/concept/trading-engine-loop.md` — `sources` 에 PositionManager.kt 선언, L26 `unsynced` 서술
- `bot/src/main/kotlin/com/trading/bot/engine/UserTradingManager.kt:244` — `hold_volume`(코드 변경 없음)

# Blockers

없음.

# Acceptance

`JAVA_HOME=/Users/jongyoonlee/Library/Java/JavaVirtualMachines/jbr-21.0.9/Contents/Home ./gradlew :bot:test`
(JDK25 기본이라 prefix 필수). 각 항목에 **오늘 코드에서 실패하는 이유**를 적어 tautology 를 배제한다.

| # | 시나리오 | 기대 | 오늘 실패하는 이유 |
|---|---|---|---|
| 1 | `syncPosition`, uuid+`pendingSellVolume=0.001`, free=0, locked=0.001 | `position=true`, `holdVolume=0.001` | 통과해야 함 (PR #50 회귀 보존 — Red 아님) |
| 2 | `syncPosition`, uuid=null, free=0, locked=0.001, **seed `position=false`** | `holdVolume=0.0`, `unsynced=true`, position 불변(false) | 오늘 `position=true`·`holdVolume=0.001`·`unsynced=false` |
| 3 | `syncPosition`, uuid=null, free=0.0004, locked=0.001, **seed `unsynced=true`** | `holdVolume=0.0004`, `unsynced=false` | 오늘 `holdVolume=0.0014` |
| 4 | `syncPosition`, uuid+`pendingSellVolume=0.001`, free=0, locked=0.003 (0.002 는 타 사유) | `holdVolume=0.001` (상한) | 오늘 `holdVolume=0.003` |
| 5 | `applySellFillOutcome` 부분체결, executed=0.0006, `pendingSellVolume=0.001`, free=0.0004, locked=0.002 | `holdVolume=0.0004`, `position=true`, pending 해소 | 오늘 `holdVolume=0.0024` |
| 6 | `applySellFillOutcome` 전량, executed=0.001, `pendingSellVolume=0.001`, free=0, locked=0.002 (타 사유), **seed `position=true`,`holdVolume=0.001`** | `markSold` — `position=false`, `holdVolume=0.0`, `pendingSellUuid=null`, SELL 기록 생성 | 오늘 `position=true`·`holdVolume=0.002` (유령) |
| 7 | `applySellFillOutcome` 부분체결, free=0(미반영), locked=0.0004(우리 것), executed=0.0006, `pendingSellVolume=0.001` | `holdVolume=0.0004`, `position=true` — **잔여분 미유실** | 통과해야 함 (D1' 이 지키는 반대 방향 — 순진한 `free` 안이었다면 깨짐) |
| 8 | `applySellFillOutcome` cancel+0, **seed `position=false`**, `pendingSellVolume=0.001`, free=0.001, locked=0.002 (타 사유) | `holdVolume=0.001`, pending 해소 | 오늘 `holdVolume=0.003` (P1-1 구멍, 기존 테스트 미커버) |
| 9 | 전체 회귀 | `./gradlew :bot:test` 통과 | — |
| 10 | 타입체크 | `./gradlew compileKotlin` 통과 | — |
| 11 | 문서 동기화 | `wiki/pages/entity/upbit-api.md`(L36 포함)·`wiki/pages/concept/trading-engine-loop.md` 갱신 + `wiki/index.md` 정합. `check_links.py`·`wiki/verify.sh`·`wiki/smoke.sh` 통과 | — |

# Review Disposition

plan-review(2026-08-22, Claude subagent + codex medium) 처분:

| finding | 처분 | 근거 |
|---|---|---|
| P1-1 D2 자기모순 (cancel+0 이 죽은 uuid 로 syncPosition) | **fix** | 사실 확인(`:596-597`). D1' 상한 규칙으로 소멸 — 파라미터 추가 불필요(D2') |
| P1-2 `free` 전환의 잔여분 유실 위험 | **fix (다른 방식)** | 지적 타당. 단 제안 3안 대신 D1' 상한 규칙 채택 — 미검증 거래소 가정 자체를 제거(D7-1,2) |
| P1-3 `syncPosition` else 분기 미정의 / 4분면 | **fix** | 타당. D3' 에서 "position 을 내리지 않는다"로 확정, 4분면 모두 오늘과 동일 |
| P1-4 Acceptance tautology | **부분 fix** | #3·#6 은 타당(seed 추가). #2 는 오탐 — 오늘 코드가 `position=true` 를 세우므로 Red 가 실제로 난다. 그래도 seed 를 명시해 의도를 못박음 |
| P1-5 D1 정의문 자기모순 | **fix** | 타당. "청산 가능" → "봇이 자기 포지션으로 통제하는 수량"으로 재정의 |
| P2 D3 매 tick 캔들 로딩 비용 | **wontfix (명시 수용)** | D3' 에 비용과 이유 기재. 피하려면 유령 포지션을 세워야 해 본말전도 |
| P2 D3 peak 추적 중단 | **wontfix (명시 수용)** | `peakPrice` 는 durable 이라 유실 아님. lock 구간 중 신고점만 놓침 |
| P2 WARN 스팸 | **fix** | `unsynced` 전이 시점에만 발화(D3') |
| P2 `unsynced` KDoc 거짓화 | **fix** | KDoc 갱신 |
| P2 `markBought` averaging 이 5번째 writer | **fix (문서만)** | `replace=true` 고정이라 프로덕션 미도달(✅ `:290`). KDoc 한 줄로 재배선 함정 방지 |
| P2 wiki 갱신 대상 누락 | **fix** | Acceptance 11 에 `upbit-api.md:36`·`trading-engine-loop.md` 추가 |
| P2 `recoverSellFromBalance` 미완전 해소 | **부분 fix** | 톤 정정(D7-3) + Deferred 기록 |
| `/api/bot/status` 계약 테스트 | **defer** | 소비자 부재 실증, shape 불변 (D7-4) |
| codex: entry metadata 결합 미검증 | **fix** | D3' 가 position 을 안 내리므로 `markBought` 연장 판정에 영향 없음 — Acceptance 2 에서 position 불변으로 확인 |

code-review(2026-08-22, Claude subagent + codex high) 처분:

| finding | 처분 | 근거 |
|---|---|---|
| **Major** `ourSellRemainder` 가 미체결 잔량이 아닌 주문 원수량 → 상한이 느슨 | **fix (문서·명명)** | 사실 확인 ✅ — `pendingSellVolume` 쓰기는 `sell()` 한 곳뿐이라 부분체결로 줄지 않는다. 다만 `pendingSellVolume` 은 감사 기록 fallback 도 겸해 부분체결마다 깎으면 SELL 수량이 망가진다. 상한을 조이려면 `wait` 분기에서 `getAccounts()` 를 매 tick 더 불러야 하는데, 노출이 `wait`+부분체결+타사유lock 동시 성립 구간에 한정되고 terminal 시 자체 교정된다. → `ourSellLockCeiling` 로 rename + "느슨한 상한" 명시 + characterization 테스트로 고정 + `# Deferred` |
| WARN dedup 을 `unsynced` 로 해 원인이 묻힘 | **fix** | 타당 ✅ 조회 실패로 먼저 켜지면 lock 원인이 영영 안 남는다. 비영속 `unattributableLockWarned` 분리 |
| 귀속 불명 가드가 사실상 기동 전용 | **fix (범위 한정 명시)** | 타당 ✅ `syncPosition` 호출부가 3곳뿐인 건 기존 구조. 확대는 스코프 밖 → 주석에 판정 시점 명시 + `# Deferred` |
| `unsynced` 가 `/api/bot/status` 에 없음 | **fix** | 타당 ✅ D3' 가 만든 새 차단 상태의 유일한 관측 수단. 1줄 추가(additive, shape 불변). README 는 응답 필드를 문서화하지 않아 drift 없음 |
| `holdVolume` KDoc 이 `completeBuy` 재구현과 불일치 | **fix** | 타당 ✅ `completeBuy` 가 `heldVolume(account, 0.0)` 를 쓰도록 통일(수치 동치) |
| `buy()`·`TradingEngine` 주석 drift | **fix** | 타당 ✅ 같은 변경에서 3곳 중 2곳만 갱신했었다 |
| `sell()` M4 의 raw `locked>0` 와 일관성 결함 | **defer** | 기존 동작이라 회귀 아님. 주석이 이미 "M3 별도 PR" 로 유예 선언. 청산 판정 변경은 스코프 밖 |
| 테스트 공백 4건 | **fix** | 타당 ✅ position 불변 / `+∞` 레거시 / dedup 플래그 / 부분체결 상한 — 5개 추가 |
| PLAUSIBLE `executed > pendingSellVolume` clamp | **wontfix** | 리뷰어 자체 반증대로 `pendingSellVolume`(=`sellable`)과 주문 `volume` 이 같은 문자열에서 나와 정상 응답으로는 도달 불가. 잔여도 dust 규모이고, 대안(다 팔린 걸 보유로 유지)이 곧 제거 대상인 유령이다 |
| Nit: plan 디렉토리 untracked | **fix** | 이 repo 는 `plans/` 를 추적한다 — 커밋에 포함 |
| Nit: `recoverSellFromBalance` KDoc 혼동 | **fix** | "이 경로만 상한 규칙 밖" 한 줄 추가 |

# Deferred

- `recoverSellFromBalance` 의 `pendingSellVolume ?: holdVolume` fallback 은 이 경로가 `totalBalance()` 를
  유지하는 한 완전히는 정렬되지 않는다(심각도 낮음 — `pendingSellVolume` 이 null 인 레거시 row 에서만
  발현, 그때도 감사 수량이 과대계상될 뿐 주문에는 영향 없음). 파일: `PositionManager.kt:613`.
- `/api/bot/status` 응답 계약 테스트 부재 (소비자 없음 — D7-4).
- **`wait` + 부분체결 구간의 상한 느슨함** — `ourSellLockCeiling` 이 주문 원수량이라 그 구간에서
  `holdVolume` 이 체결분만큼 과대계상될 수 있다(우리 몫 0.4 + 타 사유 0.2 → 0.6). 주문이 terminal 이 되면
  `applySellFillOutcome` 이 체결분을 빼 교정된다. 영향은 `/api/bot/status` 표시값뿐(주문 수량은 `free` 기반).
  조이려면 `wait` 분기에서 매 tick 잔고를 더 조회해야 해 비용이 이득을 넘는다.
  characterization 테스트 `syncPosition caps locked at the whole order volume while the order is still open`
  이 이 동작을 고정한다. 파일: `PositionManager.kt` `ourSellLockCeiling`.
- **귀속 불명 lock 가드는 기동·재동기화 시점 전용** — 정상 동기화된 뒤 런타임에 새로 생긴 lock 은
  `syncPosition` 이 호출되지 않아 감지되지 않는다(`syncPosition` 호출부가 3곳뿐인 기존 구조).
  `buy()` 직전 판정으로 넓히려면 매수 경로에 잔고 조회가 추가돼야 한다.
- **`sell()` M4 의 raw `locked > 0` phantom 판정** — 상한 규칙과 답이 다르다(기존 동작, 회귀 아님).
  `PositionManager.kt` 의 "locked 무한상주 시 미체결주문 취소 후 재매도는 M3 별도 PR" 유예와 같은 건이다.

# Workflow Findings
