(ns meta-flow.scheduler.dev.inspect
  (:require [meta-flow.control.events :as events]
            [meta-flow.scheduler.shared :as shared]
            [meta-flow.store.protocol :as store.protocol]
            [meta-flow.store.sqlite :as store.sqlite]))

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
        {:collection/dispatch {:dispatch/paused? false
                               :dispatch/cooldown-until nil}
         :collection/resource-policy-ref {:definition/id :resource-policy/default
                                          :definition/version 3}})))
