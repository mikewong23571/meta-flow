(ns meta-flow.projection
  (:require [meta-flow.db :as db]
            [meta-flow.sql :as sql]))

(defprotocol ProjectionReader
  (load-scheduler-snapshot [reader now])
  (list-runnable-task-ids [reader now limit])
  (list-awaiting-validation-run-ids [reader now limit])
  (list-expired-lease-run-ids [reader now limit])
  (list-active-run-ids [reader now limit])
  (count-active-runs [reader now]))

(def ^:private snapshot-list-limit 100)

(defn- collection-states
  [connection]
  (mapv #(-> % :state_edn sql/text->edn)
        (sql/query-rows connection
                        "SELECT state_edn FROM collection_state ORDER BY updated_at DESC, collection_id ASC"
                        [])))

(defn- runnable-task-ids-query
  [connection limit]
  (mapv :task_id
        (sql/query-rows connection
                        (str "SELECT task_id FROM runnable_tasks_v1 "
                             "ORDER BY created_at ASC, task_id ASC LIMIT ?")
                        [limit])))

(defn- awaiting-validation-run-ids-query
  [connection limit]
  (mapv :run_id
        (sql/query-rows connection
                        (str "SELECT run_id FROM awaiting_validation_runs_v1 "
                             "ORDER BY updated_at ASC, run_id ASC LIMIT ?")
                        [limit])))

(defn- expired-lease-run-ids-query
  [connection now limit]
  (mapv :run_id
        (sql/query-rows connection
                        (str "SELECT l.run_id AS run_id "
                             "FROM leases l "
                             "JOIN runs r ON r.run_id = l.run_id "
                             "WHERE l.state = ':lease.state/active' "
                             "AND l.lease_expires_at <= ? "
                             "AND r.state NOT IN (':run.state/awaiting-validation', ':run.state/finalized', ':run.state/retryable-failed') "
                             "ORDER BY l.lease_expires_at ASC, l.run_id ASC "
                             "LIMIT ?")
                        [now limit])))

(defn- active-run-ids-query
  [connection limit]
  (mapv :run_id
        (sql/query-rows connection
                        (str "SELECT run_id FROM runs "
                             "WHERE state NOT IN (':run.state/finalized', ':run.state/retryable-failed') "
                             "ORDER BY updated_at ASC, run_id ASC LIMIT ?")
                        [limit])))

(defn- query-count
  [connection sql-text params]
  (long (or (:item_count (sql/query-one connection sql-text params)) 0)))

(defn- runnable-task-count-query
  [connection]
  (query-count connection
               "SELECT COUNT(*) AS item_count FROM runnable_tasks_v1"
               []))

(defn- awaiting-validation-run-count-query
  [connection]
  (query-count connection
               "SELECT COUNT(*) AS item_count FROM awaiting_validation_runs_v1"
               []))

(defn- expired-lease-run-count-query
  [connection now]
  (query-count connection
               (str "SELECT COUNT(*) AS item_count "
                    "FROM leases l "
                    "JOIN runs r ON r.run_id = l.run_id "
                    "WHERE l.state = ':lease.state/active' "
                    "AND l.lease_expires_at <= ? "
                    "AND r.state NOT IN (':run.state/awaiting-validation', ':run.state/finalized', ':run.state/retryable-failed')")
               [now]))

(defn- active-run-count-query
  [connection]
  (query-count connection
               (str "SELECT COUNT(*) AS item_count FROM runs "
                    "WHERE state NOT IN (':run.state/finalized', ':run.state/retryable-failed')")
               []))

(defrecord SQLiteProjectionReader [db-path]
  ProjectionReader
  (load-scheduler-snapshot [_ now]
    (sql/with-read-transaction db-path
      (fn [connection]
        (let [collections (collection-states connection)
              runnable-task-ids (runnable-task-ids-query connection snapshot-list-limit)
              awaiting-validation-run-ids (awaiting-validation-run-ids-query connection snapshot-list-limit)
              expired-lease-run-ids (expired-lease-run-ids-query connection now snapshot-list-limit)
              runnable-count (runnable-task-count-query connection)
              awaiting-validation-count (awaiting-validation-run-count-query connection)
              expired-lease-count (expired-lease-run-count-query connection now)]
          {:snapshot/now now
           :snapshot/collections collections
           :snapshot/dispatch-paused? (boolean (some #(true? (get-in % [:collection/dispatch :dispatch/paused?]))
                                                     collections))
           :snapshot/runnable-task-ids runnable-task-ids
           :snapshot/awaiting-validation-run-ids awaiting-validation-run-ids
           :snapshot/expired-lease-run-ids expired-lease-run-ids
           :snapshot/runnable-count runnable-count
           :snapshot/awaiting-validation-count awaiting-validation-count
           :snapshot/expired-lease-count expired-lease-count}))))
  (list-runnable-task-ids [_ _ limit]
    (sql/with-connection db-path
      (fn [connection]
        (runnable-task-ids-query connection limit))))
  (list-awaiting-validation-run-ids [_ _ limit]
    (sql/with-connection db-path
      (fn [connection]
        (awaiting-validation-run-ids-query connection limit))))
  (list-expired-lease-run-ids [_ now limit]
    (sql/with-connection db-path
      (fn [connection]
        (expired-lease-run-ids-query connection now limit))))
  (list-active-run-ids [_ _ limit]
    (sql/with-connection db-path
      (fn [connection]
        (active-run-ids-query connection limit))))
  (count-active-runs [_ _]
    (sql/with-connection db-path
      (fn [connection]
        (active-run-count-query connection)))))

(defn sqlite-projection-reader
  ([] (sqlite-projection-reader db/default-db-path))
  ([db-path]
   (->SQLiteProjectionReader db-path)))
