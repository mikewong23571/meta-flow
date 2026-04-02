(ns meta-flow.scheduler.step
  (:require [meta-flow.control.events :as events]
            [meta-flow.control.projection :as projection]
            [meta-flow.defs.loader :as defs.loader]
            [meta-flow.scheduler.dispatch.core :as dispatch]
            [meta-flow.scheduler.retry :as retry]
            [meta-flow.scheduler.runtime :as runtime]
            [meta-flow.scheduler.shared :as shared]
            [meta-flow.scheduler.state :as state]
            [meta-flow.scheduler.validation :as validation]
            [meta-flow.store.protocol :as store.protocol]
            [meta-flow.store.sqlite :as store.sqlite]))

(defn scheduler-env
  [db-path]
  {:db-path db-path
   :store (store.sqlite/sqlite-state-store db-path)
   :defs-repo (defs.loader/filesystem-definition-repository)
   :reader (projection/sqlite-projection-reader db-path)
   :now (shared/now)})

(defn- run-last-progress-at
  [store run-id]
  (->> (store.protocol/list-run-events store run-id)
       (filter #(contains? #{events/run-worker-started
                             events/run-worker-heartbeat}
                           (:event/type %)))
       (reduce (fn [latest event]
                 (let [emitted-at (:event/emitted-at event)]
                   (if (or (nil? latest)
                           (.isAfter (java.time.Instant/parse emitted-at)
                                     (java.time.Instant/parse latest)))
                     emitted-at
                     latest)))
               nil)))

(defn- enrich-run
  [store run]
  (assoc run :run/last-progress-at (run-last-progress-at store (:run/id run))))

(defn- heartbeat-timed-out?
  [run now]
  (let [timeout-seconds (long (or (:run/heartbeat-timeout-seconds run) 0))
        last-progress-at (or (:run/last-progress-at run)
                             (when (contains? #{:run.state/dispatched
                                                :run.state/running}
                                              (:run/state run))
                               (:run/updated-at run)))]
    (and (contains? #{:run.state/dispatched
                      :run.state/running}
                    (:run/state run))
         (pos? timeout-seconds)
         last-progress-at
         (not (.isAfter (.plusSeconds (java.time.Instant/parse last-progress-at)
                                      timeout-seconds)
                        (java.time.Instant/parse now))))))

(defn recover-heartbeat-timeouts!
  [{:keys [reader now store] :as env}]
  (let [recovered-run-ids (atom [])]
    (doseq [run-id (projection/list-heartbeat-timeout-run-ids reader now 100)]
      (when-let [run (store.protocol/find-run store run-id)]
        (when-let [task (store.protocol/find-task store (:run/task-id run))]
          (let [run-now (enrich-run store run)]
            (when (heartbeat-timed-out? run-now now)
              (let [timeout {:timeout/kind :timeout.kind/heartbeat
                             :timeout/seconds (:run/heartbeat-timeout-seconds run-now)
                             :timeout/last-heartbeat-at (or (:run/last-progress-at run-now)
                                                            (:run/updated-at run-now))}]
                (when (runtime/recover-heartbeat-timeout! env run-now task timeout)
                  (swap! recovered-run-ids conj run-id))))))))
    @recovered-run-ids))

(defn recover-expired-leases!
  [{:keys [reader now store] :as env}]
  (let [recovered-run-ids (atom [])]
    (doseq [run-id (projection/list-expired-lease-run-ids reader now 100)]
      (when-let [run (store.protocol/find-run store run-id)]
        (when-let [task (store.protocol/find-task store (:run/task-id run))]
          (when (runtime/recover-expired-lease! env run task)
            (swap! recovered-run-ids conj run-id)))))
    @recovered-run-ids))

(defn poll-active-runs!
  [{:keys [reader now store defs-repo] :as env}]
  (doseq [run-id (projection/list-active-run-ids reader now 100)]
    (when-let [run (store.protocol/find-run store run-id)]
      (when-let [task (store.protocol/find-task store (:run/task-id run))]
        (let [adapter (runtime/runtime-adapter-for-run defs-repo run)
              {run-now :run task-now :task} (runtime/ingest-poll-events! env adapter run task)]
          (when-not (state/terminal-run-state? run-now)
            (state/apply-event-stream! store defs-repo run-now task-now now)))))))

(defn process-awaiting-validation!
  [{:keys [reader now store defs-repo] :as env}]
  (doseq [run-id (projection/list-awaiting-validation-run-ids reader now 100)]
    (when-let [run (store.protocol/find-run store run-id)]
      (when-let [task (store.protocol/find-task store (:run/task-id run))]
        (let [{run-now :run task-now :task} (state/apply-event-stream! store defs-repo run task now)]
          (when (and (= :run.state/awaiting-validation (:run/state run-now))
                     (= :task.state/awaiting-validation (:task/state task-now))
                     (:run/artifact-id run-now))
            (validation/assess-run! env run-now task-now)))))))

(defn run-scheduler-step
  [db-path]
  (let [env (scheduler-env db-path)
        {:keys [reader now store defs-repo]} env
        collection-state (shared/ensure-collection-state! store defs-repo now)
        snapshot (projection/load-scheduler-snapshot reader now)
        expired-lease-run-ids (recover-expired-leases! env)
        _ (poll-active-runs! env)
        heartbeat-timeout-run-ids (recover-heartbeat-timeouts! env)
        _ (process-awaiting-validation! env)
        {:keys [created-runs task-errors dispatch-block-reason capacity-skipped-task-ids]}
        (dispatch/dispatch-runnable-tasks! env collection-state snapshot)
        {:keys [requeued-task-ids escalated-task-ids]}
        (retry/process-retryable-failures! env (:snapshot/retryable-failed-task-ids snapshot))]
    {:created-runs created-runs
     :requeued-task-ids requeued-task-ids
     :escalated-task-ids escalated-task-ids
     :expired-lease-run-ids expired-lease-run-ids
     :heartbeat-timeout-run-ids heartbeat-timeout-run-ids
     :dispatch-block-reason dispatch-block-reason
     :capacity-skipped-task-ids capacity-skipped-task-ids
     :task-errors task-errors
     :snapshot snapshot
     :now now
     :collection-state collection-state}))
