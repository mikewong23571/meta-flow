# Clojure Workflow Data Model

> This document records the recommended Clojure data model for the abstract workflow architecture.
> It focuses on data structure design, invariants, and state boundaries, not implementation details.

## Purpose

The workflow described in [abstract-workflow-architecture.md](/home/mikewong/proj/main/meta-flow/docs/abstract-workflow-architecture.md) is a data-driven state machine system.

Before implementing scheduler, worker, validator, or runtime adapter behavior in Clojure, the system needs a stable data model that makes these concerns explicit:

- what is static definition data
- what is authoritative runtime state
- what is derived projection
- what belongs to task lifecycle vs run lifecycle
- what belongs to evidence assessment vs scheduler decision
- what is append-only history vs mutable control state

This document defines that model.

## Design Principles

The Clojure model should follow these rules:

- use plain maps as the canonical representation
- use namespaced keywords for all keys and enum-like values
- treat schemas and state machines as first-class data
- keep static definitions separate from mutable runtime state
- keep task state separate from run state
- make `run` a first-class control-plane entity
- distinguish canonical state from materialized projections
- record definition-version bindings inside runtime state
- model worker output as artifact plus evidence, not just a path
- separate assessment facts from scheduler disposition
- model worker notifications as append-only events, not destructive messages
- keep collection-level resource policy explicit

In practice, this means the default representation should be:

- EDN-friendly
- SQLite-friendly
- audit-friendly
- replay-friendly
- test-friendly

## Why Plain Maps

For this workflow, plain maps are a better default than `defrecord`.

Reasons:

- the system is driven by inspectable state and schema, not by object identity
- entities need to move cleanly across EDN, JSON, SQLite, logs, and tests
- state transitions are easier to validate and diff when the data is uniform
- static definitions and runtime snapshots should have the same shape discipline

`defrecord` can still be introduced later for local optimization or protocol dispatch, but it should not define the canonical domain shape.

## Three Data Layers

The model should be split into three layers.

### 1. Definition Data

Definition data is versioned with code and describes what the workflow is.

Examples:

- workflow definition
- task type definition
- task state machine definition
- run state machine definition
- runtime profile definition
- artifact contract definition
- validator definition
- resource policy definition

This data is mostly static and should not be mutated during execution.

### 2. Runtime State

Runtime state is the authoritative workflow truth.

Examples:

- tasks
- runs
- leases
- events
- artifacts
- evidence records
- claims
- assessments
- dispositions
- collection control state

This is the data that must survive fresh scheduler sessions, worker exits, retries, and recovery.

### 3. Projection

Projection is recomputed from authoritative state and exists to support bounded scheduling decisions and efficient reads.

Examples:

- queue depth
- active run count
- retry backlog
- notification backlog
- runnable task candidates
- task summary pointers such as current run or latest run

Projection is useful, but it is not the system of record.

## Canonical State vs Projection

This distinction must be explicit.

Canonical state is the data that business logic is allowed to write as truth.

Projection is a derived view that may be materialized for convenience or performance.

Recommended rules:

- do not keep the same fact writable in multiple places
- if a field can be recomputed from authoritative history, default it to projection
- if a projection is materialized, document its owner and rebuild rule

Examples of projection-friendly fields:

- current run pointer
- latest run pointer
- attempt count
- retry count
- queued count
- validation backlog count

## Core Entity Set

The workflow should start with these core entities:

1. `workflow-def`
2. `task-type-def`
3. `run-fsm-def`
4. `task`
5. `run`
6. `lease`
7. `event`
8. `artifact`
9. `evidence`
10. `claim`
11. `assessment`
12. `disposition`
13. `collection-state`

These entities map directly to the abstract architecture:

- `task source` produces `task`
- `state store` persists task, run, lease, event, artifact, evidence, claim, assessment, disposition, and collection state
- `scheduler` reads runtime state and projection
- `worker` advances `run`, emits `event`, and produces `artifact`
- `validator` produces `assessment`
- `scheduler` converts assessment into `disposition`
- `artifact store` is represented through `artifact` and the artifact contract

## Entity Boundaries

The most important design boundary is:

- `task` represents the long-lived business unit of work
- `run` represents one execution attempt for a task

This distinction should remain strict.

### `task`

`task` should contain:

- stable task identity
- stable task work key
- task type
- upstream source reference
- task input
- task policy
- current task lifecycle state
- definition-version binding

`task` should not embed run execution details, lease details, or counters that are naturally derived from run history.

### `run`

`run` should contain:

- run identity
- parent task identity
- execution attempt number
- run execution state
- lease pointer
- worker identity
- heartbeat and progress summary
- execution result summary
- definition-version bindings for runtime behavior

`run` is the primary control-plane entity for scheduling, timeout handling, takeover, and validation entry.

## Recommended Top-Level Shape

For in-memory modeling, simulation, and tests, the workflow state can be represented as:

```clj
{:workflow/defs
 {:task-types {...}
  :task-fsms {...}
  :run-fsms {...}
  :artifact-contracts {...}
  :validators {...}
  :runtime-profiles {...}
  :resource-policies {...}}

 :state/tasks        {#uuid "..." {...}}
 :state/runs         {#uuid "..." {...}}
 :state/leases       {#uuid "..." {...}}
 :state/events       {#uuid "..." {...}}
 :state/artifacts    {#uuid "..." {...}}
 :state/evidence     {#uuid "..." {...}}
 :state/claims       {#uuid "..." {...}}
 :state/assessments  {#uuid "..." {...}}
 :state/dispositions {#uuid "..." {...}}
 :state/collections  {:collection/default {...}}

 :projection/task-index {...}
 :projection/scheduler-snapshot {...}}
```

Meaning:

- `:workflow/defs` is definition data
- `:state/*` is authoritative runtime state
- `:projection/*` is recomputable or rebuildable read support

This shape is useful even if the persistent store is relational.

## Definition Layer

The definition layer should describe what may happen.

### `task-type-def`

The most important definition entity is `task-type-def`.

It should define:

- input contract
- policy contract
- task state machine
- run state machine
- runtime profile
- artifact contract
- validator
- resource policy class

Example:

```clj
{:task-type/id :task-type/default
 :task-type/version 1
 :task-type/input-schema :schema/task-input
 :task-type/policy-schema :schema/task-policy
 :task-type/task-fsm :task-fsm/default
 :task-type/run-fsm :run-fsm/default
 :task-type/runtime-profile :runtime-profile/codex-default
 :task-type/artifact-contract :artifact-contract/default
 :task-type/validator :validator/default
 :task-type/resource-policy :resource-policy/default}
```

### `task-fsm-def`

The task FSM defines the long-lived business lifecycle.

Example:

```clj
{:task-fsm/id :task-fsm/default
 :task-fsm/version 1
 :task-fsm/initial :task.state/queued
 :task-fsm/terminal #{:task.state/completed
                      :task.state/needs-review}
 :task-fsm/transitions
 {:task.event/lease
  {:from #{:task.state/queued :task.state/retryable-failed}
   :to   :task.state/leased}

  :task.event/start-run
  {:from #{:task.state/leased}
   :to   :task.state/running}

  :task.event/await-validation
  {:from #{:task.state/running}
   :to   :task.state/awaiting-validation}

  :task.event/complete
  {:from #{:task.state/awaiting-validation}
   :to   :task.state/completed}

  :task.event/retry
  {:from #{:task.state/running
           :task.state/awaiting-validation}
   :to   :task.state/retryable-failed}

  :task.event/escalate
  {:from #{:task.state/running
           :task.state/awaiting-validation
           :task.state/retryable-failed}
   :to   :task.state/needs-review}}}
```

### `run-fsm-def`

The run FSM defines the short-lived execution lifecycle.

Example:

```clj
{:run-fsm/id :run-fsm/default
 :run-fsm/version 1
 :run-fsm/initial :run.state/created
 :run-fsm/terminal #{:run.state/finalized
                     :run.state/abandoned}
 :run-fsm/transitions
 {:run.event/lease
  {:from #{:run.state/created}
   :to   :run.state/leased}

  :run.event/dispatch
  {:from #{:run.state/leased}
   :to   :run.state/dispatched}

  :run.event/heartbeat
  {:from #{:run.state/dispatched :run.state/running}
   :to   :run.state/running}

  :run.event/worker-exit
  {:from #{:run.state/dispatched :run.state/running}
   :to   :run.state/exited}

  :run.event/artifact-ready
  {:from #{:run.state/exited}
   :to   :run.state/awaiting-validation}

  :run.event/finalize
  {:from #{:run.state/awaiting-validation}
   :to   :run.state/finalized}

  :run.event/takeover
  {:from #{:run.state/leased
           :run.state/dispatched
           :run.state/running}
   :to   :run.state/taken-over}

  :run.event/abandon
  {:from #{:run.state/taken-over
           :run.state/exited}
   :to   :run.state/abandoned}}}
```

The key point is that both state machines should be represented as data, not hidden only inside implementation code.

## Definition Pinning

Runtime state must record which definition versions were in force when it was created or evaluated.

Without this, historical replay and audit become ambiguous as task types, validators, and runtime profiles evolve.

Recommended binding pattern:

```clj
{:task/definition-ref
 {:task-type/id :task-type/default
  :task-type/version 1}}
```

```clj
{:run/task-type-ref
 {:task-type/id :task-type/default
  :task-type/version 1}
 :run/runtime-ref
 {:runtime-profile/id :runtime-profile/codex-default
  :runtime-profile/version 3}
 :run/run-fsm-ref
 {:run-fsm/id :run-fsm/default
  :run-fsm/version 1}}
```

```clj
{:artifact/contract-ref
 {:artifact-contract/id :artifact-contract/default
  :artifact-contract/version 1}}
```

```clj
{:assessment/validator-ref
 {:validator/id :validator/default
  :validator/version 2}}
```

## Task Model

Recommended canonical task shape:

```clj
{:task/id #uuid "..."
 :task/work-key
 {:subject/type :subject/default
  :subject/id "ticket-123"
  :objective :objective/default
  :policy-class :policy-class/default
  :input-revision "rev-1"}

 :task/type :task-type/default
 :task/definition-ref
 {:task-type/id :task-type/default
  :task-type/version 1}

 :task/source
 {:source/kind :source/manual
  :source/ref "ticket-123"}

 :task/input
 {:input/payload {...}}

 :task/policy
 {:policy/priority 50
  :policy/max-attempts 3
  :policy/validation-mode :validation/strict}

 :task/state :task.state/queued
 :task/created-at #inst "2026-04-01T00:00:00.000Z"
 :task/updated-at #inst "2026-04-01T00:00:00.000Z"}
```

Recommended rules:

- separate `:task/source` from `:task/input`
- separate `:task/input` from `:task/policy`
- keep `:task/work-key` stable enough for deduplication and idempotent enqueue
- do not store run counters or current-run pointers in canonical task state by default

## Run Model

Recommended canonical run shape:

```clj
{:run/id #uuid "..."
 :run/task-id #uuid "..."
 :run/attempt 1
 :run/state :run.state/running

 :run/task-type-ref
 {:task-type/id :task-type/default
  :task-type/version 1}

 :run/runtime-ref
 {:runtime-profile/id :runtime-profile/codex-default
  :runtime-profile/version 3}

 :run/run-fsm-ref
 {:run-fsm/id :run-fsm/default
  :run-fsm/version 1}

 :run/active-lease-id #uuid "..."

 :run/worker
 {:worker/id "worker-001"
  :worker/kind :worker/codex-exec}

 :run/progress
 {:progress/stage :stage/research
  :progress/message "searching sources"}

 :run/heartbeat
 {:heartbeat/last-at #inst "2026-04-01T00:02:00.000Z"
  :heartbeat/count 12}

 :run/result
 {:result/exit-status nil
  :result/artifact-id nil
  :result/failure-reason nil}

 :run/created-at #inst "2026-04-01T00:00:00.000Z"
 :run/updated-at #inst "2026-04-01T00:02:00.000Z"}
```

Recommended rules:

- task state and run state must be separate enums
- a task may have many runs over time
- at most one non-terminal run should exist for a task at a time
- run is the unit that owns execution history
- lease details belong to the lease entity; run should point to the active lease

## Lease Model

Lease should be a first-class runtime entity rather than duplicated in multiple places.

Recommended shape:

```clj
{:lease/id #uuid "..."
 :lease/run-id #uuid "..."
 :lease/owner :scheduler/default
 :lease/token "opaque-token"
 :lease/state :lease.state/active
 :lease/acquired-at #inst "2026-04-01T00:00:00.000Z"
 :lease/expires-at #inst "2026-04-01T00:10:00.000Z"
 :lease/released-at nil}
```

Recommended rule:

- there should be at most one active lease for one run at a time

## Event Model

Worker-to-host communication should be modeled as append-only events rather than single-consumer messages.

Recommended shape:

```clj
{:event/id #uuid "..."
 :event/stream [:run #uuid "..."]
 :event/seq 42
 :event/type :event/worker-heartbeat
 :event/task-id #uuid "..."
 :event/run-id #uuid "..."
 :event/payload {:progress/stage :stage/research}
 :event/caused-by
 {:actor/type :worker
  :actor/id "worker-001"}
 :event/emitted-at #inst "2026-04-01T00:02:00.000Z"}
```

Events may include:

- heartbeat
- progress update
- worker exit
- cooldown detected
- manual attention requested
- artifact ready

If consumers need cursors, model them separately from the event itself.

## Artifact Model

Artifacts are output, not orchestration truth.

The data model should distinguish:

- artifact contract definition
- artifact instance
- evidence records inside the artifact
- claims derived from evidence

Example artifact contract:

```clj
{:artifact-contract/id :artifact-contract/default
 :artifact-contract/version 1
 :artifact-contract/required-entries
 #{:artifact/manifest
   :artifact/notes
   :artifact/log}
 :artifact-contract/manifest-schema :schema/artifact-manifest}
```

Example artifact:

```clj
{:artifact/id #uuid "..."
 :artifact/task-id #uuid "..."
 :artifact/run-id #uuid "..."
 :artifact/store :artifact-store/fs
 :artifact/location "/abs/path/..."
 :artifact/contract-ref
 {:artifact-contract/id :artifact-contract/default
  :artifact-contract/version 1}
 :artifact/manifest-summary {...}
 :artifact/created-at #inst "2026-04-01T00:05:00.000Z"}
```

Example evidence:

```clj
{:evidence/id #uuid "..."
 :evidence/artifact-id #uuid "..."
 :evidence/kind :evidence/document
 :evidence/source-url "https://example.com/report"
 :evidence/retrieved-at #inst "2026-04-01T00:03:00.000Z"
 :evidence/content-hash "sha256:..."
 :evidence/provenance
 {:source/type :source/web
  :source/trust-level :trust/medium}}
```

Example claim:

```clj
{:claim/id #uuid "..."
 :claim/artifact-id #uuid "..."
 :claim/type :claim/summary
 :claim/value "example conclusion"
 :claim/supporting-evidence-ids [#uuid "..."]}
```

This model gives validators a stable surface that is richer than a file path and lighter than raw filesystem inspection.

## Assessment and Disposition

Assessment should be separated from scheduler disposition.

### `assessment`

Assessment is a validator-produced fact record about artifact quality or contract satisfaction.

Recommended shape:

```clj
{:assessment/id #uuid "..."
 :assessment/task-id #uuid "..."
 :assessment/run-id #uuid "..."
 :assessment/artifact-id #uuid "..."
 :assessment/outcome :assessment/accepted
 :assessment/findings []
 :assessment/validator-ref
 {:validator/id :validator/default
  :validator/version 2}
 :assessment/checked-at #inst "2026-04-01T00:06:00.000Z"}
```

### `disposition`

Disposition is the scheduler's control decision derived from assessment and current workflow policy.

Recommended shape:

```clj
{:disposition/id #uuid "..."
 :disposition/task-id #uuid "..."
 :disposition/run-id #uuid "..."
 :disposition/from-assessment-id #uuid "..."
 :disposition/action :disposition/retry
 :disposition/reason :reason/insufficient-evidence
 :disposition/decided-at #inst "2026-04-01T00:07:00.000Z"}
```

This split keeps validator behavior audit-friendly and lets scheduler policy evolve without redefining validation facts.

## Collection-Level Resource Policy

The abstract architecture requires explicit collection-level control state.

This should model resource boundaries rather than only ad hoc counters.

Recommended shape:

```clj
{:collection/id :collection/default
 :collection/dispatch
 {:dispatch/paused? false
  :dispatch/reason nil}

 :collection/resources
 [{:resource/key [:provider/openai :task-type/default]
   :resource/max-concurrent 4
   :resource/cooldown-until nil
   :resource/budget-class :budget/default}

  {:resource/key [:provider/github :task-type/default]
   :resource/max-concurrent 8
   :resource/cooldown-until nil
   :resource/budget-class :budget/repo-heavy}]

 :collection/updated-at #inst "2026-04-01T00:00:00.000Z"}
```

This entity should hold bounded scheduler control inputs such as:

- dispatch pause
- concurrency limits
- provider cooldown
- runtime or task-type resource ceilings
- budget-class policy

It should not hold recomputable counters such as queue depth or active run count unless there is a strong operational reason.

## What Should Be Projection Instead of Canonical State

The following scheduler inputs should usually be projection:

- queue depth
- active run count
- retry backlog
- event backlog
- list of runnable tasks
- validation backlog
- current run pointer
- latest run pointer
- attempt count
- retry count

These values may be materialized for performance, but they should still be understood as rebuildable views.

## Recommended Enum Domains

All enum-like values should use namespaced keywords.

Examples:

- task states: `:task.state/queued`, `:task.state/running`, `:task.state/completed`
- run states: `:run.state/created`, `:run.state/running`, `:run.state/finalized`
- lease states: `:lease.state/active`, `:lease.state/released`
- event types: `:event/worker-heartbeat`, `:event/worker-exit`
- assessment outcomes: `:assessment/accepted`, `:assessment/rejected`
- disposition actions: `:disposition/complete`, `:disposition/retry`, `:disposition/escalate`
- provider names: `:provider/openai`

Avoid mixing:

- strings for some enums and keywords for others
- task state values and run state values in the same field
- assessment outcomes and scheduler actions in the same field

## Schema Guidance

The recommended implementation direction is:

- represent domain entities as plain maps
- define schemas separately
- treat schemas as queryable data

For Clojure, Malli is a strong fit because:

- workflow data is already map-oriented
- schemas can remain data-first
- nested contracts are easier to compose
- validation can be used both at boundaries and in tests

Regardless of library choice, the important point is that schema should support:

- input validation
- persistence validation
- task transition validation
- run transition validation
- artifact contract validation
- assessment validation

## Minimal Invariants

If the system needs a minimal rule set to stay coherent, it should enforce these:

1. `task-type-def` owns the task lifecycle contract.
2. `run-fsm-def` owns the execution lifecycle contract.
3. `task` and `run` are different entities and must not be collapsed.
4. runtime state is authoritative; filesystem artifacts are not.
5. one task may have many runs, but at most one non-terminal run at a time.
6. one run may have many leases over history, but at most one active lease at a time.
7. projections must not be treated as independent writable truth.
8. runtime entities must pin the definition versions they depend on.
9. validator writes assessment; scheduler writes disposition.
10. collection-level resource policy must remain explicit and bounded.

## One-Sentence Summary

The Clojure workflow model should represent what may happen as versioned definitions, what has happened as authoritative runtime state, and what the scheduler currently sees as projection, while keeping run control, evidence, assessment, and resource policy explicit from the beginning.
