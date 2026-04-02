# Repository Guidelines

## Project Structure & Module Organization

Application code lives in `src/meta_flow/`. Top-level entry and shared domain namespaces include `main.clj`, `cli.clj`, `db.clj`, `sql.clj`, and `schema.clj`. Control-plane primitives are grouped under `control/`, including event ingestion, event constants, FSM helpers, and projection queries. Scheduler code is split between the public facade in `scheduler.clj` and internal implementation namespaces under `scheduler/` such as `step.clj`, `runtime.clj`, `state.clj`, `validation.clj`, `dev.clj`, and `shared.clj`. Runtime adapters live under `runtime/`; the mock adapter keeps its public entrypoint in `runtime/mock.clj` with internal helpers split under `runtime/mock/` across `fs.clj`, `events.clj`, and `execution.clj`, while adapter lookup and contracts live in `runtime/registry.clj` and `runtime/protocol.clj`. Storage code lives under `store/`; the protocol is in `store/protocol.clj`, and the SQLite state store keeps its public entrypoint in `store/sqlite.clj` with internal responsibilities split across `store/sqlite/shared.clj`, `tasks.clj`, `runs.clj`, `run_data.clj`, run internals under `store/sqlite/run/`, lease internals under `store/sqlite/lease/`, and artifact / assessment / disposition internals under `store/sqlite/artifact/`. Definition loading and workflow metadata live under `defs/`; `loader.clj` is the facade over `protocol.clj`, `source.clj`, `index.clj`, `validation.clj`, and `repository.clj`. Service-layer validation helpers live in `service/validation.clj`. Tests live in `test/meta_flow/`, with scheduler-focused coverage and shared fixtures under `test/meta_flow/scheduler/`, SQLite store coverage and fixtures under `test/meta_flow/store/`, and lint governance tests under `test/meta_flow/lint/`. Repository data and runtime inputs live under `resources/meta_flow/`: workflow definitions in `defs/*.edn`, SQL migrations in `sql/*.sql`, and prompt assets in `prompts/`. Architecture notes and execution plans are in `docs/`.

## Build, Test, and Development Commands

Use the local Babashka task runner; it wraps the repo’s Clojure aliases and avoids global tool drift.

- `bb repl` starts nREPL on port `7888`.
- `bb test` runs the Kaocha suite once.
- `bb test:watch` reruns tests on file changes.
- `bb lint` runs `clj-kondo` on `src` and `test`, then applies governance on both `src/` and `test/`: file length over 240 lines warns and over 300 lines fails; directory width over 7 direct source files warns and over 12 fails. The warning and error text explicitly frame these as responsibility and layering signals, and ask for splitting by responsibility rather than continuing to grow the same namespace or directory layer.
- `bb fmt` rewrites formatting with `cljfmt`.
- `bb fmt:check` verifies formatting without edits.
- `bb check` runs `fmt:check`, `lint`, then `test`; treat it as the pre-PR gate.
- `bb init` initializes the SQLite DB and runtime directories.
- `bb defs:validate` validates bundled EDN workflow definitions.
- `clojure -T:build prep` copies `src` and `resources` into `target/classes`.

## Coding Style & Naming Conventions

Follow idiomatic Clojure with two-space indentation and small, focused namespaces. Namespace names use hyphens while file paths use underscores: `meta-flow.scheduler` maps to `src/meta_flow/scheduler.clj`. Prefer `kebab-case` for vars/functions, `SCREAMING_SNAKE_CASE` only for true constants, and namespaced keywords for persisted domain data such as `:task/state`. Run `bb fmt` and `bb lint` before submitting changes.

## Testing Guidelines

Tests use Kaocha with `clojure.test`; configuration is in `tests.edn`. Add tests beside the covered namespace in `test/meta_flow/`, and name files `*_test.clj` (for example `scheduler/demo_test.clj`). Favor deterministic unit tests plus DB-backed integration coverage for scheduler, store, and CLI behavior. Run `bb test` locally and `bb check` before opening a PR.

## Commit & Pull Request Guidelines

Recent history favors short, imperative commits, often with Conventional Commit prefixes such as `feat:`, `docs:`, and `chore:`. Keep that pattern, for example `feat: persist run disposition columns`. PRs should explain the behavioral change, list validation steps (`bb check` output), link the relevant issue or plan doc, and include terminal output or screenshots when CLI behavior changes.
