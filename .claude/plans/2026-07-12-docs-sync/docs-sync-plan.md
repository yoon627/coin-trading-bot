---
title: docs-sync — 실거래 안전 관련 문서-코드 모순 4건 해소 (리스크 수치 정반대 등)
status: in_progress
started: 2026-07-12
updated: 2026-07-12
---

# Goal

감사(docs 관점)에서 확인된 문서-코드 모순 4건을 해소한다. 특히 PROJECT_ANALYSIS.md 가 실거래 리스크 파라미터를 **정반대로**(손절 -3%/익절 +5% — 실제는 익절 +2%/손절 -5%) 기재하고 라이브에 없는 MA50 매수 차단을 있다고 서술하는 건은 운영자의 리스크 오인으로 직결된다.

# Progress

- 2026-07-12: 감사 v3(docs finder 완주, 검증 agent 는 쿼터로 실패) 발견 4건을 메인 세션 grep spot-check 로 전건 확인 후 plan 작성. 구현 미착수. 확인 내역: PROJECT_ANALYSIS.md:105 vs TradingProperties.kt(takeProfitPct=2.0·maxLossPct=5.0·maxHoldDays=1), common/ 에 indicator/ 패키지 부재(README:75 는 기재), monitoring/ 6파일 git 추적 잔존(README:5 는 "제거됨"), README 에 DISCORD_ERROR_* 0건(.env.example 에만 존재).
- 2026-07-12: codex 검토(medium, read-only) 반영 — SellReason 은 6종(TradeRecord.kt:22-28, **MANUAL 포함** — 수동 매도 기록이 사용: TradeExecutionService.kt:107-115)이라 "자동 매도 사유 5종 + MANUAL" 로 정정. monitoring/ 삭제의 compose/스크립트 소비자 없음 재확인. 추가 발견(perf/load-test.js 가 /actuator/prometheus 기대 vs actuator 는 health,info 만 노출)은 #25 소유라 # Deferred 로. 리스크 수치·common 트리 정정은 codex "no objections". Claude plan-reviewer 는 쿼터 소진으로 생략(§9 사유 기록).

# Next

착수 시 4건을 한 브랜치에서 일괄 수정 (전부 S, 문서만):
1. PROJECT_ANALYSIS.md §5 리스크 관리 → README 132-145(코드와 일치 확인됨) 기준으로 갱신, MA50 은 백테스트 전용 명시.
2. README:75·PROJECT_ANALYSIS:53 common 트리 → 실제 구조(config/·domain/·strategy/, Indicators 는 strategy/ 거주)로, 컨트롤러 개수(10+Auth) 정정.
3. monitoring/ 디렉토리 삭제(README "제거됨" 서술과 일치시키는 최소 수정 — git 이력 보존, 죽은 설정 6파일).
4. README Discord 섹션: 매도 사유 — 자동 5종(TAKE_PROFIT/TRAILING_STOP/STOP_LOSS/CHART_EXIT/DAILY_RESET) + 수동 MANUAL, 총 6종(TradeRecord.kt:22-28) + 환경변수 표에 DISCORD_ERROR_ALERT_ENABLED·DISCORD_ERROR_WEBHOOK_URL 추가.

# Decisions

- monitoring/ 은 "미사용 보관" 주석이 아니라 **삭제** — README·PROJECT_ANALYSIS·compose 주석 모두 "제거됨"이라 서술과 실체를 일치시키는 쪽이 최소 수정(죽은 코드는 삭제, git 이 기억 — §6). 유지 의사가 있으면 착수 시 사용자 확인.
- 수치의 진실 소스는 코드(TradingProperties + application.yml) — README 는 이미 일치하므로 README 를 기준 사본으로 사용.
- 이 작업은 문서 전용이라 dead-path-cleanup(코드 경로 정리)과 독립 — 다만 dead-path-cleanup·marketdata-consolidation 머지 후 그 변경분의 문서 반영은 각 plan 의 몫(이 plan 은 현재 시점 모순만).

# Key Files

- `PROJECT_ANALYSIS.md` — :105(리스크 수치), :53(common 트리), :59(컨트롤러 수), :21(모니터링)
- `README.md` — :75(common 트리), :81(컨트롤러 수), :132-145(리스크 기준 사본), :278(매도 사유), :280-302(환경변수 표)
- `monitoring/` — 삭제 대상 6파일(prometheus.yml·loki.yml·promtail.yml·grafana/provisioning/**)
- 대조 근거: `common/.../config/TradingProperties.kt:12-19`, `bot/.../domain/TradeRecord.kt:23-27`, `.env.example:24-27`

# Acceptance

- [ ] PROJECT_ANALYSIS 리스크 줄이 코드 기본값(익절 +2%/손절 -5%/트레일링 -2%/보유 1거래일)과 일치, MA50 백테스트 전용 명시
- [ ] 두 문서의 common 트리에서 indicator/ 제거·Indicators 위치 정정, 컨트롤러 수 정정
- [ ] `git ls-files monitoring/` 0건 + 문서에서 참조 0건
- [ ] README 에 매도 사유 6종(자동 5 + MANUAL)·DISCORD_ERROR_* 2행 존재 (`grep` 으로 확인)
- [ ] 문서 전용 변경이므로 빌드 무영향 — `git diff --stat` 이 md/monitoring 만 포함함을 확인

# Blockers

(없음)

# Deferred

- perf/load-test.js:105-109 가 `/actuator/prometheus` 를 기대하나 actuator 는 health,info 만 노출(application.yml:23-27) — k6 스크립트 정비는 open #25 소유(codex 발견, severity: low)
