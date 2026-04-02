(ns meta-flow.scheduler
  (:require [clojure.java.io :as io]
            [meta-flow.defs.loader :as defs.loader]
            [meta-flow.defs.protocol :as defs.protocol]
            [meta-flow.events :as events]
            [meta-flow.event-ingest :as event-ingest]
            [meta-flow.fsm :as fsm]
            [meta-flow.projection :as projection]
            [meta-flow.runtime.protocol :as runtime.protocol]
            [meta-flow.runtime.registry :as runtime.registry]
            [meta-flow.service.validation :as service.validation]
            [meta-flow.sql :as sql]
            [meta-flow.store.protocol :as store.protocol]
            [meta-flow.store.sqlite :as store.sqlite]))

(def ^:private max-demo-scheduler-steps 20)

(def ^:private terminal-run-states
  #{:run.state/finalized
    :run.state/retryable-failed})

(def ^:private current-assessment-key
  "validation/current")

(def ^:private current-disposition-key
  "decision/current")

(def ^:private demo-runtime-profile-ref
  {:definition/id :runtime-profile/mock-worker
   :definition/version 1})

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

(defn- run-artifact-root
  [store run]
  (when-let [artifact-id (:run/artifact-id run)]
    (when-let [artifact (store.protocol/find-artifact store artifact-id)]
      (or (:artifact/root-path artifact)
          (:artifact/location artifact)))))

(defn- latest-run
  [store task-id]
  (store.protocol/find-latest-run-for-task store task-id))

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

(defn- terminal-run-state?
  [run]
  (contains? terminal-run-states (:run/state run)))

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

(defn- event-seq
  [event]
  (long (or (:event/seq event) 0)))

(defn- apply-run-event-stream!
  [store defs-repo run now-value]
  (let [watermark (long (or (:run/last-applied-event-seq run) 0))
        events (store.protocol/list-run-events-after store (:run/id run) watermark)]
    (if (seq events)
      (let [applied-run (reduce (fn [current-run event]
                                  (apply-run-event! store defs-repo current-run event now-value))
                                run
                                events)
            max-seq (reduce max watermark (map event-seq events))]
        (or (store.protocol/transition-run! store (:run/id applied-run)
                                            {:transition/from (:run/state applied-run)
                                             :transition/to (:run/state applied-run)
                                             :changes {:run/last-applied-event-seq max-seq}}
                                            now-value)
            (assoc applied-run :run/last-applied-event-seq max-seq)))
      run)))

(defn- apply-task-event-stream!
  [store defs-repo task run-id now-value]
  (let [watermark (long (or (:task/last-applied-event-seq task) 0))
        events (store.protocol/list-run-events-after store run-id watermark)]
    (if (seq events)
      (let [applied-task (reduce (fn [current-task event]
                                   (apply-task-event! store defs-repo current-task event now-value))
                                 task
                                 events)
            max-seq (reduce max watermark (map event-seq events))]
        (or (store.protocol/transition-task! store (:task/id applied-task)
                                             {:transition/from (:task/state applied-task)
                                              :transition/to (:task/state applied-task)
                                              :changes {:task/last-applied-event-seq max-seq}}
                                             now-value)
            (assoc applied-task :task/last-applied-event-seq max-seq)))
      task)))

(defn- apply-event-stream!
  [store defs-repo run task now-value]
  {:run (apply-run-event-stream! store defs-repo run now-value)
   :task (apply-task-event-stream! store defs-repo task (:run/id run) now-value)})

(defn- scheduler-event-intent
  [run event-type payload now-value]
  {:event/run-id (:run/id run)
   :event/type event-type
   :event/payload payload
   :event/caused-by {:actor/type :scheduler
                     :actor/id "meta-flow-scheduler"}
   :event/idempotency-key (str "scheduler:" (:run/id run) ":" event-type)
   :event/emitted-at now-value})

(defn- emit-event!
  [store run event-type payload now-value]
  (event-ingest/ingest-run-event! store
                                  (scheduler-event-intent run event-type payload now-value)))

(defn- persist-dispatch-result!
  [store run dispatch-result now-value]
  (when (seq dispatch-result)
    (or (store.protocol/transition-run! store (:run/id run)
                                        {:transition/from (:run/state run)
                                         :transition/to (:run/state run)
                                         :changes {:run/execution-handle dispatch-result}}
                                        now-value)
        run)))

(defn- ingest-poll-events!
  [store adapter defs-repo run task now-value]
  (let [ctx {:db-path (:db-path store)
             :store store
             :repository defs-repo
             :now now-value}
        poll-events (runtime.protocol/poll-run! adapter ctx run now-value)]
    (doseq [event-intent poll-events]
      (event-ingest/ingest-run-event! store event-intent))
    {:run (or (store.protocol/find-run store (:run/id run)) run)
     :task (or (store.protocol/find-task store (:task/id task)) task)}))

(defn- ensure-collection-state!
  [store defs-repo now-value]
  (or (store.protocol/find-collection-state store :collection/default)
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

(defn- recover-expired-lease!
  [db-path defs-repo run task now-value]
  (when-let [lease-id (:run/lease-id run)]
    (sql/with-transaction db-path
      (fn [connection]
        (let [run-event (scheduler-event-intent run
                                                events/run-lease-expired
                                                {:lease/id lease-id}
                                                now-value)
              task-event (scheduler-event-intent run
                                                 events/task-lease-expired
                                                 {:lease/id lease-id}
                                                 now-value)
              run-after (if-let [transition (run-transition-for-event defs-repo run (:event/type run-event))]
                          (or (store.sqlite/transition-run-via-connection! connection
                                                                           (:run/id run)
                                                                           transition
                                                                           now-value)
                              run)
                          run)
              task-after (if-let [transition (task-transition-for-event defs-repo task (:event/type task-event))]
                           (or (store.sqlite/transition-task-via-connection! connection
                                                                             (:task/id task)
                                                                             transition
                                                                             now-value)
                               task)
                           task)]
          (store.sqlite/ingest-run-event-via-connection! connection run-event)
          (store.sqlite/ingest-run-event-via-connection! connection task-event)
          (release-lease! connection lease-id now-value)
          {:run run-after
           :task task-after})))))

(defn- create-run!
  [db-path store defs-repo task]
  (let [now-value (now)
        runtime-profile (defs.protocol/find-runtime-profile defs-repo
                                                            (get-in task [:task/runtime-profile-ref :definition/id])
                                                            (get-in task [:task/runtime-profile-ref :definition/version]))
        adapter (runtime.registry/runtime-adapter (:runtime-profile/adapter-id runtime-profile))
        run-id (str "run-" (new-id))
        lease-id (str "lease-" (new-id))
        run {:run/id run-id
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
        claimed (store.protocol/claim-task-for-run! store
                                                    task
                                                    run
                                                    lease
                                                    task-lease-transition
                                                    run-lease-transition
                                                    now-value)
        leased-run (:run claimed)
        leased-task (:task claimed)
        ctx {:db-path db-path
             :store store
             :repository defs-repo
             :now now-value}]
    (when claimed
      (try
        (runtime.protocol/prepare-run! adapter ctx leased-task leased-run)
        (let [dispatch-result (runtime.protocol/dispatch-run! adapter ctx leased-task leased-run)
              run-with-handle (or (persist-dispatch-result! store leased-run dispatch-result now-value)
                                  leased-run)
              task-now (or (store.protocol/find-task store (:task/id leased-task))
                           leased-task)]
          (apply-event-stream! store defs-repo run-with-handle task-now now-value))
        (catch Throwable startup-ex
          (try
            (let [recovery-result (store.protocol/recover-run-startup-failure! store leased-task leased-run (now))
                  recovery-status (:recovery/status recovery-result)
                  event-count (:event-count recovery-result)
                  recovered-run (:run recovery-result)
                  recovered-task (:task recovery-result)]
              (when (and (= :skipped recovery-status)
                         (pos? (long (or event-count 0)))
                         recovered-run
                         recovered-task)
                ;; If dispatch emitted durable events before failing, advance the
                ;; control-plane state immediately instead of leaving the claim
                ;; parked in :leased until a later poll or lease timeout.
                (apply-event-stream! store defs-repo recovered-run recovered-task (now))))
            (catch Throwable recovery-ex
              (.addSuppressed startup-ex recovery-ex)))
          (throw startup-ex))))))

(defn- assess-run!
  [store defs-repo run task]
  (let [run-id (:run/id run)
        existing-assessment (store.protocol/find-assessment-by-key store run-id current-assessment-key)
        existing-disposition (store.protocol/find-disposition-by-key store run-id current-disposition-key)
        now-value (now)
        assessment (or existing-assessment
                       (let [artifact-root (run-artifact-root store run)
                             contract (defs.protocol/find-artifact-contract defs-repo
                                                                            (get-in task [:task/artifact-contract-ref :definition/id])
                                                                            (get-in task [:task/artifact-contract-ref :definition/version]))
                             outcome (service.validation/assess-required-paths artifact-root contract)
                             recorded {:assessment/id (new-id)
                                       :assessment/run-id run-id
                                       :assessment/key current-assessment-key
                                       :assessment/validator-ref (:task/validator-ref task)
                                       :assessment/outcome (:assessment/outcome outcome)
                                       :assessment/missing-paths (:assessment/missing-paths outcome)
                                       :assessment/checks (:assessment/checks outcome)
                                       :assessment/notes (:assessment/notes outcome)
                                       :assessment/checked-at now-value}]
                         (store.protocol/record-assessment! store recorded)))
        disposition (or existing-disposition
                        (let [recorded {:disposition/id (new-id)
                                        :disposition/run-id run-id
                                        :disposition/key current-disposition-key
                                        :disposition/decided-at now-value
                                        :disposition/action (if (= :assessment/accepted (:assessment/outcome assessment))
                                                              :disposition/accepted
                                                              :disposition/rejected)
                                        :disposition/notes (:assessment/notes assessment)}]
                          (store.protocol/record-disposition! store recorded)))]
    (if (= :assessment/accepted (:assessment/outcome assessment))
      (do
        (emit-event! store run events/run-assessment-accepted {:artifact/id (:run/artifact-id run)} now-value)
        (emit-event! store run events/task-assessment-accepted {:artifact/id (:run/artifact-id run)} now-value)
        (apply-event-stream! store defs-repo run task now-value))
      (do
        (emit-event! store run events/run-assessment-rejected
                     {:artifact/id (:run/artifact-id run)
                      :assessment/notes (:assessment/notes assessment)}
                     now-value)
        (emit-event! store run events/task-assessment-rejected
                     {:artifact/id (:run/artifact-id run)
                      :assessment/notes (:assessment/notes assessment)}
                     now-value)
        (apply-event-stream! store defs-repo run task now-value)))
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
        _ (doseq [run-id (projection/list-expired-lease-run-ids reader now-value 100)]
            (when-let [run (store.protocol/find-run store run-id)]
              (when-let [task (store.protocol/find-task store (:run/task-id run))]
                (recover-expired-lease! db-path defs-repo run task now-value))))
        _ (doseq [run-id (projection/list-active-run-ids reader now-value 100)]
            (when-let [run (store.protocol/find-run store run-id)]
              (when-let [task (store.protocol/find-task store (:run/task-id run))]
                (let [runtime-profile (defs.protocol/find-runtime-profile defs-repo
                                                                          (get-in run [:run/runtime-profile-ref :definition/id])
                                                                          (get-in run [:run/runtime-profile-ref :definition/version]))
                      adapter (runtime.registry/runtime-adapter (:runtime-profile/adapter-id runtime-profile))
                      {run-now :run task-now :task} (ingest-poll-events! store adapter defs-repo run task now-value)]
                  (when-not (terminal-run-state? run-now)
                    (apply-event-stream! store defs-repo run-now task-now now-value))))))
        _ (doseq [run-id (projection/list-awaiting-validation-run-ids reader now-value 100)]
            (when-let [run (store.protocol/find-run store run-id)]
              (when-let [task (store.protocol/find-task store (:run/task-id run))]
                (let [{run-now :run task-now :task} (apply-event-stream! store defs-repo run task now-value)]
                  (when (and (= :run.state/awaiting-validation (:run/state run-now))
                             (= :task.state/awaiting-validation (:task/state task-now))
                             (:run/artifact-id run-now))
                    (assess-run! store defs-repo run-now task-now))))))
        active-count (projection/count-active-runs reader now-value)
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
  [defs-repo {:keys [task-id work-key runtime-profile-ref]
              :or {runtime-profile-ref demo-runtime-profile-ref}}]
  (let [task-type (defs.protocol/find-task-type-def defs-repo :task-type/cve-investigation 1)]
    {:task/id (or task-id (str "task-" (new-id)))
     :task/work-key (or work-key (str "CVE-2024-12345-" (subs (new-id) 0 8)))
     :task/task-type-ref {:definition/id (:task-type/id task-type)
                          :definition/version (:task-type/version task-type)}
     :task/task-fsm-ref (:task-type/task-fsm-ref task-type)
     :task/run-fsm-ref (:task-type/run-fsm-ref task-type)
     :task/runtime-profile-ref runtime-profile-ref
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

(defn- retry-path-complete?
  [task run]
  (and (= :task.state/retryable-failed (:task/state task))
       (= :run.state/retryable-failed (:run/state run))))

(defn enqueue-demo-task!
  ([db-path]
   (enqueue-demo-task! db-path {}))
  ([db-path options]
   (let [store (store.sqlite/sqlite-state-store db-path)
         defs-repo (defs.loader/filesystem-definition-repository)
         now-value (now)
         _ (ensure-collection-state! store defs-repo now-value)
         existing-task (when-let [work-key (:work-key options)]
                         (store.protocol/find-task-by-work-key store work-key))
         task (or existing-task
                  (store.protocol/enqueue-task! store
                                                (build-demo-task defs-repo options)))]
     {:task task
      :reused? (boolean existing-task)})))

(defn demo-happy-path!
  [db-path]
  (let [store (store.sqlite/sqlite-state-store db-path)
        task (:task (enqueue-demo-task! db-path))]
    (loop [step 0]
      (let [task-now (store.protocol/find-task store (:task/id task))
            run-now (latest-run store (:task/id task))]
        (cond
          (happy-path-complete? task-now run-now)
          {:task task-now
           :run run-now
           :artifact-root (run-artifact-root store run-now)
           :scheduler-steps step}

          (>= step max-demo-scheduler-steps)
          (throw (ex-info "Demo happy path did not converge within the expected scheduler steps"
                          {:task task-now
                           :run run-now
                           :scheduler-steps step}))

          :else
          (do
            (run-scheduler-step db-path)
            (recur (inc step))))))))

(defn demo-retry-path!
  [db-path]
  (let [store (store.sqlite/sqlite-state-store db-path)
        task (:task (enqueue-demo-task! db-path))]
    (loop [step 0
           tampered? false]
      (let [task-now (store.protocol/find-task store (:task/id task))
            run-now (latest-run store (:task/id task))
            artifact-root (some->> run-now
                                   (run-artifact-root store))]
        (cond
          (retry-path-complete? task-now run-now)
          {:task task-now
           :run run-now
           :artifact-root artifact-root
           :assessment (store.protocol/find-assessment-by-key store (:run/id run-now) current-assessment-key)
           :disposition (store.protocol/find-disposition-by-key store (:run/id run-now) current-disposition-key)
           :scheduler-steps step}

          (>= step max-demo-scheduler-steps)
          (throw (ex-info "Demo retry path did not converge within the expected scheduler steps"
                          {:task task-now
                           :run run-now
                           :scheduler-steps step
                           :artifact-root artifact-root
                           :tampered? tampered?}))

          :else
          (let [tampered-now? (if (and (not tampered?)
                                       artifact-root
                                       (= :run.state/exited (:run/state run-now)))
                                (let [notes-file (io/file artifact-root "notes.md")]
                                  (when-not (.exists notes-file)
                                    (throw (ex-info "Demo retry path could not find notes.md to remove"
                                                    {:artifact-root artifact-root
                                                     :run run-now})))
                                  (when-not (.delete notes-file)
                                    (throw (ex-info "Demo retry path could not remove notes.md"
                                                    {:artifact-root artifact-root
                                                     :run run-now})))
                                  true)
                                tampered?)]
            (run-scheduler-step db-path)
            (recur (inc step) tampered-now?)))))))

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
      (let [event-list (store.protocol/list-run-events store run-id)
            heartbeat (->> event-list
                           (filter #(= events/run-worker-heartbeat (:event/type %)))
                           last
                           :event/emitted-at)]
        (assoc run
               :run/artifact-root (run-artifact-root store run)
               :run/event-count (count event-list)
               :run/last-heartbeat heartbeat))
      (throw (ex-info (str "Run not found: " run-id) {:run-id run-id})))))

(defn inspect-collection!
  [db-path]
  (let [store (store.sqlite/sqlite-state-store db-path)]
    (or (store.protocol/find-collection-state store :collection/default)
        {:collection/dispatch {:dispatch/paused? false}
         :collection/resource-policy-ref {:definition/id :resource-policy/default
                                          :definition/version 1}})))
