# LLM Wiki — 색인

이 repo 의 영속 지식베이스. 운영 규약은 [WIKI.md](WIKI.md), 변경 이력은 [log.md](log.md).

작업을 시작할 때 관련 페이지를 여기서 찾는다. 없으면 "wiki 에 없음" 이며, 추측으로 답하지 않는다.

## concept — 이 시스템이 무엇인가

- [[architecture-overview]] — 단일 Spring Boot 프로세스 안의 봇·API·시세수집, 모듈 2개
- [[trading-engine-loop]] — `processTicker` 게이트 순서, 청산 우선순위, 기본 리스크 파라미터
- [[exit-gates]] — 손절·트레일링·익절·차트청산·보유상한의 판정식과 비자명한 지점
- [[swing-strategies]] — `TradingStrategy` 인터페이스와 전략 9종(무릎 매수 2종 포함), 기본 `combined` 의 3조건
- [[marketdata-pipeline]] — WS ticker + REST 캔들 수집, `MarketDataStore`, half-open 워치독
- [[accumulate-ladder]] — 메이저 코인 사다리 매매(떨어지면 단계 매수·오르면 단계 매도, 예산 상한만)와 알트 유니버스 자동 선정 — 둘 다 기본 off, 롤백은 forward-off
- [[persistence-schema]] — Flyway V1~V27, Upbit 주문·포지션 상태가 무엇을 살리는가, 매도 전략 귀속, KIS 스키마 제거(V27)
- [[trade-record-volume-semantics]] — `trade_records.volume` 이 엔진은 총보유량 스냅샷, 수동은 증분인 이유와 보유량 산출 규칙, 추정치가 섞인 그룹의 잔량 0 허용 오차
- [[backtest-engine]] — 단일티커·all-in 구조와 라이브 정합의 한계

## decision — 이 repo 가 내린 결정

- [[rightsizing-history]] — collector·Kafka·ML·KIS 주식 봇을 왜 제거했나
- [[migration-numbering]] — 미머지 브랜치의 Flyway 번호 선점 문제
- [[plan-git-tracking]] — `.claude/plans/` 는 gitignored (2026-09-15 추적 해제 — 이유·기각 대안·삭제 전 백업)
- [[worktree-workflow]] — 분기·병렬 제약·머지 후 자동 정리
- [[prepush-codex-review]] — pre-push 는 `deploy.yml` paths-ignore 자기제외 가드만(fail-closed inline); codex 리뷰 게이트는 2026-09-03 제거 — 이유·되돌리는 법·설치본 드리프트
- [[db-integration-test-harness]] — DB 통합테스트는 Testcontainers 가 아니라 외부 제공 Postgres 를 쓴다 (Docker 29 비호환)
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
- [[lesson-rollback-removal]] — 롤백 보험을 거두기 전에 "무엇으로 되돌리는가"를 먼저 정의할 것
- [[lesson-skip-is-not-pass]] — 건너뛴 테스트는 통과가 아니다 (초록불이 미검증을 가린다)
- [[lesson-bracket-needs-fill-semantics]] — 브래킷·감도 family 는 계기의 체결 규칙을 코드로 확인한 뒤 설계한다 (유령 손절이 없었던 이유, 비관 브래킷의 불가능한 경로)

## entity — 외부 사실·버전

- [[upbit-api]] — 잔고 필드(`locked` 의 의미와 상한 규칙), 주문 파라미터, 상태 판정, 캔들 경계(KST 09:00)
- [[jdk-gradle-toolchain]] — JDK 21 고정, Gradle 8.12, JDK 25 비호환 우회
- [[deployment-stack]] — Vultr 서울 vc2-1c-2gb + Caddy TLS + GHCR, `main` 머지 = 자동 배포

## source / query

외부 원문을 ingest 하면 `source/`, 재사용 가치 있는 질의 결과는 `query/` 에 쌓인다. `source/` 는 아직 없음.

- [[reset-churn-measurement]] — #128 일일리셋 반사실 측정: 신호 지속성의 가치는 ±0.5%p/건 이하이고 표본 선택에 취약하다
- [[universe-look-ahead-audit]] — #112 유니버스 look-ahead 실측: 상승장 4마켓은 데이터 부족이 아니라 선정 방식 탓이고, 시점 중립으로 고르면 8마켓이 된다
- [[yearly-strategy-comparison]] — 운영 8종 1년(2025-09~2026-09, 전부 하락) 비교: 스윙은 노출 2~12% 로 ±5% 안, 적립·단순보유는 −48/−50%. 순위상관 0.32 라 1년 순위로 전략을 고르면 과적합
- [[parameter-search-2026-09]] — 1년 fixture 파라미터·아이디어 탐색(좌표 51,480 + 신규 3종): 사전고정 게이트로는 라이브를 이기는 설정 없음. null 대조군이 판정을 가른 과정
- [[hold-limit-policy-2026-09]] — 익일 09:00 전량매도 재검토: 어떤 대안도 독립 3창을 다 이기지 못하나, 비용이 오르면 교차점에 가까워진다
- [[trailing-arm-finding-2026-09]] — 트레일링 1축(2.0/arm3 → 1.5/arm0)이 미관측 7국면에서 사전고정 판정을 통과: 이 repo 최초의 확증 결과. 거래수 불변, 7/7 창 양수, 방향 무관. 단 G3 탈락은 실재하고 승격값 1.5 는 격자 하한이다
- [[exit-resolution-verdict-2026-09]] — 탐색 후보의 우위는 일봉 청산모델의 산물: 4시간봉으로 판정하면 남지 않는다. `yearly` 실효 독립 표본은 1.22(문서의 2~3 은 오기). 09:00 이라는 *시각* 도 처음 실측
- [[trailing-width-2026-09]] — #184 트레일링 폭 재판정(240분봉, 10창 사전고정): 0.75·1.00·1.25 전부 통과하고 조일수록 좋으나 그 단조성은 계기 편향과 같은 방향. 승격 아님, 판별은 그림자 관측 1.0
- [[take-profit-stop-loss-2026-09]] — 익절×손절 20셀 사전명세 비교(진입일 페어링 maxT, 10창): 통과 9·후보 0. 익절 8·off 는 pooled·하락 창에서 이기지만(일부 창은 음수) 계기 편향 순방향, 손절 off 우위의 2/3 는 진입봉 손절 5건 처리에 걸려 있다(정정: 체결이 봉 시가라 그 손절은 실재). 익절 3·손절 3 은 통과 없이 대부분 열세. 판별은 15분봉 또는 tick 가상 보유
- [[exit-resolution-ladder-2026-09]] — 240→15→5분봉 사다리(같은 10창·maxT): 익절 8·off·손절 off·9시 유지 정책의 240분봉 우위가 해상도를 올릴수록 단조로 0 에 수렴, 5분봉 통과 0 → 현행 유지. 기준선 자체가 240분 +236 → 5분 −219%p 로 뒤집혀 240분봉 판정(트레일링 1.5 포함)을 5분봉으로 재검토해야 한다
- [[shared-balance-2026-09]] — #180 마켓별 독립 예산 가정을 한 계좌로 바꾼 재판정(후처리): 현금 장부(운영 규칙, 초기 1M)에서 후보 E 의 yearly 열세 −32.8k → −37.7k, 동시 보유 1개 스트레스(S=1)에서 −42.2k(P(격차≤0)=0.993); 7국면 우위 357k → 331k(현금 장부) / 290k(S=1), 계좌 50만원이면 178k — 부호는 유지. 후처리는 라이브 진입을 과소 계산하는 하한, 마켓 순서 임의성은 200 치환 분포(상한)
- [[entry-set-decomposition-2026-09]] — #190 5분봉 기준선 −219%p 의 분해: 공통 진입 1,058건은 5분에서 +427%p 좋아지고(체결가 +819·청산 −392), 부호 반전은 전부 5분 전용 진입 709건(−882, 건당 −1.24, 손실 495/709 — 65% 가 09:00 시간청산)에서 온다. 240분 전용 진입 0건. 다음 판정 대상은 진입 조건
- [[trailing-resolution-2026-09]] — #189 트레일링 1.5/arm0 승격 근거(240분봉 +0.124%p/거래)를 15·5분봉 사다리로 재판정: +0.004 → −0.027, 5분 marginal [−200, +108]%p, 비관 브래킷 −173 — 사전고정 해석 (c) 승격 근거 미지지, **되돌릴 근거도 아님**. 폭 축만 수렴하나 미통과, arm 축 비단조
