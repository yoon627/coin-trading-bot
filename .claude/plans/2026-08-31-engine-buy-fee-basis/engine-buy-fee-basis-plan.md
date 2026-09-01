---
title: engine-buy-fee-basis — 엔진 매수 수수료를 추정 대신 거래소 실측값으로 (#133)
status: in_progress
started: 2026-08-31
updated: 2026-09-01
---

# Goal

엔진 매수의 `trade_executions.fee` 가 **포지션 전체 원가**에 요율을 곱해 부풀려지는 것을 고친다.
Upbit 주문 응답의 `paid_fee` 를 파싱해 **추정을 실측으로 대체**하고, 실측할 수 없는 경로는
틀린 추정 대신 **0(미기록)** 으로 남긴다.

# Progress

- 2026-08-31 Explore 완료. #132 작업 중 이슈에 남긴 확정 내용을 코드로 재확인하고, 새 사실 3가지를 찾았다.
- 2026-08-31 plan 리뷰(codex 단독 — Claude plan-reviewer 는 stall) → Critical 2 반영해 설계 교체
  (`Double?` → sealed `FeeBasis`, 파싱 실패 시 추정 금지).
- 2026-08-31 구현 → mutation 으로 Red 입증(엔진 배선 되돌리면 4개 실패) → Green.
- 2026-09-01 code-review + codex → REQUEST CHANGES(Major 2·Minor 5) **전부 반영**.
  가드 3종을 mutation 으로 재검증(전부 CAUGHT). simplify 로 도달 불가 분기·정책 중복 제거.
- 2026-09-01 codex 최종 diff 재검토 → Critical 0·Major 0·Minor 2(1건 반영). 커밋 `04dfd60`·`7728cf8`.
- 2026-09-01 **PR #155 오픈, CI `test` 통과**(1m58s). 771 테스트·실패 0.

## 결함 (재확인)

`TradePnl.estimatedFee` KDoc 이 기준을 못박는다 — *"이쪽은 **체결 대금**에 … 비율을 곱한다"*.
그런데 엔진 경로의 `record.totalAmount` 는 체결 대금이 아니다:

```kotlin
// PositionManager.completeBuy
val volume = account?.let { heldVolume(it, 0.0) }?.takeIf { it > 0.0 } ?: executedVolume  // 거래소 실잔고
val fillPrice = account?.avgBuyPriceDouble()?.takeIf { it > 0.0 } ?: currentPrice          // 거래소 평단
val totalAmount = fillPrice * volume                                                       // = 포지션 전체 원가
```

기존 보유분이 있으면 그만큼 수수료가 부풀려진다.

## 새 사실 1 — 경로마다 주문 응답 시점이 다르다 (범위를 가른다)

| 경로 | 주문 응답 | `paid_fee` 쓸 수 있나 |
|---|---|---|
| 엔진 `applyFillOutcome` | `getOrder(uuid)` — **체결 후 조회** | ✅ 실제 값 |
| 수동 `executeBuy` | `placeOrder` 즉시 응답, **재조회 없음**(`recordOrder` 는 buildRecord 만 감싼다) | ❌ 체결 전이라 0/부분 |
| 엔진 `recoverFromBalance` | 없음 (getOrder 장애 복구 경로) | ❌ 알 수 없음 |

→ **`paid_fee` 로 고칠 수 있는 건 엔진 정상 경로뿐이다.** 수동 경로는 `totalAmount` 가 주문 금액이라
추정 기준이 이미 맞으므로 건드릴 이유가 없다.

## 새 사실 2 — 마이그레이션이 필요 없다

`V11` 의 `fee DOUBLE PRECISION DEFAULT 0` 이고, **V21 이 이미 `0 = 미기록` 규약을 세웠다**:

> `fee` 는 소급하지 않는다. 이 마이그레이션 이전 행은 **0(미기록)** 으로 남는다.

그리고 같은 작업의 교훈이 방향을 정해준다 — *"0(미기록)은 '없다'고 읽히지만 **틀린 추정치는 맞는 값과
구분되지 않는다**"*. basis 를 모르는 복구 경로는 **틀린 추정보다 0 이 낫다.**

## 새 사실 3 — 2026-08-22 결정의 *근거*가 이 버그다

`TradeRecord` KDoc:

> 파생값은 sink 가 유도하는 것이 기본이다 — `fee` 는 **`totalAmount` 만 있으면 나오므로** 여기 없고
> `TradeExecutionService.saveAudit` 이 계산한다.

**그 전제가 엔진 경로에서 거짓이다.** 결정을 뒤집는 게 아니라, `paid_fee` 를 파싱하면 fee 가 파생값이
아니라 **실측값**이 되어 전제 자체가 바뀐다. 같은 KDoc 이 이미 손익 두 필드에 같은 성격의 예외를
두고 있다(*"매도 시점의 평단은 청산과 함께 사라져서 sink 가 되짚을 수 없다"*).

# Next

**PR #155 오픈·CI 통과 — 머지 대기.** https://github.com/yoon627/coin-trading-bot/pull/155

worktree `engine-buy-fee-basis` clean·전부 push 완료·**미머지**라 유지한다. 커밋 2개:
`04dfd60`(본 구현) · `7728cf8`(sink 최종 방어).

다음 세션 즉시 액션:

1. **PR #155 머지 판단.** 머지 = 운영 배포(컨테이너 재생성 = 트레이딩 엔진 재시작)다. 머지 후
   `App healthy!` 로그로 배포 확인 — **job 결론이 success 라도 stale 가드로 스킵됐을 수 있다.**
2. 머지되면 worktree·로컬·원격 브랜치 정리 + 이 plan `status: done`.
3. 아래 `# Workflow Findings` 의 pre-push 게이트 건을 이슈로 올릴지 판단(사용자 미결).

# Decisions

1. **2026-08-22 결정을 *수정*한다** — "뒤집는 게 아니다"라는 표현은 쓰지 않는다 (codex C2 반영).

   당초 나는 "전제가 바뀐 것이지 결정을 뒤집는 게 아니다"라고 썼다. codex 가 정확히 지적했다 —
   그건 합리화에 가깝다. **`fee` 를 `TradeRecord` 에 넣지 않기로 한 결정은 명시적으로 뒤집힌다.**
   정직한 서술은 "전제가 바뀌었으므로 결정의 적용 범위를 수정한다"이다.

   근거는 유효하다: 그 결정의 명시된 이유가 "fee 는 `totalAmount` 로 유도된다"인데 엔진 경로에서
   거짓이고, `paid_fee` 파싱 후에는 fee 가 **파생값이 아니라 외부 실측 사실**이 된다.
   `commitFill` 시그니처 변경 안이 더 정직한 설계인 것도 아니다(codex) — 그쪽도 fee 의 출처를
   전달해야 하고, `TradeRecord` 가 감사·알림 양쪽에 쓰이는 "체결 사실"이라 실측 fee 를 싣는 것이
   자연스럽다. **"접촉면이 넓어서 피한다"는 이유만으로는 정당화하지 않는다.**

2. **`Double?` sentinel 대신 sealed 타입으로 의도를 드러낸다** (codex M1 반영).

   ```kotlin
   sealed interface FeeBasis {
       /** sink 가 `totalAmount` 에 요율을 곱해 추정한다. 그 값이 실제 체결 대금인 경로에서만 쓴다. */
       data object Estimate : FeeBasis
       /** 거래소가 청구한 실제 수수료(`paid_fee`). */
       data class Measured(val amount: Double) : FeeBasis
       /** basis 를 알 수 없다 — 추정하면 틀린 값이 되므로 0(미기록)으로 남긴다. */
       data object Unrecorded : FeeBasis
   }
   ```

   당초안(`Double?` 에 `null`/실수/`0.0` 세 의미)은 sentinel 을 주석에만 두어 타입 수준에서 오독을
   허용한다. 특히 `0.0` 은 "미기록"과 "실제 수수료 0"을 구분하지 못하고, "실제 0 은 없다"는 내 보장은
   공식 계약으로 입증된 적이 없다.

   | 값 | 어느 경로 | 저장되는 `fee` |
   |---|---|---|
   | `Estimate` | 수동 매수 (`totalAmount` = 주문 금액이라 기준이 맞다) | `totalAmount × 요율 / 2` |
   | `Measured` | 엔진 정상 (`getOrder` 의 `paid_fee`) | 그 값 |
   | `Unrecorded` | 엔진 복구 · `paid_fee` 부재·파싱 실패 | `0.0` |

   **기본값을 두지 않는다.** `TradeRecord` KDoc 이 같은 이유로 `strategy`·손익 두 필드에 기본값을
   두지 않는다 — *"인자를 빠뜨려도 컴파일이 통과하는 바람에 매도 경로가 전략을 통째로 유실했던 전례가
   있다. 생성부가 매번 의도를 밝히게 한다."* fee 도 같은 형태다. 비용은 구성 지점 17곳(main 5·테스트
   12) 수정이며, 전부 기계적이고 컴파일러가 강제한다.

3. **`paid_fee` 를 못 얻으면 `Unrecorded` 다 — 추정으로 떨어지지 않는다** (codex C1 반영).

   당초 Acceptance 는 파싱 실패 시 "기존 동작으로 떨어진다"였는데, **그 기존 동작이 바로 고치려는
   버그**(`estimatedFee(스냅샷)`)다. 엔진 경로에서 `Estimate` 로 폴백하면 과대계상이 재발한다.

4. **복구 경로를 `Unrecorded` 로 두는 근거를 정확히 쓴다** (codex M2 반영).

   `recoverFromBalance` 의 KDoc 전제("pending 생존 중 position=false 이므로 해당 통화 잔고는 이 주문
   체결분이다")가 성립하면 추정이 맞지 않느냐는 반론이 가능하다. 답: **그 전제는 수량 귀속의 근거이지
   수수료 복원의 근거가 아니다.** `avgBuyPrice × volume` 은 포지션 스냅샷이고, 그 주문의 실제 체결
   대금·maker/taker 조건·거래별 수수료를 보장하지 않는다. 주문 응답도 pending state 도 원 주문 금액을
   갖고 있지 않다. 요율로 추정할 수는 있어도 "맞다"고 할 수 없다.

5. **`totalAmount` 의 의미는 건드리지 않는다.** 엔진 기록이 스냅샷인 것은 #20(재시작 시 이중계상 방지)의
   의도된 설계다. 같은 스냅샷을 읽는 다른 소비자(집계 SUM·SPA·Discord)의 문제는 **이슈 #146** 이
   따로 들고 있다. 이번엔 수수료 계산만 고친다.

4. **수동 경로는 건드리지 않는다.** `totalAmount` = 주문 금액이라 추정 기준이 맞다. 정확도를 더 올리려면
   `getOrder` 재조회가 필요한데, 주문 접수 직후라 체결이 안 잡힐 수 있고 API 호출이 하나 늘어난다.
   이득 대비 비용이 맞지 않아 범위 밖.

# Key Files

- `bot/.../domain/Order.kt` — `paid_fee` 파싱 추가
- `bot/.../domain/TradeRecord.kt` — `fee` 필드 + KDoc(왜 예외인지)
- `bot/.../engine/PositionManager.kt` — `applyFillOutcome`(실측 전달) · `recoverFromBalance`(미기록)
- `bot/.../engine/TradeExecutionService.kt` — `saveAudit`(실측 우선, 없으면 추정)
- `bot/.../domain/TradePnl.kt` — `estimatedFee` KDoc(이제 언제 쓰이는지)
- `wiki/pages/concept/trade-record-volume-semantics.md` — 수수료 기준 절 추가

# Blockers

없음.

# Acceptance

codex M3 의 fixture 케이스를 그대로 채택한다.

- [ ] **엔진 정상 경로가 실측을 쓴다** — `state=done`·`executed_volume>0`·`paid_fee="12.3"` → `fee=12.3`.
      **기존 보유분이 큰 상태에서도** `totalAmount` 가 fee 계산에 쓰이지 않는다(이게 핵심 회귀 가드).
- [ ] **부분 체결 취소도 실측을 쓴다** — `state=cancel`·`executed_volume>0`·`paid_fee="12.3"` → `fee=12.3`.
      `applyFillOutcome` 이 `executed>0` 이면 cancel 도 매수 확정으로 처리하므로 같은 경로다.
- [ ] **미체결은 기록 자체가 없다** — `state=cancel`·`executed_volume=0` → audit 없음(기존 동작).
- [ ] **`paid_fee` 부재·파싱 실패는 `Unrecorded`** — `null` 또는 `"invalid"` → `fee=0.0`, 예외 없음.
      **추정으로 떨어지지 않는다**(떨어지면 고치려는 버그가 재발한다 — codex C1).
- [ ] **엔진 복구 경로는 0(미기록)** — `recoverFromBalance` 로 확정된 체결은 `fee=0`.
- [ ] **수동 경로 무회귀** — 수동 매수의 `fee` 는 여전히 `주문금액 × 요율 / 2`.
- [ ] **문서 동기화** — `TradeRecord` KDoc 에 2026-08-22 결정을 **수정**한다는 사실과 근거 명시, wiki 갱신.
- [ ] **검증 통과** — `./gradlew build` + test (JDK21).

# Review Disposition

## plan 리뷰 (2026-08-31)

**Claude `plan-reviewer` subagent 는 10분간 진전 없이 중단(stalled)됐다.** 같은 방식으로 재시도하지 않고
(동일 실패 반복 방지) codex 단독 + 메인 직접 확인으로 대체했다. §9 의 "Codex 미가용이면 생략 사유 기록"의
역방향 케이스라 여기 남긴다.

### 메인이 코드로 직접 확인한 것

| 질문 | 결과 |
|---|---|
| `saveAudit` 진입점 전수 | **정확히 2개** — `commitFill`(엔진, 원자화) · `saveAndNotify`(수동). 네 번째 경로 없음 |
| 멱등 재시도 상호작용 | **무해.** `saveAudit` 이 `exchangeOrderId` 로 통째로 skip → 처음 기록된 값이 남는다 |
| `0 = 미기록` 이 실측 0 과 충돌하나 | 수수료 면제로 실제 0 이어도 **저장값이 똑같이 0** 이고 이 컬럼은 코드에 읽는 곳이 없다. 다만 codex M1 이 타입 수준 오독을 지적해 sealed 타입으로 전환(Decision 2) |

### codex (medium) 처분

| # | 지적 | 처분 | 근거 |
|---|---|---|---|
| **C1** | `paid_fee` 실패 시 "기존 동작"으로 폴백하면 **버그가 재발**한다 | **fix** | 내 Acceptance 의 실제 결함. 엔진 경로는 `Unrecorded`(0)로 간다 → Decision 3 |
| **C2** | "결정을 뒤집는 게 아니다"는 합리화에 가깝다 | **fix** | 타당. "적용 범위를 수정한다"로 정직하게 고쳐 씀 → Decision 1 |
| **M1** | `Double?` 하나에 세 의미 → 타입 수준 오독 허용 | **fix** | sealed `FeeBasis` 채택. 이 파일 KDoc 의 "생성부가 의도를 밝히게 한다" 철학과 일치 → Decision 2 |
| **M2** | 복구 경로 `0` 은 옳으나 근거를 정확히 쓸 것 | **fix** | "잔고 전제는 *수량 귀속*의 근거이지 수수료 복원의 근거가 아니다" → Decision 4 |
| **M3** | `paid_fee` 방향은 정당하나 fixture 테스트 부족 | **fix** | 제시한 5케이스를 Acceptance 로 채택 |
| m1 | rollback 전략 부재 | **fix** | 아래 추가 |

## code-review + codex (2026-08-31) — REQUEST CHANGES → 전부 반영

| # | 지적 | 처분 | 조치 |
|---|---|---|---|
| **M1** | `recoverFromBalance → Unrecorded` 테스트가 없어 **Acceptance 미충족** | **fix** | 기존 복구 테스트에 `assertEquals(FeeBasis.Unrecorded, result!!.fee)` 추가. 없었다면 후속 작업자가 그 줄을 `Estimate` 로 바꿔도 770건이 전부 통과했다. |
| **M2** | `paid_fee` JSON 매핑이 한 줄도 실행되지 않음 | **fix** | `UpbitClientTest` 에 실제 응답 형태 fixture 2종 추가. 어노테이션이 깨지면 **모든 엔진 매수 fee 가 조용히 0** 이 되는데 경고도 소비자도 없다. |
| **m2** | `toDoubleOrNull()` 이 `"NaN"`·`"Infinity"`·음수를 `Measured` 로 통과 | **fix** | **실측 확인함**(`Double.parseDouble("NaN") → NaN`). PostgreSQL `double precision` 이 `NaN` 을 저장하면 이후 `SUM(fee)` 이 **영구히** `NaN` — 0 이 섞이는 것과 달리 복구 불가. `isFinite() && >= 0.0` 가드 + 테스트. |
| m1 | `TradePnl` KDoc 이 "매도는 실측 불가"라 서술하나 엔진 매도는 `Order` 를 쥐고 버린다 | **fix(문서)** | 정직하게 정정 — "얻을 수 없어서가 아니라 범위에서 뺀 것". |
| m3 | wiki 본문이 frontmatter 의 유보와 자기모순 | **fix(문서)** | 본문을 유보 쪽으로 하향(`cancel`+부분체결 fixture 미확보). |
| m4 | "모든 매도"가 `executeSellVolume` 부분 체결에 성립 안 함 | **fix(문서)** | #105 오차 공유를 명시. |
| n1·n2 | 도달 불가 엘비스 + 정책 중복 | **fix(simplify)** | `filled != null &&` 로 smart-cast → 도달 불가 분기·주석 2줄·중복이 한꺼번에 사라짐. |
| n4 | "왕복분" 주석이 혼합 기준에서 부정확 | **fix(문서)** | 범위 한정. |
| m5 | `Unrecorded→0` 의 **repo 밖** 소비자 확인 불가 | **defer** | 리뷰어가 repo 내 소비자 전수 확인 → **0건**(SQL·SPA·Grafana·Discord 모두). 0 은 이미 흔하다(V21 이전 행 전부, KIS 경로 전부). 컬럼 추가는 과투자 → `# Deferred`. |
| n3 | 매도 `Estimate` 테스트가 tautology | **wontfix** | 상수를 그대로 단언하나 회귀 핀으로서의 값은 있다. 매도 실측화(후속)를 하면 다시 쓴다. |
| — | `TradeRecord` 필수 인자 = compat 파괴 | **false-positive** | 리뷰어가 반증 — `common` 참조 0건, 발행 아티팩트 없음, 기본값 금지는 의도된 설계. |
| — | `recoverSellFromBalance` 도 `Unrecorded` 여야 | **false-positive** | 원칙은 "주문 응답 유무"가 아니라 "`totalAmount` 가 그 체결의 대금인가". 매도 복구는 전량 소진 확인 후 기록해 대금 성격이 맞다. |

## codex 최종 diff 재검토 (2026-09-01) — Critical 0 · Major 0 · Minor 2

**pre-push codex 게이트가 이 push 에서 실행되지 않았다**(로그 없음·출력 없음). 훅 `:46-48` 이 stdin 에서
ref 를 못 읽으면 조용히 `exit 0` 하는데, 같은 백그라운드 방식으로 돌린 이전 push 는 정상 동작해
원인은 ⚠️미확정이다. 실질 공백은 따로 있었다 — code-review 의 codex 는 **반영 이전 diff** 를 봤고
그 뒤 추가한 NaN 가드·fixture·simplify 는 검토를 안 거쳤다. 그래서 최종 diff 로 직접 돌렸다.

| # | 지적 | 처분 |
|---|---|---|
| m1 | `FeeBasis.Measured` 가 public 생성자라 `Measured(NaN)` 을 직접 만들 수 있어 **타입이 주장하는 불변식을 강제 못 한다** | **fix** — DB 에 닿는 마지막 지점(`saveAudit`)에 최종 방어 + 테스트. 파싱 가드와 중복이지만, 되돌릴 수 없는 결과(SUM 영구 NaN)를 막는 경계 방어라 정당하다 |
| m2 | `getOrder → … → DB fee` 전 구간을 한 테스트가 잇지 않는다 | **wontfix** — 양쪽 절반이 각각 덮인다(`result.fee` 단언 + `saveAudit` 매핑 테스트). 이음매는 직접 호출이라 세 번째 테스트는 중복이다. codex 도 "구현 결함은 아님"으로 분류 |

codex 가 확인해 준 것: smart-cast 변경은 **동작 보존**(`filled == null && executed > 0.0` 불가) ·
`volume: null` 역직렬화 정상 · `TradeRecord` 생성부 누락 없음 · 이번 변경과 충돌하는 주석 drift 없음 ·
`Measured(0.0)` 을 유효 실측으로 받는 것이 옳고 **상한은 두지 않는 편이 낫다**(정상 거래 오판 위험).

**mutation 으로 세 가드를 검증했다** — 각각 되돌리면 테스트가 잡는다:

| mutation | 결과 |
|---|---|
| `isFinite`·음수 가드 제거 | CAUGHT |
| `@JsonProperty("paid_fee")` 오타 | CAUGHT |
| 복구 경로를 `Estimate` 로 되돌림 | CAUGHT |

## Rollback

DB 스키마를 바꾸지 않으므로(`fee` 컬럼은 V11 부터 존재, `DEFAULT 0`) **코드 revert 만으로 되돌아간다.**
revert 하면 엔진 매수 fee 가 다시 스냅샷 기준 추정이 된다 — 기록된 과거 행은 그대로 남고 소급하지 않는다
(V21 이 세운 "fee 는 소급하지 않는다" 규약과 동일).

# Deferred

- **엔진 매도도 `paid_fee` 를 쓸 수 있다** — `awaitFill` 로 주문 응답을 쥐고도 버린다
  (`PositionManager` 매도 경로). 얻을 수 없어서가 아니라 매수만큼 급하지 않아 뺐다: 매도의
  `totalAmount` 는 기준(대금)이 옳고 오차가 슬리피지에 비례(≈0.0X%)해, 매수의 배수 오차
  (기존보유/신규주문)와 급이 다르다. 심각도: 하.

- **`0 = 미기록` 과 `0 = 실제 무료` 를 구분할 basis 컬럼** — 리뷰어가 repo 내 `trade_executions.fee`
  소비자를 전수 확인해 **0건**임을 확인했으나(SQL·SPA·Grafana·Discord), 운영 DB 직접 쿼리나 개인
  대시보드 같은 **repo 밖** 소비자는 확인 수단이 없다. 지금은 wiki 가 규약을 적어둔 것이 방어선이다.
  심각도: 하(컬럼 추가는 현 시점 과투자).

- **`paid_fee` 의 부분체결 `cancel` 최종값 여부 미확인** — 공식 문서 필드 정의상 그렇게 읽히나
  실제 응답 fixture 를 확보하지 못했다. 코드와 테스트가 그 전제 위에 있다. wiki `upbit-api` 에
  유보로 명시했다. 반례가 나오면 거기부터 고친다.

- `wiki/verify.sh` 페이지 수 tripwire baseline 실패 — **이미 이슈 #152**.

# Workflow Findings

- **pre-push codex 게이트가 조용히 건너뛰어졌다** (2026-09-01, ⚠️원인 미확정 — 사용자 판단 대기).

  브랜치 첫 push(`04dfd60`)에서 리뷰가 실행되지 않았다. **증거**: 훅 출력 0줄이고
  `.git/codex-pre-push/` 의 최신 로그가 `20260831-230325.jsonl`(그 push 이전)이다.
  두 번째 push(`7728cf8`)를 **foreground** 로 돌리니 정상 실행돼 통과했다
  (`OK: codex found no blocking issues`).

  훅 `:46-48` 이 stdin 에서 ref 를 못 읽으면 **아무 출력 없이 `exit 0`** 한다. 그러나 같은
  백그라운드 방식으로 돌린 이전 push(`trade-snapshot-semantics`)는 정상 동작했으므로
  stdin 가설이 깔끔히 맞지 않는다(백그라운드 3건 중 2건 실패). **확정하지 않는다.**

  **왜 중요한가**: memory 의 *"`git push` 는 run_in_background 로"* 조언이 **안전 게이트를 무력화**할
  수 있다. 성능 최적화가 게이트를 우회하는 형태이고, 실패가 조용해서(출력·로그 둘 다 없음) 알아채기
  어렵다. 이번엔 code-reviewer 가 codex 를 돌렸고 최종 diff 로 한 번 더 돌려서 실질 피해는 없었다.

  **후속 후보**: (a) 이슈로 올려 훅이 stdin 부재 시 경고하고 실패하도록 / (b) memory
  `project_prepush_codex_slow` 를 "foreground 로 돌리되 오래 걸림을 감수" 로 정정.
  둘 다 운영 자산 변경이라 승인 후 별도 작업(§1).

- **Claude `plan-reviewer` subagent 가 10분간 진전 없이 중단(stalled)됐다** (2026-08-31).
  같은 방식으로 재시도하지 않고 codex 단독 + 메인 직접 확인으로 대체했다. 1회 관찰이라 아직 패턴 아님.
