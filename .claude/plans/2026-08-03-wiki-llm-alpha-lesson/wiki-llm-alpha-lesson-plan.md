---
title: wiki-llm-alpha-lesson — LLM 트레이딩 알파 검증 설계를 wiki 에 적립
status: in_progress
started: 2026-08-03
updated: 2026-08-03
---

# Goal

"LLM 을 트레이딩에 붙여 수익을 낼 수 있나" 를 조사한 결과를 `wiki/pages/decision/lesson-llm-alpha-verification.md` 로 적립한다. 구현은 하지 않는다 — 이번 작업의 산출물은 문서 한 장과 그 동기화뿐이다.

# Progress

- 2026-08-03 조사: rightsizing 의 기각 사유가 "미검증"이지 "반증"이 아님을 확인. 원 구현은 231 커밋 전수 삭제이력에 없어 복원 불가. 뉴스/공지 수집 코드·스키마 부재 확인. `CombinedStrategy` 3조건이 전부 가격 파생임을 코드로 확인.
- 2026-08-03 페이지 작성 + `rightsizing-history` 에 inbound 링크 추가 + `index.md` 등재.

- 2026-08-03 검증 3종 통과 — `check_links` clean / `verify.sh` clean(26 pages) / `smoke.sh` 10 pass 0 fail(음성검사 포함). 인용 줄번호 5건 재대조 완료.

# Next

커밋 → push → PR. (작업 산출물은 완성·검증 완료 상태이며 남은 것은 머지 절차뿐.)

# Decisions

- **구현하지 않고 문서만 적립** (이유: 사용자 지시 — 검증 설계를 먼저 확정하고 구현은 별건).
- **페이지 1장으로 한정** (이유: 구독 vs API 과금 정책은 이 페이지의 격리 조건 절에 한 줄로만 넣는다. 별도 entity 페이지로 쪼개면 이 페이지 혼자서는 결론이 성립하지 않게 된다).
- **inbound 링크는 `rightsizing-history` 에 붙인다** (이유: 기각을 기록한 페이지가 재도입 조건을 가리키는 것이 의미상 정확하고, 고아 방지 요건도 함께 충족).

# Key Files

- `wiki/pages/decision/lesson-llm-alpha-verification.md` — 신규, 이번 작업의 산출물
- `wiki/pages/decision/rightsizing-history.md` — inbound 링크 1줄 추가
- `wiki/index.md` — decision / lesson 절에 등재
- `wiki/log.md` — ingest 이력 append

# Blockers

없음.

# Acceptance

| 항목 | 검증 방법 | 통과 기준 |
|---|---|---|
| 페이지가 규약 형식을 만족 | `check_links.py` | frontmatter 5키·outbound ≥2·dead link 0·orphan 0·index 동기화 전부 통과 |
| 검증기 미커버 불변식 | `bash wiki/verify.sh` | stem 중복·파일명 정규식·frontmatter 값 통과 |
| 대표 질문에 답이 되는가 | `bash wiki/smoke.sh` | 통과, 진행중 작업 상태 침범 없음 |
| 서술이 근거를 가짐 | `sources` 의 파일과 대조 | 페이지의 각 사실 주장이 sources 로 확인 가능 |

# Review Disposition

# Deferred

# Workflow Findings
