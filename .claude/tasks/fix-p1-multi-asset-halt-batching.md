# Fix P1: Multi-asset total-DD halt must wait for all same-closeTime marks

status: completed
created: 2026-04-18
updated: 2026-04-18
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

- [x] Re-read `Engine.run()` loop + `BarStream` structure (`research/src/main/kotlin/.../engine/BarStream.kt`)
- [x] Decide: materialize-then-iterate (simpler) or peekable-stream (preserves laziness). Chose materialize — research-scale event lists are small vs per-bar allocations.
- [x] Write failing test: 2-asset same-closeTime scenario where partial mark would falsely halt
- [x] Implement halt-batching
- [x] Run single-asset tests to make sure nothing else regressed
- [x] Remove the inline `TODO(multi-asset halt)` block
- [x] Update `Engine` KDoc pipeline description (step 6 wording rewritten — explicit "only after the last event at this closeTime")
- [x] `./gradlew :research:test` green
- [ ] Commit: `fix(research,engine): batch halt check across same-closeTime events`
- [x] Mark status: completed + progress log

## Parallelization / coordination

Same `Engine.kt` as warmup-isolation plan. See warmup plan for coordination
strategy (serial in one session OR worktree-based parallel).

If the warmup plan has already landed when this runs, rebase on top of
`main` before starting.

## Progress log

## 2026-04-18 — Landed

- Materialized `BarStream` into `events = BarStream(config.history).toList()` so the Engine loop can peek one event ahead for closeTime-group boundaries. Research scale makes this trivial vs per-bar allocations; no peekable-stream wrapper needed.
- Halt check now gated by `isLastInCloseTimeGroup = (i == events.lastIndex || events[i + 1].bar.closeTime != event.bar.closeTime)`. Per-event `markToMarket`, `onPeakUpdate`, and `recordDailyEquity` still run on every event (plan-specified): only the `shouldHaltSimulation` gate is batched.
- Removed the inline `TODO(multi-asset halt)` comment block; replaced with a short rationale block matching the new behavior.
- Rewrote Engine KDoc step 6 to say "after the last event at this closeTime" and kept the anti-lookahead invariants section.
- New test `EngineSmokeTest.total-drawdown halt waits for all same-closeTime marks before triggering`: two assets share every closeTime; on bar 1, A crashes to 30 and B rallies to 170. Pre-fix: A's partial mark showed 35% DD and halted the run at 6500. Post-fix: both marks run → final equity 10_000, simulation continues, equityCurve has 2 entries.
- `./gradlew :research:test` green (EngineSmokeTest single-asset halt test still passes, AntiLookahead / Determinism / WalkForward / LegacyStrategyAcceptance / GoldenDataset unchanged).
- Pending: commit with the subject above. All 3 P1 plans now landed — ready for user to push (`CODEX_ACK=1 git push`).

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
