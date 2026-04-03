# Codex Runtime

This document describes the current Codex runtime integration in Meta-Flow.
It focuses on the concrete runtime boundary that exists in the repository today.

## Role

The Codex runtime is one `RuntimeAdapter` implementation.
It participates in the same scheduler and store contracts as the mock runtime:

- prepare a run workdir
- dispatch execution
- poll process state
- emit durable run events
- support cancellation and recovery

The control plane remains outside Codex.
SQLite is still the authoritative source of task, run, lease, event, assessment, and disposition state.

## Main Pieces

- `src/meta_flow/runtime/codex.clj`
  Public runtime adapter entrypoint.
- `src/meta_flow/runtime/codex/execution.clj`
  Run preparation, dispatch, polling, and cancellation orchestration.
- `src/meta_flow/runtime/codex/helper.clj`
  Helper-side event callbacks and durable process-state updates.
- `src/meta_flow/runtime/codex/process/launch.clj`
  Launch support, environment shaping, launch modes, and process metadata.
- `src/meta_flow/runtime/codex/home.clj`
  Project-level `CODEX_HOME` installation.
- `resources/meta_flow/codex_home/`
  Bundled `CODEX_HOME` templates.
- `resources/meta_flow/prompts/worker.md`
  Worker prompt injected into Codex runs.

## Project-Level `CODEX_HOME`

The Codex runtime profile points at a project-local `CODEX_HOME` root.
Install the bundled templates with:

```bash
clojure -M -m meta-flow.main runtime init-codex-home
```

The installer:

- creates the target directory if needed
- writes bundled template files when absent
- preserves existing files instead of overwriting them

Bundled templates currently include:

- `README.md`
- `config.edn`

The installed `CODEX_HOME` is runtime-local state, not control-plane truth.
Deleting or recreating it does not mutate SQLite workflow state.

## Launch Modes

The Codex runtime supports two launch modes:

- `:launch.mode/stub-worker`
  Default safe local mode used by the repository gate and tests.
- `:launch.mode/codex-exec`
  Real Codex launch path for explicit smoke testing.

The real launch path ultimately shells out to:

```bash
codex exec --dangerously-bypass-approvals-and-sandbox --skip-git-repo-check ...
```

`--search` is included when the runtime profile enables web search.

## Smoke Testing

The real Codex path is intentionally opt-in:

```bash
META_FLOW_ENABLE_CODEX_SMOKE=1 clojure -M -m meta-flow.main demo codex-smoke
```

Before dispatch, the runtime validates launch support such as:

- whether smoke mode is enabled
- whether the `codex` command is available
- whether required provider environment variables are present

This keeps default local development and CI-style gates independent from Codex installation and provider credentials.

## Run Workdir Contract

Each Codex run gets a workdir containing durable runtime context such as:

- `task.edn`
- `run.edn`
- `runtime-profile.edn`
- `artifact-contract.edn`
- `process.json`
- worker prompt material

`process.json` is the durable runtime-side process state file.
It tracks launch metadata, helper callback state, heartbeat markers, artifact readiness, and cancellation markers.

## Event Flow

Codex worker activity is reflected back into the control plane through normal run events.
The helper layer emits events such as:

- worker started
- heartbeat
- worker exited
- artifact ready

Important boundaries:

- run events still go through the normal event-ingest path
- helper callbacks update `process.json` under file lock
- artifact attachment and event writes are retried on transient SQLite busy errors

## Environment Boundary

The launched process environment is intentionally narrow.
Only variables in the runtime profile allowlist are forwarded, along with:

- `CODEX_HOME`
- `JAVA_HOME`

This keeps runtime capability explicit and reviewable in definitions.

## Related Commands

- `clojure -M -m meta-flow.main runtime init-codex-home`
- `META_FLOW_ENABLE_CODEX_SMOKE=1 clojure -M -m meta-flow.main demo codex-smoke`
- `clojure -M -m meta-flow.main inspect run --run-id <run-id>`

