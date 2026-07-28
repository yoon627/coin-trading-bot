---
title: lesson — 소형 EC2 에서 OOM-killer 로 부팅 자체가 실패 (5컨테이너 시절)
category: decision
created: 2026-07-28
updated: 2026-07-28
claim_state: historical
verified: 2026-07-28 — docs/lessons.md 원문 항목(2026-05-25) 이관, 원본 커밋 331426f. 전제(5컨테이너)는 현재 구조와 다름 — settings.gradle.kts 모듈 2개로 확인
sources:
  - docs/lessons.md
  - PROJECT_ANALYSIS.md
---

# lesson: 소형 EC2 OOM

**언제**: 2026-05-25

> [!conflict] 이 교훈의 **전제는 현재와 다르다.** 당시 `docker-compose.yml` 은 app + collector + kafka + postgres + redis **5컨테이너**였고 JVM 이 둘이었다. 지금은 경량화로 collector·Kafka 가 제거돼 JVM 이 하나다([[rightsizing-history]]). 아래 "지금도 유효한 것"만 현재형으로 읽어야 한다.

## 증상

t2.micro(1GB)·t3.small(2GB) 에 올렸더니 두 JVM(app/collector) 동시 부팅 peak 에서 **OOM-killer 발동(`exit 137`) → restart loop**. 실패 양상도 인스턴스마다 달랐다 — t2.micro 는 swap thrashing, t3.small 은 OS-level OOM.

## 지금도 유효한 것

- **메모리 부족은 "느려짐"이 아니라 "부팅 실패"로 나타난다.** `exit 137` 은 앱 버그가 아니라 커널이 죽인 흔적이다.
- **최소 4GB** 가 이 스택의 하한이었다. 현재 운영은 t4g.medium(arm64, 4GB)이며 이 교훈이 반영된 결과다([[deployment-stack]]).
- JVM 이 여럿이면 각각 heap 상한을 명시하지 않는 한 동시 부팅 peak 이 합산된다.

## 지금은 다른 것

collector 가 없어져 JVM 이 하나이므로 당시의 "동시 부팅 peak" 자체가 사라졌다. 컨테이너도 app + postgres + redis + caddy 구성이다. 따라서 **이 항목을 근거로 현재 인스턴스 크기를 논하지 말고**, 현재 구성 기준으로 다시 측정한다.

같은 작업에서 발견된 배포 스크립트 함정은 별도 항목으로 분리했다 — [[lesson-deploy-script-pitfalls]].
