(ns meta-flow.store.sqlite.tasks
  (:require [meta-flow.db :as db]
            [meta-flow.sql :as sql]
            [meta-flow.store.sqlite.shared :as shared]))

(defn find-task-row
  [connection task-id]
  (sql/query-one connection
                 "SELECT task_id, state, task_edn, updated_at FROM tasks WHERE task_id = ?"
                 [task-id]))

(defn find-collection-state-row
  [connection collection-id]
  (sql/query-one connection
                 "SELECT collection_id, state_edn, created_at, updated_at FROM collection_state WHERE collection_id = ?"
                 [collection-id]))

(defn find-task-by-work-key-row
  [connection work-key]
  (sql/query-one connection
                 "SELECT task_id, state, task_edn, updated_at FROM tasks WHERE work_key = ?"
                 [work-key]))

(defn insert-task!
  [connection task]
  (let [task (shared/normalize-task task)]
    (sql/execute-update! connection
                         (str "INSERT INTO tasks "
                              "(task_id, work_key, task_type_id, task_type_version, task_fsm_id, task_fsm_version, "
                              "runtime_profile_id, runtime_profile_version, artifact_contract_id, artifact_contract_version, "
                              "validator_id, validator_version, resource_policy_id, resource_policy_version, state, task_edn, "
                              "created_at, updated_at) "
                              "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                         [(shared/require-key! task :task/id)
                          (shared/require-key! task :task/work-key)
                          (shared/ref-id task :task/task-type-ref)
                          (shared/ref-version task :task/task-type-ref)
                          (shared/ref-id task :task/task-fsm-ref)
                          (shared/ref-version task :task/task-fsm-ref)
                          (shared/ref-id task :task/runtime-profile-ref)
                          (shared/ref-version task :task/runtime-profile-ref)
                          (shared/ref-id task :task/artifact-contract-ref)
                          (shared/ref-version task :task/artifact-contract-ref)
                          (shared/ref-id task :task/validator-ref)
                          (shared/ref-version task :task/validator-ref)
                          (shared/ref-id task :task/resource-policy-ref)
                          (shared/ref-version task :task/resource-policy-ref)
                          (shared/require-key! task :task/state)
                          (sql/edn->text task)
                          (:task/created-at task)
                          (:task/updated-at task)])
    task))

(defn update-task-transition!
  [connection task-id from-state to-state now transition]
  (when-let [row (find-task-row connection task-id)]
    (let [stored-state (:state row)]
      (when (= stored-state (db/keyword-text from-state))
        (let [existing (shared/parse-edn-column row :task_edn)
              updated-task (shared/build-transitioned-entity existing :task :task/state to-state now transition)]
          (when (= 1 (sql/execute-update! connection
                                          (str "UPDATE tasks SET state = ?, task_edn = ?, updated_at = ? "
                                               "WHERE task_id = ? AND state = ? AND updated_at = ? AND task_edn = ?")
                                          [(:task/state updated-task)
                                           (sql/edn->text updated-task)
                                           (:task/updated-at updated-task)
                                           task-id
                                           stored-state
                                           (:updated_at row)
                                           (:task_edn row)]))
            updated-task))))))

(defn transition-task-via-connection!
  [connection task-id transition now]
  (update-task-transition! connection
                           task-id
                           (:transition/from transition)
                           (:transition/to transition)
                           now
                           transition))

(defn upsert-collection-state!
  [db-path collection-state]
  (sql/with-transaction db-path
    (fn [connection]
      (let [collection-id (shared/require-key! collection-state :collection/id)
            existing-row (find-collection-state-row connection collection-id)
            collection-state (shared/normalize-collection-state collection-state (:created_at existing-row))]
        (sql/execute-update! connection
                             (str "INSERT INTO collection_state "
                                  "(collection_id, dispatch_paused, resource_policy_id, resource_policy_version, state_edn, created_at, updated_at) "
                                  "VALUES (?, ?, ?, ?, ?, ?, ?) "
                                  "ON CONFLICT(collection_id) DO UPDATE SET "
                                  "dispatch_paused = excluded.dispatch_paused, "
                                  "resource_policy_id = excluded.resource_policy_id, "
                                  "resource_policy_version = excluded.resource_policy_version, "
                                  "state_edn = excluded.state_edn, "
                                  "updated_at = excluded.updated_at")
                             [collection-id
                              (boolean (get-in collection-state [:collection/dispatch :dispatch/paused?]))
                              (shared/ref-id collection-state :collection/resource-policy-ref)
                              (shared/ref-version collection-state :collection/resource-policy-ref)
                              (sql/edn->text collection-state)
                              (:collection/created-at collection-state)
                              (:collection/updated-at collection-state)])
        collection-state))))

(defn find-collection-state
  [db-path collection-id]
  (sql/with-connection db-path
    (fn [connection]
      (some-> (find-collection-state-row connection collection-id)
              shared/collection-state-row->entity))))

(defn enqueue-task!
  [db-path task]
  (sql/with-transaction db-path
    (fn [connection]
      (if-let [row (find-task-by-work-key-row connection (:task/work-key task))]
        (shared/parse-edn-column row :task_edn)
        (try
          (insert-task! connection task)
          (catch java.sql.SQLException throwable
            (if-let [existing-row (find-task-by-work-key-row connection (:task/work-key task))]
              (shared/parse-edn-column existing-row :task_edn)
              (throw throwable))))))))

(defn find-task
  [db-path task-id]
  (sql/with-connection db-path
    (fn [connection]
      (some-> (find-task-row connection task-id)
              (shared/parse-edn-column :task_edn)))))

(defn find-task-by-work-key
  [db-path work-key]
  (sql/with-connection db-path
    (fn [connection]
      (some-> (find-task-by-work-key-row connection work-key)
              (shared/parse-edn-column :task_edn)))))

(defn transition-task!
  [db-path task-id transition now]
  (sql/with-transaction db-path
    (fn [connection]
      (transition-task-via-connection! connection task-id transition now))))
