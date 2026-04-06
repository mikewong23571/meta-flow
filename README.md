# Meta-Flow

Meta-Flow is a Clojure workflow orchestration host for unattended task execution.
It keeps authoritative runtime state in SQLite, drives task and run lifecycles through
versioned definitions, and dispatches work through pluggable runtime adapters.

The repository currently ships with:

- a SQLite-backed control plane
- a mock runtime for deterministic local testing
- a Codex runtime adapter with project-level `CODEX_HOME` support
- CLI flows for enqueueing work, running one scheduler cycle, demos, and inspection

## Quick Start

Prerequisites:

- JDK 21
- Clojure CLI 1.12+
- Babashka (`bb`)

Initialize local state:

```bash
bb init
bb defs:validate
```

Run the default local gate:

```bash
bb check
```

Run the whole-repository aggregate gate:

```bash
bb check:full
```

Start the preview UI:

```bash
bb ui:api
cd ui && bb install
cd ui && bb watch
```

Then open `http://localhost:8787`.

Exercise the local happy path:

```bash
clojure -M -m meta-flow.main demo happy-path
```

Run a single scheduler cycle against persisted state:

```bash
clojure -M -m meta-flow.main scheduler once
```

## CLI

The current CLI surface is:

```bash
clojure -M -m meta-flow.main init
clojure -M -m meta-flow.main defs validate
clojure -M -m meta-flow.main runtime init-codex-home
clojure -M -m meta-flow.main enqueue [--work-key <work-key>]
clojure -M -m meta-flow.main scheduler once
clojure -M -m meta-flow.main demo happy-path
clojure -M -m meta-flow.main demo retry-path
META_FLOW_ENABLE_CODEX_SMOKE=1 clojure -M -m meta-flow.main demo codex-smoke
clojure -M -m meta-flow.main inspect task --task-id <task-id>
clojure -M -m meta-flow.main inspect run --run-id <run-id>
clojure -M -m meta-flow.main inspect collection
```

See [docs/cli-reference.md](docs/cli-reference.md) for command behavior and operational notes.

## Documentation

Current docs:

- [docs/architecture/overview.md](docs/architecture/overview.md)
  Architecture, runtime boundaries, and main control flow.
- [docs/architecture/extension-guide.md](docs/architecture/extension-guide.md)
  How to add task types, runtime adapters, validators, and scheduler behavior.
- [docs/architecture/arch-lint-rules.md](docs/architecture/arch-lint-rules.md)
  Implemented architectural governance rules.
- [docs/cli-reference.md](docs/cli-reference.md)
  Human-facing CLI command reference.
- [docs/codex-runtime.md](docs/codex-runtime.md)
  Codex runtime adapter, `CODEX_HOME`, launch modes, and smoke testing.
- [docs/frontend-clojurescript.md](docs/frontend-clojurescript.md)
  Frontend stack decision, ClojureScript best practices, and browser/backend boundary rules.
- [docs/internal-flow.md](docs/internal-flow.md)
  Internal control flow, data flow, scheduler loop, and runtime boundaries.
- [dev.md](dev.md)
  Local development workflow and task-runner usage.

Historical material:

- [docs/archive/README.md](docs/archive/README.md)
  Archived plans, review notes, and superseded design documents.

## Repository Layout

- `src/meta_flow/` application code
- `test/meta_flow/` tests
- `resources/meta_flow/defs/` versioned workflow definitions
- `resources/meta_flow/sql/` SQLite bootstrap and migrations
- `resources/meta_flow/prompts/` worker prompts
- `resources/meta_flow/codex_home/` bundled `CODEX_HOME` templates
- `docs/` current repository documentation
- `ui/` browser preview app and static assets
  This subproject owns its own `bb` tasks and check pipeline; run UI checks from `ui/`, while root `bb check:full` mounts the UI as one governance node.
