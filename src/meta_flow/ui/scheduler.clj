(ns meta-flow.ui.scheduler
  (:require [meta-flow.control.projection :as projection]
            [meta-flow.db :as db]
            [meta-flow.scheduler :as scheduler]
            [meta-flow.sql :as sql]))

(defn- collection-updated-at
  [collection]
  (:collection/updated-at collection))

(defn load-overview
  ([] (load-overview db/default-db-path))
  ([db-path]
   (let [now (sql/utc-now)
         reader (projection/sqlite-projection-reader db-path)
         snapshot (projection/load-scheduler-snapshot reader now)
         collection (scheduler/inspect-collection! db-path)]
     {:snapshot {:now (:snapshot/now snapshot)
                 :dispatch-paused? (:snapshot/dispatch-paused? snapshot)
                 :dispatch-cooldown-active? (:snapshot/dispatch-cooldown-active? snapshot)
                 :dispatch-cooldown-until (:snapshot/dispatch-cooldown-until snapshot)
                 :active-run-count (:snapshot/active-run-count snapshot)
                 :runnable-count (:snapshot/runnable-count snapshot)
                 :runnable-task-ids (:snapshot/runnable-task-ids snapshot)
                 :retryable-failed-count (:snapshot/retryable-failed-count snapshot)
                 :retryable-failed-task-ids (:snapshot/retryable-failed-task-ids snapshot)
                 :awaiting-validation-count (:snapshot/awaiting-validation-count snapshot)
                 :awaiting-validation-run-ids (:snapshot/awaiting-validation-run-ids snapshot)
                 :expired-lease-count (:snapshot/expired-lease-count snapshot)
                 :expired-lease-run-ids (:snapshot/expired-lease-run-ids snapshot)
                 :heartbeat-timeout-count (:snapshot/heartbeat-timeout-count snapshot)
                 :heartbeat-timeout-run-ids (:snapshot/heartbeat-timeout-run-ids snapshot)}
      :collection {:dispatch (:collection/dispatch collection)
                   :resource-policy-ref (:collection/resource-policy-ref collection)
                   :updated-at (collection-updated-at collection)}})))

(defn load-task
  ([task-id]
   (load-task db/default-db-path task-id))
  ([db-path task-id]
   (scheduler/inspect-task! db-path task-id)))

(defn load-run
  ([run-id]
   (load-run db/default-db-path run-id))
  ([db-path run-id]
   (scheduler/inspect-run! db-path run-id)))
