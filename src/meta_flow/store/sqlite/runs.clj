(ns meta-flow.store.sqlite.runs
  (:require [meta-flow.db :as db]
            [meta-flow.sql :as sql]
            [meta-flow.store.sqlite.shared :as shared]
            [meta-flow.store.sqlite.tasks :as tasks]))

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

(defn insert-lease!
  [connection lease]
  (sql/execute-update! connection
                       (str "INSERT INTO leases "
                            "(lease_id, run_id, state, lease_token, lease_expires_at, lease_edn, created_at, updated_at) "
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                       [(shared/require-key! lease :lease/id)
                        (shared/require-key! lease :lease/run-id)
                        (shared/require-key! lease :lease/state)
                        (shared/require-key! lease :lease/token)
                        (shared/require-key! lease :lease/expires-at)
                        (sql/edn->text lease)
                        (:lease/created-at lease)
                        (:lease/updated-at lease)]))

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

(defn release-lease-via-connection!
  [connection lease-id now]
  (when-let [row (sql/query-one connection
                                "SELECT lease_edn FROM leases WHERE lease_id = ?"
                                [lease-id])]
    (let [released-lease (-> (sql/text->edn (:lease_edn row))
                             (assoc :lease/state :lease.state/released
                                    :lease/updated-at now)
                             sql/canonicalize-edn)]
      (sql/execute-update! connection
                           "UPDATE leases SET state = ?, lease_edn = ?, updated_at = ? WHERE lease_id = ?"
                           [(:lease/state released-lease)
                            (sql/edn->text released-lease)
                            (:lease/updated-at released-lease)
                            lease-id])
      released-lease)))

(defn create-run!
  [db-path task run lease]
  (let [task-id (shared/require-key! task :task/id)
        run-id (shared/require-key! run :run/id)
        lease-id (shared/require-key! lease :lease/id)
        _ (shared/require-matching-value! lease :lease/run-id run-id)
        _ (shared/require-matching-value! run :run/lease-id lease-id)
        lease (shared/normalize-lease (assoc lease :lease/run-id run-id))
        run (shared/normalize-run (assoc run
                                         :run/task-id task-id
                                         :run/lease-id lease-id)
                                  lease-id)]
    (sql/with-transaction db-path
      (fn [connection]
        (insert-run! connection run)
        (insert-lease! connection lease)
        {:run run
         :lease lease}))))

(defn claim-task-for-run!
  [db-path task run lease task-transition run-transition now]
  (let [task-id (shared/require-key! task :task/id)
        run-id (shared/require-key! run :run/id)
        lease-id (shared/require-key! lease :lease/id)
        _ (shared/require-matching-value! lease :lease/run-id run-id)]
    (sql/with-transaction db-path
      (fn [connection]
        (when-let [task-row (tasks/find-task-row connection task-id)]
          (when (= (:state task-row) (db/keyword-text (:transition/from task-transition)))
            (let [attempt (next-run-attempt connection task-id)
                  lease (shared/normalize-lease (assoc lease :lease/run-id run-id))
                  run (shared/normalize-run (assoc run
                                                   :run/task-id task-id
                                                   :run/attempt attempt
                                                   :run/lease-id lease-id)
                                            lease-id)
                  _ (insert-run! connection run)
                  _ (insert-lease! connection lease)
                  claimed-run (or (update-run-transition! connection
                                                          run-id
                                                          (:transition/from run-transition)
                                                          (:transition/to run-transition)
                                                          now
                                                          run-transition)
                                  (throw (ex-info "Failed to transition claimed run"
                                                  {:task-id task-id
                                                   :run-id run-id})))
                  claimed-task (or (tasks/update-task-transition! connection
                                                                  task-id
                                                                  (:transition/from task-transition)
                                                                  (:transition/to task-transition)
                                                                  now
                                                                  task-transition)
                                   (throw (ex-info "Failed to transition claimed task"
                                                   {:task-id task-id
                                                    :run-id run-id})))]
              {:task claimed-task
               :run claimed-run
               :lease lease})))))))

(defn recover-run-startup-failure!
  [db-path task run now]
  (sql/with-transaction db-path
    (fn [connection]
      (let [run-row (find-run-row connection (:run/id run))
            task-row (tasks/find-task-row connection (:task/id task))
            event-count (run-event-count connection (:run/id run))
            current-run (some-> run-row shared/run-row->entity)
            current-task (some-> task-row (shared/parse-edn-column :task_edn))]
        (if (and current-run
                 current-task
                 (zero? event-count)
                 (= :run.state/leased (:run/state current-run))
                 (= :task.state/leased (:task/state current-task))
                 (= (:run/lease-id current-run) (:run/lease-id run)))
          (let [recovered-run (or (transition-run-via-connection! connection
                                                                  (:run/id run)
                                                                  {:transition/from :run.state/leased
                                                                   :transition/to :run.state/finalized}
                                                                  now)
                                  current-run)
                _ (release-lease-via-connection! connection (:run/lease-id run) now)
                recovered-task (or (tasks/transition-task-via-connection! connection
                                                                          (:task/id task)
                                                                          {:transition/from :task.state/leased
                                                                           :transition/to :task.state/queued}
                                                                          now)
                                   current-task)]
            {:recovery/status :recovered
             :run recovered-run
             :task recovered-task
             :event-count event-count})
          {:recovery/status :skipped
           :run current-run
           :task current-task
           :event-count event-count})))))

(defn find-run
  [db-path run-id]
  (sql/with-connection db-path
    (fn [connection]
      (some-> (find-run-row connection run-id)
              shared/run-row->entity))))

(defn find-latest-run-for-task
  [db-path task-id]
  (sql/with-connection db-path
    (fn [connection]
      (some-> (find-latest-run-row-for-task connection task-id)
              shared/run-row->entity))))

(defn transition-run!
  [db-path run-id transition now]
  (sql/with-transaction db-path
    (fn [connection]
      (transition-run-via-connection! connection run-id transition now))))
