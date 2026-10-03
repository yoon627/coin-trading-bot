---
title: 경량화(rightsizing) — 왜 collector·Kafka·ML·KIS 주식 봇이 없는가
category: decision
created: 2026-07-28
updated: 2026-10-03
claim_state: current
verified: 2026-10-03 — Redis 제거 반영(`RateLimitFilter` 원문·운영 compose 에 redis 서비스 없음 확인) · 2026-10-01 — fan-out sink 서술: 시세 DB 저장 제거 뒤 `MarketDataIngestionService` 의 sink 는 store·`CandleAggregator` 둘(각 try/catch) · 2026-10-01 — MVP 1단계(연구·백테 코드와 운영 밖 전략·전략 설정 제거) 항목 추가 — 운영 거래 분포는 2026-09-30 읽기 전용 조회(combined 212건·2026-07-14~09-30) · 2026-09-16 — KIS 경로 제거(V27) 반영 · 2026-08-19 — price_snapshots 제거(V19) 반영
sources:
  - CLAUDE.md
  - PROJECT_ANALYSIS.md
  - bot/src/main/kotlin/com/trading/bot/marketdata/MarketDataIngestionService.kt
---

# 경량화 (rightsizing)

이 repo 에는 한때 **`collector` 별도 모듈 + Kafka + ML(Smile) + Claude 분석 + Resilience4j + Prometheus/Grafana/Loki + `research` 모듈**이 있었다. 전부 제거됐다. 지금은 `common` + `bot` 두 모듈, 단일 프로세스다([[architecture-overview]]).

## 왜 제거했나

- **단일 JVM 에서 메시지 버스는 순비용이다.** collector→Kafka→bot 은 프로세스 경계가 있을 때만 값을 한다. 같은 프로세스 안이면 직접 fan-out 으로 충분하고, 실제로 `MarketDataIngestionService` 가 sink 마다(지금은 store 와 집계기, 2026-10-01 까지는 DB 저장도) **독립 try/catch 로 격리**해 구 Kafka 2-consumer-group 과 등가의 성질을 유지한다([[marketdata-pipeline]]).
- **운영 비용이 실제로 부팅을 막았다.** 5컨테이너 구성은 소형 EC2 에서 OOM 으로 뜨지 못했다([[lesson-ec2-sizing-oom]]).
- ML·스캘핑·Claude 분석은 수익 기여가 입증되지 않은 채 유지비만 발생했다. 이건 **미검증이지 반증이 아니다** — 재도입하려면 무엇을 어떤 순서로 증명해야 하는지는 [[lesson-llm-alpha-verification]] 에 있다.
- **연구·백테 코드(2026-10-01, MVP 1단계 — 2026-09-30 결정).** 운영은 사용자 한 명이 `combined` 하나로 돈다(2026-07 이후 거래 기록 212건 전부). 연구 전략 10종, D1 백테 엔진·intrabar 청산 모델·M1 replay, 대시보드 백테 화면·API, 연구 테스트·fixture(31MB)·수집 스크립트, wiki 연구 리포트 19쪽은 운영에 닿지 않는 유지비였다 — 사용자 결정으로 지웠다. 같은 단계에서 등록 전략 중 `combined` 외 8종·무릎 전략의 청산 헬퍼 `ShoulderExit` 과 전략을 고르는 설정(`TRADING_STRATEGY`)도 지웠다([[swing-strategies]]). 연구 결론 중 운영에 남은 것은 트레일링 1.5/arm 0 하나다([[trading-engine-loop]]). 방법론 교훈은 남겼다([[lesson-bracket-needs-fill-semantics]] · [[lesson-llm-alpha-verification]]).

## 남은 흔적을 만나면

- 문서·주석에 "collector", "Kafka", "research 모듈" 이 나오면 **과거 서술**이다. **KIS(한국투자증권 국내주식) 봇도 2026-09-16 에 통째로 제거됐다** — 사용자 결정(유지 비용 대비 필요 없음). `kis/` 패키지·`/api/stock|kis/*`·주식 화면·`users.kis_*`·`stock_order_intent`·`stock_position_state` 가 V27 에서 사라졌고 `bot_state.exchange` 컬럼만 남았다(값은 UPBIT 뿐). "KIS"·"stock" 서술은 과거다.
- 소비자 없이 남은 저장 경로가 잔재로 남는다. `price_snapshots` 가 그랬고 V19 에서 제거됐다([[persistence-schema]]) — 경량화 직후가 아니라 한참 뒤에야 드러났다는 점이 교훈이다. 이런 잔재의 정리 진행 상태는 GitHub 이슈 큐가 소유하며 여기 적지 않는다.
- Redis 도 2026-10 에 뺐다 — API rate limit 카운터 전용이었고, 단일 인스턴스라 앱 메모리 카운터로 바꿨다([[deployment-stack]]). Redis·`REDIS_*` 서술은 과거다.
- "백테"·"fixture"·"M1 replay"·`query/` 리포트를 인용하는 서술은 2026-10-01 이전 것이다. 지운 코드·리포트는 저장소 이력에 있다(마지막으로 있던 커밋 `288ec49`).

## 되돌릴 때의 기준

분산·다중 인스턴스로 다시 가려면 **부하 테스트로 단일 인스턴스 한계를 먼저 입증**한다는 조건이 붙어 있다(이슈 #26/#25). 추정으로 인프라를 늘리지 않는다는 뜻이고, 이 원칙은 [[github-issues-backlog]] 에 기록된 다른 조건부 작업들과 같은 성격이다.
