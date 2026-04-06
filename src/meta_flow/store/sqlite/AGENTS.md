# SQLite Store Notes

## Scope

This directory owns the SQLite-backed `StateStore` implementation and its persistence invariants.

Read it by persistence slice:

- `tasks.clj`, `runs.clj`, and `run_data.clj` are the main facade namespaces behind `src/meta_flow/store/sqlite.clj`.
- `run/` owns run row, lifecycle, and event persistence details.
- `lease/` owns active-lease persistence.
- `artifact/` owns artifact, assessment, and disposition persistence.
- `shared.clj` holds shared SQL/row helpers.

## Change Rules

- Preserve transaction boundaries and CAS-style row transitions. Do not split atomic write sequences across call sites unless the design is intentionally changing.
- Keep idempotency semantics explicit: work keys, event idempotency keys, assessment keys, and disposition keys should continue to collapse duplicates predictably.
- Treat structured columns and persisted row images as authoritative read boundaries. If a write path changes stored shape, update all read/summary rebuild paths consistently.
- Keep monotonic event sequencing and timestamp semantics intact, especially under retry or concurrent-ingest paths.
- When schema or persisted semantics change, update the SQL migrations and DB-backed tests together.

## Validation

- For lifecycle and transition changes, check `test/meta_flow/store/sqlite_lifecycle_test.clj`.
- For event ingestion and summary rebuild changes, check `test/meta_flow/store/sqlite_run_events_test.clj`, `test/meta_flow/store/sqlite/run/events_unit_test.clj`, and `test/meta_flow/store/sqlite_run_data_test.clj`.
- For record shape, idempotency, and projection behavior, check `test/meta_flow/store/sqlite_records_test.clj`, `test/meta_flow/store/sqlite_runs_test.clj`, and `test/meta_flow/store/sqlite/`.
