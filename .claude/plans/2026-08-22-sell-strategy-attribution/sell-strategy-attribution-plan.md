---
title: sell-strategy-attribution — 매도 기록의 전략 귀속 복구 + 금액 손익 저장
status: in_progress
started: 2026-08-22
updated: 2026-08-23
---

# Goal

매도 기록에 진입 전략이 안 남아 **전략별 손익 귀속이 불가능한 P0 버그**를 고치고, 과거 30건을 소급 복구하며, 금액 손익(`pnl_amount`)을 저장한다. 목표는 `/api/strategies/performance` 가 "어느 알고리즘이 얼마 벌었나"에 실제로 답하게 만드는 것.

# Progress

- 2026-08-22: 운영 DB 실측으로 버그 확정 — `trade_records`/`trade_executions` 의 **SELL 30건 전부 `strategy=NULL`**. BUY 는 `combined` 29/`manual` 2/`rsi_bounce` 1 로 정상 기록. `trade_executions.pnl_amount` 62행 전부 NULL, `fee` 전부 0.
- 2026-08-22: 근본 원인 확정 — `PositionManager.buildSellRecord`(`:639-665`)가 `TradeRecord` 생성 시 `strategy` 인자를 넘기지 않는다. `TradeRecord.strategy` 가 기본값 `null` 인 optional 파라미터라 컴파일이 통과한다. 매수 경로(`completeBuy:268`)는 넘긴다.
- 2026-08-22: 데이터 가용성 확인 — `markSold`(`TradingState:107`)가 `clearEntryMeta()`(`:111`→`:128`)로 `entryStrategy` 를 지우지만 `buildSellRecord` 는 **그 이전**에 호출된다(호출부 3곳 `:537`·`:591`·`:623` 전부). 값은 손에 있고 넘기지만 않았다.
- 2026-08-22: **backfill SQL 을 UPDATE 전에 읽기전용 SELECT 로 시뮬레이션해 페어링 버그를 사전 발견.** 초안(직전 BUY 기준)은 추가매수 2건에서 엔진 시맨틱과 어긋났다(id 4 BTC: 직전 `rsi_bounce` vs 최초 `manual`, id 7 ETH: 직전 `combined` vs 최초 `manual`, 둘 다 legs=2). "포지션 시작 BUY" 기준으로 교정 후 30건 전부 해결(unresolved 0), 집계 **combined 28 / manual 2 / rsi_bounce 0**. 매수원금도 단건→합산 교정(id 4: 21,637→31,636).
- 2026-08-22: **두 테이블 대응 방식을 운영 DB 실측으로 확정.** `trade_records` 에 `exchange_order_id` 부재·`trade_executions` 18행 NULL·타입 불일치(timestamp vs timestamptz)·1초 오차로 **조인은 위험**하다고 판단, 각 테이블 독립 페어링으로 전환. 검증 결과 양쪽 30건·unresolved 0·**mismatches 0**·집계 동일.
- 2026-08-22: **plan-reviewer — NO-GO.** backfill 규칙 2건이 코드 시맨틱과 어긋난다는 지적. 메인이 코드·운영DB 로 직접 검증해 **둘 다 사실 확인, 내 2차 "교정"이 오히려 오류였음**:
  - **P1 귀속 규칙 역전** — `markBought:83` 의 "추가매수 시 entryStrategy 유지" 분기는 `position && !replace` 조건인데 `completeBuy:285` 가 `replace=true` 로 부르므로 **절대 타지 않는다**. 수동 BUY 는 `TradingState` 미변경, `syncPosition` 도 `entryStrategy` 미설정 → 어느 경로든 `entryStrategy = 엔진 전략`. 규칙을 "마지막 non-manual BUY" 로 최종 교정, 집계 **combined 29 / rsi_bounce 1**(내 1차 시뮬 결과와 동일).
  - **P2 원금 이중계상** — `completeBuy:265-267` 이 `balanceDouble()`(전체 잔고)·`avgBuyPriceDouble()`(포지션 평단)로 기록해 BUY 행이 누적값임을 실측 확정(BTC leg2 `0.00022853` == SELL 수량). 합산은 약 46% 과대 → "마지막 BUY 평단 × SELL volume" 으로 교정.
  - P3(`strategy` 기본값 제거를 이번 범위로)·P5(롤백 근거)·W1(cutoff 를 id 로)·W2(tie-break id)·W4(ORDER BY) 반영. codex 는 이 세션 PreToolUse hook 이 `codex exec` 를 차단해 미실행.
- 2026-08-22: **architecture-reviewer(codex off) — NEEDS DISCUSSION.** `buildSellRecord` 내부 채움·V20 DDL+backfill 병합은 승인. Major 3건(backfill 이 `trade_executions` 미포함 / `fee` 를 도메인에 올리면 5경로 드리프트+비대칭 필드 / 1000건 캡 위 금액 집계) 전부 채택해 아래 Decisions 에 반영. V20 번호는 미머지 브랜치 전수 확인 결과 사용 가능.

- 2026-08-22: **TDD Red → 구현 → Green 완료.** 신규 테스트 4개(매도 4경로)가 수정 전 실패·기존 82개 통과로 Red 성립 확인(vacuous 아님). 구현 후 `:bot:test` 전체 BUILD SUCCESSFUL.
- 2026-08-22: **V20 을 임시 postgres 컨테이너에서 실제 실행 검증** — V1~V20 순차 적용 전부 ok, `pnl_amount` 컬럼·백업테이블 2개 생성 확인. 운영 데이터 재현 시드로 backfill 귀속 **4/4 기대값 일치**(BTC 2-leg→`rsi_bounce`, ETH 2-leg→`combined`, ADA 1-leg→`combined`, XRP manual만→`NULL`), `pnl_amount` 844.26(합산이면 1234로 부풀 값), 재실행 0행. 컨테이너는 검증 후 삭제, 로컬 개발 DB 는 미변경.
- 2026-08-22: 문서 동기화 — `PROJECT_ANALYSIS.md`(V20 행), wiki `persistence-schema`(V20 + "매도 기록의 전략 귀속" 절 신설, verified 갱신), `exit-gates`(entryStrategy 의 두 번째 용도), `wiki/index.md`(기존 "V1~V18" drift 도 교정). 검증 3종 통과(link clean / verify clean 28p / smoke 10 pass).

- 2026-08-22: **code-reviewer(+codex high) REQUEST CHANGES → Major 2 + Minor 7 + Nit 5 반영** (처분은 `# Review Disposition`). 핵심은 M1 — 내가 arch 지적에 과잉 방어로 넣은 `id<=62` 상한이 오히려 "측정~배포 사이 체결분 영구 미보정" 을 만든다는 지적. 상한을 제거하고 점검 블록(WARNING)으로 대체했다. M2 는 `aggregateByStrategy` 를 임시 컨테이너에서 실제 실행해 해소.
- 2026-08-22: **simplify 체크(메인 직접)** — `netPnlPercent` 래퍼는 반복 인자를 줄이므로 유지, V20 의 테이블별 UPDATE 는 컬럼·조건이 달라 통합하면 오히려 복잡해져 유지. 제거한 것: 불필요한 `!!`(비-null `fee`), `TradeRecord` KDoc 의 원칙 서술 교정(`pnlAmount` 가 예외임을 정직하게 명시 — 평단은 청산과 함께 사라져 sink 가 되짚을 수 없다).
- 2026-08-22: **최종 검증 4/4 통과**(격리 runner) — `:bot:test :common:test` BUILD SUCCESSFUL / `build -x test` SUCCESSFUL / `wiki/verify.sh` clean 28p / `wiki/smoke.sh` 10 pass. 손익 공식 단일화도 확인(`rg "roundTripFeeRate \* 100"` → `TradePnl.kt` 1곳).

- 2026-08-23: **pre-push codex 3라운드 통과 후 push·PR #117 생성**(`30f3c19`). 라운드별 지적은 `# Review Disposition` 참조 — P1(fee 하드코딩)·P2(백업 불일치)·P2(부분체결)·P1(귀속 순서) 처리 후 "no blocking issues".

# Next

**배포 시 확인 필요**(마이그레이션이 운영 데이터를 바꾼다):
1. 배포 전 `deploy/vultr/backup.sh` 1회 실행(pg_dump→S3).
2. 배포 후 기동 로그에서 `V20 backfill: 미귀속 매도 잔존` WARNING 을 확인 — 수동 매수만으로 만든 포지션 수와 일치해야 정상이고, 그보다 크면 페어링 전제가 깨진 것이다.
3. SQL 로 전략별 집계 확인(API 아님 — `findByUserId` 는 legacy NULL user_id 행을 못 본다): `SELECT strategy, count(*) FROM trade_records WHERE side='SELL' GROUP BY 1`. 기대 **combined 29 / rsi_bounce 1**.
4. 확인 후 `trade_records_v20_backup`·`trade_executions_v20_backup` DROP.

# Decisions

## 도메인·계산 배치

- **`TradeRecord` 는 "체결 이벤트"다** — 테이블 컬럼 집합의 미러가 아니다. 소비자가 이미 3종(`trade_records` sink, `trade_executions` sink, `DiscordNotifier`)이라 사실상 이벤트로 쓰이고 있다. 따라서 **체결 사실**은 도메인에 싣고, **파생 지표**는 sink(`saveAudit`)가 계산한다. 이 한 줄이 아래 두 결정의 근거다.
- **`fee` 는 `TradeRecord` 에 넣지 않는다** (arch M2). `saveAudit` 가 `TradeExecutionEntity` 를 만들 때 `record.totalAmount × roundTripFeeRate/2` 로 파생한다. 이유: ① `TradeRecord` 생성 경로가 5곳(`completeBuy`·`buildSellRecord`·수동 3경로)인데 전부 `saveAudit` 한 지점으로 수렴하므로 거기서 계산하면 드리프트 표면이 0이다 ② 편도 수수료는 BUY 에도 성립하는데 매도 경로에만 넣으면 BUY 행이 영원히 0으로 남는다 ③ `trade_records` 에는 `fee` 컬럼이 없어 도메인 필드로 두면 한쪽 sink 에서 조용히 버려지는 비대칭 필드가 된다.
- **`pnlAmount` 는 `TradeRecord` 필드로 둔다** — `saveAudit` 는 매수원금을 모른다(`totalAmount` 는 매도금액). 다만 **공식은 도메인 순수함수 한 곳**에 두고 `PositionManager`·`TradeExecutionService` 가 함께 호출한다. `netPnlPercent` 공식이 이미 `PositionManager:649` 와 `TradeExecutionService:32-34` 에 **중복**돼 있으므로 같이 해소한다.
- **`pnlAmount` 와 `strategy` 둘 다 기본값을 제거한다** (arch m5 + plan-review P3). 이번 버그의 근본 유인이 optional 기본값이라 인자를 빼먹어도 컴파일이 통과한 것이다. `pnlAmount` 를 required 로 만드는 순간 Kotlin data class 특성상 **생성 지점 14곳(프로덕션 5 + 테스트 9)이 전부 컴파일 에러**가 되는데, 이는 `strategy` 기본값 제거가 건드릴 집합과 **정확히 같다**. 따라서 `strategy` 만 미루는 건 한계비용이 몇 줄인 일을 남겨두는 것이라 같은 PR 에서 닫는다.
- **손익 공식 순수함수는 `(pnlPercent, basisPrice, volume)` 를 인자로 받는다** — `state` 를 직접 참조하면 재시작 pending SELL 복구 경로(`buildSellRecord:647` 가 `state.avgBuyPrice` 대신 `pendingSellAvgPrice` 를 쓰는 분기)에서 0/null 이 된다. 부분매도가 leg 별 SELL 행을 만들어도 이 시그니처면 GROUP BY 합산이 자동으로 맞는다.
- **`pnl_amount` 는 net** = `pnlPercent/100 × basisPrice × volume`. `pnlPercent` 가 이미 net 이라 gross 로 두면 두 컬럼 기준이 달라 합산 시 어긋난다. BUY 행은 null(진입 시점엔 손익 없음 — `pnlPercent` 와 같은 규칙).
- **backfill 의 매수원금은 BUY 행 합산이 아니라 "마지막 BUY 의 평단(`total_amount/volume`) × SELL volume"** — plan-reviewer 지적 후 실측 확정. `completeBuy:265-267` 이 `account.balanceDouble()`(통화 **전체 잔고**)와 `account.avgBuyPriceDouble()`(거래소 **포지션 평단**)로 기록하므로 **엔진 BUY 행은 증분 leg 이 아니라 그 시점 포지션 누적값**이다. 실측: BTC leg1 `9.83e-05` → leg2 `0.00022853` → SELL `0.00022853`(leg2 == 매도수량), ETH leg1 `0.00345` → leg2 `0.00790` → SELL `0.00790`. 합산하면 leg1 이 이중계상된다(id 4 기준 21,637 → 31,636, 약 46% 과대). 이 규칙은 런타임 공식(`buildSellRecord:647` 의 `basisPrice = state.avgBuyPrice`)을 그대로 미러한다.
- **`trade_records` 에는 `pnl_amount` 만 추가**하고 `fee` 는 넣지 않는다(위 비대칭 이유).

## 집계

- **`getPerformance` 를 DB GROUP BY 로 내린다** (arch M3). 현재 `findByUserId(userId, 1000)` + 인메모리 `groupBy` 인데, 여기에 **원화 금액**을 얹으면 1001번째 거래부터 조용히 사라지는 값에 "이 전략이 번 돈"이라는 이름을 붙이게 된다. (게다가 현재 1000 은 SQL LIMIT 이 아니라 `findByUserId:61-67` 이 전 행을 Flux 로 받은 뒤 `.skip().take()` 하는 것이라 성능상으로도 나쁘다.) `aggregateSellStatsByUser`(`:27-38`)가 동형 선례.
  - **`ORDER BY` 를 반드시 명시한다** (plan-review W4) — SPA(`screens.jsx:224`)가 `.slice(0, 5)` 로 상위 5개만 그리는데 GROUP BY 는 HashAggregate 라 순서 보장이 없다. 현재 인메모리는 "최근 거래 전략 우선" 순서였다.
  - 혼합 집계를 정확히 재현: `total_trades`·`total_amount` 는 BUY+SELL, 승률·pnl 은 `side='SELL' AND pnl_percent IS NOT NULL` → `COUNT(*) FILTER (...)`. 정수 나눗셈 `::double precision`, 0 division `NULLIF`, 빈 그룹 `COALESCE`(선례가 이미 쓴다).
  - **`total_amount` 정의는 그대로 둔다** — BUY+SELL 합이라 같은 자금을 이중계상한 무의미 값이지만, SPA 가 안 그리므로 지금 바꾸면 API 계약만 조용히 변한다. 정리는 `# Deferred`.

## 마이그레이션 (V20)

- **DDL + backfill 을 한 마이그레이션에 병합** (arch Q4 승인). repo 전례 V8·V15 에 UPDATE/INSERT 가 있다.
- **두 테이블 모두 backfill** (arch M1). `trade_records` 와 `trade_executions` 는 `saveAudit` 가 **한 트랜잭션에서 함께 쓰는** 이중기록이라 한쪽만 복구하면 "같은 체결인데 두 감사 테이블이 다른 답을 주는" 상태가 영구화된다. `trade_executions` 는 `exchange='UPBIT'` 로 스코프(KIS 행 보호).
- **두 테이블을 조인하지 않고 각자 독립 페어링한다** — 운영 DB 실측으로 결정. 조인이 위험한 이유: ① `trade_records` 에는 `exchange_order_id` 컬럼이 **아예 없고** `trade_executions` 도 18행이 NULL 이라 주문 ID 로 대응 불가 ② `id` 가 우연히 일치하지만(4↔4, 7↔7…) KIS `StockOrderReconciler` 가 `trade_executions` 에만 쓰면 어긋난다(현재 `exchange<>'UPBIT'` 0행이라 우연히 맞을 뿐) ③ 시간 조인은 `created_at`(**timestamp**) vs `executed_at`(**timestamptz**) 타입이 달라 서버 timezone 에 의존하고, 실제로 1초 차이 나는 행이 있어 윈도우 휴리스틱이 필요하다. **각 테이블이 자기 시간축으로 같은 규칙을 돌리면 이 문제가 전부 사라진다** — 실측 검증: 양쪽 30건, unresolved 0, **mismatches 0**, 집계 동일(combined 28 / manual 2).
- **시간축·NULL 처리는 테이블별로 다르다** — `trade_records`: `created_at`(timestamp), `user_id` nullable → `IS NOT DISTINCT FROM`. `trade_executions`: `executed_at`(timestamptz), `user_id` NOT NULL → `=`, 추가로 `exchange` 를 페어링 조건에 포함.
- **페어링은 "포지션 구간(직전 SELL 이후) 내 첫 번째 non-manual BUY, 없으면 NULL"** — **세 번 교정**한 최종 규칙이다(1차 직전 BUY → 2차 포지션 시작 BUY → 3차 마지막 non-manual → **최종 첫 non-manual**). 마지막 교정은 pre-push codex P1: `markBought` 의 else 안 `entryStrategy = if (resuming) entryStrategy ?: strategy else strategy` 에서 **`resuming` 이 참이면 기존 값이 살아남는다**. 내가 검증한 건 `resuming=false`(수동 매수 위 엔진 매수)뿐이었고, `resuming=true` 경로가 실재한다 — 재시작 후 `syncPosition` 이 `position=true` 로 만든 뒤 `reconcilePendingBuy`(`:207`)·`BalanceRecovery`(`:241`)가 `completeBuy` 를 부르는 경우. 즉 한 포지션에 엔진 BUY 가 여럿이면 **먼저 찍힌 전략**이 남는다. 운영 데이터는 포지션당 엔진 BUY 가 1개뿐이라 결과가 같지만(combined 29 / rsi_bounce 1), 규칙으로서는 첫 번째가 맞다. 검증: 엔진 2-leg 케이스를 시드에 추가해 5/5 일치 확인.
  - ⚠️ **원금은 반대로 마지막 BUY 기준**이다. 전략은 최초 진입값이 유지되는 반면 `avgBuyPrice` 는 `markBought` 가 매번 덮어쓴다. 두 기준이 다른 것은 런타임을 미러한 결과다(검증에서 SOL 원금 = 마지막 평단 3000×5 확인).
  - ❌ 1차 초안 "직전 BUY": 우연히 결과는 맞았으나 `manual` 을 후보에 넣어 규칙이 부정확.
  - ❌ 2차 "포지션 시작 BUY(최초)": `markBought:83,93` 의 "추가매수 시 entryStrategy 유지" 주석을 근거로 삼았는데, **`completeBuy:285` 는 `markBought(replace=true)` 로 호출하므로 `position && !replace` 가지를 절대 타지 않는다.** 항상 `else` 로 가서 `entryStrategy = if (resuming) entryStrategy ?: strategy else strategy` 를 평가한다. 수동 BUY(`TradeExecutionService:42-76`)는 `TradingState` 를 전혀 건드리지 않고 `syncPosition:52-73` 도 `entryStrategy` 를 세우지 않으므로, `resuming` 이 false 든 true 든 결과는 **엔진 전략**이다.
  - ✅ 최종: 런타임이 실제로 남기는 값은 **그 포지션의 마지막 엔진 BUY 전략**이다. `manual` BUY 는 `TradingState` 에 진입 전략을 남기지 않으므로 애초에 귀속 후보가 아니다.
  - 실측 재검증: **combined 29 / rsi_bounce 1 / manual 0**, unresolved 0.
- **`user_id` 매칭은 NULL-safe** — `trade_records.user_id` 가 V2 에서 nullable 로 추가돼 `b.user_id = s.user_id` 는 NULL 행에서 매칭 실패한다(`NULL = NULL` → unknown). `IS NOT DISTINCT FROM` 을 쓴다. 지금은 단일 유저라 무해하지만 티커가 유저 간 겹치므로 규칙을 지금 박는다.
- **재실행 안전(idempotent)** — 조건이 `strategy IS NULL AND side='SELL'` 이라 2회차는 **값 변경이 없다**(귀속 후보가 없어 NULL 로 남은 행은 다시 걸리지만 NULL→NULL 이라 무해). 신규/빈 DB 에서는 no-op. Flyway 가 재실행하지 않으므로 실사용상 1회다.
- **상한을 두지 않는다** — arch m6 지적("실측 안 된 행까지 건드린다")에 처음엔 `id <= 62` 를 걸었으나 code-review M1 에서 **그 방어가 오히려 결함**임이 드러났다: 봇은 측정과 배포 사이에도 돌기 때문에 그 사이 체결된 SELL 이 상한에 걸려 영구 미보정으로 남는다. V20 이 도는 시점은 새 앱 기동 시이고 그때 `strategy` 가 빈 매도 행은 정의상 전부 구버전 코드가 쓴 것이므로(새 코드는 채운다) 상한이 필요 없다. 전제가 어긋나는 행은 상관 서브쿼리가 NULL 을 돌려 건드리지 않고 넘어가고, 점검 블록이 그 수를 WARNING 으로 알린다.
- **백필 결과를 점검 블록으로 노출한다** — UPDATE 는 전제가 깨져도 조용히 0행으로 끝나 "고쳐졌다"고 오인하게 만든다. `DO $$` 로 남은 미귀속 수를 세어 `RAISE WARNING`. `EXCEPTION` 이 아닌 이유는 귀속 후보가 없는 행(수동 매수만으로 만든 포지션)이 **정상적으로** NULL 로 남아야 하는데 그걸로 기동을 막으면 `deploy.sh:585` 의 자동 롤백이 걸리기 때문이다.
- **페어링 순서·tie-break 도 시각이 아니라 `id`** (plan-review W2) — `created_at` 은 마이크로초 동률이 가능하고, 두 테이블이 서로 다른 시각을 담는다(`trade_records` = 도메인 객체 생성 시각, `trade_executions` = `saveAudit` 내 엔티티 생성 시각). 상관 서브쿼리는 `(user_id, ticker)` 파티션 + `ORDER BY id DESC`.
- **롤백 근거를 V20 안에 남긴다** (plan-review P5). backfill 은 NULL→값 덮어쓰기라 규칙이 틀렸을 때 원상복구가 불가능하다. V20 이 UPDATE 전에 `CREATE TABLE trade_records_v20_backup AS SELECT id, strategy FROM trade_records WHERE <cutoff>` (+ `trade_executions` 분)을 만든다 — 62행이라 비용 0, down 스크립트 대용. 배포 전 `deploy/vultr/backup.sh`(pg_dump→S3) 1회 실행도 절차로 박는다.
  - 마이그레이션 **실패** 경로는 기존 장치로 이미 커버된다(명시만 함): Postgres 가 DDL 트랜잭션을 지원하므로 V20 실패 시 통째 롤백 + 기동 실패 → `deploy/vultr/deploy.sh:585` 가 `LAST_GOOD_SHA` 로 자동 롤백. `pnl_amount` 는 additive nullable 이고 R2DBC 는 엔티티 선언 컬럼만 SELECT/INSERT 하므로 **구버전 앱 + V20 공존도 안전**하다.
- **V20 상단에 귀속 규칙과 그 한계를 주석**으로 남긴다 — 페어링이 "코드가 보장하는 불변식"이 아니라 "적용 시점 데이터의 성질"임을 명시. 부분매도(`applySellFillOutcome:542-551` 의 `remaining>0` 분기)와 수동 `executeSellVolume` 은 **이미 오늘 가능**하므로 미래에는 1:1 이 깨질 수 있다.

# Key Files

- `bot/src/main/kotlin/com/trading/bot/engine/PositionManager.kt` — `buildSellRecord`(`:639`) 수정. ⚠️ 다른 세션이 `stuck-sell-alert` 브랜치에서 같은 파일을 건드릴 수 있음
- `bot/src/main/kotlin/com/trading/bot/domain/TradeRecord.kt` — `pnlAmount` 추가(기본값 없음). `fee` 는 안 넣음
- **신규**: 손익 공식 순수함수(`bot/domain` — `netPnlPercent`/`pnlAmount`). 기존 중복 2곳 해소
- `bot/src/main/kotlin/com/trading/bot/engine/TradeExecutionService.kt` — `saveAudit`(`:175-202`)가 `fee` 파생 + `pnlAmount` 전달, `netPnlPercent`(`:32-34`) 순수함수로 치환
- `bot/src/main/kotlin/com/trading/bot/persistence/entity/TradeRecordEntity.kt` — `pnlAmount` 매핑
- `bot/src/main/kotlin/com/trading/bot/persistence/TradeRecordRepository.kt` — `aggregateByStrategy` 추가(`aggregateSellStatsByUser:27-38` 선례)
- `bot/src/main/kotlin/com/trading/bot/api/StrategyController.kt` — `getPerformance`(`:40-65`) DB 집계로 치환
- **신규**: `bot/src/main/resources/db/migration/V20__backfill_sell_strategy_and_pnl_amount.sql`
- 기존 테스트: `PositionManagerTest`·`PositionManagerExtendedTest`·`TradeAuditAtomicityTest`·`TradeExecutionServiceTest`·`DiscordNotifierTest`·`TradingEngineTest`

# Blockers

없음.

# Acceptance

- [x] **매도 기록에 진입 전략이 남는다** — 검증: 신규 단위테스트가 **네 경로**(즉시체결 `:971` / reconcile done `:1058` / 잔고복구 `:751` / **부분체결 `:1080`**)에서 `TradeRecord.strategy == entryStrategy` 단언. 통과 기준: TDD Red(수정 전 실패) → Green.
  - ⚠️ **vacuous 단언 함정**: 기존 테스트 대부분이 `markBought(price, vol)` 를 strategy 없이 호출해 `entryStrategy = null` 이다. 그대로 단언하면 `null == null` 로 **수정 전에도 Green** 이 되어 Red 가 성립하지 않는다. 반드시 `markBought(price, vol, "combined")` 또는 `TradingState(..., entryStrategy = "combined")` 로 세운다. 이 repo 는 `750d70f` 에서 vacuous 단언으로 pre-push codex 에 걸린 전례가 있다.
- [x] **`pnlAmount` 가 net 기준으로 채워진다** — 검증: 단위테스트가 `pnlAmount ≈ pnlPercent/100 × basisPrice × volume` 단언. BUY 는 null.
- [x] **`pnlPercent == null` 인 SELL 에서 `pnlAmount` 도 null**(0 아님) — 검증: 기존 `sell records null pnl when avg buy price unknown`(`PositionManagerExtendedTest:1234`)에 단언 추가.
- [x] **`TradeRecordRepository.save` 매핑 누락이 잡힌다** — 검증: 엔티티 변환 단위테스트가 `pnlAmount` 를 단언. 근거: 이 매핑은 수동 필드 복사라 `record.exchangeOrderId` 를 **이미 구조적으로 버리고 있다**(`TradeRecordEntity` 에 필드 자체가 없음). 도메인 required 가 이 경계는 못 지켜주므로 `pnlAmount` 도 같은 방식으로 조용히 유실될 수 있다.
- [x] **`fee` 가 BUY·SELL 양쪽에 채워진다** — 검증: `saveAudit` 테스트가 `TradeExecutionEntity.fee ≈ totalAmount × roundTripFeeRate/2` 를 BUY 행에서도 단언.
- [x] **손익 공식이 한 곳** — 검증: `rg "roundTripFeeRate \* 100"` 결과가 순수함수 1곳(기존 2곳 중복 해소).
- [x] **backfill 규칙이 재현 데이터에서 정확하다** — 임시 컨테이너에 운영을 재현한 시드를 넣고 V20 실행: BTC 2-leg→`rsi_bounce`, ETH 2-leg→`combined`, ADA 1-leg→`combined`, XRP manual만→`NULL` **4/4 기대값 일치**. `pnl_amount` 844.26(합산이면 1234로 부풀 값). ⚠️ **운영 적용은 배포 시점** — 그때 SQL 로 `strategy IS NULL` 잔존과 전략별 집계를 확인해야 완결된다(아래 배포 절차).
- [x] **DB 계층을 로컬에서 실제로 돌렸다** — V1~V20 순차 적용 전부 ok, `aggregateByStrategy` 쿼리 실행해 `FILTER`·`GROUP BY NULL`·alias ORDER BY 동작 + 혼합 집계(거래수 BUY+SELL / 손익 SELL-only) 재현 확인. 점검 블록 WARNING 도 실제 발화.
- [x] **`fee` 컬럼이 균일해진다** — 검증에서 10행 전부 채워짐(zero_fee 0). "0(미기록)"과 추정치가 섞이지 않는다.
- [x] **마이그레이션 사전 검증 절차 준수** (arch m7 — Flyway 가 테스트에서 안 돌아 첫 실행이 프로덕션이다) — 검증: V20 의 UPDATE 를 SELECT 형태로 운영 스냅샷에 먼저 실행해 영향 행수·귀속 분포를 확인한 기록이 `# Progress` 에 있다. (사실상 완료 — 절차로 승격)
- [x] **마이그레이션이 재실행 안전** — 검증: 같은 DB 에 두 번 적용해도 2회차 0행 갱신. 빈 DB no-op.
- [x] **API 가 금액을 노출하고 절단되지 않는다** — 검증: `getPerformance` 응답에 `total_pnl_amount` 가 있고, 집계가 DB GROUP BY 라 1000건 캡의 영향을 받지 않는다.
- [x] **`unknown` 폴백은 유지한다** (arch m9) — **과거 30건**에 대해 unknown 그룹이 사라지는 것이 목표이며, 신규 SELL 은 `entryStrategy` 가 null 일 수 있으므로(`recoverSellFromBalance` 경로, `TradingEngine:303` 이 null 을 정상 취급) `?: "unknown"` 폴백을 제거하지 않는다. 빈 문자열 강제도 금지(`completeBuy:262` 주석이 WARN 스팸 경고).
- [x] **`/api/trades` additive 변경 인식** (arch m8) — `TradeHistoryController` 가 `TradeRecordEntity` 를 그대로 반환하므로 `pnlAmount` 추가가 응답 스키마를 바꾼다. additive 라 SPA 는 안 깨지지만 의도된 변경으로 기록.
- [x] **기존 회귀 없음** — 검증: `JAVA_HOME=/Users/jongyoonlee/Library/Java/JavaVirtualMachines/jbr-21.0.9/Contents/Home ./gradlew :bot:test` green.
- [x] **문서 동기화** — `PROJECT_ANALYSIS.md`(스키마 V20) + wiki `persistence-schema`(V19→V20) + `sources` 에 `PositionManager.kt` 를 가진 페이지. `wiki/index.md` 동기화. 검증 3종(`check_links.py`·`verify.sh`·`smoke.sh`).

# Review Disposition

**code-reviewer(+codex high) — REQUEST CHANGES. CONFIRMED Major 2 / Minor 7 / Nit 5 / refuted 6.**

| finding | 처분 | 근거 |
|---|---|---|
| **M1** V20 `id<=62` 상한이 측정 스냅샷 고정 — 측정~배포 사이 신규 SELL 영구 미보정 | **fix (상한 제거)** | reviewer 는 "배포 직전 재측정"을 제안했으나 상한 자체가 불필요하다. V20 이 도는 시점은 새 앱 기동 시이고 그때 `strategy IS NULL` 인 SELL 은 정의상 전부 구버전이 쓴 행이다(새 코드는 채운다). 상한은 arch m6("실측 안 된 행") 에 대한 과잉 방어였고 오히려 결함의 원인이었다 |
| **M1-b** 백필 실패가 조용히 통과 | **fix** | UPDATE 뒤 `DO $$` 점검 블록 추가. 남은 미귀속 수를 WARNING 으로. EXCEPTION 이 아닌 이유: 귀속 후보 없는 행(수동매수 포지션)은 정상적으로 NULL 이라 기동을 막으면 `deploy.sh` 자동 롤백이 걸린다. 검증에서 실제 발화 확인 |
| **M2** `aggregateByStrategy` SQL 미실행 | **fix** | 임시 컨테이너에 시드 넣고 실제 실행. `FILTER`·`GROUP BY NULL`·alias ORDER BY 동작 확인, 혼합 집계(거래수 BUY+SELL / 손익 SELL-only) 재현 확인 |
| **m3** `fee` 에 "0(미기록)"과 추정치 혼재 | **fix → 철회, (b) 문서화로 변경** | 처음엔 reviewer 제안 (a)대로 기존 행도 backfill 했으나 **pre-push codex 가 P1 로 차단**: 수수료율은 `TRADING_ROUND_TRIP_FEE_RATE` 로 환경마다 다를 수 있고(배포 compose 3종이 전부 이 변수를 전달) SQL 에 상수로 박으면 기본값 아닌 환경에서 과거·현재 기준이 갈린다. 원본이 0 이라 되돌릴 근거도 없다. **0(미기록)은 "없다"고 읽히지만 틀린 추정치는 맞는 값과 구분되지 않는다** → backfill 제거, wiki 에 한계 명시. 컬럼 소비자가 0건이라 실손해 없음 |
| **m4** V20 주석의 manual 평단 서술이 거짓 | **fix** | 수동 BUY 는 `total_amount/volume` 이 틱 가격이지 평단이 아니다. 한계를 명시하도록 주석 교정 |
| **m5** `TradePnl` 직접 테스트 없음 | **fix** | `TradePnlTest` 7케이스 추가. `netPercent(currentPrice=0)` 포함(아래 Nit) |
| **m6** ORDER BY 와 SPA 표시 지표 불일치 | **fix** | `total_pnl_amount DESC` → `total_pnl_pct DESC`. SPA(`screens.jsx:224`)가 `total_pnl_pct` 를 그린다 |
| **m7** `pnl_amount` ↔ `fee` 수수료 기준 상이 | **fix (문서)** | `estimatedFee` KDoc 에 기준 차이 명시. 소비자가 없어 코드 변경은 과하다 |
| **m8** 수동 매도 `pnl_amount` 가 요청수량·틱가격 추정치 | **defer** | 이번 변경이 도입한 부정확성이 아니다(`volume`·`price`·`pnlPercent` 가 이전부터 같은 추정치). `# Deferred` 기록 |
| **Nit** `DiscordNotifierTest` 의 `strategy=null` 커버리지 소실 | **fix** | 세 테스트 중 하나를 `null` 로 되돌려 `DiscordNotifier:43` 분기 복원 |
| **Nit** `netPercent` 의 `currentPrice>0` 조건 추가 = 동작 변화 | **fix (테스트+기록)** | 기존엔 `currentPrice=0` 에서 −100.1% 를 기록했다. 개선이 맞으나 미기록이었다 → `TradePnlTest` 로 고정 |
| **Nit** `entity.captured.fee!!` 불필요한 `!!` | **fix** | `fee` 는 non-null(`= 0.0`) |
| **Nit** 백업 테이블 영구 잔존·pre-exist 시 실패 | **fix (범위 축소)** | 손대는 행만 백업하도록 변경(`WHERE side='SELL' AND strategy IS NULL`). 정리 시점은 주석에 명시 |
| **Nit** plan 의 "재실행 0행" 부정확 | **fix** | 귀속 실패로 NULL 이 남은 행은 재차 걸린다 → "값 변경 0" 이 정확 |
| **PLAUSIBLE** 부분매도 낀 포지션의 무귀속 | **wontfix (감시로 대체)** | 대상 데이터에 미도달. M1-b 의 WARNING 이 이 케이스도 함께 잡는다 |
| **Nit** `IS NOT DISTINCT FROM` 이 NULL 유저를 한 논리 유저로 묶음 | **wontfix** | 현재 단일 유저. 멀티테넌트 도입 시 재검토 |
| **pre-push codex P1(1차)** `fee` backfill 이 수수료율을 하드코딩 | **fix (backfill 철회)** | `TRADING_ROUND_TRIP_FEE_RATE` 는 배포 compose 3종이 전달하는 환경별 설정값. SQL 에 상수로 박으면 기본값 아닌 환경에서 과거·현재 기준이 갈린다. 컬럼 소비자 0건이라 실손해 없음 |
| **pre-push codex P2(2차)** 백업 조건이 UPDATE 대상과 불일치 | **fix** | `strategy='manual'` SELL 이 `pnl_amount` 백필만 받으면 원본이 안 남는다. 백업을 네 UPDATE 의 합집합으로 |
| **pre-push codex P2(2차)** 부분체결 후속 매도 무귀속 | **wontfix (근거 기록)** | 대상 0건(연속 SELL 없음). "직전 SELL 에서 물려받기"로 넓히려면 같은 포지션인지 판단할 근거가 필요한데 수량 누적을 추적하지 않는 한 SQL 로 알 수 없다 — 추측으로 넓히면 조용히 틀린 귀속을 만든다. WARNING 으로 드러내는 편이 낫다 |
| **pre-push codex P1(3차)** 귀속이 마지막 BUY 라 런타임(최초 유지)과 불일치 | **fix (ASC 로 정정)** | 내가 `resuming=false` 케이스만 검증했다. `resuming=true`(재시작 reconcile) 경로가 실재하고 그때 기존 `entryStrategy` 가 살아남는다. 엔진 2-leg 시드를 추가해 5/5 재검증 |
| refuted 6건 | — | `markSold` 이후 호출 / 부분체결 후 `entryStrategy` 소실 / 명명 파라미터 바인딩 / full scan / 외부 호환성 등. reviewer 가 반증 근거와 함께 기각 |

# Deferred

- **수동 매도 경로의 `volume`·`price` 가 체결 응답이 아니라 요청값** (code-review m8) — `executeSellVolume:153` 의 `vol` 은 요청 수량, `currentPrice` 는 `getTicker()` 값이다. 0.3 요청/0.2 체결이면 `pnl_amount` 가 50% 과대. 이번 변경 이전부터 `volume`·`price`·`pnlPercent` 가 같은 추정치였으므로 신규 결함은 아니나, 금액으로 승격돼 사용자가 현금처럼 읽게 됐다.
- **통합 테스트 인프라 부재** (code-review M2 의 근본 원인) — `Testcontainers`/`@DataR2dbcTest`/`@SpringBootTest` 가 repo 에 0건이라 Flyway·R2DBC 매핑·GROUP BY SQL 이 CI 에서 한 번도 안 돈다. 이번엔 임시 컨테이너 수동 검증으로 메웠다. 이슈 #53 이 같은 주제.
- **`*_v20_backup` 테이블 정리** — 백필 결과 확인 후 DROP. 방치하면 영구 잔존한다.
- **수동 매도가 진입 전략의 크레딧을 뺏는다** (plan-review W6) — `executeSellAll`·`executeSellVolume` 은 `strategy="manual"` 하드코딩(`ManualTradeController:73,86`). 엔진이 잡은 포지션을 사람이 수동 청산하면 손익이 `manual` 로 잡혀 진입 전략이 크레딧을 못 받는다. Goal("어느 알고리즘이 얼마 벌었나")에 직접 반하지만 이번 수정 범위 밖.
- **`unknown` 의 주 발생원은 수동매수 + syncPosition** (plan-review 누락시나리오 3) — arch m9 는 `recoverSellFromBalance` 를 원인으로 들었으나, 실제로는 "수동 BUY 로 만든 포지션을 엔진이 매도" 하면 `entryStrategy = null` 이라 수정 후에도 계속 `unknown` 이 발생한다. 근본 해결은 수동 매수도 `TradingState` 를 세우게 하는 것.
- **`total_amount` 가 BUY+SELL 합이라 무의미** — 같은 자금을 이중계상. SPA 가 안 그려서 지금은 무해하나 이름이 손익으로 오독된다.
- **`saveAudit:176` 의 `record.userId ?: 0`** — `TradeRecord.userId` 는 non-null `Long = 0` 이라 이 elvis 는 죽은 코드다. 0 이 넘어가면 `REFERENCES users(id)` 위반으로 감사 저장이 통째 실패한다.
- **KIS 경로도 동일 결함** (arch 발견) — `StockOrderReconciler.buildExecution`(`:204-215`)이 `TradeExecutionEntity` 생성 시 `strategy` 를 넘기지 않는다. `stock_position_state.entry_strategy`(`StockPositionStateEntity:22`)에 값은 있다. 심각도 중. 파일: `bot/src/main/kotlin/com/trading/bot/kis/order/StockOrderReconciler.kt`.
- **`TradeRecord.strategy` 의 optional 기본값 제거** — 이번 버그의 근본 유인. 프로덕션 5곳 + 테스트 9곳 = 14곳 수정 필요라 이번 범위 밖.
- **엔티티가 곧 API 계약** — `TradeHistoryController:24-26` 이 `TradeRecordEntity` 를 DTO 매핑 없이 반환. 나중에 컬럼을 지우면 SPA(`screens.jsx` 의 `o.totalAmount`)가 조용히 깨진다. 같은 repo 의 `LeaderboardController:91` 은 명시 매핑이라 규약이 갈려 있다.
- **집계 구현 3벌** — `StrategyController:45-50`(인메모리), `LeaderboardController:74-77`(인메모리), `:33`(DB GROUP BY). 이번에 하나를 DB 로 내리면 나머지도 정리 후보.
- **7월 3건이 보유상한을 3거래일 초과** — `maxHoldDays=1` 인데 id 11·13·14 가 3거래일 뒤 DAILY_RESET. 8월은 전부 1거래일로 정상. 봇 중지·장애·배포 공백 가능성. 파일: `DailyResetManager.kt`. (출처: maxholddays-sweep plan)
- `ParameterSweepTest` 가 all-in 복리 `totalReturnPct` 로 정렬 — wiki `strategy-evolution-expectations` 원칙과 어긋남.
- maxHoldDays 축 백테는 **상승장 fixture 가 2026-08-22 main 에 들어왔으므로**(`97e663e`, PR #99) 국면 paired 로 재개 가능 — 이전 폐기 근거("표본이 전부 하락장")가 해소됐다.
