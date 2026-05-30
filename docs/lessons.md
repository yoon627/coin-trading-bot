# Lessons

재발 가능성 높고 비자명한 함정만 기록한다. 단순 오타·일회성 환경 문제·README 에 이미 있는 내용은 제외.

형식: `- [YYYY-MM-DD] 증상 → 원인 → 해결 (관련 파일/커밋)`

---

- [2026-05-25] 새 세션에서 "진행하던 작업 기억해?" 에 "없음" 으로 잘못 단언 → memory/ 와 .claude/plans/ 만 확인하고 git 영속 상태(stash·tag·local commits ahead) 를 안 봄 → 앞으로 resume 류 질문에는 ① memory/ ② .claude/plans/ ③ `git stash list` ④ `git tag --list "claude-*"` ⑤ `git log --oneline @{u}..HEAD` ⑥ `git status --short` 여섯 곳을 모두 확인한 뒤 답한다. 이전 세션이 stash@{0} `claude-pre-rebase-wt` 와 tag `claude-backup-before-rebase`, 6개 local commits 를 남겨놨는데도 못 찾았음.
- [2026-05-30] prod 프로파일 + HTTP-only 배포 = 브라우저 로그인 불가. `AuthController.shouldMarkSecure` 가 prod 면 무조건 `Secure` 쿠키를 발급(코드 주석에 "Local prod-mode HTTP testing is unsupported" 명시) → 브라우저가 HTTP 연결에선 Secure 쿠키를 저장 안 함 → /api/auth/login 은 200 + token body 반환되지만 cookie 미저장 → 다음 요청에 쿠키 없음 → /app.html 401 → /login.html 로 튕김 → 사용자 입장 "로그인 실패". curl 검증은 token body 만 보면 통과해서 못 잡음. → 다음부터 (1) prod-HTTP 배포 전 Secure-cookie 제약 사전 확인, (2) auth 검증은 curl 의 token body 만 보지 말고 `--cookie-jar` 로 쿠키 헤더가 실제 박히는지 + 후속 인증 요청까지 확인, (3) prod 배포는 가능하면 TLS 종단(Caddy/Nginx + Let's Encrypt) 동반. 명시적 우회 변수(`APP_AUTH_COOKIE_FORCE_INSECURE`)는 임시용에만. (`bot/.../auth/AuthController.kt:109-115`)
- [2026-05-30] 배포 후 사용자가 "URL 접속이 안 된다"고 알림. 내가 동작 검증할 때 같은 머신에서 `curl`로 HTTP 200 받고 "확인됨"이라 보고했지만, 보안그룹이 **setup 머신의 공인 IP/32 단일**로 잠겨 있어 다른 디바이스(폰·다른 PC·VPN)나 HTTPS 자동 업그레이드된 브라우저에서는 접근 불가. → 다음 EC2 배포 직후엔 (1) "이 IP 외 다른 디바이스/네트워크에서 접근하려면 SG 갱신 필요" 명시, (2) 기대 URL을 **반드시 `http://`+포트** 형태로 강조(Chrome HTTPS-Only 모드 함정), (3) 가능하면 사용자에게 직접 `curl`로 검증해보라고 안내해 본인 환경 IP로도 실제로 도달하는지 확인. setup 검증은 *한 곳*에서만 통과해도 일반화하면 안 됨. (`deploy/aws/deploy.sh` SG 규칙)
- [2026-05-25] 본 repo 의 `docker-compose.yml` (app+collector+kafka+postgres+redis 5컨테이너) 을 EC2 t2.micro(1GB)·t3.small(2GB) 에 올리려 했더니 두 JVM(app/collector) 동시 부팅 peak 에 OOM-killer 가 발동(`exit 137`) → restart loop. swap thrashing(t2.micro)·OS-level OOM(t3.small) 패턴이 다름. 다음 EC2 시도 시 **최소 t3.medium(4GB)** 또는 collector 분리/heap 명시 제한 없이는 부팅 자체가 안 됨. `deploy/aws/deploy.sh` 도 두 함정: ① `set -euo pipefail`+`load_state()`의 `[[ -f $STATE_FILE ]] && source` 짧은-회로가 false 일 때 exit 1 → 스크립트 즉시 종료 (`if [[ ]]; then ... fi` 로 교체), ② Git Bash(MSYS2) 가 single-quoted `/dev/xvda` 를 `C:/Program Files/Git/dev/xvda` 로 path-convert → `InvalidBlockDeviceMapping` (`export MSYS_NO_PATHCONV=1` 으로 차단). 둘 다 fix 한 채 보관됨.
