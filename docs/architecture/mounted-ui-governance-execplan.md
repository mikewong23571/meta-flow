# Mounted UI Governance ExecPlan

This ExecPlan is the durable record of the mounted-UI-governance spike. The spike implementation has been removed from the working tree. The architectural knowledge stays here so future work can start from clarified goals instead of re-running the same experiment.

## Purpose / Big Picture

The repository is trending toward two distinct governance domains:

1. backend and control-plane code under `src/meta_flow/`
2. browser UI code and toolchain under `frontend/`

The long-term goal is to let the root governance layer reason about these as separate nodes. That means `bb check` can eventually focus on backend/core governance, while the UI can own its own checks and expose a stable mounted-node contract to the root layer when a full-repository gate is needed.

This document does not describe the current command surface as shipped code. It describes the validated direction, the knowledge learned from the spike, and the work that would still be required to make that direction production-ready.

## Current Baseline

The working tree now intentionally stays on the stable pre-spike command surface plus the reusable governance-core extraction:

- `bb check` still runs the repository governance gate with backend and frontend checks embedded in one result set.
- `bb ui:governance` remains the explicit frontend governance entrypoint.
- The reusable gate model and gate runner live in `src/meta_flow/governance/`.
- Coverage execution now excludes `meta-flow.governance.*` from in-process Cloverage instrumentation because that fix is required even without the mounted-node split.

The spike-specific command surface has been removed:

- no `bb check:full`
- no root-side mounted UI adapter
- no `--node-edn` frontend node payload path

## Progress

- [x] (2026-04-06) Extracted a reusable governance core into `src/meta_flow/governance/core.clj`, `src/meta_flow/governance/runner.clj`, and `src/meta_flow/governance/report.clj`.
- [x] (2026-04-06) Refactored the root and frontend governance entrypoints to use the shared gate model and shared reporting path.
- [x] (2026-04-06) Fixed a real coverage/orchestration interaction by excluding `meta-flow.governance.*` from in-process Cloverage instrumentation.
- [x] (2026-04-06) Ran a spike proving that the UI can be mounted as one root node instead of being expanded inline in the root gate list.
- [x] (2026-04-07) Removed the spike-only implementation from the working tree after capturing its findings here.
- [ ] Define the durable mounted-node contract for UI governance.
- [ ] Reintroduce the mounted-node split only after the contract, command surface, and failure reporting are hardened.

## Surprises & Discoveries

- Observation: the key problem is governance boundary ownership, not just runtime cost.
  Evidence: the repository already tolerated frontend checks in `bb check`, but the code became harder to reason about because root orchestration and UI policy were assembled in the same namespace.

- Observation: a shared gate model is valuable independently of the mounted-node idea.
  Evidence: `meta-flow.governance.core`, `meta-flow.governance.runner`, and `meta-flow.governance.report` simplified root and frontend execution even after the spike code was removed.

- Observation: in-process coverage instrumentation can corrupt later governance orchestration in the same JVM.
  Evidence: after introducing `meta-flow.governance.*`, `bb check` only became reliable again once coverage excluded those orchestration namespaces from Cloverage instrumentation.

- Observation: the UI can be treated as a mountable node with very little mechanism.
  Evidence: the spike successfully reduced the root/UI contract to one command plus one structured status payload, without requiring the root layer to know frontend-internal rule assembly.

- Observation: the spike was useful for learning but not yet clean enough for production.
  Evidence: the first implementation added temporary flags and command branches that clarified feasibility, but they were not yet the right long-lived interface for the repository.

## Decision Log

- Decision: keep the governance-core extraction.
  Rationale: normalized gates, reusable reporting, and crash-to-gate conversion are valuable regardless of whether UI mounting lands next or later.
  Date/Author: 2026-04-06 / Codex

- Decision: keep the coverage exclusion for `meta-flow.governance.*`.
  Rationale: it fixes a real reliability issue in the current in-process coverage runner and is not spike-specific.
  Date/Author: 2026-04-06 / Codex

- Decision: remove the first mounted-node implementation from the working tree.
  Rationale: the spike answered the feasibility question, but the repository should not keep experimental command flags and partial adapters as if they were settled interfaces.
  Date/Author: 2026-04-07 / Codex

- Decision: retain the mounted-node direction as the planned architecture.
  Rationale: the spike clarified that backend/core governance and UI governance are different ownership domains, and that a mounted-node contract is the right abstraction boundary once hardened.
  Date/Author: 2026-04-07 / Codex

## Outcomes & Retrospective

The spike answered the most important architectural question: yes, UI governance can be mounted as an independent node. The repository does not need the root gate to expand the UI's internal rule list forever.

The cleanup answered a second question that matters just as much: proving feasibility is not the same as shipping the abstraction. The right end state is not "leave spike flags in place". The right end state is "keep the reusable core, remove temporary command branches, and only reintroduce the split when the mounted-node contract is explicit and durable."

This leaves the repository in a better intermediate state:

- the governance core is reusable
- the default command surface is stable again
- the spike knowledge is preserved
- future mounted-node work has a much smaller and clearer scope

## Spike Learnings To Preserve

The spike established these core design constraints:

- The root governance layer should own orchestration policy, not subsystem-internal policy.
- The UI should own its own governance rule assembly.
- The root/UI interface should be a stable node contract, not a shared inline entry list.
- The full-repository gate should compose node results rather than duplicate subsystem rules.
- The mounted-node contract should prefer structured output over scraping human-readable text.

The spike also clarified the likely first contract shape:

```clojure
{:node :ui
 :status :pass
 :headline "ui node passed"
 :summary "8 ui gate(s) evaluated"}
```

That shape was sufficient to prove feasibility. It is not yet a commitment that EDN-on-stdout is the final transport.

## Next Implementation Plan

When work resumes, start from the current stable baseline rather than restoring the spike code verbatim.

1. Define the mounted-node contract first.
   The open choice is whether the root should consume EDN on stdout, JSON, or a file-based result payload. The contract must specify success, warning, error, and crash behavior.
2. Add a thin root-side node adapter.
   The adapter should only run the UI node, capture its result, and normalize it into one governance gate. It should not know UI-internal checks.
3. Narrow the root default gate only after the mounted-node adapter is stable.
   `bb check` can become backend/core-only once the repository has a trustworthy full-repository aggregation path.
4. Reintroduce a full aggregation command only after the command surface is explicit and documented.
5. Update tests around boundary ownership.
   The root tests should prove backend-only behavior once the split lands. UI tests should prove that frontend governance still runs as a self-contained node.

## Validation and Acceptance

The mounted-node design should only be considered ready when all of the following are true:

- `bb check` is semantically stable as the default backend/core gate.
- the UI node has its own explicit governance command and surfaces UI-specific failures without depending on the root entrypoint
- the full-repository command mounts the UI as one node result
- the root layer does not encode the UI's internal rule list
- tests cover normal success, UI-node failure, and malformed node result handling

## Recovery / Rollback Rule

If future mounted-node work becomes unstable, the safe rollback path is the same one used here:

- keep the governance-core extraction
- keep the coverage instrumentation fix
- remove temporary mounted-node command branches
- return to the stable embedded-governance baseline until the contract is clarified

## Artifacts and Notes

The spike produced two durable insights that should not be re-discovered expensively:

- a normalized governance-core layer is worth keeping
- UI mounting is feasible, but should be introduced as a contract-first change rather than as a lingering spike branch

This document is therefore the canonical place to recover spike knowledge after cleanup. Future implementation work should reference this plan instead of attempting to preserve temporary spike code in the main command path.
