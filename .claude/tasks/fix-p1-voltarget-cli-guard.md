# Fix P1: VolTarget CLI should not crash at runtime

status: pending
created: 2026-04-18
blocks: (none — isolated to CLI)
blocked_by: (none)
estimated: 15-30 min
parallelization: fully isolated from the other two P1 plans; can run in any session in parallel with them

## Goal

Prevent `research` CLI from letting a user pick `vol-target` sizing when the
engine still hardcodes `assetDailyVol=0.0`. After the Apr 2026 fail-loud fix,
`SizingCalculator` throws on non-positive vol, so any `vol-target:X` arg that
reaches `Engine.applyEntryFills()` crashes with an `IllegalStateException` at
the first entry fill.

## Why

Codex caught this as P1 in the 2026-04-18 pre-push review of commit range
`a9299e2..0dbd96f`. The `fail-loud` change was correct as a safety net, but
`Main.kt` still exposes `vol-target` as a documented CLI option, making the
crash reachable from normal CLI usage. Until realized vol is wired into the
engine (separate follow-up), `vol-target` should not be selectable.

## Acceptance criteria

- `research/src/main/kotlin/com/trading/research/cli/Main.kt`:
  - `vol-target` is either removed from the `when` in the sizing parser OR it
    throws a clean, actionable `IllegalArgumentException` / `UsageError` at
    CLI parse time (not at fill time), with a message pointing to the
    remaining supported options.
  - The `--sizing` help string no longer advertises `vol-target:0.15`.
- A unit test or integration test for the CLI parser covers the rejection
  path (e.g., `--sizing vol-target:0.15` → parser error).
- `./gradlew :research:test` green.
- README / CLAUDE.md doc-sync: if `vol-target` is mentioned in any repo
  docs, update to reflect the v1 limitation.

## Steps

- [ ] Read `research/src/main/kotlin/com/trading/research/cli/Main.kt` to confirm exact current shape
- [ ] Decide: reject-with-error vs remove-from-parser (recommend: reject with clear message + updated help)
- [ ] Write failing test for CLI parser rejecting `vol-target:...`
- [ ] Implement the rejection
- [ ] Update help text (`help = "..."`) to drop `vol-target` mention
- [ ] Grep repo for `vol-target` in docs, update if referenced (likely none)
- [ ] Run `:research:test` — all green
- [ ] Commit: `fix(research,cli): reject vol-target sizing until realized vol is wired in`
- [ ] Mark status: completed + update progress log

## Progress log

_(append entries here as work proceeds)_

## Resume context (for next session)

- The crash is only reachable via the CLI path (`research/cli/Main.kt:67`). No in-repo tests currently exercise VolTarget end-to-end, so the fix is small.
- Hook is in place; push will run `codex exec review` on the commit. Expect clean review since change is localized.
- Parallel-session safety: this plan touches only `Main.kt` + its test. No conflict with the warmup-isolation plan (Engine.kt) or the multi-asset-halt plan (Engine.kt).
- After committing, do not push alone — coordinate with the other two P1 plans so the push goes through once all three are fixed.
