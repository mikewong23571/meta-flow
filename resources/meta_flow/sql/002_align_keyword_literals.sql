UPDATE tasks
SET state = ':' || state
WHERE state GLOB 'task.state/*';

UPDATE runs
SET state = ':' || state
WHERE state GLOB 'run.state/*';

UPDATE leases
SET state = ':' || state
WHERE state GLOB 'lease.state/*';

UPDATE run_events
SET event_type = ':' || event_type
WHERE event_type GLOB 'event/*';

UPDATE assessments
SET status = ':' || status
WHERE status GLOB 'assessment/*';

UPDATE dispositions
SET disposition_type = ':' || disposition_type
WHERE disposition_type GLOB 'disposition/*';

DROP VIEW IF EXISTS runnable_tasks_v1;

DROP VIEW IF EXISTS awaiting_validation_runs_v1;

DROP INDEX IF EXISTS runs_one_phase1_nonterminal_run_per_task;

DROP INDEX IF EXISTS leases_one_active_lease_per_run;

CREATE UNIQUE INDEX IF NOT EXISTS runs_one_phase1_nonterminal_run_per_task
  ON runs(task_id)
  WHERE state IN (
    ':run.state/created',
    ':run.state/leased',
    ':run.state/dispatched',
    ':run.state/running',
    ':run.state/exited',
    ':run.state/awaiting-validation'
  );

CREATE UNIQUE INDEX IF NOT EXISTS leases_one_active_lease_per_run
  ON leases(run_id)
  WHERE state = ':lease.state/active';

CREATE VIEW IF NOT EXISTS runnable_tasks_v1 AS
SELECT task_id, work_key, task_type_id, task_type_version, state, created_at, updated_at
FROM tasks
WHERE state = ':task.state/queued';

CREATE VIEW IF NOT EXISTS awaiting_validation_runs_v1 AS
SELECT run_id, task_id, attempt, state, updated_at
FROM runs
WHERE state = ':run.state/awaiting-validation';

INSERT OR IGNORE INTO schema_migrations (migration_id, description)
VALUES ('002_align_keyword_literals', 'Align persisted keyword literals and rebuild dependent indexes/views');
