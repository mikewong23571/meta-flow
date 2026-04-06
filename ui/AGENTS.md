# UI Project Notes

## Scope

This directory is the separate browser UI project for Meta-Flow. It owns the Reagent/Shadow-CLJS frontend, frontend-specific governance checks, and the compiled preview assets under `public/`.

Read it by boundary:

- `src/meta_flow_ui/` is the product UI. `app.cljs` mounts the app, `routes.cljs` owns hash routing, `state.cljs` holds app-level atoms, `http.cljs` owns browser fetch helpers, and `components.cljs` plus `ui/` hold shared presentation primitives.
- `src/meta_flow_ui/pages/` is the main feature surface. `defs/` is the largest and most stateful page family; `scheduler/` and `tasks/` each own their page-local state and detail flows; `home.cljs` and `preview.cljs` are lighter entry pages.
- `public/styles/` is the styling system. `tokens.css` and `theme.css` define design tokens; shared layout and interaction rules live under `shared/`; page-specific CSS lives in `pages/`, `scheduler.css`, and `tasks.css`.
- `src/meta_flow/lint/` and `src/meta_flow/governance/` own UI governance gates, reporting, and machine-readable node output.
- `test/meta_flow/lint/` covers the frontend governance layer rather than browser interaction tests.

## Hotspots

- `src/meta_flow_ui/pages/defs/` is the main pressure zone: it has the deepest subtree, the most cross-file state flow, and the largest UI files in the project.
- `src/meta_flow/lint/check/frontend/` is the main governance hotspot: style, semantics, architecture, page-role, and build checks all converge there.
- `src/meta_flow_ui/pages/tasks.cljs` and `src/meta_flow_ui/pages/scheduler.cljs` are smaller than defs but still act as page-level orchestration facades, so they should stay thin.

## Change Rules

- Keep page orchestration near the page root and move repeatable rendering or state transitions down into feature subdirectories before a page namespace turns into another `defs.cljs`.
- Treat `routes.cljs`, page-local `state.cljs`, and `http.cljs` as explicit boundaries. Do not bury routing, polling, or remote I/O inside presentation helpers.
- Reuse shared UI primitives from `components.cljs` and `ui/` when semantics are actually shared; do not create one-off "shared" wrappers that are still page-specific.
- Keep styles token-driven. Raw colors belong in `tokens.css` or `theme.css`, not in page CSS or inline literals.
- When a UI change affects structure or semantics, expect the frontend governance checks to be the first consumer that breaks.

## Validation

- Run `bb check` from `ui/` for the normal pre-PR gate.
- Use `bb governance` when you need the detailed frontend governance report.
- Use `bb test` when changing the governance layer under `src/meta_flow/lint/` or `src/meta_flow/governance/`.
- Use `bb watch` for iterative page work and `bb compile:check` or `bb release` when validating bundle-level changes.
