# CLI Reference

Meta-Flow exposes a small operational CLI through `meta-flow.main`.
All commands run from the repository root.

## Initialization

```bash
clojure -M -m meta-flow.main init
```

Creates or verifies:

- the SQLite database
- runtime directories under `var/`
- bundled definition availability

Typical local bootstrap:

```bash
bb init
bb defs:validate
```

## Definitions

```bash
clojure -M -m meta-flow.main defs init-overlay
clojure -M -m meta-flow.main defs validate
```

Initializes the writable `defs/` overlay and validates the merged bundled-plus-overlay repository.

Clone-first authoring is also available from the CLI:

```bash
clojure -M -m meta-flow.main defs create-runtime-profile \
  --from runtime-profile/codex-worker \
  --new-id runtime-profile/repo-review \
  --name "Codex repo review worker" \
  --worker-prompt-path meta_flow/prompts/worker.md \
  --web-search false

clojure -M -m meta-flow.main defs publish-runtime-profile \
  --id runtime-profile/repo-review \
  --version 1

clojure -M -m meta-flow.main defs create-task-type \
  --from task-type/repo-arch-investigation \
  --new-id task-type/repo-review \
  --name "Repo review" \
  --runtime-profile runtime-profile/repo-review

clojure -M -m meta-flow.main defs publish-task-type \
  --id task-type/repo-review \
  --version 1
```

Notes:

- `create-runtime-profile` supports `--from-version`, `--new-version`, `--worker-prompt-path`, and `--web-search`.
- `create-task-type` supports `--from-version`, `--new-version`, `--runtime-profile`, and `--runtime-profile-version`.
- `--runtime-profile` resolves the latest published version when `--runtime-profile-version` is omitted.
- task-type drafts may only reference published runtime profiles; publish the runtime-profile draft first.

## Runtime Setup

```bash
clojure -M -m meta-flow.main runtime init-codex-home
```

Installs bundled `CODEX_HOME` templates for the Codex runtime profile into the configured project path.
Existing files are preserved.

See [codex-runtime.md](codex-runtime.md) for details.

## Enqueue

```bash
clojure -M -m meta-flow.main enqueue
clojure -M -m meta-flow.main enqueue --work-key demo-123
```

Enqueues a demo task into the SQLite control plane.

Notes:

- `--work-key` is optional.
- enqueue is idempotent on work key.
- if the task already exists, the command reports reuse instead of creating a duplicate task.

## Scheduler

```bash
clojure -M -m meta-flow.main scheduler once
```

Runs one scheduler cycle against persisted state.
The summary includes:

- created runs
- requeued tasks
- escalated tasks
- recovered expired leases
- recovered heartbeat timeouts
- dispatch blocking state
- capacity skips
- pre-step snapshot counts

This is the main operational entry point for patrol-style scheduling.

## Demo Flows

### Happy Path

```bash
clojure -M -m meta-flow.main demo happy-path
```

Runs the deterministic local mock success flow and converges a task to completion.

### Retry Path

```bash
clojure -M -m meta-flow.main demo retry-path
```

Runs a deterministic mock rejection flow and demonstrates validation failure plus retry/escalation logic.

### Codex Smoke

```bash
META_FLOW_ENABLE_CODEX_SMOKE=1 clojure -M -m meta-flow.main demo codex-smoke
```

Runs the explicit opt-in Codex smoke path.

Important:

- smoke is disabled unless `META_FLOW_ENABLE_CODEX_SMOKE=1`
- launch support is validated before dispatch
- default repository test and check flows do not depend on Codex being installed

## Inspect

### Task

```bash
clojure -M -m meta-flow.main inspect task --task-id <task-id>
```

Pretty-prints the current task snapshot from the control plane.

### Run

```bash
clojure -M -m meta-flow.main inspect run --run-id <run-id>
```

Pretty-prints the current run snapshot from the control plane.

### Collection

```bash
clojure -M -m meta-flow.main inspect collection
```

Pretty-prints collection-level scheduler state and summary data.

## Babashka Tasks

Daily development commands go through `bb`:

- `bb repl`
- `bb test`
- `bb test:watch`
- `bb lint`
- `bb coverage`
- `bb fmt`
- `bb fmt:check`
- `bb check`
- `bb check:full`
- `bb check:verbose`
- `bb init`
- `bb defs:validate`

The browser UI is a separate same-repo project under `ui/`. Run UI tasks from there, for example:

- `cd ui && bb install`
- `cd ui && bb watch`
- `cd ui && bb compile:check`
- `cd ui && bb governance`
- `cd ui && bb node`
- `cd ui && bb check`

See [../dev.md](../dev.md) for the local development loop.
