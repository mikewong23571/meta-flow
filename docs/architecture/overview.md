# Meta-Flow Architecture Overview

Meta-Flow is a workflow orchestration host for unattended execution.
It separates static workflow definitions from mutable runtime state and drives all control
decisions from SQLite-backed truth.

## Core Model

The system is built around these roles:

- `DefinitionRepository`
  Loads versioned workflow definitions from `resources/meta_flow/defs/`.
- `StateStore`
  Persists tasks, runs, leases, run events, artifacts, assessments, dispositions, and collection state.
- `ProjectionReader`
  Provides read-only scheduler inputs derived from authoritative state.
- `Scheduler`
  Runs one patrol-style control cycle at a time.
- `RuntimeAdapter`
  Executes one run through a concrete backend such as mock or Codex.
- `ValidationService`
  Assesses produced artifacts against the task's contract.

## State Boundaries

Meta-Flow keeps three layers separate:

- Definition data
  Versioned EDN configuration that describes task types, FSMs, runtime profiles, validators, and resource policies.
- Runtime state
  Authoritative persisted workflow state in SQLite.
- Projection
  Read-only derived views used to support bounded scheduler decisions.

The scheduler writes canonical state through the store.
It does not treat projections, filesystem artifacts, or runtime memory as truth.

## Task And Run Lifecycles

Task and run are separate lifecycles.

- A task is the durable logical work item.
- A run is one execution attempt for that task.
- Retries create new runs instead of mutating a single long-lived execution record.

That split is what makes retries, recovery, inspection, and validation auditable.

## Scheduler Cycle

One scheduler cycle does the following:

1. recover expired leases
2. poll active runs
3. recover heartbeat timeouts
4. validate runs awaiting validation
5. dispatch runnable work within resource-policy limits
6. requeue or escalate retryable failures

The public entrypoint is the single-cycle scheduler path exposed through the CLI:

```bash
clojure -M -m meta-flow.main scheduler once
```

## Runtime Adapters

The repository currently ships with two runtime adapters:

- mock
  Deterministic local runtime used by demos and tests
- Codex
  External worker runtime with project-level `CODEX_HOME`, helper callbacks, and durable process-state tracking

Runtime-specific behavior lives behind the runtime protocol and registry.
The scheduler remains generic.

## Definitions

Workflow behavior is data-driven.
Bundled definition files include:

- `workflow.edn`
- `task-types.edn`
- `task-fsms.edn`
- `run-fsms.edn`
- `runtime-profiles.edn`
- `artifact-contracts.edn`
- `validators.edn`
- `resource-policies.edn`

Task and run records persist pinned definition refs so new code or new definition versions do not silently rewrite existing in-flight meaning.

## Storage

SQLite is the single source of truth.
The repository persists both structured control columns and canonical EDN/JSON payloads to keep the system:

- queryable
- auditable
- replay-friendly
- evolvable

Important invariants include:

- work-key idempotency for enqueue
- one active lease per run
- one non-terminal run per task
- idempotent event ingestion via event idempotency keys

## Current Code Layout

Primary source areas:

- `src/meta_flow/control/`
  Event ingestion, FSM helpers, and projection reads
- `src/meta_flow/defs/`
  Definition loading, indexing, validation, and repository access
- `src/meta_flow/scheduler/`
  Scheduler orchestration, runtime interaction, retry logic, and timeout handling
- `src/meta_flow/runtime/`
  Runtime protocol plus mock and Codex adapters
- `src/meta_flow/store/`
  Store protocol and SQLite implementation
- `src/meta_flow/cli.clj`
  Human-facing operational commands

## Related Docs

- [extension-guide.md](extension-guide.md)
- [../cli-reference.md](../cli-reference.md)
- [../codex-runtime.md](../codex-runtime.md)
