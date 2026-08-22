---
title: trade-roundtrip — 매매 내역을 매수→매도 라운드트립으로 조회
status: in_progress
started: 2026-08-22
updated: 2026-08-22
---

# Goal

"언제 얼마에 사서 → 언제 얼마에 팔았는지"를 한 행으로 보는 조회 API + 화면.
1차는 **스키마 변경 없이** 기존 `trade_records` 그룹핑만 — 과거 데이터에 즉시 적용된다.
대상은 Upbit 경로만(KIS 제외).

# Progress

- 2026-08-22: 사전 조사 완료. 현행 진단 5건 확정(아래 Decisions). worktree `trade-roundtrip` 생성, dlc structural 진입. draft plan 작성.
- 2026-08-22: TDD Red(미구현 심볼 참조로 컴파일 실패 확인) → 구현 → Green. 테스트 9종 통과, `./gradlew build` BUILD SUCCESSFUL.
  백엔드(`TradeRoundTrip.kt` 조립 + `findRecentAscending` + `GET /api/trades/roundtrips`), 프론트(OrdersPage 탭 구조) 완료.
  JSX 문법은 esbuild 파싱으로 검증, 필드명 계약은 직렬화 테스트로 고정. README API 표 갱신.
  **화면 실제 렌더은 미검증** — 브라우저 접근 실패(Blockers 참조).
- 2026-08-22: codex pre-push 리뷰가 push 를 2회 차단, 지적 3건 전부 수정(P1 부분 매도 · P2 잘린 그룹 partial ·
  P3 truncated 경계 — `# Review Disposition`). **P1 은 설계 가정 오류**라 그룹핑 기준을 수량 잔량으로 재구현했다.
  테스트 9종 → 12종, `./gradlew build` + esbuild 재검증 통과.

- 2026-08-22: codex 3차 지적 중 P2(화면 건수) 수정, P1(요청수량 기록)은 기록 경로 기존 결함이라 **이슈 #105 로 분리**.
  사용자 승인 하에 `CODEX_SKIP=1` 로 push, **PR #113** 생성. 커밋 5개.

# Next

1. **화면 실제 렌더 확인** (acceptance 9 — 유일한 미충족 항목). 하네스:
   `python3 -m http.server 8899 --directory bot/src/main/resources/static` → `http://localhost:8899/_rt_harness.html`.
   확인 후 `bot/src/main/resources/static/_rt_harness.html` 를 **삭제**한다(의도적으로 커밋에서 제외했다).
2. 확인되면 PR #113 머지 → worktree·브랜치 정리(이 repo 는 머지 후 자동 정리 규약).
3. 이어서 2차: SELL 행에 `entry_price`/`entry_at`/`pnl_amount` 기록해 수수료 차감된 정확한 손익 금액 제공(새 worktree).

# Decisions

## 왜 필요한가 — 현행 진단 (모두 코드 확인)

1. `trade_records`(V1)는 BUY/SELL 이 **독립 행**이고 둘을 잇는 키가 없다.
2. `PositionManager.buildSellRecord` 는 평단(`basisPrice`)으로 수익률만 계산하고 **평단 자체를 버린다** → 매도 행만 봐서는 매수가를 모른다.
3. `trade_records` 에 `pnl_amount` 컬럼이 없다. `trade_executions`(V11)에는 있으나 `TradeExecutionService.saveAudit` 이 채우지 않아 **항상 NULL** → 손익 금액을 아는 경로가 없다.
4. 화면(`screens.jsx` OrdersPage)은 시간/거래쌍/방향/가격/수량/총액/전략 7컬럼뿐 — DB에 있는 `pnl_percent`·`reason` 조차 안 보인다.
5. 보유 기간은 1번 때문에 계산 불가.

## 그룹핑 기준 — 수량 잔량 (2026-08-22 정정)

~~매도는 항상 전량 청산이므로 `직전 SELL 이후의 BUY 들` + `SELL 1건` 으로 묶인다~~
→ **틀렸다. 수량 잔량으로 판정하도록 변경** (이유: 이 전제는 **엔진 경로에만** 참이다.
`ManualTradeController.kt:80-86` 의 수동 매도는 `sellVolume = volume` 으로 **수량 지정 매도**를 지원하고
(`sell_all` 과 택일) 부분 매도마다 SELL 행이 남는다. SELL 하나를 곧 포지션 종료로 보면
첫 매도가 전체 매수액과 비교돼 손익이 틀리고 나머지 매도는 고아 SELL 로 빠진다. codex pre-push P1).

**현재 기준**: 한 포지션은 "매수를 누적하다가 매도 누적으로 **잔량이 0**(≤ `VOLUME_EPSILON`)이 될 때까지".
부분 매도는 같은 포지션에 누적되고, 매도 평균가·수익률은 수량가중으로 합산한다.
잔량이 남으면 `open=true` 이고 확정 손익(exit·pnl)을 채우지 않는다.

## SQL 아닌 Kotlin 순수 함수로 그룹핑 (이유: 테스트 가능성)

이 repo에는 **R2DBC 통합 테스트 인프라가 없다** — testcontainers 미사용, `bot/src/test/.../persistence/` 의 테스트 2개는 모두 mockk 기반 service 레이어다.
CTE+윈도우 함수 `@Query` 로 구현하면 검증할 방법이 없고 PostgreSQL 방언에 묶인다.
→ repository 는 기존 `findByUserId(userId, Sort)` 로 레코드를 가져오고, 조립은 **순수 함수**가 한다. 단위 테스트로 엣지 케이스를 전부 덮는다.

## 메모리 로드 상한

`aggregateSellStatsByUser` 주석의 "메모리 로드 회피" 의도는 **전 유저** 집계 기준이고, 라운드트립은 **단일 유저** 조회라 성격이 다르다.
전체 레코드를 시간순 로드하되 상한(`MAX_RECORDS`)을 둔다. 상한 초과 시 잘린 사실을 응답에 명시한다(조용한 truncation 금지).

## 1차 범위에서 제외 (2차로 미룸)

- 스키마 추가(`entry_price`/`entry_at`/`pnl_amount` on SELL) — 앞으로의 정확도용. 1차 그룹핑으로 과거까지 커버되므로 급하지 않다.
- 손익 **금액**은 1차에서 `매도총액 − 매수총액`(gross, 수수료 미차감)으로만 제공하고 그 사실을 화면에 표기한다. `pnl_percent` 는 왕복수수료 차감 net 이라 둘의 부호가 어긋날 수 있다.
- KIS 주식 경로.

## 가정 (사용자 확인 없이 진행 — 상위집합이라 안전)

기존 flat 내역 뷰를 **없애지 않고** 탭으로 병행한다(라운드트립이 기본). 어느 선호에도 안전한 상위집합.

## plan 검토 반영 (2026-08-22, 메인 직접 — plan-reviewer subagent 미사용)

1. **정렬 안정성 — 그룹핑 정확도에 직결.** `createdAt` 만으로 정렬하면 동률 시 BUY/SELL 순서가 뒤집혀 그룹이 어긋난다.
   `Sort.by(createdAt ASC, id ASC)` 로 **2차 정렬 키를 반드시 넣는다.**
2. **truncation 시 첫 그룹 불완전.** 상한으로 오래된 레코드를 자르면 가장 오래된 라운드트립의 BUY 가 잘려 매수 정보가 빈다.
   조용히 넘기지 않고 해당 행에 `partial=true` 를 세우고 응답에도 `truncated` 를 노출한다.
3. **패키지 위치**: `engine/` → `domain/` (위 Key Files 참조).
4. **`DAILY_RESET` 확인 완료** — `TradingEngine.kt:322` 의 정상 청산 게이트라 가짜 기록이 아니다. 그룹핑에 영향 없음.

# Key Files

- `bot/.../persistence/TradeRecordRepository.kt` — 조회. 기존 `findByUserId(userId, Sort)` 재사용
- `bot/.../api/TradeHistoryController.kt` — 신규 엔드포인트 `GET /api/trades/roundtrips`
- `bot/.../api/TradeRoundTrip.kt` — (신규) DTO + 순수 그룹핑 함수. 테스트 주 대상.
  위치를 `engine/`→`domain/`→**`api/`** 로 변경 (이유: `engine/` 은 거래 *실행* 로직이라 부적절.
  `domain/` 은 입력이 `TradeRecordEntity`(persistence)라 domain→persistence 의존 역전을 부른다.
  조회 응답 조립이고 기존 `TradeHistoryController` 도 Entity 를 그대로 노출하므로 `api/` 가 의존 방향상 정합)
- `bot/src/test/.../api/TradeRoundTripTest.kt` — (신규) TDD
- `bot/src/main/resources/static/tide-app/api.js` — `roundtrips()` 추가
- `bot/src/main/resources/static/tide-app/screens.jsx` — OrdersPage 탭 + 라운드트립 테이블

# Acceptance

| # | 충족 내용 | 검증 방법 | 결과 |
|---|-----------|-----------|------|
| 1 | 단일 매수→매도가 한 행으로 조립 | `TradeRoundTripTest` | ✅ 통과 |
| 2 | 분할 매수(N건)→매도 1건이 한 행, 평단=금액가중 | 동 테스트 (100×1+200×3 → 175) | ✅ 통과 |
| 3 | 미청산 보유분이 `open=true` 로 표시 | 동 테스트 | ✅ 통과 |
| 4 | 여러 티커가 교차 기록돼도 티커별로 정확히 분리 | 동 테스트 | ✅ 통과 |
| 5 | 고아 SELL(선행 BUY 없음)이 크래시 없이 처리 | 동 테스트 (`partial=true`, 손익금액 null) | ✅ 통과 |
| 5b | 수동 **부분 매도**가 한 포지션으로 누적 | 동 테스트 (4+6 분할 매도 → 1건, 가중평균 116/16.0%) | ✅ 통과 |
| 5c | 부분 매도 후 잔량 보유는 미청산 | 동 테스트 (`open=true`, 확정손익 null) | ✅ 통과 |
| 5d | 앞이 잘린 그룹은 partial 로 손익 비움 | 동 테스트 (`truncatedHead=true`) | ✅ 통과 |
| 5e | truncated 경계 — 정확히 상한이면 잘린 것 아님 | `TradeHistoryControllerTest` 2건 | ✅ 통과 |
| 6 | 전체 테스트 통과 | `./gradlew build` (테스트 12종) | ✅ BUILD SUCCESSFUL |
| 7 | 타입체크 통과 | 위 build 에 포함 | ✅ BUILD SUCCESSFUL |
| 8 | 프론트가 읽는 필드명과 와이어 포맷이 일치 | 직렬화 계약 테스트(17개 snake_case 키) | ✅ 통과 |
| 9 | 화면에서 라운드트립 탭이 렌더 | 하네스 + 브라우저 육안 | ❌ **미검증** — 브라우저 접근 실패 |
| 10 | 문서 동기화 | README API 표 · wiki sources 조회 | ✅ README 갱신, wiki 해당 페이지 없음 |

추가 검증(표 밖): JSX 문법 — `npx esbuild screens.jsx` 파싱 성공(61.0kb, 오류 0).
babel-standalone 이 브라우저에서 트랜스파일하는 구조라 빌드 타임 검증이 없어 별도로 돌렸다.

# Blockers

**acceptance 9(화면 렌더 관찰) 미충족** — Chrome 확장으로 `http://localhost:8899` / `http://127.0.0.1:8899` 접근이
2회 모두 `Frame is showing error page` 로 실패했다. 같은 URL 을 `curl` 로 부르면 200 이므로 서버는 정상이고,
확장의 사이트 권한(localhost 미허용)이 원인으로 보인다(⚠️추정).
푸는 데 필요한 것: 확장에서 localhost 사이트 권한 허용, 또는 사용자가 직접 브라우저로 하네스 URL 열어 육안 확인.

세션 환경 이슈 2건이 **작업 외적으로** 존재 (이 plan 의 범위 아님):
- 프로젝트 PreToolUse agent 훅의 `if` 가 fail-open 이라 무관한 Bash 명령을 차단한다. 수정본은 준비됨(`scratchpad/settings.fixed.json`), 적용은 사용자 몫.
- `.claude/settings.json` 첫 훅이 `shell: "powershell"` 인데 이 macOS에 powershell 이 없어 **시크릿 스캔 게이트가 죽어 있다.**

# Review Disposition

- **defer** — [codex 3차 P1] 수동 매도가 요청 수량을 기록해 잔량 계산이 조기에 0 이 될 수 있다.
  기록 경로의 **기존 결함**이고 고치려면 실거래 주문 경로에 체결 폴링을 넣어야 해 조회 기능 범위 밖 → `# Deferred`.
- **fix** — [codex 3차 P2] 화면 건수: `rtList.length`(limit 으로 잘린 길이)를 써서 100건을 넘으면 항상 '총 100건' 으로
  보였다. API 의 `total` 을 쓰도록 수정.
- **fix** — [codex pre-push P1] **부분 매도 미고려 (설계 가정 오류)**: 모든 SELL 을 포지션 종료로 처리해
  수동 부분 매도 시 라운드트립·손익이 틀렸다. 수량 잔량 기반 판정으로 재구현(위 Decisions 정정).
  테스트 2건 추가(분할 매도 전량청산 / 부분 매도 후 잔량 보유). DTO 에 `sellCount` 추가, 화면에도 표기.
- **fix** — [codex pre-push P2] 앞이 잘린 그룹의 `partial` 누락: 조회 상한으로 분할 매수 중간이 잘리면
  남은 첫 BUY 만으로 평단이 계산돼 정상처럼 보였다. `assembleRoundTrips(truncatedHead)` 로 각 티커의
  가장 오래된 그룹을 `partial` 처리하고 손익 금액을 비운다. 테스트 1건 추가.
- **fix** — [codex pre-push P3] `truncated` 경계 오류: `records.size >= MAX_SOURCE_RECORDS` 는 결과가
  *정확히* 상한일 때도 잘렸다고 표시했다. 상한+1 건을 받아 `> MAX` 로 판정하고 넘칠 때만 `takeLast(MAX)` 하도록 수정.
  회귀 방지 테스트 2건 추가(`TradeHistoryControllerTest`). 상수는 테스트 접근을 위해 `internal companion` 으로 노출.

# Deferred

- **수동 매도가 체결량이 아니라 요청 수량을 기록한다** — **이슈 #105 로 분리** (codex P1 · 기록 경로 기존 결함 · `TradeExecutionService.kt:152-157`):
  `executeSellVolume` 은 주문 후 체결을 확인하지 않고 요청한 `sellVolume` 을 그대로 `trade_records.volume` 에 넣는다
  (`recordOrder` 도 `orderUuid` 를 로깅·반환에만 쓴다). 엔진 경로(`PositionManager.completeBuy`)가 실제 잔고·체결량을
  쓰는 것과 다르다. 부분 체결·미체결이 나면 이 조회의 **잔량 계산이 조기에 0 에 도달해 아직 보유 중인 포지션이
  청산된 것처럼** 보인다. 고치려면 수동 매도에 체결 폴링(엔진의 `FILL_POLL_ATTEMPTS` 류)을 넣어야 하는데
  **실거래 주문 경로 변경**이라 조회 기능 범위 밖이고, 과거 데이터는 어차피 소급되지 않는다.
  시장가 매도는 통상 즉시 전량 체결이라 실제 빈도는 낮을 것으로 보이나(⚠️추정) 확인된 바 없다.

- **`trade_records` 중복 행 가능성** (경미 · 기존 데이터 품질 · `TradeExecutionService.saveAudit`):
  멱등 dedup 이 `exchangeOrderId` 기준인데 이 값이 **null 인 과거·수동 기록은 dedup 대상이 아니다**(`TradeRecord.kt:13` 주석에 명시).
  중복 BUY 가 섞여 있으면 조립된 평단이 왜곡된다. 조회 로직이 만든 문제가 아니라 원 데이터 이슈라 이번 범위 밖.

# Workflow Findings

- 2026-08-22: PreToolUse agent 훅 오탐으로 무관한 Bash 명령 3건 차단(운영 DB 조회·파일 쓰기). 원인은 `if` 필터의 fail-open(`$VAR`/`$()`/파싱실패 시 무조건 실행) + agent 가 "대상 아님"을 빈 응답 대신 텍스트로 출력. 사용자 명시 지적 있음 → 기록 대상.
