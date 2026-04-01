PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS schema_migrations (
  migration_id TEXT PRIMARY KEY,
  description TEXT NOT NULL,
  applied_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS collection_state (
  collection_id TEXT PRIMARY KEY,
  dispatch_paused INTEGER NOT NULL DEFAULT 0,
  resource_policy_id TEXT NOT NULL,
  resource_policy_version INTEGER NOT NULL,
  state_edn TEXT NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS tasks (
  task_id TEXT PRIMARY KEY,
  work_key TEXT NOT NULL UNIQUE,
  task_type_id TEXT NOT NULL,
  task_type_version INTEGER NOT NULL,
  task_fsm_id TEXT NOT NULL,
  task_fsm_version INTEGER NOT NULL,
  runtime_profile_id TEXT NOT NULL,
  runtime_profile_version INTEGER NOT NULL,
  artifact_contract_id TEXT NOT NULL,
  artifact_contract_version INTEGER NOT NULL,
  validator_id TEXT NOT NULL,
  validator_version INTEGER NOT NULL,
  resource_policy_id TEXT NOT NULL,
  resource_policy_version INTEGER NOT NULL,
  state TEXT NOT NULL,
  task_edn TEXT NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS runs (
  run_id TEXT PRIMARY KEY,
  task_id TEXT NOT NULL REFERENCES tasks(task_id),
  attempt INTEGER NOT NULL,
  run_fsm_id TEXT NOT NULL,
  run_fsm_version INTEGER NOT NULL,
  runtime_profile_id TEXT NOT NULL,
  runtime_profile_version INTEGER NOT NULL,
  state TEXT NOT NULL,
  lease_id TEXT,
  artifact_id TEXT,
  run_edn TEXT NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS runs_one_phase1_nonterminal_run_per_task
  ON runs(task_id)
  WHERE state IN (
    'run.state/created',
    'run.state/leased',
    'run.state/dispatched',
    'run.state/running',
    'run.state/exited',
    'run.state/awaiting-validation'
  );

CREATE TABLE IF NOT EXISTS leases (
  lease_id TEXT PRIMARY KEY,
  run_id TEXT NOT NULL REFERENCES runs(run_id),
  state TEXT NOT NULL,
  lease_token TEXT NOT NULL UNIQUE,
  lease_expires_at TEXT NOT NULL,
  lease_edn TEXT NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS leases_one_active_lease_per_run
  ON leases(run_id)
  WHERE state = 'lease.state/active';

CREATE TABLE IF NOT EXISTS run_events (
  run_id TEXT NOT NULL REFERENCES runs(run_id),
  event_seq INTEGER NOT NULL,
  event_type TEXT NOT NULL,
  event_idempotency_key TEXT NOT NULL,
  event_payload_edn TEXT NOT NULL,
  created_at TEXT NOT NULL,
  PRIMARY KEY (run_id, event_seq),
  UNIQUE (run_id, event_idempotency_key)
);

CREATE TABLE IF NOT EXISTS artifacts (
  artifact_id TEXT PRIMARY KEY,
  run_id TEXT NOT NULL REFERENCES runs(run_id),
  task_id TEXT NOT NULL REFERENCES tasks(task_id),
  artifact_contract_id TEXT NOT NULL,
  artifact_contract_version INTEGER NOT NULL,
  root_path TEXT NOT NULL,
  artifact_edn TEXT NOT NULL,
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS assessments (
  assessment_id TEXT PRIMARY KEY,
  run_id TEXT NOT NULL REFERENCES runs(run_id),
  validator_id TEXT NOT NULL,
  validator_version INTEGER NOT NULL,
  status TEXT NOT NULL,
  assessment_edn TEXT NOT NULL,
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS dispositions (
  disposition_id TEXT PRIMARY KEY,
  run_id TEXT NOT NULL REFERENCES runs(run_id),
  disposition_type TEXT NOT NULL,
  disposition_edn TEXT NOT NULL,
  created_at TEXT NOT NULL
);

CREATE VIEW IF NOT EXISTS runnable_tasks_v1 AS
SELECT task_id, work_key, task_type_id, task_type_version, state, created_at, updated_at
FROM tasks
WHERE state = 'task.state/queued';

CREATE VIEW IF NOT EXISTS awaiting_validation_runs_v1 AS
SELECT run_id, task_id, attempt, state, updated_at
FROM runs
WHERE state = 'run.state/awaiting-validation';

INSERT OR IGNORE INTO schema_migrations (migration_id, description)
VALUES ('001_init', 'Initial meta-flow bootstrap schema');
