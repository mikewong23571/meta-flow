# Description-Driven Extension for Task Types and Runtime Profiles

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document must be maintained in accordance with `.agent/PLANS.md`.

## Purpose / Big Picture

The goal of this change is to let a user extend Meta-Flow by describing a new task class or runtime capability bundle, without first editing Clojure source files. After this work, a user should be able to create a project-local `task-type` or `runtime-profile` through an explicit authoring flow, validate it, inspect it through the existing HTTP API, and then create tasks that use it. The important user-visible shift is that extension stops being “edit bundled EDN in `resources/` by hand and maybe also patch Clojure” and becomes “author a new definition in a writeable definitions workspace, validate it, and use it”.

This plan deliberately does not promise that every future execution behavior can be invented from natural language alone. The first concrete outcome is narrower and more useful: within the existing definition model, users can add or clone task types and runtime profiles through a guided authoring plane. A later milestone adds free-form description to draft generation, but only on top of the governed authoring plane.

## Progress

- [x] (2026-04-06 07:09Z) Read `.agent/PLANS.md` and confirmed this document must be self-contained, novice-friendly, and observable.
- [x] (2026-04-06 07:09Z) Re-read the current definitions, validation, runtime registry, task creation flow, and defs HTTP endpoints to identify the real extension boundary.
- [x] (2026-04-06 07:09Z) Chose the target scope of this plan: make `task-type` and `runtime-profile` authorable through project-local definitions and a guided authoring flow; do not try to make arbitrary new runtime adapters or validator engines purely data-defined in the first pass.
- [x] (2026-04-06 07:20Z) Revised the plan after review to define repository reload semantics, forbid silent same-version overrides, move drafts out of the active overlay, and require a real runtime-path acceptance check.
- [x] (2026-04-06 07:23Z) Completed a spike for the highest-risk part of the plan: bundled defs plus active overlay can be merged additively, `defs/drafts/` stays outside the live repository, and a cached filesystem repository can be reloaded after disk changes.
- [x] (2026-04-06 07:35Z) Completed a second spike for the minimal authoring flow: clone-based draft creation and publish can work with a very small input set, without asking users to fill every schema field explicitly.
- [x] (2026-04-06 07:39Z) Completed a third spike for the first user-facing surface: a thin CLI can wrap the clone-first authoring flow without introducing a large new abstraction layer.
- [x] (2026-04-06 07:39Z) Implemented the layered definitions substrate in spike form: bundled defs, active overlay, duplicate-version rejection, draft exclusion by directory layout, and explicit repository reload all exist in code and tests.
- [x] (2026-04-06 07:39Z) Implemented clone-first definition authoring in spike form: `create-runtime-profile-draft!`, `create-task-type-draft!`, and publish flows now exist in code and tests.
- [x] (2026-04-06 07:39Z) Implemented a first thin CLI surface in spike form: `defs init-overlay`, `defs create-runtime-profile`, `defs create-task-type`, `defs publish-runtime-profile`, and `defs publish-task-type`.
- [x] (2026-04-06 07:53Z) Hardened the definitions substrate into production-quality Milestone 1 groundwork: bundled-plus-overlay loading now works from `defs/`, draft content stays excluded by top-level file loading, duplicate version errors report bundled vs overlay sources, filesystem repositories support explicit reload, `defs init-overlay` creates deterministic overlay files with atomic writes, and the new substrate is covered by loader/CLI tests plus the full test suite.
- [x] (2026-04-06 08:01Z) Froze the first stable clone-first authoring contract in code and docs: added explicit request schemas for `task-type` and `runtime-profile` drafts, introduced `meta-flow.defs.authoring` helpers that resolve source templates and enforce the publish-order rule, documented the first-release override allowlists, and covered the contract with dedicated tests.
- [x] (2026-04-06 08:46Z) Productionized the CLI authoring surface: added a dedicated `meta-flow.cli.defs` layer for clone-first `create-*` and `publish-*` commands, tightened flag validation and publish-first failures, split authoring persistence helpers into focused namespaces to stay within structure governance, updated CLI docs, and covered the end-to-end authoring flow with new authoring/CLI tests plus the full suite.
- [x] (2026-04-06 08:57Z) Added the HTTP authoring surface for Task 4: `/api/defs` now exposes the clone-first contract, template listing, draft listing/detail, validate/create/publish flows for `runtime-profile` and `task-type`, explicit reload, shared repository injection for in-process visibility after publish, and dedicated HTTP tests covering success plus 400/404/409 cases.
- [ ] Add optional description-to-draft generation on top of the stable clone-first authoring contract.
- [ ] Update validation, docs, and acceptance coverage so a novice can create a new task type and runtime profile end to end and observe a task running with the new definitions.

## Surprises & Discoveries

- Observation: the repository already consumes `task-type` and `runtime-profile` as data during task creation and scheduler dispatch, but authoring remains manual and file-based.
  Evidence: `src/meta_flow/ui/tasks.clj` creates tasks from `:task-type/*` refs and `src/meta_flow/ui/http.clj` exposes read-only defs endpoints, but there is no write path for definitions.

- Observation: the current definitions bundle is not an open-ended registry; it is a fixed set of known EDN files loaded from classpath resources.
  Evidence: `src/meta_flow/defs/source.clj` hard-codes `workflow.edn`, `task-types.edn`, `task-fsms.edn`, `run-fsms.edn`, `artifact-contracts.edn`, `validators.edn`, `runtime-profiles.edn`, and `resource-policies.edn`.

- Observation: adding a new `task-type` or `runtime-profile` that only recombines existing pieces is already mostly data-driven, but adding a new validator behavior or runtime adapter still requires Clojure changes.
  Evidence: `src/meta_flow/service/validation.clj` dispatches on `:validator/type` with a `case`, and `src/meta_flow/runtime/registry.clj` dispatches on `adapter-id` with a `case`.

- Observation: the current `runtime-profile` schema is adapter-specific and therefore only partly description-driven.
  Evidence: `src/meta_flow/schema.clj` uses a Malli `:multi` on `:runtime-profile/adapter-id` and only defines branches for `:runtime.adapter/mock` and `:runtime.adapter/codex`.

- Observation: the existing HTTP surface already has enough read-only structure to support an authoring plane, because it can list task types, list runtime profiles, return details, and expose create-options for task creation.
  Evidence: `src/meta_flow/ui/http.clj` already serves `/api/task-types`, `/api/task-types/detail`, `/api/runtime-profiles`, `/api/runtime-profiles/detail`, and `/api/task-types/create-options`.

- Observation: the overlay collision rule does not need a new bespoke validator for versioned definition buckets in the first spike. Simply concatenating bundled and overlay vectors already trips the existing duplicate-version index checks.
  Evidence: the spike added overlay loading plus a test that duplicates `:runtime-profile/mock-worker` version `1`, and repository load fails with `Duplicate definition version in runtime-profiles`.

- Observation: draft exclusion is cheap if drafts stay in `defs/drafts/` and the active loader only reads the known top-level filenames from the overlay root.
  Evidence: the spike test writes an active profile under `defs/runtime-profiles.edn` and a draft-only profile under `defs/drafts/runtime-profiles.edn`; only the active profile appears in the loaded repository.

- Observation: cache invalidation can stay explicit in the repository layer for now; a full watch service is not required to prove the route.
  Evidence: the spike added `reload-filesystem-definition-repository!`, and a repository created before an overlay file change only sees the new profile after explicit reload.

- Observation: the authoring flow does not need a full “fill every field” form to get started. A clone-based draft with `from-id`, `from-version`, `new-id`, `new-name`, and a small `overrides` map is enough to create valid drafts for both `runtime-profile` and `task-type`.
  Evidence: the second spike added `meta-flow.defs.authoring` plus tests that create and publish drafts by cloning `:runtime-profile/codex-worker`, `:runtime-profile/mock-worker`, and `:task-type/repo-arch-investigation` with only a few explicit overrides.

- Observation: a thin CLI is enough to exercise the whole clone-first route. The first user-facing layer does not need an interactive prompt system or HTTP write surface to validate the product direction.
  Evidence: the third spike added `defs init-overlay`, `defs create-runtime-profile`, `defs create-task-type`, `defs publish-runtime-profile`, and `defs publish-task-type` command paths and verified them with targeted CLI tests.

## Decision Log

- Decision: the first implementation target is not “arbitrary natural-language-defined execution semantics”; it is “governed, project-local authoring of `task-type` and `runtime-profile` definitions”.
  Rationale: this repository still hard-codes validator engines, runtime adapters, and the set of definition files. Claiming fully free-form description-driven extension would be false and would push complexity into an unsafe implicit layer.
  Date/Author: 2026-04-06 / Codex

- Decision: new user-authored definitions will live in a project-local overlay directory, not by editing `resources/meta_flow/defs/` in place.
  Rationale: classpath resources are bundled defaults and are awkward to mutate safely at runtime. A writeable overlay lets the repository keep shipped defaults while allowing authoring, validation, and version control of local extensions.
  Date/Author: 2026-04-06 / Codex

- Decision: the overlay will initially support only existing definition kinds and existing execution engines.
  Rationale: the immediate pain point is that `task-type` and `runtime-profile` are too hard to add. Removing that pain does not require inventing a plugin system for runtime adapters and validators in the same change.
  Date/Author: 2026-04-06 / Codex

- Decision: active overlay definitions are append-only by `definition id + version`. A local overlay may add a new definition or a new version, but it may not silently replace an existing bundled or local definition with the same id and version.
  Rationale: the repository currently pins task and run behavior by versioned refs. Allowing same-version shadowing would silently change the meaning of already-persisted refs and make the version field unreliable.
  Date/Author: 2026-04-06 / Codex

- Decision: drafts are stored outside the active overlay, under `defs/drafts/`, and are never loaded by the runtime repository until an explicit publish step copies them into the active overlay files.
  Rationale: the current loader reads every record from every known definitions file into the live repository. A separate drafts area is the smallest clear way to introduce “draft” without polluting runtime resolution semantics.
  Date/Author: 2026-04-06 / Codex

- Decision: authoring flows must either invalidate the in-process definitions cache or rebuild a fresh repository before reporting success to HTTP and CLI callers.
  Rationale: the current filesystem repository caches one loaded snapshot. Without an explicit reload step, newly authored definitions will not reliably appear in long-running processes.
  Date/Author: 2026-04-06 / Codex

- Decision: the first implementation may use an explicit repository reload function instead of a more ambitious automatic file-watching design.
  Rationale: the spike proved that explicit cache reset is enough to clarify the technical route. It keeps the initial authoring implementation simple while preserving a later upgrade path to automatic invalidation if needed.
  Date/Author: 2026-04-06 / Codex

- Decision: the first authoring surface should be clone-first, not schema-first. The user supplies a template id and a very small set of changes, and the system inherits the rest from the template.
  Rationale: the second spike proved that this is enough to create valid drafts. It also matches the real user need better than exposing every pinned ref and adapter field up front.
  Date/Author: 2026-04-06 / Codex

- Decision: the first user-facing implementation should land in CLI before HTTP.
  Rationale: the third spike showed that CLI is enough to validate command shape, required inputs, publish ordering, and cache reload behavior. It is the cheapest path to learning before building a richer authoring API.
  Date/Author: 2026-04-06 / Codex

- Decision: free-form description generation is a second-layer feature that must emit the same structured draft format used by the guided wizard.
  Rationale: one source of truth for authoring keeps validation, persistence, and testability stable. Natural language should produce drafts, not bypass the governed definition model.
  Date/Author: 2026-04-06 / Codex

- Decision: this plan treats `task-type` and `runtime-profile` differently. `task-type` is the semantic assembly that pins lifecycle, validation, artifact shape, and resource policy. `runtime-profile` is the capability bundle that pins adapter-specific execution settings.
  Rationale: this split already exists in the repository and should become more explicit, not less, as authoring becomes easier.
  Date/Author: 2026-04-06 / Codex

## Outcomes & Retrospective

This plan is now partially de-risked by three spikes. The first spike proved the highest-risk loader assumptions: active overlay loading can remain file-based, draft isolation can be achieved by directory layout alone, and repository cache refresh can be explicit. The second spike proved that the minimal authoring model can be clone-first rather than schema-first: valid drafts can be created and published with a template id, a new id, a new name, and a narrow override map. The third spike proved that this route can be surfaced through a thin CLI without inventing a large authoring framework first. The remaining work is now mostly about hardening, broadening the override vocabulary, and deciding when HTTP adds enough value to justify itself.

Task 1 is now complete in the current codebase. The definitions loader no longer assumes a classpath-only world: it merges bundled defaults with a top-level `defs/` overlay, ignores `defs/drafts/` by construction, and surfaces source metadata in duplicate-version failures so a user can tell whether the conflict came from bundled defaults or local overlay data. The filesystem repository now exposes an explicit reload path so long-running processes can see authored definitions after disk changes. The CLI also has a stable `defs init-overlay` command that creates deterministic overlay files and draft directories using atomic writes, giving later authoring tasks a safe write target instead of ad hoc file creation.

Tasks 2 and 3 are now complete in the current codebase. The clone-first request contract is explicit in schema and service code, the CLI authoring surface now runs through dedicated `defs create-*` and `defs publish-*` commands with stricter option parsing and clearer publish-order failures, and the authoring implementation has been split into focused namespaces so the repo keeps passing its file-size governance. A novice can now create a runtime-profile draft, publish it, create a task-type draft that points at the published profile, publish that task type, and validate the resulting overlay through documented CLI steps.

## Context and Orientation

Meta-Flow currently stores workflow definitions as versioned EDN data in `resources/meta_flow/defs/`. EDN is Clojure’s data notation, similar in spirit to JSON but able to express Clojure keywords such as `:task-type/default`. The loader in `src/meta_flow/defs/source.clj` reads a fixed set of files from that directory. The repository implementation in `src/meta_flow/defs/repository.clj` validates the loaded data and exposes lookup functions through `src/meta_flow/defs/protocol.clj`.

One implementation detail matters a great deal for this plan: `src/meta_flow/defs/repository.clj` caches the fully loaded definitions snapshot inside the repository instance. That is fine for read-only bundled definitions, but it means any authoring flow must explicitly reload or invalidate the cache after a successful write. If this is not done, a long-running HTTP server will continue serving stale definitions even after the files on disk changed.

Two definition kinds matter most for this change.

A `task-type` is the definition that says what a task means. In `src/meta_flow/schema.clj`, a task type pins a task state machine, a run state machine, a runtime profile, an artifact contract, a validator, a resource policy, an input schema, and a work-key expression. When the user creates a task through `src/meta_flow/ui/tasks.clj`, the task is assembled from those pinned refs.

A `runtime-profile` is the definition that says how a task is allowed to execute. In this repository, the profile points to a runtime adapter such as `:runtime.adapter/mock` or `:runtime.adapter/codex` and carries adapter-specific settings such as allowed MCP servers, environment allowlists, prompt path, helper script path, and timeouts.

The current extension boundary is uneven. A user can already add a new `task-type` by editing EDN and reusing existing refs, but there is no supported authoring surface, only shipped resource files. A user cannot create a new validator engine or runtime adapter without changing Clojure because `src/meta_flow/service/validation.clj` and `src/meta_flow/runtime/registry.clj` dispatch with hard-coded branches. The right next step is therefore to make the definition kinds that are already data-shaped genuinely authorable, while keeping code-defined extension seams explicit.

The key modules for this plan are:

- `src/meta_flow/defs/source.clj`, which currently hard-codes the definition file set and loads only bundled resources.
- `src/meta_flow/defs/repository.clj`, which builds the validated definition repository.
- `src/meta_flow/defs/validation.clj`, which validates schemas and cross-definition links.
- `src/meta_flow/schema.clj`, which defines the shape of all definition kinds.
- `src/meta_flow/ui/defs.clj` and `src/meta_flow/ui/http.clj`, which currently expose read-only defs inspection endpoints.
- `src/meta_flow/ui/tasks.clj`, which already proves that task creation can consume a data-defined `task-type`.
- `resources/meta_flow/defs/task-types.edn` and `resources/meta_flow/defs/runtime-profiles.edn`, which show the current assembly style.

## Milestones

### Milestone 1: Make definitions writeable through a layered source model

At the end of this milestone, Meta-Flow still ships the same bundled definitions, but it no longer assumes that definitions only live in `resources/`. A project-local overlay directory exists, the repository loads bundled base definitions plus local overlay definitions, validation reports errors against the writeable files a user can actually edit, and the repository can be reloaded after authoring writes. This milestone is the foundation for all later authoring work because a wizard is not meaningful if it can only tell the user to patch classpath resources by hand.

The proof for this milestone is simple and observable: after creating an overlay `runtime-profile` or `task-type` file, `clojure -M -m meta-flow.main defs validate` succeeds, the existing defs HTTP endpoints show the new definition after an explicit reload or automatic cache invalidation, and the bundled shipped definitions continue to appear unchanged.

### Milestone 2: Add a definition authoring plane for `task-type` and `runtime-profile`

At the end of this milestone, a user can create a new `task-type` or `runtime-profile` without hand-editing EDN. The system provides a guided draft flow that either starts from a template or clones an existing definition, asks for the fields that matter, writes a draft file under `defs/drafts/`, validates it against the merged bundled-plus-overlay repository, and reports what still needs to be fixed. Only an explicit publish step may copy the draft into the active overlay files under `defs/`. Dependency order matters: if a task type wants to reference a newly authored runtime profile, that runtime profile must be published into the active overlay before the task-type draft can validate that ref. This authoring plane should exist both as an HTTP service under `/api/defs/...` and as a CLI entry point so the feature is usable without requiring a separate frontend.

The proof for this milestone is that a novice can run a CLI or HTTP flow, create a new draft `runtime-profile`, publish it, create a new draft `task-type` that points to the published profile, publish the task type, validate the active definitions, and then inspect both through the existing detail endpoints.

### Milestone 3: Add description-to-draft generation on top of the same authoring plane

At the end of this milestone, a user can provide a short description such as “I want a repo-review task that uses Codex, has web search disabled, allows only Context7, and emails a markdown report” and the system generates a draft `task-type` plus, when needed, a draft `runtime-profile`. The generated output is still ordinary EDN under `defs/drafts/` and still passes through the same validation and review path as hand-authored drafts. This milestone does not claim that the generated definitions are always correct; it claims that the system can convert user intent into a governed draft instead of forcing the user to know every field name in advance.

The proof for this milestone is that a description request yields a persisted draft, the draft is visible through the authoring inspection surface rather than the live definitions list, and the user can either accept or revise it before publish.

## Plan of Work

The first major change is to split “definition source” from “definition repository”. In `src/meta_flow/defs/source.clj`, replace the current single-source resource loader with a layered source model. Keep the existing bundled files under `resources/meta_flow/defs/` as the base layer, but add a project-local overlay directory, proposed as `defs/`, at the repository root. The overlay uses the same file names as the bundled layer: `task-types.edn`, `runtime-profiles.edn`, and the other definition files. If an overlay file does not exist, the loader falls back to bundled data. If an overlay file exists, the loader merges the records by definition id and version, but only additively: a local overlay may contribute a new id or a new version, and a collision on the same id and version must fail validation rather than silently replacing the bundled definition. Record source metadata so validation errors and detail views can say whether a definition came from bundled defaults or local overlay.

Add a small orientation comment to the plan implementer here: the goal is not to invent a new database-backed definition store in this change. Stay file-based, deterministic, and diff-friendly. A novice should be able to see the authored definitions in normal EDN files under `defs/` and commit them to git if desired. The repository implementation must also gain a reload path, either by explicit cache invalidation on write or by constructing a fresh repository instance for each authoring operation that needs post-write visibility.

The second major change is to introduce a definition authoring service module, likely under `src/meta_flow/defs/authoring.clj` or `src/meta_flow/service/defs_authoring.clj`. This service owns draft creation and file writes. It should provide operations with stable names and explicit inputs, such as `list-templates`, `create-runtime-profile-draft!`, `create-task-type-draft!`, `clone-definition-draft!`, `validate-draft!`, and `publish-definition-draft!`. “Draft” here means a candidate definition stored outside the active overlay, under `defs/drafts/`, together with validation output and source metadata. Publishing means copying or merging a validated draft into the active overlay file under `defs/`, then reloading the active repository. The service should write ordinary EDN files but should do so deterministically: preserve top-level vector shape, sort definitions by id and version, and write only the target file that owns the definition kind.

The third major change is to make the first stable authoring request shape explicit. The first release should be clone-first, not schema-first. That means the stable request contract is intentionally small: `from-id`, optional `from-version`, `new-id`, `new-name`, optional `new-version`, and a narrow `overrides` map. For `runtime-profile`, the first supported overrides should be limited to fields the current CLI spike already exercises safely, such as `:runtime-profile/web-search-enabled?`, `:runtime-profile/worker-prompt-path`, and later other adapter fields that are explicitly added one by one. For `task-type`, the first supported overrides should be limited to fields such as `:task-type/runtime-profile-ref` and other pinned refs or input-shape edits that are proven in tests. Only after this clone-first request shape is documented and stable should the repository add a richer wizard schema in `src/meta_flow/schema.clj` or a new focused schema namespace. The richer schema can ask for semantics and input fields more directly, but it must still compile down to the same clone-first request contract in the first implementation phase.

The fourth major change is to expose authoring through HTTP and CLI. In `src/meta_flow/ui/http.clj`, add a new `/api/defs` route group rather than overloading the current `/api/task-types` and `/api/runtime-profiles` read-only endpoints. The new routes should include endpoints to list templates, create a draft, validate a draft, publish a draft, and reload or invalidate the definitions cache after a successful publish. In the CLI layer, extend `src/meta_flow/cli/commands.clj` and the command router so a novice can run commands such as `clojure -M -m meta-flow.main defs init-overlay`, `clojure -M -m meta-flow.main defs create-runtime-profile --from runtime-profile/codex-worker --new-id runtime-profile/repo-review`, and `clojure -M -m meta-flow.main defs create-task-type --from task-type/repo-arch-investigation --new-id task-type/repo-review`. The first implementation may accept flags rather than truly interactive terminal prompts; what matters is that the command is an authoring surface, not manual file surgery.

The fifth major change is to add description-to-draft generation. Create a separate service module, likely `src/meta_flow/defs/generation.clj`, that takes a free-form description and produces the same authoring request map used by the wizard path. Do not let this module write EDN directly. It must emit a normalized authoring request that then passes through the same authoring service and the same validation flow as a hand-filled request. This keeps natural language generation optional and reviewable. If the repository is not yet ready to call a language model directly, implement the milestone as a deterministic placeholder that accepts a description plus a required base template and returns a structured draft by applying explicit heuristics; the user-visible contract remains the same.

The sixth major change is to clarify and harden scope boundaries in validation and documentation. Update `src/meta_flow/defs/validation.clj`, `docs/architecture/extension-guide.md`, and `docs/internal-flow.md` so the repository plainly says: `task-type` and `runtime-profile` are authorable through definitions; validator engines and runtime adapters remain code extension seams. This matters because once the system has a wizard, users will naturally assume that every keyword field is equally extensible. The docs must state which parts are data assembly and which parts are implementation seams.

## Task Breakdown

The remaining work should be executed as five concrete tasks. These tasks are smaller than the milestones above and are intended to guide day-to-day implementation order.

### Task 1: Harden the definitions substrate

Take the current spike code in `src/meta_flow/defs/source.clj`, `src/meta_flow/defs/repository.clj`, and related tests and make it safe to keep. The goal is not new functionality; it is to convert the proven route into a dependable base. This task should tighten error messages, add atomic write behavior where needed, keep draft paths out of the active loader, and make reload semantics explicit and documented. Finish this task before widening the user-facing surfaces.

### Task 2: Freeze the first stable authoring contract

Take the clone-first model proven in the spike and turn it into an explicit supported contract. The output of this task is a short, stable request shape for `runtime-profile` and `task-type` creation, plus a documented allowlist of supported override fields in the first release. This task must also document the publish-order rule for inter-definition references, especially that a task type cannot validate against an unpublished runtime-profile draft.

### Task 3: Productionize the CLI authoring surface

Take the current thin CLI and decide which parts are spike-only and which parts become the official first surface. This task should improve argument validation, make output and failure messages more precise, add missing command tests, and update CLI documentation. It should continue reusing the clone-first contract from Task 2 rather than inventing a second input model.

### Task 4: Add the HTTP authoring surface

After the CLI contract is stable, add HTTP endpoints under `/api/defs` that mirror the same draft and publish operations. This task should reuse the same underlying authoring service and request model, add request coercion, and provide draft inspection or listing endpoints so users can see unpublished drafts. It should not redesign the core authoring contract.

### Task 5: Add description-to-draft generation

Only after Tasks 1 through 4 are stable should the repository add free-form description support. This task should map a human description into the same clone-first request shape already used by CLI and HTTP. The generated result must remain a draft under `defs/drafts/`, not an implicit publish. This task is successful when description generation is a thin layer over a stable authoring plane rather than a parallel system.

## Concrete Steps

All commands in this plan run from `/Users/mike/projs/main/meta-flow`.

Start by creating the overlay directory and proving the loader can see it:

    clojure -M -m meta-flow.main defs init-overlay
    clojure -M -m meta-flow.main defs validate

After Milestone 1 is implemented, the expected transcript should include ordinary validation success and should not require editing `resources/`:

    Definitions valid
    Task types: 3
    Task FSMs: 2
    Run FSMs: 2
    Runtime profiles: 3

Then create a runtime profile draft through the new authoring plane:

    clojure -M -m meta-flow.main defs create-runtime-profile \
      --from runtime-profile/codex-worker \
      --new-id runtime-profile/repo-review \
      --name "Codex repo review worker" \
      --worker-prompt-path meta_flow/prompts/worker.md \
      --web-search false

Expected transcript:

    Wrote draft runtime profile :runtime-profile/repo-review version 1 to defs/drafts/runtime-profiles/runtime-profile_repo-review_v1.edn
    Validation: OK

Publish the runtime profile draft before creating any task type that references it:

    clojure -M -m meta-flow.main defs publish-runtime-profile --id runtime-profile/repo-review --version 1

Expected transcript:

    Published :runtime-profile/repo-review version 1 to defs/runtime-profiles.edn
    Reloaded definitions cache

Then create a task type draft that points to the published runtime profile:

    clojure -M -m meta-flow.main defs create-task-type \
      --from task-type/repo-arch-investigation \
      --new-id task-type/repo-review \
      --name "Repo review" \
      --runtime-profile runtime-profile/repo-review

Expected transcript:

    Wrote draft task type :task-type/repo-review version 1 to defs/drafts/task-types/task-type_repo-review_v1.edn
    Validation: OK

Publish the task type draft into the active overlay and reload definitions:

    clojure -M -m meta-flow.main defs publish-task-type --id task-type/repo-review --version 1

Expected transcript:

    Published :task-type/repo-review version 1 to defs/task-types.edn
    Reloaded definitions cache

After publish, inspect the authored definitions through the HTTP API:

    clojure -M -m meta-flow.main ui serve --port 8788

In another shell:

    curl 'http://localhost:8788/api/runtime-profiles/detail?runtime-profile-id=runtime-profile%2Frepo-review&runtime-profile-version=1'
    curl 'http://localhost:8788/api/task-types/detail?task-type-id=task-type%2Frepo-review&task-type-version=1'

The responses should include the authored ids and the pinned refs.

Finally, after Milestone 3 is implemented, generate a draft from description:

    clojure -M -m meta-flow.main defs generate-task-type \
      --description "Create a repo review task that uses Codex, disables web search, and emits a markdown report"

Expected transcript:

    Generated draft request from description
    Wrote draft runtime profile :runtime-profile/repo-review version 1
    Wrote draft task type :task-type/repo-review version 1
    Validation: OK
    Drafts remain under defs/drafts until publish

## Validation and Acceptance

Acceptance is behavioral, not purely structural.

First, after Milestone 1, the repository must load definitions from bundled defaults plus project-local overlay without requiring edits to `resources/meta_flow/defs/`. Run:

    clojure -M -m meta-flow.main defs validate

and expect success with the overlay present. A failure must report the overlay file path and the specific definition that is invalid.

Second, after Milestone 2, a novice must be able to create a new `runtime-profile` and a new `task-type` without hand-editing EDN. The proof is:

    clojure -M -m meta-flow.main defs create-runtime-profile ...
    clojure -M -m meta-flow.main defs publish-runtime-profile ...
    clojure -M -m meta-flow.main defs create-task-type ...
    clojure -M -m meta-flow.main defs publish-task-type ...
    clojure -M -m meta-flow.main defs validate

and both newly authored definitions appear through:

    curl 'http://localhost:8788/api/runtime-profiles'
    curl 'http://localhost:8788/api/task-types'

Third, a task created from the new task type must use the new pinned runtime profile. The proof is:

    curl -X POST http://localhost:8788/api/tasks \
      -H 'content-type: application/json' \
      -d '{"task-type-id":"task-type/repo-review","task-type-version":1,"input":{"input/repo-url":"https://github.com/example/repo","input/notify-email":"you@example.com"}}'

Then fetch the task detail and verify the stored task references the authored `runtime-profile`.

Fourth, the plan must prove not just storage but runtime use. The recommended acceptance path is to create a new task type that clones an existing runnable task type while pointing at a newly authored runtime profile that still uses a supported adapter. Then run the scheduler and observe behavior that depends on the authored profile. For a mock-backed profile, the proof is that the task completes end to end through the scheduler using the new runtime-profile ref. For a codex-backed profile, the proof is that dispatch or launch support resolves the newly authored profile and that the resulting runtime state or launch support output reflects the new profile fields.

Fifth, after Milestone 3, a description-based authoring request must produce the same final overlay EDN shape as the guided authoring path. The proof is that the generated definitions can be listed, inspected, and validated through the same commands and endpoints as manually authored drafts.

Sixth, the normal test and governance suite must remain green:

    bb fmt:check
    bb lint
    bb test
    bb check

Add or update tests in at least these areas:

- `test/meta_flow/defs_*` for layered definition loading, merge semantics, and authoring writes.
- `test/meta_flow/ui/` for new defs authoring endpoints.
- `test/meta_flow/cli/` for new defs authoring commands.
- `test/meta_flow/service/validation_test.clj` only if validation behavior changes.

## Idempotence and Recovery

The overlay directory creation must be idempotent. Running the overlay initialization command multiple times should create missing files but never duplicate definitions or reorder files unpredictably.

Definition writes must be deterministic. If the same authoring request is applied twice, the second run should either report that the definition already exists or produce byte-stable output. The authoring service must never append duplicate entries with the same id and version.

Publishing must also reject same-version collisions. If `:runtime-profile/repo-review` version `1` already exists anywhere in the active merged repository, a publish attempt for the same id and version must fail with an explicit error telling the user to choose a new version or remove the unpublished draft.

Validation failures must not leave partial corrupt output behind. The safe pattern is: write to a temporary file, validate the full merged definition set, and then atomically replace the target overlay file only on success. If validation fails, keep the previous file and print the candidate errors.

Description generation must also be recoverable. If generation produces an incomplete draft, store it as a draft with explicit errors or warnings instead of silently publishing it as valid.

Repository reload must be recoverable too. If a publish succeeds on disk but cache invalidation fails, the command or HTTP response must say so explicitly and instruct the caller to rebuild the repository or restart the server before trusting read-side results.

## Artifacts and Notes

The most important expected file additions are:

- `docs/architecture/description-driven-extension-execplan.md`, this plan.
- `src/meta_flow/defs/source.clj` updates for layered source loading.
- `src/meta_flow/defs/repository.clj` updates for source metadata and overlay-backed repositories.
- `src/meta_flow/defs/authoring.clj`, a new authoring service.
- `src/meta_flow/defs/generation.clj`, a new description-to-draft service for Milestone 3.
- `src/meta_flow/ui/http.clj` updates for `/api/defs/...` authoring routes.
- `src/meta_flow/cli/commands.clj` updates for `defs create-*` and `defs generate-*` commands.
- `defs/` as the new active project-local overlay directory created by users, not shipped defaults.
- `defs/drafts/` as the new inactive drafts directory that is never loaded into the live repository until publish.

The most important behavioral snippet after Milestone 2 should look like:

    $ clojure -M -m meta-flow.main defs create-runtime-profile --from runtime-profile/codex-worker --new-id runtime-profile/repo-review --name "Codex repo review worker"
    Wrote draft runtime profile :runtime-profile/repo-review version 1 to defs/drafts/runtime-profiles/runtime-profile_repo-review_v1.edn
    Validation: OK

    $ clojure -M -m meta-flow.main defs publish-runtime-profile --id runtime-profile/repo-review --version 1
    Published :runtime-profile/repo-review version 1 to defs/runtime-profiles.edn
    Reloaded definitions cache

    $ clojure -M -m meta-flow.main defs create-task-type --from task-type/repo-arch-investigation --new-id task-type/repo-review --name "Repo review" --runtime-profile runtime-profile/repo-review
    Wrote draft task type :task-type/repo-review version 1 to defs/drafts/task-types/task-type_repo-review_v1.edn
    Validation: OK

## Interfaces and Dependencies

In `src/meta_flow/defs/source.clj`, define a layered source API instead of a single resource loader. At the end of Milestone 1, the file should expose stable functions with responsibilities equivalent to:

    load-bundled-definition-data
    load-overlay-definition-data
    merge-definition-data
    load-definition-data

`load-definition-data` must return the same logical map shape that the repository already expects, but with enough metadata for error reporting. `merge-definition-data` must reject collisions on identical id and version rather than choosing a winner implicitly.

In `src/meta_flow/defs/repository.clj`, add an explicit reload path. At the end of Milestone 1, the repository layer should expose a stable way to clear or rebuild the cached definitions snapshot after authoring writes.

In `src/meta_flow/defs/authoring.clj`, define a service interface in ordinary functions. At minimum, the module should provide functions equivalent to:

    list-definition-templates
    create-runtime-profile-draft!
    create-task-type-draft!
    validate-draft!
    validate-overlay!
    publish-draft!

Each function should accept explicit maps and return explicit result maps. Do not bury user-visible outcomes in side effects alone. Draft functions operate on `defs/drafts/`; publish functions move validated content into the active overlay under `defs/`.

In `src/meta_flow/ui/http.clj`, add routes under `/api/defs` rather than mixing write operations into the current read-only endpoints. Keep the current list and detail endpoints stable.

In `src/meta_flow/cli/commands.clj`, add explicit defs authoring commands. Keep them additive and non-interactive at first if that reduces risk. A flag-driven wizard is acceptable as the first authoring surface.

In `src/meta_flow/schema.clj`, add separate authoring request schemas rather than mutating the final persisted schemas to look like form requests.

In `src/meta_flow/defs/validation.clj` and the documentation, state clearly that runtime adapters and validator engines remain code-defined seams. If a future change wants fully pluggable validators or adapters, that must be a new plan with its own validation and security model.

Revision note: 2026-04-06 / Codex. Created this ExecPlan to turn the earlier repository survey into an implementable path. The plan chooses a staged approach: first make definitions writeable, then add guided authoring, then add description-to-draft generation, because the repository is not yet honestly “pure description-driven”.

Revision note: 2026-04-06 / Codex. Revised the plan after review. The updated version now defines reload behavior for the cached definitions repository, forbids silent same-version overrides in the overlay, moves drafts into `defs/drafts/` so they are not live definitions, and strengthens acceptance so a newly authored runtime profile must be observed in an actual runtime path rather than only in stored task refs.

Revision note: 2026-04-06 / Codex. Added spike findings after implementing and validating the source/repository prototype. The plan now records that additive overlay merge, draft exclusion by directory layout, and explicit repository reload have all been proven in code and tests.

Revision note: 2026-04-06 / Codex. Added second-spike findings after implementing and validating a minimal authoring service prototype. The plan now records that clone-first draft creation is viable and that the likely first user-facing input shape is “template id + new id/name + a small overrides map”, not a full-schema form.

Revision note: 2026-04-06 / Codex. Added third-spike findings after implementing and validating a thin CLI wrapper over the clone-first authoring service. The plan now records that CLI is the cheapest first user-facing surface and that HTTP can remain a later packaging step.

Revision note: 2026-04-06 / Codex. Revised the plan after a completeness review. The updated version now reflects actual spike progress in the `Progress` section, promotes the clone-first request shape to an explicit first-release contract, and fixes the walkthrough so dependency defs such as runtime profiles are published before task types draft refs to them.

Revision note: 2026-04-06 / Codex. Added a `Task Breakdown` section so the remaining implementation can be executed as five smaller tasks without creating a second top-level plan. This keeps the current ExecPlan as the single source of truth while making day-to-day execution order explicit.

Revision note: 2026-04-06 / Codex. Completed Task 4 by adding `/api/defs` authoring routes on top of the existing clone-first service. The HTTP layer now shares a reloadable definitions repository with the read-only defs endpoints so published drafts become visible without restarting the server, and the new route group is covered by end-to-end HTTP tests.
