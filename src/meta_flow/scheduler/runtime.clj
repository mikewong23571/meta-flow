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
            [meta-flow.store.sqlite :as store.sqlite]
            [meta-flow.store.sqlite.shared :as store.sqlite.shared]))

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

(defn- find-lease-row
  [connection lease-id]
  (sql/query-one connection
                 "SELECT lease_id, state, lease_expires_at FROM leases WHERE lease_id = ?"
                 [lease-id]))

(defn- last-progress-at
  [connection run-id]
  (:last_progress_at
   (sql/query-one connection
                  (str "SELECT MAX(created_at) AS last_progress_at "
                       "FROM run_events "
                       "WHERE run_id = ? AND event_type IN (?, ?)")
                  [run-id
                   (str events/run-worker-started)
                   (str events/run-worker-heartbeat)])))

(defn- heartbeat-timed-out?
  [run last-progress-at now]
  (let [timeout-seconds (long (or (:run/heartbeat-timeout-seconds run) 0))
        observed-at (or last-progress-at
                        (when (contains? #{:run.state/dispatched
                                           :run.state/running}
                                         (:run/state run))
                          (:run/updated-at run)))]
    (and (contains? #{:run.state/dispatched
                      :run.state/running}
                    (:run/state run))
         (pos? timeout-seconds)
         observed-at
         (not (.isAfter (.plusSeconds (java.time.Instant/parse observed-at)
                                      timeout-seconds)
                        (java.time.Instant/parse now))))))

(defn- expired-lease?
  [lease-row now]
  (and lease-row
       (= ":lease.state/active" (:state lease-row))
       (not (.isAfter (java.time.Instant/parse (:lease_expires_at lease-row))
                      (java.time.Instant/parse now)))))

(defn- active-lease?
  [lease-row]
  (and lease-row
       (= ":lease.state/active" (:state lease-row))))

(defn- current-timeout-context
  [connection defs-repo run task now timeout-kind]
  (let [current-run-row (store.sqlite/find-run-row connection (:run/id run))
        current-task-row (store.sqlite/find-task-row connection (:task/id task))
        current-run (some-> current-run-row store.sqlite.shared/run-row->entity)
        current-task (some-> current-task-row (store.sqlite.shared/parse-edn-column :task_edn))
        lease-id (:run/lease-id current-run)
        lease-row (when lease-id
                    (find-lease-row connection lease-id))
        progress-at (when current-run
                      (last-progress-at connection (:run/id current-run)))]
    (when (and current-run current-task lease-id)
      (case timeout-kind
        :timeout.kind/lease-expiry
        (when (and (expired-lease? lease-row now)
                   (state/run-transition-for-event defs-repo current-run events/run-lease-expired)
                   (state/task-transition-for-event defs-repo current-task events/task-lease-expired))
          {:run current-run
           :task current-task
           :lease-id lease-id})

        :timeout.kind/heartbeat
        (when (and (active-lease? lease-row)
                   (heartbeat-timed-out? current-run progress-at now)
                   (state/run-transition-for-event defs-repo current-run events/run-heartbeat-timed-out)
                   (state/task-transition-for-event defs-repo current-task events/task-heartbeat-timed-out))
          {:run current-run
           :task current-task
           :lease-id lease-id
           :last-progress-at progress-at})))))

(defn- recover-timeout!
  [{:keys [db-path defs-repo now]} run task run-event task-event payload-fn timeout-kind]
  (try
    (sql/with-transaction db-path
      (fn [connection]
        (when-let [{current-run :run
                    current-task :task
                    current-lease-id :lease-id
                    :as context}
                   (current-timeout-context connection defs-repo run task now timeout-kind)]
          (let [payload (assoc (payload-fn context) :lease/id current-lease-id)
                run-event-intent (state/scheduler-event-intent current-run
                                                               run-event
                                                               payload
                                                               now)
                task-event-intent (state/scheduler-event-intent current-run
                                                                task-event
                                                                payload
                                                                now)
                run-transition (state/run-transition-for-event defs-repo current-run (:event/type run-event-intent))
                task-transition (state/task-transition-for-event defs-repo current-task (:event/type task-event-intent))
                run-after (and run-transition
                               (store.sqlite/transition-run-via-connection! connection
                                                                            (:run/id current-run)
                                                                            run-transition
                                                                            now))
                task-after (and task-transition
                                (store.sqlite/transition-task-via-connection! connection
                                                                              (:task/id current-task)
                                                                              task-transition
                                                                              now))]
            (cond
              (and run-after task-after)
              (do
                (store.sqlite/ingest-run-event-via-connection! connection run-event-intent)
                (store.sqlite/ingest-run-event-via-connection! connection task-event-intent)
                (release-lease! connection current-lease-id now)
                {:run run-after
                 :task task-after})

              (or run-after task-after)
              (throw (ex-info "Timeout recovery lost a concurrent transition race"
                              {:error/type ::timeout-recovery-race
                               :timeout/kind timeout-kind
                               :run/id (:run/id current-run)
                               :task/id (:task/id current-task)}))

              :else
              nil)))))
    (catch clojure.lang.ExceptionInfo exception
      (if (= ::timeout-recovery-race (:error/type (ex-data exception)))
        nil
        (throw exception)))))

(defn recover-expired-lease!
  [env run task]
  (recover-timeout! env
                    run
                    task
                    events/run-lease-expired
                    events/task-lease-expired
                    (constantly {:timeout/kind :timeout.kind/lease-expiry})
                    :timeout.kind/lease-expiry))

(defn recover-heartbeat-timeout!
  [env run task timeout]
  (recover-timeout! env
                    run
                    task
                    events/run-heartbeat-timed-out
                    events/task-heartbeat-timed-out
                    (fn [{:keys [run last-progress-at]}]
                      {:timeout/kind :timeout.kind/heartbeat
                       :timeout/seconds (:run/heartbeat-timeout-seconds run)
                       :timeout/last-heartbeat-at (or last-progress-at
                                                      (:run/updated-at run))
                       :timeout/requested-last-heartbeat-at (:timeout/last-heartbeat-at timeout)})
                    :timeout.kind/heartbeat))

(defn create-run!
  [{:keys [store defs-repo now] :as env} task]
  (let [adapter (runtime-adapter-for-task defs-repo task)
        run-id (str "run-" (shared/new-id))
        lease-id (str "lease-" (shared/new-id))
        run (cond-> {:run/id run-id
                     :run/run-fsm-ref (:task/run-fsm-ref task)
                     :run/runtime-profile-ref (:task/runtime-profile-ref task)
                     :run/state :run.state/created
                     :run/created-at now
                     :run/updated-at now}
              (shared/task-heartbeat-timeout-seconds defs-repo task)
              (assoc :run/heartbeat-timeout-seconds
                     (shared/task-heartbeat-timeout-seconds defs-repo task)))
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
