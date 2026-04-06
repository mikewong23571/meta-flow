# Scheduler Notes

## Scope

This directory owns scheduler-side orchestration: task/run validation, state transitions, dispatch decisions, retries, timeout handling, runtime coordination, and developer/demo helpers.

The main internal split is:

- `state.clj`, `validation.clj`, and `retry.clj` for transition and eligibility rules.
- `step.clj`, `runtime.clj`, `runtime/run.clj`, and `runtime/timeout.clj` for scheduler passes, runtime coordination, and timeout handling.
- `dispatch/core.clj` for capacity and dispatch decisions.
- `shared.clj` for common scheduler helpers.
- `dev.clj` plus `dev/` for demos, inspection, and developer-facing task helpers.

The public facade stays narrow in `src/meta_flow/scheduler.clj`; avoid pushing subsystem detail back up into that file.

## Change Rules

- Prefer convergence across repeated scheduler passes. If behavior depends on "the next pass fixes it", make that explicit and testable rather than hiding it in one oversized step.
- Keep decision logic close to scheduler state/validation rules, not mixed into dev helpers or facade wrappers.
- Reuse store/runtime/control boundaries instead of reaching into row-shape or process-detail concerns from scheduler code.
- Timeout and recovery behavior should flow through the same run/task transition model used elsewhere; avoid special-case side channels.
- Keep `dev/` isolated. Demo and inspection conveniences should not quietly become production dependencies.

## Validation

- For retry or dispatch changes, check `test/meta_flow/scheduler/retry_test.clj` and `test/meta_flow/scheduler/dispatch/`.
- For validation and awaiting-validation convergence changes, check `test/meta_flow/scheduler/validation_test.clj`.
- For timeout, heartbeat, lease, or startup recovery changes, check `test/meta_flow/scheduler/heartbeat/` and `test/meta_flow/scheduler/recovery/`.
- For end-to-end scheduler pass behavior, check `test/meta_flow/scheduler/runtime/run_test.clj`, `test/meta_flow/scheduler/runtime_mock_test.clj`, and nearby support fixtures.
