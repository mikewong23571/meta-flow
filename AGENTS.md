# Repository Guidelines

## Project Structure & Module Organization

Application code lives in `src/meta_flow/`. Top-level entry and shared namespaces include `main.clj`, `cli.clj`, `db.clj`, `sql.clj`, `schema.clj`, and the public scheduler facade in `scheduler.clj`; CLI flows are split further under `cli/commands.clj` and `cli/inspect.clj`. Control-plane primitives live under `control/`, with event ingestion, event constants, FSM helpers, a projection facade in `control/projection.clj`, and snapshot helpers in `control/projection/snapshot.clj`. Workflow definition loading and metadata live under `defs/`, where `loader.clj` fronts `protocol.clj`, `source.clj`, `index.clj`, `validation.clj`, and `repository.clj`. Scheduler internals are split by responsibility under `scheduler/`, including state and validation, step execution, retry policy, shared helpers, runtime orchestration in `scheduler/runtime.clj` plus `scheduler/runtime/{run,timeout}.clj`, dispatch logic in `scheduler/dispatch/core.clj`, and developer/demo helpers in `scheduler/dev.clj` plus `scheduler/dev/{demo,inspect,task}.clj`. Runtime adapters live under `runtime/`; shared contracts stay in `runtime/protocol.clj` and `runtime/registry.clj`, the mock adapter is split across `runtime/mock.clj` plus `runtime/mock/{fs,events,execution}.clj`, and the Codex adapter is split across `runtime/codex.clj` plus helper, home, filesystem, events, worker, worker prompt, worker API, execution, execution dispatch, launch support, and process launch/state namespaces. Storage code lives under `store/`; the protocol is in `store/protocol.clj`, and the SQLite implementation keeps its facade in `store/sqlite.clj` with shared helpers plus task, runs, run-data, lease, run-row/lifecycle/event, and artifact assessment/disposition persistence separated under `store/sqlite/`, including subareas for `run/`, `lease/`, and `artifact/`. UI entrypoints now live under `ui/`, with Ring/HTTP wiring in `ui/http.clj` and scheduler/task-facing handlers in `ui/scheduler.clj` and `ui/tasks.clj`. Lint and governance code lives under `lint/`, with entry namespaces in `lint/check.clj`, `lint/coverage.clj`, and `lint/file_length.clj`; detailed lint execution is split across `lint/check/{execution,report,shared}.clj`, and frontend-specific checks/build/style rules live under `lint/check/frontend/`, while coverage helpers live under `lint/coverage/`. Service-layer validation helpers live in `service/validation.clj`. Tests live in `test/meta_flow/`, organized by subsystem under `arch/`, `cli/`, `control/`, `db/`, `defs/`, `lint/`, `runtime/`, `scheduler/`, `service/`, `store/`, and `ui/`; scheduler fixtures live under `test/meta_flow/scheduler/support/`, scheduler coverage is further split across `dispatch/`, `heartbeat/`, `recovery/`, and `runtime/`, store coverage includes focused SQLite `run/` tests, and runtime coverage is split across `runtime/codex/`, `runtime/codex_launch/`, `runtime/codex_units/`, and `runtime/codex_worker_api/` with additional worker-wrapper support under `runtime/codex/worker_wrapper/`. Repository data and runtime inputs live under `resources/meta_flow/`: workflow definitions in `defs/*.edn`, SQL migrations in `sql/*.sql`, prompt assets in `prompts/`, and bundled Codex home/config fixtures under `codex_home/`. Current repository documentation lives in `docs/`, including active architecture references under `docs/architecture/`; archived historical material under `docs/archive/` is not part of the active reference set and does not need to be consulted when updating this file.

## Build, Test, and Development Commands

Use the local Babashka task runner; it wraps the repo’s Clojure aliases and avoids global tool drift.

- `bb repl` starts nREPL on port `7888`.
- `bb test` runs the Kaocha suite once.
- `bb test:watch` reruns tests on file changes.
- `bb lint` runs `clj-kondo` on `src` and `test`, then applies governance on both `src/` and `test/`: file length over 240 lines warns and over 300 lines fails; directory width over 7 direct source files warns and over 12 fails. The warning and error text explicitly frame these as responsibility and layering signals, and ask for splitting by responsibility rather than continuing to grow the same namespace or directory layer.
- `bb coverage` runs Kaocha with Cloverage and applies overall line-coverage governance: below 88% warns and below 85% fails, with messaging that frames coverage as a responsibility-governance signal rather than a vanity metric.
- `bb fmt` rewrites formatting with `cljfmt`.
- `bb fmt:check` verifies formatting without edits.
- `bb check` runs the unified governance gate with concise output across format hygiene, static analysis, structure governance, frontend governance, executable correctness, and coverage; treat it as the pre-PR gate.
- `bb init` initializes the SQLite DB and runtime directories.
- `bb defs:validate` validates bundled EDN workflow definitions.
- `clojure -T:build prep` copies `src` and `resources` into `target/classes`.

## Coding Style & Naming Conventions

Follow idiomatic Clojure with two-space indentation and small, focused namespaces. Namespace names use hyphens while file paths use underscores: `meta-flow.scheduler` maps to `src/meta_flow/scheduler.clj`. Prefer `kebab-case` for vars/functions, `SCREAMING_SNAKE_CASE` only for true constants, and namespaced keywords for persisted domain data such as `:task/state`. Run `bb fmt` and `bb lint` before submitting changes.

## Compatibility Posture

This repository is still in an aggressive development stage. Do not treat backward compatibility of task data structures, task definition refs, or persisted task-shape semantics as a default requirement.

- Prefer the simplest current design for task schema and definition evolution, even if that changes the meaning of previously pinned task versions.
- Do not block task-model or task-definition changes solely to preserve replay/backfill/upgrade compatibility for existing task rows or historical task-type/task-fsm/resource-policy pins.
- Only preserve or add compatibility paths for task data and task definitions when the user explicitly asks for it.

## Testing Guidelines

Tests use Kaocha with `clojure.test`; configuration is in `tests.edn`. Add tests beside the covered namespace in `test/meta_flow/`, and name files `*_test.clj` (for example `scheduler/demo_test.clj`). Favor deterministic unit tests plus DB-backed integration coverage for scheduler, store, and CLI behavior. Coverage governance is part of the repo policy: overall line coverage below 88% warns and below 85% fails. Run `bb test` locally and `bb check` before opening a PR.

## Commit & Pull Request Guidelines

Recent history favors short, imperative commits, often with Conventional Commit prefixes such as `feat:`, `docs:`, and `chore:`. Keep that pattern, for example `feat: persist run disposition columns`. PRs should explain the behavioral change, list validation steps (`bb check` output), link the relevant issue or plan doc, and include terminal output or screenshots when CLI behavior changes.
