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
