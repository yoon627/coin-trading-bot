---
title: ec2-tls-caddy — EC2 평문 HTTP 로그인 불가를 Caddy TLS 종단으로 근본 해결
status: done
started: 2026-05-31
updated: 2026-06-02
---

# Goal

`http://13.125.170.147:8080` 로그인 불가의 근본 원인(prod 프로파일이 항상 Secure 쿠키 발급 → 평문 HTTP에서 브라우저가 미저장)을 TLS 종단 도입으로 해결. Caddy reverse proxy를 docker-compose에 추가, sslip.io 무료 도메인(`13-125-170-147.sslip.io`)으로 Let's Encrypt 인증서 자동 발급 → HTTPS 접속.

상세 원안: `~/.claude/plans/piped-wandering-barto.md` (plan mode 승인본).

# Progress

- 2026-05-31: 진단 완료. 근본원인 = `AuthController.shouldMarkSecure():113-120` prod 무조건 Secure. 실서버 health 200·login API 정상 확인. favicon 401은 정상 노이즈.
- 2026-06-01: plan-reviewer(Claude)+codex 검토 완료 → CONDITIONAL GO. CRITICAL 2·HIGH 5 반영. 구현 착수.
- 2026-06-01: 코드/설정 변경 완료(미커밋, 브랜치 `feat/ec2-tls-caddy`) — Caddyfile(신규), docker-compose.prod.yml(caddy 서비스+caddy_data/config 볼륨, app `ports`→`expose`), deploy.sh(`ensure_sg_rules`/`preflight_domain` 신규, 컨테이너 내부 헬스체크로 교체, APP_DOMAIN 자동생성, Caddyfile scp, do_setup도 ensure_sg_rules로 통일), .env.example(APP_DOMAIN 추가). `docker compose config` 검증 OK. **문서 동기화 착수 직후 사용자 요청으로 세션 정리.**
- 2026-06-01: 문서 동기화 완료 — README.md(아키텍처 다이어그램에 caddy/HTTPS 흐름, 컨테이너 표 caddy 행, 기술스택 TLS 행, 배포 다이어그램/텍스트 caddy, .env 조정변수에 APP_DOMAIN), deploy/aws/README.md(접속 URL→https+sslip.io, 보안 섹션 TLS 적용/80상시·443 CIDR), PROJECT_ANALYSIS.md(§10 다이어그램 caddy:443→app + 캡션, 기술스택 TLS 행). ASCII 다이어그램 정렬 검증(▲/│/┴ 세로정렬, postgres 칸 헤더 오정렬 보정).
- 2026-06-01: code-review(Claude code-reviewer + codex 병행) → **REQUEST CHANGES**. Critical 1(443 기본 0.0.0.0/0 보안하향), Major 4(SG 에러 전부삼킴/tmp_env 시크릿 누수/배포 헬스체크 Caddy·TLS 미검증 거짓양성/preflight resolver 미확인을 정상취급) + RateLimitFilter XFF 미반영(Caddy 뒤 단일IP 합산→auth rate limit 무력화; 기존문제·범위밖), Minor(압축범위 과넓음/flush_interval/caddy mem·zstd/ACME email/이미지 pin/8080 SG 잔존). **헬스체크 curl 은 app 이미지 Dockerfile:15 `apk add curl` 로 존재 확인 — 문제없음**. 443 접근제어(H1) 사용자 재결정 대기.
- 2026-06-01: 사용자 443 결정 = **옵션①(전체개방 + rate limit 강화)**. 리뷰 수정 적용 완료 — RateLimitFilter XFF 첫 IP 파싱(`clientIp()`, +테스트 2건 TDD Red→Green), Caddyfile(압축 제외 `/api/prices/stream` 으로 축소 / `flush_interval -1` / `header_up X-Forwarded-For {remote_host}`), deploy.sh(`_authorize_ingress` Duplicate만 무시·그외 fail / `tmp_env` EXIT trap / HTTPS e2e `curl --resolve` soft-warn / preflight resolver 부재·실패·불일치 구분). 검증: `:bot:test` BUILD SUCCESSFUL(10 tests), `bash -n` OK, `compileKotlin` 통과. caddy validate·compose config 는 docker 데몬 미기동 → 배포 시 검증. simplify 메인 직접 점검(추가 항목 없음). caddy 이미지 pin(Nit)은 후속.
- 2026-06-01: **[브랜치 복구]** 작업이 `main` working tree 에 쌓여 있던 것 발견(세션 중 사용자가 `feat/ec2-tls-caddy → main` checkout). 로컬 main/feat 둘 다 #8 이라 origin/main(#10, deploy/.env 건드린 #9·#10)보다 behind. `git stash -u` → `origin/main` 기준 worktree `.claude/worktrees/ec2-tls-caddy`(브랜치 `ec2-tls-caddy`) 생성 → `stash pop` 으로 #9/#10 과 **자동 3-way 병합(충돌 0)**. 병합 검증: DISCORD_ERROR_*(#9/#10) + APP_DOMAIN(우리) 공존 확인. worktree 에서 `:bot:test` SUCCESSFUL·`bash -n` OK. 커밋 **2bd180a** (origin/main 위 1커밋). main working tree 는 stash 로 #8 원복(깨끗).
- 2026-06-01: lessons.md 함정 기록(17b620d). push `origin/ec2-tls-caddy` + **PR #11**(https://github.com/yoon627/coin-trading-bot/pull/11, pre-push hook 통과). cleanup: 로컬 `main` ff→#10(origin 동기화), 빈 `feat/ec2-tls-caddy` 삭제. **코드 작업 완료** — 남은 것은 배포(사용자 파일)·PR 머지.
- 2026-06-02: **배포 성공** (방법 A — 사용자가 scp 로 `.env` 복원, 이어서 `deploy.sh deploy` 실행). SG 80/443 신규 추가, preflight OK, **'시크릿 생성' 메시지 없음(APP_ENCRYPTION_SECRET 보존)**, caddy+app(expose)+postgres+redis 기동, App healthy. **e2e 직접 검증 통과**: HTTPS 200 + LE 인증서 valid(ssl_verify=0), HTTP→HTTPS 308 리다이렉트, SSE 실시간 스트림(Content-Encoding 없음=압축 제외 정상). 로그인 쿠키 Secure 는 코드(prod 무조건 Secure)+HTTPS 로 보장 → 사용자 실계정 최종 확인 권장.
- 2026-06-02: 브라우저 로그인 **403 발견 → CORS 버그**. SecurityConfig `allowedOrigins` 가 localhost 만 → fetch 가 same-origin POST 에 붙이는 Origin(`https://13-125-170-147.sslip.io`)을 Spring CORS 가 거부. curl(Origin 없이)은 401 이라 e2e 로 못 잡음(2026-05-30 "curl≠브라우저" 함정 재발). 수정 커밋 `2a26a00`: SecurityConfig `@Value(APP_DOMAIN)` 으로 https origin 추가 + compose app `APP_DOMAIN` env + lessons. `compileKotlin`/`test` OK, push(PR #11 갱신). **app 코드라 GHCR 이미지 재빌드(PR 머지→CI) 필요 — deploy.sh 만으론 미반영.** status done→in_progress 재오픈.
- 2026-06-02: PR #11 **squash 머지**(`331426f`). 그새 머지된 `#12`(app 로직만, deploy/compose/auth 무관 → worktree 충돌 없음) 위에 올라감. CI run `26765694495`(test + multi-arch GHCR push) **빌드 중** → watch background(`b9pdswu0j`). 완료 후 `deploy.sh deploy`(새 `:latest` = #11 compose + #11/#12 app) + `curl -H Origin` / 브라우저 로그인 검증 예정.
- 2026-06-02: CI `build-and-push`(multi-arch, ~15-18분)가 오래 걸려 **세션 마무리**(test ✓, build-and-push 미완). 재배포·로그인 검증은 다음 세션에서 아래 Next 절차로 재개. TLS 인프라(Caddy/HTTPS/SSE/SG)는 이미 prod 작동 — 남은 건 **CORS fix 가 든 새 이미지로 재배포 + 로그인 403 해소 확인**뿐.
- 2026-06-02: 사용자 '재빌드 했는데 403' → **직접 prod 진단**. 근본원인 = **CORS fix 재배포 미반영**. `app-app-1` 이 2026-06-01 **15:27 생성**(CI build-and-push 16:04 *이전*)·이미지 `c70753d3`(**05-31 10:20 빌드, CORS fix 없음**)·`APP_DOMAIN` env **미주입**(NOT SET). Caddy 우회 app 내부 OPTIONS preflight 도 403 → app/env 문제 확정(Caddy 무관). 안전조건 확인(로컬 `deploy/aws/.env` `APP_ENCRYPTION_SECRET` 보존·`.state`·`.pem`). → `deploy.sh deploy` 재실행(새 `:latest` pull + `APP_DOMAIN` 렌더 + 컨테이너 재생성).
- 2026-06-02: **재배포 완료 + 검증 통과 → status: done**. `deploy.sh deploy` 가 새 이미지 `e0229bd`(CI 16:04 빌드, CORS fix 포함) pull · `app-app-1` recreate · `APP_DOMAIN=13-125-170-147.sslip.io` 주입 · **시크릿 생성 없음**(키 보존). 직접 검증: CORS preflight(OPTIONS) **200 + `Access-Control-Allow-Origin`** 헤더, login POST(오자격) **401**(403→해소, `Invalid credentials`=정상). 컨테이너 `APP_DOMAIN` set·image created 16:04 확인. 남은 건 사용자 실계정 브라우저 로그인 최종 확인(200+`Set-Cookie`/Secure)뿐.

# Next

**[✅ 완료 — status: done]** 근본원인(CORS fix 재배포 미반영) 해소. `deploy.sh deploy` 로 새 이미지(`e0229bd`=CI 16:04)·`APP_DOMAIN` 반영·컨테이너 recreate, curl 검증까지 통과(위 Progress 2026-06-02 마지막 줄). **남은 1건(선택)**: 사용자 실계정 브라우저 로그인 200+`Set-Cookie`(Secure) + 후속 인증 정상 확인. (선택) 8080 SG inbound revoke·`caddy:2-alpine` minor pin.

1. ✓ CI 빌드 `success` + prod 재배포로 새 이미지(`e0229bd`=16:04)·`APP_DOMAIN` 반영 검증 완료.
2. **재배포**: `bash .claude/worktrees/ec2-tls-caddy/deploy/aws/deploy.sh deploy`
   - worktree `deploy/aws/` 에 `.env`(사용자 scp 복원)·`coin-trading-bot-key.pem`(`~/.ssh/coin-trading-bot-ec2` 복사)·`.state`(IP 13.125.170.147 / SG sg-09c8a817423cd928b / 인스턴스 i-05575e4603c9c1f63) **이미 복원돼 있음**.
   - ⚠️ deploy 로그에 `시크릿 생성` 뜨면 `.env` 유실 → **중단**(APP_ENCRYPTION_SECRET 보존). prod ssh/scp 분류기는 사용자 직접 지시 맥락에서 통과.
3. **검증**: `curl -i -H "Origin: https://13-125-170-147.sslip.io" -X POST https://13-125-170-147.sslip.io/api/auth/login -H "Content-Type: application/json" -d '{"username":"<실계정>","password":"<pw>"}'` → **403 아닌 401/200** 이어야 CORS 해소. + 브라우저 로그인 시 쿠키 `Secure` 저장 + 후속 인증 요청까지(lessons.md [2026-05-30]).
4. 검증 통과 → **status: done**. (선택) 기존 8080 SG inbound revoke(현재 무해), `caddy:2-alpine` minor pin.

> 미커밋 변경: `deploy/aws/{Caddyfile(신규),deploy.sh,docker-compose.prod.yml,.env.example}` + 문서 3종(README.md, deploy/aws/README.md, PROJECT_ANALYSIS.md). 재개 시 `git status` + 이 plan 부터 확인.

# Decisions

- **인증서**: sslip.io(`13-125-170-147.sslip.io`) + Caddy 자동 Let's Encrypt. (검색으로 2026 동작 확인)
- **배포 실행**: 사용자가 `.pem/.env/.state`를 `deploy/aws/`에 복사 → Claude가 `deploy.sh deploy`. (.gitignore 처리 확인됨)
- **C1 (헬스체크)**: app `ports:8080:8080`→`expose:8080`이라 deploy.sh 호스트측 `curl localhost:8080` 실패 → `docker compose exec -T app curl localhost:8080/actuator/health`로 교체.
- **C2 (SG 멱등)**: 기존 박스는 `do_setup` 조기 return → `ensure_sg_rules()`를 `do_deploy`에서 멱등 호출(80/443).
- **H1 (443 범위)**: 본인 IP/32로 묶으면 lessons.md:11 재발. 현 8080이 이미 0.0.0.0/0 → **443=`${APP_ALLOW_CIDR:-0.0.0.0/0}`** (접근성 유지+TLS 암호화 개선). 80=0.0.0.0/0 상시(ACME 발급/갱신).
  - **2026-06-01 보강 (code-review Critical 수용 + 사용자 옵션① 결정)**: 443 전체개방 유지(어디서든 접속)하되 'TLS≠접근제어' 반박을 받아들여 **로그인 brute-force 를 RateLimitFilter IP 기반 제한으로 방어**. Caddy `header_up X-Forwarded-For {remote_host}`(client 위조 XFF 차단) + RateLimitFilter `clientIp()` 가 XFF 첫 IP 를 식별자로. forward-headers-strategy(전역) 대신 RateLimitFilter 직접 파싱 — same-origin 이라 URL 재작성 불필요, rate limit IP 만 필요(최소 변경).
- **code-review 수정 (2026-06-01)**: ①`_authorize_ingress` 헬퍼로 SG authorize 의 `InvalidPermission.Duplicate` 만 무시·그 외 fail(80/443 미개방 은폐 방지) ②`tmp_env`(시크릿 평문) `trap EXIT` cleanup ③배포 헬스체크에 HTTPS e2e 추가(`curl --resolve $domain:443:127.0.0.1`, 인증서 지연 대비 soft-warn + caddy logs) ④`preflight_domain` resolver 부재/실패/불일치 구분 경고. caddy 이미지 minor pin(Nit)은 `pull_policy: always` 와 함께 후속 검토.
- **H2 (도메인 검증)**: deploy 시 `preflight_domain`으로 sslip.io resolve가 EC2 IP와 일치하는지 확인(불일치 경고).
- **H3 (depends_on)**: Caddy `depends_on: app: condition: service_started` (healthy 아님 — upstream 죽어도 Caddy 생존해 ACME/502).
- **H4 (SSE)**: Caddyfile 압축 제외를 `/api/prices/*` → **`/api/prices/stream`** 으로 축소 (code-review: `/latest`·`/status` 는 일반 JSON 이라 압축 허용). + `reverse_proxy { flush_interval -1 }` 로 SSE 즉시 flush 명시. 검증에 `curl -N` 즉시수신 포함.
- **H5 (롤백)**: 8080 제거는 배포·검증 성공 후 별도 단계. 롤백 시 8080 SG 재허용 + `docker compose down -v` 금지(caddy_data 보존).
- **M2 (ACME_EMAIL)**: 빈값 시 `email ` 파싱 에러 위험 → 익명 ACME(글로벌 email 미지정). ACME_EMAIL 옵션 제거.
- **M4**: caddy mem_limit 128m.
- **CORS/ForwardedHeader**: same-origin 상대경로(`api.js`)+prod 무조건 Secure라 SecurityConfig·forward-headers 변경 **불필요**(코드로 확정). (rate limit IP 식별만 RateLimitFilter 가 XFF 직접 파싱 — H1 보강 참조.)
- **브랜치 (2026-06-01 변경)**: 작업이 실수로 `main` working tree 에 쌓였고 로컬이 origin/main(#10)보다 behind → `feat/ec2-tls-caddy`(#8, 로컬 빈 브랜치) 대신 **worktree `.claude/worktrees/ec2-tls-caddy`(브랜치 `ec2-tls-caddy`, origin/main #10 기준)** 에서 작업·커밋. 기존 feat/ec2-tls-caddy 는 폐기 예정.

# Key Files

- `deploy/aws/Caddyfile` (신규) — TLS 종단, SSE 압축 제외
- `deploy/aws/docker-compose.prod.yml` — caddy 서비스+볼륨, app expose 전환
- `deploy/aws/deploy.sh` — APP_DOMAIN 자동생성, ensure_sg_rules, preflight_domain, scp Caddyfile, 컨테이너 헬스체크
- `deploy/aws/.env.example` — APP_DOMAIN 추가
- `bot/.../auth/AuthController.kt:113-120` — 근거(변경 없음)
- `bot/.../config/RateLimitFilter.kt` — `clientIp()` 가 XFF 첫 IP 로 Caddy 뒤 IP별 rate limit 복원
- 문서: `README.md`, `deploy/aws/README.md`, `PROJECT_ANALYSIS.md`
- **작업 위치**: worktree `.claude/worktrees/ec2-tls-caddy`(브랜치 `ec2-tls-caddy`). plan 은 메인 repo `.claude/plans/`(ignored — worktree 미동기, 메인 경로에서 갱신).

# Blockers

- (해소) 443 접근제어: 사용자 옵션①(전체개방 + RateLimitFilter XFF 강화) 결정 → 적용 완료.
- (해소) `.pem`/`.state` 복원: `~/.ssh/coin-trading-bot-ec2` 복사 + `aws describe`(IP 13.125.170.147 / SG sg-09c8a817423cd928b / 인스턴스 i-05575e4603c9c1f63 / VPC vpc-004e094cca90cbadd / Subnet subnet-0c8613ef0b6b3f74f) → worktree `deploy/aws/` 에 작성.
- (해소) 배포: 방법 A 로 `deploy.sh deploy` 완료(SG 80/443 추가, `APP_ENCRYPTION_SECRET` 보존). e2e 직접 검증 통과 — HTTPS 200·LE 인증서 valid·HTTP→HTTPS 308·SSE 실시간(압축 제외). 로그인 쿠키 `Secure` 만 사용자 실계정 최종 확인 권장.
