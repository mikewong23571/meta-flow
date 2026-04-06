# Browser UI For Definition Authoring

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document must be maintained in accordance with `.agent/PLANS.md`.

This plan builds on the checked-in ExecPlan at `docs/architecture/description-driven-extension-execplan.md`. That earlier plan already delivered the writable definitions substrate, clone-first authoring service, CLI commands, and `/api/defs/...` HTTP endpoints. This plan uses that work as a stable backend contract and adds the missing browser interface on top of it.

## Purpose / Big Picture

After this change, a user who opens the browser UI at `http://localhost:8787` will be able to create a new `runtime-profile` and a new `task-type` without writing curl commands or hand-editing EDN. The user-visible shift is that the existing read-only “Defs” page becomes an authoring surface with draft creation, validation, publish, and draft inspection. A novice should be able to start the API server and frontend preview, open the Defs page, create a runtime profile draft from a template, publish it, create a task type draft that points at that runtime profile, publish it, and then create a task that uses the authored definitions.

The result must be observable in the browser, not merely through tests. The Defs page should show authored drafts and published definitions, and the Tasks page should be able to create a task from the newly published task type in the same running UI session.

## Progress

- [x] (2026-04-06 11:01Z) Re-read `.agent/PLANS.md` and confirmed this plan must be self-contained, prose-first, novice-guiding, and outcome-focused.
- [x] (2026-04-06 11:01Z) Re-read the existing definitions authoring ExecPlan and confirmed that backend authoring is already implemented through `/api/defs/...` and should be treated as the stable foundation for browser work.
- [x] (2026-04-06 11:06Z) Re-read the current frontend structure under `frontend/src/meta_flow_ui/` and confirmed that the browser app already has a Defs page, route handling, page-local state modules, and Tasks create-dialog patterns that can host authoring UI without introducing a second frontend architecture.
- [x] (2026-04-06 11:06Z) Chose the target scope for this plan: add browser UI for clone-first `runtime-profile` and `task-type` authoring plus draft inspection and publish; do not add a schema-first visual editor or arbitrary definition-kind editing in the first pass.
- [x] (2026-04-06 11:10Z) Re-reviewed this plan against repo reality and a live browser spike, and tightened it around four concrete friction points: no dedicated CLJS test harness exists yet, browser create does not need validate as a hard prerequisite, generation can return one or two draft results plus notes, and local `ui:api` / `shadow-cljs` processes may already be running or stale during manual acceptance.
- [x] (2026-04-06 11:24Z) Ran a narrow frontend state/orchestration spike for Defs authoring, proved that a `:defs :authoring` subtree plus a larger `pages/defs/state.cljs` orchestration layer compiles cleanly under `npm run ui:check`, then removed the spike code so the repo stays clean until feature work starts.
- [x] (2026-04-06 12:24Z) Confirmed Milestone 1 substrate is now present in the frontend tree: browser-side authoring contract/templates/drafts/reload wrappers and shared `:defs :authoring` state live under `frontend/src/meta_flow_ui/pages/defs/authoring/` and compile cleanly as the stable base for browser controls.
- [x] (2026-04-06 12:24Z) Implemented Milestone 2 on `#/defs/runtimes`: the page now shows a runtime-profile authoring summary, a clone-first modal dialog with validate and create actions, runtime draft list and inspection panels, publish controls, and a reload path that keeps the published runtime list live without restarting the server.
- [x] (2026-04-06 12:24Z) Verified the runtime-profile browser authoring surface with `bb ui:check`, `bb test --focus meta-flow.ui.http.defs-authoring-test`, and a Playwright smoke against `http://localhost:8787/index.html#/defs/runtimes` that confirmed the runtime authoring section and dialog fields render in the browser.
- [x] (2026-04-06 12:24Z) Implemented frontend HTTP wrappers and page state for defs authoring endpoints, including draft validation, draft creation, draft listing/detail, publish, reload, and description generation.
- [x] (2026-04-06 12:55Z) Implemented Milestone 3 on `#/defs`: the task-type tab now shows a task-type authoring summary, a clone-first modal dialog, published-runtime-only runtime override selection, task-type draft list and inspection panels, publish controls, and page bootstrap that keeps task-type authoring data live alongside the published catalog.
- [x] (2026-04-06 12:55Z) Fixed a browser-facing request encoding bug in `frontend/src/meta_flow_ui/http.cljs` so authoring POST bodies preserve namespaced keys like `authoring/from-id` and `task-type/runtime-profile-ref`; without that fix, both runtime-profile and task-type create/validate routes were being rejected by request coercion.
- [x] (2026-04-06 12:55Z) Verified the task-type browser authoring flow with `bb ui:check`, `bb test --focus meta-flow.ui.http.defs-authoring-test`, a Playwright smoke that rendered the new task-type authoring panel/dialog at `http://localhost:8787/#/defs`, and a browser create/publish walkthrough that published `runtime-profile/browser-m3-ui-smoke-3` followed by `task-type/browser-m3-ui-smoke-3` from the live UI before cleaning the generated overlay drafts back out of the repo.
- [x] (2026-04-06 13:24Z) Implemented Milestone 4 on `#/defs`: the task-type authoring tab now exposes a description-generation dialog with optional template/id overrides, a persisted generation result panel that renders whichever draft results the backend actually returned, direct affordances to inspect the generated task-type draft or jump to runtime drafts, and automatic refresh plus task-draft inspection after generation succeeds.
- [x] (2026-04-06 13:24Z) Verified the Milestone 4 browser flow with `bb ui:check`, `bb test --focus meta-flow.ui.http.defs-generation-test`, `bb test --focus meta-flow.ui.http.defs-authoring-test`, and a Playwright smoke at `http://localhost:8787/index.html#/defs` that generated `runtime-profile/repo-review` plus `task-type/repo-review`, rendered the returned notes in the browser, and then cleaned the temporary overlay drafts back out of the repo.
- [x] (2026-04-06 14:09Z) Tightened Milestone 5 backend acceptance in `test/meta_flow/ui/http/defs_authoring_test.clj` so the authored-task proof now asserts `/api/task-types/create-options` exposes the published task type exactly as the Tasks dialog consumes it, then still proves task creation, pinned runtime refs, task-list rendering, and finalized mock execution.
- [x] (2026-04-06 14:09Z) Verified the full Milestone 5 browser path in a live UI session: started `bb ui:api` and `bb ui:watch`, authored and published `runtime-profile/browser-m5-ui-proof` plus `task-type/browser-m5-ui-proof`, created task `task-0a45aa47-f099-45fc-918a-b51f2d6c428e` from `#/tasks`, ran `bb scheduler:run --task-id task-0a45aa47-f099-45fc-918a-b51f2d6c428e`, and observed completion with run `run-457c749b-de4e-4f5d-b45c-6dda0bf379e5` and runtime profile `runtime-profile/browser-m5-ui-proof`.
- [x] (2026-04-06 14:09Z) Added `docs/architecture/defs-authoring-browser-ui-walkthrough.md` plus screenshots under `docs/architecture/assets/defs-authoring-browser-ui/` so a novice can reproduce the browser path, compare expected startup and scheduler transcripts, and verify the runtime-profile ref in both the browser and `var/runs/.../runtime-profile.edn`.

## Surprises & Discoveries

- Observation: the repository already has a browser Defs page, but it is read-only.
  Evidence: `frontend/src/meta_flow_ui/pages/defs.cljs` renders task-type and runtime-profile list/detail screens, and `frontend/src/meta_flow_ui/pages/defs/state.cljs` only calls `/api/task-types`, `/api/task-types/detail`, `/api/runtime-profiles`, and `/api/runtime-profiles/detail`.

- Observation: the frontend already has a working create-dialog pattern that is close to the needed authoring UI shape.
  Evidence: `frontend/src/meta_flow_ui/pages/tasks/create.cljs` renders a modal dialog with per-field validation, page-local state updates, submit handling, and cancel behavior. The same pattern can host clone-first definition authoring with less risk than inventing a new UI framework.

- Observation: the backend contract is broader than the current browser app uses.
  Evidence: `src/meta_flow/ui/http/defs/handlers.clj` already exposes `/api/defs/contract`, templates, drafts list/detail, validate, publish, reload, and generation routes for both `runtime-profile` and `task-type`, but the browser HTTP helper does not yet wrap these endpoints.

- Observation: the frontend architecture is intentionally narrow and governed.
  Evidence: `frontend/src/meta_flow_ui/app.cljs`, `frontend/src/meta_flow_ui/routes.cljs`, and `src/meta_flow/lint/check/frontend/shared_ui_support.clj` show that new browser work must stay inside page namespaces plus `ui/layout` and `ui/patterns`, and must not introduce page-specific implementation into shared UI facades.

- Observation: the browser app and API server run on different ports in local development, so “observable acceptance” must mention both processes explicitly.
  Evidence: `bb.edn` defines `bb ui:watch` for `http://localhost:8787` and `bb ui:api` for `http://localhost:8788`, and `frontend/src/meta_flow_ui/http.cljs` already special-cases port `8787` to call the API server on `8788`.

- Observation: the repo does not currently include a dedicated CLJS test runner or browser component-test harness.
  Evidence: `bb.edn` exposes `ui:watch`, `ui:release`, `ui:check`, `ui:governance`, and JVM-side `bb test`, but there is no frontend test task, and repository search does not show `cljs.test`, `doo`, or another configured browser-test runner under `frontend/`.

- Observation: the create routes already perform draft planning and validation internally, so an explicit browser-side validate call is useful as preview but is not required before create.
  Evidence: `src/meta_flow/ui/http/defs/handlers.clj` exposes `POST /api/defs/.../drafts` independently from `POST /api/defs/.../drafts/validate`, and `src/meta_flow/defs/authoring.clj` shows `create-definition-draft!` calling `plan-definition-draft!` before writing the draft.

- Observation: description generation can produce either a single task-type draft or a linked pair of runtime-profile plus task-type drafts, and it returns notes that the browser should surface.
  Evidence: `test/meta_flow/defs/generation_test.clj` covers both the two-draft case and the one-draft case, and asserts returned `:notes` explaining draft status and publish ordering.

- Observation: local browser acceptance can be skewed by already running or stale `ui:api` and `shadow-cljs` processes.
  Evidence: a local spike hit `bb ui:api` with “Address already in use”, `bb ui:watch` with “shadow-cljs already running”, and a live process on `:8788` returned a stale authoring contract that did not match the current source tree.

- Observation: a narrow Defs authoring state spike compiled cleanly without requiring a new frontend architecture, but leaving that half-implementation checked in would create more noise than value before the real UI work starts.
  Evidence: a temporary `:defs :authoring` state subtree plus expanded `frontend/src/meta_flow_ui/pages/defs/state.cljs` orchestration compiled successfully with `npm run ui:check`, and the spike was then intentionally removed after capturing the design conclusion in this plan.

- Observation: the Tasks browser dialog depends on more than the raw task-type id and version; it consumes `create-options` input-schema placeholders and renders task-list secondary text from task-type names rather than ids.
  Evidence: the first Milestone 5 test revision failed until its expectations were updated to match `/api/task-types/create-options` returning `:field/placeholder "my-unique-work-key"` and `/api/tasks` returning `:task/summary {:secondary "Repo review mock"}` for the authored task type.

- Observation: the default browser authoring proof writes into a repo-local overlay at `defs/`, so manual Milestone 5 acceptance must either clean that directory afterward or deliberately keep the authored overlay files.
  Evidence: `src/meta_flow/defs/source.clj` sets `default-overlay-root` to `"defs"`, and the live walkthrough created `defs/drafts/runtime-profiles/runtime-profile_browser-m5-ui-proof_v1.edn` and `defs/drafts/task-types/task-type_browser-m5-ui-proof_v1.edn` during proof.

## Decision Log

- Decision: this plan will add browser authoring on top of the existing clone-first backend contract instead of redesigning the request model.
  Rationale: the current backend already validates a stable request shape, enforces publish order, and is covered by tests. Replacing that model in the browser milestone would duplicate risk and break the contract the previous ExecPlan just stabilized.
  Date/Author: 2026-04-06 / Codex

- Decision: the first browser UI will be modal- and panel-based inside the existing Defs page, not a brand-new dedicated authoring application.
  Rationale: the current browser app already routes to Defs list/detail pages and already has reusable dialog behavior in the Tasks page. Extending the existing page keeps the information architecture simple for a novice and aligns with the current frontend layering.
  Date/Author: 2026-04-06 / Codex

- Decision: the first browser authoring pass will remain clone-first and allow only the same supported override keys the backend already documents.
  Rationale: a schema-first visual editor would encourage users to expect arbitrary field editing, which is not what the current authoring service supports. The browser UI should make the supported path obvious rather than implying broader capability.
  Date/Author: 2026-04-06 / Codex

- Decision: task-type authoring in the browser must keep the publish-order rule visible in the UI rather than hiding it behind backend errors.
  Rationale: the backend already rejects task-type drafts that reference unpublished runtime-profile drafts. The browser should help the user avoid that error by surfacing published runtime-profile choices separately from draft state and by showing the rule near the form.
  Date/Author: 2026-04-06 / Codex

- Decision: description generation will be included in the browser plan only as a thin entry point that creates drafts through the same authoring state.
  Rationale: `/api/defs/task-types/generate` already exists and already writes drafts. The browser can expose it without turning generation into a separate workflow or bypassing draft review.
  Date/Author: 2026-04-06 / Codex

- Decision: the first proof strategy will not require a new CLJS test harness before browser work can start.
  Rationale: the repository already has strong backend HTTP acceptance for authoring and task execution, but no configured frontend test runner. Requiring a new harness up front would turn a browser-authoring spike into a tooling project. The first implementation must therefore be proven by backend HTTP acceptance, browser-visible walkthroughs or transcripts, and the existing frontend compile/governance gates. Optional CLJS state tests are still welcome if a low-friction harness emerges during implementation.
  Date/Author: 2026-04-06 / Codex

- Decision: explicit validate stays in the browser UI as a preview and troubleshooting aid, not as a hard prerequisite that blocks create.
  Rationale: the backend already validates create requests. Requiring validate-first in the initial browser release would add unnecessary client-side state coupling and slow down the shortest useful authoring flow. The browser should make validate easy, but create must remain available as the primary action.
  Date/Author: 2026-04-06 / Codex

- Decision: generation UI must treat the backend response as a possibly multi-draft result rather than assuming a single created task-type draft.
  Rationale: the generation backend can create a linked runtime-profile draft and task-type draft together, or just a task-type draft, and returns notes explaining the outcome. The browser must render what was actually created rather than forcing the response into a single-item shape.
  Date/Author: 2026-04-06 / Codex

- Decision: manual browser acceptance steps must begin by checking whether local UI processes are already running and whether the API contract matches current source expectations.
  Rationale: in real local development, stale or pre-existing processes can make the browser appear broken or inconsistent when the issue is environment drift. The plan should reduce that friction explicitly.
  Date/Author: 2026-04-06 / Codex

- Decision: spike code for Defs authoring state should not remain checked in unless it is immediately being carried through to user-visible UI.
  Rationale: the goal of the current phase is to reduce execution friction, not to accumulate partial implementation. Once the spike proved that the proposed state shape and orchestration layer compile cleanly, the useful artifact became the plan update, not the temporary code.
  Date/Author: 2026-04-06 / Codex

- Decision: Milestone 5 backend acceptance will prove the Tasks browser path by asserting `/api/task-types/create-options`, not by introducing a new CLJS component-test harness.
  Rationale: the Tasks page creates tasks from the `create-options` endpoint, so asserting that contract keeps the proof browser-aligned while preserving the repo's existing testing posture.
  Date/Author: 2026-04-06 / Codex

- Decision: the live browser proof should be captured as a checked-in walkthrough with screenshots and terminal transcripts, but the generated `defs/` overlay must still be treated as disposable proof output rather than source.
  Rationale: the milestone explicitly asks for browser-visible proof. A durable walkthrough document plus artifacts satisfies that requirement, while cleaning repo-local overlay files avoids shipping accidental authored data with the feature.
  Date/Author: 2026-04-06 / Codex

## Outcomes & Retrospective

Milestone 5 is now delivered. The browser authoring feature is no longer proven only by intermediate UI renders and backend endpoint tests; it now has a browser-aligned HTTP acceptance test for the Tasks dialog contract plus a checked-in walkthrough showing the live UI path from authored defs to task completion. A novice can follow one document, run the local UI, compare expected browser and terminal output, and confirm that the finalized run preserved the authored runtime-profile ref.

This plan started from a better place than the prior authoring plan did because the backend authoring system was already real, tested, and observable through CLI and HTTP. The remaining gap was usability and proof. That gap is now closed: the repository ships a browser authoring surface, an acceptance test that covers the browser task-creation contract, and screenshots/transcripts that demonstrate the intended user story end to end without requiring a frontend-specific test runner.

The main lesson from the final milestone is that proof has to match the real browser contract, not just the underlying data model. The last acceptance gap was not missing backend behavior; it was missing evidence that the exact `Tasks` dialog input source and task-detail/runtime evidence held together after authoring. The completed milestone therefore favors contract-shaped acceptance and explicit cleanup guidance for the repo-local `defs/` overlay over introducing heavier frontend test infrastructure.

## Context and Orientation

The browser UI lives under `frontend/src/meta_flow_ui/`. This is a ClojureScript application compiled by `shadow-cljs` into static assets under `frontend/public/`. In local development, `bb ui:watch` serves the browser app on `http://localhost:8787` and recompiles on change, while `bb ui:api` serves the backend JSON API on `http://localhost:8788`.

Milestone 4 is now delivered in that frontend tree. The task-type authoring page still carries the clone-first dialog from Milestone 3, but it now also includes a second browser entry point for description-driven generation and a result section that explicitly keeps generated outputs in draft state until publish. The practical effect is that a novice can stay on `#/defs`, ask the browser UI to derive a task type from plain language, and immediately see whether the backend produced one task-type draft or a linked runtime-profile plus task-type pair, along with the publish-order notes returned by the server.

The current browser entry point is `frontend/src/meta_flow_ui/app.cljs`. It chooses which page component to render based on route state from `frontend/src/meta_flow_ui/routes.cljs`. Routes are hash-based, so a path like `#/defs` selects the definitions page without server-side routing changes.

The definitions browser page today is `frontend/src/meta_flow_ui/pages/defs.cljs`. It renders task-type and runtime-profile list/detail views by calling helpers in `frontend/src/meta_flow_ui/pages/defs/state.cljs`, `frontend/src/meta_flow_ui/pages/defs/list.cljs`, and `frontend/src/meta_flow_ui/pages/defs/detail.cljs`. A “page-local state module” here means a namespace that reads and updates a slice of the shared Reagent atom in `frontend/src/meta_flow_ui/state.cljs`.

The shared browser state lives in `frontend/src/meta_flow_ui/state.cljs` inside `ui-state`. The current `:defs` state only holds list and detail data for published task types and runtime profiles. It does not yet hold authoring form data, template lists, draft lists, submit state, or validation errors.

The frontend HTTP wrapper is `frontend/src/meta_flow_ui/http.cljs`. It currently exposes `fetch-json` and `post-json`. The browser Defs page uses it only for read-only endpoints. The backend authoring endpoints already exist in `src/meta_flow/ui/http/defs/handlers.clj`. Those routes support:

- `/api/defs/contract`
- `/api/defs/runtime-profiles/templates`
- `/api/defs/runtime-profiles/drafts`
- `/api/defs/runtime-profiles/drafts/detail`
- `/api/defs/runtime-profiles/drafts/validate`
- `/api/defs/runtime-profiles/drafts/publish`
- `/api/defs/task-types/templates`
- `/api/defs/task-types/drafts`
- `/api/defs/task-types/drafts/detail`
- `/api/defs/task-types/drafts/validate`
- `/api/defs/task-types/drafts/publish`
- `/api/defs/task-types/generate`
- `/api/defs/reload`

The existing Tasks page provides the closest browser pattern for authoring UI. `frontend/src/meta_flow_ui/pages/tasks.cljs` opens a modal dialog from a page action button, while `frontend/src/meta_flow_ui/pages/tasks/create.cljs` renders the dialog fields and submit actions. `frontend/src/meta_flow_ui/pages/tasks/state.cljs` manages the dialog state and POST submission flow. The new definition authoring browser work should mirror that pattern so a novice sees a familiar create-then-submit interaction.

The previous ExecPlan at `docs/architecture/description-driven-extension-execplan.md` already completed the backend authoring milestone. That matters because this plan should not redefine what a draft is. In this repository, a “draft” means a definition file stored under `defs/drafts/` that is visible through `/api/defs/.../drafts` but is not part of the live repository until an explicit publish step copies it into `defs/`.

## Milestones

### Milestone 1: Add Frontend Authoring State And API Bindings

At the end of this milestone, the browser app still looks mostly the same, but the frontend knows how to talk to the authoring backend. The Defs page state module can load templates, list drafts, inspect a draft, validate a draft request, create a draft, publish a draft, reload definitions, and generate drafts from description. This milestone is successful when browser code can exercise the backend authoring contract without yet needing polished visual controls.

The proof is that the new browser orchestration functions can drive the backend contract end to end, with success and failure state visible in browser-facing code paths. If a low-friction CLJS state test harness is introduced during implementation, use it; otherwise prove this milestone with backend HTTP acceptance coverage plus a browser or REPL-driven state smoke that demonstrates the expected data shapes for templates, drafts, validation responses, publish responses, and generation responses.

### Milestone 2: Add Runtime Profile Browser Authoring

At the end of this milestone, a user browsing `#/defs/runtimes` can open a runtime-profile authoring dialog, choose a template, enter `new-id` and `new-name`, optionally set supported overrides, validate the request when useful, create a draft, inspect the draft, and publish it. The page should then refresh the published runtime-profile list so the newly authored profile is visible without a manual server restart.

The proof is that a novice can start the browser app, open the runtime profile tab, create a clone of `runtime-profile/mock-worker` or `runtime-profile/codex-worker`, publish it, and immediately see it in the runtime profile list and detail views.

### Milestone 3: Add Task Type Browser Authoring

At the end of this milestone, a user browsing `#/defs` can open a task-type authoring dialog, choose a template, enter `new-id` and `new-name`, set supported overrides, optionally validate, and create/publish a task-type draft. The UI must visibly explain the publish-order rule: only published runtime profiles may be selected for the task-type runtime-profile override in the first browser release.

The proof is that a novice can publish a new runtime profile in the runtime tab, switch back to the task-type tab, create a task type that references the published runtime profile, publish it, and immediately see the authored task type in list and detail views.

### Milestone 4: Add Draft Review And Description Generation

At the end of this milestone, the browser UI shows draft lists and draft detail for both definition kinds, and exposes a description-generation entry point for task types. Generation must create drafts through the existing backend route and then refresh the draft lists. The browser must make clear that generated results are still drafts and still require publish, and it must handle the real backend outcome shape: either a single task-type draft or a linked pair of runtime-profile plus task-type drafts, plus any returned notes.

The proof is that a novice can enter a natural-language description, see whichever draft rows were actually created appear in the browser, inspect them, read the returned guidance, and publish them in the correct order.

### Milestone 5: Prove End-To-End Browser Behavior

At the end of this milestone, the feature is not just visually present; it is behaviorally proven. Backend HTTP acceptance plus a documented browser walkthrough or transcript must show that authored definitions created from the Defs browser UI can be used by the Tasks browser UI to create a task, and that the task runs using the authored runtime profile after scheduler execution.

The proof is a test and a manual walkthrough that uses the browser UI plus the scheduler API path to observe the authored definition refs end to end.

## Plan of Work

Start by expanding the frontend state model in `frontend/src/meta_flow_ui/state.cljs`. Add a dedicated `:authoring` sub-map under `:defs` rather than scattering new atoms through multiple namespaces. The first release needs separate state for runtime-profile authoring and task-type authoring, each with template lists, drafts lists, selected template id/version, form values, validation result, submit error, submitting flag, publish-in-flight flag, and any currently viewed draft detail. Also add state for description generation so generated drafts can reuse the same visible draft lists.

Next, extend `frontend/src/meta_flow_ui/pages/defs/state.cljs` so it becomes the single browser-facing orchestration layer for the Defs page. Add wrappers that call the existing authoring endpoints through `meta-flow-ui.http/post-json` and `meta-flow-ui.http/fetch-json`. Keep the current read-only loaders intact, but add new functions with explicit names such as `load-authoring-contract!`, `load-runtime-profile-templates!`, `load-task-type-templates!`, `load-runtime-profile-drafts!`, `load-task-type-drafts!`, `validate-runtime-profile-draft!`, `create-runtime-profile-draft!`, `publish-runtime-profile-draft!`, `validate-task-type-draft!`, `create-task-type-draft!`, `publish-task-type-draft!`, `generate-task-type-draft!`, and `reload-definitions!`. Each must update the shared `:defs` state predictably on success and failure.

Then add browser controls in `frontend/src/meta_flow_ui/pages/defs.cljs` and, if the file grows too large, split authoring UI into new namespaces under `frontend/src/meta_flow_ui/pages/defs/authoring/`. Reuse the page-shell action area the same way the Tasks page does. The runtime profile tab should gain a “New Runtime Profile” action; the task type tab should gain a “New Task Type” action and a “Generate From Description” action. Use modal dialogs for data entry in the first pass because the repository already has a proven modal create flow in the Tasks page and because dialogs keep the browsing context visible behind the authoring interaction.

Add reusable rendering helpers for the forms and draft panels. Runtime-profile authoring should expose only the stable clone-first fields: template, new id, new name, optional new version, web-search toggle when applicable, and worker prompt path when applicable. Task-type authoring should expose template, new id, new name, optional new version, description override, runtime-profile selector constrained to published runtime profiles, input schema override only if the backend contract already supports it safely, and work-key override only if it can be represented clearly. The browser must not invent fields that the backend contract does not support.

Add draft browsing to the Defs page layout. The user needs to see not only published definitions but also drafts. The simplest safe pattern is a secondary panel or section below the main published list that shows current drafts for the active tab, each with detail and publish actions. Keep the draft vocabulary explicit: label these rows as drafts, show their file-like identity as id plus version, and show validation or publish guidance inline when available.

Add browser-side UX for validation without making it mandatory. The backend already has `/validate` endpoints. The browser should call validate explicitly when the user clicks “Validate” and show the resolved template, normalized request, and any backend error. Creation should remain available without a prior validate click because the backend already validates create requests; if create fails, the browser should preserve the entered values and show the exact backend error. This keeps the shortest authoring path fast while still making validation discoverable.

Update routing only where necessary. The first browser authoring release does not need a completely separate route tree if dialogs are sufficient. However, if draft detail becomes large or needs deep-linking, add hash routes such as `#/defs/drafts/...` only after keeping the browsing story simple. Prefer reusing `#/defs` and `#/defs/runtimes` until a deeper route clearly improves behavior.

Add CSS in page-specific styles rather than generic shared files unless the pattern is truly shared. The existing Defs page already uses `frontend/public/styles/pages/defs.css` and `frontend/public/styles/pages/defs/detail.css`, and `frontend/public/index.html` already loads those files. Extend those page styles or add a sibling `frontend/public/styles/pages/defs/authoring.css` if the volume becomes large enough. Do not move page-specific authoring visuals into `components.css` or shared UI layers.

Finally, add proof coverage. Frontend tests in this repository are light today, so the first browser-facing acceptance should rely on backend HTTP tests, the existing frontend compile/governance gates, and a documented browser walkthrough or transcript. If a low-friction CLJS/state test harness becomes available during implementation, add targeted state tests for the new Defs orchestration helpers; if not, do not block the browser milestone on frontend harness work. Keep the repository’s unified gates green: formatting, lint, frontend build governance, unit tests, and coverage.

## Concrete Steps

All commands below run from `/Users/mike/projs/main/meta-flow`.

Before starting new processes, check whether local UI processes are already running. In real local development it is common to have an existing API server on `:8788` or an existing `shadow-cljs` watcher.

First, verify the authoring API contract from the running backend if one exists:

    curl -s http://localhost:8788/api/defs/contract

Expected contract signal from current source:

    task-type supported-override-keys includes task-type/description

If the port is closed, start `bb ui:api`. If the port is open but the returned contract does not match the current source tree, treat the process as stale and restart it before trusting browser behavior.

Start the backend API server:

    bb ui:api

Expected startup line:

    UI server running on http://localhost:8788

In a second shell, start the browser frontend watcher:

    bb ui:watch

Expected startup outcome:

    shadow-cljs watch app
    ...
    Available at http://localhost:8787

If `bb ui:watch` reports that `shadow-cljs` is already running for this project, reuse the existing watcher if it reflects the current worktree, or restart it deliberately before proceeding. Do not assume that a pre-existing watcher or API process is current.

Open the browser at `http://localhost:8787/#/defs`. Before implementation, the page is read-only. After Milestone 2 and 3, the task-type and runtime-profile tabs must each expose authoring actions such as “New Runtime Profile” or “New Task Type”.

Create a runtime profile through the browser UI by cloning `runtime-profile/mock-worker`. The intended first-release values are:

    template: runtime-profile/mock-worker
    new id: runtime-profile/repo-review-mock
    new name: Repo review mock worker

Expected browser outcome:

    draft created successfully
    runtime profile draft appears in the runtime draft list

Publish that runtime profile from the same browser page. Expected outcome:

    publish succeeded
    runtime-profile/repo-review-mock appears in the published runtime list

Then create a task type through the browser UI by cloning `task-type/default` and pointing it at the published runtime profile:

    template: task-type/default
    new id: task-type/repo-review-mock
    new name: Repo review mock
    runtime profile: runtime-profile/repo-review-mock

Expected browser outcome:

    draft created successfully
    task type draft appears in the task-type draft list

Publish that task type. After publish, confirm it appears in the published task type list and detail view.

Finally navigate to `#/tasks`, open the existing “New Task” dialog, choose `task-type/repo-review-mock`, enter:

    work-key: repo-review/acme

Expected browser/API outcome:

    task is created in queued state
    task detail shows runtime-profile/repo-review-mock as the pinned runtime profile

After scheduler execution is wired into the acceptance walkthrough, run:

    clojure -M:native-access -m meta-flow.main scheduler once

or repeatedly:

    bb scheduler:run --task-id <task-id>

and expect the task to reach a terminal state while preserving the authored runtime-profile ref.

## Validation and Acceptance

Acceptance is behavioral and browser-visible.

First, run:

    bb ui:check
    bb ui:governance
    bb test
    bb check

and expect all gates to pass. The final `bb check` output should include the frontend build gate and executable correctness as passing.

Second, with `bb ui:api` and `bb ui:watch` running, open `http://localhost:8787/#/defs/runtimes`. The page must show a runtime-profile authoring action. Use the browser only, not curl, to create and publish a runtime-profile clone. After publish, refresh-free visibility is required: the new runtime profile must appear in the list without restarting the API server or frontend watcher.

Third, open `http://localhost:8787/#/defs`. The page must show a task-type authoring action. Use the browser only to create and publish a task-type clone that references the newly published runtime profile. The page must either prevent unpublished runtime-profile choices or clearly reject them with the backend publish-order message.

Fourth, open `http://localhost:8787/#/tasks`. Use the existing New Task dialog to create a task from the newly authored task type. The task detail must show the authored runtime-profile ref. This proves that browser authoring affects the normal task creation surface rather than only a hidden draft store.

Fifth, run the scheduler and observe the task complete with the authored runtime profile. The browser task detail, run detail, or documented runtime file inspection must show that the authored runtime profile was used during execution.

Sixth, if description generation is included in the implemented milestone, the browser must show a generation action that creates drafts, not published definitions. The generated result may add one task-type draft or a linked runtime-profile plus task-type draft pair. Whatever was created must appear in the draft list and require explicit publish.

Tests to add or update must include at least:

- backend HTTP acceptance tests aligned with the exact browser flow
- a browser walkthrough, transcript, or equivalent browser-visible artifact proving the Defs UI path works end to end
- frontend state tests for the new Defs authoring helpers only if a low-friction CLJS harness is introduced during this work
- any route or UI regression tests needed for the expanded Defs page

## Idempotence and Recovery

The browser UI must be safe to retry. Reopening a dialog or refreshing the page must not silently duplicate a published definition. If the user retries a create or publish request for an id/version that already exists, the browser should display the backend conflict error and preserve the entered form values so the user can adjust the version or id.

Draft creation should be idempotent at the UI level in the same sense the backend is safe: if the user validates repeatedly, the browser should update the validation preview without writing duplicate drafts. Creation should happen only when the user explicitly clicks the create action.

Publish failures must preserve context. If publish fails because of a conflict or backend validation problem, keep the draft visible, keep the dialog or draft panel open, and show the exact backend error. Do not clear the form or silently reload away from the error state.

If the user reloads the browser tab mid-authoring, published data must still be recoverable from the API after the page reloads. Draft form values do not need durable persistence in the first release, but draft lists must reload from the server so the authored drafts are not lost from view.

## Artifacts and Notes

The most important files this plan is expected to touch are:

- `docs/architecture/defs-authoring-browser-ui-execplan.md`, this plan
- `docs/architecture/defs-authoring-browser-ui-walkthrough.md` for the Milestone 5 novice-facing proof transcript
- `docs/architecture/assets/defs-authoring-browser-ui/defs-published.png` and `docs/architecture/assets/defs-authoring-browser-ui/tasks-completed.png` for browser-visible evidence
- `frontend/src/meta_flow_ui/state.cljs` for the expanded defs authoring state shape
- `frontend/src/meta_flow_ui/pages/defs/state.cljs` for authoring HTTP orchestration
- `frontend/src/meta_flow_ui/pages/defs.cljs` for authoring actions and draft sections
- `frontend/src/meta_flow_ui/pages/defs/list.cljs` and `frontend/src/meta_flow_ui/pages/defs/detail.cljs` if list/detail rendering must expose draft or publish affordances
- new `frontend/src/meta_flow_ui/pages/defs/authoring/*.cljs` namespaces if the dialogs and forms need to be split out
- `frontend/public/styles/pages/defs.css` and possibly a new authoring-specific defs stylesheet
- backend tests under `test/meta_flow/ui/http/` that prove the browser path’s backing API behavior

Expected manual success story after implementation:

    $ bb ui:api
    UI server running on http://localhost:8788

    $ bb ui:watch
    ...
    Available at http://localhost:8787

    Browser:
      open #/defs/runtimes
      click New Runtime Profile
      clone runtime-profile/mock-worker
      publish runtime-profile/repo-review-mock

      open #/defs
      click New Task Type
      clone task-type/default
      choose runtime-profile/repo-review-mock
      publish task-type/repo-review-mock

      open #/tasks
      create task with work-key repo-review/acme
      observe queued task pinned to runtime-profile/repo-review-mock

## Interfaces and Dependencies

In `frontend/src/meta_flow_ui/state.cljs`, extend the `default-defs-state` map with an `:authoring` subtree. At the end of implementation, that subtree must be rich enough to store:

- backend contract metadata
- template lists for `runtime-profile` and `task-type`
- draft lists and current draft detail for both definition kinds
- dialog state for runtime-profile authoring
- dialog state for task-type authoring
- generation form state for description-driven task-type draft generation
- validation results and submit errors

In `frontend/src/meta_flow_ui/pages/defs/state.cljs`, define stable browser orchestration functions that wrap the backend authoring routes. They should mirror the backend contract names so a novice can match browser code to API routes directly.

In `frontend/src/meta_flow_ui/pages/defs.cljs`, keep the current page-shell entry point but add authoring actions and sections. If the file becomes too large or violates frontend governance, split visual authoring components into new page-specific namespaces under `frontend/src/meta_flow_ui/pages/defs/authoring/`.

In `frontend/src/meta_flow_ui/http.cljs`, keep using the existing `fetch-json` and `post-json` helpers. No new transport abstraction is needed in this milestone.

In `src/meta_flow/ui/http/defs/handlers.clj`, the backend route contract should remain stable. This plan assumes the browser is a consumer of those routes, not a reason to redesign them.

Use the existing frontend stack already present in the repository:

- Reagent for browser state and components
- shadow-cljs for compilation and local watch mode
- the current page-shell and dialog patterns from `frontend/src/meta_flow_ui/components.cljs` and the Tasks page

Do not introduce a second frontend state library, router, form library, or CSS framework for this milestone.

Revision note: 2026-04-06 / Codex. Created this ExecPlan to add the missing browser UI layer on top of the already completed definition authoring backend. The plan chooses an incremental route: first wire state and API bindings, then add runtime-profile and task-type dialogs, then prove the authored definitions work through the existing Tasks UI.
Revision note: 2026-04-06 / Codex. Updated the plan after completing Milestone 5. The revision marks the final acceptance work complete, records the browser-aligned `create-options` test tightening, documents the repo-local overlay cleanup concern, and links the new walkthrough and screenshot artifacts that prove the browser flow end to end.
