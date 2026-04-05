# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## UI Design

All frontend UI work must conform to `docs/design-system.md`. Read it before making any UI changes.

## Commands

All tasks run through `bb`. The full list is in `bb.edn`.

```bash
bb check          # fmt:check → lint → test → coverage — run before every commit (also runs automatically as pre-commit hook)
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

To run a single test namespace:

```bash
bb test --focus meta-flow.db-test
```

## Architecture

`meta-flow` is a workflow orchestration host. It schedules short-lived agent workers against a task queue, tracks execution through FSM-driven state machines, and validates worker-produced artifacts before marking tasks complete. The system is designed for unattended operation: all authoritative state lives in SQLite, workers are stateless, and a fresh scheduler process can always resume from the database.

### Layers

**Definitions layer** (`src/meta_flow/defs/`, `resources/meta_flow/defs/`)
Static, versioned configuration loaded from EDN files at startup. Definitions declare task types, FSMs, artifact contracts, validators, runtime profiles, and resource policies. They are read-only at runtime. `DefinitionRepository` protocol in `defs/protocol.clj`; filesystem implementation in `defs/loader.clj`.

**State store** (`src/meta_flow/store/`)
Single source of truth for all runtime state. `StateStore` protocol in `store/protocol.clj`; SQLite implementation in `store/sqlite.clj`. Covers tasks, runs, leases, events, artifacts, assessments, and dispositions. All state transitions go through explicit compare-and-set operations. `enqueue-task!` is idempotent by work key.

**Event ingestion** (`src/meta_flow/control/event_ingest.clj`)
The only permitted write path for run events. Callers supply an intent map without `:event/seq`; the store assigns monotonic sequence numbers inside a transaction. Idempotency keys collapse duplicate events.

**Projection layer** (`src/meta_flow/control/projection.clj`)
Read-only query layer on top of SQLite. `ProjectionReader` protocol exposes `load-scheduler-snapshot`, plus list queries for runnable tasks, awaiting-validation runs, and expired leases. All backed by SQL views (`runnable_tasks_v1`, `awaiting_validation_runs_v1`). Never write through this layer.

**FSM** (`src/meta_flow/control/fsm.clj`)
Pure functions over FSM definition maps. `ensure-transition!` validates and returns the target state or throws. Called by the scheduler to guard every state advance. Tasks and runs each have independent FSMs defined in `resources/meta_flow/defs/task-fsms.edn` and `run-fsms.edn`.

**Scheduler** (`src/meta_flow/scheduler.clj`)
Control plane. `run-scheduler-step` is the single entry point for one scheduling cycle: it applies the event stream for non-final runs, triggers validation for runs awaiting it, then dispatches new runs up to the resource policy limit. Designed to be called repeatedly as a fresh invocation; no in-process state between calls.

**Runtime adapter** (`src/meta_flow/runtime/`)
`RuntimeAdapter` protocol in `runtime/protocol.clj` — `prepare-run!`, `dispatch-run!`, `poll-run!`, `cancel-run!`. The mock implementation in `runtime/mock.clj` writes JSON artifacts to disk and emits synthetic events, used for tests and the demo happy path. Real adapters (e.g. Codex) implement the same protocol.

**Validation service** (`src/meta_flow/service/validation.clj`)
Called by the scheduler after a run reaches `awaiting-validation`. Loads the artifact contract from definitions, checks the produced artifact, and records an assessment and disposition.

**Database / SQL utilities** (`src/meta_flow/db.clj`, `src/meta_flow/sql.clj`)
`db/open-connection` applies required SQLite pragmas (`WAL`, `foreign_keys`, `busy_timeout`) on every connection — this is connection-scoped, not file-scoped. `sql/edn->text` / `sql/text->edn` handle the EDN blob columns. Keywords persist as their full string representation including the leading colon (e.g. `:task.state/queued`); SQL views and partial unique indexes match against this form.

### Key invariants

- `StateStore` and `DefinitionRepository` are separate. Definitions never flow through the store.
- All run events must go through `control.event-ingest/ingest-run-event!`, never directly to `StateStore`.
- `ProjectionReader` is read-only. Scheduler inputs come only from projections.
- Task lifecycle and run lifecycle are independent. `create-run!` does not advance task state; callers must call `transition-task!` explicitly.
- SQLite keyword-text state values always include the leading `:`. Indexes and views depend on this.
