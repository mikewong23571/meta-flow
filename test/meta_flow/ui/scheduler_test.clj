(ns meta-flow.ui.scheduler-test
  (:require [clojure.test :refer [deftest is]]
            [meta-flow.control.projection :as projection]
            [meta-flow.db :as db]
            [meta-flow.scheduler :as scheduler]
            [meta-flow.sql :as sql]
            [meta-flow.ui.scheduler :as ui.scheduler]))

(deftest load-overview-shapes-snapshot-and-collection-data
  (with-redefs [sql/utc-now (fn [] "2026-04-01T00:10:00Z")
                projection/sqlite-projection-reader (fn [db-path]
                                                      (is (= "scheduler.sqlite3" db-path))
                                                      :reader)
                projection/load-scheduler-snapshot (fn [reader now]
                                                     (is (= :reader reader))
                                                     (is (= "2026-04-01T00:10:00Z" now))
                                                     {:snapshot/now now
                                                      :snapshot/dispatch-paused? true
                                                      :snapshot/dispatch-cooldown-active? true
                                                      :snapshot/dispatch-cooldown-until "2026-04-01T00:30:00Z"
                                                      :snapshot/active-run-count 3
                                                      :snapshot/runnable-count 4
                                                      :snapshot/runnable-task-ids ["task-1" "task-2"]
                                                      :snapshot/retryable-failed-count 1
                                                      :snapshot/retryable-failed-task-ids ["task-3"]
                                                      :snapshot/awaiting-validation-count 2
                                                      :snapshot/awaiting-validation-run-ids ["run-4" "run-5"]
                                                      :snapshot/expired-lease-count 1
                                                      :snapshot/expired-lease-run-ids ["run-6"]
                                                      :snapshot/heartbeat-timeout-count 1
                                                      :snapshot/heartbeat-timeout-run-ids ["run-7"]})
                scheduler/inspect-collection! (fn [db-path]
                                                (is (= "scheduler.sqlite3" db-path))
                                                {:collection/dispatch {:dispatch/paused? true}
                                                 :collection/resource-policy-ref {:definition/id "resource-policy/default"
                                                                                  :definition/version 3}
                                                 :collection/updated-at "2026-04-01T00:09:00Z"})]
    (is (= {:snapshot {:now "2026-04-01T00:10:00Z"
                       :dispatch-paused? true
                       :dispatch-cooldown-active? true
                       :dispatch-cooldown-until "2026-04-01T00:30:00Z"
                       :active-run-count 3
                       :runnable-count 4
                       :runnable-task-ids ["task-1" "task-2"]
                       :retryable-failed-count 1
                       :retryable-failed-task-ids ["task-3"]
                       :awaiting-validation-count 2
                       :awaiting-validation-run-ids ["run-4" "run-5"]
                       :expired-lease-count 1
                       :expired-lease-run-ids ["run-6"]
                       :heartbeat-timeout-count 1
                       :heartbeat-timeout-run-ids ["run-7"]}
            :collection {:dispatch {:dispatch/paused? true}
                         :resource-policy-ref {:definition/id "resource-policy/default"
                                               :definition/version 3}
                         :updated-at "2026-04-01T00:09:00Z"}}
           (ui.scheduler/load-overview "scheduler.sqlite3")))))

(deftest load-overview-and-detail-loaders-use-public-scheduler-facade
  (let [calls (atom [])]
    (with-redefs [db/default-db-path "default.sqlite3"
                  projection/sqlite-projection-reader (fn [db-path]
                                                        (swap! calls conj [:reader db-path])
                                                        :reader)
                  projection/load-scheduler-snapshot (fn [_ now]
                                                       {:snapshot/now now})
                  sql/utc-now (fn [] "2026-04-01T00:10:00Z")
                  scheduler/inspect-collection! (fn [db-path]
                                                  (swap! calls conj [:collection db-path])
                                                  {:collection/dispatch {}
                                                   :collection/resource-policy-ref nil
                                                   :collection/updated-at "2026-04-01T00:09:00Z"})
                  scheduler/inspect-task! (fn [db-path task-id]
                                            (swap! calls conj [:task db-path task-id])
                                            {:task/id task-id})
                  scheduler/inspect-run! (fn [db-path run-id]
                                           (swap! calls conj [:run db-path run-id])
                                           {:run/id run-id})]
      (is (= {:snapshot {:now "2026-04-01T00:10:00Z"
                         :dispatch-paused? nil
                         :dispatch-cooldown-active? nil
                         :dispatch-cooldown-until nil
                         :active-run-count nil
                         :runnable-count nil
                         :runnable-task-ids nil
                         :retryable-failed-count nil
                         :retryable-failed-task-ids nil
                         :awaiting-validation-count nil
                         :awaiting-validation-run-ids nil
                         :expired-lease-count nil
                         :expired-lease-run-ids nil
                         :heartbeat-timeout-count nil
                         :heartbeat-timeout-run-ids nil}
              :collection {:dispatch {}
                           :resource-policy-ref nil
                           :updated-at "2026-04-01T00:09:00Z"}}
             (ui.scheduler/load-overview)))
      (is (= {:task/id "task-42"}
             (ui.scheduler/load-task "task-42")))
      (is (= {:task/id "task-99"}
             (ui.scheduler/load-task "scheduler.sqlite3" "task-99")))
      (is (= {:run/id "run-42"}
             (ui.scheduler/load-run "run-42")))
      (is (= {:run/id "run-99"}
             (ui.scheduler/load-run "scheduler.sqlite3" "run-99"))))
    (is (= [[:reader "default.sqlite3"]
            [:collection "default.sqlite3"]
            [:task "default.sqlite3" "task-42"]
            [:task "scheduler.sqlite3" "task-99"]
            [:run "default.sqlite3" "run-42"]
            [:run "scheduler.sqlite3" "run-99"]]
           @calls))))
