(ns meta-flow.scheduler.dev
  (:require [clojure.java.io :as io]
            [meta-flow.control.events :as events]
            [meta-flow.defs.loader :as defs.loader]
            [meta-flow.defs.protocol :as defs.protocol]
            [meta-flow.scheduler.shared :as shared]
            [meta-flow.scheduler.validation :as validation]
            [meta-flow.store.protocol :as store.protocol]
            [meta-flow.store.sqlite :as store.sqlite]))

(def max-demo-scheduler-steps
  20)

(def demo-runtime-profile-ref
  {:definition/id :runtime-profile/mock-worker
   :definition/version 1})

(defn build-demo-task
  [defs-repo {:keys [task-id work-key runtime-profile-ref]
              :or {runtime-profile-ref demo-runtime-profile-ref}}]
  (let [task-type (defs.protocol/find-task-type-def defs-repo :task-type/cve-investigation 1)]
    {:task/id (or task-id (str "task-" (shared/new-id)))
     :task/work-key (or work-key (str "CVE-2024-12345-" (subs (shared/new-id) 0 8)))
     :task/task-type-ref {:definition/id (:task-type/id task-type)
                          :definition/version (:task-type/version task-type)}
     :task/task-fsm-ref (:task-type/task-fsm-ref task-type)
     :task/run-fsm-ref (:task-type/run-fsm-ref task-type)
     :task/runtime-profile-ref runtime-profile-ref
     :task/artifact-contract-ref (:task-type/artifact-contract-ref task-type)
     :task/validator-ref (:task-type/validator-ref task-type)
     :task/resource-policy-ref (:task-type/resource-policy-ref task-type)
     :task/state :task.state/queued
     :task/created-at (shared/now)
     :task/updated-at (shared/now)}))

(defn happy-path-complete?
  [task run]
  (and (= :task.state/completed (:task/state task))
       (= :run.state/finalized (:run/state run))))

(defn retry-path-complete?
  [task run]
  (and (= :task.state/retryable-failed (:task/state task))
       (= :run.state/retryable-failed (:run/state run))))

(defn enqueue-demo-task!
  ([db-path]
   (enqueue-demo-task! db-path {}))
  ([db-path options]
   (let [store (store.sqlite/sqlite-state-store db-path)
         defs-repo (defs.loader/filesystem-definition-repository)
         now-value (shared/now)
         _ (shared/ensure-collection-state! store defs-repo now-value)
         existing-task (when-let [work-key (:work-key options)]
                         (store.protocol/find-task-by-work-key store work-key))
         task (or existing-task
                  (store.protocol/enqueue-task! store
                                                (build-demo-task defs-repo options)))]
     {:task task
      :reused? (boolean existing-task)})))

(defn demo-happy-path!
  [db-path run-scheduler-step-fn]
  (let [store (store.sqlite/sqlite-state-store db-path)
        task (:task (enqueue-demo-task! db-path))]
    (loop [step 0]
      (let [task-now (store.protocol/find-task store (:task/id task))
            run-now (shared/latest-run store (:task/id task))]
        (cond
          (happy-path-complete? task-now run-now)
          {:task task-now
           :run run-now
           :artifact-root (shared/run-artifact-root store run-now)
           :scheduler-steps step}

          (>= step max-demo-scheduler-steps)
          (throw (ex-info "Demo happy path did not converge within the expected scheduler steps"
                          {:task task-now
                           :run run-now
                           :scheduler-steps step}))

          :else
          (do
            (run-scheduler-step-fn db-path)
            (recur (inc step))))))))

(defn demo-retry-path!
  [db-path run-scheduler-step-fn]
  (let [store (store.sqlite/sqlite-state-store db-path)
        task (:task (enqueue-demo-task! db-path))]
    (loop [step 0
           tampered? false]
      (let [task-now (store.protocol/find-task store (:task/id task))
            run-now (shared/latest-run store (:task/id task))
            artifact-root (some->> run-now
                                   (shared/run-artifact-root store))]
        (cond
          (retry-path-complete? task-now run-now)
          {:task task-now
           :run run-now
           :artifact-root artifact-root
           :assessment (store.protocol/find-assessment-by-key store (:run/id run-now) validation/current-assessment-key)
           :disposition (store.protocol/find-disposition-by-key store (:run/id run-now) validation/current-disposition-key)
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
            (run-scheduler-step-fn db-path)
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
               :run/artifact-root (shared/run-artifact-root store run)
               :run/event-count (count event-list)
               :run/last-heartbeat heartbeat))
      (throw (ex-info (str "Run not found: " run-id) {:run-id run-id})))))

(defn inspect-collection!
  [db-path]
  (let [store (store.sqlite/sqlite-state-store db-path)]
    (or (store.protocol/find-collection-state store :collection/default)
        {:collection/dispatch {:dispatch/paused? false}
         :collection/resource-policy-ref {:definition/id :resource-policy/default
                                          :definition/version 3}})))
