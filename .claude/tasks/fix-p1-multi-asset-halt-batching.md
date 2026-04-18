# Fix P1: Multi-asset total-DD halt must wait for all same-closeTime marks

status: pending
created: 2026-04-18
blocks: (none directly, but see parallelization note)
blocked_by: (optionally warmup-isolation if touching Engine.kt serially)
estimated: 30-60 min
parallelization: touches `Engine.kt` — same file as warmup-isolation. Run sequentially OR on a separate worktree.

## Goal

When a backtest runs multiple assets with synchronized bar closeTimes,
`config.killSwitch.shouldHaltSimulation()` must be evaluated only after
ALL assets at the same `closeTime` have been marked to market, not after
the first one. Otherwise the halt check fires on a partial-mark portfolio
and can stop the run on drawdown that later same-timestamp marks would
have offset.

## Why

Explicitly deferred as a TODO in `Engine.kt` after the 2026-04-18 kill-switch
reorder, and re-flagged by codex as P1 in the pre-push review. Single-asset
runs are unaffected. Safe for v1 because current backtests are single-asset,
but BLOCKS any future multi-asset research workload until fixed.

## Acceptance criteria

- `Engine.run()` groups events by `closeTime` before the halt-check
  decision. Two options:
  1. Materialize the stream into a list (OK for research scale), then iterate
     with 1-event lookahead.
  2. Add a peekable wrapper around `BarStream` that allows a 1-event look-ahead.
- `shouldHaltSimulation()` is only called once per `closeTime` group, AFTER
  every event at that `closeTime` has run through `markToMarket` +
  `onPeakUpdate`.
- `metrics.recordDailyEquity` and `onPeakUpdate` still run per-event as today
  (per-asset marking is fine; only the halt gate is batched).
- New test: 2 assets with identical `closeTime`; first asset's mark would
  trigger halt alone, but second asset's mark puts portfolio back above
  threshold. Expected: simulation continues past that `closeTime`.
- All existing tests remain green (`EngineSmokeTest.total-drawdown
  kill-switch halts on the breaching bar` etc.).
- Inline `TODO(multi-asset halt)` comment in `Engine.kt` is removed once the
  fix lands.

## Steps

- [ ] Re-read `Engine.run()` loop + `BarStream` structure (`research/src/main/kotlin/.../engine/BarStream.kt`)
- [ ] Decide: materialize-then-iterate (simpler) or peekable-stream (preserves laziness). For research scale, prefer materialize.
- [ ] Write failing test: 2-asset same-closeTime scenario where partial mark would falsely halt
- [ ] Implement halt-batching
- [ ] Run single-asset tests to make sure nothing else regressed
- [ ] Remove the inline `TODO(multi-asset halt)` block
- [ ] Update `Engine` KDoc pipeline description (step 6 "Kill-switch halt check" — add "after all same-timestamp events marked")
- [ ] `./gradlew :research:test` green
- [ ] Commit: `fix(research,engine): batch halt check across same-closeTime events`
- [ ] Mark status: completed + progress log

## Parallelization / coordination

Same `Engine.kt` as warmup-isolation plan. See warmup plan for coordination
strategy (serial in one session OR worktree-based parallel).

If the warmup plan has already landed when this runs, rebase on top of
`main` before starting.

## Progress log

_(append entries here)_

## Resume context

- The inline TODO added in commit `5708da8` (Engine halt reorder) explicitly
  flagged this as deferred. The diff included the comment:
  > TODO(multi-asset halt): on portfolios where several assets share a
  > closeTime, [BarStream] emits each as a separate event. The halt check
  > here runs after marking ONLY event.asset ...
- `BarStream` emits events in `closeTime` order, tie-breaking by asset name.
  Confirm this while designing the batch logic.
- Consider moving `metrics.recordDailyEquity` grouping behavior alongside —
  currently `MetricsAccumulator` overwrites same-date entries, which is
  semantically "last-asset-wins". That's a separate minor design question;
  do not change as part of this fix unless it's necessary.
