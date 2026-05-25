# Lessons

재발 가능성 높고 비자명한 함정만 기록한다. 단순 오타·일회성 환경 문제·README 에 이미 있는 내용은 제외.

형식: `- [YYYY-MM-DD] 증상 → 원인 → 해결 (관련 파일/커밋)`

---

- [2026-05-25] 새 세션에서 "진행하던 작업 기억해?" 에 "없음" 으로 잘못 단언 → memory/ 와 .claude/plans/ 만 확인하고 git 영속 상태(stash·tag·local commits ahead) 를 안 봄 → 앞으로 resume 류 질문에는 ① memory/ ② .claude/plans/ ③ `git stash list` ④ `git tag --list "claude-*"` ⑤ `git log --oneline @{u}..HEAD` ⑥ `git status --short` 여섯 곳을 모두 확인한 뒤 답한다. 이전 세션이 stash@{0} `claude-pre-rebase-wt` 와 tag `claude-backup-before-rebase`, 6개 local commits 를 남겨놨는데도 못 찾았음.
- [2026-05-25] 본 repo 의 `docker-compose.yml` (app+collector+kafka+postgres+redis 5컨테이너) 을 EC2 t2.micro(1GB)·t3.small(2GB) 에 올리려 했더니 두 JVM(app/collector) 동시 부팅 peak 에 OOM-killer 가 발동(`exit 137`) → restart loop. swap thrashing(t2.micro)·OS-level OOM(t3.small) 패턴이 다름. 다음 EC2 시도 시 **최소 t3.medium(4GB)** 또는 collector 분리/heap 명시 제한 없이는 부팅 자체가 안 됨. `deploy/aws/deploy.sh` 도 두 함정: ① `set -euo pipefail`+`load_state()`의 `[[ -f $STATE_FILE ]] && source` 짧은-회로가 false 일 때 exit 1 → 스크립트 즉시 종료 (`if [[ ]]; then ... fi` 로 교체), ② Git Bash(MSYS2) 가 single-quoted `/dev/xvda` 를 `C:/Program Files/Git/dev/xvda` 로 path-convert → `InvalidBlockDeviceMapping` (`export MSYS_NO_PATHCONV=1` 으로 차단). 둘 다 fix 한 채 보관됨.
