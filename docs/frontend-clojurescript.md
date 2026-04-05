# Meta-Flow ClojureScript Frontend Strategy

This document records the current decision for introducing a browser UI to Meta-Flow.
Its primary purpose is boundary control: frontend work must stay inside a narrow,
predictable scope and must not erode the existing control-plane architecture.

## Status

- accepted for initial frontend introduction
- applies to the first browser UI implementation for this repository
- may be revised later, but only by updating this document explicitly

## Primary Goal

The immediate goal is not "add a modern frontend stack".
The goal is to introduce a UI surface without allowing frontend code to:

- become a second control plane
- reach directly into SQLite, runtime workdirs, or scheduler internals
- force premature framework complexity
- blur the boundary between authoritative state and rendered views

This document therefore optimizes for controlled scope over maximal flexibility.

## Chosen Stack

The selected frontend stack is:

- ClojureScript
- `shadow-cljs` for build, dev watch, browser REPL, and production release compilation
- `Reagent` as the default React wrapper
- `Radix Primitives` for accessible interaction primitives
- `npm` for JavaScript package management
- app-owned CSS with semantic design tokens via CSS custom properties

This stack is chosen because it is the narrowest practical path for a browser UI in the
ClojureScript ecosystem today:

- `shadow-cljs` is the mainstream application-oriented CLJS toolchain and has the best
  default experience for browser builds, live reload, and npm interop
- `Reagent` is thin, stable, and sufficient for dashboards, read-heavy views, and
  incremental UI work
- `Radix Primitives` provide accessible behavior for complex controls without taking over
  the project's visual language
- direct `npm` interop keeps the project aligned with the current JavaScript ecosystem
  rather than introducing older packaging conventions as the default path
- CSS custom properties provide a stable runtime styling boundary without forcing a
  larger styling framework up front

## Design Tokens And Visual Consistency

Visual consistency will be enforced through semantic design tokens, not through ad hoc
per-component styling.

The project should maintain three token layers:

- reference tokens
  raw visual values such as palette steps, spacing steps, radius steps, shadow steps,
  and font families
- semantic tokens
  role-based tokens such as `--color-bg-canvas`, `--color-bg-panel`,
  `--color-text-primary`, `--color-border-muted`, `--space-stack-md`,
  `--radius-control-md`
- component tokens
  narrow aliases for reusable UI pieces when needed, such as
  `--button-bg-primary` or `--table-row-hover-bg`

The rule is:

- raw values are defined once
- semantic tokens are what application styles consume by default
- component tokens are introduced only when a reusable component needs its own contract

Recommended initial file shape:

```text
frontend/src/meta_flow_ui/styles/
  tokens.css
  theme.css
  base.css
  components.css
```

Recommended ownership:

- `tokens.css`
  reference token declarations and shared token naming contract
- `theme.css`
  semantic token mappings for the default light theme and any future theme overrides
- `base.css`
  typography, element defaults, layout scaffolding
- `components.css`
  shared component classes only

Implementation rules:

- no raw hex colors in component styles outside token definition files
- no arbitrary spacing, radius, or shadow literals in feature view styles unless a new
  token is first introduced
- application and component styles should consume semantic tokens by default, not
  reference tokens directly
- typography, spacing, border, and elevation choices should come from the token set
- z-index values should be tokenized or kept in a very small documented scale
- motion durations and easing should come from tokens if animation is introduced

This is the main mechanism for keeping the UI visually coherent across multiple
contributors.

## UI Framework And CSS Library Decision

The project should distinguish between three separate concerns:

- rendering framework
- accessible interaction primitives
- styling system

Current decisions:

- rendering framework: `Reagent`
- accessible interaction primitives: `Radix Primitives`
- styling system: app-owned CSS backed by semantic tokens

The default styling decision is intentionally not:

- a full visual UI framework such as Bootstrap, Ant Design, or Material UI
- a pre-styled React theme system as the main source of visual language
- a utility framework as the source of design truth

Reason:

- a full visual framework would define too much of the UI shape too early
- it would make the first Meta-Flow frontend look like a generic admin template
- it would weaken the boundary between product-specific visual language and third-party
  defaults
- it would increase the cost of later visual refinement

## Allowed Third-Party UI Surface

`Radix Primitives` are the default behavior layer for shared interactive primitives in
this frontend.

Use Radix by default for behavior-bearing reusable controls such as:

- dialog
- popover
- tabs
- dropdown menu
- tooltip
- select
- checkbox
- switch
- radio group
- scroll area

Why this is acceptable:

- the primitives are explicitly unstyled
- they preserve control over the visual system
- they solve keyboard and interaction complexity without making styling decisions for us

This means third-party UI code may provide behavior, focus management, ARIA semantics,
and state attributes, while Meta-Flow still owns appearance.

Radix Primitives are therefore part of the baseline frontend stack for this repository,
not an incidental optional add-on.

Purely presentational structures such as panels, tables, badges, empty states, and
layout shells remain app-owned components unless there is a specific interaction reason
to compose them from Radix primitives.

## CSS Library Position

The default CSS approach is plain CSS with custom properties.

This means:

- no CSS-in-JS as the default styling strategy
- no utility-first framework as a required dependency for phase 1
- no component-theme package as the default visual system
- no `Radix Themes` as the default styling layer

Tailwind CSS is not the default decision for this repository.
It may be introduced later only if there is a concrete ergonomics reason and only if it
maps to the project's semantic tokens rather than replacing them.

If Tailwind is ever added, the rule remains:

- semantic tokens stay authoritative
- utilities are an access layer over those tokens
- visual consistency still comes from tokens and shared components, not utility
  accumulation alone

## Visual System Rules

To keep the implementation process controlled, the frontend should define and reuse a
small explicit visual system before feature expansion.

Phase 1 should define at least:

- one font stack for UI text
- one font stack for code or IDs if needed
- a fixed type scale
- a fixed spacing scale
- a fixed radius scale
- a fixed border color scale
- a fixed surface/background scale
- a fixed feedback color set for success, warning, danger, and info
- a minimal elevation scale

Shared components should then be built on top of those tokens, for example:

- button
- input
- select
- panel
- table
- status badge
- empty state
- loading state

Feature screens should compose these primitives instead of inventing local one-off
controls.

## Explicit Non-Choices

The following are intentionally not the default for phase 1:

- not `cljs.main` as the main frontend build workflow
- not `re-frame` as the initial application framework
- not a JS-first stack with CLJS embedded as a niche layer
- not a shared full-stack `.cljc` architecture by default
- not code splitting as an initial requirement
- not frontend ownership of workflow or scheduler rules
- not a full pre-styled admin UI kit as the primary visual system

These are deferred, not forbidden forever.
They should only be introduced when the current boundary becomes insufficient.

## Frontend Architecture Boundary

The browser UI is a presentation layer over backend-owned read models.

The boundary is:

- backend owns authoritative state
- SQLite remains the source of truth
- scheduler and runtime adapters remain backend-only concerns
- browser consumes JSON APIs derived from projection and inspection reads
- browser may hold transient UI state, but not workflow truth

The frontend must not:

- read SQLite directly
- read files from runtime artifact directories directly
- call scheduler internals directly
- reconstruct domain truth from multiple ad hoc endpoints
- duplicate task/run state machines in client code

The frontend may:

- render collection, task, run, and artifact summaries returned by backend APIs
- poll backend read endpoints for fresh state
- hold local UI state such as route, filters, panel visibility, form drafts, and
  pending request status
- apply purely presentational derivations over already-fetched backend data

## Integration Model For This Repository

The frontend will be integrated as a separate subtree in the same repository.

Recommended layout:

```text
frontend/
  public/
    index.html
  src/
    meta_flow_ui/
      app.cljs
      routes.cljs
      http.cljs
      state.cljs
      styles/
      views/
      components/

package.json
shadow-cljs.edn
```

This is preferred over putting browser namespaces into `src/meta_flow/` because:

- the current `src/meta_flow/` tree is the control-plane implementation
- frontend build and dependency lifecycles are different from backend lifecycles
- the split prevents accidental namespace and responsibility drift

The frontend remains same-repo, but not same-layer.

## Backend Integration Boundary

Frontend introduction does not mean the existing CLI and scheduler code should become
web-shaped internally.

The backend integration rule is:

- expose a thin HTTP API layer
- back that API with existing projection and inspect-style reads
- keep orchestration, persistence, and runtime logic behind that API

The first UI should be read-oriented.
Initial API surface should therefore prefer endpoints such as:

- `GET /api/collection`
- `GET /api/tasks/:task-id`
- `GET /api/runs/:run-id`
- `GET /api/tasks`
- `GET /api/runs`

If mutation is later required, command endpoints should be explicit and narrow.
Do not begin with a generic "UI can do everything the scheduler can do" posture.

## State Management Rules

Initial frontend state management should stay simple.

Default approach:

- local `reagent/atom` for component-local state
- a small shared app state atom only when multiple screens truly need shared UI state
- explicit fetch functions for server data
- server responses treated as backend snapshots, not client-owned entities

`re-frame` is deferred until there is a demonstrated need for:

- larger event orchestration
- heavier polling/subscription coordination
- complex cross-screen server-state reuse
- nontrivial derived state graphs that are no longer comfortable in plain Reagent

Until then, adding `re-frame` would widen the architecture earlier than necessary.

## Data And Domain Rules

To keep implementation controllable, frontend code should treat backend data with a
strict separation between domain records and view formatting.

Recommended pattern:

- `http.cljs`
  request and response decoding only
- `state.cljs`
  UI state and loading/error flags only
- `styles/`
  tokens, base theme, and shared component styles only
- `views/`
  rendering and small display-only transformations

Avoid:

- burying fetch logic inside view components
- embedding route parsing across many view files
- mixing raw backend payloads with ad hoc UI-only shapes without a clear boundary
- duplicating backend enums, FSM transitions, or policy rules without necessity
- mixing feature-specific CSS literals into many files without tokenization

If the frontend needs labels, grouping, or display summaries, prefer:

- backend computes authoritative lifecycle meaning
- frontend computes display formatting only

## Async And Interop Practices

The default async path is JavaScript `Promise` interop over browser `fetch`.

Guidelines:

- use plain promise interop for API calls
- introduce `core.async` only if async composition becomes materially complex
- keep JS interop at edges, not spread throughout view code
- prefer a small number of wrapper functions around browser APIs

## Testing And Quality Rules

The frontend should have its own validation loop and should not dilute existing backend
governance.

Recommended baseline:

- CLJS unit/component tests via `shadow-cljs` browser test target and `cljs.test`
- formatter/linter choices added only if they provide clear value
- backend `bb check` remains the backend gate, not an implicit frontend gate

When the frontend is implemented, add explicit frontend tasks rather than overloading
existing backend task names with hidden extra behavior.

## Scope Control Rules

The following rules are the main guardrails for implementation work:

1. Frontend work starts from read-only inspection and collection views.
2. Backend remains responsible for task/run semantics, scheduler transitions, and
   validation meaning.
3. New frontend complexity requires proof of need, not preference.
4. Shared `.cljc` code must stay narrow and pure if introduced at all.
5. Any move from plain Reagent to `re-frame` requires an explicit architectural update.
6. Any move from same-repo split layout to a separate repo requires an explicit
   operational justification.

## Phase 1 Recommendation

Phase 1 should target a controlled dashboard, not a full operator console.

Recommended first slice:

- collection overview
- task detail view
- run detail view
- polling-based refresh
- no write actions unless there is a concrete operational need

That slice is enough to validate:

- the HTTP boundary
- the CLJS toolchain fit
- the frontend directory and build structure
- the viability of inspect/projection-backed read models

## First Screen Recommendation

The first browser screen should be a read-only scheduler overview.

It should not begin with:

- a generic task table
- artifact browsing
- worker log streaming
- write controls for scheduler operations

Reason:

- the scheduler is the narrowest operational surface that already has stable read-model
  signals
- it exposes system health and backlog pressure without requiring the browser to know
  scheduler internals
- it gives the frontend a clear first route and data contract before detail screens grow

Recommended first route:

- `/scheduler`

Recommended screen goal:

- answer "is the scheduler healthy right now?"
- answer "what kind of work is waiting or stuck?"
- provide drilldown links into a task or run when an operator sees a problem

## Scheduler Overview Information Plan

The first scheduler screen should show information in four layers.

### 1. Snapshot Header

This is the top-level framing for the entire page.

Display:

- snapshot timestamp
- refresh state
- polling cadence
- stale-data indicator when the UI has not refreshed within the expected interval

This gives the operator confidence about whether they are looking at current state or a
stale browser snapshot.

### 2. Dispatch And Capacity Status

This is the first operational summary block.

Display:

- dispatch paused or active
- dispatch cooldown active or inactive
- cooldown-until timestamp when present
- active run count
- collection resource policy ref for the default collection

Why these fields come first:

- they explain whether the scheduler is intentionally not dispatching
- they distinguish "the queue is idle" from "dispatch is blocked"
- they expose the single collection-level control-plane state already present in the
  backend

### 3. Work Queue And Attention Buckets

This is the main body of the page.

Use one card or panel per scheduler bucket, ordered by operator urgency.

Recommended buckets:

- heartbeat timeout runs
- expired lease runs
- awaiting validation runs
- retryable failed tasks
- runnable tasks

Each bucket should show:

- total count
- a short sample list of ids returned by the backend
- empty-state copy when the count is zero
- a label explaining what the bucket means in scheduler terms

Presentation rule:

- attention buckets should be visually separated from normal backlog buckets
- `heartbeat timeout` and `expired lease` should read as incident-like conditions
- `awaiting validation`, `retryable failed`, and `runnable` should read as workflow
  progress or backlog conditions

Important UI rule:

- the backend snapshot currently returns capped sample id lists
- the UI must show counts as authoritative and treat the id list as a sample, not a full
  result set
- when count exceeds sample size, the UI should say that only the first slice is shown

### 4. Detail Drilldown Panel

The first screen should support narrow drilldown without becoming a full console.

When the operator clicks an id from a bucket, show a side panel or routed detail panel
backed by existing inspect-style reads.

For a selected task, display:

- task id
- task state
- work key
- task type ref
- task fsm ref
- run fsm ref
- runtime profile ref
- artifact contract ref
- validator ref
- resource policy ref
- created-at
- updated-at

For a selected run, display:

- run id
- task id
- attempt
- run state
- lease id
- artifact id
- artifact root
- event count
- last heartbeat
- created-at
- updated-at

This drilldown is enough for the first interface because it answers "what is this item"
and "why is it in this bucket" without exposing raw DB rows or full event timelines.

## Scheduler Overview Priority Rules

The screen should make the operator's reading order explicit.

Recommended priority order:

1. dispatch blocked
2. heartbeat timeout runs
3. expired lease runs
4. awaiting validation runs
5. retryable failed tasks
6. runnable backlog

This ordering matters because the UI should not present all scheduler facts as equally
important.
The first screen is for operational triage, not neutral data browsing.

## Initial Backend Read Models For The Scheduler Screen

The first scheduler screen should reuse existing read-model boundaries instead of adding
frontend-driven domain reconstruction.

Recommended initial API shape:

- `GET /api/scheduler/overview`
  returns the scheduler snapshot derived from projection reads
- `GET /api/collections/default`
  returns default collection inspect data if kept separate from overview
- `GET /api/tasks/:task-id`
  returns inspect-task data for drilldown
- `GET /api/runs/:run-id`
  returns inspect-run data for drilldown

`GET /api/scheduler/overview` should at minimum expose:

- `snapshot/now`
- `snapshot/dispatch-paused?`
- `snapshot/dispatch-cooldown-active?`
- `snapshot/dispatch-cooldown-until`
- `snapshot/runnable-count`
- `snapshot/runnable-task-ids`
- `snapshot/retryable-failed-count`
- `snapshot/retryable-failed-task-ids`
- `snapshot/awaiting-validation-count`
- `snapshot/awaiting-validation-run-ids`
- `snapshot/expired-lease-count`
- `snapshot/expired-lease-run-ids`
- `snapshot/heartbeat-timeout-count`
- `snapshot/heartbeat-timeout-run-ids`
- active run count

If collection data remains separate, the collection response should at minimum expose:

- `collection/dispatch`
- `collection/resource-policy-ref`
- `collection/updated-at`

## Explicit Non-Goals For The First Scheduler Screen

To keep the first UI controlled, the following should stay out of scope:

- write actions such as pause, resume, retry, or force-dispatch
- full run event timeline rendering
- artifact file browser or direct runtime filesystem access
- historical charts and trend analytics
- multi-collection navigation
- generic search across all tasks and runs
- client-side reconstruction of scheduler lifecycle meaning

These can be added later, but they should not be mixed into the first screen.

## Decision Summary

For the first Meta-Flow frontend:

- use `shadow-cljs`
- use `Reagent`
- use `Radix Primitives` as the default behavior layer for shared interactive primitives
- manage JavaScript dependencies with `npm`
- use semantic design tokens via app-owned CSS custom properties
- keep styling app-owned even when using Radix primitives
- keep frontend code in a dedicated `frontend/` subtree
- integrate through thin backend JSON APIs over existing read models
- keep the first UI read-oriented and operationally narrow
- defer `re-frame`, code splitting, and broader architectural expansion until justified

## External References

- ClojureScript Quick Start
  https://clojurescript.org/guides/quick-start
- ClojureScript Promise Interop
  https://clojurescript.org/guides/promise-interop
- ClojureScript Code Splitting
  https://clojurescript.org/guides/code-splitting
- shadow-cljs User Guide
  https://shadow-cljs.github.io/docs/UsersGuide.html
- Reagent
  https://github.com/reagent-project/reagent
- Design Tokens Community Group
  https://www.designtokens.org/
- Design Tokens Format Module 2025.10
  https://www.w3.org/community/reports/design-tokens/CG-FINAL-format-20251028/
- Radix Primitives Styling
  https://www.radix-ui.com/primitives/docs/guides/styling
- Tailwind Theme Variables
  https://tailwindcss.com/docs/theme
- re-frame Best Practice FAQ
  https://day8.github.io/re-frame/FAQs/BestPractice/
- re-frame Subscriptions
  https://day8.github.io/re-frame/subscriptions/
