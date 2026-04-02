(ns meta-flow.scheduler
  (:require [meta-flow.defs.loader :as defs.loader]
            [meta-flow.defs.protocol :as defs.protocol]
            [meta-flow.event-ingest :as event-ingest]
            [meta-flow.fsm :as fsm]
            [meta-flow.projection :as projection]
            [meta-flow.runtime.protocol :as runtime.protocol]
            [meta-flow.runtime.registry :as runtime.registry]
            [meta-flow.service.validation :as service.validation]
            [meta-flow.sql :as sql]
            [meta-flow.store.protocol :as store.protocol]
            [meta-flow.store.sqlite :as store.sqlite]))

(def ^:private max-demo-scheduler-steps 5)

(defn- new-id
  []
  (str (java.util.UUID/randomUUID)))

(defn- now
  []
  (sql/utc-now))

(defn- lease-expires-at
  [now-value]
  (-> (java.time.Instant/parse now-value)
      (.plusSeconds 1800)
      str))

(defn- scalar-query
  [db-path sql-text params]
  (sql/with-connection db-path
    (fn [connection]
      (when-let [row (sql/query-one connection sql-text params)]
        (first (vals row))))))

(defn- latest-collection-state
  [db-path]
  (sql/with-connection db-path
    (fn [connection]
      (when-let [row (sql/query-one connection
                                    "SELECT state_edn FROM collection_state ORDER BY updated_at DESC LIMIT 1"
                                    [])]
        (sql/text->edn (:state_edn row))))))

(defn- all-non-final-runs
  [db-path]
  (sql/with-connection db-path
    (fn [connection]
      (map :run_id
           (sql/query-rows connection
                           "SELECT run_id FROM runs WHERE state <> ':run.state/finalized'"
                           [])))))

(defn- find-artifact
  [db-path artifact-id]
  (sql/with-connection db-path
    (fn [connection]
      (when-let [row (sql/query-one connection
                                    "SELECT artifact_edn FROM artifacts WHERE artifact_id = ?"
                                    [artifact-id])]
        (sql/text->edn (:artifact_edn row))))))

(defn- latest-assessment
  [db-path run-id]
  (sql/with-connection db-path
    (fn [connection]
      (when-let [row (sql/query-one connection
                                    "SELECT assessment_edn FROM assessments WHERE run_id = ? ORDER BY created_at DESC LIMIT 1"
                                    [run-id])]
        (sql/text->edn (:assessment_edn row))))))

(defn- latest-disposition
  [db-path run-id]
  (sql/with-connection db-path
    (fn [connection]
      (when-let [row (sql/query-one connection
                                    "SELECT disposition_edn FROM dispositions WHERE run_id = ? ORDER BY created_at DESC LIMIT 1"
                                    [run-id])]
        (sql/text->edn (:disposition_edn row))))))

(defn- run-artifact-root
  [db-path run]
  (when-let [artifact-id (:run/artifact-id run)]
    (when-let [artifact (find-artifact db-path artifact-id)]
      (or (:artifact/root-path artifact)
          (:artifact/location artifact)))))

(defn- active-run-count
  [db-path]
  (or (scalar-query db-path
                    "SELECT COUNT(*) AS count FROM runs WHERE state <> ':run.state/finalized'"
                    [])
      0))

(defn- run-count-for-task
  [db-path task-id]
  (or (scalar-query db-path
                    "SELECT COUNT(*) AS count FROM runs WHERE task_id = ?"
                    [task-id])
      0))

(defn- latest-run-id-for-task
  [db-path task-id]
  (scalar-query db-path
                "SELECT run_id FROM runs WHERE task_id = ? ORDER BY attempt DESC LIMIT 1"
                [task-id]))

(defn- latest-run
  [store task-id]
  (when-let [run-id (latest-run-id-for-task (:db-path store) task-id)]
    (store.protocol/find-run store run-id)))

(defn- task-fsm
  [defs-repo task]
  (defs.protocol/find-task-fsm-def defs-repo
                                   (get-in task [:task/task-fsm-ref :definition/id])
                                   (get-in task [:task/task-fsm-ref :definition/version])))

(defn- run-fsm
  [defs-repo run]
  (defs.protocol/find-run-fsm-def defs-repo
                                  (get-in run [:run/run-fsm-ref :definition/id])
                                  (get-in run [:run/run-fsm-ref :definition/version])))

(defn- task-transition-for-event
  [defs-repo task event-type]
  (fsm/apply-task-event (task-fsm defs-repo task) task event-type))

(defn- run-transition-for-event
  [defs-repo run event-type]
  (fsm/apply-run-event (run-fsm defs-repo run) run event-type))

(defn- apply-task-event!
  [store defs-repo task event now-value]
  (if-let [transition (task-transition-for-event defs-repo task (:event/type event))]
    (or (store.protocol/transition-task! store (:task/id task) transition now-value)
        task)
    task))

(defn- apply-run-event!
  [store defs-repo run event now-value]
  (if-let [transition (run-transition-for-event defs-repo run (:event/type event))]
    (or (store.protocol/transition-run! store (:run/id run) transition now-value)
        run)
    run))

(defn- apply-event-stream!
  [store defs-repo run task now-value]
  (let [events (store.protocol/list-run-events store (:run/id run))]
    (reduce (fn [{:keys [run task]} event]
              {:run (apply-run-event! store defs-repo run event now-value)
               :task (apply-task-event! store defs-repo task event now-value)})
            {:run run
             :task task}
            events)))

(defn- emit-event!
  [store run event-type payload now-value]
  (event-ingest/ingest-run-event! store
                                  {:event/run-id (:run/id run)
                                   :event/type event-type
                                   :event/payload payload
                                   :event/caused-by {:actor/type :scheduler
                                                     :actor/id "meta-flow-scheduler"}
                                   :event/idempotency-key (str "scheduler:" (:run/id run) ":" event-type)
                                   :event/emitted-at now-value}))

(defn- ensure-collection-state!
  [store defs-repo now-value]
  (or (latest-collection-state (:db-path store))
      (let [default-policy (defs.protocol/find-resource-policy defs-repo
                                                               :resource-policy/default
                                                               1)
            initial {:collection/id :collection/default
                     :collection/dispatch {:dispatch/paused? false}
                     :collection/resource-policy-ref {:definition/id (:resource-policy/id default-policy)
                                                      :definition/version (:resource-policy/version default-policy)}
                     :collection/created-at now-value
                     :collection/updated-at now-value}]
        (store.protocol/upsert-collection-state! store initial)
        initial)))

(defn- collection-policy
  [defs-repo collection-state]
  (defs.protocol/find-resource-policy defs-repo
                                      (get-in collection-state [:collection/resource-policy-ref :definition/id])
                                      (get-in collection-state [:collection/resource-policy-ref :definition/version])))

(defn- release-lease!
  [connection lease-id now-value]
  (when-let [row (sql/query-one connection
                                "SELECT lease_edn FROM leases WHERE lease_id = ?"
                                [lease-id])]
    (let [released-lease (-> (sql/text->edn (:lease_edn row))
                             (assoc :lease/state :lease.state/released
                                    :lease/updated-at now-value)
                             sql/canonicalize-edn)]
      (sql/execute-update! connection
                           "UPDATE leases SET state = ?, lease_edn = ?, updated_at = ? WHERE lease_id = ?"
                           [(:lease/state released-lease)
                            (sql/edn->text released-lease)
                            (:lease/updated-at released-lease)
                            lease-id]))))

(defn- recover-run-startup-failure!
  [db-path task run now-value]
  (sql/with-transaction db-path
    (fn [connection]
      (let [recovered-run (-> run
                              (assoc :run/state :run.state/finalized
                                     :run/updated-at now-value)
                              sql/canonicalize-edn)
            recovered-task (-> task
                               (assoc :task/state :task.state/queued
                                      :task/updated-at now-value)
                               sql/canonicalize-edn)]
        (sql/execute-update! connection
                             "UPDATE runs SET state = ?, run_edn = ?, updated_at = ? WHERE run_id = ?"
                             [(:run/state recovered-run)
                              (sql/edn->text recovered-run)
                              (:run/updated-at recovered-run)
                              (:run/id recovered-run)])
        (release-lease! connection (:run/lease-id recovered-run) now-value)
        (sql/execute-update! connection
                             "UPDATE tasks SET state = ?, task_edn = ?, updated_at = ? WHERE task_id = ?"
                             [(:task/state recovered-task)
                              (sql/edn->text recovered-task)
                              (:task/updated-at recovered-task)
                              (:task/id recovered-task)])))))

(defn- create-run!
  [db-path store defs-repo task]
  (let [now-value (now)
        runtime-profile (defs.protocol/find-runtime-profile defs-repo
                                                            (get-in task [:task/runtime-profile-ref :definition/id])
                                                            (get-in task [:task/runtime-profile-ref :definition/version]))
        adapter (runtime.registry/runtime-adapter (:runtime-profile/adapter-id runtime-profile))
        run-id (str "run-" (new-id))
        lease-id (str "lease-" (new-id))
        attempt (inc (run-count-for-task db-path (:task/id task)))
        run {:run/id run-id
             :run/attempt attempt
             :run/run-fsm-ref (:task/run-fsm-ref task)
             :run/runtime-profile-ref (:task/runtime-profile-ref task)
             :run/state :run.state/created
             :run/created-at now-value
             :run/updated-at now-value}
        lease {:lease/id lease-id
               :lease/run-id run-id
               :lease/token (str lease-id "-token")
               :lease/state :lease.state/active
               :lease/expires-at (lease-expires-at now-value)
               :lease/created-at now-value
               :lease/updated-at now-value}
        {:keys [run]} (store.protocol/create-run! store task run lease)
        run-lease-transition {:transition/from (:run/state run)
                              :transition/to (fsm/ensure-transition! (run-fsm defs-repo run)
                                                                     :run
                                                                     (:run/id run)
                                                                     (:run/state run)
                                                                     :run.event/lease-granted)}
        task-lease-transition {:transition/from (:task/state task)
                               :transition/to (fsm/ensure-transition! (task-fsm defs-repo task)
                                                                      :task
                                                                      (:task/id task)
                                                                      (:task/state task)
                                                                      :task.event/lease-granted)}
        leased-run (or (store.protocol/transition-run! store (:run/id run) run-lease-transition now-value)
                       run)
        leased-task (or (store.protocol/transition-task! store (:task/id task) task-lease-transition now-value)
                        task)
        ctx {:db-path db-path
             :store store
             :repository defs-repo
             :now now-value}]
    (try
      (runtime.protocol/prepare-run! adapter ctx leased-task leased-run)
      (runtime.protocol/dispatch-run! adapter ctx leased-task leased-run)
      (apply-event-stream! store defs-repo leased-run leased-task now-value)
      (catch Throwable startup-ex
        (try
          (recover-run-startup-failure! db-path leased-task leased-run (now))
          (catch Throwable recovery-ex
            (.addSuppressed startup-ex recovery-ex)))
        (throw startup-ex)))))

(defn- assess-run!
  [store defs-repo db-path run task]
  (let [run-id (:run/id run)
        existing-assessment (latest-assessment db-path run-id)
        existing-disposition (latest-disposition db-path run-id)
        now-value (now)
        assessment (or existing-assessment
                       (let [artifact-root (run-artifact-root db-path run)
                             contract (defs.protocol/find-artifact-contract defs-repo
                                                                            (get-in task [:task/artifact-contract-ref :definition/id])
                                                                            (get-in task [:task/artifact-contract-ref :definition/version]))
                             outcome (service.validation/assess-required-paths artifact-root contract)
                             recorded {:assessment/id (new-id)
                                       :assessment/run-id run-id
                                       :assessment/validator-ref (:task/validator-ref task)
                                       :assessment/outcome (:assessment/outcome outcome)
                                       :assessment/missing-paths (:assessment/missing-paths outcome)
                                       :assessment/checks (:assessment/checks outcome)
                                       :assessment/notes (:assessment/notes outcome)
                                       :assessment/checked-at now-value}]
                         (store.protocol/record-assessment! store recorded)
                         recorded))
        disposition (or existing-disposition
                        (let [recorded {:disposition/id (new-id)
                                        :disposition/run-id run-id
                                        :disposition/decided-at now-value
                                        :disposition/action (if (= :assessment/accepted (:assessment/outcome assessment))
                                                              :disposition/accepted
                                                              :disposition/rejected)
                                        :disposition/notes (:assessment/notes assessment)}]
                          (store.protocol/record-disposition! store recorded)
                          recorded))]
    (when (= :assessment/accepted (:assessment/outcome assessment))
      (emit-event! store run :run.event/assessment-accepted {:artifact/id (:run/artifact-id run)} now-value)
      (emit-event! store run :task.event/assessment-accepted {:artifact/id (:run/artifact-id run)} now-value)
      (apply-event-stream! store defs-repo run task now-value))
    {:assessment assessment
     :disposition disposition}))

(defn run-scheduler-step
  [db-path]
  (let [store (store.sqlite/sqlite-state-store db-path)
        defs-repo (defs.loader/filesystem-definition-repository)
        reader (projection/sqlite-projection-reader db-path)
        now-value (now)
        collection-state (ensure-collection-state! store defs-repo now-value)
        snapshot (projection/load-scheduler-snapshot reader now-value)
        _ (doseq [run-id (all-non-final-runs db-path)]
            (when-let [run (store.protocol/find-run store run-id)]
              (when-let [task (store.protocol/find-task store (:run/task-id run))]
                (apply-event-stream! store defs-repo run task now-value))))
        _ (doseq [run-id (projection/list-awaiting-validation-run-ids reader now-value 100)]
            (when-let [run (store.protocol/find-run store run-id)]
              (when-let [task (store.protocol/find-task store (:run/task-id run))]
                (let [{run-now :run task-now :task} (apply-event-stream! store defs-repo run task now-value)]
                  (when (and (= :run.state/awaiting-validation (:run/state run-now))
                             (= :task.state/awaiting-validation (:task/state task-now))
                             (:run/artifact-id run-now))
                    (assess-run! store defs-repo db-path run-now task-now))))))
        active-count (active-run-count db-path)
        max-active (or (:resource-policy/max-active-runs
                        (collection-policy defs-repo collection-state))
                       1)
        created-runs (atom [])
        task-errors (atom [])]
    (when-not (:dispatch/paused? (:collection/dispatch collection-state))
      (loop [remaining (max 0 (- max-active active-count))
             runnable-ids (projection/list-runnable-task-ids reader now-value 100)]
        (when (and (pos? remaining) (seq runnable-ids))
          (let [task-id (first runnable-ids)
                created-run (when-let [task (store.protocol/find-task store task-id)]
                              (when (= :task.state/queued (:task/state task))
                                (try
                                  (let [run (create-run! db-path store defs-repo task)]
                                    (swap! created-runs conj run)
                                    run)
                                  (catch Throwable throwable
                                    (swap! task-errors conj
                                           {:task/id (:task/id task)
                                            :task/work-key (:task/work-key task)
                                            :error/message (.getMessage throwable)
                                            :error/data (ex-data throwable)})
                                    nil))))]
            (recur (if created-run
                     (dec remaining)
                     remaining)
                   (rest runnable-ids))))))
    {:created-runs @created-runs
     :task-errors @task-errors
     :snapshot snapshot
     :now now-value
     :collection-state collection-state}))

(defn- build-demo-task
  [defs-repo]
  (let [task-type (defs.protocol/find-task-type-def defs-repo :task-type/cve-investigation 1)]
    {:task/id (str "task-" (new-id))
     :task/work-key (str "CVE-2024-12345-" (subs (new-id) 0 8))
     :task/task-type-ref {:definition/id (:task-type/id task-type)
                          :definition/version (:task-type/version task-type)}
     :task/task-fsm-ref (:task-type/task-fsm-ref task-type)
     :task/run-fsm-ref (:task-type/run-fsm-ref task-type)
     :task/runtime-profile-ref {:definition/id :runtime-profile/mock-worker
                                :definition/version 1}
     :task/artifact-contract-ref (:task-type/artifact-contract-ref task-type)
     :task/validator-ref (:task-type/validator-ref task-type)
     :task/resource-policy-ref (:task-type/resource-policy-ref task-type)
     :task/state :task.state/queued
     :task/created-at (now)
     :task/updated-at (now)}))

(defn- happy-path-complete?
  [task run]
  (and (= :task.state/completed (:task/state task))
       (= :run.state/finalized (:run/state run))))

(defn demo-happy-path!
  [db-path]
  (let [store (store.sqlite/sqlite-state-store db-path)
        defs-repo (defs.loader/filesystem-definition-repository)
        _ (ensure-collection-state! store defs-repo (now))
        task (store.protocol/enqueue-task! store (build-demo-task defs-repo))]
    (loop [step 0]
      (let [task-now (store.protocol/find-task store (:task/id task))
            run-now (latest-run store (:task/id task))]
        (cond
          (happy-path-complete? task-now run-now)
          {:task task-now
           :run run-now
           :artifact-root (run-artifact-root db-path run-now)
           :scheduler-steps step}

          (>= step max-demo-scheduler-steps)
          (throw (ex-info "Demo happy path did not converge within the expected scheduler steps"
                          {:task task-now
                           :run run-now
                           :scheduler-steps step}))

          (and task-now
               (= :task.state/queued (:task/state task-now))
               (nil? run-now))
          (do
            (create-run! db-path store defs-repo task-now)
            (recur (inc step)))

          :else
          (let [{run-next :run task-next :task} (apply-event-stream! store defs-repo run-now task-now (now))]
            (when (and (= :run.state/awaiting-validation (:run/state run-next))
                       (= :task.state/awaiting-validation (:task/state task-next))
                       (:run/artifact-id run-next))
              (assess-run! store defs-repo db-path run-next task-next))
            (recur (inc step))))))))

(defn inspect-task!
  [db-path task-id]
  (let [store (store.sqlite/sqlite-state-store db-path)]
    (if-let [task (store.protocol/find-task store task-id)]
      (select-keys task
                   [:task/id
                    :task/state
                    :task/work-key
                    :task/task-type-ref
                    :task/task-fsm-ref
                    :task/run-fsm-ref
                    :task/runtime-profile-ref
                    :task/artifact-contract-ref
                    :task/validator-ref
                    :task/resource-policy-ref
                    :task/created-at
                    :task/updated-at])
      (throw (ex-info (str "Task not found: " task-id) {:task-id task-id})))))

(defn inspect-run!
  [db-path run-id]
  (let [store (store.sqlite/sqlite-state-store db-path)]
    (if-let [run (store.protocol/find-run store run-id)]
      (let [events (store.protocol/list-run-events store run-id)
            heartbeat (->> events
                           (filter #(= :event/worker-heartbeat (:event/type %)))
                           last
                           :event/emitted-at)]
        (assoc run
               :run/artifact-root (run-artifact-root db-path run)
               :run/event-count (count events)
               :run/last-heartbeat heartbeat))
      (throw (ex-info (str "Run not found: " run-id) {:run-id run-id})))))

(defn inspect-collection!
  [db-path]
  (or (latest-collection-state db-path)
      {:collection/dispatch {:dispatch/paused? false}
       :collection/resource-policy-ref {:definition/id :resource-policy/default
                                        :definition/version 1}}))
