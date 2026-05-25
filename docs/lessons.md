# Lessons

재발 가능성 높고 비자명한 함정만 기록한다. 단순 오타·일회성 환경 문제·README 에 이미 있는 내용은 제외.

형식: `- [YYYY-MM-DD] 증상 → 원인 → 해결 (관련 파일/커밋)`

---

- [2026-05-25] 새 세션에서 "진행하던 작업 기억해?" 에 "없음" 으로 잘못 단언 → memory/ 와 .claude/plans/ 만 확인하고 git 영속 상태(stash·tag·local commits ahead) 를 안 봄 → 앞으로 resume 류 질문에는 ① memory/ ② .claude/plans/ ③ `git stash list` ④ `git tag --list "claude-*"` ⑤ `git log --oneline @{u}..HEAD` ⑥ `git status --short` 여섯 곳을 모두 확인한 뒤 답한다. 이전 세션이 stash@{0} `claude-pre-rebase-wt` 와 tag `claude-backup-before-rebase`, 6개 local commits 를 남겨놨는데도 못 찾았음.
