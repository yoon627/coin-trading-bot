---
title: 경량화(rightsizing) — 왜 collector·Kafka·ML 이 없는가
category: decision
created: 2026-07-28
updated: 2026-08-19
claim_state: current
verified: 2026-08-19 — price_snapshots 제거(V19) 반영
sources:
  - CLAUDE.md
  - PROJECT_ANALYSIS.md
  - bot/src/main/kotlin/com/trading/bot/marketdata/MarketDataIngestionService.kt
---

# 경량화 (rightsizing)

이 repo 에는 한때 **`collector` 별도 모듈 + Kafka + ML(Smile) + Claude 분석 + Resilience4j + Prometheus/Grafana/Loki + `research` 모듈**이 있었다. 전부 제거됐다. 지금은 `common` + `bot` 두 모듈, 단일 프로세스다([[architecture-overview]]).

## 왜 제거했나

- **단일 JVM 에서 메시지 버스는 순비용이다.** collector→Kafka→bot 은 프로세스 경계가 있을 때만 값을 한다. 같은 프로세스 안이면 직접 fan-out 으로 충분하고, 실제로 `MarketDataIngestionService` 가 store/persistence 두 sink 를 **독립 try/catch 로 격리**해 구 Kafka 2-consumer-group 과 등가의 성질을 유지한다([[marketdata-pipeline]]).
- **운영 비용이 실제로 부팅을 막았다.** 5컨테이너 구성은 소형 EC2 에서 OOM 으로 뜨지 못했다([[lesson-ec2-sizing-oom]]).
- ML·스캘핑·Claude 분석은 수익 기여가 입증되지 않은 채 유지비만 발생했다. 이건 **미검증이지 반증이 아니다** — 재도입하려면 무엇을 어떤 순서로 증명해야 하는지는 [[lesson-llm-alpha-verification]] 에 있다.

## 남은 흔적을 만나면

- 문서·주석에 "collector", "Kafka", "research 모듈" 이 나오면 **과거 서술**이다.
- 소비자 없이 남은 저장 경로가 잔재로 남는다. `price_snapshots` 가 그랬고 V19 에서 제거됐다([[persistence-schema]]) — 경량화 직후가 아니라 한참 뒤에야 드러났다는 점이 교훈이다. 이런 잔재의 정리 진행 상태는 GitHub 이슈 큐가 소유하며 여기 적지 않는다.
- Redis 는 남아 있다 — `RateLimitFilter` 가 조건부로 쓴다.

## 되돌릴 때의 기준

분산·다중 인스턴스로 다시 가려면 **부하 테스트로 단일 인스턴스 한계를 먼저 입증**한다는 조건이 붙어 있다(이슈 #26/#25). 추정으로 인프라를 늘리지 않는다는 뜻이고, 이 원칙은 [[github-issues-backlog]] 에 기록된 다른 조건부 작업들과 같은 성격이다.
