# UI Notes

## Scope

This directory owns the HTTP/UI-facing read and create surface for Meta-Flow. It adapts control-plane and definition data into API responses; it does not define scheduler, runtime, or store policy.

Read it by boundary:

- `http.clj` owns route composition, request coercion, and server startup.
- `http/middleware.clj` owns cross-cutting HTTP behavior.
- `http/defs.clj` plus `http/defs/` own defs-specific HTTP handlers, catalog shaping, and request/response schema wiring.
- `defs.clj` and `defs/authoring.clj` assemble definition summaries, detail views, and authoring-facing responses.
- `scheduler.clj` and `tasks.clj` assemble UI-facing views over scheduler state, tasks, and runs.

## Change Rules

- Keep handlers thin. Route-level functions should validate/coerce input and delegate view assembly or command logic downward.
- Do not turn UI code into a second control plane. Scheduler policy, runtime semantics, SQLite row invariants, and workflow-definition truth stay in their owning layers.
- Prefer returning projection/view data rather than leaking raw persistence details or filesystem/runtime-workdir assumptions into HTTP responses.
- When adding new definition-backed fields to UI responses, extend the summary/detail projection here instead of making callers join repository data ad hoc.
- If browser/frontend work expands later, treat this directory as the API contract boundary, not as a place to mirror frontend state logic.

## Validation

- For route, coercion, middleware, or server behavior changes, check `test/meta_flow/ui/http_test.clj` and `test/meta_flow/ui/http_task_types_test.clj`.
- For scheduler/task/run view changes, check `test/meta_flow/ui/scheduler_test.clj` and `test/meta_flow/ui/tasks_test.clj`.
- For definition-backed UI summaries and detail views, check `test/meta_flow/ui/defs_task_type_test.clj` and `test/meta_flow/ui/defs_runtime_profile_test.clj`.
