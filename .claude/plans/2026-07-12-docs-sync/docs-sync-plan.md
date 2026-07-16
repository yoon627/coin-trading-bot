---
title: docs-sync — 실거래 안전 관련 문서-코드 모순 4건 해소 (리스크 수치 정반대 등)
status: done
started: 2026-07-12
updated: 2026-07-16
---

# Goal

감사(docs 관점)에서 확인된 문서-코드 모순 4건을 해소한다. 특히 PROJECT_ANALYSIS.md 가 실거래 리스크 파라미터를 **정반대로**(손절 -3%/익절 +5% — 실제는 익절 +2%/손절 -5%) 기재하고 라이브에 없는 MA50 매수 차단을 있다고 서술하는 건은 운영자의 리스크 오인으로 직결된다.

# Progress

- 2026-07-12: 감사 v3(docs finder 완주, 검증 agent 는 쿼터로 실패) 발견 4건을 메인 세션 grep spot-check 로 전건 확인 후 plan 작성. 구현 미착수. 확인 내역: PROJECT_ANALYSIS.md:105 vs TradingProperties.kt(takeProfitPct=2.0·maxLossPct=5.0·maxHoldDays=1), common/ 에 indicator/ 패키지 부재(README:75 는 기재), monitoring/ 6파일 git 추적 잔존(README:5 는 "제거됨"), README 에 DISCORD_ERROR_* 0건(.env.example 에만 존재).
- 2026-07-12: codex 검토(medium, read-only) 반영 — SellReason 은 6종(TradeRecord.kt:22-28, **MANUAL 포함** — 수동 매도 기록이 사용: TradeExecutionService.kt:107-115)이라 "자동 매도 사유 5종 + MANUAL" 로 정정. monitoring/ 삭제의 compose/스크립트 소비자 없음 재확인. 추가 발견(perf/load-test.js 가 /actuator/prometheus 기대 vs actuator 는 health,info 만 노출)은 #25 소유라 # Deferred 로. 리스크 수치·common 트리 정정은 codex "no objections". Claude plan-reviewer 는 쿼터 소진으로 생략(§9 사유 기록).
- 2026-07-16: 착수·완료. 재대조: TradingProperties(takeProfit=2.0·maxLoss=5.0·trailing=2.0·maxHoldDays=1), SellReason 6종, MA50 은 BacktestEngine.kt:149-151 `useMarketFilter`(기본 off) 전용·라이브 매수경로 부재(StrategyController.kt:83 주석 근거), 컨트롤러 api/ 패키지 10개 + auth/AuthController 1 = 총 11(문서 '12개' 과다계상 → api/ 줄은 10개로, codex pre-push P3 반영), Indicators 는 strategy/Indicators.kt(indicator/ 패키지 없음), DISCORD_ERROR_* application.yml:65-66 실소비.
- 2026-07-16 **스코프 축소 (main refresh 충돌)**: push 후 origin/main 에 `8438303 docs: refresh README for current architecture`(README 448줄 리라이트)가 선착 머지된 것을 PR#35 머지충돌로 발견. 그 refresh 가 **내 README 변경을 전부 이미 반영**(리스크 수치·indicator/ 제거·컨트롤러 수·DISCORD_ERROR_*)했고 monitoring/ 을 **삭제 대신 "남아있음"으로 명시 문서화**(README:77). → 사용자 결정(AskUserQuestion "PROJECT_ANALYSIS만 남기기"): origin/main 머지, **README 는 main 버전 채택**(내 편집 폐기·superseded), **monitoring/ 삭제 취소·복원**(main 이 유지 문서화 — 삭제 시 새 모순), **PROJECT_ANALYSIS.md 수정 3건만 존치**. 머지 후 docs-sync↔origin/main 순수 diff = PROJECT_ANALYSIS.md(7줄) + 이 plan 뿐(README·monitoring net-zero 검증). 문서 전용·빌드 무영향.

# Next

착수 시 4건을 한 브랜치에서 일괄 수정 (전부 S, 문서만):
1. PROJECT_ANALYSIS.md §5 리스크 관리 → README 132-145(코드와 일치 확인됨) 기준으로 갱신, MA50 은 백테스트 전용 명시.
2. README:75·PROJECT_ANALYSIS:53 common 트리 → 실제 구조(config/·domain/·strategy/, Indicators 는 strategy/ 거주)로, 컨트롤러 개수(10+Auth) 정정.
3. monitoring/ 디렉토리 삭제(README "제거됨" 서술과 일치시키는 최소 수정 — git 이력 보존, 죽은 설정 6파일).
4. README Discord 섹션: 매도 사유 — 자동 5종(TAKE_PROFIT/TRAILING_STOP/STOP_LOSS/CHART_EXIT/DAILY_RESET) + 수동 MANUAL, 총 6종(TradeRecord.kt:22-28) + 환경변수 표에 DISCORD_ERROR_ALERT_ENABLED·DISCORD_ERROR_WEBHOOK_URL 추가.

# Decisions

- ~~monitoring/ 삭제~~ → **삭제 취소 (2026-07-16, 이유: main 8438303 refresh 가 README:77 에서 monitoring/ 을 "남아있지만 compose 미포함"으로 명시 문서화함. 삭제하면 그 서술이 대상을 잃어 새 모순 발생 — docs-sync 취지에 역행). 유지가 문서-실체 정합. 별도 삭제를 원하면 README:77 동반 수정하는 독립 작업으로.**
- ~~README 를 리스크 기준 사본으로 사용~~ → **README 변경 전부 superseded (2026-07-16, 이유: main 8438303 이 리스크 수치·indicator/·컨트롤러 수·DISCORD_ERROR_* 를 이미 반영). 이 브랜치는 origin/main README 를 그대로 채택, PROJECT_ANALYSIS.md 만 수정 존치.**
- 수치의 진실 소스는 코드(TradingProperties + application.yml).
- 이 작업은 문서 전용이라 dead-path-cleanup(코드 경로 정리)과 독립.

# Key Files

- `PROJECT_ANALYSIS.md` — :105(리스크 수치), :53(common 트리), :59(컨트롤러 수), :21(모니터링)
- `README.md` — :75(common 트리), :81(컨트롤러 수), :132-145(리스크 기준 사본), :278(매도 사유), :280-302(환경변수 표)
- `monitoring/` — 6파일 유지(삭제 취소). main README:77 이 "남아있음"으로 문서화
- 대조 근거: `common/.../config/TradingProperties.kt:12-19`, `bot/.../domain/TradeRecord.kt:23-27`, `.env.example:24-27`

# Acceptance

(스코프 축소로 최종 acceptance = PROJECT_ANALYSIS.md 만. README·monitoring 항목은 main 8438303 이 커버하여 이 브랜치 대상에서 제외.)

- [x] PROJECT_ANALYSIS 리스크 줄이 코드 기본값(익절 +2%/손절 -5%/트레일링 -2%/보유 1거래일)과 일치, MA50 백테스트 전용 명시 — PROJECT_ANALYSIS.md:104
- [x] PROJECT_ANALYSIS common 트리에서 indicator/ 제거·Indicators 를 strategy/ 로 정정, 컨트롤러 수 api/ 10개(+auth/ AuthController)로 정정 — `git diff` 확인
- [x] docs-sync↔origin/main 순수 diff = PROJECT_ANALYSIS.md(7줄) + 이 plan 뿐 (README·monitoring net-zero) — `git diff --stat origin/main HEAD` 확인
- [x] 문서 전용 변경이므로 빌드 무영향 (`.kt` 소스 0건)
- [~] ~~README 매도 사유·DISCORD_ERROR_*·common 트리~~ → main 8438303 이 이미 반영 (superseded)
- [~] ~~monitoring/ 삭제~~ → 취소 (main 이 유지 문서화)

# Blockers

(없음)

# Deferred

- perf/load-test.js:105-109 가 `/actuator/prometheus` 를 기대하나 actuator 는 health,info 만 노출(application.yml:23-27) — k6 스크립트 정비는 open #25 소유(codex 발견, severity: low). 같은 뿌리: SecurityConfig.kt:41 의 `/actuator/prometheus` permitAll 도 미노출 엔드포인트에 대한 죽은 규칙(2026-07-12 인라인 보안 점검 발견)
