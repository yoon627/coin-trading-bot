---
id: 2026-05-29-fix-review-findings
title: 코드 리뷰 confirmed 54건 전부 수정 (실자금 트레이딩 봇)
status: active
created: 2026-05-29T22:50:01+0900
updated: 2026-05-29T22:50:01+0900
---

# Goal
멀티에이전트 리뷰(workflow w89i4yu1g)에서 adversarial 검증을 통과한 confirmed 54건
(critical 7 / high 9 / medium 20 / low 18)을 전부 수정한다. 실자금 트레이딩 봇이므로
거래 로직은 TDD(Red→Green)로, 각 wave 종료 시 빌드+테스트 게이트를 통과시킨다.
최종적으로 `TRADING_AUTO_START=true`로도 안전한 상태 + 배포 가능 상태가 목표.

# Acceptance criteria
- [ ] critical 7건 전부 수정 + 회귀 테스트
- [ ] high 9건 전부 수정
- [ ] medium 20 / low 18 수정 (또는 dead-scaffolding 은 사유 명시 후 제외)
- [ ] `JAVA_HOME=JBR21 ./gradlew test` 전체 통과
- [ ] `docker build` (arm64) 성공 유지
- [ ] 작업 단위로 commit (fix/review-findings 브랜치)

# Plan
- [x] Wave 0 — 배포 차단 (Dockerfile collector COPY, CI collector 빌드) — 완료, 커밋됨
- [x] Wave 1 — 핵심 거래 로직 (TDD): crit#1 재매수가드, crit#2 체결량/수수료, crit#3 fire-and-forget, crit#5 429 멱등, high investRatio, high 백테스트 look-ahead — 완료, 테스트 통과
- [ ] Wave 2 — 보안: crit#4 키 분리(SecretKeyMaterialProvider), crit#6 SSE 인증/캡(PriceStreamController), high rate-limit fail-open(RateLimitFilter), prod ssl 외부화
- [ ] Wave 3 — 영속성/동시성: TradeExecutionService tx, MarketDataPersistenceService upsert, TradeRecordRepository GROUP BY, DataRetentionService candles, MarketDataRepository, UpbitWebSocketClient reconnect/catch
- [ ] Wave 4 — API 검증: ChartController, BotConfigController, ManualTradeController, RequestValidators, UpbitErrorHandlerAdvice, StrategyController
- [ ] Wave 5 — 품질/low: Indicators 중복통합, RSI Wilder, query_hash, TZ, syncPosition, catch 로깅, scope.cancel, jacoco gate, 약한 PW, 테스트 sleep, h2 의존성 등
- [ ] Wave 6 — 최종 전체 빌드+테스트, 문서 동기화, 커밋 정리

# Progress log
## 2026-05-29T22:50 — Started
- 리뷰 결과(54건) `.claude/tasks/_review-findings.md` 로 추출.
- 파일 핫스팟: PositionManager(5), TradingEngine(4), UpbitWebSocketClient(3), ManualTradeController(3).
- Wave 0(배포 차단 2건)은 앞서 수정 완료(Dockerfile, deploy.yml). 배포 패키지(prod compose/deploy.sh/README)도 작성·검증됨.
- 브랜치 `fix/review-findings` 생성. main 미커밋 변경분이 이 브랜치로 이월됨.

## 2026-05-29T23:?? — Wave 1 완료
- crit#1: PositionManager.buy 에 position 가드 + TradingEngine 에 `!position && !boughtToday` 게이트 + markBought 가 boughtToday=true set.
- crit#2/#3: buy 는 awaitFill(getOrder 폴링) 후 getAccounts 로 실수량/평단 재조회. sell 은 거래소 실잔고로 주문 + done 확인 후에만 markSold, 잔고 0 이면 phantom 청산.
- crit#5: UpbitClientImpl.placeOrder 의 retryOnRateLimit 제거(GET 만 재시도 유지).
- high: investRatio 적용(krwBalance*ratio cap maxInvest), BacktestEngine next-bar-open 체결로 look-ahead 제거.
- 테스트: PositionManagerExtendedTest buy/sell 전면 재작성 + TradingStateTest boughtToday. engine/domain/client 패키지 통과(BUILD SUCCESSFUL).

# Resume context
- **Branch**: fix/review-findings
- **Uncommitted files**: Wave 1 코드/테스트 (커밋 예정) + .claude/tasks/*
- **Next concrete action**: Wave 2(보안) — SecretKeyMaterialProvider 키 분리(crit#4), PriceStreamController SSE 인증/구독 캡(crit#6), RateLimitFilter fail-open(high), application-prod ssl 외부화. 먼저 PriceStreamController.kt/SecurityConfig.kt/RateLimitFilter.kt/PriceCacheService.kt 정독.
- **Open questions**: (1) 평단 물타기(averaging-up) 허용? 기본은 "포지션 보유 중 추가매수 금지"로 구현. (2) investRatio 적용식 = krwBalance*investRatio (상한 maxInvestAmount)로 구현.
- **Gotchas**: 로컬 빌드 `export JAVA_HOME=/Users/jongyoonlee/Library/Java/JavaVirtualMachines/jbr-21.0.9/Contents/Home` 필수. `./gradlew|tail` 는 exit code 마스킹됨 — 리다이렉트 후 $? 확인.
