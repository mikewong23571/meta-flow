(ns meta-flow.store.sqlite
  (:require [clojure.string :as str]
            [meta-flow.db :as db]
            [meta-flow.sql :as sql]
            [meta-flow.store.protocol :as store.protocol]))

(defn- require-key!
  [entity key-name]
  (or (get entity key-name)
      (throw (ex-info (str "Missing required entity key " key-name)
                      {:entity entity
                       :key key-name}))))

(defn- require-definition-ref!
  [entity ref-key]
  (or (get entity ref-key)
      (throw (ex-info (str "Missing required definition ref " ref-key)
                      {:entity entity
                       :ref-key ref-key}))))

(defn- require-matching-value!
  [entity key-name expected]
  (when-let [actual (get entity key-name)]
    (when (not= actual expected)
      (throw (ex-info (str "Mismatched entity key " key-name)
                      {:entity entity
                       :key key-name
                       :expected expected
                       :actual actual}))))
  expected)

(defn- normalize-task
  [task]
  (let [created-at (or (:task/created-at task) (sql/utc-now))
        updated-at (or (:task/updated-at task) created-at)]
    (sql/canonicalize-edn
     (assoc task
            :task/created-at created-at
            :task/updated-at updated-at))))

(defn- normalize-run
  [run lease-id]
  (let [created-at (or (:run/created-at run) (sql/utc-now))
        updated-at (or (:run/updated-at run) created-at)]
    (sql/canonicalize-edn
     (assoc run
            :run/created-at created-at
            :run/updated-at updated-at
            :run/lease-id (or (:run/lease-id run) lease-id)))))

(defn- normalize-lease
  [lease]
  (let [created-at (or (:lease/created-at lease) (sql/utc-now))
        updated-at (or (:lease/updated-at lease) created-at)]
    (sql/canonicalize-edn
     (assoc lease
            :lease/created-at created-at
            :lease/updated-at updated-at))))

(defn- normalize-collection-state
  ([collection-state]
   (normalize-collection-state collection-state nil))
  ([collection-state existing-created-at]
   (let [created-at (or existing-created-at
                        (:collection/created-at collection-state)
                        (sql/utc-now))
         updated-at (or (:collection/updated-at collection-state) created-at)]
     (sql/canonicalize-edn
      (assoc collection-state
             :collection/created-at created-at
             :collection/updated-at updated-at)))))

(defn- normalize-artifact
  [artifact]
  (let [created-at (or (:artifact/created-at artifact) (sql/utc-now))]
    (sql/canonicalize-edn
     (assoc artifact :artifact/created-at created-at))))

(defn- normalize-assessment
  [assessment]
  (let [checked-at (or (:assessment/checked-at assessment)
                       (:assessment/created-at assessment)
                       (sql/utc-now))]
    (sql/canonicalize-edn
     (assoc assessment :assessment/checked-at checked-at))))

(defn- normalize-disposition
  [disposition]
  (let [decided-at (or (:disposition/decided-at disposition)
                       (:disposition/created-at disposition)
                       (sql/utc-now))]
    (sql/canonicalize-edn
     (assoc disposition :disposition/decided-at decided-at))))

(defn- normalize-event
  [event seq-value]
  (let [emitted-at (or (:event/emitted-at event) (sql/utc-now))]
    (sql/canonicalize-edn
     (assoc event
            :event/seq seq-value
            :event/emitted-at emitted-at))))

(def ^:private required-event-intent-keys
  [:event/run-id
   :event/type
   :event/payload
   :event/caused-by
   :event/idempotency-key])

(defn- require-event-intent-key!
  [event-intent key-name]
  (let [value (get event-intent key-name ::missing)]
    (when (or (= ::missing value) (nil? value))
      (throw (ex-info (str "Missing required event key " key-name)
                      {:event-intent event-intent
                       :key key-name})))))

(defn- validate-event-intent!
  [event-intent]
  (doseq [key-name required-event-intent-keys]
    (require-event-intent-key! event-intent key-name))
  event-intent)

(defn- ref-id
  [entity ref-key]
  (-> entity
      (require-definition-ref! ref-key)
      :definition/id
      db/keyword-text))

(defn- ref-version
  [entity ref-key]
  (-> entity
      (require-definition-ref! ref-key)
      :definition/version))

(defn- parse-edn-column
  [row column]
  (some-> row column sql/text->edn))

(defn- find-task-row
  [connection task-id]
  (sql/query-one connection
                 "SELECT task_id, state, task_edn FROM tasks WHERE task_id = ?"
                 [task-id]))

(defn- find-collection-state-row
  [connection collection-id]
  (sql/query-one connection
                 "SELECT collection_id, state_edn, created_at, updated_at FROM collection_state WHERE collection_id = ?"
                 [collection-id]))

(defn- find-task-by-work-key-row
  [connection work-key]
  (sql/query-one connection
                 "SELECT task_id, state, task_edn FROM tasks WHERE work_key = ?"
                 [work-key]))

(defn- find-run-row
  [connection run-id]
  (sql/query-one connection
                 (str "SELECT run_id, task_id, attempt, state, lease_id, artifact_id, run_edn, "
                      "created_at, updated_at FROM runs WHERE run_id = ?")
                 [run-id]))

(defn- find-run-event-row
  [connection run-id idempotency-key]
  (sql/query-one connection
                 "SELECT run_id, event_seq, event_payload_edn FROM run_events WHERE run_id = ? AND event_idempotency_key = ?"
                 [run-id idempotency-key]))

(defn- next-event-seq
  [connection run-id]
  (let [row (sql/query-one connection
                           "SELECT COALESCE(MAX(event_seq), 0) AS max_seq FROM run_events WHERE run_id = ?"
                           [run-id])]
    (inc (long (or (:max_seq row) 0)))))

(def ^:private max-run-row-update-attempts 5)

(def ^:private max-event-ingest-attempts 5)

(defn- run-row->entity
  [row]
  (cond-> (parse-edn-column row :run_edn)
    (:task_id row) (assoc :run/task-id (:task_id row))
    (contains? row :attempt) (assoc :run/attempt (:attempt row))
    (:state row) (assoc :run/state (sql/text->edn (:state row)))
    true (assoc :run/lease-id (:lease_id row))
    true (assoc :run/artifact-id (:artifact_id row))
    (:created_at row) (assoc :run/created-at (:created_at row))
    (:updated_at row) (assoc :run/updated-at (:updated_at row))))

(defn- replace-run-row!
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

(defn- update-run-row!
  [connection run-id update-fn]
  (loop [attempt 1]
    (when-let [row (find-run-row connection run-id)]
      (when-let [updated-run (update-fn (run-row->entity row) row)]
        (if (= 1 (replace-run-row! connection row updated-run))
          updated-run
          (if (< attempt max-run-row-update-attempts)
            (recur (inc attempt))
            (throw (ex-info "Failed to update run row after retries"
                            {:run-id run-id
                             :attempts attempt}))))))))

(defn- update-run-summary-from-event!
  [connection event]
  (let [run-id (:event/run-id event)
        emitted-at (:event/emitted-at event)
        artifact-id (or (get-in event [:event/payload :artifact/id])
                        (get-in event [:event/payload :artifact-id])
                        (:event/artifact-id event))]
    (update-run-row! connection run-id
                     (fn [run _]
                       (cond-> run
                         emitted-at (assoc :run/updated-at
                                           (let [updated-at (:run/updated-at run)]
                                             (if (and updated-at
                                                      (not (.isAfter (java.time.Instant/parse emitted-at)
                                                                     (java.time.Instant/parse updated-at))))
                                               updated-at
                                               emitted-at)))
                         artifact-id (assoc :run/artifact-id artifact-id))))))

(defn- require-run-task-id!
  [connection run-id]
  (or (:task_id (find-run-row connection run-id))
      (throw (ex-info (str "Unknown run " run-id)
                      {:run-id run-id}))))

(defn- insert-task!
  [connection task]
  (let [task (normalize-task task)]
    (sql/execute-update! connection
                         (str "INSERT INTO tasks "
                              "(task_id, work_key, task_type_id, task_type_version, task_fsm_id, task_fsm_version, "
                              "runtime_profile_id, runtime_profile_version, artifact_contract_id, artifact_contract_version, "
                              "validator_id, validator_version, resource_policy_id, resource_policy_version, state, task_edn, "
                              "created_at, updated_at) "
                              "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                         [(require-key! task :task/id)
                          (require-key! task :task/work-key)
                          (ref-id task :task/task-type-ref)
                          (ref-version task :task/task-type-ref)
                          (ref-id task :task/task-fsm-ref)
                          (ref-version task :task/task-fsm-ref)
                          (ref-id task :task/runtime-profile-ref)
                          (ref-version task :task/runtime-profile-ref)
                          (ref-id task :task/artifact-contract-ref)
                          (ref-version task :task/artifact-contract-ref)
                          (ref-id task :task/validator-ref)
                          (ref-version task :task/validator-ref)
                          (ref-id task :task/resource-policy-ref)
                          (ref-version task :task/resource-policy-ref)
                          (require-key! task :task/state)
                          (sql/edn->text task)
                          (:task/created-at task)
                          (:task/updated-at task)])
    task))

(defn- insert-run!
  [connection run]
  (sql/execute-update! connection
                       (str "INSERT INTO runs "
                            "(run_id, task_id, attempt, run_fsm_id, run_fsm_version, runtime_profile_id, runtime_profile_version, "
                            "state, lease_id, artifact_id, run_edn, created_at, updated_at) "
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                       [(require-key! run :run/id)
                        (require-key! run :run/task-id)
                        (require-key! run :run/attempt)
                        (ref-id run :run/run-fsm-ref)
                        (ref-version run :run/run-fsm-ref)
                        (ref-id run :run/runtime-profile-ref)
                        (ref-version run :run/runtime-profile-ref)
                        (require-key! run :run/state)
                        (:run/lease-id run)
                        (:run/artifact-id run)
                        (sql/edn->text run)
                        (:run/created-at run)
                        (:run/updated-at run)]))

(defn- insert-lease!
  [connection lease]
  (sql/execute-update! connection
                       (str "INSERT INTO leases "
                            "(lease_id, run_id, state, lease_token, lease_expires_at, lease_edn, created_at, updated_at) "
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                       [(require-key! lease :lease/id)
                        (require-key! lease :lease/run-id)
                        (require-key! lease :lease/state)
                        (require-key! lease :lease/token)
                        (require-key! lease :lease/expires-at)
                        (sql/edn->text lease)
                        (:lease/created-at lease)
                        (:lease/updated-at lease)]))

(defn- update-run-artifact!
  [connection run-id artifact-id]
  (update-run-row! connection run-id
                   (fn [run _]
                     (-> run
                         (assoc :run/artifact-id artifact-id)
                         (assoc :run/updated-at (sql/utc-now))))))

(defn- build-transitioned-entity
  [existing entity-key state-key to-state now transition]
  (sql/canonicalize-edn
   (or (:entity transition)
       (get transition entity-key)
       (cond-> existing
         true (assoc state-key to-state)
         now (assoc (keyword (namespace state-key) "updated-at") now)
         (:changes transition) (merge (:changes transition))))))

(defn- update-task-transition!
  [connection task-id from-state to-state now transition]
  (when-let [row (find-task-row connection task-id)]
    (let [stored-state (:state row)]
      (when (= stored-state (db/keyword-text from-state))
        (let [existing (parse-edn-column row :task_edn)
              updated-task (build-transitioned-entity existing :task :task/state to-state now transition)]
          (when (= 1 (sql/execute-update! connection
                                          (str "UPDATE tasks SET state = ?, task_edn = ?, updated_at = ? "
                                               "WHERE task_id = ? AND state = ?")
                                          [(:task/state updated-task)
                                           (sql/edn->text updated-task)
                                           (:task/updated-at updated-task)
                                           task-id
                                           stored-state]))
            updated-task))))))

(defn- update-run-transition!
  [connection run-id from-state to-state now transition]
  (update-run-row! connection run-id
                   (fn [existing row]
                     (when (= (:state row) (db/keyword-text from-state))
                       (build-transitioned-entity existing :run :run/state to-state now transition)))))

(defn- load-existing-run-event
  [db-path run-id idempotency-key]
  (sql/with-connection db-path
    (fn [connection]
      (some-> (find-run-event-row connection run-id idempotency-key)
              (parse-edn-column :event_payload_edn)))))

(defn- retryable-event-ingest-exception?
  [throwable]
  (let [message (some-> throwable .getMessage str/lower-case)]
    (boolean (or (and message (str/includes? message "database is locked"))
                 (and message (str/includes? message "busy"))
                 (and message (str/includes? message "run_events.run_id, run_events.event_seq"))))))

(defn- ingest-run-event-once!
  [db-path event-intent]
  (sql/with-transaction db-path
    (fn [connection]
      (if-let [existing-row (find-run-event-row connection
                                                (:event/run-id event-intent)
                                                (:event/idempotency-key event-intent))]
        (parse-edn-column existing-row :event_payload_edn)
        (let [event (normalize-event event-intent (next-event-seq connection (:event/run-id event-intent)))]
          (sql/execute-update! connection
                               (str "INSERT INTO run_events "
                                    "(run_id, event_seq, event_type, event_idempotency_key, event_payload_edn, created_at) "
                                    "VALUES (?, ?, ?, ?, ?, ?)")
                               [(:event/run-id event)
                                (:event/seq event)
                                (:event/type event)
                                (:event/idempotency-key event)
                                (sql/edn->text event)
                                (:event/emitted-at event)])
          (update-run-summary-from-event! connection event)
          event)))))

(defn- ingest-run-event-with-retry!
  [db-path event-intent]
  (loop [attempt 1]
    (let [result (try
                   {:status :ok
                    :value (ingest-run-event-once! db-path event-intent)}
                   (catch java.sql.SQLException throwable
                     {:status :error
                      :throwable throwable}))]
      (if (= :ok (:status result))
        (:value result)
        (let [throwable (:throwable result)
              existing-event (load-existing-run-event db-path
                                                      (:event/run-id event-intent)
                                                      (:event/idempotency-key event-intent))]
          (cond
            existing-event existing-event
            (and (< attempt max-event-ingest-attempts)
                 (retryable-event-ingest-exception? throwable))
            (recur (inc attempt))
            :else
            (throw throwable)))))))

(defrecord SQLiteStateStore [db-path]
  store.protocol/StateStore
  (upsert-collection-state! [_ collection-state]
    (sql/with-transaction db-path
      (fn [connection]
        (let [collection-id (require-key! collection-state :collection/id)
              existing-row (find-collection-state-row connection collection-id)
              collection-state (normalize-collection-state collection-state (:created_at existing-row))]
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
                                (ref-id collection-state :collection/resource-policy-ref)
                                (ref-version collection-state :collection/resource-policy-ref)
                                (sql/edn->text collection-state)
                                (:collection/created-at collection-state)
                                (:collection/updated-at collection-state)])
          collection-state))))
  (enqueue-task! [_ task]
    (sql/with-transaction db-path
      (fn [connection]
        (if-let [row (find-task-by-work-key-row connection (:task/work-key task))]
          (parse-edn-column row :task_edn)
          (try
            (insert-task! connection task)
            (catch java.sql.SQLException _
              (if-let [existing-row (find-task-by-work-key-row connection (:task/work-key task))]
                (parse-edn-column existing-row :task_edn)
                (throw _))))))))
  (find-task [_ task-id]
    (sql/with-connection db-path
      (fn [connection]
        (some-> (find-task-row connection task-id)
                (parse-edn-column :task_edn)))))
  (find-task-by-work-key [_ work-key]
    (sql/with-connection db-path
      (fn [connection]
        (some-> (find-task-by-work-key-row connection work-key)
                (parse-edn-column :task_edn)))))
  (create-run! [_ task run lease]
    (let [task-id (require-key! task :task/id)
          run-id (require-key! run :run/id)
          lease-id (require-key! lease :lease/id)
          _ (require-matching-value! lease :lease/run-id run-id)
          _ (require-matching-value! run :run/lease-id lease-id)
          lease (normalize-lease (assoc lease :lease/run-id run-id))
          run (normalize-run (assoc run
                                    :run/task-id task-id
                                    :run/lease-id lease-id)
                             lease-id)]
      (sql/with-transaction db-path
        (fn [connection]
          (insert-run! connection run)
          (insert-lease! connection lease)
          {:run run
           :lease lease}))))
  (find-run [_ run-id]
    (sql/with-connection db-path
      (fn [connection]
        (some-> (find-run-row connection run-id)
                run-row->entity))))
  (ingest-run-event! [_ event-intent]
    (when (contains? event-intent :event/seq)
      (throw (ex-info "Event producers must not supply :event/seq"
                      {:event-intent event-intent})))
    (validate-event-intent! event-intent)
    (ingest-run-event-with-retry! db-path event-intent))
  (list-run-events [_ run-id]
    (sql/with-connection db-path
      (fn [connection]
        (mapv #(parse-edn-column % :event_payload_edn)
              (sql/query-rows connection
                              "SELECT event_payload_edn FROM run_events WHERE run_id = ? ORDER BY event_seq ASC"
                              [run-id])))))
  (attach-artifact! [_ run-id artifact]
    (let [_ (require-matching-value! artifact :artifact/run-id run-id)
          artifact (normalize-artifact (assoc artifact :artifact/run-id run-id))
          root-path (or (:artifact/root-path artifact) (:artifact/location artifact))]
      (sql/with-transaction db-path
        (fn [connection]
          (let [task-id (require-run-task-id! connection run-id)
                _ (require-matching-value! artifact :artifact/task-id task-id)]
            (sql/execute-update! connection
                                 (str "INSERT INTO artifacts "
                                      "(artifact_id, run_id, task_id, artifact_contract_id, artifact_contract_version, root_path, artifact_edn, created_at) "
                                      "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                                 [(require-key! artifact :artifact/id)
                                  (require-key! artifact :artifact/run-id)
                                  (require-key! artifact :artifact/task-id)
                                  (ref-id artifact :artifact/contract-ref)
                                  (ref-version artifact :artifact/contract-ref)
                                  root-path
                                  (sql/edn->text (assoc artifact :artifact/root-path root-path))
                                  (:artifact/created-at artifact)])
            (update-run-artifact! connection run-id (:artifact/id artifact))
            (assoc artifact :artifact/root-path root-path))))))
  (record-assessment! [_ assessment]
    (let [assessment (normalize-assessment assessment)]
      (sql/with-transaction db-path
        (fn [connection]
          (sql/execute-update! connection
                               (str "INSERT INTO assessments "
                                    "(assessment_id, run_id, validator_id, validator_version, status, assessment_edn, created_at) "
                                    "VALUES (?, ?, ?, ?, ?, ?, ?)")
                               [(require-key! assessment :assessment/id)
                                (require-key! assessment :assessment/run-id)
                                (ref-id assessment :assessment/validator-ref)
                                (ref-version assessment :assessment/validator-ref)
                                (require-key! assessment :assessment/outcome)
                                (sql/edn->text assessment)
                                (:assessment/checked-at assessment)])
          assessment))))
  (record-disposition! [_ disposition]
    (let [disposition (normalize-disposition disposition)]
      (sql/with-transaction db-path
        (fn [connection]
          (sql/execute-update! connection
                               (str "INSERT INTO dispositions "
                                    "(disposition_id, run_id, disposition_type, disposition_edn, created_at) "
                                    "VALUES (?, ?, ?, ?, ?)")
                               [(require-key! disposition :disposition/id)
                                (require-key! disposition :disposition/run-id)
                                (require-key! disposition :disposition/action)
                                (sql/edn->text disposition)
                                (:disposition/decided-at disposition)])
          disposition))))
  (transition-task! [_ task-id transition now]
    (sql/with-transaction db-path
      (fn [connection]
        (update-task-transition! connection
                                 task-id
                                 (:transition/from transition)
                                 (:transition/to transition)
                                 now
                                 transition))))
  (transition-run! [_ run-id transition now]
    (sql/with-transaction db-path
      (fn [connection]
        (update-run-transition! connection
                                run-id
                                (:transition/from transition)
                                (:transition/to transition)
                                now
                                transition)))))

(defn sqlite-state-store
  ([] (sqlite-state-store db/default-db-path))
  ([db-path]
   (->SQLiteStateStore db-path)))
