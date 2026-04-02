(ns meta-flow.scheduler.runtime
  (:require [meta-flow.control.event-ingest :as event-ingest]
            [meta-flow.control.events :as events]
            [meta-flow.control.fsm :as fsm]
            [meta-flow.defs.protocol :as defs.protocol]
            [meta-flow.runtime.protocol :as runtime.protocol]
            [meta-flow.runtime.registry :as runtime.registry]
            [meta-flow.scheduler.shared :as shared]
            [meta-flow.scheduler.state :as state]
            [meta-flow.sql :as sql]
            [meta-flow.store.protocol :as store.protocol]
            [meta-flow.store.sqlite :as store.sqlite]))

(defn runtime-profile
  [defs-repo runtime-profile-ref]
  (defs.protocol/find-runtime-profile defs-repo
                                      (:definition/id runtime-profile-ref)
                                      (:definition/version runtime-profile-ref)))

(defn runtime-adapter-for-run
  [defs-repo run]
  (let [profile (runtime-profile defs-repo (:run/runtime-profile-ref run))]
    (runtime.registry/runtime-adapter (:runtime-profile/adapter-id profile))))

(defn runtime-adapter-for-task
  [defs-repo task]
  (let [profile (runtime-profile defs-repo (:task/runtime-profile-ref task))]
    (runtime.registry/runtime-adapter (:runtime-profile/adapter-id profile))))

(defn runtime-context
  [{:keys [db-path store defs-repo now]}]
  {:db-path db-path
   :store store
   :repository defs-repo
   :now now})

(defn persist-dispatch-result!
  [store run dispatch-result now-value]
  (when (seq dispatch-result)
    (or (store.protocol/transition-run! store (:run/id run)
                                        {:transition/from (:run/state run)
                                         :transition/to (:run/state run)
                                         :changes {:run/execution-handle dispatch-result}}
                                        now-value)
        run)))

(defn ingest-poll-events!
  [env adapter run task]
  (let [{:keys [store now]} env
        ctx (runtime-context env)
        poll-events (runtime.protocol/poll-run! adapter ctx run now)]
    (doseq [event-intent poll-events]
      (event-ingest/ingest-run-event! store event-intent))
    {:run (or (store.protocol/find-run store (:run/id run)) run)
     :task (or (store.protocol/find-task store (:task/id task)) task)}))

(defn release-lease!
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

(defn recover-expired-lease!
  [{:keys [db-path defs-repo now]} run task]
  (when-let [lease-id (:run/lease-id run)]
    (sql/with-transaction db-path
      (fn [connection]
        (let [run-event (state/scheduler-event-intent run
                                                      events/run-lease-expired
                                                      {:lease/id lease-id}
                                                      now)
              task-event (state/scheduler-event-intent run
                                                       events/task-lease-expired
                                                       {:lease/id lease-id}
                                                       now)
              run-after (if-let [transition (state/run-transition-for-event defs-repo run (:event/type run-event))]
                          (or (store.sqlite/transition-run-via-connection! connection
                                                                           (:run/id run)
                                                                           transition
                                                                           now)
                              run)
                          run)
              task-after (if-let [transition (state/task-transition-for-event defs-repo task (:event/type task-event))]
                           (or (store.sqlite/transition-task-via-connection! connection
                                                                             (:task/id task)
                                                                             transition
                                                                             now)
                               task)
                           task)]
          (store.sqlite/ingest-run-event-via-connection! connection run-event)
          (store.sqlite/ingest-run-event-via-connection! connection task-event)
          (release-lease! connection lease-id now)
          {:run run-after
           :task task-after})))))

(defn create-run!
  [{:keys [store defs-repo now] :as env} task]
  (let [adapter (runtime-adapter-for-task defs-repo task)
        run-id (str "run-" (shared/new-id))
        lease-id (str "lease-" (shared/new-id))
        run {:run/id run-id
             :run/run-fsm-ref (:task/run-fsm-ref task)
             :run/runtime-profile-ref (:task/runtime-profile-ref task)
             :run/state :run.state/created
             :run/created-at now
             :run/updated-at now}
        lease {:lease/id lease-id
               :lease/run-id run-id
               :lease/token (str lease-id "-token")
               :lease/state :lease.state/active
               :lease/expires-at (shared/lease-expires-at now)
               :lease/created-at now
               :lease/updated-at now}
        run-lease-transition {:transition/from (:run/state run)
                              :transition/to (fsm/ensure-transition! (state/run-fsm defs-repo run)
                                                                     :run
                                                                     (:run/id run)
                                                                     (:run/state run)
                                                                     :run.event/lease-granted)}
        task-lease-transition {:transition/from (:task/state task)
                               :transition/to (fsm/ensure-transition! (state/task-fsm defs-repo task)
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
                                                    now)
        leased-run (:run claimed)
        leased-task (:task claimed)
        ctx (runtime-context env)]
    (when claimed
      (try
        (runtime.protocol/prepare-run! adapter ctx leased-task leased-run)
        (let [dispatch-result (runtime.protocol/dispatch-run! adapter ctx leased-task leased-run)
              run-with-handle (or (persist-dispatch-result! store leased-run dispatch-result now)
                                  leased-run)
              task-now (or (store.protocol/find-task store (:task/id leased-task))
                           leased-task)]
          (state/apply-event-stream! store defs-repo run-with-handle task-now now))
        (catch Throwable startup-ex
          (try
            (let [recovery-result (store.protocol/recover-run-startup-failure! store leased-task leased-run (shared/now))
                  recovery-status (:recovery/status recovery-result)
                  event-count (:event-count recovery-result)
                  recovered-run (:run recovery-result)
                  recovered-task (:task recovery-result)]
              (when (and (= :skipped recovery-status)
                         (pos? (long (or event-count 0)))
                         recovered-run
                         recovered-task)
                (state/apply-event-stream! store defs-repo recovered-run recovered-task (shared/now))))
            (catch Throwable recovery-ex
              (.addSuppressed startup-ex recovery-ex)))
          (throw startup-ex))))))
