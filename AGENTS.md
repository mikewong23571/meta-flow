use formpatch skill modify clj source code

# Repository Guidelines

## Project Structure & Module Organization

Application code lives in `src/meta_flow/`. Read the codebase in layers rather than as a flat file list:

- Entry points and shared facades: `main.clj`, `cli.clj`, and `scheduler.clj` are the main public entry points; shared infrastructure sits in `db.clj`, `sql.clj`, and `schema.clj`.
- Core workflow domains:
  - `control/` handles event ingestion, FSM logic, and projections.
  - `defs/` handles workflow-definition loading, indexing, validation, and repository access.
  - `scheduler/` contains orchestration, dispatch, step execution, retries, runtime coordination, and developer/demo helpers.
- Runtime and persistence:
  - `runtime/` defines runtime contracts plus the concrete `codex/` and `mock/` adapters.
  - `store/` defines storage contracts and the SQLite-backed implementation.
- Service and UI:
  - `service/` contains service-layer validation.
  - `ui/` contains Ring/HTTP wiring, middleware, and handlers for defs, scheduler, and tasks.
- Governance and tests:
  - `lint/` contains lint, governance, and coverage checks.
  - `test/meta_flow/` broadly mirrors the production layout, with deeper splits where coverage is dense, especially under `scheduler/`, `runtime/`, `store/`, and `ui/`.
- Bundled assets and docs:
  - `resources/meta_flow/` holds EDN definitions, SQL migrations, prompt assets, and Codex home fixtures.
  - `docs/architecture/` holds active architecture references; `docs/archive/` is historical background only.

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
