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
