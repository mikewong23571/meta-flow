# Clojure Workflow Data Model

This document describes the data-model rules that fit the repository as it exists today.
It focuses on canonical shapes, entity boundaries, and persistence semantics.

## Design Goals

The default workflow representation should be:

- plain-data friendly
- EDN friendly
- SQLite friendly
- inspectable in tests and logs
- stable across fresh scheduler invocations

## Data Layers

Meta-Flow keeps three data layers distinct.

### 1. Definition Data

Definition data describes what the workflow is allowed to do.
It is versioned with code and loaded from `resources/meta_flow/defs/`.

Current definition kinds:

- workflow
- task type
- task FSM
- run FSM
- runtime profile
- artifact contract
- validator
- resource policy

### 2. Runtime State

Runtime state is authoritative workflow truth.
It is persisted in SQLite and survives retries, crashes, and fresh scheduler processes.

Current persisted runtime entities are:

- task
- run
- lease
- run event
- artifact
- assessment
- disposition
- collection state

### 3. Projection

Projection is read-only derived state used to support scheduler decisions.
It may be represented by SQL views or read queries, but it is not the writable source of truth.

Examples:

- runnable task candidates
- awaiting-validation runs
- expired lease candidates
- heartbeat-timeout candidates
- collection-level snapshot counts

## Why Plain Maps

Canonical workflow data should use plain Clojure maps.

Reasons:

- maps move cleanly across EDN, JSON, SQLite, logs, and tests
- maps are easy to diff and inspect
- definitions and runtime snapshots can share the same shape discipline
- protocol boundaries do not require records to express domain meaning

`defrecord` may still be useful for localized implementation concerns, but not as the canonical domain format.

## Core Entity Boundaries

### Task

A task is the durable logical work item.

It should carry:

- stable task identity
- stable work key
- task type and pinned definition refs
- source and input data
- current task lifecycle state

A task should not own attempt-specific execution details.

### Run

A run is one execution attempt for a task.

It should carry:

- run identity
- parent task identity
- attempt number
- run lifecycle state
- pinned runtime and FSM refs
- execution-handle and runtime summary data

Retries should create new runs rather than mutating one long-lived execution record into multiple attempts.

### Lease

A lease represents dispatch ownership and expiry state for one run.
It is the mechanism that allows bounded recovery and prevents duplicate active execution.

### Run Event

Run events are append-only control-plane facts emitted by runtime behavior.

They should be:

- idempotent on semantic event identity
- sequenced by the store
- replayable into canonical state transitions

### Artifact

An artifact is the persisted output of a run plus the contract it claims to satisfy.
Artifact state is useful for validation and inspection, but it is not itself the scheduler's source of truth.

### Assessment And Disposition

These are intentionally separate:

- assessment records what validation concluded
- disposition records what control action follows from that assessment

That separation keeps validation facts distinct from scheduler policy.

### Collection State

Collection state represents scheduler-wide controls that are not properties of any single task or run.

Examples include:

- dispatch cooldown
- collection-level resource controls
- snapshot-level counters or control fields

## Definition Pinning

Runtime entities should persist pinned definition refs so behavior remains stable for in-flight work.

Current pinned refs include combinations of:

- task type
- task FSM
- run FSM
- runtime profile
- artifact contract
- validator
- resource policy

This prevents silent reinterpretation when bundled definitions evolve.

## Canonical State vs Projection

A useful rule is:

- canonical state is writable truth
- projection is recomputable read support

That means:

- do not keep the same fact writable in multiple places
- prefer deriving queue counts and candidate lists from authoritative state
- if a projection is materialized, treat it as rebuildable

## Store Shape Guidance

The current repository uses a mixed persistence model:

- structured control columns for queryability and constraints
- canonical payload columns for auditability and evolution

This balance keeps the system operationally useful without turning the store into either:

- opaque blobs that cannot be queried safely
- over-normalized tables that make evolution heavy

## Current Baseline

The current repository baseline does not require conceptual entities like `evidence` or `claim` as first-class persisted tables.
Those may be introduced later for domain-specific workflows, but they are not part of the current minimum workflow host model.

## Related Docs

- [abstract-workflow-architecture.md](abstract-workflow-architecture.md)
- [architecture/overview.md](architecture/overview.md)
- [architecture/extension-guide.md](architecture/extension-guide.md)

