# Wiki 변경 이력

형식: `## [YYYY-MM-DD] <ingest|lint|query> | <요약>`

---

## [2026-07-28] ingest | 부트스트랩 — 25페이지 초기 적재

`wiki/` 신설. 규약(`WIKI.md` schema 1)·index·log·pages 골격 생성.

- **concept 7**: architecture-overview, trading-engine-loop, exit-gates, swing-strategies, marketdata-pipeline, persistence-schema, backtest-engine
- **decision 8**: rightsizing-history, migration-numbering, plan-git-tracking, worktree-workflow, prepush-codex-review, docs-code-sync, github-issues-backlog, strategy-evolution-expectations
- **decision/lesson 7**: `docs/lessons.md` 6항목에서 이관 (EC2 항목은 3주제가 섞여 있어 sizing / deploy-script 로 분리)
- **entity 3**: upbit-api, jdk-gradle-toolchain, deployment-stack

출처: 코드 실측(TradingEngine·PositionManager·ExitGates·BacktestEngine·MarketDataIngestionService·pre-push hook·build 설정), `docs/lessons.md`, plan 15개, GitHub 이슈 #49, PROJECT_ANALYSIS.md.

`source/`·`raw/` 는 비움 — 이관 대상이 전부 repo 내 tracked 문서라 원문 보존 대상이 아니다(`WIKI.md` §5).

### 소스 커버리지

무엇이 빠졌는지 숨기지 않기 위해 남긴다.

| 소스 | 처리 |
|---|---|
| `docs/lessons.md` 6항목 | **ingested** — 7페이지로 분리 이관, 원본은 포인터로 축소(원문 커밋 `331426f`) |
| `PROJECT_ANALYSIS.md` | **ingested** — concept 페이지들의 근거로 사용. 단 수치는 코드로 재확인(과거 오기재 전례) |
| plan: strategy-evolution-loop | **부분 ingested** — 확정 합의·머지된 정합 개선만. 진행 상태는 plan 소유 |
| plan: trading-state-durability, marketdata-consolidation, docs-sync, ec2-tls-caddy 등 종결분 | **ingested(주제 재구성)** — 결정·교훈이 concept/decision 페이지에 녹아 있음. 페이지 1:1 대응은 없음 |
| plan: engine-lifecycle, ops-safety-net, order-state-integrity, test-hardening, dead-path-cleanup, stock-bot-kis, stock-quant-strategy, prepush-codex-hardening | **intentionally-skipped** — `in_progress`. 상태는 plan·이슈 소유(`WIKI.md` §1) |
| memory 13개 | **부분 ingested** — repo 사실(JDK25 비호환·pre-push·worktree 정리·merge 자동정리)은 페이지화. 세션 핸드오프·전역 작업방식 memory 는 대상 아님 |
| GitHub 이슈 | **intentionally-skipped** — 열린 이슈 상태는 이관 금지. #49 등은 포인터로만 참조 |
| `docs/superpowers/specs/`, `docs/superpowers/plans/` | **skipped(이번 범위 밖)** — 작성 시점 고정 설계 스펙. 필요 시 후속 ingest |

### 검증 결과

- `check_links.py` → clean (초기 orphan 2건 수정 후)
- `wiki/verify.sh` → clean (25페이지, stem 유니크·정규식·frontmatter 값)
- `wiki/smoke.sh` → 9/9 pass. **음성 질의가 실제로 위반 1건을 잡았다** — `migration-numbering` 이 특정 브랜치의 번호 선점 상태를 적고 있어 §1(백로그 침범)에 걸렸고, 확인 절차로 교체했다.

---

## [2026-08-02] ingest | KIS 주식 자동매매·주문 수명주기

현재 KIS 코드를 기준으로 재사용 가능한 두 흐름을 영속 페이지로 기록했다.

- `kis-stock-trading-flow`: `/api/stock/bot/start` → 사용자별 엔진 → 장시간·시세·신호·포지션 수량 계산 → durable 포지션 메타데이터.
- `kis-order-lifecycle`: 자동·수동 공통 `StockOrderService` → 검증 → WAL tx1 → KIS 송신 → 상태 tx2 → 당일 체결조회 reconcile → audit/`NEEDS_REVIEW`.
- `architecture-overview`를 현재 코드의 Upbit·KIS 이중 경로와 KIS 패키지에 맞게 보정했다.
- `persistence-schema`를 V14 기준에서 V18 기준으로 갱신하고 V15~V18 KIS 주문·키·포지션 마이그레이션을 추가했다.

근거: KIS controller/engine/marketdata/order/client/reconcile 구현, `application.yml`, V15~V18 migration. Wiki 구조·frontmatter·링크 검증은 ingest 후 실행한다.

---

## [2026-08-03] ingest | lesson-llm-alpha-verification 1페이지 추가

"LLM 을 트레이딩에 붙여 수익을 낼 수 있나" 조사 결과를 적립. 구현은 하지 않았고 검증 설계만 고정했다.

- **decision/lesson +1**: `lesson-llm-alpha-verification`
- `rightsizing-history` 에 inbound 링크 1줄 추가(기각 사유 → 재도입 조건). 이 페이지의 고아 방지도 겸한다.

핵심 주장과 근거:

| 주장 | 근거 |
|---|---|
| 기각 사유는 미검증이지 반증이 아님 | `rightsizing-history.md:22` 인용 |
| 원 구현 복원 불가 | `git log --all --diff-filter=D` 231커밋 전수 → LLM 관련 경로 0건 |
| 지표는 전부 가격 파생 → LLM 재입력은 정보량 0 | `CombinedStrategy.kt` 전문(3조건) |
| 과거 백테스트 무효 | 모델 학습 오염은 `BacktestEngine.kt:98`(다음 봉 시가 체결)이 막는 층위가 아님 |
| 텍스트 수집 경로 부재 | `common`·`bot/src/main` grep 0건, 스키마에 텍스트 테이블 없음 |
| 구독 토큰으로 서버 자동화 불가 | Anthropic Consumer Terms — API 키 외 자동·비인간 접근 금지 |

## [2026-08-24] ingest | 배포 자동화 실측 + 검증 지점 일반화 교훈(코드 분기)

`sell-strategy-attribution` 작업(PR #117, V21)에서 나온 재사용 지식 2건. 새 페이지 없이 기존 2페이지를 갱신했다 — 귀속 규칙 자체는 이미 [[persistence-schema]] 에 있고, 여기 적립한 건 그 규칙을 **어떻게 잘못 도출했는가**와 **배포가 언제 도는가**다.

| 페이지 | 무엇을 추가했나 | 근거 |
|---|---|---|
| [[deployment-stack]] | `main` 머지 = 즉시 배포 시작(사전 백업 창 없음), stale 가드로 그 PR 배포가 skipped 될 수 있음 | `.github/workflows/deploy.yml:61,75-87`, 2026-08-23 #117 실측 |
| [[lesson-single-point-verification]] | "검증 지점"에 **코드 분기**를 포함 — `resuming=false` 만 보고 일반화한 사례 | `TradingState.kt:95,109`, pre-push codex P1 |

`verified` 갱신: deployment-stack 은 live Actions 배포를 **처음으로 실제 관찰**(2026-08-23 03:40:44, Flyway v21)해 "merge 후 과제" 단서를 해소했다.

부수 수정: `index.md` 의 stale 2줄(`persistence-schema` V1~V20 → V1~V21, `deployment-stack` EC2 t4g.medium → Vultr vc2-1c-2gb).

## [2026-08-26] ingest | lesson-rollback-removal 1페이지 추가

V21 백업 테이블을 DROP 하려다 pre-push codex P1 지적으로 폐기한 건에서 나온 교훈. 근거 두 개가 모두 틀렸다 — "백업이 전부 NULL 이라 무가치"(NULL 이 바로 복원 대상 상태다)와 "배포 전 pg_dump 가 남아 있다"(확인하지 않고 썼고, 실제로 없다).

작업 자체의 현재 상태는 이슈 #137 이 소유한다(wiki 는 진행 상태를 담지 않는다). 여기 남긴 것은 재사용 가능한 판단 규칙뿐이다.

## [2026-09-01] ingest | DB 통합테스트 하네스 + skip 교훈 2페이지

#53(PR #153)에서 나온 재사용 지식. 코드 주석에만 있으면 다음 사람이 같은 3시간을 반복한다.

| 페이지 | 무엇을 남겼나 |
|---|---|
| [[db-integration-test-harness]] | Testcontainers 를 쓰지 않는 이유(docker-java API 1.32 vs Docker 29 의 min 1.40)와 대신 택한 외부 DB 방식. 효과 없던 우회 목록도 함께 — 다시 시도하지 않도록 |
| [[lesson-skip-is-not-pass]] | 조건부 skip 이 초록불로 위장한 건. gradle 이 성공 시 조용해 CI 로그로 실행 여부를 관찰할 수 없었다 |

진단의 핵심은 "docker 가 안 뜬다"가 아니라 **API 버전 프리픽스를 붙여 `/info` 를 직접 호출**해 400 나는 버전을 찾은 것이다.

## [2026-09-02] ingest | #112 fixture 교체 반영 — 교체 전 측정치 historical 처리

| 페이지 | 변경 |
|---|---|
| [[reset-churn-measurement]] | 시점 중립 fixture 로 #128 반사실 재측정 — "조건부 리셋만 부호 일관" 결론 철회 |
| [[universe-look-ahead-audit]] | 대조표의 "현재 로스터"가 감사 시점(교체 전) 로스터임을 명시, 결론은 유지 |
| [[swing-strategies]] | 무릎 백테 판정을 교체 전 fixture 기준 historical 로 표기 — 새 fixture 재실행은 미완 |

fixture 를 `sources` 로 둔 페이지는 fixture 가 바뀌면 "현재 결과"로 읽히는 수치를 재측정하거나 historical 로 표기해야 한다 — 이번엔 #128 만 재측정했고 무릎 비교는 표기만 했다.

## [2026-09-02] ingest | accumulate-ladder 1페이지 추가 + 3페이지 갱신

메이저 코인 사다리 매매 프로파일과 알트 유니버스 자동 선정(기본 off) 구현을 적재.

| 페이지 | 변경 |
|---|---|
| [[accumulate-ladder]] | 신규 — 사다리 규칙·원자 전이·체결 비율 조건부 rung·정합 매퍼·현금 경쟁·유니버스 교체·백테 프로파일·forward-off 롤백 |
| [[trading-engine-loop]] | `processTicker` 프로파일 dispatch·`applyTickers`·`reservedKrw` 절 추가 |
| [[exit-gates]] | 적립 티커는 게이트 미적용 명시 |
| [[persistence-schema]] | V23 행, 최신 V23 |

출처: 코드 실측(AccumulateLadder·AccumulateBacktest·TradingEngine·PositionManager·LadderStateMapper·UniverseSelector), V23 을 실제 Postgres 에 적용(`scripts/run-db-tests.sh` 3건/skip 0), `AccumulateBacktestTest` 격자 출력, spec `docs/superpowers/specs/2026-09-02-accumulate-ladder-design.md`. 진행 상태는 plan `2026-09-02-accumulate-profile` 소유.

## [2026-09-03] query | yearly-strategy-comparison — 운영 8종 1년 전략 비교 1페이지 추가

fixture `yearly/`(8종 × 365봉, 2025-09-03~2026-09-02) 위에서 스윙 9종(재진입 2모드)·적립 사다리·단순보유를 고정 노셔널 예산 대비 순수익률·봉단위 equity MDD·노출로 비교. 출처: `YearlyStrategyComparisonTest`(`RUN_YEARLY_COMPARE=true`) 산출물, plan `2026-09-03-yearly-strategy-compare`. 진행 상태는 plan 소유.

## [2026-09-03] ingest | pre-push codex 게이트 제거
- [[prepush-codex-review]] 재정의(current): deploy.yml 가드만 남김 + 제거 이유·롤백·설치본 5주 드리프트. [[jdk-gradle-toolchain]]·[[worktree-workflow]] 의 push 게이트 서술 정정, [[plan-git-tracking]] 의 "pre-commit 이 plans 를 스캔" 주장은 이 clone 에 pre-commit 이 없어 철회. index·smoke.sh 갱신.
- 근거: scripts/git-hooks/pre-push 슬림본 12케이스 실행, `.git/hooks/pre-push` 338줄 vs 정본 381줄 diff, PR #165, ~/.claude PR #156.

## [2026-09-08] query | trailing-width-2026-09 — #184 트레일링 폭 240분봉 사전고정 재판정 1페이지 추가 + 3페이지 갱신

- [[trailing-width-2026-09]] 신설: 10창(yearly·bear 제외) 라이브 의미론 계기에서 0.75·1.00·1.25 전부 사전고정 통과, 단조성은 계기 편향과 같은 방향이라 승격 아님. 판별 경로 = 그림자 관측 1.0.
- [[trailing-arm-finding-2026-09]] 한계 절에 후속 링크. [[trading-engine-loop]] 8번·[[exit-gates]] 재진입 문단에 09:00 경계 stale-window 가드(`hasCurrentDayCandle`) 반영.
- 근거: `TrailingWidthIntradayTest`(`RUN_TRAILING_WIDTH=true`) 산출물, `TradingEngineTest` 재현 테스트 Red→Green, 사전고정 커밋 `52c38c7`. 진행 상태는 plan 소유.

## [2026-09-09] query | take-profit-stop-loss-2026-09 — 익절×손절 20셀 사전명세 비교 1페이지 추가 + sources 3페이지 verified 갱신

- [[take-profit-stop-loss-2026-09]] 신설: 진입일 1:1 페어링·전 거래일 이동블록·셀 공통 draw 의 단일단계 maxT(FWER 5%)로 19셀 동시 판정. 통과 9(익절 8·off 8셀 + TP5/SLoff), 후보 0 — 익절 축은 계기 편향 순방향이라 사전고정이 후보 제외, 손절 off 는 진입봉 손절을 종가로 판정하는 감도에서 미통과. 익절 3·손절 3 은 편향 무관 열세.
- `LiveSemanticsArm` 에 `entryBarStopOnClose`·진단 필드 추가(기본값 종전 동작, `RUN_TRAILING_WIDTH` 리포트 md5 동일) → 그 파일을 sources 로 가진 [[trailing-width-2026-09]]·[[exit-resolution-verdict-2026-09]]·[[trailing-arm-finding-2026-09]] verified 갱신.
- 근거: `TakeProfitStopLossIntradayTest`(`RUN_TP_SL_GRID=true`) 산출물, 사전고정 커밋 `a04ab34`(plan-reviewer 2회·codex 1회 검토 반영). 진행 상태는 plan 소유.

## [2026-09-09] query | exit-resolution-ladder-2026-09 — 240→15→5분봉 해상도 사다리 1페이지 추가 + sources 4페이지 verified 갱신

- [[exit-resolution-ladder-2026-09]] 신설: 익절·손절·9시 유지 정책 7셀을 15분·5분봉으로 재판정(공통 frame·maxT·브래킷 family 2종·수렴 규칙 사전고정 `daf16c1`). 5분봉 통과 0 — 240분봉 우위는 계기 편향(TPoff +0.195 → +0.043 → +0.010/거래). 기준선 자체가 240분 +235.9 → 5분 −219.4%p 로 뒤집힘(진입 1,058 → 1,767건).
- `LiveSemanticsArm` 에 window 지연 생성·`keepWinnersUntilDays`·`pessimisticTrailing`·`Trade.keptPastLimit` 추가(기본값 종전 동작, md5 동일) → sources 인 [[trailing-width-2026-09]]·[[exit-resolution-verdict-2026-09]]·[[trailing-arm-finding-2026-09]]·[[take-profit-stop-loss-2026-09]] verified 갱신. 수집기 `--unit`·`backtest-cache/`(저장소 밖) 규약은 [[upbit-api]] 와 `scripts/collect_intraday_fixtures.py` docstring.
- 리뷰(code-reviewer + codex) 정정 반영: [[take-profit-stop-loss-2026-09]] 의 "진입봉 유령 손절" 프레이밍은 코드와 어긋난다(`combined` 가 현재가 ≤ 돌파선을 거부해 체결은 항상 봉 시가 → 진입 봉 저가는 체결 이후). 답·손절 축·읽는 법·index 한 줄을 정정. 사다리 페이지의 진입 메커니즘·결측 허용치 방향·막힌 진입 정의도 같은 리뷰로 보강.
- 근거: `ExitResolutionLadderTest`(`RUN_EXIT_LADDER=true`) 산출물, plan-reviewer 1회(NO-GO 반영)·code-reviewer·codex 검토. 진행 상태는 plan 소유.

## [2026-09-09] decision | lesson-bracket-needs-fill-semantics — 사다리 리뷰가 반증한 브래킷 전제를 교훈으로

- [[lesson-bracket-needs-fill-semantics]] 신설: 감도 family 를 "유령 손절 브래킷"으로 설계하면서 계기의 체결 규칙(`combined` 가 현재가 ≤ 돌파선 거부 → 체결은 항상 봉 시가)을 코드로 확인하지 않은 것, 비관 브래킷이 경로 가정과 모순되는 청산을 낸 것. 3 Whys·올바른 방법(체결 규칙 한 줄 + 경로 가정별 단위테스트). 사용자 승인 후 적립(§13).

## [2026-09-16] ingest | KIS(한국투자증권 국내주식) 경로 제거 반영

- [[kis-stock-trading-flow]]·[[kis-order-lifecycle]] 삭제 — 서술 대상 코드(`bot/.../kis/`·`/api/stock|kis/*`·주식 화면)가 사용자 결정으로 통째로 제거됐다(V27 이 `stock_order_intent`·`stock_position_state`·`users.kis_*` 를 DROP).
- [[architecture-overview]]·[[exit-gates]]·[[marketdata-pipeline]]·[[trading-engine-loop]]·[[persistence-schema]]·[[plan-git-tracking]]·[[rightsizing-history]] 에서 KIS 서술을 제거·정정하고 V27 행을 추가.
- 근거: 삭제 diff, `./gradlew build` 통과. 진행 상태는 plan 소유.

## [2026-09-16] ingest | shared-balance-2026-09 — 공유 잔고 재판정 (#180)

- [[shared-balance-2026-09]] 신설: `LiveSemanticsArm` 결과 위에 한 계좌 현금 장부(`SharedBalanceSim`, 후처리)를 얹어 현행 라이브 vs 후보 E 를 yearly·7국면에서 재판정. 라이브 사이징 규칙(taper·복리)과 슬롯 규칙(동시 보유 상한, 마켓 순서 200 치환)의 두 축, 주 통계량 셀은 결과 전 사전고정.
- `LiveSemanticsArm.Trade` 에 진입·청산 봉 시각 필드 추가(동작 불변) → 그 파일을 sources 로 가진 query 페이지들의 `verified` 갱신. [[exit-resolution-verdict-2026-09]]·[[trailing-arm-finding-2026-09]] 한계 절에 포인터.
- 근거: `RUN_SHARED_BALANCE=true` 리포트, `SharedBalanceSimTest` 10건, 전체 `:bot:test` 통과. 진행 상태는 plan 소유.

## [2026-09-16] ingest | trailing-resolution-2026-09 · entry-set-decomposition-2026-09 — 240분봉 계기 판정의 해상도 재검토 (#189, #190)

- [[trailing-resolution-2026-09]] 신설: 트레일링 1.5/arm0 승격 근거를 240→15→5분봉 사다리로 재판정(규칙은 결과 전 커밋, 240분 배관 +110.37/758 정확 재현). 주 셀 격차/거래 +0.124 → +0.004 → −0.027, 5분 미통과·비관 브래킷 −173 → 사전고정 해석 (c): 승격 근거 미지지, 되돌릴 근거 아님.
- [[entry-set-decomposition-2026-09]] 신설: 5분봉 기준선 부호 반전(−455%p)의 분해 — 공통 진입 1,058건은 5분에서 +427 좋아지고(체결가 +819·청산 −392), 격차는 전부 5분 전용 진입 709건(−882)에서 온다. 240분 전용 0건(⊆ 실측).
- [[trailing-arm-finding-2026-09]]·[[exit-resolution-ladder-2026-09]] 한계 절에 포인터. 근거: 두 드라이버 리포트, 항등식·배관 단정, 전체 `:bot:test`. 진행 상태는 plan 소유.


## [2026-09-16] ingest | entry-resolution-vs-live-2026-09 — 라이브 진입 vs 240/15/5분봉 계기 대조 (#190 잔여)

- [[entry-resolution-vs-live-2026-09]] 신설: 운영 `combined` 매수 76건(07-14~09-14)을 계기 진입과 (마켓, UTC 일) 로 대조. 240분 F1 0.536·라이브가 0.97%p 싸게 삼(다른 계기), 15분=5분 진입 집합 동일(64건, 대칭차 0)·F1 0.743, 공통 표본 median(|Δ|) 5분 0.21 < 15분 0.37 < 240분 1.22. 사전고정 판정(F1 최대 ∧ 가격 최소, 결과 전 커밋 `ffe32d5`)은 F1 동률로 유보. 라이브 전용 24건 중 13건이 09:00 KST 직후 — 리셋 직후 전일 돌파선 진입 가설(미확인, 이슈 분리).
- [[entry-set-decomposition-2026-09]] 한계 절의 "라이브 대조 부재"를 포인터로 교체. 라이브 거래 원문은 저장소 밖 캐시(public repo) — 페이지는 집계만. `verify.sh` 페이지 수 43±2 → 46±2. 진행 상태는 plan 소유.

## [2026-09-16] ingest | breakout-entry-filters-2026-09 — combined 진입 필터 family 사전고정 판정 (#208)

- [[breakout-entry-filters-2026-09]] 신설: 계기 `LiveSemanticsArm` 에 `EntryFilter`(기본 NONE = 기존 경로, 시가 규칙) 옵션을 더해 9셀을 5분봉 10창·진입일 기여·maxT 로 판정(규칙 결과 전 커밋 `4bdfae2`). 통과 0·후보 0 → 현행 유지. §2 분해: 지연은 제거 손실 ≈ 체결가 비용, 마진 1% 는 −964 제거 vs −832 체결가 비용, 마감 12h 는 오후 진입 696건 합계 −84. 예측(지연 양수·마진 음수)이 반쯤 틀림 — 기록.
- [[entry-set-decomposition-2026-09]]·[[entry-resolution-vs-live-2026-09]] "다음" 포인터 추가. `verify.sh` 페이지 수 47(46±2 안). 진행 상태는 plan 소유.

## [2026-09-16] ingest | lesson-seed-vs-stream-overwrite · marketdata-pipeline 갱신 — 재시작 뒤 전일 D1 절단 결함 (#209, #27 C3)

- [[lesson-seed-vs-stream-overwrite]] 신설: `CandleAggregator` 가 period 를 처음 볼 때 M1 하나로 새 봉을 만들어 upsert → 부팅 seed 의 완전한 D1 이 재시작 이후 구간만 남은 봉으로 대체 → 다음날 돌파선 붕괴(라이브 00h 매수 17건 중 7건). `seedDailyCandles` 가 오늘(UTC) D1 을 `CandleAggregator.prime` 으로 등록해 첫 M1 이 이어받게 수정(store 읽기 없음, 재현 테스트 Red→Green). #27 C3 원안(D1 집계 제외+재폴링)은 재폴링 실패 시 store D1 정지·`evaluateChartExit` current-day 가드 부재 때문에 기각; "집계기가 store 를 읽어 병합" 안은 축출 period 재유입 시 이중 계상으로 기각.
- [[marketdata-pipeline]] `candleBuffers` 절에 병합 규칙·volume 겹침 한계 추가, sources 에 CandleAggregator. 진행 상태는 plan 소유.

## [2026-09-16] ingest | reentry-premium-2026-09 — 리셋 뒤 재진입 프리미엄 분해·대조 (#143 대체, #144 입력)

- [[reentry-premium-2026-09]] 신설: 5분봉 10창 재진입 300쌍 프리미엄 중앙값 +2.16% vs 대조군 +2.77%(돌파선 성분 1.86 vs 2.29, 오버슈트 0.20 vs 0.26, 10/10 창 부호 일치) — 리셋 고유의 가격 불이익 없음, #128 헤드라인은 돌파 진입 프리미엄. 단 왕복 비용(재진입 다리)은 남고 D1 반사실은 0 으로 그린다(≈0.8%p/TIME_EXIT). 기간정합 계기(2026-07~09) 10쌍 +1.68 vs 54건 +1.76. 라이브 12쌍 중앙값 +2.84% — #209 B 오염은 갭을 낮추는 방향이라 원인이 아니고 소표본으로 미해석. 지표 결과 전 커밋 `e0506ae`, 리뷰 반영(분위·경계·B 서명) 후속 커밋.
- [[reset-churn-measurement]] 미측정 성분 1 에 포인터. #143 M1 fixture 기각. 진행 상태는 plan 소유.

## [2026-09-17] update | marketdata-pipeline — M1 폴링 count=5 + 집계기 분 단위 멱등 (#27 C3 잔여)

- [[marketdata-pipeline]] `candleBuffers` 절에 결손의 실제 크기(`count=1` 은 진행 중 분봉만 → 각 분의 완결본을 영영 못 받아 상위봉 volume 평균 절반·레인지 좁음, 부팅일 무관 상시)와 수정(`M1_FETCH_COUNT=5` 오름차순, `CandleAggregator` base/provisional/lastFolded, `prime(candle, fetchedAt)` 로 seed 겹침 1분 고정, seed 시점 `startFrom` 으로 모든 interval 에 period 바닥) 추가. 잔여: 4분 넘는 폴링 공백.
- [[lesson-seed-vs-stream-overwrite]] 재발 감지 절에 상시판 포인터. 진행 상태는 plan 소유.

## [2026-09-17] update | trading-engine-loop · upbit-api — buy() 귀속 불명 lock 가드 + wait 부분체결 관측 (#121 · #120)

- [[trading-engine-loop]] 2번 항목: 런타임에 생긴 lock 은 `buy()` 가 사이징 잔고에서 같은 판정(추가 호출 없음)을 해 `unsynced` 로 넘긴다. 운영 로그는 컨테이너 재생성마다 사라져(json-file 10m×3) 발생 빈도는 판단 불가 — 가드가 무료라 빈도와 무관하게 넣었다.
- [[upbit-api]] 주문 절: `wait` 부분체결 시 `remaining_volume` 갱신은 공식 문서 명시 없음 → 봇이 info 로그로 근거를 쌓는다. #120 의 상한 조이기는 그 근거가 생기면.
