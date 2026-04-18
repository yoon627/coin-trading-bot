# Fix P1: Walk-forward warmup bars must not trade or score

status: pending
created: 2026-04-18
blocks: (none directly, but see parallelization note)
blocked_by: (none)
estimated: 45-75 min
parallelization: touches `Engine.kt`; same file as multi-asset-halt plan. Run in the same session sequentially OR on a separate worktree with a rebase afterwards.

## Goal

When `WalkForwardRunner.sliceHistory()` prepends `warmupBars` pre-window bars
so indicators can warm up, those pre-window bars must NOT produce fills, must
NOT affect the kill-switch peak, and must NOT land in the equity curve. Only
the in-window bars should drive metrics, PnL, and halt decisions.

## Why

Codex caught this as P1 in the 2026-04-18 pre-push review. The current fix
feeds pre-roll bars straight through `Engine.run()`, so strategies that are
not explicitly guarded by `warmupBars` (including future strategies) can
open positions in the warmup period and carry pre-window PnL into
`trainResult` / `testResult`. That silently invalidates the walk-forward
split for any indicator-based backtest.

## Acceptance criteria

- `BacktestRunConfig` gains an optional field `warmupUntil: Instant? = null`.
- `Engine.run()` recognizes the warmup phase: for any event with
  `event.bar.closeTime <= config.warmupUntil`:
  - Advance the clock and advance `RollingUniverseView` (so strategies see
    warmup history via `ctx.universe.recentBars()` on the first real bar).
  - Do NOT `applyEntryFills`, `applyExitFills`, `markToMarket`,
    `killSwitch.onPeakUpdate`, `metrics.recordDailyEquity`, `queueRiskExits`,
    or `runStrategyAndSubmit`.
- `WalkForwardRunner.run()` passes `warmupUntil` = the last pre-roll bar's
  `closeTime` (or `null` when `warmupBars == 0`).
- New test: with `warmupBars = 5` and a 10-bar window, the returned
  `equityCurve` has exactly 10 entries (not 15); the strategy's first
  invocation inside the window sees ≥ 5 bars via `ctx.universe.recentBars()`.
- Existing `EngineSmokeTest`, `AntiLookaheadTest`, `DeterminismTest`,
  `WalkForwardTest`, `LegacyStrategyAcceptanceTest`, `GoldenDatasetTest` all
  remain green.
- The existing `WalkForwardTest.run prepends warmup buffer bars before the
  window start` test needs its assertion updated: `observer.totalInvocations`
  will drop to the window bar count since strategy is not called during
  warmup. Replace with an assertion that the first in-window invocation sees
  at least `warmupBars` historical bars.

## Steps

- [ ] Read current `Engine.run()` loop to map exact insertion points
- [ ] Add `warmupUntil: Instant? = null` to `BacktestRunConfig`
- [ ] Introduce `isWarmup` check at top of the loop; early-continue after clock + universe advance
- [ ] Update `WalkForwardRunner.run()` to compute `warmupUntil` from the sliced history (last bar with `closeTime < window.from`)
- [ ] Write failing test asserting first in-window invocation sees warmup history
- [ ] Implement → tests green
- [ ] Update the previously-broken `observer.totalInvocations` assertion
- [ ] Update KDoc on `Engine` pipeline to describe the warmup phase
- [ ] `./gradlew :research:test` green
- [ ] Commit: `fix(research,engine): skip fills/metrics during walk-forward warmup phase`
- [ ] Mark status: completed + progress log update

## Parallelization / coordination

`Engine.kt` is also edited by the multi-asset-halt plan. Either:
- **Same session, serial**: do warmup-isolation first, then multi-asset-halt, rebase onto warmup result.
- **Separate worktree**: `git worktree add ../coin-trading-bot-halt halt-batch-branch` and develop the other fix there; merge / rebase before push.

Do NOT run both edits in parallel without at least one of these, or Engine.kt diffs will conflict at merge.

## Progress log

_(append entries here)_

## Resume context

- The warmup pre-roll was introduced in commit `45805aa` (walk-forward slice + warmup pre-roll). That commit's rationale is that `LegacyStrategyAdapter.warmupBars=50` needs history before the window. The issue is NOT "don't prepend warmup", it's "don't let warmup trade".
- `WalkForwardTest.run prepends warmup buffer bars before the window start` currently asserts `observer.totalInvocations >= 15` — that assertion must change since strategy won't be called during warmup.
- Anti-lookahead invariant in `Engine.kt` KDoc must be updated to include the warmup phase rules.
- Pre-push hook will re-review; expect clean APPROVE if the fix is complete.
