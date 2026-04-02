# Phase 1 Review Findings

This document consolidates all issues found during two rounds of code review
and one round of hands-on testing of Phase 1 ("host skeleton + SQLite control
plane + mock happy path").

Issues are grouped by **resolution category** — each category represents a
distinct "when and why to fix" boundary. Within each category issues are
ordered by severity.

References to plan documents use short tags:
- **main-plan** = `docs/meta-flow-program-execplan.md`
- **p1-plan** = `docs/meta-flow-phase-1-execplan.md`

---

## Category A: Architecture boundary erosion — fix before Phase 2

These issues do not invalidate the Phase 1 happy path, but they erode
structural boundaries that the plan is trying to establish. The longer they
survive, the harder those boundaries become to restore cleanly.

### A1. Scheduler bypasses ProjectionReader / StateStore with raw SQL

**Severity:** high
**Plan ref:** p1-plan:21 ("调度器的 runnable/awaiting-validation/expired-lease
输入只能来自 ProjectionReader"), main-plan:90 ("projection v1 只允许是只读查询层").
**Evidence:** `src/meta_flow/scheduler.clj` contains 8 private functions
that open their own JDBC connections and run ad-hoc SQL:

| function | line | query |
|---|---|---|
| `latest-collection-state` | :38 | `SELECT state_edn FROM collection_state` |
| `all-non-final-runs` | :48 | `SELECT run_id FROM runs WHERE state <> …` |
| `find-artifact` | :55 | `SELECT artifact_edn FROM artifacts` |
| `latest-assessment` | :64 | `SELECT assessment_edn FROM assessments` |
| `latest-disposition` | :73 | `SELECT disposition_edn FROM dispositions` |
| `active-run-count` | :89 | `SELECT COUNT(*) FROM runs` |
| `run-count-for-task` | :96 | `SELECT COUNT(*) FROM runs WHERE task_id = ?` |
| `latest-run-id-for-task` | :103 | `SELECT run_id FROM runs ORDER BY attempt` |

**Impact:** If projection strategy changes (materialized view, cache, etc.),
every one of these scattered queries must be found and migrated. The phase
plan's intended layering is already eroding inside `scheduler.clj`, even
though the specific runnable / awaiting-validation / expired-lease scheduling
inputs still come from `ProjectionReader`.

**Fix:** Move these queries into `ProjectionReader` or `StateStore` protocol
methods as appropriate.

### A2. `transition-task!` CAS weaker than `transition-run!`

**Severity:** medium
**Plan ref:** p1-plan:21 ("关键状态推进必须走显式事务与 compare-and-set"),
main-plan:175 ("迁移都必须采用 compare-and-set 条件更新").
**Evidence:** `src/meta_flow/store/sqlite.clj:341-348` —
`update-task-transition!` uses `WHERE task_id = ? AND state = ?`.
Compare with `replace-run-row!` at :196-210 which checks
`state + lease_id + artifact_id + updated_at + run_edn`.
**Impact:** Two concurrent writers reading the same task can both succeed
their CAS if they share the same `from-state` — the second silently
overwrites the first's `task_edn` and `updated_at`.

**Fix:** Add `updated_at` (and optionally `task_edn`) to the WHERE clause
of the task UPDATE, matching the strength of `replace-run-row!`.

---

## Category B: Phase 2 prerequisites — must fix when retry / Codex work begins

These are correctly out of scope for Phase 1 happy path, but represent
the hardest prerequisite blockers for Phase 2 milestones.

### B1. Expired lease loaded but never consumed

**Severity:** high (for Phase 2)
**Plan ref:** main-plan:159 ("识别 lease 超时"), main-plan:181 ("回收超时 lease").
**Evidence:**
- `src/meta_flow/projection.clj:36-48` — `expired-lease-run-ids-query` works.
- `test/meta_flow/sqlite_store_test.clj:387` — test proves view returns results.
- `src/meta_flow/scheduler.clj:327-334` — `run-scheduler-step` loads snapshot
  including expired-lease-run-ids but has no loop to act on them.
- `src/meta_flow/runtime/protocol.clj` — `poll-run!` and `cancel-run!` are
  defined but never called by the scheduler.

**Impact:** A run whose lease expires will stay stuck forever. Fresh
scheduler cannot recover it, and the same gap also means there is still no
control loop exercising `poll-run!` at all.

**Fix:** Add an expired-lease recovery loop in `run-scheduler-step` between
the event-stream replay and the dispatch loop.

### B2. Rejected assessment does not drive FSM — run stays stuck

**Severity:** high (for Phase 2)
**Plan ref:** main-plan:157-161 (Milestone 2 requires rejected → retryable_failed
→ requeue).
**Evidence:**
- `src/meta_flow/scheduler.clj:320` — `assess-run!` only emits FSM events
  when `assessment/outcome` is `:assessment/accepted`. Rejected case falls
  through with no state transition.
- `resources/meta_flow/defs/task-fsms.edn` and `run-fsms.edn` — no transition
  for a rejected/retry event.
- `test/meta_flow/scheduler_happy_path_test.clj:167` — test confirms rejected
  run stays in `:run.state/awaiting-validation`.

**Impact:** Any task that fails validation has no exit path; it stays stuck
in `:task.state/awaiting-validation` / `:run.state/awaiting-validation`.
This is the single largest structural gap before retry / needs_review can be
built.

**Fix:** Add rejected/retry transitions to FSMs, emit rejection events in
`assess-run!`, and implement the disposition-driven task state change so the
rejected path lands in an explicit control-plane state such as
`retryable_failed` or `needs_review`, rather than remaining in validation.

### B3. `dispatch-run!` return value discarded — no durable execution handle

**Severity:** medium (for Phase 2)
**Plan ref:** main-plan:187 ("dispatch-run! 成功后必须持久化写出 execution handle").
**Evidence:** `src/meta_flow/scheduler.clj:279` — `dispatch-run!` returns a
map with `:runtime-run/workdir`, `:runtime-run/artifact-root`, etc., but
`create-run!` ignores the return value.
**Impact:** After a crash, a fresh scheduler has no persisted handle to poll
or cancel an in-flight Codex worker.

**Fix:** Persist the dispatch result (e.g., into `run_edn` or a sidecar file)
so `poll-run!` and `cancel-run!` can use it.

### B4. `create-run!` transaction fragmentation

**Severity:** medium (for Phase 2)
**Plan ref:** main-plan:175 ("所有状态推进必须包在显式事务里").
**Evidence:** `src/meta_flow/scheduler.clj:232-286` — `create-run!` performs:
1. `store.protocol/create-run!` (transaction 1: insert run + lease)
2. `store.protocol/transition-run!` (transaction 2: CAS run state)
3. `store.protocol/transition-task!` (transaction 3: CAS task state)
4. `runtime.protocol/prepare-run!` (filesystem)
5. `runtime.protocol/dispatch-run!` (filesystem + N event transactions)

If step 2 succeeds but step 3 fails, the run is leased but the task is still
queued. `recover-run-startup-failure!` (:207-230) tries to clean up, but that
cleanup is itself another separate transaction.

**Impact:** In a real adapter, prepare/dispatch can fail partway, leaving
orphaned leased runs.

**Fix:** Merge steps 1-3 into a single transaction, or make the recovery path
unconditionally idempotent.

---

## Category C: Event contract and naming — freeze before Codex integration

### C1. Event type namespace drift between layers

**Severity:** medium
**Evidence:**
- Run FSM definitions use `:run.event/worker-exited`, `:run.event/dispatched`
  etc. (`resources/meta_flow/defs/run-fsms.edn:13`).
- Mock runtime emits these correctly (`src/meta_flow/runtime/mock.clj:98-101`).
- But store tests use `:event/worker-heartbeat` and `:event/worker-exit`
  (`test/meta_flow/sqlite_store_test.clj:208,215`) — note the different
  namespace (`:event/` vs `:run.event/`) and verb form (`worker-exit` vs
  `worker-exited`).
- `inspect-run!` filters on `:event/worker-heartbeat`
  (`src/meta_flow/scheduler.clj:466`) — a namespace that no producer uses.

**Impact:** When the Codex adapter lands, whichever convention it follows will
silently mismatch the other. Heartbeat events will never be found by inspect.
FSM transitions will break if the adapter picks the wrong namespace.

**Fix:** Define a canonical event-type registry (map or enum) and have all
producers and consumers reference it. Freeze the registry before Codex work.

### C2. Mock runtime emits no heartbeat event

**Severity:** medium
**Plan ref:** p1-plan:144 ("mock runtime 只通过这个 API 写入 worker-start、
heartbeat、worker-exit、artifact-ready"), main-plan:179 (same list),
main-plan:252 ("run inspect 的期望结果必须至少包含…最后 heartbeat").
**Evidence:**
- `src/meta_flow/runtime/mock.clj:98-121` — emits dispatched, worker-started,
  worker-exited, artifact-ready. No heartbeat.
- `src/meta_flow/scheduler.clj:466` — `inspect-run!` filters for heartbeat
  and always gets `nil`.
- Confirmed by testing: `inspect run` returns `:run/last-heartbeat nil`.

**Impact:** `inspect run` output does not meet the plan's acceptance criteria.
More importantly, any code path that relies on heartbeat presence (e.g.,
future "no heartbeat → expired" logic) will have zero test coverage.

**Fix:** Add at least one heartbeat event in `dispatch-run!`, after
`:run.event/worker-started` and before `:run.event/worker-exited`.

---

## Category D: Hardening — fix before production or high-concurrency use

### D1. Assessment / disposition tables lack an explicit idempotency model

**Severity:** medium
**Plan ref:** main-plan:175 ("compare-and-set").
**Evidence:**
- `resources/meta_flow/sql/001_init.sql:104-120` — `assessments` and
  `dispositions` have only a primary key on `assessment_id`/`disposition_id`.
  No unique index on a semantic key such as `(run_id, validation_round)` or
  `(run_id, decision_round)`.
- `src/meta_flow/scheduler.clj:291-294` — `assess-run!` does
  `(or existing-assessment (insert new))` but the read and write are not
  in the same CAS transaction.

**Impact:** Duplicate scheduler invocations, recovery replays, or external
pollers can insert multiple assessments/dispositions per run.

**Fix:** Introduce an explicit validation / decision idempotency model.
Options include `validation_round` / `decision_round`, or a semantic
idempotency key, then enforce uniqueness on that key and use `INSERT ... ON
CONFLICT` (or equivalent CAS semantics). Avoid baking in `UNIQUE(run_id)` if
the long-term design expects multiple assessments or dispositions per run.

### D2. `edn/read-string` without reader restriction

**Severity:** low-medium
**Evidence:** `src/meta_flow/sql.clj:24` — `(edn/read-string value)`.
**Impact:** If external data (worker payload, user input) enters an EDN
column, tagged literals could invoke arbitrary reader functions.

**Fix:** Use `(edn/read-string {:readers {} :default tagged-literal} value)`.

### D3. Full event-stream replay every scheduler step

**Severity:** low-medium
**Plan ref:** (design issue, no explicit plan constraint)
**Evidence:**
- `src/meta_flow/scheduler.clj:148-156` — `apply-event-stream!` loads and
  reduces ALL events for a run.
- `src/meta_flow/scheduler.clj:335-338` — called for every non-final run.
- Same run can be replayed up to 3 times per step (non-final loop, awaiting-
  validation loop, inside create-run!).

**Impact:** O(events * runs) per scheduler step. Safe now due to FSM
idempotence, but will degrade as event counts grow.

**Fix:** Introduce a watermark or cursor per run to enable incremental replay.

---

## Category E: Plan-vs-implementation deltas — track, not necessarily fix in Phase 1

### E1. `demo happy-path` uses dedicated control flow, not `scheduler once`

**Severity:** low-medium
**Plan ref:** main-plan:236 ("运行一轮 scheduler once…如果 mock runtime 是同步
的，那么一轮调度后就应产生 artifact").
**Evidence:** `src/meta_flow/scheduler.clj:404-439` — `demo-happy-path!`
directly calls `create-run!` and `apply-event-stream!` / `assess-run!` in a
custom loop, never calling `run-scheduler-step`.
**Impact:** Demo proves the component functions work, but does not exercise
the public `scheduler once` entry point end-to-end.

### E2. CLI missing `enqueue` and `demo retry-path` commands

**Severity:** low
**Plan ref:** main-plan:183 ("CLI 至少要支持…enqueue…demo retry-path").
**Evidence:** `src/meta_flow/cli.clj:111-140` — only `init`, `defs validate`,
`scheduler once`, `demo happy-path`, and `inspect` are implemented.
**Impact:** `enqueue` is a convenience; `demo retry-path` depends on Category
B2 being resolved first. Neither blocks Phase 1 acceptance.

---

## Category F: Low priority / cosmetic

### F1. `mock/poll-run!` and `mock/cancel-run!` lack explanatory comments

**Severity:** low
**Evidence:** `src/meta_flow/runtime/mock.clj:126-129`.
**Fix:** One-line comment each explaining why they are stubs.

### F2. No connection pooling

**Severity:** low (performance)
**Evidence:** `src/meta_flow/sql.clj:69-84` — every `with-connection` /
`with-transaction` opens a fresh JDBC connection and applies 3 pragmas.
**Impact:** Acceptable for Phase 1 volumes. Will become the first bottleneck
if event ingest frequency rises.

---

## Summary table

| ID | Category | Severity | One-line summary |
|----|----------|----------|-----------------|
| A1 | A — boundary | high | Scheduler has 8 raw-SQL queries bypassing ProjectionReader/StateStore |
| A2 | A — boundary | medium | `transition-task!` CAS only checks state, not updated_at |
| B1 | B — phase-2 prereq | high | Expired lease IDs loaded but never acted on |
| B2 | B — phase-2 prereq | high | Rejected assessment doesn't trigger FSM transition |
| B3 | B — phase-2 prereq | medium | `dispatch-run!` return value not persisted |
| B4 | B — phase-2 prereq | medium | `create-run!` spans 3+ independent transactions |
| C1 | C — event contract | medium | Event type namespaces inconsistent across layers |
| C2 | C — event contract | medium | Mock runtime never emits heartbeat |
| D1 | D — hardening | medium | No uniqueness constraint on assessments/dispositions per run |
| D2 | D — hardening | low-med | `edn/read-string` without reader restriction |
| D3 | D — hardening | low-med | Full event replay every step, no watermark |
| E1 | E — plan delta | low-med | `demo happy-path` does not go through `scheduler once` |
| E2 | E — plan delta | low | CLI missing `enqueue` and `demo retry-path` |
| F1 | F — cosmetic | low | Mock stubs lack explanatory comments |
| F2 | F — cosmetic | low | No connection pooling |
