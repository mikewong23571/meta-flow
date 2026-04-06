# Mounted UI Governance

Current note (2026-04-07): UI checks live under `ui/bb.edn`, and the repository root mounts the UI only as one aggregate governance node via `bb check:full`. Historical details below still reference the earlier root-owned command names and should be read as implementation history, not the current command surface.

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document must be maintained in accordance with `.agent/PLANS.md`.

## Purpose / Big Picture

After this change, a novice should be able to use three governance entrypoints without having to understand the repo’s internal layering. `bb check` should become the stable default gate for backend and control-plane code. `bb ui:governance` should remain the detailed frontend gate that explains browser-specific failures in full. `bb check:full` should become the whole-repository gate that mounts the UI as one summarized node instead of expanding eight frontend rules inline at the root.

The user-visible proof is simple. Before this change, `bb check` prints every frontend governance gate alongside backend gates. After this change, `bb check` no longer lists those frontend gates, `bb ui:governance` still prints them directly, and `bb check:full` prints one mounted UI gate whose action tells the user to run `bb ui:governance` for subsystem detail. That is the architectural point of the work: separate ownership boundaries without losing full-repository observability.

## Progress

- [x] (2026-04-06) Extracted a reusable governance core into `src/meta_flow/governance/core.clj`, `src/meta_flow/governance/runner.clj`, and `src/meta_flow/governance/report.clj`.
- [x] (2026-04-06) Refactored the root and frontend governance entrypoints to use the shared gate model and shared reporting path.
- [x] (2026-04-06) Fixed a real coverage/orchestration interaction by excluding `meta-flow.governance.*` from in-process Cloverage instrumentation.
- [x] (2026-04-06) Ran a spike proving that the UI can be mounted as one root node instead of being expanded inline in the root gate list.
- [x] (2026-04-07) Removed the spike-only implementation from the working tree after capturing its architectural lessons.
- [x] (2026-04-07) Re-read `.agent/PLANS.md` and rewrote this file into a full contract-first ExecPlan instead of a spike summary.
- [x] (2026-04-07) Closed the largest open design question by choosing one durable transport: a machine-facing UI governance command that prints one EDN payload on stdout and nothing else.
- [x] (2026-04-06 17:05Z) Implemented Milestone 1: added `src/meta_flow/governance/node.clj`, added machine-facing `src/meta_flow/lint/check/frontend_node.clj`, and exposed `bb ui:governance:node` as the explicit contract-debugging command.
- [x] (2026-04-06 17:05Z) Implemented Milestone 2: added `src/meta_flow/lint/check/full.clj`, mounted the UI as one summarized gate, and converted blank stdout / unreadable EDN into recoverable governance error gates.
- [x] (2026-04-06 17:05Z) Implemented Milestone 3: narrowed `src/meta_flow/lint/check.clj` to backend/core gate assembly and updated the command surface so `bb check` and `bb check:full` now have distinct responsibilities.
- [x] (2026-04-06 17:05Z) Completed validation: `bb test` passed with `262 tests, 1332 assertions, 0 failures`; `bb ui:governance` passed; `bb ui:governance:node` emitted one EDN payload; `bb check` and `bb check:full` both completed successfully with the repository’s pre-existing directory-width warning under `test/meta_flow/lint`.

## Surprises & Discoveries

- Observation: the real problem is governance boundary ownership, not raw runtime cost.
  Evidence: `src/meta_flow/lint/check.clj` currently assembles backend, frontend, and coverage concerns into one root gate list, so the root layer must know the UI’s internal rule list to produce the default report.

- Observation: the reusable governance core is valuable even if mounted UI work never lands.
  Evidence: `src/meta_flow/governance/core.clj`, `src/meta_flow/governance/runner.clj`, and `src/meta_flow/governance/report.clj` are still useful after the spike code was removed because both root and frontend governance already depend on them.

- Observation: the frontend already has a self-contained governance boundary in code, but the command surface does not yet honor it.
  Evidence: `src/meta_flow/lint/check/frontend.clj` already defines `frontend-gate-entries` and `frontend-gates`, while `src/meta_flow/lint/check.clj` separately inlines those same frontend gates into the root path.

- Observation: in-process coverage instrumentation can corrupt later governance orchestration in the same JVM.
  Evidence: `src/meta_flow/lint/coverage/execution.clj` now excludes `^meta-flow\\.governance\\..*` because the governance/reporting layer must not be instrumented by the same process that later prints and aggregates the final gate result.

- Observation: the spike showed that the transport can stay very small.
  Evidence: the experimental implementation only needed one subprocess call and one structured payload to prove that the root does not need the UI’s internal gate list.

- Observation: the missing piece is not feasibility but a durable contract.
  Evidence: the spike worked, but it depended on temporary command branches and flags such as `--node-edn`, which are exactly the sort of half-settled interface this repository should not preserve.

- Observation: `prn` emits the mounted payload as a namespaced-map literal such as `#:node{:id :ui, ...}`, not a long flat map literal.
  Evidence: the shipped `bb ui:governance:node` command now prints `#:node{:id :ui, :label "mounted-ui-governance", ...}`. This is valid EDN and the root-side parser accepts it without special handling.

- Observation: the mounted split did not eliminate an unrelated existing structure-governance warning.
  Evidence: both `bb check` and `bb check:full` still report `test/meta_flow/lint contains 8 direct source files (threshold 7)`. That warning is pre-existing repository state, not a regression introduced by the mounted-node work.

## Decision Log

- Decision: keep the governance-core extraction in `src/meta_flow/governance/`.
  Rationale: normalized gates, shared crash handling, and shared report printing improve the current codebase independently of mounted-node work.
  Date/Author: 2026-04-06 / Codex

- Decision: keep the coverage exclusion for `meta-flow.governance.*`.
  Rationale: this is a current reliability fix, not a spike artifact.
  Date/Author: 2026-04-06 / Codex

- Decision: remove the first mounted-node implementation from the working tree instead of treating it as production code.
  Rationale: the spike answered the feasibility question but left the repository with a temporary command surface and an unfinished contract.
  Date/Author: 2026-04-07 / Codex

- Decision: the durable transport for the mounted UI node will be one EDN map printed to stdout by a dedicated machine-facing command.
  Rationale: both producer and consumer are Clojure code in the same repository, so EDN avoids a translation layer, preserves keywords naturally, and is easy to validate with the existing governance-core conventions. A dedicated command is clearer than reintroducing ad hoc flags on the human-facing UI governance command.
  Date/Author: 2026-04-07 / Codex

- Decision: `bb ui:governance` remains the human-facing detailed frontend entrypoint, while the mounted-node contract uses a separate machine-facing entrypoint.
  Rationale: the full-repository root gate needs a quiet, parseable contract, but humans still need the detailed frontend report with gate-by-gate evidence and actions.
  Date/Author: 2026-04-07 / Codex

- Decision: the repository will grow a new `bb check:full` command, and only after that command is stable will `bb check` stop inlining frontend gates.
  Rationale: this preserves a safe migration path. The mounted path must be proven before the default root command changes semantics.
  Date/Author: 2026-04-07 / Codex

- Decision: transport failures and malformed node payloads must surface as ordinary governance error gates rather than uncaught exceptions.
  Rationale: the whole point of the governance layer is recoverable, high-signal failure reporting. The root gate should degrade to one explicit “mounted UI node failed” result, not crash the entire report path.
  Date/Author: 2026-04-07 / Codex

- Decision: the default backend/core `bb check` gate now uses `["src" "test"]` as its source roots and no longer inlines frontend rule assembly.
  Rationale: the cutover is only meaningful if the root command actually stops enumerating frontend governance entries. The mounted UI node is now the full-repository path for browser-specific governance.
  Date/Author: 2026-04-06 / Codex

## Outcomes & Retrospective

The spike already answered the architectural yes-or-no question: the UI can be mounted as an independent governance node. The implementation work in this repository has now carried that design through to completion. The root gate no longer expands frontend-internal checks inline, the frontend still owns its own detailed governance command, and the whole-repository command mounts the UI as one summarized node.

The most important implementation result is not merely that a new command exists. The important result is that the boundary is now explicit and recoverable. `bb ui:governance:node` prints exactly one EDN payload, `bb check:full` normalizes that payload into one mounted gate, and malformed or missing payloads degrade into one ordinary governance error gate instead of crashing the report path.

Validation confirms the behavior. `bb test` now passes with `262 tests, 1332 assertions, 0 failures`. `bb ui:governance` passes with the detailed eight-gate frontend report. `bb ui:governance:node` emits one machine-facing EDN payload. `bb check` and `bb check:full` both complete successfully, each surfacing only the repository’s unrelated existing directory-width warning in `test/meta_flow/lint`.

## Context and Orientation

This repository has a small governance framework. A “gate” is one normalized result map with a `:status`, a short `:headline`, and an `:action`. The shared logic lives in `src/meta_flow/governance/core.clj`, which defines valid statuses and exit-code rules, `src/meta_flow/governance/runner.clj`, which runs entries concurrently and converts crashes into error gates, and `src/meta_flow/governance/report.clj`, which prints grouped human-readable reports.

The shipped backend/core root governance command is implemented in `src/meta_flow/lint/check.clj`. That file now assembles backend concerns only: formatting, static analysis, structure governance, executable correctness, and coverage. The command surface in `bb.edn` wires `bb check` to this namespace.

The shipped frontend governance command is implemented in `src/meta_flow/lint/check/frontend.clj`. That file owns the detailed frontend rule list and still prints the full eight-gate human-facing report under `bb ui:governance`. The machine-facing mounted-node producer lives separately in `src/meta_flow/lint/check/frontend_node.clj` and is wired to `bb ui:governance:node`.

The shipped whole-repository aggregator is implemented in `src/meta_flow/lint/check/full.clj`. That file runs the backend/core root gates, mounts the UI through `src/meta_flow/governance/node.clj`, and appends one normalized `mounted-ui-governance` gate before executable correctness and coverage.

The tests now prove the split boundary rather than the old embedded one. `test/meta_flow/lint/frontend_test.clj` proves that `check/check-gates` is backend/core plus execution, that frontend gate assembly still exists, and that the machine-facing frontend node payload summarizes non-pass gates. `test/meta_flow/governance/node_test.clj` proves payload normalization and protocol-failure handling. `test/meta_flow/lint/check_full_test.clj` proves that the full aggregator mounts exactly one UI node gate.

In this plan, a “mounted node” means a subsystem that executes its own governance rules and then returns one summarized result object to the root gate. The mounted node still owns its internal rule list. The root only owns orchestration, transport handling, and high-level reporting. In this repository, the first mounted node is the browser UI governance subsystem.

In this plan, a “node payload” means the single machine-readable result emitted by a mounted node command. A “transport failure” means the subprocess could not be started, exited without printing a payload, or printed data that could not be parsed or validated as that payload.

## Milestones

### Milestone 1: Freeze the mounted UI node contract

At the end of this milestone, the repository will have a machine-facing frontend governance entrypoint that emits one EDN map and nothing else on stdout. That command will be safe for a human to run manually, but its primary purpose is to be mounted by the root gate. The detailed `bb ui:governance` command will continue to exist unchanged for subsystem debugging.

The proof for this milestone is that a novice can run the new machine-facing command and see exactly one EDN map on stdout. If the UI subsystem is healthy, the payload should contain a passing or warning status. If the subsystem is blocked, the payload should still be valid EDN and should describe the failure in one summarized node result.

### Milestone 2: Add whole-repository aggregation with the UI mounted as one node

At the end of this milestone, the repository will have a new full-repository command, `bb check:full`, that runs the backend/core root gates plus the mounted UI node. The root report must show one frontend-related gate, not the UI’s internal gate list. The mounted gate must surface action text that sends the user to `bb ui:governance` for detail.

The proof for this milestone is that `bb check:full` prints one mounted UI gate even when the UI subsystem fails. A transport failure must appear as one recoverable governance error gate with a clear cause. A valid UI-node failure must appear as one ordinary error gate derived from the payload, not as a protocol failure.

### Milestone 3: Narrow the default root gate after the mounted path is proven

At the end of this milestone, `bb check` will become the stable backend/core default gate and will no longer inline frontend rules. The repository will then have a clean separation of responsibilities: `bb check` for backend/core, `bb ui:governance` for detailed UI governance, and `bb check:full` for the aggregate whole-repository gate.

The proof for this milestone is that `bb check` no longer prints `frontend-*` gates, while `bb ui:governance` still prints them and `bb check:full` still mounts the UI as one node. Tests must prove all three behaviors in isolation.

## Interfaces and Dependencies

This change should keep using the existing governance-core modules rather than inventing a second result model. The mounted-node layer should normalize into the same gate shape that `meta-flow.governance.core/normalize-gate` already expects. The root report path should keep using `src/meta_flow/lint/check/report.clj` and `src/meta_flow/governance/report.clj`.

Add a shared mounted-node contract helper under `src/meta_flow/governance/node.clj`. This namespace should do three jobs. First, it should define the required payload keys and valid statuses. Second, it should validate and normalize a parsed node payload into one ordinary governance gate. Third, it should expose one helper that converts transport or protocol failures into the same kind of error gate. Keep the output stable and small; do not make the root depend on the UI’s internal gate list.

The machine-facing frontend entrypoint should live in a dedicated namespace rather than as a flag on `src/meta_flow/lint/check/frontend.clj`. Use `src/meta_flow/lint/check/frontend_node.clj` or an equivalently explicit name. Its `-main` should run `ui/frontend-gates`, summarize those gates into one node payload, print that payload with `prn`, flush stdout, and exit with the same blocked-or-not semantics that the human-facing governance command already uses. It must not print human-oriented report text to stdout. If extra diagnostics are needed during implementation, they belong on stderr only.

The node payload must be an EDN map with these keys:

    {:node/id :ui
     :node/label "mounted-ui-governance"
     :node/status :pass
     :node/headline "frontend governance passed"
     :node/summary "8 frontend gate(s) evaluated"
     :node/evidence []
     :node/action "Run `bb ui:governance` for gate-by-gate detail."}

`:node/status` must use the same statuses the governance core already accepts: `:pass`, `:warning`, `:error`, or `:skipped`. `:node/evidence` must be a vector of short strings chosen by the UI node itself. Keep the evidence small, capped at five lines, and biased toward warnings and errors. A passing node may emit no evidence lines at all. The summary string is intended for the root report and should remain short.

Add a whole-repository entrypoint in `src/meta_flow/lint/check/full.clj`. This namespace should be the only place that mounts the UI node. It should reuse the current root/backend gate assembly plus execution and coverage gates, run the UI-node subprocess through the shared command helper, parse the payload, and append one normalized mounted UI gate to the overall report.

The root-side subprocess helper can live in `src/meta_flow/governance/node.clj` or a nearby shared namespace, but it must execute one explicit command array rather than a shell string. Use the same `clojure.java.shell` style already used by `src/meta_flow/lint/check/shared.clj`. The command should be deterministic and repository-local, for example:

    ["clojure" "-M:native-access:governance" "-m" "meta-flow.lint.check.frontend-node"]

If a `bb ui:governance:node` task is added for human convenience, the root aggregator should still call the direct Clojure command so the mounted path does not depend on Babashka task indirection.

Update `bb.edn` so the public commands are explicit:

    `bb check`
      backend/core governance only

    `bb ui:governance`
      detailed frontend governance only

    `bb check:full`
      backend/core governance plus the mounted UI node

If a machine-facing `bb ui:governance:node` task is added, document it as a debugging aid for the mounted contract, not as the primary human-facing frontend command.

The tests should be split by boundary. Add a new test namespace for the mounted-node contract, for example `test/meta_flow/governance/node_test.clj`, that proves payload validation, malformed payload handling, and transport-failure gate generation. Update `test/meta_flow/lint/frontend_test.clj` so it still proves detailed frontend gates exist, and add a new full-check test, for example `test/meta_flow/lint/check_full_test.clj`, that proves the full aggregator mounts one UI node rather than inlining frontend gates.

## Plan of Work

Start by introducing the shared node contract in `src/meta_flow/governance/node.clj`. Define a normalization function that accepts a parsed EDN payload and returns one gate map with the label `mounted-ui-governance`. The normalization should copy the status and headline, prepend the summary into the report evidence if present, append any node evidence lines, and preserve the node action. Also define one transport-failure helper that returns a gate with `:status :error`, a protocol-specific headline, the subprocess exit code when known, and an action that points to `bb ui:governance` or the machine-facing node command for recovery.

Then add the machine-facing UI entrypoint. Reuse `ui/frontend-gates` from `src/meta_flow/lint/check/frontend.clj` rather than duplicating the UI rule assembly. The summarizer in `src/meta_flow/lint/check/frontend_node.clj` should compute the node status with `meta-flow.governance.core/overall-status`, convert `:blocked` into `:error`, produce a short headline that distinguishes pass, warning, skipped, and error, and collect up to five evidence lines from non-pass gates first. The action string should always send a human to `bb ui:governance` for detailed investigation.

After the node producer exists, add the whole-repository aggregator. The least risky implementation is to extract the backend/core portion of `src/meta_flow/lint/check.clj` into a helper such as `backend-gate-entries` or `backend-check-gates`, then reuse that helper from both `src/meta_flow/lint/check.clj` and the new `src/meta_flow/lint/check/full.clj`. Do not leave two copies of the backend gate list in the tree. The full aggregator should add exactly one mounted UI gate after the backend/core gates and before or after execution/coverage gates consistently. Pick one order and keep it stable in tests; this plan recommends putting the mounted UI gate after the backend/core gates and before execution/coverage, so the report reads as “code structure, mounted subsystem, behavior proof.”

Next, update the command surface in `bb.edn`. Add `check:full`, keep `ui:governance`, and only after the full path is green remove the inline frontend entries from the default `check` task’s namespace. This sequencing matters. During Milestones 1 and 2, it should still be possible to compare the old embedded path with the new mounted path until the tests prove equivalence at the boundary.

Finally, tighten the tests and documentation together. Update existing frontend tests so they stop asserting that `check/check-gates` returns inline frontend gates once Milestone 3 lands. Replace that assertion with two separate ones: `check/check-gates` does not include frontend labels, and `full/check-gates` includes exactly one `mounted-ui-governance` gate. Add negative tests for malformed EDN, blank stdout, and subprocess crashes so the new path cannot silently regress into throwing exceptions.

## Concrete Steps

All commands below run from `/Users/mike/projs/main/meta-flow`.

Before changing code, record the current baseline:

    bb check

Today, before this plan is implemented, the output includes inline frontend gates such as:

    frontend-architecture-governance
    frontend-shared-component-placement-governance
    frontend-shared-component-facade-governance
    frontend-ui-layering-governance
    frontend-page-role-governance
    frontend-semantics-governance
    frontend-style-governance
    frontend-build

Also record the current detailed frontend command:

    bb ui:governance

Expected current behavior is a detailed frontend-only report grouped by blocked, warning, passed, and skipped gates.

After Milestone 1, run the new machine-facing node command. If you add a Babashka alias for convenience, use:

    bb ui:governance:node

If you keep it as a direct Clojure invocation only, use:

    clojure -M:native-access:governance -m meta-flow.lint.check.frontend-node

Expected output on stdout is exactly one EDN map, for example:

    {:node/id :ui, :node/label "mounted-ui-governance", :node/status :pass, :node/headline "frontend governance passed", :node/summary "8 frontend gate(s) evaluated", :node/evidence [], :node/action "Run `bb ui:governance` for gate-by-gate detail."}

After Milestone 2, run the new aggregate command:

    bb check:full

Expected report shape is one mounted UI gate rather than the inline frontend list, for example:

    AI governance summary: pass
    passed gates:
    - format-hygiene: all tracked source roots are formatted
    - static-analysis: no static-analysis findings
    - structure-governance: no structure-governance issues
    - mounted-ui-governance: frontend governance passed
    - executable-correctness: executable correctness checks passed
    - coverage-governance: coverage governance passed

After Milestone 3, rerun the default root command:

    bb check

Expected output no longer includes any `frontend-*` labels. The detailed frontend gate list must still appear under:

    bb ui:governance

Run focused tests while building each milestone:

    bb test --focus meta-flow.governance.node-test
    bb test --focus meta-flow.lint.frontend-test
    bb test --focus meta-flow.lint.check-full-test

Finish with the repository gates that matter for this change:

    bb test
    bb check
    bb check:full
    bb ui:governance

If the frontend build gate depends on local npm bootstrap, ensure `node_modules` exists first:

    bb ui:install

## Validation and Acceptance

This plan is complete only when a novice can prove all three governance boundaries from the command line.

First, `bb check` must succeed or fail based only on backend/core governance plus executable correctness and coverage. The acceptance signal is structural: the report must not include the frontend gate labels that were previously inlined from `src/meta_flow/lint/check/frontend.clj`.

Second, `bb ui:governance` must still provide detailed frontend subsystem feedback. The acceptance signal is behavioral: a frontend-only failure still produces the detailed per-gate report a human needs to debug browser governance. The mounted-node work must not make the human-facing UI command less useful.

Third, `bb check:full` must produce one mounted UI gate. The acceptance signal is both positive and negative. Positively, the mounted UI gate appears with a useful summary and an action pointing to `bb ui:governance`. Negatively, the report must not leak the UI’s internal rule list into the root aggregator.

The failure-path acceptance matters just as much. If the machine-facing node command prints malformed EDN, blank stdout, or exits before producing a payload, `bb check:full` must still print a normal governance report with one blocked gate such as “mounted UI node failed before emitting a payload” or “mounted UI node emitted an invalid payload.” The full command must never crash the report path because the mounted subprocess misbehaved.

Test acceptance should be explicit. `test/meta_flow/governance/node_test.clj` must prove contract normalization and malformed-payload handling. `test/meta_flow/lint/frontend_test.clj` must prove the frontend detailed command still owns its internal gate list. `test/meta_flow/lint/check_full_test.clj` must prove the aggregate command mounts one UI node and converts transport failures into one error gate. The previous test that asserted inline frontend gates under `check/check-gates` must be rewritten to assert the new split once Milestone 3 lands.

## Idempotence and Recovery

All implementation steps in this plan should be repeatable. Adding the new namespaces, running the new commands, and rerunning the tests are safe to do multiple times. The mounted-node transport is intentionally read-only with respect to repository state, so it has no migration or data-drift risk.

The safe rollback path is also explicit. If the mounted node becomes unstable during implementation, keep the governance-core extraction and the coverage instrumentation fix, remove the new machine-facing node command and full aggregation command, and restore the stable embedded frontend gate list in `src/meta_flow/lint/check.clj`. That rollback returns the repository to the current baseline without losing any durable governance-core work.

If only the default-command cutover is unstable, keep `bb check:full` and the machine-facing node path, but temporarily leave `bb check` on the old embedded behavior until the mounted contract is corrected. This partial rollback is preferable to carrying a broken default command.

## Artifacts and Notes

The mounted-node contract must stay intentionally small. A future contributor should not add the UI’s raw gate list to the payload merely because it is convenient during debugging. If the root needs more detail, add short evidence lines chosen by the UI node itself, not a second embedded report format.

The canonical example payload for this plan is:

    {:node/id :ui
     :node/label "mounted-ui-governance"
     :node/status :warning
     :node/headline "frontend governance reported warnings"
     :node/summary "8 frontend gate(s) evaluated"
     :node/evidence ["frontend-style-governance: 1 warning(s), 0 error(s), 1 CSS issue(s)"
                     "frontend-build: frontend build check passed"]
     :node/action "Run `bb ui:governance` for gate-by-gate detail."}

The canonical transport-failure gate should read like an ordinary governance failure, for example:

    {:gate :mounted-ui-governance
     :label "mounted-ui-governance"
     :status :error
     :headline "mounted UI node failed before emitting a payload"
     :cause "exit 1, stdout was blank"
     :action "Run `bb ui:governance` or the machine-facing node command directly and recover the UI governance runner before trusting `bb check:full`."}

Keep the bottom-line rule in mind while editing. The root layer should own orchestration policy. The UI layer should own UI governance policy. The mounted-node contract is the seam that keeps those responsibilities separate.

Change Note (2026-04-07 / Codex): rewrote this document to comply with `.agent/PLANS.md`, added the missing context/orientation and execution sections, and converted the previously open mounted-node design questions into one prescriptive plan with a chosen EDN stdout contract, explicit command surface, rollout order, and failure semantics.

Change Note (2026-04-06 17:05Z / Codex): completed the implementation described by this ExecPlan, updated the progress checklist to the shipped state, recorded the actual validation results, and revised the context/orientation section so it describes the current mounted-node command surface instead of the pre-implementation baseline.
