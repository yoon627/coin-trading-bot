---
title: gabia-domain — 가비아 구매 도메인을 EC2(Caddy TLS)에 연결
status: done
started: 2026-06-02
updated: 2026-06-02
---

# Goal

가비아에서 구매한 도메인을 EC2 prod(`13.125.170.147`, Caddy TLS 종단)에 연결. 기존 `13-125-170-147.sslip.io` 대신 실도메인으로 HTTPS 접속.

# Progress

- 2026-06-02: 현황 파악 완료. **EIP 고정 확인**(`13.125.170.147` = `eipalloc-075c7e498a43cda9c`, 인스턴스 `i-05575e4603c9c1f63` 에 연결 → 재부팅에도 IP 불변, A레코드 연결 적합). 인프라가 `APP_DOMAIN` env **단일 변수**로 도메인 제어 확인: `Caddyfile {$APP_DOMAIN}`(LE 자동발급), `deploy.sh:102` 서버 .env 렌더, `SecurityConfig` CORS 자동. 가비아 A레코드 설정법 조사([공식 매뉴얼](https://customer.gabia.com/manual/38/3041/3040)). 사용자에게 **도메인명 + 접속형태(apex/서브/apex+www)** 질문 → **답변 대기**.
- 2026-06-02: 도메인 = **`do-anything.cloud`** (apex) 수령. 사용자 "가비아 DNS 호스트에 13.125.170.147 등록했는데 안 됨" → 외부 DNS 진단. **lame delegation 확인**: `.cloud` TLD 는 가비아 NS 로 **위임 정상**(위임 IP 4개 중 `43.201.170.100`=`ns.gabia.co.kr`, `20.200.205.248`=`ns1.gabia.co.kr` 일치 확인 + `121.78.117.39`,`211.234.124.90` 가비아 대역). 그러나 가비아 NS 들이 do-anything.cloud 질의에 **전부 rcode=REFUSED**. Google DoH(`Status:2`=SERVFAIL) Comment: "Name servers refused query (lame delegation?)", extended err `rcode=REFUSED ... At delegation do-anything.cloud`. 즉 **가비아 DNS 서버에 zone 미생성/미활성** — 사용자가 넣은 A레코드(13.125.170.147)는 존 활성 전엔 무효(값 문제 아님). 외부 resolve 불가 → **prod 재배포 금지**(Caddy ACME challenge 실패). 사용자 가비아 콘솔 조치 대기.
- 2026-06-02: **도메인 구매 12h+ 경과·존 여전히 REFUSED 재확인**(`www`·apex 둘 다 Google DoH SERVFAIL + 가비아 NS 직접 질의 `Query refused`). 12h+ 면 단순 전파 대기 아님 — **가비아 DNS 존 미활성 확정**. 사용자가 `www` A레코드 추가했으나 무관(존 자체 REFUSED 라 모든 레코드 불가시). → 가비아 콘솔 DNS 레코드 "적용"/존 활성 확인, 안 되면 **가비아 고객센터 문의**(멘트: "ns.gabia.co.kr 로 위임됐는데 외부 조회 시 NS가 REFUSED/SERVFAIL, 존 미활성 의심"). apex vs www 메인 여부는 존 복구 후 확정(www 메인이면 Caddyfile/CORS 코드 변경).
- 2026-06-02: codex 2nd-opinion 시도 실패 — ChatGPT 계정 entitlement 로 모델 5종(gpt-5.3-codex/5.1-codex/5.2/5-codex/5) 전부 `400 not supported`(codex 알려진 버그). Claude 단독 비판적 재검토 → **보강 가설**: `REFUSED` 의 또다른 흔한 원인 = 신규 도메인 **ICANN 등록자 이메일 인증 미완료 → registrar hold(DNS 정지)**. 이 경우 가비아 DNS 설정 만져도 무효, 이메일 인증부터 해야 함. **판별법**: `lookup.icann.org` 에서 do-anything.cloud status → `clientHold`/`serverHold`/`pendingVerification` 이면 인증·보류 문제(가비아 zone 아님), `ok`/`active` 면 원진단대로 가비아 zone 프로비저닝 문제.
- 2026-06-02: codex **0.136 으로 재시도 성공**(default `model: gpt-5.5`, ChatGPT Team 지원). **원인 정정**: 앞선 codex 실패는 stale 토큰 아님(토큰 5/25 그대로인데 gpt-5.5 로 작동) — codex 0.125 의 default(gpt-5.3-codex)+override 구형모델이 ChatGPT 계정 미지원이었던 것. Team 은 모델별 entitlement 상이(gpt-5.5 O / gpt-5.2·5.3-codex X), 버전 업뎃으로 지원 default 가 잡혀 해결. codex 가 DNS 진단 **독립 확인**: "거의 확실히 가비아 zone 활성화/프로비저닝 mismatch, Caddy/EC2/ACME/전파 아님". codex 추가 포인트: ① 레코드를 가비아 **엉뚱한 product/zone/account 또는 다른 NS 서비스**에 넣었을 가능성(넣은 화면이 활성 zone 아닐 수 있음) ② RDAP status 확인(단 부모 위임 중이라 ICANN hold 가능성 낮음 동의).
- 2026-06-02: **✅ 완료** — 사용자가 가비아 DNS zone 활성화 → apex A레코드(`do-anything.cloud`→`13.125.170.147`) 정상 전파(Google/Cloudflare/가비아NS 모두 확인, lame delegation 해소). `deploy/aws/.env` 에 `APP_DOMAIN=do-anything.cloud` 추가 + `deploy.sh deploy`(apex 단일, 코드변경 0, `preflight OK`·시크릿 생성 없음·App healthy). **외부 검증 전부 통과**: HTTPS 200·LE 인증서 valid(ssl_verify=0)·HTTP→HTTPS 308·CORS preflight 200(`Allow-Origin: https://do-anything.cloud`)·login probe 401(403 아님). 남은 건 사용자 실계정 브라우저 로그인 최종 확인(선택). www 는 미등록(apex만).

# Next

**[✅ 완료 — status: done]** apex 도메인 연결+재배포+외부검증 통과(위 Progress 2026-06-02 마지막 줄). 아래는 해결 과정 이력.

1. **사용자**:
   - **⭐ ICANN 등록자 이메일 인증 확인(최우선)** — 가비아 가입 메일함에 "소유자/등록자 정보 인증"·"Verify" 메일 있으면 링크 클릭. 신규 도메인 미인증 시 registrar hold→DNS 정지(REFUSED 흔한 원인). `lookup.icann.org` 에서 status 가 `clientHold`/`pendingVerification` 이면 이 케이스(가비아 콘솔 무관, 인증부터).
   - (가비아 콘솔) 네임서버가 가비아 기본(`ns.gabia.co.kr`/`ns1.gabia.co.kr`)인지 확인 (TLD 위임은 이미 가비아로 정상 → 보통 OK).
   - **DNS 관리/설정**에서 A레코드(호스트 `@`, 값 `13.125.170.147`, TTL `300`)가 **저장·적용**됐는지 + 해당 도메인에 가비아 DNS 호스팅 서비스가 **활성**인지 확인.
   - 방금 도메인 등록/네임서버 변경/레코드 저장했다면 **전파 대기**(가비아 zone 프로비저닝 + TLD, 보통 수십분~수시간). **24h+ REFUSED 지속 시 가비아 고객센터**(존 미생성 가능성).
2. 검증: `nslookup -type=A do-anything.cloud 8.8.8.8` → `REFUSED`/`SERVFAIL` 아니고 `13.125.170.147` 나오면 해소. (또는 dnschecker.org 전파 확인)
3. DNS 정상화 후 (나): `deploy/aws/.env` 에 `APP_DOMAIN=do-anything.cloud` 추가 → `deploy.sh deploy` → Caddy Let's Encrypt 발급(~30s) 확인. **시크릿 생성 메시지 뜨면 중단**(APP_ENCRYPTION_SECRET 보존).
4. 검증: `https://do-anything.cloud` 200 + 인증서 valid + CORS preflight 200(`-H "Origin: https://do-anything.cloud"`) + admin 브라우저 로그인.

# Decisions

- **EIP 고정**(13.125.170.147)이라 도메인 연결 안전 — 재부팅 IP 불변.
- **APP_DOMAIN 단일 제어**: 단일 도메인(apex 또는 서브도메인)이면 `deploy/aws/.env` 한 줄(`APP_DOMAIN=`) + 재배포로 끝, **코드 변경 0**(Caddy/CORS 자동). app 이미지 재빌드 불필요(env 주입만).
- **apex+www 둘 다**면 Caddyfile `{$APP_DOMAIN}` 한 칸으론 부족 → 다중 도메인 블록 + CORS 2 origin → app 코드 변경 → PR 머지·CI 재빌드 후 deploy 필요(2026-06-02 CORS 함정: 코드 변경은 CI 재빌드+deploy 둘 다 해야 prod 반영).
- **순서**: 가비아 DNS 먼저 전파 → 그 다음 재배포. DNS 전 재배포 시 Caddy ACME HTTP-01 challenge 실패(인증서 미발급).
- **sslip.io**: APP_DOMAIN 교체 시 Caddy 는 새 도메인만 서빙 → sslip.io HTTPS 는 인증서 만료로 끊김. 문제 없음(새 도메인으로 이행). 필요 시 Caddyfile 에 sslip.io 병기 가능.
- **접속형태 = apex `do-anything.cloud`** (사용자 제시, www 병행 여부 미확정). 단일 apex 면 `.env` 한 줄+재배포(코드 변경 0). www 도 원하면 Caddyfile 다중도메인 + CORS 2 origin(코드 변경+CI 재빌드 동반).
- **2026-06-02 진단 결론**: 1차 블로커("도메인명 대기")는 해소, 새 블로커는 **가비아측 DNS zone lame delegation(REFUSED)**. TLD→가비아 NS 위임은 정상이므로 레지스트라/네임서버 재설정 불필요 — **가비아 DNS 존 활성/전파 문제로 범위 한정**. 우리(EC2/Caddy) 측 작업은 DNS 정상 resolve 이후로 보류.

# Key Files

- `deploy/aws/.env` — `APP_DOMAIN=<도메인>` 추가 (gitignored, 사용자 보유)
- `deploy/aws/Caddyfile` — `{$APP_DOMAIN}`, 다중 도메인 시 수정
- `deploy/aws/deploy.sh:87,102,137` — domain 기본값 / 서버 .env 렌더 / preflight resolve 확인
- `bot/src/main/kotlin/com/trading/bot/auth/SecurityConfig.kt` — CORS origins(`https://$APP_DOMAIN` 자동; 다중 도메인 시 수정)

# Blockers

- (해소) 도메인명 수령: `do-anything.cloud` (apex).
- **(해소 2026-06-02) 가비아 DNS zone lame delegation**: TLD 위임은 가비아 NS 로 정상이나 가비아 NS 가 do-anything.cloud 질의에 `REFUSED` → 외부 resolve 불가(`SERVFAIL`). 풀려면 가비아 콘솔에서 ① DNS 레코드 "적용"/존 활성 확인 ② **구매 12h+ 경과로 전파 대기 아님 확정 → 가비아 고객센터 문의 우선**. **DNS 정상화 전엔 prod 재배포 불가**(Caddy ACME 실패).
