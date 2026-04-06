# Definitions Code Notes

## Scope

This directory owns the code that loads, indexes, validates, authors, generates, and serves workflow definitions.

Read it by responsibility:

- `source.clj`, `repository.clj`, and `protocol.clj` own raw definition loading and the `DefinitionRepository` boundary.
- `validation.clj` enforces schema, link, FSM, and runtime-profile constraints.
- `index.clj` builds versioned lookup indexes and reference checks.
- `loader.clj` is the narrow public entrypoint used by the rest of the app.
- `authoring.clj` plus `authoring/` own draft editing, kind-specific authoring rules, prepare steps, and workspace materialization.
- `generation/` owns description/inference-driven definition generation support.
- `workspace/files.clj` owns reusable workspace file-layout helpers.

The bundled definition data itself lives under `resources/meta_flow/defs/`.

## Change Rules

- Keep loading, validation, indexing, and repository access separate. Avoid collapsing these into one large "definitions utility" layer.
- New definition fields should be validated explicitly. If a field affects runtime behavior or scheduler decisions, update schema and link validation together.
- Prefer encoding workflow variation in versioned definitions, not in scheduler or runtime conditionals.
- Keep repository outputs deterministic and side-effect-light. Definition loading is a read boundary, not a place for workflow policy.
- When adding a new definition type or cross-reference rule, update both the code-level indexes/validation and the bundled definition data together.

## Validation

- For source/repository loading changes, check `test/meta_flow/defs/source_test.clj` and `test/meta_flow/defs_loader_test.clj`.
- For index or duplicate-version behavior, check `test/meta_flow/defs_index_test.clj`.
- For schema, FSM, resource-path, and reference validation changes, check `test/meta_flow/defs_validation_test.clj`.
- Definition-system changes should also pass `bb defs:validate`.
