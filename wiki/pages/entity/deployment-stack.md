---
title: 배포 스택 — EC2 t4g.medium + Caddy TLS + GHCR
category: entity
created: 2026-07-28
updated: 2026-07-28
claim_state: current
verified: 2026-07-28 — PROJECT_ANALYSIS.md 기술스택 표, deploy/aws/ 존재 확인. 운영 인스턴스의 실제 기동 상태는 이 확인 범위 밖
sources:
  - PROJECT_ANALYSIS.md
  - deploy/aws/
  - README.md
---

# 배포 스택

| 계층 | 구성 |
|---|---|
| 호스트 | AWS EC2 **t4g.medium** (arm64, 4GB) |
| 컨테이너 | Docker Compose (app + postgres + redis + caddy) |
| TLS | **Caddy 2** + Let's Encrypt, sslip.io 자동 도메인 |
| 이미지 | GitHub Actions → **GHCR** (multi-arch push) |
| 배포 | `deploy/aws/deploy.sh` |

> `verified` 주의: 위는 **저장소가 정의하는 구성**이다. 특정 시점에 어떤 인스턴스가 실제로 떠 있고 어떤 이미지 태그가 돌고 있는지는 코드로 알 수 없다 — 운영 상태를 주장하려면 별도 근거가 필요하다.

## 이 구성이 나온 이유

각 선택이 사고에서 나왔다:

- **TLS 종단(Caddy)** — prod 프로파일이 항상 `Secure` 쿠키를 발급해 평문 HTTP 에서는 브라우저 로그인이 원천적으로 불가능했다([[lesson-secure-cookie-http]]).
- **4GB 인스턴스** — 그보다 작은 인스턴스에서는 OOM-killer 로 부팅 자체가 실패했다([[lesson-ec2-sizing-oom]]).
- **arm64(t4g)** — 비용 대비 성능. 그래서 이미지가 multi-arch 여야 한다.

## 배포 시 주의

- **앱 코드 변경은 이미지 재빌드가 있어야 반영된다.** `deploy.sh deploy`(pull)만으로는 안 바뀐다([[lesson-cors-origin-rebuild]]).
- 배포 스크립트 자체의 셸 함정 두 가지가 수정된 채 보관돼 있다 — 되돌리지 않도록 [[lesson-deploy-script-pitfalls]] 확인.
- 보안그룹이 단일 IP 로 잠겨 있으면 다른 디바이스에서 접근이 안 된다([[lesson-single-point-verification]]).
- 애플리케이션 자체의 구조는 [[architecture-overview]] 참조 — 단일 JVM 이므로 앱 컨테이너는 하나다.
