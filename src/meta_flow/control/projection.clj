(ns meta-flow.control.projection
  (:require [meta-flow.db :as db]
            [meta-flow.control.events :as events]
            [meta-flow.control.projection.snapshot :as snapshot]
            [meta-flow.sql :as sql]
            [meta-flow.store.sqlite.shared :as shared]))

(defprotocol ProjectionReader
  (load-scheduler-snapshot [reader now])
  (list-runnable-task-ids [reader now limit])
  (list-retryable-failed-task-ids [reader now limit])
  (list-awaiting-validation-run-ids [reader now limit])
  (list-expired-lease-run-ids [reader now limit])
  (list-heartbeat-timeout-run-ids [reader now limit])
  (list-active-run-ids [reader now limit])
  (count-active-runs [reader now])
  (count-active-runs-for-resource-policy [reader resource-policy-ref now]))

(def ^:private snapshot-list-limit 100)

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

(defn- retryable-failed-task-ids-query
  [connection limit]
  (mapv :task_id
        (sql/query-rows connection
                        (str "SELECT task_id FROM tasks "
                             "WHERE state = ':task.state/retryable-failed' "
                             "ORDER BY updated_at ASC, task_id ASC LIMIT ?")
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

(defn- heartbeat-timeout-run-rows-query
  [connection now]
  (sql/query-rows connection
                  (str "SELECT r.run_id, r.task_id, r.attempt, r.state, r.lease_id, r.artifact_id, "
                       "r.run_edn, r.created_at, r.updated_at, "
                       "MAX(CASE WHEN e.event_type IN (?, ?) THEN e.created_at END) AS last_progress_at "
                       "FROM runs r "
                       "JOIN leases l ON l.run_id = r.run_id "
                       "LEFT JOIN run_events e ON e.run_id = r.run_id "
                       "WHERE l.state = ':lease.state/active' "
                       "AND l.lease_expires_at > ? "
                       "AND r.state IN (':run.state/dispatched', ':run.state/running') "
                       "GROUP BY r.run_id, r.task_id, r.attempt, r.state, r.lease_id, r.artifact_id, "
                       "r.run_edn, r.created_at, r.updated_at "
                       "ORDER BY COALESCE(MAX(CASE WHEN e.event_type IN (?, ?) THEN e.created_at END), r.updated_at) ASC, "
                       "r.run_id ASC")
                  [(db/keyword-text events/run-worker-started)
                   (db/keyword-text events/run-worker-heartbeat)
                   now
                   (db/keyword-text events/run-worker-started)
                   (db/keyword-text events/run-worker-heartbeat)]))

(defn- heartbeat-timeout-seconds
  [run]
  (long (or (:run/heartbeat-timeout-seconds run) 0)))

(defn- heartbeat-observed-at
  [run last-progress-at]
  (or last-progress-at
      (when (contains? #{:run.state/dispatched
                         :run.state/running}
                       (:run/state run))
        (:run/updated-at run))))

(defn- timed-out-at?
  [observed-at timeout-seconds now]
  (and observed-at
       (pos? timeout-seconds)
       (not (.isAfter (.plusSeconds (java.time.Instant/parse observed-at)
                                    timeout-seconds)
                      (java.time.Instant/parse now)))))

(defn- heartbeat-timeout-run-ids-query
  [connection now limit]
  (->> (heartbeat-timeout-run-rows-query connection now)
       (keep (fn [row]
               (let [run (shared/run-row->entity row)
                     timeout-seconds (heartbeat-timeout-seconds run)
                     observed-at (heartbeat-observed-at run (:last_progress_at row))]
                 (when (timed-out-at? observed-at timeout-seconds now)
                   (:run/id run)))))
       (take limit)
       vec))

(defn- summarize-heartbeat-timeout-runs
  [connection now limit]
  (reduce (fn [{:keys [run-ids timeout-count] :as summary} row]
            (let [run (shared/run-row->entity row)
                  timeout-seconds (heartbeat-timeout-seconds run)
                  observed-at (heartbeat-observed-at run (:last_progress_at row))]
              (if (timed-out-at? observed-at timeout-seconds now)
                {:run-ids (cond-> run-ids
                            (< (count run-ids) limit) (conj (:run/id run)))
                 :timeout-count (inc timeout-count)}
                summary)))
          {:run-ids []
           :timeout-count 0}
          (heartbeat-timeout-run-rows-query connection now)))

(defrecord SQLiteProjectionReader [db-path]
  ProjectionReader
  (load-scheduler-snapshot [_ now]
    (sql/with-read-transaction db-path
      (fn [connection]
        (let [collections (snapshot/collection-states connection)
              active-cooldowns (snapshot/active-cooldowns collections now)
              runnable-task-ids (runnable-task-ids-query connection snapshot-list-limit)
              retryable-failed-task-ids (retryable-failed-task-ids-query connection snapshot-list-limit)
              awaiting-validation-run-ids (awaiting-validation-run-ids-query connection snapshot-list-limit)
              expired-lease-run-ids (expired-lease-run-ids-query connection now snapshot-list-limit)
              {:keys [run-ids timeout-count]} (summarize-heartbeat-timeout-runs connection now snapshot-list-limit)
              runnable-count (snapshot/runnable-task-count-query connection)
              retryable-failed-count (snapshot/retryable-failed-task-count-query connection)
              awaiting-validation-count (snapshot/awaiting-validation-run-count-query connection)
              expired-lease-count (snapshot/expired-lease-run-count-query connection now)]
          {:snapshot/now now
           :snapshot/collections collections
           :snapshot/dispatch-paused? (boolean (some #(true? (get-in % [:collection/dispatch :dispatch/paused?]))
                                                     collections))
           :snapshot/dispatch-cooldown-until (first active-cooldowns)
           :snapshot/dispatch-cooldown-active? (boolean (seq active-cooldowns))
           :snapshot/runnable-task-ids runnable-task-ids
           :snapshot/retryable-failed-task-ids retryable-failed-task-ids
           :snapshot/awaiting-validation-run-ids awaiting-validation-run-ids
           :snapshot/expired-lease-run-ids expired-lease-run-ids
           :snapshot/heartbeat-timeout-run-ids run-ids
           :snapshot/runnable-count runnable-count
           :snapshot/retryable-failed-count retryable-failed-count
           :snapshot/awaiting-validation-count awaiting-validation-count
           :snapshot/expired-lease-count expired-lease-count
           :snapshot/heartbeat-timeout-count timeout-count}))))
  (list-runnable-task-ids [_ _ limit]
    (sql/with-connection db-path
      (fn [connection]
        (runnable-task-ids-query connection limit))))
  (list-retryable-failed-task-ids [_ _ limit]
    (sql/with-connection db-path
      (fn [connection]
        (retryable-failed-task-ids-query connection limit))))
  (list-awaiting-validation-run-ids [_ _ limit]
    (sql/with-connection db-path
      (fn [connection]
        (awaiting-validation-run-ids-query connection limit))))
  (list-expired-lease-run-ids [_ now limit]
    (sql/with-connection db-path
      (fn [connection]
        (expired-lease-run-ids-query connection now limit))))
  (list-heartbeat-timeout-run-ids [_ now limit]
    (sql/with-connection db-path
      (fn [connection]
        (heartbeat-timeout-run-ids-query connection now limit))))
  (list-active-run-ids [_ _ limit]
    (sql/with-connection db-path
      (fn [connection]
        (active-run-ids-query connection limit))))
  (count-active-runs [_ _]
    (sql/with-connection db-path
      (fn [connection]
        (snapshot/active-run-count-query connection))))
  (count-active-runs-for-resource-policy [_ resource-policy-ref _]
    (sql/with-connection db-path
      (fn [connection]
        (snapshot/active-run-count-for-resource-policy-query connection resource-policy-ref)))))

(defn sqlite-projection-reader
  ([] (sqlite-projection-reader db/default-db-path))
  ([db-path]
   (->SQLiteProjectionReader db-path)))
