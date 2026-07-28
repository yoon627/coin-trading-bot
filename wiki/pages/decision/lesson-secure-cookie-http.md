---
title: lesson — prod 프로파일 + HTTP 배포는 브라우저 로그인이 불가능하다
category: decision
created: 2026-07-28
updated: 2026-07-28
claim_state: current
verified: 2026-07-28 — docs/lessons.md 원문 항목(2026-05-30) 이관, 원본 커밋 331426f
sources:
  - docs/lessons.md
  - bot/src/main/kotlin/com/trading/bot/auth/AuthController.kt
---

# lesson: prod + HTTP = 로그인 불가

**언제**: 2026-05-30

## 증상

배포 후 브라우저에서 로그인이 안 된다. `/api/auth/login` 은 **200 과 token body 를 정상 반환**하는데, 다음 요청에서 `/app.html` 이 401 로 튕기고 `/login.html` 로 돌아간다. 사용자 입장에선 그냥 "로그인 실패".

## 원인

`AuthController.shouldMarkSecure` 가 prod 프로파일이면 **무조건 `Secure` 쿠키**를 발급한다(코드 주석에 "Local prod-mode HTTP testing is unsupported" 라고 명시돼 있다). 브라우저는 HTTP 연결에서 Secure 쿠키를 **저장하지 않는다**. 그래서 로그인 응답 자체는 성공인데 쿠키가 안 박히고, 이후 인증이 전부 실패한다.

`curl` 로 검증하면 token body 만 보고 통과로 판단하게 되어 못 잡는다 — 같은 종류의 함정이 [[lesson-single-point-verification]] 과 [[lesson-cors-origin-rebuild]] 에서도 반복됐다.

## 지금 어떻게 하나

1. **prod 배포는 TLS 종단을 동반한다.** 현재 스택은 Caddy + Let's Encrypt 로 HTTPS 를 종단한다([[deployment-stack]]).
2. auth 검증은 token body 가 아니라 **`--cookie-jar` 로 쿠키 헤더가 실제로 박히는지 + 후속 인증 요청까지** 확인한다.
3. 우회 변수 `APP_AUTH_COOKIE_FORCE_INSECURE` 는 임시 용도로만 쓴다.
