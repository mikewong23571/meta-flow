(ns meta-flow.store.sqlite.run-data
  (:require [meta-flow.store.sqlite.artifacts :as artifacts]
            [meta-flow.store.sqlite.assessments :as assessments]
            [meta-flow.store.sqlite.dispositions :as dispositions]
            [meta-flow.store.sqlite.run-events :as run-events]))

(defn find-run-event-row
  [connection run-id idempotency-key]
  (run-events/find-run-event-row connection run-id idempotency-key))

(defn next-event-seq
  [connection run-id]
  (run-events/next-event-seq connection run-id))

(defn update-run-summary-from-event!
  [connection event]
  (run-events/update-run-summary-from-event! connection event))

(defn load-existing-run-event
  [db-path run-id idempotency-key]
  (run-events/load-existing-run-event db-path run-id idempotency-key))

(defn retryable-event-ingest-exception?
  [throwable]
  (run-events/retryable-event-ingest-exception? throwable))

(defn ingest-run-event-via-connection!
  [connection event-intent]
  (run-events/ingest-run-event-via-connection! connection event-intent))

(defn ingest-run-event-once!
  [db-path event-intent]
  (run-events/ingest-run-event-once! db-path event-intent))

(defn ingest-run-event-with-retry!
  [db-path event-intent]
  (run-events/ingest-run-event-with-retry! db-path event-intent))

(defn list-run-events
  [db-path run-id]
  (run-events/list-run-events db-path run-id))

(defn list-run-events-after
  [db-path run-id event-seq]
  (run-events/list-run-events-after db-path run-id event-seq))

(defn attach-artifact!
  [db-path run-id artifact]
  (artifacts/attach-artifact! db-path run-id artifact))

(defn find-artifact
  [db-path artifact-id]
  (artifacts/find-artifact db-path artifact-id))

(defn record-assessment!
  [db-path assessment]
  (assessments/record-assessment! db-path assessment))

(defn find-assessment-by-key
  [db-path run-id assessment-key]
  (assessments/find-assessment-by-key db-path run-id assessment-key))

(defn record-disposition!
  [db-path disposition]
  (dispositions/record-disposition! db-path disposition))

(defn find-disposition-by-key
  [db-path run-id disposition-key]
  (dispositions/find-disposition-by-key db-path run-id disposition-key))
