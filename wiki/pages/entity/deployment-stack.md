---
title: 배포 스택 — Vultr 서울 + Caddy TLS + GHCR
category: entity
created: 2026-07-28
updated: 2026-08-02
claim_state: current
verified: 2026-08-02 — PROJECT_ANALYSIS.md·deploy/vultr/ 대조, `./deploy/vultr/deploy.sh status`·HTTPS health 확인 및 자동 배포 workflow 정적 검증; live Actions 배포는 merge 후 관찰 예정
sources:
  - PROJECT_ANALYSIS.md
  - deploy/vultr/
  - .github/workflows/deploy.yml
  - README.md
---

# 배포 스택

| 계층 | 구성 |
|---|---|
| 호스트 | Vultr 서울(`icn`) **vc2-1c-2gb** (x86_64, 2GB) |
| 컨테이너 | Docker Compose (app + postgres + redis + caddy) |
| TLS | **Caddy 2** + Let's Encrypt, sslip.io 자동 도메인 |
| 이미지 | GitHub Actions → **GHCR** (multi-arch push) |
| 배포 | GitHub Actions `main` push → GHCR → SSH → `deploy/vultr/deploy.sh deploy` |

> `verified` 주의: 위 구성과 2026-08-01 운영 상태는 저장소와 실제 `status`/HTTPS 확인으로 대조했고, 2026-08-02 자동 배포 workflow의 YAML·embedded shell 계약도 정적으로 검증했다. live Actions 배포 결과는 merge 후 별도로 관찰해야 한다.
> AWS 경로는 2026-07-31 삭제된 historical reference이고, OCI는 보류 경로다.

## 이 구성이 나온 이유

각 선택이 사고에서 나왔다:

- **TLS 종단(Caddy)** — prod 프로파일이 항상 `Secure` 쿠키를 발급해 평문 HTTP 에서는 브라우저 로그인이 원천적으로 불가능했다([[lesson-secure-cookie-http]]).
- **2GB 인스턴스** — 이전 EC2의 컨테이너 실측 818MiB를 근거로 제한 합계 1472m로 rightsizing 했다.
- **x86_64(Vultr)** — AWS arm64에서 전환하지만 GHCR 이미지와 공식 의존성 이미지가 multi-arch다.

## 배포 시 주의

- **앱 코드 변경은 이미지 재빌드가 있어야 반영된다.** `deploy.sh deploy`(pull)만으로는 안 바뀐다([[lesson-cors-origin-rebuild]]).
- **자동 배포는 테스트·GHCR push 성공 뒤에만 실행된다.** Actions는 기존 Vultr 인스턴스만 갱신하고,
  고정한 호스트 키와 원격 실행 SHA를 확인한 뒤 기존 migration gate·health check를 재사용한다.
- 배포 스크립트 자체의 셸 함정 두 가지가 수정된 채 보관돼 있다 — 되돌리지 않도록 [[lesson-deploy-script-pitfalls]] 확인.
- 인프라 변경 전에는 [Vultr 상태 페이지](https://status.vultr.com/)의 전역 장애·maintenance를 확인한다.
- 보안그룹이 단일 IP 로 잠겨 있으면 다른 디바이스에서 접근이 안 된다([[lesson-single-point-verification]]).
- 애플리케이션 자체의 구조는 [[architecture-overview]] 참조 — 단일 JVM 이므로 앱 컨테이너는 하나다.
