---
title: claude-code-effort-headroom — effort 고정 해제 및 headroom 잔재 정리
status: done
started: 2026-09-02
updated: 2026-09-02
---

# Goal

Claude Code의 `CLAUDE_CODE_EFFORT_LEVEL=max` 강제를 제거해 `/effort`와 모델 기본값이 작동하게 한다.
이미 제거된 headroom이 bootstrap·문서 잔재로 재설치되거나 오류를 내지 않도록 활성 설치 경로를 정리하고 현재 환경 상태를 검증한다.

# Progress

- 2026-09-02: 별도 worktree 생성. 전역 `~/.claude/settings.json`의 `env.CLAUDE_CODE_EFFORT_LEVEL=max`, bootstrap의 macOS/Windows 재주입, headroom 설치·MCP·proxy 잔재를 확인했다.
- 2026-09-02: `headroom` 명령·`~/.headroom`·launchd service·`~/.claude.json` MCP·`ANTHROPIC_BASE_URL`이 현재 환경에 없음을 확인했다. 전역 `~/.claude/settings.json`에는 사용자 미커밋 변경이 있어 보존 대상이다.
- 2026-09-02: 공식 Claude Code 문서에서 환경변수 > 설정값 우선순위와 settings 파일의 `max` 미지원(환경변수만 가능)을 확인했다.
- 2026-09-02: Codex plan-review는 sandbox 권한 오류 후 승격 재시도에서 자기 자신을 재호출하는 재귀 상태가 되어 중단했다. 자체 리뷰로 대체하며, 리뷰 범위를 effort env 우선순위·standalone `rtk` 보존·정확한 headroom 오류 부재로 제한한다.
- 2026-09-02: `settings.json`의 effort env 제거, macOS/Windows bootstrap의 headroom 설치·MCP·proxy 및 effort 재주입 제거, standalone `rtk` 선택 처리 전환을 적용했다. macOS `~/.zshrc`에는 상속된 effort env 해제도 추가했다.
- 2026-09-02: bootstrap README/전역 README/wiki를 현재 headroom retired 및 effort unpinned 상태에 맞게 갱신했다. global settings의 기존 `model`·Orca hook·`modelSettings` dirty 변경은 보존했다.
- 2026-09-02: `bash -n`, `shellcheck`, JSON/환경변수/로그인 셸 assertion, 격리된 임시 HOME의 bootstrap `--dry-run`, global Node 테스트(11개 파일)·pre-commit 테스트·`rtk verify`를 통과했다. 실제 HOME dry-run은 기존 real directory인 `~/.agents/skills/jira-worklog` 링크 검사에서 변경 구간 전에 중단됐다.
- 2026-09-02: `rtk`를 PATH에서 제외한 격리 dry-run도 `rtk 미설치(선택)`으로 정상 종료해 standalone 부재 경로가 실패하지 않음을 확인했다.
- 2026-09-02: wiki checker에서 이번 변경으로 생긴 `headroom` orphan과 `codegraph` outbound 부족을 historical 링크 복구로 해소했다. 남은 `lesson-fix-scoped-to-one-repo` orphan은 변경 전부터 index-only였던 기존 항목으로 분리했다.
- 2026-09-02: `wiki/WIKI.md` 규약에 따라 lint 결과를 `wiki/log.md`에 append했다.

# Next

검증·수정 완료. 사용자는 기존 Claude 프로세스를 종료하고 새 터미널/세션에서 `/effort`를 확인한다.

# Decisions

- 전역 `~/.claude/settings.json`에서는 `env.CLAUDE_CODE_EFFORT_LEVEL`만 제거한다. 사용자가 별도로 추가한 `model`, hook, `modelSettings`는 건드리지 않는다.
- bootstrap은 headroom 설치·MCP 등록·proxy 기동을 수행하지 않는다. 이미 설치된 standalone `rtk`가 있으면 검증/서명하고, 없으면 설치 없이 선택 항목으로 건너뛴다.
- macOS bootstrap이 생성하는 셸 블록에서는 effort를 export하지 않고 `unset CLAUDE_CODE_EFFORT_LEVEL`로 상속된 stale 값을 제거한다. Windows bootstrap은 기존 User 환경변수도 제거한다.
- 현재 `~/.zshrc`의 주석 처리된 max export는 유지하되, 새 login shell에서 부모 프로세스의 stale effort env가 Claude로 상속되지 않도록 `unset CLAUDE_CODE_EFFORT_LEVEL`을 추가한다.
- headroom의 과거 운영 문서는 삭제하지 않고 retired/historical 상태로 남긴다. 활성 bootstrap·설정·README/wiki 설명만 현재 상태에 맞춘다.
- 현재 전역 settings의 dirty 변경은 사용자 소유로 간주한다. 이번 작업의 patch와 겹치지 않는 hunk는 보존하며, 전역 repo에 혼합 커밋하지 않는다.

# Key Files

- `/Users/jongyoonlee/.claude/settings.json` — 현재 Claude Code user settings; effort env 제거 대상, 사용자 dirty 변경 보존.
- `/Users/jongyoonlee/.claude/scripts/bootstrap/setup.sh` — macOS bootstrap의 headroom/effort 재주입 경로.
- `/Users/jongyoonlee/.claude/scripts/bootstrap/setup.ps1` — Windows bootstrap의 headroom/effort 재주입 경로.
- `/Users/jongyoonlee/.claude/scripts/bootstrap/README.md` — bootstrap 동작 문서.
- `/Users/jongyoonlee/.claude/README.md` — 전역 settings/bootstrap 설명.
- `/Users/jongyoonlee/.claude/wiki/index.md` 및 관련 effort/headroom/codegraph 페이지 — 영속 문서의 현재 상태 동기화 대상.
- `/Users/jongyoonlee/.claude/wiki/log.md` — wiki lint 결과 append-only 운영 로그.

# Acceptance

- `jq empty /Users/jongyoonlee/.claude/settings.json` 통과; `env.CLAUDE_CODE_EFFORT_LEVEL` 부재; 기존 사용자 `model`·hook·`modelSettings` 값 보존을 확인한다.
- `setup.sh`/`setup.ps1`가 `CLAUDE_CODE_EFFORT_LEVEL`을 설정하거나 headroom을 설치·MCP 등록·proxy 기동하지 않으며, standalone `rtk` 부재 시에도 실패하지 않는지 정적 확인한다. `setup.sh`는 syntax/shellcheck 및 격리된 임시 HOME dry-run으로 확인하고, `setup.ps1`는 macOS에 PowerShell 실행기가 없어 정적 검토만 한다.
- 새 login shell에서 `CLAUDE_CODE_EFFORT_LEVEL`이 비어 있고 `ANTHROPIC_BASE_URL`/`HEADROOM_*`가 주입되지 않는지 확인한다. 기존 Claude 세션은 재시작이 필요하다는 운영 절차를 보고한다.
- `claude mcp list`에 headroom이 없고 `headroom` 실행 파일·proxy service가 없는 현재 상태를 재확인한다.
- 변경한 global repo 문서/wiki의 링크·검증 스크립트와 관련 Node 테스트를 통과시킨다.

# Review Disposition

- self-review 2-pass: Critical/Major finding 없음. `settings.json` 사용자 dirty hunk 보존, standalone `rtk` 분기, effort/headroom 활성 경로를 재확인했다.
- Codex 병행 review: sandbox 권한 오류 및 승격 재시도의 재귀 상태로 실행하지 못해 자체 review로 대체했다.

# Deferred

- `skills/wiki/check_links.py`: `lesson-fix-scoped-to-one-repo`가 index-only orphan으로 남음(낮음, 이번 변경 전부터 존재; 별도 wiki 정리 대상).
- 실제 HOME에서의 `setup.sh --dry-run`: 기존 real directory `/Users/jongyoonlee/.agents/skills/jira-worklog`가 symlink가 아니어서 helper가 변경 구간 전에 실패(낮음, 기존 환경 상태; 격리된 임시 HOME에서는 통과).
- `setup.ps1` 실제 실행: 현재 macOS에 `pwsh`/`powershell`이 없어 미실행(플랫폼 검증 필요).
- 정확한 사용자의 headroom 오류 원문은 제공되지 않음. 현재 runtime에서 headroom command/dir/service/MCP/env가 모두 부재하므로 추가 삭제는 하지 않음.

# Blockers

- 없음. 단, 현재 전역 `~/.claude/settings.json`의 기존 사용자 변경과 정확한 headroom 오류 원문은 확인되지 않았으므로 해당 파일은 최소 hunk만 수정하고 오류 원문이 필요한 부분은 추정으로 단정하지 않는다.
