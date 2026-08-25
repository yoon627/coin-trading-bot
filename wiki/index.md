# LLM Wiki — 색인

이 repo 의 영속 지식베이스. 운영 규약은 [WIKI.md](WIKI.md), 변경 이력은 [log.md](log.md).

작업을 시작할 때 관련 페이지를 여기서 찾는다. 없으면 "wiki 에 없음" 이며, 추측으로 답하지 않는다.

## concept — 이 시스템이 무엇인가

- [[architecture-overview]] — 단일 Spring Boot 프로세스 안의 봇·API·시세수집, 모듈 2개
- [[trading-engine-loop]] — `processTicker` 게이트 순서, 청산 우선순위, 기본 리스크 파라미터
- [[exit-gates]] — 손절·트레일링·익절·차트청산·보유상한의 판정식과 비자명한 지점
- [[swing-strategies]] — `TradingStrategy` 인터페이스와 전략 9종(무릎 매수 2종 포함), 기본 `combined` 의 3조건
- [[marketdata-pipeline]] — WS ticker + REST 캔들 수집, `MarketDataStore`, half-open 워치독
- [[kis-stock-trading-flow]] — KIS 국내주식 봇의 시작·시세·신호·포지션 사이클
- [[kis-order-lifecycle]] — KIS 수동·자동 주문의 검증·WAL·API 송신·체결 reconcile
- [[persistence-schema]] — Flyway V1~V22, Upbit·KIS 주문·포지션 상태가 무엇을 살리는가, 매도 전략 귀속
- [[trade-record-volume-semantics]] — `trade_records.volume` 이 엔진은 총보유량 스냅샷, 수동은 증분인 이유와 보유량 산출 규칙
- [[backtest-engine]] — 단일티커·all-in 구조와 라이브 정합의 한계

## decision — 이 repo 가 내린 결정

- [[rightsizing-history]] — collector·Kafka·ML 을 왜 제거했나
- [[migration-numbering]] — 미머지 브랜치의 Flyway 번호 선점 문제
- [[plan-git-tracking]] — `.claude/plans/` 를 git 추적하는 이유
- [[worktree-workflow]] — 분기·병렬 제약·머지 후 자동 정리
- [[prepush-codex-review]] — push 게이트: P0/P1 차단, fail-closed, 480초 timeout
- [[docs-code-sync]] — 어떤 변경이 어떤 문서를 갱신시키는가
- [[github-issues-backlog]] — 백로그는 이슈 단일 소스, wiki 는 백로그가 아니다
- [[strategy-evolution-expectations]] — 반자동 루프이며 연 0~2건 승격이 정상

## decision / lesson — 겪은 함정

- [[lesson-secure-cookie-http]] — prod + HTTP 는 브라우저 로그인 불가 (curl 로는 안 잡힌다)
- [[lesson-cors-origin-rebuild]] — 브라우저만 403(CORS Origin) + 앱 변경엔 이미지 재빌드 필요
- [[lesson-single-point-verification]] — 한 곳에서 통과한 검증을 일반화하지 말 것 (네트워크 지점 · 코드 분기)
- [[lesson-ec2-sizing-oom]] — 소형 EC2 에서 OOM 으로 부팅 실패 (historical)
- [[lesson-deploy-script-pitfalls]] — `set -e` 단락 종료, MSYS 경로 변환
- [[lesson-branch-checkout-drift]] — checkout 이 미커밋 변경을 끌고 간다
- [[lesson-resume-state-sources]] — "진행하던 작업" 은 6곳을 모두 봐야 찾는다
- [[lesson-llm-alpha-verification]] — LLM 알파는 과거 백테스트로 증명 불가(학습 오염), 전향적 shadow mode + LLM 없는 baseline 선행

## entity — 외부 사실·버전

- [[upbit-api]] — 잔고 필드(`locked` 의 의미와 상한 규칙), 주문 파라미터, 상태 판정, 캔들 경계(KST 09:00)
- [[jdk-gradle-toolchain]] — JDK 21 고정, Gradle 8.12, JDK 25 비호환 우회
- [[deployment-stack]] — Vultr 서울 vc2-1c-2gb + Caddy TLS + GHCR, `main` 머지 = 자동 배포

## source / query

아직 없음. 외부 원문을 ingest 하면 `source/`, 재사용 가치 있는 질의 결과는 `query/` 에 쌓인다.
