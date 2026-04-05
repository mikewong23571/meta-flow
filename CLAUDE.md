# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## UI Design

All frontend UI work must conform to `docs/design-system.md`. Read it before making any UI changes.

## Commands

All tasks run through `bb`. The full list is in `bb.edn`.

```bash
bb check          # unified governance gate across format, lint, frontend, tests, and coverage — run before every commit (also runs automatically as pre-commit hook)
bb test           # run test suite once via kaocha; supports --focus my.ns
bb test:watch     # rerun tests on file change
bb lint           # clj-kondo static analysis plus src/test governance (file length >240 warn, >300 fail; directory width >7 warn, >12 fail)
bb coverage       # run Cloverage governance (overall line coverage <88% warn, <85% fail)
bb fmt            # reformat source files in place
bb fmt:check      # check formatting without modifying files
bb repl           # start nREPL on port 7888
bb init           # initialize SQLite database and runtime directories
bb defs:validate  # validate EDN workflow definitions in resources/
```

To build:

```bash
clojure -T:build prep   # copy src and resources into target/classes
```

To run a single test namespace:

```bash
bb test --focus meta-flow.db-test
```

## Architecture

`meta-flow` is a workflow orchestration host. It schedules short-lived agent workers against a task queue, tracks execution through FSM-driven state machines, and validates worker-produced artifacts before marking tasks complete. The system is designed for unattended operation: all authoritative state lives in SQLite, workers are stateless, and a fresh scheduler process can always resume from the database.

### Layers

**Definitions layer** (`src/meta_flow/defs/`, `resources/meta_flow/defs/`)
Static, versioned configuration loaded from EDN files at startup. Definitions declare task types, FSMs, artifact contracts, validators, runtime profiles, and resource policies. They are read-only at runtime. `DefinitionRepository` protocol in `defs/protocol.clj`; filesystem implementation in `defs/loader.clj`, backed by `defs/source.clj`, `defs/index.clj`, `defs/validation.clj`, and `defs/repository.clj`.

**State store** (`src/meta_flow/store/`)
Single source of truth for all runtime state. `StateStore` protocol in `store/protocol.clj`; SQLite facade in `store/sqlite.clj` with implementation split across `store/sqlite/` sub-namespaces for tasks, runs, run-data, leases, run-row/lifecycle/event, and artifact assessment/disposition (further split under `store/sqlite/run/`, `store/sqlite/lease/`, and `store/sqlite/artifact/`). Covers tasks, runs, leases, events, artifacts, assessments, and dispositions. All state transitions go through explicit compare-and-set operations. `enqueue-task!` is idempotent by work key.

**Event ingestion** (`src/meta_flow/control/event_ingest.clj`)
The only permitted write path for run events. Callers supply an intent map without `:event/seq`; the store assigns monotonic sequence numbers inside a transaction. Idempotency keys collapse duplicate events.

**Projection layer** (`src/meta_flow/control/projection.clj`)
Read-only query layer on top of SQLite. `ProjectionReader` protocol exposes `load-scheduler-snapshot`, plus list queries for runnable tasks, awaiting-validation runs, and expired leases. All backed by SQL views (`runnable_tasks_v1`, `awaiting_validation_runs_v1`). Snapshot helpers live in `control/projection/snapshot.clj`. Never write through this layer.

**FSM** (`src/meta_flow/control/fsm.clj`)
Pure functions over FSM definition maps. `ensure-transition!` validates and returns the target state or throws. Called by the scheduler to guard every state advance. Tasks and runs each have independent FSMs defined in `resources/meta_flow/defs/task-fsms.edn` and `run-fsms.edn`.

**Scheduler** (`src/meta_flow/scheduler.clj`, `src/meta_flow/scheduler/`)
Control plane. `run-scheduler-step` is the single entry point for one scheduling cycle: it applies the event stream for non-final runs, triggers validation for runs awaiting it, then dispatches new runs up to the resource policy limit. Designed to be called repeatedly as a fresh invocation; no in-process state between calls. Internals are split under `scheduler/` by responsibility: state/validation, step execution, retry policy, shared helpers, runtime orchestration in `scheduler/runtime.clj` (plus `scheduler/runtime/run.clj` and `scheduler/runtime/timeout.clj`), dispatch logic in `scheduler/dispatch/core.clj`, and developer/demo helpers in `scheduler/dev.clj` (plus `scheduler/dev/{demo,inspect,task}.clj`).

**Runtime adapter** (`src/meta_flow/runtime/`)
`RuntimeAdapter` protocol in `runtime/protocol.clj` — `prepare-run!`, `dispatch-run!`, `poll-run!`, `cancel-run!`. Shared contracts also in `runtime/registry.clj`. The mock adapter is split across `runtime/mock.clj` plus `runtime/mock/{fs,events,execution}.clj`. The Codex adapter lives in `runtime/codex.clj` with sub-namespaces for helper, home, filesystem, events, worker, worker prompt, worker API, execution, execution dispatch, launch support, and process launch/state.

**Validation service** (`src/meta_flow/service/validation.clj`)
Called by the scheduler after a run reaches `awaiting-validation`. Loads the artifact contract from definitions, checks the produced artifact, and records an assessment and disposition.

**UI layer** (`src/meta_flow/ui/`)
Ring/HTTP wiring in `ui/http.clj`; scheduler-facing handlers in `ui/scheduler.clj` and task-facing handlers in `ui/tasks.clj`.

**Lint / governance** (`src/meta_flow/lint/`)
Entry namespaces in `lint/check.clj`, `lint/coverage.clj`, and `lint/file_length.clj`. Detailed lint execution split across `lint/check/{execution,report,shared}.clj`; frontend-specific checks, build, and style rules live under `lint/check/frontend/`; coverage helpers live under `lint/coverage/`.

**Database / SQL utilities** (`src/meta_flow/db.clj`, `src/meta_flow/sql.clj`)
`db/open-connection` applies required SQLite pragmas (`WAL`, `foreign_keys`, `busy_timeout`) on every connection — this is connection-scoped, not file-scoped. `sql/edn->text` / `sql/text->edn` handle the EDN blob columns. Keywords persist as their full string representation including the leading colon (e.g. `:task.state/queued`); SQL views and partial unique indexes match against this form.

### Key invariants

- `StateStore` and `DefinitionRepository` are separate. Definitions never flow through the store.
- All run events must go through `control.event-ingest/ingest-run-event!`, never directly to `StateStore`.
- `ProjectionReader` is read-only. Scheduler inputs come only from projections.
- Task lifecycle and run lifecycle are independent. `create-run!` does not advance task state; callers must call `transition-task!` explicitly.
- SQLite keyword-text state values always include the leading `:`. Indexes and views depend on this.

## Coding Style

Follow idiomatic Clojure with two-space indentation and small, focused namespaces. Namespace names use hyphens while file paths use underscores: `meta-flow.scheduler` maps to `src/meta_flow/scheduler.clj`. Prefer `kebab-case` for vars/functions, `SCREAMING_SNAKE_CASE` only for true constants, and namespaced keywords for persisted domain data such as `:task/state`. Run `bb fmt` and `bb lint` before submitting changes.

## Compatibility Posture

This repository is in an aggressive development stage. Do not treat backward compatibility of task data structures, task definition refs, or persisted task-shape semantics as a default requirement.

- Prefer the simplest current design for task schema and definition evolution, even if that changes the meaning of previously pinned task versions.
- Do not block task-model or task-definition changes solely to preserve replay/backfill/upgrade compatibility for existing task rows or historical task-type/task-fsm/resource-policy pins.
- Only preserve or add compatibility paths for task data and task definitions when explicitly asked.

## Testing Guidelines

Tests use Kaocha with `clojure.test`; configuration is in `tests.edn`. Add tests beside the covered namespace in `test/meta_flow/`, and name files `*_test.clj` (e.g. `scheduler/demo_test.clj`). Favor deterministic unit tests plus DB-backed integration coverage for scheduler, store, and CLI behavior. Overall line coverage below 88% warns and below 85% fails. Run `bb test` locally and `bb check` before opening a PR.

## Commit & Pull Request Guidelines

Use short, imperative commits with Conventional Commit prefixes: `feat:`, `fix:`, `docs:`, `chore:`, etc. (e.g. `feat: persist run disposition columns`). PRs should explain the behavioral change, list validation steps (`bb check` output), link the relevant issue or plan doc, and include terminal output or screenshots when CLI behavior changes.
