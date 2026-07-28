---
title: lesson — deploy.sh 의 두 함정 (set -e 단락 종료, MSYS 경로 변환)
category: decision
created: 2026-07-28
updated: 2026-07-28
claim_state: current
verified: 2026-07-28 — docs/lessons.md 원문 항목(2026-05-25) 에서 분리 이관, 원본 커밋 331426f. 두 함정 모두 fix 된 채 보관 중이라고 원문에 기록
sources:
  - docs/lessons.md
  - deploy/aws/deploy.sh
---

# lesson: deploy.sh 의 두 함정

**언제**: 2026-05-25 (EC2 배포 작업 중 발견 — 같은 작업의 메모리 이슈는 [[lesson-ec2-sizing-oom]])

두 함정 모두 **수정된 채 보관**돼 있다. 스크립트를 손볼 때 되돌리지 않도록 남긴다.

## ① `set -euo pipefail` + 단락 평가 = 조용한 즉시 종료

```bash
# 위험
[[ -f "$STATE_FILE" ]] && source "$STATE_FILE"
```

`set -e` 하에서 이 줄은 **조건이 false 일 때 종료 코드 1** 을 내고, 스크립트가 거기서 즉시 죽는다. 상태 파일이 없는 첫 실행에서 아무 메시지 없이 끝나는 형태로 나타난다.

```bash
# 안전
if [[ -f "$STATE_FILE" ]]; then source "$STATE_FILE"; fi
```

`&&` 단락 평가는 `set -e` 스크립트에서 마지막 명령의 종료 코드가 그대로 스크립트 종료 코드가 된다는 점을 항상 염두에 둔다.

## ② Git Bash(MSYS2) 의 경로 자동 변환

Git Bash 에서 작은따옴표로 감싼 `/dev/xvda` 같은 인자가 `C:/Program Files/Git/dev/xvda` 로 **자동 변환**되어 AWS 가 `InvalidBlockDeviceMapping` 을 반환했다.

```bash
export MSYS_NO_PATHCONV=1
```

Windows 에서 배포 스크립트를 돌린다면 유닉스 경로 모양의 인자(디바이스 이름, ARN, S3 키 등)가 전부 이 변환의 대상이다.

## 일반화

배포 스크립트의 실패는 대개 **애플리케이션이 아니라 셸·환경 레이어**에서 난다. 그리고 한 환경(리눅스 CI)에서 통과한 스크립트가 다른 환경(Git Bash)에서 깨지는 것은 [[lesson-single-point-verification]] 과 같은 구조의 함정이다.
