# Runtime Codex Notes

## Scope

This directory owns the concrete Codex runtime adapter: Codex home setup, worker prompt/input materialization, process launch/state tracking, execution dispatch, worker API bridging, and runtime event emission.

Read it in slices rather than as one blob:

- `home.clj`, `fs.clj`, and `helper.clj` prepare concrete worker inputs and local runtime state.
- `worker.clj`, `worker/prompt.clj`, and `worker_api.clj` define worker-side contracts and bridge behavior.
- `execution.clj` and `execution/dispatch.clj` coordinate adapter lifecycle operations.
- `process/` and `launch/` own launch-mode, command construction, and live process state semantics.
- `events.clj` defines emitted runtime event shapes.

## Change Rules

- Preserve convergence semantics first. Cancellation, launch failure, timeout recovery, and worker exit paths should continue to converge through the existing event/FSM flow rather than through ad hoc flags or one-off state rewrites.
- Keep deterministic preparation separate from live execution. Filesystem snapshot and prompt/input materialization belong in prepare-time paths; process control and polling belong in execution/launch paths.
- Prefer extending existing runtime event and idempotency shapes over inventing parallel signaling.
- Treat Codex home installation and worker input snapshots as reproducibility boundaries. If a run needs a concrete input, write and pin it explicitly instead of depending on ambient user state.
- Keep test-support assumptions visible. This area has both smoke-style and unit-style coverage, so changes that alter launch or worker wiring usually need updates in multiple test clusters.

## Validation

- For home/bootstrap changes, check nearby coverage in `test/meta_flow/runtime/codex/home_test.clj`.
- For process launch and persisted handle/state changes, check `test/meta_flow/runtime/codex/process_launch_test.clj`, `test/meta_flow/runtime/codex/launch_mode_test.clj`, and `test/meta_flow/runtime/codex/process_handle_test.clj`.
- For cancellation or recovery changes around launched runs, check the `test/meta_flow/runtime/codex_launch/` suite.
- For worker execution, prompt wrapping, or helper-event packaging changes, check `test/meta_flow/runtime/codex/worker_wrapper/`, `test/meta_flow/runtime/codex_units/`, and `test/meta_flow/runtime/codex/managed_worker_test.clj`.
- For worker bridge and callback behavior, check `test/meta_flow/runtime/codex_worker_api/` and `test/meta_flow/runtime/codex_units/`.
