# Frontend Governance Notes

## Scope

This directory owns the UI governance gates that enforce frontend structure, semantics, style, and build health.

Read it by rule family:

- `architecture.clj` checks page and UI layering boundaries.
- `page_roles.clj` and `semantics.clj` check page identity, headings, landmarks, and markup semantics.
- `shared_ui.clj` and `shared_ui_support.clj` check shared-component placement and facade usage.
- `style.clj` checks token usage, raw style literals, file length, and directory width for CSS.
- `build.clj` checks bootstrap/build readiness and compile behavior.

The entrypoint that composes these gates is `../frontend.clj`.

## Change Rules

- Keep each namespace focused on one governance concern. If a new rule needs helpers, prefer support functions over broadening another rule family.
- Governance messages are product-facing guidance, not internal trivia. Findings should explain the architectural or semantic problem clearly enough that a frontend author can act on them.
- Avoid encoding page-specific exceptions unless the exception is a deliberate frontend convention. One-off allowances usually mean the underlying rule is placed too low or named too narrowly.
- Preserve deterministic file-system scanning and stable finding shapes so tests stay cheap and failures stay comparable across runs.
- When adding a new gate, wire it through `meta_flow/lint/check/frontend.clj` and add focused tests under `ui/test/meta_flow/lint/`.

## Validation

- Run `bb governance` from `ui/` for the composed gate report.
- Run `bb test --focus meta-flow.lint` or the full `bb test` suite after changing rule logic.
- If you touch build bootstrap behavior, also run `bb compile:check`.
