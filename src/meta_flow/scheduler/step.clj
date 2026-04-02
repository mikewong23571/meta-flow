(ns meta-flow.scheduler.step
  (:require [meta-flow.control.projection :as projection]
            [meta-flow.defs.loader :as defs.loader]
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

(defn recover-expired-leases!
  [{:keys [reader now store] :as env}]
  (doseq [run-id (projection/list-expired-lease-run-ids reader now 100)]
    (when-let [run (store.protocol/find-run store run-id)]
      (when-let [task (store.protocol/find-task store (:run/task-id run))]
        (runtime/recover-expired-lease! env run task)))))

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

(defn task-error
  [task throwable]
  {:task/id (:task/id task)
   :task/work-key (:task/work-key task)
   :error/message (.getMessage throwable)
   :error/data (ex-data throwable)})

(defn dispatch-runnable-tasks!
  [{:keys [reader now store defs-repo] :as env} collection-state]
  (let [active-count (projection/count-active-runs reader now)
        max-active (or (:resource-policy/max-active-runs
                        (shared/collection-policy defs-repo collection-state))
                       1)
        created-runs (atom [])
        task-errors (atom [])]
    (when-not (:dispatch/paused? (:collection/dispatch collection-state))
      (loop [remaining (max 0 (- max-active active-count))
             runnable-ids (projection/list-runnable-task-ids reader now 100)]
        (when (and (pos? remaining) (seq runnable-ids))
          (let [task-id (first runnable-ids)
                created-run (when-let [task (store.protocol/find-task store task-id)]
                              (when (= :task.state/queued (:task/state task))
                                (try
                                  (let [run (runtime/create-run! env task)]
                                    (swap! created-runs conj run)
                                    run)
                                  (catch Throwable throwable
                                    (swap! task-errors conj (task-error task throwable))
                                    nil))))]
            (recur (if created-run
                     (dec remaining)
                     remaining)
                   (rest runnable-ids))))))
    {:created-runs @created-runs
     :task-errors @task-errors}))

(defn run-scheduler-step
  [db-path]
  (let [env (scheduler-env db-path)
        {:keys [reader now store defs-repo]} env
        collection-state (shared/ensure-collection-state! store defs-repo now)
        snapshot (projection/load-scheduler-snapshot reader now)
        _ (recover-expired-leases! env)
        _ (poll-active-runs! env)
        _ (process-awaiting-validation! env)
        {:keys [created-runs task-errors]} (dispatch-runnable-tasks! env collection-state)
        {:keys [requeued-task-ids escalated-task-ids]}
        (retry/process-retryable-failures! env (:snapshot/retryable-failed-task-ids snapshot))]
    {:created-runs created-runs
     :requeued-task-ids requeued-task-ids
     :escalated-task-ids escalated-task-ids
     :task-errors task-errors
     :snapshot snapshot
     :now now
     :collection-state collection-state}))
