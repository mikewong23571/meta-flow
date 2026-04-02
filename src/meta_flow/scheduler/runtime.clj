(ns meta-flow.scheduler.runtime
  (:require [meta-flow.control.event-ingest :as event-ingest]
            [meta-flow.control.fsm :as fsm]
            [meta-flow.defs.protocol :as defs.protocol]
            [meta-flow.runtime.protocol :as runtime.protocol]
            [meta-flow.runtime.registry :as runtime.registry]
            [meta-flow.scheduler.shared :as shared]
            [meta-flow.scheduler.state :as state]
            [meta-flow.scheduler.runtime.timeout :as timeout]
            [meta-flow.store.protocol :as store.protocol]))

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

(defn recover-expired-lease!
  [env run task]
  (timeout/recover-expired-lease! env run task))

(defn recover-heartbeat-timeout!
  [env run task timeout-value]
  (timeout/recover-heartbeat-timeout! env run task timeout-value))

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
