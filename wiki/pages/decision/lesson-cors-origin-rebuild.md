---
title: lesson — 브라우저만 403 (CORS Origin) + 앱 코드 변경엔 이미지 재빌드가 필요
category: decision
created: 2026-07-28
updated: 2026-07-28
claim_state: current
verified: 2026-07-28 — docs/lessons.md 원문 항목(2026-06-02) 이관, 원본 커밋 331426f
sources:
  - docs/lessons.md
  - bot/src/main/kotlin/com/trading/bot/auth/SecurityConfig.kt
  - deploy/aws/docker-compose.prod.yml
---

# lesson: 브라우저만 403, 그리고 재빌드 없이는 반영 안 됨

**언제**: 2026-06-02 (HTTPS 도메인 전환 직후)

## 증상 1 — 브라우저만 403

`curl` 은 통과하는데 브라우저 로그인만 403.

**원인**: Spring `SecurityConfig` 의 CORS `allowedOrigins` 에 실 서비스 도메인이 빠져 있었다(localhost 만 등록). 브라우저 `fetch` 는 **same-origin POST 에도 `Origin` 헤더를 붙이고**, Spring CORS 가 그걸 검증해 거부한다. `curl` 은 Origin 헤더가 없으니 그 경로를 아예 타지 않아 401 로 끝나고, 그게 "e2e 통과"로 오인됐다.

이건 [[lesson-secure-cookie-http]] 의 "curl ≠ 브라우저" 함정이 **재발**한 것이다 — 한 번 데인 함정이 형태를 바꿔 다시 나왔다는 점이 이 항목의 핵심이다([[lesson-single-point-verification]]).

**해결**: `allowedOrigins` 에 실 서비스 도메인 포함(env 주입). auth 변경은 반드시 `-H "Origin: https://<도메인>"` 을 붙여 검증한다.

## 증상 2 — 고쳤는데 prod 에 반영이 안 됨

**원인**: 앱 코드(`SecurityConfig` 등) 변경은 **GHCR 이미지 재빌드**(PR 머지 → CI build-and-push)가 있어야 prod 에 반영된다. `deploy.sh deploy`(이미지 pull)만으로는 바뀌지 않는다.

**해결**: 앱 코드를 고쳤으면 배포 전에 CI 가 새 이미지를 push 했는지 확인한다. 설정·compose 만 바뀐 경우와 구분해야 한다([[deployment-stack]]).
