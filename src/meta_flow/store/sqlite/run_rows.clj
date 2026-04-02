(ns meta-flow.store.sqlite.run-rows
  (:require [meta-flow.db :as db]
            [meta-flow.sql :as sql]
            [meta-flow.store.sqlite.shared :as shared]))

(def ^:private max-run-row-update-attempts 5)

(defn find-run-row
  [connection run-id]
  (sql/query-one connection
                 (str "SELECT run_id, task_id, attempt, state, lease_id, artifact_id, run_edn, "
                      "created_at, updated_at FROM runs WHERE run_id = ?")
                 [run-id]))

(defn find-latest-run-row-for-task
  [connection task-id]
  (sql/query-one connection
                 (str "SELECT run_id, task_id, attempt, state, lease_id, artifact_id, run_edn, "
                      "created_at, updated_at FROM runs WHERE task_id = ? ORDER BY attempt DESC LIMIT 1")
                 [task-id]))

(defn run-event-count
  [connection run-id]
  (long (or (:item_count (sql/query-one connection
                                        "SELECT COUNT(*) AS item_count FROM run_events WHERE run_id = ?"
                                        [run-id]))
            0)))

(defn next-run-attempt
  [connection task-id]
  (let [row (sql/query-one connection
                           "SELECT COALESCE(MAX(attempt), 0) AS max_attempt FROM runs WHERE task_id = ?"
                           [task-id])]
    (inc (long (or (:max_attempt row) 0)))))

(defn replace-run-row!
  [connection row run]
  (sql/execute-update! connection
                       (str "UPDATE runs SET state = ?, lease_id = ?, artifact_id = ?, run_edn = ?, updated_at = ? "
                            "WHERE run_id = ? AND state = ? AND lease_id IS ? AND artifact_id IS ? "
                            "AND updated_at = ? AND run_edn = ?")
                       [(:run/state run)
                        (:run/lease-id run)
                        (:run/artifact-id run)
                        (sql/edn->text run)
                        (:run/updated-at run)
                        (:run_id row)
                        (:state row)
                        (:lease_id row)
                        (:artifact_id row)
                        (:updated_at row)
                        (:run_edn row)]))

(defn update-run-row!
  [connection run-id update-fn]
  (loop [attempt 1]
    (when-let [row (find-run-row connection run-id)]
      (when-let [updated-run (update-fn (shared/run-row->entity row) row)]
        (if (= 1 (replace-run-row! connection row updated-run))
          updated-run
          (if (< attempt max-run-row-update-attempts)
            (recur (inc attempt))
            (throw (ex-info "Failed to update run row after retries"
                            {:run-id run-id
                             :attempts attempt}))))))))

(defn require-run-task-id!
  [connection run-id]
  (or (:task_id (find-run-row connection run-id))
      (throw (ex-info (str "Unknown run " run-id)
                      {:run-id run-id}))))

(defn insert-run!
  [connection run]
  (sql/execute-update! connection
                       (str "INSERT INTO runs "
                            "(run_id, task_id, attempt, run_fsm_id, run_fsm_version, runtime_profile_id, runtime_profile_version, "
                            "state, lease_id, artifact_id, run_edn, created_at, updated_at) "
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                       [(shared/require-key! run :run/id)
                        (shared/require-key! run :run/task-id)
                        (shared/require-key! run :run/attempt)
                        (shared/ref-id run :run/run-fsm-ref)
                        (shared/ref-version run :run/run-fsm-ref)
                        (shared/ref-id run :run/runtime-profile-ref)
                        (shared/ref-version run :run/runtime-profile-ref)
                        (shared/require-key! run :run/state)
                        (:run/lease-id run)
                        (:run/artifact-id run)
                        (sql/edn->text run)
                        (:run/created-at run)
                        (:run/updated-at run)]))

(defn update-run-artifact!
  [connection run-id artifact-id]
  (update-run-row! connection run-id
                   (fn [run _]
                     (-> run
                         (assoc :run/artifact-id artifact-id)
                         (assoc :run/updated-at (sql/utc-now))))))

(defn update-run-transition!
  [connection run-id from-state to-state now transition]
  (update-run-row! connection run-id
                   (fn [existing row]
                     (when (= (:state row) (db/keyword-text from-state))
                       (shared/build-transitioned-entity existing :run :run/state to-state now transition)))))

(defn transition-run-via-connection!
  [connection run-id transition now]
  (update-run-transition! connection
                          run-id
                          (:transition/from transition)
                          (:transition/to transition)
                          now
                          transition))
