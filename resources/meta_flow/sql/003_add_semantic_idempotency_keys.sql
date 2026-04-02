ALTER TABLE assessments
ADD COLUMN assessment_key TEXT;

WITH ranked_assessments AS (
  SELECT assessment_id,
         CASE
           WHEN ROW_NUMBER() OVER (PARTITION BY run_id ORDER BY created_at DESC, assessment_id DESC) = 1
             THEN 'validation/current'
           ELSE 'legacy:' || assessment_id
         END AS backfill_key
  FROM assessments
  WHERE assessment_key IS NULL
)
UPDATE assessments
SET assessment_key = (SELECT backfill_key
                      FROM ranked_assessments
                      WHERE ranked_assessments.assessment_id = assessments.assessment_id)
WHERE assessment_key IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS assessments_run_id_assessment_key_unique
  ON assessments(run_id, assessment_key);

ALTER TABLE dispositions
ADD COLUMN disposition_key TEXT;

WITH ranked_dispositions AS (
  SELECT disposition_id,
         CASE
           WHEN ROW_NUMBER() OVER (PARTITION BY run_id ORDER BY created_at DESC, disposition_id DESC) = 1
             THEN 'decision/current'
           ELSE 'legacy:' || disposition_id
         END AS backfill_key
  FROM dispositions
  WHERE disposition_key IS NULL
)
UPDATE dispositions
SET disposition_key = (SELECT backfill_key
                       FROM ranked_dispositions
                       WHERE ranked_dispositions.disposition_id = dispositions.disposition_id)
WHERE disposition_key IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS dispositions_run_id_disposition_key_unique
  ON dispositions(run_id, disposition_key);

INSERT OR IGNORE INTO schema_migrations (migration_id, description)
VALUES ('003_add_semantic_idempotency_keys', 'Add semantic idempotency keys for assessments and dispositions');
