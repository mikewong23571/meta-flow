# Meta-Flow Extension Guide

This document describes the extension seams that exist in the repository today.
It focuses on current code structure rather than historical plans.

## Main Extension Areas

Meta-Flow is designed to be extended in three ways:

- definitions
  Add or revise workflow behavior through versioned EDN data
- protocols and implementations
  Add new runtime, store, or definition backends behind stable interfaces
- scheduler internals
  Add new control-plane behavior inside the existing scheduler phases

## 1. Add A Task Type

Task-type behavior is assembled from definition refs.
The bundled examples live in `resources/meta_flow/defs/task-types.edn`.

For project-local extension work, definitions now load from bundled defaults plus
the top-level `defs/` overlay. Drafts belong under `defs/drafts/` and are not part
of the live repository until explicitly published.

A task type normally pins:

- task FSM
- run FSM
- runtime profile
- artifact contract
- validator
- resource policy

Typical definition files involved:

- `resources/meta_flow/defs/task-types.edn`
- `resources/meta_flow/defs/task-fsms.edn`
- `resources/meta_flow/defs/run-fsms.edn`
- `resources/meta_flow/defs/runtime-profiles.edn`
- `resources/meta_flow/defs/artifact-contracts.edn`
- `resources/meta_flow/defs/validators.edn`
- `resources/meta_flow/defs/resource-policies.edn`

Recommended workflow:

1. define or reuse an artifact contract
2. define or reuse a validator
3. define or reuse a resource policy
4. define or reuse a runtime profile
5. define or reuse task and run FSMs
6. add the task-type record that pins those refs together
7. run `bb defs:validate`

## 1A. Stable Authoring Contract

The first supported authoring contract is clone-first, not schema-first.
That means callers start from an existing `task-type` or `runtime-profile`,
pick a new id and name, and apply only a narrow override map.

Stable request shape for both definition kinds:

- `:authoring/from-id`
- optional `:authoring/from-version`
- `:authoring/new-id`
- `:authoring/new-name`
- optional `:authoring/new-version`
- optional `:authoring/overrides`

First-release `runtime-profile` overrides are limited to:

- `:runtime-profile/web-search-enabled?`
- `:runtime-profile/worker-prompt-path`

First-release `task-type` overrides are limited to:

- `:task-type/runtime-profile-ref`
- `:task-type/input-schema`
- `:task-type/work-key-expr`

Publish-order rule:

- a `task-type` draft may reference only a published `runtime-profile`
- if a task type needs a newly authored runtime profile, publish that runtime profile into `defs/runtime-profiles.edn` before creating or validating the task-type draft

Current contract helpers live in `src/meta_flow/defs/authoring.clj`.
They validate the request shape, resolve the source template, and enforce the
publish-order rule before later CLI or HTTP layers try to write files.

## 2. Add A Runtime Adapter

Runtime adapters implement `RuntimeAdapter` in `src/meta_flow/runtime/protocol.clj`.

Current adapter responsibilities are:

- `adapter-id`
- `prepare-run!`
- `dispatch-run!`
- `poll-run!`
- `cancel-run!`

To add a new adapter:

1. create `src/meta_flow/runtime/<name>.clj`
2. implement the runtime protocol
3. wire the adapter into `src/meta_flow/runtime/registry.clj`
4. add a runtime profile definition that points at the new `:runtime-profile/adapter-id`
5. add tests under `test/meta_flow/runtime/`

Runtime adapters are still a code extension seam.
They are not part of the description-driven or definitions-only authoring contract.

Keep the adapter responsible only for runtime concerns.
Do not move scheduler policy or store transitions into adapter code.

## 3. Extend Validation

Validation currently uses artifact contracts plus validator definitions.
If you need stronger validation than required-path checks:

1. add a new validator type in `resources/meta_flow/defs/validators.edn`
2. extend `src/meta_flow/service/validation.clj`
3. keep `scheduler/validation` focused on orchestration, not domain-specific artifact parsing

Validator engines are also still code-defined.
Adding a new validator type requires Clojure changes; definition authoring only
lets you pin or clone existing validator refs.

## 4. Extend Resource Policy

Resource policy definitions are the right place for execution-budget and dispatch controls such as:

- max active runs
- max attempts
- lease duration
- heartbeat timeout
- queue ordering

When adding a new policy field:

1. extend schema and definition validation
2. pin the value into runtime state where needed
3. update scheduler behavior to read the pinned value, not ad hoc globals
4. add tests for both positive behavior and recovery paths

## 5. Extend Scheduler Behavior

The scheduler is already split by responsibility under `src/meta_flow/scheduler/`.

Current areas include:

- `step.clj`
  top-level cycle orchestration
- `runtime.clj`
  runtime interaction and event application
- `retry.clj`
  retry and escalation decisions
- `validation.clj`
  assessment and disposition orchestration
- `dispatch/`
  dispatch selection and capacity logic
- `runtime/timeout.clj`
  heartbeat timeout logic

Preferred rule:

- add behavior in the narrowest existing namespace that matches the responsibility
- create a new sub-namespace before growing a top-level file past its responsibility boundary

## 6. Add Definitions Or Store Backends

There are two protocol seams for infrastructure replacement:

- `DefinitionRepository`
- `StateStore`

The repository currently ships with:

- filesystem-backed definitions
- SQLite-backed state store

If you add another backend, keep the control-plane contracts unchanged so the scheduler does not grow backend-specific conditionals.

## 7. Tests To Add With Extensions

Any nontrivial extension should come with:

- definition validation tests if new defs or refs are introduced
- scheduler tests if control flow changes
- runtime tests if adapter semantics change
- store tests if persistence shape or invariants change
- arch-lint updates if a new architectural rule is introduced

## Related Docs

- [overview.md](overview.md)
- [arch-lint-rules.md](arch-lint-rules.md)
- [../codex-runtime.md](../codex-runtime.md)
