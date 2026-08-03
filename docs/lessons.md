# Lessons — `wiki/` 로 이관됨

교훈 기록은 **`wiki/pages/decision/lesson-*.md`** 로 옮겼다. 새 교훈도 거기에 쓴다(형식·규약: `wiki/WIKI.md`).

이관 전 원문은 git 에 남아 있다 — 마지막 원문 커밋 **`331426f`** (`git show 331426f:docs/lessons.md`).

## 이관 매핑 (원문 6항목 → 7페이지)

| 원문 항목 | 이관된 페이지 |
|---|---|
| 2026-05-25 resume 질문에 "없음" 오답 (git 영속 상태 미확인) | `wiki/pages/decision/lesson-resume-state-sources.md` |
| 2026-05-25 소형 EC2 OOM (5컨테이너 시절) | `wiki/pages/decision/lesson-ec2-sizing-oom.md` |
| 2026-05-25 `deploy.sh` 의 `set -e` 단락 종료 · MSYS 경로 변환 | `wiki/pages/decision/lesson-deploy-script-pitfalls.md` |
| 2026-05-30 prod + HTTP 에서 Secure 쿠키 미저장 → 로그인 불가 | `wiki/pages/decision/lesson-secure-cookie-http.md` |
| 2026-05-30 보안그룹 단일 IP — 한 곳 검증의 일반화 오류 | `wiki/pages/decision/lesson-single-point-verification.md` |
| 2026-06-01 checkout 이 미커밋 변경을 main 으로 끌고 감 | `wiki/pages/decision/lesson-branch-checkout-drift.md` |
| 2026-06-02 브라우저만 403(CORS Origin) + 이미지 재빌드 필요 | `wiki/pages/decision/lesson-cors-origin-rebuild.md` |

> EC2 항목 하나에 메모리·셸·경로변환 세 주제가 섞여 있어 두 페이지로 분리했다. 그 항목의 전제(app+collector+kafka 5컨테이너)는 현재 구조와 다르므로 `claim_state: historical` 로 표시돼 있다.

색인은 [`wiki/index.md`](../wiki/index.md).
