# Defs UI Notes

## Scope

This directory owns the frontend surface for browsing and authoring bundled definitions. It is the most stateful page family in the UI project.

Read it by slice:

- `../defs.cljs` is the page-level facade. It wires tabs, loading triggers, search state, and which list/detail/authoring section is active.
- `state.cljs` and `catalog/state.cljs` own fetched defs data, detail loading, authoring contract state, and cached authoring inputs.
- `list.cljs` and `presenter.cljs` own list-row rendering and small view-shaping helpers.
- `detail.cljs` plus `detail/` own detail-page composition and type-specific detail sections.
- `authoring/` owns the edit flow: `read.cljs`, `mutate.cljs`, `reset.cljs`, and `bootstrap.cljs` define authoring state transitions; `task_type/` and `runtime_profile/` hold kind-specific dialogs, draft shaping, and generation flows.

## Pressure Points

- `authoring/` is the primary pressure zone. Most defs mutations, draft resets, and generation flows converge there.
- `defs.cljs` is already a large facade. New behavior should usually land in `state.cljs`, `detail/`, or `authoring/` rather than further expanding that file.
- `task_type/shared.cljs` and `runtime_profile/shared.cljs` are large because they centralize form shaping. Treat them as schema/view-model helpers, not as another place to hide network or routing logic.

## Change Rules

- Keep read, mutate, reset, and bootstrap responsibilities separate. If a change touches multiple phases, make the phase boundary explicit instead of folding more logic into one function.
- Authoring dialogs should stay kind-specific. Shared code belongs in `task_type/shared.cljs` or `runtime_profile/shared.cljs` only when both flows truly need the same shaping rule.
- Keep server payload shape knowledge close to `state.cljs` and `authoring/` read-write helpers, not sprinkled through render functions.
- Prefer derived presentation helpers for labels and sections rather than duplicating definition-shape traversal in multiple components.
- When adding authoring features, update both task-type and runtime-profile entry points if the user flow or draft contract is intended to stay parallel.

## Validation

- Run `bb watch` from `ui/` for interactive page work.
- Run `bb governance` when changing page structure, shared UI placement, semantics, or CSS class conventions.
- Pay particular attention to `ui/test/meta_flow/lint/frontend_page_roles_test.clj` and `ui/test/meta_flow/lint/frontend_semantics_test.clj` when changing detail or dialog markup.
