# Abstract Workflow Architecture

This document captures the reusable workflow model behind Meta-Flow.
It is intentionally generic and does not describe any single task domain.

## Purpose

The architecture separates:

- task ingress
- authoritative workflow state
- bounded scheduling decisions
- task-scoped execution
- artifact validation

The goal is to keep the control plane deterministic and recoverable while still allowing different execution backends and task types.

## Main Roles

At the abstract level, the system has these parts:

1. `Task Source`
2. `State Store`
3. `Scheduler`
4. `Task Worker`
5. `Validator`
6. `Runtime Adapter`
7. `Artifact Store`

## Core Principles

- The state store is the only source of workflow truth.
- Tasks and runs are separate lifecycle entities.
- Scheduling decisions must be bounded by explicit task and collection state.
- Workers are short-lived execution units, not owners of global state.
- Runtime-specific behavior stays behind a runtime adapter.
- Artifacts are validation targets and outputs, not scheduling truth.

## Boundary Model

### Input Boundary

`Task Source` is outside the workflow engine.
It only needs to produce a stable task descriptor that can be enqueued.

Examples of upstream sources:

- API ingestion
- database scans
- file drops
- manual task creation

### Host System

The host system owns:

- definitions
- runtime state
- scheduler logic
- validation logic
- runtime adapter selection

### Output Boundary

`Artifact Store` is also outside the orchestration truth boundary.
It holds outputs that validators and downstream consumers inspect.

## Control Flow

The generic control loop is:

1. ingest or enqueue tasks
2. select runnable work from authoritative state
3. create or advance runs
4. dispatch a worker through a runtime adapter
5. ingest worker events and progress
6. validate produced artifacts
7. converge the task toward completion, retry, or escalation

## What The Scheduler Owns

The scheduler owns control decisions such as:

- when to dispatch work
- when to recover expired execution
- when to validate
- when to retry
- when to stop retrying

The scheduler should not perform task-specific deep execution itself.

## What The Worker Owns

The worker owns task-scoped execution such as:

- processing one task attempt
- using a bounded tool and runtime surface
- emitting progress and heartbeat
- producing artifacts

The worker should not own collection-level scheduling logic.

## Runtime Adapter Role

The runtime adapter isolates concrete execution details from the generic control plane.
That includes concerns such as:

- local mock execution
- external process launch
- project-scoped runtime homes
- environment shaping
- prompt or wrapper setup
- polling and cancellation semantics

## Artifact Contract

Artifacts should be treated as a contract rather than as an ad hoc directory convention.
A useful artifact contract typically specifies:

- expected files or outputs
- stable location or identifier
- machine-readable metadata
- enough structure for validation

## Related Docs

- [architecture/overview.md](architecture/overview.md)
- [clojure-workflow-data-model.md](clojure-workflow-data-model.md)

