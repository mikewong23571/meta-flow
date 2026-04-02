(ns meta-flow.store.sqlite
  (:require [meta-flow.db :as db]
            [meta-flow.store.protocol :as store.protocol]
            [meta-flow.store.sqlite.run-data :as run-data]
            [meta-flow.store.sqlite.runs :as runs]
            [meta-flow.store.sqlite.tasks :as tasks]))

(defn find-task-row
  [connection task-id]
  (tasks/find-task-row connection task-id))

(defn next-event-seq
  [connection run-id]
  (run-data/next-event-seq connection run-id))

(defn transition-task-via-connection!
  [connection task-id transition now]
  (tasks/transition-task-via-connection! connection task-id transition now))

(defn transition-run-via-connection!
  [connection run-id transition now]
  (runs/transition-run-via-connection! connection run-id transition now))

(defn ingest-run-event-via-connection!
  [connection event-intent]
  (run-data/ingest-run-event-via-connection! connection event-intent))

(defrecord SQLiteStateStore [db-path]
  store.protocol/StateStore
  (upsert-collection-state! [_ collection-state]
    (tasks/upsert-collection-state! db-path collection-state))
  (find-collection-state [_ collection-id]
    (tasks/find-collection-state db-path collection-id))
  (enqueue-task! [_ task]
    (tasks/enqueue-task! db-path task))
  (find-task [_ task-id]
    (tasks/find-task db-path task-id))
  (find-task-by-work-key [_ work-key]
    (tasks/find-task-by-work-key db-path work-key))
  (create-run! [_ task run lease]
    (runs/create-run! db-path task run lease))
  (claim-task-for-run! [_ task run lease task-transition run-transition now]
    (runs/claim-task-for-run! db-path task run lease task-transition run-transition now))
  (recover-run-startup-failure! [_ task run now]
    (runs/recover-run-startup-failure! db-path task run now))
  (find-run [_ run-id]
    (runs/find-run db-path run-id))
  (find-latest-run-for-task [_ task-id]
    (runs/find-latest-run-for-task db-path task-id))
  (ingest-run-event! [_ event-intent]
    (run-data/ingest-run-event-with-retry! db-path event-intent))
  (list-run-events [_ run-id]
    (run-data/list-run-events db-path run-id))
  (list-run-events-after [_ run-id event-seq]
    (run-data/list-run-events-after db-path run-id event-seq))
  (attach-artifact! [_ run-id artifact]
    (run-data/attach-artifact! db-path run-id artifact))
  (find-artifact [_ artifact-id]
    (run-data/find-artifact db-path artifact-id))
  (record-assessment! [_ assessment]
    (run-data/record-assessment! db-path assessment))
  (find-assessment-by-key [_ run-id assessment-key]
    (run-data/find-assessment-by-key db-path run-id assessment-key))
  (record-disposition! [_ disposition]
    (run-data/record-disposition! db-path disposition))
  (find-disposition-by-key [_ run-id disposition-key]
    (run-data/find-disposition-by-key db-path run-id disposition-key))
  (transition-task! [_ task-id transition now]
    (tasks/transition-task! db-path task-id transition now))
  (transition-run! [_ run-id transition now]
    (runs/transition-run! db-path run-id transition now)))

(defn sqlite-state-store
  ([] (sqlite-state-store db/default-db-path))
  ([db-path]
   (->SQLiteStateStore db-path)))
